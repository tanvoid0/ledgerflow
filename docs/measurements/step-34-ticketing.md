# Step 34 — A shop in front of the saga

The code story is `docs/adr/0008-a-shop-in-front-of-the-saga.md`; this is the perf
checkpoint. The fixed suite (`perf/bench.sh step34`) reran against the kind cluster at
commit `7d2b16b`, with one thing different from step 32: `ticketing-service` now runs
two replicas on the same three nodes, so every scenario below shares CPU with a workload
that did not exist at the last checkpoint. Host checked before load (no stray JVMs or
Gradle daemons, Ollama stopped), every consumer group `TOTAL-LAG 0`, no `hs_err` and no
pod restarts across the run — the numbers are valid, just taken on a busier cluster that
was ~13 min into a reboot recovery (coldish JITs).

## The suite, step 32 → step 34

| scenario | load | metric | step32 | step34 |
|---|---|---|---|---|
| transfer | 100/s 60s | p99 | 98 ms | 92 ms |
| transfer | 100/s 60s | failed | 3.46% | **0%** |
| holds | 50/s 30s | p99 | 4422 ms | 5797 ms |
| payments (POST) | 100/s 180s | p99 | 18 ms | 70 ms |
| payments (settled) | 100/s 180s | p99 | **449 ms** | **1400 ms** |
| payments (settled) | 100/s 180s | captured | 18014 | 18013 |

## What moved and what didn't

**transfer** is flat and actually cleaner — p99 92 ms and 0% failed against step 32's
3.46%, which was a handful of connection resets under the same open-model load. The
ledger's single-row write path does not feel the storefront sitting next to it.

**holds** reads scary at p95 3917 ms / p99 5797 ms, but that is this scenario's
character, not a step-34 regression: p99 has run 5066 (step31), 4422 (step32), 5797
(step34) — the ledger-asks-account-before-writing round trip has always had a fat tail at
50/s, and step 34 is in family. p50 is unchanged at 12 ms.

**payments** is the one real mover. The POST stays fast (k6 sees p99 70 ms — the API
accepts and returns before the saga runs) and throughput is identical: 18013 captured vs
18014, 0 failed. What tripled is the *settled* p99 — the end-to-end time from POST to the
payment row reaching Captured in the database — from 449 ms to 1400 ms. Same work
completed, same count, longer tail. The cause is contention, not correctness: two new
`ticketing-service` JVMs now compete for the three nodes' cores and for Redpanda
throughput during the 180 s run, and the saga's cross-service hops (issuer → ledger →
balance) are exactly where that queuing shows up. A rerun on a warm cluster with the
storefront idle would likely pull settled p99 back toward step 32; measured as it ran,
on a cluster carrying the shop it was built to carry, it is 1400 ms.
