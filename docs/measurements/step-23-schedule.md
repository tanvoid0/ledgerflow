# Step 23 — three jobs, and who starts them now

## Three shapes

`nightlySettlementJob` (step 22), `statementExportJob`, `agedItemSweepJob` -
what differs between them is the reader and the writer, not the job:

- `nightlySettlementJob` - chunk step, `JdbcCursorItemReader` over
  `settlement_item`, `JdbcBatchItemWriter` into `settlement_line` /
  `settlement_batch`. Instance keyed by `businessDate`.
- `statementExportJob` - chunk 50, the same reader shape over
  `settlement_batch`, `FlatFileItemWriter` with an explicit
  `lineAggregator` over the record fields, header
  `merchant_id,currency,gross_minor,fee_minor,net_minor,line_count`,
  `shouldDeleteIfExists(true)`, one file per run:
  `target/exports/settlement-<businessDate>.csv`.
- `agedItemSweepJob` - one tasklet, one statement:
  `update settlement_item set status='STALE' where status='NEW' and
  business_date < current_date - interval '2 days'`, `contribution
  .incrementWriteCount` standing in for a writer that doesn't exist.

All three sit behind the same launcher (`GET /api/v1/batch/jobs` lists
them, `POST /jobs/{name}` and `POST /jobs/{name}/now`) and the same job
repository. Nothing about "chunk vs. tasklet" or "db vs. file" reaches the
controller - it starts a `Job` by name and reads back a
`JobExecution.getStatus()`.

## The parameter is the concurrency control

`businessDate` is passed as an *identifying* job parameter on
`/jobs/{name}`: two calls with the same date are the same job instance,
and `JobOperator.start` on an already-completed instance throws
`JobInstanceAlreadyCompleteException` before any step runs - the 409 step
22 already measured, unchanged here.

`/jobs/{name}/now` instead passes `runAt = Instant.now()`. That parameter
is never repeated by construction, so every call is a new instance -
useful for the sweep, which has no calendar date to be identified by, and
dangerous for settlement, which is exactly why the schedule below calls
`/now` only for the sweep and export, and settlement through its dated
form.

409 on a repeated `businessDate`, and the two per-minute sweep instances
from the two replicas (see below):

```
> POST /api/v1/batch/jobs/nightlySettlementJob HTTP/1.1
> Host: localhost:8087
> Content-Type: application/json
> Content-Length: 29

< HTTP/1.1 409
< Content-Type: application/problem+json
{"detail":"A job instance already exists and is complete for identifying parameters={JobParameter{name='businessDate', value=2026-09-01, type=class java.lang.String, identifying=true}}.  If you want to run this job again, change the parameters.","instance":"/api/v1/batch/jobs/nightlySettlementJob","status":409,"title":"Conflict"}
```

## Six fields, and a zone

Spring's `@Scheduled(cron = ...)` cron has six fields - seconds first -
where Unix cron has five; `0 5 1 * * *` is "at 01:05:00", not "at the 5th
minute of hour 1 on every weekday" the way a five-field reading would
parse it. Getting this wrong doesn't error, it just runs at the wrong
second forever.

`zone = "Europe/London"` is pinned on every `@Scheduled` in `BatchSchedule`
because the JVM's default zone is whatever the container gives it, not
what the business day means - a settlement cron with no zone would settle
at a different wall-clock moment depending on where the jar happens to
run, which is the opposite of "the job instance is the date."

Defaults: `0 5 1 * * *` settle, `0 0 2 * * *` export, `0 */15 * * * *`
sweep - the per-minute sweep interval below is a dev knob, not the kept
value (see Checkpoint/Kept).

## One timetable, two JVMs

Both settlement-service replicas (8087, 8097) run the same
`@Scheduled` methods, independently, because the schedule is a bean inside
each JVM, not a service. Effect depends on which job:

- **Sweep** - idempotent (`status='NEW'` is the only row an update
  touches), so two replicas firing the same cron expression a fraction of
  a second apart just means two executions a minute instead of one, both
  harmless:

  ```
           start_time         |  status   | job_execution_id
  ----------------------------+-----------+------------------
   2026-09-16 11:04:00.021894 | COMPLETED |               20
   2026-09-16 11:03:00.042472 | COMPLETED |               19
   2026-09-16 11:03:00.034969 | COMPLETED |               18   <- ~7ms after 19
   2026-09-16 11:02:00.033549 | COMPLETED |               16
   2026-09-16 11:01:00.042945 | COMPLETED |               15
   2026-09-16 11:01:00.038446 | COMPLETED |               14   <- ~5ms after 15
   2026-09-16 11:00:00.203130 | COMPLETED |               13   <- ~142ms after 11
   2026-09-16 11:00:00.060557 | COMPLETED |               11
  ```
  One row per firing per replica; each minute produces two COMPLETED executions
  a fraction of a second apart (5-142ms observed), not one - the "~1s stagger" is
  worst-case, the two replicas' clocks and JVM scheduling threads are usually much
  closer than that.

- **Settle** - collides on the same `businessDate`. Whichever replica's
  `JobOperator.start` reaches the job repository first wins; the second
  gets one of two exceptions depending entirely on timing:
  - `JobInstanceAlreadyCompleteException` if the winner has already
    finished (the loser's log line is INFO "already ran" - no items move).
  - `JobExecutionAlreadyRunningException` if the two calls race close
    enough that the winner is still inside the job repository's own
    transaction (the loser's log line is WARN "still running").

  Which one came out this run, and why:

  ```
  This run produced JobExecutionAlreadyRunningException on the loser (8097) - the two
  10:00:00 firings landed close enough that 8087's job was still inside the job
  repository's transaction when 8097 tried to start it:

  8087 (winner, COMPLETED):
  {"@timestamp":"2026-09-16T10:00:00.188129700Z","log":{"level":"INFO","logger":"org.springframework.batch.core.launch.support.TaskExecutorJobLauncher"},"message":"Job: [SimpleJob: [name=nightlySettlementJob]] completed with the following parameters: [{JobParameter{name='businessDate', value=2026-09-15, type=class java.lang.String, identifying=true}}] and the following status: [COMPLETED] in 115ms"}

  8097 (loser, still running):
  {"@timestamp":"2026-09-16T10:00:00.136129100Z","log":{"level":"WARN","logger":"io.ledgerflow.settlement.batch.BatchSchedule","message":"nightlySettlementJob still running from a previous trigger"}}

  Wall-clock gap between the two 10:00:00 firings: 10:00:00.188129700Z - 10:00:00.136129100Z
  = 52ms. Subsequent minutes (10:01, 10:02, 10:03) on both replicas fell back to the calmer
  path once the instance was COMPLETED - INFO "nightlySettlementJob already ran for
  {businessDate=2026-09-15}" on both.
  ```

Three answers, cheapest first, and none of them is "add a check in
application code":

1. **One scheduler outside the JVMs.** Step 26's Kubernetes CronJob with
   `concurrencyPolicy: Forbid`, hitting `/now` once, from outside every
   replica. Nothing to coordinate - there is exactly one caller.
2. **A shared lock.** ShedLock or `pg_try_advisory_lock` around the
   `@Scheduled` method - every replica still runs the cron, but only the
   one holding the lock calls the job.
3. **Leader election.** Only the elected replica runs `@Scheduled` at all
   - the heaviest of the three, and the only one that also answers "who
     runs this if the current leader dies," which the first two don't.

## Checkpoint

`perf/bench.sh step23` on the committed jars, the normal eight up, the
second replica gone and the schedule on its production crons (the sweep
fires every 15 minutes, so at most once during the run):

```
transfer: 100/s for 60s  -> p50 6ms  p95 7ms   p99 8ms   failed 0%
hold:     50/s for 30s   -> p50 6ms  p95 8ms   p99 9ms   failed 0%
payment:  100/s for 180s -> POST p50 6ms  POST p99 16ms
                             settled p50 527ms  settled p99 21902ms
                             captured 18004  failed 0
```

Compared to step 22 (p50 431 ms / p99 10.6 s, 18,006 captured, none
failed): settled p50 527 ms, p99 21.9 s, 18,004 captured, none failed. The
p99 doubled on one run with a checked host (no stray k6, no Gradle daemons,
exactly eight JVMs) and nothing new on the request path — the schedule
only adds three idle triggers. Step 22 saw the same order of swing between
runs on the same code; this is one run, not rerun, so it is recorded as a
number, not a cause.

## Kept

- All three jobs: `nightlySettlementJob`, `statementExportJob`,
  `agedItemSweepJob` - the shape (chunk-to-db, chunk-to-file, tasklet)
  covers every batch job this system needs so far.
- `BatchSchedule` with the production cron expressions
  (`0 5 1 * * *` / `0 0 2 * * *` / `0 */15 * * * *`) and the pinned zone.
- `/jobs/{name}/now` as the manual escape hatch for the sweep and export,
  and the dated form for settlement - the distinction from "the parameter
  is the concurrency control" stays load-bearing, not just a naming
  choice.

Not kept:

- The per-minute sweep interval - a dev knob to make the two-replica
  collision visible inside a bench run; production has no reason to sweep
  faster than the 2-day staleness window it's checking.
- The second replica racing the schedule with nothing coordinating it.
  Step 26's CronJob (answer 1 above) is what actually ships; this step
  only measures what happens without it, as the reason to build it.
