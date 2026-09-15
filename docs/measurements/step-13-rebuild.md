# Step 13: the balance read model, thrown away and rebuilt from the log

balance-service on the host, Redis and Redpanda in compose, six services up.
`scripts/rebuild-balance.sh` stops the service, `FLUSHDB`, `rpk group seek
balance-service --to start`, starts it again and waits for the group's lag to reach 0.

## Rebuild time

| records on the three topics | wall clock | of which |
|---|---:|---|
| 17 | 10s | ~8s Maven + JVM start, the rest the group join |
| 3,017 (after 60s of holds at 50/s, `docs/perf/holds-step13-load.json`) | 12s | same 8s; the replay itself finishes inside the script's first 1s poll after "Started" |

Redis' side of it, `INFO commandstats`: 22µs per `EVALSHA` (one event, all its lines,
the mark and the read-your-write token), 1.8µs per `HINCRBY`. The replay is bounded by
the consumer, not the store; at these sizes it is bounded by starting a JVM. The number
to watch is records, not seconds: this stays "under a minute" until the topics hold a
few hundred thousand events, and step 20's compacted snapshot topic is what moves the
ceiling after that.

## Read-your-own-writes, through the saga

```
POST /api/v1/payments {"accountId":"1111…","wallets":["A-16"],"amountMinor":4500,"currency":"GBP"}
  -> 202, X-Request-Id: c8d0331d-…
GET  /api/v1/balances/1111…/A-16?after=c8d0331d-…
  -> 200 after 728ms, X-Projection: caught-up
     {"label":"A-16","balanceMinor":10000,"heldMinor":4500,"availableMinor":5500}
GET  /api/v1/balances/1111…/A-16            (5s later, the payment Captured)
  -> {"label":"A-16","balanceMinor":5500,"heldMinor":0,"availableMinor":5500}
```

728ms is two outbox polls (payment's command, ledger's event) plus the consumer. The
payment's request id reached the projection because ledger now puts the command's
correlationId on the MDC before placing the holds; before this step every saga-placed
`FundsHeld` carried `correlationId: null`.

## Where the two tables disagreed, and why

Query the read model next to the ledger (the curriculum's lesson for this step):

```
read model   SELECT sum(heldMinor)                                   -> 307,000
ledger       SELECT sum(amount_minor) FROM funds_holds WHERE status='HELD'  -> 308,200
```

1,200 apart: one hold on A-13, placed at step 10 while the broker was stopped, before the
outbox existed. `docs/measurements/step-10-dual-write.md` recorded it as "a hold nobody
heard about"; three steps later a projection built from the log makes it visible as a
number. The projection is right about the log and wrong about the ledger, and the ledger
wins. Repair: one `INSERT INTO outbox` for that hold, built from the row the way the
V5/V6 backfills do; the poller published it, the projection applied it, both sides read
308,200. No rebuild needed, nothing touched in Redis.

The other way round is not possible: every hold and every entry since the outbox
migrations is in the log by construction, and the backfills in `V5__hold_closed_backfill.sql`
(ledger) and `V6__outbox.sql` (account) put the history before them there too.

## Two things the step does not say

- The rewind needs an empty group, and a killed JVM is not out of the group for
  `session.timeout.ms` (45s). The script stops the service through
  `POST /actuator/shutdown` so the consumer leaves at once; a `kill -9` would make every
  rebuild 45s longer than it is.
- Redpanda in compose has no volume, so `docker compose down` takes the log with it and
  a rebuild afterwards produces an empty read model. The outbox tables are the system of
  record: `UPDATE outbox SET published_at = NULL` in account and ledger republishes
  everything, and the projection's dedupe on eventId means it is safe to do with the
  projection running.
