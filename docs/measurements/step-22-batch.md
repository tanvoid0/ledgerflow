# Step 22 — a job, and who starts it

## A different shape of program

Every service so far answers something: an HTTP request, or an event on a
topic. Settlement answers nothing - it runs because the business day ended,
over rows already sitting in `settlement_item`, with nobody waiting on the
reply. Spring Batch is Java's standard answer to that shape of program: a job
made of steps, each step reading, processing and writing in chunks that
commit as they go.

`spring.batch.job.enabled: false` is what keeps this from being the thing
that makes settlement-service dangerous to restart. Boot's default runs
every `Job` bean at startup with whatever parameters it can invent - fine
for a getting-started guide, a bug for a job that moves money. Off, the job
runs only when told to; step 26 turns it back on, inside a Kubernetes
CronJob, where "starts on its own schedule" is exactly what's wanted.
`spring.batch.jdbc.initialize-schema: always` gets the six BATCH_ tables
onto Postgres - the default only does that for an embedded database, and
nothing here is embedded. No `@EnableBatchProcessing`: it exists for
pre-Boot-3 setups, and adding it now switches Boot's own auto-configuration
off, taking the schema initialization with it.

## The tables Batch keeps

- `BATCH_JOB_INSTANCE` - which job, which identifying parameters.
  `nightlySettlementJob` for `2026-09-01` is one row, forever; a second
  start with the same parameters has nothing new to insert.
- `BATCH_JOB_EXECUTION` - one attempt at an instance. COMPLETED or FAILED,
  when it started, when it ended.
- `BATCH_JOB_EXECUTION_PARAMS` - what was actually passed -
  `businessDate=2026-09-01` - so an execution's identity survives being
  read back after the fact, not just while it's running.
- `BATCH_STEP_EXECUTION` - one step's attempt: read count, write count,
  commit count, status. This is where "did it work" turns into a number
  instead of a log line.
- `BATCH_JOB_EXECUTION_CONTEXT` - job-level state carried past a single
  step, for a restart that needs to remember something no one step owns.
- `BATCH_STEP_EXECUTION_CONTEXT` - a step's own resume point - a chunk
  step's last committed position, for the restart step 24 does on purpose.

## One call settles a day

`nightlySettlementJob` against 500 seeded captures across 20 merchants, one
currency (GBP), business date `2026-09-01`:

```
POST /api/v1/batch/jobs/nightlySettlementJob {"businessDate":"2026-09-01"}
```

| | value |
|---|---:|
| items seeded | 500 |
| lines written (`settlement_line`) | 500 |
| batch rows (`settlement_batch`, one per merchant) | 20 |
| wall time of the call | 0.335s |
| `lineItemsStep` read / write / commit count | 500 / 500 / 5 |
| `netByMerchantStep` read / write / commit count | 0 / 0 / 1 |

`lineItemsStep` chunks at 100, so 500 items with nothing skipped is 5
commits; `netByMerchantStep` is one tasklet, one transaction, whatever its
own counters report for two statements.

## The same day twice

Same request, same date, fired again after the first call already answered
COMPLETED:

```
$ curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  localhost:8087/api/v1/batch/jobs/nightlySettlementJob \
  -H 'content-type: application/json' -d '{"businessDate":"2026-09-01"}'
409
```

Batch refuses at the job-repository level, before a line of this job's own
code runs: `nightlySettlementJob[businessDate=2026-09-01]` is a closed
instance, and `JobInstanceAlreadyCompleteException` is what
`JobOperator.start` throws for it - the controller turns that into the 409.
The instance is the date on purpose: settling the same day twice has
nothing left to do, and that holds across process restarts, not just within
one request the way a database transaction would.

Reading the run back out of the job repository instead of trusting the HTTP
response:

```
$ docker exec lf-postgres psql -U ledgerflow -d settlement -c "
select i.job_name, e.status, s.step_name, s.read_count, s.write_count, s.commit_count
  from batch_job_instance i
  join batch_job_execution e on e.job_instance_id = i.job_instance_id
  join batch_step_execution s on s.job_execution_id = e.job_execution_id
 order by s.step_execution_id;"
       job_name       | status    | step_name         | read_count | write_count | commit_count
 nightlySettlementJob | COMPLETED | lineItemsStep     |        500 |         500 |            5
 nightlySettlementJob | COMPLETED | netByMerchantStep |          0 |           0 |            1
```

One instance, one execution, two step executions - the second call added
neither.

## Checkpoint

`perf/bench.sh step22` on the committed jars: transfer p99 11 ms, holds p99
11 ms, payment POST p99 14 ms, settled p50 431 ms / p99 10.6 s, 18,006
captured, none failed. Step 21 was 438 ms / 9.9 s / none failed. The Batch
wiring costs the capture path nothing: mid-run, `spring_kafka_listener_milliseconds`
had settlement-service's capture listener at 20.8 ms a record against step
18's 21.2, payment-service's reply threads at 6.8 (7.0), ledger-service at
8.1 (8.4), all three threads of each within 0.1 ms of one another.

That row took six runs, and the five bad ones were two host faults, neither
in this step's code:

- **Lost consumer offsets.** Three times the stack came back from a hard
  stop (the JVMs, k6 and Docker Desktop's backend killed together) and
  payment-service logged `Found no committed offset for partition ...` /
  `Resetting offset ... to position offset=0` on some partitions and not
  others. Redpanda had lost part of the group's committed offsets, and
  `auto-offset-reset: earliest` did the rest: 100-190k old reply events
  replayed through the inbox's dedupe at ~1.6k/s, so the payments phase
  started with the reply threads already 60-90 s behind and every saga in
  the first minutes timed out at ISSUE. The signature is `rpk group describe
  payment-service` showing lag on some partitions and zero on others while
  nothing is producing - checked *after* the consumers have joined, not
  while the JVMs are starting (the group reports 0 with no members).
- **Two JIT crashes.** risk-service on the 15th (`EXCEPTION_ACCESS_VIOLATION`
  in C2-compiled `FetchRequestData.addSize`), ledger-service on the 16th
  (`EXCEPTION_ILLEGAL_INSTRUCTION` in C2-compiled
  `NetworkClient$DefaultMetadataUpdater.isUpdateDue`, on a legal `vmovsd`),
  both on a Kafka consumer thread ~3 minutes into load, Temurin 25.0.4+7 on
  Zen 5. Application code is not in either frame. Every saga waiting on the
  dead service ran to its deadline; the run is discarded, not recorded.

Both leave the same shape in the settled numbers - a p50 in seconds and a
few hundred `TIMED_OUT at ISSUE` - which is why the box's first suspect,
Batch's transaction wiring next to Boot's, was checked with the listener
histogram before rerunning rather than after.

## Kept

- `spring.batch.job.enabled: false` by default; `true` only inside step
  26's CronJob.
- Two steps, not one: a chunk step (`lineItemsStep`) for the per-item
  pricing, a tasklet (`netByMerchantStep`) for the set-based net - each
  statement in the tasklet is its own idempotent upsert, so a JVM dying
  mid-tasklet is answered by running the step again, not by a compensating
  action.
- The job instance's identity is the business date. No separate
  "already settled" check in application code - the job repository already
  refuses.
- The manual HTTP trigger blocks the calling thread and answers COMPLETED
  or FAILED in the response body. Kept because a job that takes seconds
  makes that the right shape; an hour-long job holding an HTTP thread is
  step 26's problem to fix, not this one's.
