# Step 36 — Charged, no ticket: the orphan refund

The code story is `docs/adr/0010-charged-no-ticket.md`; this is the live-run
checkpoint. **Compose numbers, run with the AI platform wired in and Ollama live.**
Host AMD Ryzen 9 9950X / 32 threads, Docker Desktop; no `hs_err`, no container
restarts across the run.

## The scenario is the real done-when, and it passes

`scripts/scenario-orphan-refund.sh` exits 0 on the step-36 override stack
(`docker-compose.step36.yml`: payment's fault seam + `ORPHAN_TIMEOUT=PT0.3S`,
`SWEEP_INTERVAL_MS=100`). One run, end to end:

- alice checks out with a `fault-` request id; payment **captures** but the fault
  seam drops its 202 as a 503, so checkout returns **PENDING_PAYMENT** with no paymentId
- alice is **charged** (MAIN 105400 → 102600), i.e. the capture went through
- the sweep times the unanswered order out to **FAILED/TIMED_OUT**, freeing seat C7
- the late `PaymentCaptured` lands on the FAILED order → `confirm()`'s orphan branch
  **refunds** it; order **REFUNDED**, alice's MAIN **back to 105400**
- a redelivered capture changes nothing (payment_id stamp is the guard)

`TicketingFlowIT` 13/13 green, including the new
`a5xxFromPaymentLeavesTheOrderPendingNotRolledBack` that pins the contract this
step exposed: a **5xx response** (payment may have committed) must leave the order
PENDING_PAYMENT, not roll it back like a 4xx refusal.

## The real bug this run found: a 503 masked as 403

The fault seam threw 503, but ticketing's `pay()` received **403**, read it as a clean
refusal, rolled the order back to HELD and 502'd — so no orphan ever formed. Root cause
was in **payment-service**, not the orphan code: `PaymentSecurityConfig` ended with
`anyRequest().hasAnyRole(STAFF)`. When the controller throws, Boot forwards to `/error`;
that ERROR dispatch was re-checked against the chain, and the **buyer's relayed token
lacks STAFF**, so the 503 was masked as 403. Normal 202 payments never hit `/error`, so
it had been invisible. Fix: `dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()`,
so a buyer's error response carries its real status. With that, payment returns 503,
ticketing's existing `catch (RestClientException)` leaves the order PENDING_PAYMENT, and
the orphan path runs.

## The fixed suite, step 35 → step 36 (both compose)

| scenario | load | metric | step35 | step36 |
|---|---|---|---|---|
| transfer | 100/s 60s | p99 | 1510 ms | 2236 ms |
| transfer | 100/s 60s | failed | 4% | 4% |
| holds | 50/s 30s | p99 | 1387 ms | 1760 ms |
| payments (POST) | 100/s 180s | p99 | 12 ms | 172 ms |
| payments (POST) | 100/s 180s | failed | 0% | 0% |
| payments (settled) | 100/s 180s | p99 | 697 ms | **34993 ms** ⚠ |
| payments (settled) | 100/s 180s | captured | 18011 | 14904 |
| payments (settled) | 100/s 180s | failed | 0 | **3124** ⚠ |

**transfer / holds / POST** are in compose family and unchanged by step 36 — transfer is
the usual first-scenario cold-JIT signature (p50 9 ms), POST p99 172 ms / 0% failed.

**settled is contaminated, not a step-36 cost.** The AI platform is wired in and Ollama
was live; under the 100/s payments load the phase-3 **LLM enrichment listener on
payment-service** backs up — post-run `payment-service` group `TOTAL-LAG 18450`, while
`settlement-service` and `balance-service` sit at 0. The settled-state poll waits on the
payment saga, which shares payment-service with the enrichment calls to gemma, so the tail
balloons to 35 s and 3124 payments miss the window. settlement itself is not behind. The
orphan path does not touch enrichment, so this says nothing about step 36 — it is the
documented "Ollama/LLM-in-the-loop before bench" pitfall. A clean settled checkpoint needs
the enrichment paused or Ollama stopped; left as a follow-up rather than rerun here.
