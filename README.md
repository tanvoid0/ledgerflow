# LedgerFlow

A double-entry payment ledger with authorisation holds, being built across
services that talk by events. No distributed transaction anywhere.

Java 25 · Spring Boot 4.1 · Maven multi-module · PostgreSQL 17 · k6 · Docker

## What exists today

| service | port | owns |
|---|---|---|
| account-service | 8080 | accounts, wallets, the book of record (journal entries and postings). The only service that moves money. |
| ledger-service | 8081 | funds holds. Asks account whether a wallet exists before reserving against it, writes the hold and its `ledger.FundsHeld` event in one transaction; a poller moves the event to Kafka. |
| notification-service | 8082 | the record of what it sent, and which events it has already handled. Consumes `ledger.FundsHeld`; a replay of the topic sends nothing twice. Acks after the commit, retries on `.retry-*` topics, dead-letters on `.dlt`. |

Two shared libraries: `ledgerflow-events` (Money, WalletRef, EventEnvelope, the
event records and their JSON schemas - no behaviour) and `ledgerflow-starter-web`
(the request-id filter as a Boot auto-configuration).

## Events are contracts

One event so far: `ledger.FundsHeld` on `ledgerflow.ledger.wallet-hold.events.v1`,
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
that will never parse, the record lands in `…events.v1.dlt` with the exception
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

## Measured, not claimed

Open model (k6 constant-arrival-rate), single node, Postgres 17 in Docker.
Every raw run is in `docs/perf/`; `perf/compare.sh <before> <after>` reproduces
any row. Write-ups in `docs/measurements/`.

| path / change | load | p50 | p99 | failed |
|---|---|---:|---:|---:|
| Transfer, naive SUM balance | 100/s | 5 | 8 | 0% |
| Transfer, materialised balance + atomic debit | 100/s | 5 | 8 | 0% |
| Hold, both services healthy | 50/s | 7 | 15 | 0% |
| Hold, account frozen, no timeout | 50/s | 30003 | 30008 | 100% |
| Hold, account frozen, 800ms timeout + cached fallback | 50/s | 5 | 809 | 0% |
| Hold, account frozen, + 2 retries | 50/s | 407 | 2012 | 0% |

The overdraft race: 50 concurrent 80.00 debits against a wallet holding 100.00.
Naive code created ten and left the wallet at -700.00; one conditional UPDATE
and a CHECK constraint bring it to exactly one. `perf/race.sh` reproduces it,
`TransferConcurrencyIT` fails if the fix is ever removed.

## The properties, and where they are enforced

| property | enforced by | proved by |
|---|---|---|
| Every entry sums to zero | `JournalEntry` compact constructor | `JournalEntryTest` |
| No transfer applies twice | Idempotency-Key lookup + UNIQUE index | `TransferControllerTest`, live retry |
| No wallet overdrawn under concurrency | conditional UPDATE + CHECK constraint | `TransferConcurrencyIT` (`-DexcludedGroups=none`) |
| A slow dependency cannot take a service down | timeouts, retry in its own bean, fallback | `docs/measurements/step-06-cascade.md` |
| A consumer crash loses nothing; a bad message blocks nothing | manual ack after the work, `@RetryableTopic` + DLT | `FundsHoldListenerIT`, `docs/measurements/step-09-consumer-restart.md` |
| A broker outage loses no event; a rolled-back hold publishes none | transactional outbox, poller marks rows only after the ack | `PlaceHoldIT`, `docs/measurements/step-10-dual-write.md` |

## Run it

```bash
docker compose -f infra/compose/docker-compose.yml up -d      # Postgres 5433, Redpanda 9092, schema registry 18081
docker exec ledgerflow-redpanda rpk topic create ledgerflow.ledger.wallet-hold.events.v1 -p 3   # retry and dlt topics create themselves
scripts/check-schemas.sh --register                           # put the event schemas in the registry
./mvnw -T 1C clean install                                    # builds everything, runs the tests
./mvnw -pl services/account-service spring-boot:run           # terminal 1
./mvnw -pl services/ledger-service spring-boot:run            # terminal 2
./mvnw -pl services/notification-service spring-boot:run      # terminal 3: watch it for "emailed the customer"

curl -s localhost:8080/api/v1/accounts | jq
curl -s -X POST localhost:8081/api/v1/holds -H 'content-type: application/json' \
  -d '{"accountId":"11111111-1111-1111-1111-111111111111","wallets":["A-12"],"amountMinor":4500,"currency":"GBP"}' | jq
```

Load and race: `RATE=100 DURATION=60s perf/run.sh transfer baseline`, `perf/race.sh`.
Watch the events: `docker exec ledgerflow-redpanda rpk topic consume ledgerflow.ledger.wallet-hold.events.v1 -f '%p %k %v\n'`.
Break one: `printf 'poison\t{not json\n' | docker exec -i ledgerflow-redpanda rpk topic produce ledgerflow.ledger.wallet-hold.events.v1 -f '%k\t%v\n'`, then `scripts/dlt-depth.sh`.
Freeze a service to watch the cascade: `scripts/freeze.sh 8080`, `scripts/freeze.sh 8080 --thaw`.
Replay the topic and watch nothing move: stop notification, `docker exec ledgerflow-redpanda rpk group seek notification-service --to start` (the group must be empty first, 45s after a kill), start it, `docker exec ledgerflow-postgres psql -U ledgerflow -d notification -c 'SELECT count(*) FROM sent_notifications'` before and after.
Kill the broker and place a hold: `docker stop ledgerflow-redpanda`, then `docker exec ledgerflow-postgres psql -U ledgerflow -d ledger -c 'SELECT count(*) FROM outbox WHERE published_at IS NULL'` before and after `docker start ledgerflow-redpanda`.
