# Step 35 — The organiser cancels, the money goes back

The code story is `docs/adr/0009-the-organiser-cancels.md`; this is the perf
checkpoint. **These are compose numbers, not kind numbers.** Step 34's checkpoint ran
on the kind cluster; step 35 ran on `docker compose` (`scripts/demo-compose.sh`), because
the kind rollout path does a `kind load` that this session is not allowed to drive. Compose
and kind do not share a performance shape — most visibly, the settled tail is *shorter* on
compose here, and the first scenario in the suite pays a cold-JIT price that the warm kind
pods did not. So the step 34 → step 35 column below is **like-for-unlike**: read it as "where
compose lands", not as a regression or an improvement over step 34. The one number that is
genuinely new and genuinely step 35's is the **cancel fan-out** at the bottom.

Host checked before load (Ollama tray + backend stopped — the fraud demo had just woken
gemma4 — plus an Android Studio Gradle daemon killed), every consumer group `TOTAL-LAG 0`,
no `hs_err`, no container restarts across the run. Commit `7e8392c` (+ the uncommitted
`topics.sh` topic fix, below), host AMD Ryzen 9 9950X / 32 threads, Docker Desktop.

## The fixed suite, step 34 (kind) → step 35 (compose)

| scenario | load | metric | step34 (kind) | step35 (compose) |
|---|---|---|---|---|
| transfer | 100/s 60s | p99 | 92 ms | 1510 ms |
| transfer | 100/s 60s | failed | 0% | 4% |
| holds | 50/s 30s | p99 | 5797 ms | 1387 ms |
| payments (POST) | 100/s 180s | p99 | 70 ms | 12 ms |
| payments (settled) | 100/s 180s | p99 | 1400 ms | **697 ms** |
| payments (settled) | 100/s 180s | p50 | 288 ms | 304 ms |
| payments (settled) | 100/s 180s | captured | 18013 | 18011 |
| payments (settled) | 100/s 180s | failed | 0 | 0 |

**transfer** is the cold one. It runs first in the suite, against containers that were built
and started minutes earlier, so it eats the whole stack's C1→C2 JIT warm-up and Docker
Desktop's per-commit fsync on a single volume: p99 1510 ms and 4% of requests (299 of 6202,
most of them in the first seconds) reset or missed. Step 35 changed nothing on the transfer
path — it is pure account-service ledger writes — so this is the documented compose
cold-start signature (memory: fresh containers measure JIT warm-up, p99 2.3s→9ms warm), not
anything the refund work touched. The proof it is warm-up is three scenarios down:

**payments** is the step-35 path's own suite line and it is clean and warm: POST p99 12 ms,
**settled p50 304 / p99 697 ms, 18011 captured, 0 failed**. By the time the 180 s payments run
starts the stack is hot, and compose's settled tail sits well under kind's — the opposite of a
cost. The refund code adds a terminal `Refunded` state and a listener but does not sit on the
capture path, so the forward saga is unchanged.

**holds** p99 1387 ms / 0% is in compose family and, like always, a fat-tailed scenario
(ledger-asks-account-before-writing at 50/s); p50 is 9 ms.

## The new cost: the cancel fan-out

This is what step 35 actually added. One organiser cancel of a sold-out event emits one
`RefundOrder` per CONFIRMED order → one `RevokeCaptures` each in payment-service →
one TREASURY→buyer transfer each in settlement-service. Every one of those debits the **same**
TREASURY wallet row (`99999999-…`), so a big cancel is a burst of concurrent debits on one
row — the watch item the plan called out. Measured with `.local/work/fanout-step35.sh`
(12 single-seat orders on one event, alice, each CONFIRMED before the next; then one cancel):

| what | value |
|---|---|
| orders confirmed (captures in TREASURY) | 12 |
| cancel → all 12 REFUNDED | **701 ms** |
| refund-saga `updated_at` spread across the 12 | **18 ms** |
| final saga states | 12 Refunded |
| seats after cancel | all FREE |

Two things to read off this. First, **the end-to-end cancel cost is ~700 ms for 12 refunds** —
the same order as a single settled payment (settled p99 697 ms above), because the twelve
`RefundOrder`s are produced in one ticketing transaction and then processed in parallel across
payment-service's listener threads, not one-after-another. Second, **the shared TREASURY row is
not a bottleneck at this scale**: all twelve TREASURY→buyer transfers landed within an 18 ms
window. Capture already hammers that row the other way (every capture credits TREASURY), so the
row is used to concurrent traffic; the reverse burst behaves the same. A cancel an order of
magnitude larger would be the next thing to measure — at twelve, the contention the plan feared
simply is not there.

## One fix this run needed

`scripts/topics.sh` did not create the new command topic
`ledgerflow.payment.refund.commands.v1`. Without it, the payment-service consumer would
auto-create the topic on first poll at **one** partition with default retention, where every
other command topic is three partitions / 1-day retention. Added it to the 1-day command-topic
loop alongside `ledger.hold.commands` et al., so compose and kind both provision it correctly.
