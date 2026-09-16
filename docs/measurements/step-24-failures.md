# Step 24 — two ways to fail, and only one of them retries

## Transient or permanent

Two deliberate failures, chosen to be the only classification that
matters to a batch step:

- **Transient.** issuer-service's `GET /api/v1/fx/{currency}` prices the
  uplift honestly (0 bps for GBP, 25 otherwise), but
  `POST /api/v1/fx/faults?count=N` arms the next `N` calls to answer 503
  instead. The feed comes back on its own; nothing about the data was
  wrong, only the moment.
- **Permanent.** a `settlement_item` with `amount_minor <= 0` makes the
  processor throw `UnsettleableItemException`. No amount of waiting fixes
  a negative amount - retrying it is a slower way to fail the same item.

The first FAILED run, with the feed armed to fail ten calls - more than the
four retries absorb. The curriculum promises a mess of half-committed chunks
for the restart to cope with; here the mess is smaller than promised: the
feed was down from the first item, so the first chunk read 100, wrote 0 and
rolled back once. Nothing committed. The restart in the section below still
has to start from that count, which happens to be zero:

```
 job_execution_id |  status   | exit_code |     step_name     | read_count | write_count | commit_count | rollback_count 
------------------+-----------+-----------+-------------------+------------+-------------+--------------+----------------
               25 | FAILED    | FAILED    | lineItemsStep     |        100 |           0 |            0 |              1
               24 | COMPLETED | COMPLETED | agedItemSweepStep |          0 |           0 |            1 |              0
               23 | COMPLETED | COMPLETED | agedItemSweepStep |          0 |           0 |            1 |              0
               21 | COMPLETED | COMPLETED | agedItemSweepStep |          0 |           0 |            1 |              0

select count(*) from settlement_line where business_date = '2026-09-03';  -- 0

exit_message (first 300 chars):
org.springframework.batch.core.step.FatalStepExecutionException: Unable to process chunk
        at org.springframework.batch.core.step.item.ChunkOrientedStep.processChunkSequentially(ChunkOrientedStep.java:556)
        at org.springframework.batch.core.step.item.ChunkOrientedStep.processNextChunk(ChunkOriented
```

## Retry is includes and excludes

`lineItemsStep` is `.faultTolerant()` with Spring Framework 7's
`org.springframework.core.retry.RetryPolicy`, the same engine as step 06's
`@Retryable` on the ledger's account client, here built with a builder
because a step is configured, not declared: maxRetries 4, delay 200ms,
multiplier 2, maxDelay 5s, jitter 100ms, `includes(RestClientException)`,
`excludes(UnsettleableItemException)`.

`includes`/`excludes` is a decision about the world, not about Java - it
says which failures are worth a second attempt and which aren't, and
getting it backwards either retries a bad amount four times for nothing or
gives up on a feed that would have answered the very next call. Jitter
exists for the case where twenty pods lose the same feed at the same
instant and would otherwise all retry in lockstep.

Clean run against the faulted run, and the 503 lines in between:

```
 job_execution_id |  status   |         start_time         |          end_time          |    duration     
------------------+-----------+----------------------------+----------------------------+-----------------
               26 | COMPLETED | 2026-09-16 11:39:49.598502 | 2026-09-16 11:39:50.340083 | 00:00:00.741581   -- 09-05, clean, faults=0
               27 | COMPLETED | 2026-09-16 11:39:58.672755 | 2026-09-16 11:39:59.908992 | 00:00:01.236237   -- 09-08, same shape, faults=2

Both COMPLETED; the faulted run took ~0.49s longer, consistent with two absorbed
retries (200ms then 400ms base delay before jitter). Neither service logs the 503s
at INFO: grep -i "warming up" and a literal 503 status against issuer-service.log
come back empty (the fault filter answers 503 at the HTTP layer without logging),
and grep -i retry against settlement-service.log matches only Kafka's internal
retry-<n> partition/consumer-group names, not job-item retries - the policy logs
nothing at INFO when a retry succeeds.
```

## Skip is a policy with a limit

`RejectUnsettleableItems` is both a `SkipPolicy` (decides whether this
exception should be skipped rather than fail the step) and a
`SkipListener` (records the decision): a skip inserts one row into
`settlement_reject(item_id, business_date, reason, rejected_at)`, sets the
item's `status = 'REJECTED'`, and logs one WARN.

The skip limit lives inside the policy (`skipCount < 10`), not in
`.skipLimit()` on the step builder - Batch ignores `.skipLimit()` once a
custom `SkipPolicy` is set, a detail the curriculum's own snippet gets
wrong. Ten rejects out of five hundred items is a number for Monday's
report; a hundred means the upstream format changed and the job should
stop, not quietly reject a fifth of the day.

The reject row and the WARN line it came with:

```
 item_id | business_date |         reason         |          rejected_at          
---------+---------------+------------------------+-------------------------------
    3002 | 2026-09-06    | item 3002 has amount 0 | 2026-09-16 10:41:09.990992+00

  id  |  status  
------+----------
 3002 | REJECTED

WARN line (io.ledgerflow.settlement.batch.RejectUnsettleableItems):
{"@timestamp":"2026-09-16T10:41:09.990179100Z","log":{"level":"WARN","logger":"io.ledgerflow.settlement.batch.RejectUnsettleableItems"},"message":"rejecting item 3002: item 3002 has amount 0"}

select count(*) from settlement_line where business_date = '2026-09-06';  -- 500
```

## Restart is not a rerun

`POST /api/v1/batch/executions/{id}/restart` takes no body - the failed
execution's instance already knows its parameters. Batch resumes: steps
that completed are skipped outright, and `lineItemsStep` resumes from its
last committed chunk, because the count lives in the step execution
context, not in the request.

Chunk size is the unit of lost work. At chunk 100, a stop mid-chunk loses
at most 99 items of in-flight processing - never the whole step, never the
items already committed in earlier chunks. The `on conflict do nothing`
writer from step 22 is what makes the restart's rewrite of that one
in-flight chunk a no-op rather than a duplicate.

Two executions' step rows side by side - the failed one and the restart
that finished it:

```
 job_execution_id | job_instance_id |  status   |     step_name     | read_count | write_count | commit_count 
------------------+-----------------+-----------+-------------------+------------+-------------+--------------
               25 |              26 | FAILED    | lineItemsStep     |        100 |           0 |            0
               29 |              26 | COMPLETED | lineItemsStep     |        501 |         500 |            6
               29 |              26 | COMPLETED | netByMerchantStep |          0 |           0 |            1

Both executions share job_instance_id 26 - the restart reused the failed run's
instance rather than starting a new one. The restart re-read the whole chunk from
the top (read_count 501, including the poison item, which the SkipPolicy now
rejects instead of failing the step) and finished cleanly: 500 written, 6 commits,
then netByMerchantStep ran. settlement_line for 2026-09-03 = 500 afterward.

Instance count check (group by job_instance_id order by 1 desc limit 1) returns
job_instance_id 29 with count 1 - that is the 09-06 instance (created after 09-03's,
since it ran later), not the restarted one. The instance that actually has >1
executions is 26, visible directly above.
```

## STARTED with no end time

A killed JVM leaves the job's execution row `STARTED`, with no
`end_time`. Batch refuses to restart that execution - it has no way to
tell a dead process from a slow one, and restarting a job that might still
be running would be two processes writing the same chunk.

`JobOperator.recover(execution)` (Batch 6) is the answer: it marks the
execution `FAILED` and restartable, and only then does
`/restart` finish the job. Before Batch 6 this was a manual `UPDATE` on
`BATCH_JOB_EXECUTION` and hoping nothing else touched the row at the same
time. Step 26 meets this state for real on the first pod eviction; here
it's produced on purpose.

The refused restart's error body, the recover call, and the restart that
then completed:

```
POST /api/v1/batch/executions/31/restart (execution 31 STARTED, end_time null - JVM
killed mid-run on a 20000-row seed):
{"timestamp":"2026-09-16T10:44:11.322Z","status":500,"error":"Internal Server Error","path":"/api/v1/batch/executions/31/restart"}
HTTP 500 - refused, as expected.

POST /api/v1/batch/executions/31/recover:
{"jobName":"nightlySettlementJob","executionId":31,"status":"FAILED","exitCode":"UNKNOWN","startTime":"2026-09-16T11:43:36.293631","endTime":"2026-09-16T11:44:16.605638"}
status now FAILED (restartable).

POST /api/v1/batch/executions/31/restart (second attempt):
{"jobName":"nightlySettlementJob","executionId":32,"status":"COMPLETED","exitCode":"COMPLETED","startTime":"2026-09-16T11:44:22.0927513","endTime":"2026-09-16T11:44:24.8312109"}
COMPLETED.

Checks:
select count(*) from batch_job_execution where status = 'STARTED' and end_time is null;  -- 0
select count(*) from settlement_item i where i.status = 'NEW' and exists (select 1 from settlement_line l where l.item_id = i.id);  -- 0
```

## The fact worth keeping

`netByMerchantTasklet` marks SETTLED only the items that have a line:

```sql
exists (select 1 from settlement_line where item_id = settlement_item.id)
```

Without that guard, a rejected item - one with no line, because it was
skipped before the writer ever ran - would still come out SETTLED, because
the tasklet works in batches of merchants, not items.

Proof query, kept from before this step and still the one that matters:

```sql
select count(*) from settlement_item i
where i.status = 'NEW'
  and exists (select 1 from settlement_line l where l.item_id = i.id);
```

Must be 0 - a line without a settled item means a restart wrote work that
the second step never acknowledged.

## Checkpoint

`perf/bench.sh step24` against step 23's numbers (settled p50 527 / p99
21902 / 18004 captured / 0 failed):

```
step24  p50=7ms  p95=11ms  p99=17ms  rps=88  failed=0%
step24  settled: p50=908ms  p95=20485ms  p99=24332ms  captured=18005  failed=0
```

Against step 23 (settled p50 527 / p99 21902 / 18004 / 0): p50 908 ms, p99
24.3 s, 18,005 captured, none failed. The nightly job is not on this path -
the bench drives the payment saga, and nothing this step changed runs during
it (the feed switch is at zero, the schedule is on its production crons) - so
the p50 swing is the run, not the retry policy. Three checkpoints in a row have
drifted up on the same load (431 → 527 → 908 ms) with a checked host each time;
that is a trend to name, not explain here, and a step where load is the lesson
is where it gets looked at.

## Kept

- The retry policy with its includes/excludes split.
- `RejectUnsettleableItems` as one policy doing both jobs, and
  `settlement_reject` as the table that makes a skip auditable.
- The two operator routes: `restart` for a failed-but-recoverable
  execution, `recover` for one a killed JVM left `STARTED`.
- `SettlementFaultToleranceTest`.

Not kept:

- The fault-injection switch on issuer-service stays in the code as a
  dev/ops knob (useful for the next chaos drill) but is never armed on a
  production path.
- The seeded dates used to line the two runs up for this measurement.
