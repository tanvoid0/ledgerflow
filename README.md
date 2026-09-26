# LedgerFlow

A double-entry payment ledger: a payment places an authorisation hold, then
captures or refunds it, run across eight services that talk only by events.
No distributed transaction anywhere, and no path by which the risk model that
scores every payment can move money.

Java 25 · Spring Boot 4.1 · Maven multi-module · PostgreSQL 17 · Redpanda · Redis · OpenTelemetry + Grafana LGTM · k6 · Docker

## Walkthrough

One command to a fraud case narrated by a local model, in ninety seconds: `docs/walkthrough.md`.

## Measured, not claimed

Open model (k6 constant-arrival-rate), one machine (Ryzen 9 9950X, 32 threads),
Postgres 17, Redpanda and Redis in Docker Desktop, eight services as local JVMs —
except the last four rows, which run on the kind cluster (three nodes, two replicas
of every service) and compare only with each other.
Every raw run is in `docs/perf/`; `perf/compare.sh <before> <after>` reproduces
any row. Each row below is one change against the row above it. A payment is
timed from the POST to its saga row reaching Captured (`perf/settled.sh`), not
to the 202, which takes 5ms at every load and says nothing.

| path / change | load | p50 ms | p99 ms | failed |
|---|---|---:|---:|---:|
| Transfer, naive SUM balance | 100/s | 5 | 8 | 0% |
| Transfer, materialised balance + atomic debit | 100/s | 5 | 8 | 0% |
| Hold, both services healthy | 50/s | 7 | 15 | 0% |
| Hold, account frozen, no timeout | 50/s | 30003 | 30008 | 100% |
| Hold, account frozen, 800ms timeout + cached fallback | 50/s | 5 | 809 | 0% |
| Hold, account frozen, + 2 retries | 50/s | 407 | 2012 | 0% |
| Payment settled, baseline: one outbox batch per 500ms tick, one offset commit per record | 100/s | 15750 | 41478 | 88% hit the 15s deadline |
| Payment, outbox drains until a batch comes back short | 100/s | 2770 | 29351 | 0% |
| Payment, one offset commit per poll (`manual`, not `manual_immediate`) | 100/s | 2667 | 3663 | 0% |
| Payment, outbox tick 500ms -> 50ms | 100/s | 378 | 1452 | 0% |
| Payment, virtual threads on payment-service (not kept) | 100/s | 417 | 5335 | 0% |
| Payment, `synchronous_commit = off` (not kept: a ledger does not trade durability for 1s of tail) | 100/s | 300 | 390 | 0% |
| Payment, the kept path, at 150/s | 150/s | 6791 | 39299 | 16% hit the deadline |
| Payment, the kept path, at 200/s | 200/s | 17717 | 44996 | 63% hit the deadline |
| Payment with risk-service scoring every one beside it (`risk.score` p99 0.99ms; A/B off/on: within run-to-run spread) | 100/s | 391 | 996 | 0% |
| Payment settled, kind cluster, two replicas per service | 100/s | 243 | 378 | 0% |
| Payment settled, cluster, four remote batch workers deployed beside it | 100/s | 247 | 360 | 0% |
| Payment settled, cluster, a JWT checked on every request | 100/s | 260 | 472 | 0% |
| Payment settled, cluster, Tomcat 11.0.24 -> 11.0.26 (the image scanner's first finding) | 100/s | 265 | 449 | 0% |

Startup, account-service, host JVM: plain 2.82 s -> AOT cache 1.07 s, median of 3 (`scripts/startup-time.sh`).

The outbox poll, not the database, was the authorisation path: seven outbox
hops at 500ms each put 1.75s of waiting into the mean payment, and one batch
of 100 per tick capped every service at 200 rows a second, above which the
backlog only grew until the deadline failed every payment. The ceiling is now
just above 100/s, and what holds it there is fifteen fsyncs per payment on one
Docker disk plus the one TREASURY row every capture credits. Where the trace and
the JFR pointed, what each database ran (`pg_stat_statements`), and the runs
that moved nothing: `docs/measurements/step-15-authorisation-path.md`.
`perf/bench.sh stepNN` runs the same suite at the end of every step and
`docs/perf/README.md` (generated) charts the checkpoints, so the cost of each
added service is a number, not a feeling. Why k6: `docs/adr/0003-k6-over-jmeter.md`.
Write-ups in `docs/measurements/`. What this system guarantees and what
enforces each guarantee: "The properties, and where they are enforced" below.

## What exists today

| service | port | owns |
|---|---|---|
| account-service | 8080 | accounts, wallets, the book of record (journal entries and postings). The only service that moves money. Every entry leaves as `account.EntryPosted`. |
| ledger-service | 8081 | funds holds. Asks account whether a wallet exists before reserving against it, writes the hold and its `ledger.FundsHeld` event in one transaction; a poller moves the event to Kafka. Release and capture leave as `ledger.HoldClosed`. |
| notification-service | 8082 | the record of what it sent, and which events it has already handled. Consumes `ledger.FundsHeld`; a replay of the topic sends nothing twice. Acks after the commit, retries on `.<group>.retry-*` topics, dead-letters on `.<group>.dlt`. |
| balance-service | 8083 | the available balance: account's balance less ledger's open holds, per wallet, in Redis. A projection of the three events above; owns no truth, and `scripts/rebuild-balance.sh` proves it by deleting it. |
| payment-service | 8085 | the workflow. `POST /api/v1/payments` starts a saga: reserve (ledger), authorize (issuer), issue (settlement). Owns the saga state and a per-step deadline; nothing else. |
| issuer-service | 8086 | a stub card issuer: authorizes everything except an amount of exactly 1, refunds on request. |
| settlement-service | 8087 | captures: moves the held money through account-service (`Idempotency-Key` per hold) and remembers each capture so it can revoke it. |
| risk-service | 8084 | scores every payment for fraud risk off the event stream; rules veto and the score only ranks a payment to review, and its Postgres role cannot connect to any database but its own. |

Three shared libraries: `ledgerflow-events` (Money, WalletRef, EventEnvelope, the
event and command records and their JSON schemas - no behaviour), `ledgerflow-starter-web`
(the request-id filter, and traces, metrics and logs shipped over OTLP, as Boot auto-configurations)
and `ledgerflow-starter-messaging` (the outbox poller, the inbox that dedupes on eventId, one
retry policy for every listener; the outbox row carries the trace it was written in).

## A workflow across services

Reserve, authorize, issue: three services, three databases, no transaction spanning them.
Payment-service holds the workflow in one pure function, `PaymentSaga.on(state, reply)`,
over a sealed `PaymentState`: add a state and the switch stops compiling until it is handled.
Every step has a deadline; a sweeper feeds `StepTimedOut` through the same function, so a
timeout is not a special case. Every failure ends in a terminal state with the wallets
released, the authorization refunded and the captures revoked - in that order, and only the
ones that could have happened. A reply that arrives after the payment has already failed is
absorbed, and the compensation already sent covers whatever the late service did.
Messages: `docs/events/payment-saga.md`. All three failures and the late reply, live:
`docs/measurements/step-12-saga.md`. Every path: `PaymentSagaTest`, `PaymentFlowIT`.

## One payment is one trace

`grafana/otel-lgtm` runs next to the stack; every service pushes traces, metrics and logs
to it. A payment is one trace of 48 spans across seven services, both directions of every
Kafka hop included, from the POST to the last `HoldClosed` reaching the read model - with the
time between hops measured rather than guessed: 2.4s of a 3.2s payment was outbox polling,
which is the number step 15 went after.
The two Kafka observation flags alone did not do that: the outbox poller sends on its own
thread, so every trace used to end at the outbox. Now `OutboxAppender` stores the current
span in the row and `OutboxPublisher` restores it around the send. Every log line carries
`trace_id`, `requestId` and (in payment) `paymentId`, so Grafana walks from a span to its lines
and a Loki query by payment finds the whole story. The metrics worth an alert:
`ledgerflow_dead_letters_total` (zero is the only acceptable value),
`kafka_consumer_fetch_manager_records_lag_max`, `ledgerflow_saga_compensated_total{step,reason}`.
The traces, queries and thresholds: `docs/measurements/step-14-observability.md`.

## A read model that can be thrown away

Nobody could answer "how much can this wallet spend": the balance lives in account, the
holds in ledger. balance-service answers it from a Redis hash per account, built by
summing `EntryPosted` lines into the balance and `FundsHeld` / `HoldClosed` into the held
amount. Sums commute, so it needs no ordering; one Lua script marks the event id and
applies every line in one step, so a redelivery changes nothing and a crash leaves no
half-applied event. The outbox migrations backfill the history before them (opening
balances included), so the topic is complete from its first record, and
`scripts/rebuild-balance.sh` wipes Redis, rewinds the group and rebuilds: 3,000 events
in the time it takes to start a JVM (`docs/measurements/step-13-rebuild.md`, which also
shows the read model catching the hold step 10 lost). A user who just wrote reads their
own write with `?after=<X-Request-Id>`: the reason is `docs/adr/0002-read-your-own-writes.md`.

## Events are contracts

The first event: `ledger.FundsHeld` on `ledgerflow.ledger.wallet-hold.events.v1`,
keyed by hold id, wrapped in an envelope (eventId, aggregateVersion, correlationId
= the caller's `X-Request-Id`). The written contract is `docs/events/ledger.FundsHeld.md`;
the schema next to the record is registered in Redpanda's schema registry with
BACKWARD compatibility, and `scripts/check-schemas.sh` refuses a change an
existing consumer would not survive (CI runs it on every PR that touches a schema).

The first version of this event was deliberately naive. `docs/events/README.md`
lists what was wrong with it and ticks items off as later steps fix them.

Consuming it: offsets move only when the listener says so, after the work.
A listener that throws gets three more attempts from retry topics (1s, 3s, 9s)
while the main partition keeps moving; after that, or straight away for data
that will never parse, the record lands in `…events.v1.notification-service.dlt` with the exception
in its headers. `scripts/dlt-depth.sh` says how many are there (anything above
zero is an incident), `scripts/dlt-replay.sh` puts them back once the cause is
fixed. Kill-the-consumer proof in `docs/measurements/step-09-consumer-restart.md`.

Producing it: the hold and its event are committed together. The event is a
row in ledger's `outbox` table, written inside the hold's transaction; a
scheduled poller claims unpublished rows (`FOR UPDATE SKIP LOCKED`, so a
second instance can run), waits for the broker's ack and marks them. Broker
down: the rows wait. Hold rolled back: the row was never there. Why polling
and not Debezium: `docs/adr/0001-outbox-over-cdc.md`. Kill-the-broker proof,
before and after, in `docs/measurements/step-10-dual-write.md`.

## The properties, and where they are enforced

The overdraft race: 50 concurrent 80.00 debits against a wallet holding 100.00.
Naive code created ten and left the wallet at -700.00; one conditional UPDATE
and a CHECK constraint bring it to exactly one. `perf/race.sh` reproduces it,
`TransferConcurrencyIT` fails if the fix is ever removed.

| property | enforced by | proved by |
|---|---|---|
| Every entry sums to zero | `JournalEntry` compact constructor | `JournalEntryTest` |
| No transfer applies twice | Idempotency-Key lookup + UNIQUE index | `TransferControllerTest`, live retry |
| No wallet overdrawn under concurrency | conditional UPDATE + CHECK constraint | `TransferConcurrencyIT` (`-DexcludedGroups=none`) |
| A slow dependency cannot take a service down | timeouts, retry in its own bean, fallback | `docs/measurements/step-06-cascade.md` |
| A consumer crash loses nothing; a bad message blocks nothing | manual ack after the work, `@RetryableTopic` + DLT | `FundsHoldListenerIT`, `docs/measurements/step-09-consumer-restart.md` |
| A broker outage loses no event; a rolled-back hold publishes none | transactional outbox, poller marks rows only after the ack | `PlaceHoldIT`, `docs/measurements/step-10-dual-write.md` |
| Every failed payment ends terminal with its wallets released | sealed state + exhaustive transition, per-step deadline and sweeper, idempotent compensations | `PaymentSagaTest`, `PaymentFlowIT`, `docs/measurements/step-12-saga.md` |
| The balance view is disposable, and a user sees their own write | projection of events only, atomic mark-and-apply in Lua, outbox backfills, bounded wait on the request id | `ProjectionIT`, `PostTransferIT`, `docs/measurements/step-13-rebuild.md` |
| A model can flag a payment and cannot move money | rules veto, score ranks, `risk` role cannot connect to the ledger, ArchUnit | `ArchitectureTest`, `RiskRoleIT`, `docs/measurements/step-16-risk.md` |
| Events for one wallet arrive in order | partition key = the wallet, not the hold; unkeyed sends measured and reverted | `PartitionKeyTest`, `PlaceHoldIT`, `docs/measurements/step-18-partitions.md` |
| A consumer restart moves no partition | cooperative-sticky assignor + static membership in every consumer | `docs/measurements/step-19-groups.md` |
| The balance model rebuilds from a snapshot, not from history | compacted `ledgerflow.balance.snapshots.v1`, seeded then resumed from its source offsets | `docs/measurements/step-20-compaction.md` |
| A broker dies mid-stream and nothing is lost | rf 3 on every topic under `scripts/cluster3.sh`, `acks=all`, the outbox holds what the cluster will not take | `docs/measurements/step-21-broker.md` |
| A business day settles once | Spring Batch job instance = the date; a second run is a 409; both steps are idempotent upserts | `FeeTest`, `SettlementJobIT`, `docs/measurements/step-22-batch.md` |
| Three jobs, one timetable, and a second replica doubles it | chunk to file, tasklet, `@Scheduled` per JVM; `runAt` vs `businessDate` decides whether a run may repeat | `SettlementJobIT`, `docs/measurements/step-23-schedule.md` |
| A feed goes down, a row is wrong, a pod dies - the run finishes anyway | retry policy with includes/excludes, skip policy with a limit and a reject table, restart by execution id, recover for a killed JVM | `SettlementFaultToleranceTest`, `docs/measurements/step-24-failures.md` |
| One script, a clean machine, the whole system in containers | Paketo images from the pom, `${ENV:default}` everywhere, one compose file, the same image as a one-shot job with an exit code | `scripts/demo-compose.sh`, `docs/measurements/step-25-containers.md` |
| The cluster owns the timetable | kind + kustomize, three probes that mean three things, CronJobs with Forbid, a stranded execution recovered from a command line with the service scaled to zero | `scripts/k8s-up.sh`, `docs/measurements/step-26-kubernetes.md` |
| A shared cursor reader races under threads; a partitioned step does not | `JdbcPagingItemReader` for a safe shared reader, `IdRangePartitioner` for one range and one reader per worker, restart re-runs only the failed partitions | `docs/measurements/step-27-batch-throughput.md` |
| One job branches on the day's outcome, routes to two tables, runs two flows at once, and stops on request instead of dying | exit-status classification + `on()`/`to()` transitions, a `JobExecutionDecider` for month end, `ClassifierCompositeItemWriter`, a split of two independent steps, `JobOperator.stop()` | `docs/measurements/step-28-job-graph.md` |
| A batch step scales past one JVM's cores by dispatching to a pool of pods instead of reading itself | `RemotePartitioningManagerStepBuilder` polling `batch_step_execution`, partitions keyed by step execution id on a Kafka topic, workers as their own scalable Deployment | `docs/measurements/step-29-remote-workers.md` |
| No token means no API - a token without ledger-write cannot move money, ledger and settlement authenticate to account as themselves | shared resource-server auto-configuration, `@PreAuthorize("hasRole('ledger-write')")` on the transfer use case, client-credentials interceptor per service | `TransferControllerTest`, `docs/measurements/step-31-locked-doors.md` |
| Every service names its own build and git commit, ships its own SBOM, and a CRITICAL finding fails the build | `build-info` + `git-commit-id` on `/actuator/info`, CycloneDX on `/actuator/sbom`, `trivy sbom` in CI, `trivy image` on a schedule | `docs/measurements/step-32-what-we-ship.md` |

## Run it

```bash
./scripts/demo-compose.sh   # everything in containers: images from the pom, compose --wait, topics + schemas, three scenarios
./scripts/demo.sh    # compose --wait, topics + schema registration, build, start all eight, run three scenarios - a few minutes on a warm Maven cache (host JVMs - what the perf numbers are taken on)
./scripts/stop-services.sh
docker compose -f infra/compose/docker-compose.yml -f infra/compose/docker-compose.app.yml stop

./scripts/k8s-up.sh    # a kind cluster instead: images from the pom, kind load, kustomize apply (now including the settlement worker pool the nightly job's manager profile dispatches partitions to), the four CronJobs, topics + schemas, Keycloak on 8180 (25 pods) - refuses while the compose infra above is still up (same host ports)
./scripts/k8s-down.sh  # kind delete cluster - gives the host ports back

TOKEN=$(scripts/token.sh ops)   # every /api route wants one now; scripts/token.sh mints one, good for 300s
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/accounts | jq
curl -s -X POST -H "Authorization: Bearer $TOKEN" localhost:8081/api/v1/holds -H 'content-type: application/json' \
  -d '{"accountId":"11111111-1111-1111-1111-111111111111","wallets":["A-12"],"amountMinor":4500,"currency":"GBP"}' | jq
PAYMENT=$(curl -s -X POST -H "Authorization: Bearer $TOKEN" localhost:8085/api/v1/payments -H 'content-type: application/json'   -d '{"accountId":"11111111-1111-1111-1111-111111111111","wallets":["A-13"],"amountMinor":4500,"currency":"GBP"}' | jq -r .paymentId)
sleep 3; curl -s -H "Authorization: Bearer $TOKEN" localhost:8085/api/v1/payments/$PAYMENT | jq       # Captured. amountMinor 1: declined. 20000: capture fails. freeze.sh 8086: times out.
curl -s -H "Authorization: Bearer $TOKEN" localhost:8083/api/v1/balances/11111111-1111-1111-1111-111111111111 | jq '.wallets[] | select(.label=="A-13")'   # balance 55.00, held 0
```

See it: http://localhost:3000 (admin / admin), Explore -> Tempo, search by service `payment-service`, open the trace; "Logs for this span" jumps to Loki.
Throw the balance view away and watch it come back: `scripts/rebuild-balance.sh`.
Read your own write: `curl -si -H "Authorization: Bearer $TOKEN" localhost:8083/api/v1/balances/<account>/A-12?after=<the X-Request-Id a POST answered with>`.
Score a burst without moving any money: `scripts/replay-fraud.sh` - 30 payments from one account plus one
blocked beneficiary, watched into `risk_decision` as REVIEW and BLOCK rows with a first case note, saga
states in payment-service untouched throughout.

Load and race: `perf/bench.sh step15` (the fixed suite, ~6 min), `RATE=100 DURATION=60s perf/run.sh transfer my-label` (one scenario), `perf/race.sh`.
Watch the events: `docker exec ledgerflow-redpanda rpk topic consume ledgerflow.ledger.wallet-hold.events.v1 -f '%p %k %v\n'`.
Break one: `printf 'poison\t{not json\n' | docker exec -i ledgerflow-redpanda rpk topic produce ledgerflow.ledger.wallet-hold.events.v1 -f '%k\t%v\n'`, then `scripts/dlt-depth.sh`.
Freeze a service to watch the cascade: `scripts/freeze.sh 8080`, `scripts/freeze.sh 8080 --thaw`.
Replay the topic and watch nothing move: stop notification, `docker exec ledgerflow-redpanda rpk group seek notification-service --to start` (the group must be empty first, 30s after a kill or a clean stop under static membership), start it, `docker exec ledgerflow-postgres psql -U ledgerflow -d notification -c 'SELECT count(*) FROM sent_notifications'` before and after.
Kill the broker and place a hold: `docker stop ledgerflow-redpanda`, then `docker exec ledgerflow-postgres psql -U ledgerflow -d ledger -c 'SELECT count(*) FROM outbox WHERE published_at IS NULL'` before and after `docker start ledgerflow-redpanda`.
The compose above is one broker, on purpose: nothing to replicate, nothing to lose - `scripts/cluster3.sh up` trades a slower start and 3x the storage per topic for surviving one dead node; `docs/measurements/step-21-broker.md`.

Know what you ship: `curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/actuator/info | jq` (build version, git commit, dirty flag), `curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/actuator/sbom/application | jq '.components | length'`, and whether Postgres is really in there: `curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/actuator/sbom/application | jq '[.components[] | select(.name == "postgresql")] | length'`. The gate that gave that SBOM a reason to exist: `.github/workflows/ci.yml` fails a PR on a CRITICAL finding in any service's own jar; `.github/workflows/image-scan.yml` scans the built image itself on push to main and every Monday.
