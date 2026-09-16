# Step 27 — one job, every core

200,000 items, 200 merchants, 1 in 20 EUR, chunk 100.

| configuration                          | wall clock | items/sec | note |
|-----------------------------------------|-----------:|----------:|------|
| single threaded, cursor reader          | {{R: t1}} | {{R: t1}} | baseline |
| taskExecutor(4), cursor reader          | {{R: t2}} | {{R: t2}} | WRONG — see counts, kept as evidence |
| taskExecutor(4), paging reader          | {{R: t3}} | {{R: t3}} | |
| partitioned, gridSize 8                 | {{R: t4}} | {{R: t4}} | |
| partitioned, gridSize 8, pool raised     | {{R: t5}} | {{R: t5}} | |

## One core

```text
{{R: baseline}}
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
{{R: jfr}}
```

## Four threads and the race

`chunkExecutor()` — `SimpleAsyncTaskExecutor`, virtual threads, concurrency
4 — bolted onto `lineItemsStep` with the reader unchanged. `JdbcCursorItemReader`
holds one `ResultSet` and one cursor position; four threads calling `read()`
on it is four threads calling `next()` on the same connection at once.
Nothing in the driver throws in a way that says "your reader is not thread
safe" — rows get skipped, rows get read twice, and the job still reports
COMPLETED. That silence is the whole danger: this is the multi-threaded
step every tutorial offers for free, and the readers that survive it are a
design decision, not a default.

Three ways out, in the order worth considering them: a reader that is safe
by construction (`JdbcPagingItemReader`, an independent ordered query per
page, no shared cursor); a synchronized wrapper around the cursor reader,
correct but turns reading into a critical section; or don't share a reader
at all — partition, and give every worker its own. The next two sections
are the first and the third.

```text
{{R: race}}
```

2026-10-03 stays in the database rather than getting cleaned up — it is the
evidence the bug was real, and rerunning it would only prove the same race
twice.

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
{{R: paging}}
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
{{R: partition}}
```

Each partition is a real `StepExecution` row — `batch_step_execution` after
a run holds one manager plus eight `lineItemsWorkerStep` rows, each with
its own read and write count, so a slow slice is visible rather than
folded into one total. Restart follows from the same shape: kill a run
mid-step and only the partitions still `STARTED` re-run on restart, not the
ones that already `COMPLETED`.

```text
{{R: restart}}
```

## The ceiling that is not threads

Eight partitions is eight readers wanting a connection each, on top of
whatever HTTP traffic is already on the pool step 15 sized for the payment
path. Raise Hikari's `maximum-pool-size` to 20 (`DB_POOL_SIZE`) and watch
`pg_stat_activity` during a run rather than guessing:

```text
{{R: pool}}
```

```text
{{R: pg_stat}}
```

Past the pool the ceiling moves to the database itself — lock contention,
WAL flushes, or plain CPU, and `wait_event` says which rather than leaving
it to guesswork. Threads are the cheapest thing to add to this job and
almost never the constraint.

```text
{{R: sentence}}
```

## Checkpoint

`perf/bench.sh step27`, against step 26's settled p50 243ms / p99 378ms
(18005 captured, 0 failed, two replicas through the kind cluster):

```text
{{R: bench}}
```
