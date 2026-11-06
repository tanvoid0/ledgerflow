# 0010 — Charged, no ticket: refund the orphaned capture

Status: accepted (step 36)

## Context

Step 35 (ADR 0009) gave a settled payment a way back: cancel an event, and every CONFIRMED order
is refunded through `RefundOrder` → `RevokeCaptures` → `PaymentRefunded`. It left one hole open,
named in its own plan and in a `ponytail:` comment on `Ticketing.sweep()`:

> a capture landing more than 10 min after an unanswered checkout finds the order FAILED and is
> dropped: charged, no ticket.

The hole is precise. `checkout()` calls payment-service over HTTP and, on a clean 2xx, records the
returned paymentId on the order. When payment-service answers with **no clean result** — a 5xx, a
reset, a read timeout (`RestClientException`) — the payment may still have been created and may
still capture, but ticketing never learns its id: the order stays `PENDING_PAYMENT` with
`payment_id IS NULL`. If the outcome (`PaymentCaptured`) arrives soon, `confirm()` matches it by
the buyer's request id and the order confirms — the system self-heals. But if the sweep's timeout
passes first, the order is marked `FAILED`/`TIMED_OUT` and its seats freed; the late
`PaymentCaptured` then finds no `PENDING_PAYMENT` row to match, and `confirm()` silently does
nothing. The buyer has been debited, holds no seat, and nobody refunds them.

## Decision

**Ticketing detects the late unmatched capture and triggers the existing refund.** The money
reversal is entirely reused from step 35 — no new event, no new topic, no ledger or settlement
change. Step 36 is two small changes in ticketing plus a dev knob:

1. **`confirm()` gains an orphan branch.** The normal `PENDING_PAYMENT` match runs first and is
   untouched. Only when it updates zero rows does the orphan query run: it matches the capture to
   an order that is `FAILED`, `fail_reason = 'TIMED_OUT'`, `payment_id IS NULL`, on the buyer's
   request id plus account, amount and currency, stamps the `payment_id`, and — if that stamped a
   row — appends one `RefundOrder` to the outbox in the same transaction. Stamping the id is the
   idempotency guard: a redelivered capture no longer satisfies `payment_id IS NULL` and emits
   nothing. The happy path pays for no extra query.

2. **`refunded()` settles the orphan.** Its status guard widens from `CONFIRMED` to
   `CONFIRMED OR (FAILED AND fail_reason = 'TIMED_OUT')`, so the returning `PaymentRefunded` moves
   the orphan to `REFUNDED`. The seat-free statement is unchanged and touches only `SOLD` seats;
   the orphan's seats were already freed when the sweep failed it, so that branch frees nothing.

Why in `confirm()` and not in payment-service: payment-service only knows it captured
successfully. It has no ticket, no notion of an order that never confirmed. Ticketing is the only
service that can see a capture arrive for an order it already gave up on.

The orphan clause cannot catch a real decline: `payment_id IS NULL` with `status = 'FAILED'` is
produced only by the sweep, which always sets `fail_reason = 'TIMED_OUT'`. A declined payment goes
through `fail()`, which stamps a `payment_id` from `PaymentFailed`.

## The timeouts are dev knobs

The orphan only forms when the sweep fails the order **before** its capture lands (~700ms). In
production that means a payment-service outage longer than the 10-minute orphan timeout — rare and
real. To show the mechanism without a ten-minute wait, two values became knobs:
`ticketing.orphan-timeout` (default `PT10M`) and `ticketing.sweep-interval-ms` (default `1000`).
The step-36 scenario sets them to `PT0.3S` and `100`, and payment-service gets a dev-only fault
seam (`ledgerflow.payment.fault.drop-response`) that drops the 202 for a request id beginning
`fault-`, after the payment has committed. All three are off by default and never enabled in a real
deployment.

## Consequences

- A buyer charged with no ticket is now made whole automatically, through the same path as a
  cancel refund. One more trigger, zero new money code.
- `confirm()` carries a second query, but only on the capture-misses-everything path; the common
  confirm is unchanged.
- The seat an orphan held is freed at FAIL (by the sweep) and stays free — correct: the buyer never
  got it, and it should return to sale.

## Deferred (not built)

- The real production trigger (a >10-min payment-service outage). The knob makes it testable; the
  mechanism is identical.
- A metric on orphan refunds (`ledgerflow.ticketing.orphan_refunded`) if ops wants the signal —
  one counter in the orphan branch.
- Partial / multi-capture orders: the ledger issues one capture per hold and a refund revokes all
  captures by payment id, so there is no new case.
