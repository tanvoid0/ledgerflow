# Step 15: the authorisation path, measured before it was touched

Seven services as local JVMs (`java -jar`, C2), Postgres 17, Redpanda and Redis in
Docker Desktop, one machine: Ryzen 9 9950X, 32 threads, Windows 11. Load is
`perf/k6/payments.js`: 20/s for 30s to warm up, then 100/s for 180s against
`POST /api/v1/payments`, 18,000 payments a run, wallets funded from treasury in
`setup()` so the run never measures the insufficient-funds path by accident.

Two numbers per run. k6 times the POST, which answers 202 in 5ms at every load
and at every stage below, so it is a number about one INSERT. `perf/settled.sh`
reads the other one from payment's database afterwards: `created_at` to
`updated_at` of the saga row, one clock, Captured or Failed. Every table below
is the settled number unless it says POST.

## Why 100/s and not the 200/s the step asked for

| run | settled p50 | p99 | captured | failed |
|---|---:|---:|---:|---:|
| `baseline-200` | 15.9s | 34.7s | 792 | 35,216 |
| `outbox-drains-200` | 16.1s | 41.7s | 4,386 | 31,623 |

At 200/s the unfixed path collapses to the 15s step deadline and stays collapsed
after the first fix: nothing a row can show. At 100/s the baseline collapses too,
and each fix is visible. The rate was chosen as the one where the baseline just
fails. The same profile against the tuned path, to find the new ceiling:

| run | load | settled p50 | p99 | captured | failed |
|---|---|---:|---:|---:|---:|
| `step15` | 100/s | 367 | 1,088 | 18,007 | 0 |
| `tuned-150` | 150/s | 6,791 | 39,299 | 22,725 | 4,284 |
| `tuned-200` | 200/s | 17,717 | 44,996 | 13,205 | 22,803 |

The ceiling moved from under 100/s to just above it, not to 200/s. What holds
it there is in the last two sections: fifteen fsyncs per payment on one virtual
disk, and one wallet row that every capture queues on.

## What the trace said before any change

A payment is seven outbox hops: payment -> ledger (reserve), ledger -> payment
(held), payment -> issuer, issuer -> payment, payment -> settlement, settlement
-> payment, payment -> ledger (capture). Each `outbox` span to its `publish` span
in the step 14 trace was a wait of up to 500ms for the poller's next tick: 250ms
in the mean, 1.75s of nothing per payment. That was the mean. The tail was the
ceiling: `OutboxPublisher` claimed one batch of 100 per tick, so a service could
publish at most 200 rows a second, and payment-service writes four rows per
payment. At 100 payments/s the backlog grew for the whole run and every payment
that joined it late died at the deadline.

`pg_stat_statements` (on, in Compose, since this step) said the database was not
the problem: the outbox poll costs 40-70µs when it finds rows and 7µs when it
does not, 20 times a second per service, a rounding error. What it did name is in
the last section.

## One change at a time

| run | change | settled p50 | p95 | p99 | captured / failed |
|---|---|---:|---:|---:|---:|
| `baseline` | one batch per 500ms tick, `manual_immediate` acks | 15,750 | 34,176 | 41,478 | 2,101 / 15,909 |
| `outbox-drains` | poller loops until a batch comes back short | 2,770 | 25,483 | 29,351 | 18,009 / 0 |
| `acks-per-poll` | `ack-mode: manual`: one offset commit per poll, not per record | 2,667 | 3,335 | 3,663 | 18,011 / 0 |
| `outbox-tick-50ms` | `fixedDelay` 500 -> 50 | 378 | 953 | 1,452 | 18,009 / 0 |
| `step15` | the same code, the checkpoint run | 367 | 725 | 1,088 | 18,007 / 0 |

Each row is the row above plus one change, same profile, same machine.

- **Drain until short.** Removes the ceiling: no payment dies at the deadline
  any more. The median is now 7 hops x 250ms of tick wait plus the work, which
  is the 2.7s.
- **One commit per poll.** `manual_immediate` is a synchronous commit to the
  broker after every record, about 2ms each; with 15 listener threads per
  service that was the consumer side's own ceiling and the reason p99 stayed at
  29s while p50 was 2.7s. `manual` acks the same records and commits once when
  the poll's batch is done. Same guarantee (nothing is committed before the work),
  one round trip per batch. p99 29s -> 3.7s.
- **50ms tick.** The mean hop wait drops from 250ms to 25ms. An empty poll is
  a 7µs query; 20 a second per service is nothing. p50 2.7s -> 378ms.

A median payment after the three, from Tempo (`docs/measurements` keeps the
query in step 14): every `outbox` -> `publish` gap is now the tick, every
`publish` -> consumer gap is the broker.

```
    0 ms     4 ms  payment-service      http
    2 ms     0 ms  payment-service      outbox
   45 ms     0 ms  payment-service      publish
   52 ms     8 ms  ledger-service       ledgerflow.ledger.hold.commands.v1
   56 ms     0 ms  ledger-service       outbox
  110 ms     0 ms  ledger-service       publish
  117 ms     6 ms  payment-service      ledgerflow.ledger.wallet-hold.events.v1
  120 ms     0 ms  payment-service      outbox
  175 ms     0 ms  payment-service      publish
  181 ms     5 ms  issuer-service       ledgerflow.issuer.authorization.commands.v1
  183 ms     0 ms  issuer-service       outbox
  219 ms     0 ms  issuer-service       publish
  225 ms     5 ms  payment-service      ledgerflow.issuer.authorization.events.v1
  228 ms     0 ms  payment-service      outbox
  238 ms     0 ms  payment-service      publish
  243 ms    14 ms  settlement-service   ledgerflow.settlement.capture.commands.v1
  246 ms     7 ms  account-service      http
  255 ms     0 ms  settlement-service   outbox
  261 ms     0 ms  settlement-service   publish
  267 ms     5 ms  payment-service      ledgerflow.settlement.capture.events.v1
  270 ms     0 ms  payment-service      outbox
  299 ms     0 ms  payment-service      publish
  306 ms     5 ms  ledger-service       ledgerflow.ledger.hold.commands.v1
  353 ms     0 ms  ledger-service       publish   (HoldClosed: the payment is Captured)
```

## What moved nothing, and the one thing that must not be kept

All at 100/s for 180s on top of `outbox-tick-50ms`; the spread between
identical runs is about 10% on p99, so anything inside 1,300-1,450 is noise.

| run | change | p50 | p99 | kept |
|---|---|---:|---:|---|
| `outbox-wakes` | append signals the poller instead of waiting for the tick | 234 | 1,359 | no: p50 only; the tail is not the tick any more, and it is a cross-thread signal on every append |
| `wal-4gb-lz4` | Postgres `max_wal_size=4GB`, `wal_compression=lz4` | 411 | 1,298 | no: noise |
| `outbox-deletes` | DELETE published rows instead of marking them | 564 | 1,428 | no: noise, and it loses the audit trail |
| `async-mark` | mark rows published off the send path | 543 | 1,392 | no: noise |
| `sampling-5pct` | tracing sampled at 5% instead of 100% | 462 | 1,813 | no: tracing is not the cost |
| `sync-commit-off` | Postgres `synchronous_commit=off` | 300 | 390 | **no** |

The last row is the only one that moved the tail, and it is the one that names
what the tail is: a payment is about fifteen commits across five databases, and
every one of them is an fsync on one Docker Desktop virtual disk. Turning the
fsync off is not a fix; it means a crash loses the last ~600ms of committed
ledger writes, and a ledger that can do that is not a ledger. It is a
measurement of the disk. The remedy is a disk that is not a VM's, one Postgres
per service on its own volume, and fewer transactions per payment; none of
those is a change to this code, and the table says so instead of hiding it.

## Virtual threads: measured, slower, not kept

`SPRING_THREADS_VIRTUAL_ENABLED=true` on payment-service, same profile, JFR
attached (`jcmd <pid> JFR.start settings=profile`, 215s):

| threads | POST p99 | settled p50 | p95 | p99 |
|---|---:|---:|---:|---:|
| platform (`step15`) | 12 | 367 | 725 | 1,088 |
| virtual (`virtual-threads`) | 196 | 417 | 4,152 | 5,335 |

`jfr view pinned-threads`: no events (JDK 25; `synchronized` stopped pinning in
24, and `-Djdk.tracePinnedThreads` no longer exists). `jfr view cpu-load`: JVM
at 1% user. Nothing is stuck and nothing is busy; the service waits, as it
should. Where it waits moved: in a slow trace every hop that is not
payment-service's own consumer is unchanged, and payment-service's consumers
return replies 757ms, 989ms and 1,198ms after they landed on the topic,
against 6-10ms on platform threads. JFR shows the Kafka heartbeat threads
waiting up to 499ms for the `ConsumerNetworkClient` lock, which the consumer
holds for the whole of `poll()`: the poll on a virtual thread sits out its full
`fetch.max.wait.ms` as though nothing had arrived. That is as far as this step
follows it. The honest sentence for an interview: on this path nothing blocks
long enough or often enough to need virtual threads (Tomcat's 200 threads see
6ms requests, the listeners are 3 per topic by partition count), the measurement
says they cost 4s of tail, so they stay off.

## What each database ran, ordered by total time

`pg_stat_statements` over the whole `step15` suite (transfer 60s, holds 30s,
payments 180s), reset before it:

| db | calls | total ms | mean ms | statement |
|---|---:|---:|---:|---|
| account | 24,922 | 49,561 | 1.99 | `UPDATE wallets SET balance_minor = balance_minor + $1 WHERE id = $2` |
| account | 24,922 | 4,748 | 0.19 | `UPDATE wallets SET balance_minor = balance_minor - $1 WHERE id = $2 AND ...` |
| payment | 74,400 | 3,013 | 0.04 | `INSERT INTO outbox ...` |
| account | 49,844 | 2,601 | 0.05 | `INSERT INTO postings ...` |
| payment | 57,301 | 2,407 | 0.04 | `INSERT INTO processed_events ... ON CONFLICT DO NOTHING` |
| payment | 55,800 | 1,970 | 0.04 | `UPDATE sagas SET state = ...` |
| payment | 6,370 | 463 | 0.07 | the outbox poll, `SELECT ... FOR UPDATE SKIP LOCKED` |

The credit costs ten times the debit, and it is the same UPDATE on the same
table. The difference is the row: every capture in the system credits TREASURY,
so at 100 payments/s a hundred transactions a second queue on one row lock,
each holding it until its own commit has fsynced. That is the next bottleneck
on this path and it is not in this service's code: it is settlement converging
everything on one wallet. Step 22's batch settlement (many captures, one entry)
is where it gets addressed; here it is named, with its number, so the row in the
README that fixes it can be traced back to this one.

Reproduce: `docker exec ledgerflow-postgres psql -U ledgerflow -d account -c
"SELECT d.datname, calls, round(total_exec_time::numeric) total_ms,
round(mean_exec_time::numeric,2) mean_ms, left(query,80) FROM pg_stat_statements s
JOIN pg_database d ON d.oid = s.dbid ORDER BY total_exec_time DESC LIMIT 10"`.

## The checkpoint

`perf/bench.sh step15`, all seven services up, C2 from now on (`spring-boot:run`
had been pinning the JIT at C1: `spring-boot.run.optimizedLaunch=false` in the
root pom). Transfer 100/s: p50 7ms, p99 11ms (8ms at step 30 with one service
and no outbox row per entry). Hold 50/s: p50 8ms, p99 13ms. Payment 100/s: POST
p99 12ms, settled p50 367ms, p99 1,088ms, 18,007 captured, 0 failed. The trend
from here is `docs/perf/README.md`.
