# ADR 0006: which rung of the scaling ladder settlement actually needs

Status: accepted (step 29)

## Context

Step 27 found the nightly settlement job's ceiling, and it wasn't the box: eight partitioned workers on one JVM only doubled the per-chunk latency, because every item makes one synchronous HTTP call to issuer-service for an FX rate, and eight workers just means eight of those calls in flight against one instance at once. Growing past one JVM's core count is a real capability — a bigger business day than eight cores can page through in reasonable time exists — but it's a five-rung ladder with a bill due at each rung climbed, and the top of it pays that bill even when the ceiling never moves.

## Options considered

1. **One thread, one reader.** Step 27's baseline: 73.8s for 200,000 items. Zero new infrastructure, one failure domain, correct by construction.
2. **Threads over a safe reader.** `JdbcPagingItemReader` behind a `taskExecutor`: 25.9s, ~2.8x. Still one JVM, and `saveState(false)` means a restart has no honest position to resume from — the trade this rung makes for concurrency.
3. **In-JVM partitions.** `IdRangePartitioner`, one reader per partition, restart-safe again: 16.2s on 8 cores. The ceiling is that JVM's core count, full stop, and it's one pod — a node failure takes the whole day with it.
4. **Remote partitioning (this step).** The same partition split, dispatched over Kafka to a pool of worker pods instead of a thread pool. Buys concurrency past one JVM's cores and survives a pod dying mid-partition, for the cost of a topic, a consumer group, a second profile, and a 30s stall per dead worker.
5. **Remote chunking.** Splits the read/process/write loop itself across the wire instead of just the partition boundaries. Buys finer-grained work distribution at the cost of serialising a `StepContribution` → `StepExecution` graph onto every chunk instead of once per partition — the heaviest rung, and the one this project keeps out of the nightly graph.

## Decision

**Rung 3: in-JVM partitions.** On the same cluster, eight partitions in one pod settled 200,000 items in 25.4 s; the same eight on four worker pods took 28.6 s. Each partition runs at about 1,000 items a second wherever it runs, because every item waits on one synchronous FX call to issuer-service, and more machines add more waiters, not less waiting. Remote partitioning works and survives a dead pod — 2026-11-01 finished with no gap and no duplicate, 27 s later than it would have — and remote chunking works at 235 times the wire cost per unit of work. Neither buys this job any time today.

What ships: the code for both rungs, the worker Deployment, and the CronJob with `--spring.profiles.active=manager`, because step 29's proofs are Jobs made from that CronJob; the web Deployment stays in-JVM as the baseline. The day to climb is when a business day outgrows one JVM's cores *after* the FX call has stopped being the wait — and then to rung 4, not 5. Chunking only earns its bytes when per-item work is CPU, not a call out, and the payload can stay small.

## Consequences

- Only one chunking manager can run at a time: the replies consumer is `@Profile("manager")`, and a second manager JVM would consume the first one's replies rather than its own.
- `JobOperator.stop()` is manager-side only. It sets a flag the manager's own step checks between dispatches; a worker already running a partition has no flag to see and finishes what it was sent.
- `max.poll.interval.ms=600000` is a hard ceiling, not a tuning knob turned up for safety: a partition that runs longer than ten minutes gets its consumer kicked and its request redelivered while it's still executing. That's only safe because the writer is `on conflict do nothing` — the same guard step 24 built for a different restart path is what makes this one non-destructive too.
- Flyway runs on one role only. Both roles read and write Flyway-managed tables, but the worker profile turns migration off — a worker that somehow became the first pod up would find tables that don't exist yet. Today that can't happen (the worker Deployment shares the image whose manager path has already migrated by the time any CronJob fires), but it's a soft ordering, not a wired one.
- A remote-chunking run costs one serialised round trip per 100 items instead of one dispatch per partition: 4,000 messages against 8 for the same 200,000 rows — a 100-item request is 12,905 bytes and its reply 5,192, against 55 for a partition request, ~36 MB against 440 bytes — and most of that bill is the `StepContribution` graph riding along, not the items.

## What I would revisit

The ceiling step 27 named — one synchronous FX call per item to issuer-service — is still the ceiling here; nothing about workers on other machines touches it.

```text
in-JVM (cluster):     25.4 s for 200,000, 8 partitions on one pod
remote, 4 workers:    28.6 s for 200,000, 8 partitions on 4 pods; ~1,000 items/s per partition either way
```

names how much a rung bought against a ceiling that was never thread count or pod count. Fixing that call — caching the two distinct currencies' rates for the run instead of looking one up per item — is cheaper than any rung on this ladder and should happen before climbing another one. The way back down, if a future team decides this rung wasn't worth it, is one argument (`--spring.profiles.active`) and `replicas: 0` on the worker Deployment.
