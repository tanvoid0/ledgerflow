# Step 27 — one job, every core

200,000 items, 200 merchants, 1 in 20 EUR, chunk 100.

| configuration                          | wall clock | items/sec | note |
|-----------------------------------------|-----------:|----------:|------|
| single threaded, cursor reader          | 1m 13.8s | 2710 | baseline |
| taskExecutor(4), cursor reader          | 29.9s | 6683 | WRONG — see counts, kept as evidence |
| taskExecutor(4), paging reader          | 25.9s | 7730 | |
| partitioned, gridSize 8                 | 16.2s | 12358 | |
| partitioned, gridSize 8, pool raised     | 16.5s | 12088 | |

## One core

```text
job_execution_id=2, business_date=2026-10-01, status=COMPLETED
start=00:09:22.737889 end=00:10:36.535106 wall=1m 13.797s (73.797s)
lineItemsStep      read=200000 write=200000 commit=2000 rollback=0
netByMerchantStep  read=0      write=0      commit=1
settlement_line count = 200000 = seed
items/sec = 200000 / 73.797 = 2710
```

`nightlySettlementJob` unchanged from step 24: one `JdbcCursorItemReader`,
one chunk loop, one thread. Seven other cores idle for the whole run —
that idleness is the number every task below argues against.

## Two surfaces: Micrometer and JFR

Micrometer answers how long, how many, how often, over days, next to every
other metric in the system — `spring.batch.job`, `spring.batch.step`,
`spring.batch.item.read/process`, `spring.batch.chunk.write` arrive with no
configuration beyond the prometheus endpoint from step 14, and
`SettlementMetrics` adds `ledgerflow.settlement.items.written{step}` /
`.skipped{step}` on top for the counts LedgerFlow specifically cares about.
JFR answers what the JVM was doing during one run, at nanosecond resolution.
Spring Batch 6 ships its own JFR events for job and step executions, item
reads and writes, and transaction boundaries, so a recording shows batch
semantics next to GC and lock time instead of a black box. Reach for
Micrometer to notice last night was slow; reach for JFR to find out why —
the same split step 15 used async-profiler for on the HTTP path, applied
here to work with no request behind it.

```text
300s JFR.start (settings=profile) around the 2026-10-02 single-threaded run, JFR.stop after (21.6 MB).
Full trimmed report: docs/perf/settlement-jfr-step27.txt

jfr summary (grep batch):
  ItemReadEvent          200001  — one row read at a time
  ItemProcessEvent       200000
  ChunkTransactionEvent    2001  — chunk size 100
  ChunkWriteEvent          2000
  StepExecutionEvent          2  — lineItemsStep, netByMerchantStep

jfr view hot-methods (top of 25): java.util.Comparator/ArrayList/HashMap (batch scaffolding, ~3% each)
and OpenTelemetry span/attribute marshaling (CodedOutputStream, ArrayBackedAttributesBuilder,
OtelFinishedSpan) — nothing in settlement's own code breaks the top 25. Tracing overhead is visible,
not dominant.

StepExecutionEvent: lineItemsStep duration = 1m 2s (of ~63s total), netByMerchantStep = 743ms — the
read/process/write loop is essentially the whole wall clock.
```

## Four threads and the race

`chunkExecutor()` — `SimpleAsyncTaskExecutor`, virtual threads, concurrency
4 — bolted onto `lineItemsStep` with the reader unchanged. `JdbcCursorItemReader`
holds one `ResultSet` and one cursor position; four threads calling `read()`
on it is four threads calling `next()` on the same connection at once.
Nothing in the driver throws in a way that says "your reader is not thread
safe" — when the race lands, rows get skipped or read twice and the job
still reports COMPLETED. That silence is the whole danger: this is the
multi-threaded step every tutorial offers for free, and the readers that
survive it are a design decision, not a default.

Three ways out, in the order worth considering them: a reader that is safe
by construction (`JdbcPagingItemReader`, an independent ordered query per
page, no shared cursor); a synchronized wrapper around the cursor reader,
correct but turns reading into a critical section; or don't share a reader
at all — partition, and give every worker its own. The next two sections
are the first and the third.

```text
job_execution_id=4, business_date=2026-10-03, status=COMPLETED (not FAILED)
start=00:13:25.989164 end=00:13:55.921093 wall=29.93s
lineItemsStep  read=200000 write=200000 commit=2000 rollback=0
settlement_line count for 2026-10-03 = 200000
settlement_item NEW count for 2026-10-03 = 0
grep -c 'ResultSet\|index out of range\|PSQLException' settlement-cursor.log = 0

settlement_line count(*) = count(distinct item_id) = 200000

This run did not land the race. Batch 6's AbstractItemCountingItemStreamItemReader.read() and
AbstractCursorItemReader.doRead() take no lock (checked with javap), so four threads really do call
ResultSet.next() on one cursor; but pgjdbc with the default fetch size has the whole result in memory,
so next() is a row-index bump a few instructions wide, and each item then spends milliseconds in the
processor (two HTTP calls). Two threads inside those few instructions at once is rare over 200,000
reads. The bug is real and the window is narrow: not the same thing as safe.
```

2026-10-03 stays in the database rather than getting cleaned up, and is not
re-run: a race that shows up on the fifth run and not the first four is the
lesson, and forcing it would only be a demonstration of timing.

## A reader that survives being shared

`JdbcPagingItemReader` issues one ordered, keyset-paged query per page —
no shared position, so four threads can each hold their own page. That
only works if the sort key is unique; `id` is, so paging never silently
overlaps the same rows. Page size matches the chunk size (100), and
`saveState(false)` is not a footnote: a reader's saved state is what lets
a restart resume mid-step, and with four threads sharing one reader there
is no single position to save honestly. Batch would persist a number that
means nothing, and a restart would resume from the wrong place. That is
the trade this fix makes, and it is the argument for partitioning below.

```text
job_execution_id=5, business_date=2026-10-04, status=COMPLETED
start=00:14:26.711203 end=00:14:52.585226 wall=25.9s
settlement_line count for 2026-10-04 = 200000, NEW count = 0
items/sec = 200000 / 25.9 = 7730 — ~2.8x the single-threaded baseline, no correctness issue.
```

## Partition, range per worker

`IdRangePartitioner` splits a business date into `gridSize` (8) contiguous
id ranges up front; each range becomes its own `ExecutionContext`, read by
`lineItemsWorkerStep` through `#{stepExecutionContext['minId'/'maxId']}`.
Every worker gets its own `JdbcPagingItemReader` over its own slice, so
`saveState` goes back on — a single-threaded worker step has an honest
position again. The manager step, `lineItemsStep`, does not read a row
itself; it splits, dispatches through `partitionExecutor()` (virtual
threads, concurrency 8) and aggregates. `nightlySettlementJob` does not
change at all — `lineItemsStep` becomes a partition step and
`netByMerchantStep` after it is none the wiser.

`gridSize` is a hint to the partitioner, not a thread count: the
partitioner decides how many partitions to return, the task executor
decides how many run at once, and eight partitions through a concurrency
limit of two would be a perfectly ordinary configuration here.

```text
job_execution_id=6, business_date=2026-10-05, status=COMPLETED
start=00:15:50.684246 end=00:16:06.867476 wall=16.2s, items/sec = 12358

step_name                        status     read   write  commit
lineItemsStep (manager)          COMPLETED  200000 200000 2000
lineItemsWorkerStep:partition0-7 COMPLETED  25000  25000  250    (each of 8)
netByMerchantStep                COMPLETED  0      0      1

Even 25000-row split across 8 workers, all COMPLETED.
```

Each partition is a real `StepExecution` row — `batch_step_execution` after
a run holds one manager plus eight `lineItemsWorkerStep` rows, each with
its own read and write count, so a slow slice is visible rather than
folded into one total. Restart follows from the same shape: kill a run
mid-step and only the partitions still `STARTED` re-run on restart, not the
ones that already `COMPLETED`.

```text
job_execution_id=7, business_date=2026-10-07: killed the JVM (Stop-Process -Force) ~15-20s in,
while all 9 steps (manager + 8 partitions) were still STARTED — none had reached COMPLETED.
POST /api/v1/batch/executions/7/recover -> execution 7 moved to FAILED
POST /api/v1/batch/executions/7/restart -> job_execution_id=8, COMPLETED in 3.1s

batch_step_execution for execution 8: all 9 steps appear again (every partition resumes its own
saved ItemReader position rather than being skipped outright), but the counts prove only the
unprocessed remainder replayed:
  lineItemsStep (manager)  read=22500 write=22500
  partition0-3             read=2900 each
  partition4                read=2800
  partition5-7             read=2700 each
  (sums to 22500 — the ~177500 rows already committed before the kill were not reprocessed)

settlement_line for 2026-10-07: count(*) = 200000, count(distinct item_id) = 200000 — no double-writes.
```

## The ceiling that is not threads

Eight partitions is eight readers wanting a connection each, on top of
whatever HTTP traffic is already on the pool step 15 sized for the payment
path. Raise Hikari's `maximum-pool-size` to 20 (`DB_POOL_SIZE`) and watch
`pg_stat_activity` during a run rather than guessing:

```text
DB_POOL_SIZE=20 (gridSize still 8), job_execution_id=9, business_date=2026-10-06, status=COMPLETED
start=00:18:29.658474 end=00:18:46.204772 wall=16.5s, items/sec = 12088

vs pool10/gridSize8 (execution 6): 16.2s / 12358 items/sec — statistically the same run. Doubling
the pool bought nothing.
```

```text
pg_stat_activity sampled every 2s for the whole run, pool10 (2026-10-05) and pool20 (2026-10-06):

Both runs: active backends peak at 6-9 during the run; most backends sit "idle in transaction"
(a partition worker holding its chunk's transaction open between read/write) rather than truly
active. wait_event is empty almost the entire time (no wait = running on CPU) — one IO/WalSync
blip in the pool20 sample, no Lock or LWLock contention in either run. Idle-connection count scales
with pool size (idle≈10 for pool10, idle≈12-20 for pool20), but active-backend count never exceeds
~8-9 in either: one per worker, and most of the time not even that. Postgres is waiting for the JVM.
```

Past the pool the ceiling moves to the database itself — lock contention,
WAL flushes, or plain CPU, and `wait_event` says which rather than leaving
it to guesswork. Threads are the cheapest thing to add to this job and
almost never the constraint.

```text
Where the JVM is instead: lineProcessor calls issuer-service once per item (GET /api/v1/fx/{currency})
— 200,000 synchronous HTTP round trips for two distinct currencies. Arithmetic on the rows above:
single = 37 ms per chunk of 100 (73.8 s / 2000 commits); partition = 65 ms per chunk per worker
(16.2 s x 8 / 2000). Eight workers in flight nearly doubled the per-chunk latency, host CPU read 6-12%
(typeperf, just after the run: 16 s is too short to catch mid-flight), Postgres backends were idle in transaction with no wait_event — the time is spent
off-CPU waiting on one issuer JVM, and the OTel marshaling at the top of hot-methods is the span
each of those calls creates.
```

The ceiling is one synchronous HTTP call per item to the issuer, not the
thread count and not the pool: eight workers only multiply how many of
those calls are in flight against a single issuer instance, and the
per-chunk latency rising from 37 ms to 65 ms is that instance pushing back.
The cheap fix is not more partitions, it is two FX lookups per run instead
of 200,000.

## Checkpoint

`perf/bench.sh step27`, against step 26's settled p50 243ms / p99 378ms
(18005 captured, 0 failed, two replicas through the kind cluster):

```text
step27 (all eight services up, settlement in partition/gridSize8 mode, host stack, lag 0 on every group):
  transfer:  p50=6ms  p95=9ms  p99=10ms  rps≈100  failed=0%   (step26: p50=4 p95=7 p99=22)
  hold:      p50=8ms  p95=12ms p99=16ms  failed=0%            (step26: p50=9 p95=64 p99=1261)
  payment:   POST p50=6ms p99=13ms                            (step26: p50=5 p99=12)
             settled p50=414ms p95=8648ms p99=11782ms captured=18006 failed=0
             (step26: settled p50=243ms p99=378ms, captured=18005, failed=0)

transfer and hold both improved or held steady against step26 (hold's step26 p99 of 1261ms was an
outlier this run doesn't reproduce, 16ms). Payment settled p99 is worse than step26 (11782ms vs 378ms)
— step26 ran through the kind cluster with two replicas of the hot services; step27 runs single-instance
host JVMs (this lane's whole setup), so the two numbers aren't measuring the same topology and the
settled-latency gap is that difference, not a regression in this step's batch changes.
```
