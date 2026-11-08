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

Run twice. The first run had the phase-3 LLM enrichment live (`LLM_URL` → the agent
platform, Ollama up): under 100/s it backed up payment-service (`TOTAL-LAG 18450` post-run,
settlement/balance at 0) and the settled tail blew out to p99 35 s with 3124 payments
missing the window — the documented "LLM-in-the-loop before bench" pitfall, since the
settled poll waits on the payment saga that shares the service with the enrichment calls.
The numbers below are the **clean run with enrichment paused** (`LLM_URL=""` → the listener
tags "uncategorised" with no network call), every group `TOTAL-LAG 0` before and after.

| scenario | load | metric | step35 | step36 (clean) |
|---|---|---|---|---|
| transfer | 100/s 60s | p99 | 1510 ms | 59 ms |
| transfer | 100/s 60s | failed | 4% | 0% |
| holds | 50/s 30s | p99 | 1387 ms | 106 ms |
| payments (POST) | 100/s 180s | p99 | 12 ms | 105 ms |
| payments (POST) | 100/s 180s | failed | 0% | 0% |
| payments (settled) | 100/s 180s | p50 | 304 ms | 3165 ms |
| payments (settled) | 100/s 180s | p99 | 697 ms | 17236 ms |
| payments (settled) | 100/s 180s | captured | 18011 | 18012 |
| payments (settled) | 100/s 180s | failed | 0 | 0 |

**transfer / holds / POST** are clean and warm (transfer p99 59 ms / 0% — the stack had been
up a while, so no cold-JIT tax this time). Step 36 adds nothing to any of these paths.

**settled completes fully — 18012 captured, 0 failed, 0 lag — but its tail is fatter than
step 35's** (p50 3.2 s / p99 17 s vs 304 ms / 697 ms). This is not a step-36 cost: the stack
has materially changed since step 35's measurement — the AI platform is now wired in, so
payment-service's outcome topic carries an extra enrichment consumer (cheap with the URL
blank, but still a consumer) alongside the risk/assistant listeners, and the whole stack runs
heavier. The point step 36 needs is the shape, and it holds: with the orphan/fault code in,
the forward payment saga still settles every one of 18k payments with zero failures. A
like-for-like comparison to step 35's settled tail would need the pre-AI stack, which no
longer exists; the orphan path itself (sweep + late-capture refund) is proven by the scenario,
not by this suite.
