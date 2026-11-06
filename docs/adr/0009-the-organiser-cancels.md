# 0009 — The organiser cancels, the money goes back

## Context

ADR 0008 put a shop in front of the payment saga and said, in as many words, what it did not do:
when an organiser cancels an event, "SOLD stays sold, no refunds." A buyer who had paid was left
charged for an event that would never happen. That is a correctness hole, not a missing nicety, so
this step closes it: cancelling an event refunds every confirmed order on it.

The payment saga (ADR 0004) is a one-shot forward workflow. `Captured` and `Failed` are terminal
and ignore every later reply — on purpose, so a late message is covered by the compensations the
saga already sent. A refund of a payment that has already settled is therefore not a new reply the
saga can take; it is a new, separate action on a finished payment. The design question was how to
reverse settled money without reopening the saga or inventing a second one.

## What capture actually did

The money does not move in the ledger's hold machinery. On `IssueCaptures`, settlement-service
transfers the buyer's wallet to the TREASURY wallet (`account-service`'s transfer API, idempotency
key `capture-<holdId>`) and records a `captures` row. The hold being closed CAPTURED afterwards only
tells balance-service to drop the held amount from its projection. The organiser is never credited —
captured ticket money sits in TREASURY (organiser payout is still deferred).

Crucially, settlement-service already knows how to undo this. `RevokeCaptures` drives
`CaptureCommandListener.revoke()`, which reads the `captures` rows for a payment and transfers
TREASURY back to the buyer (key `revoke-<captureId>`), then marks each capture REVOKED. The saga's
own failure path already emits `RevokeCaptures` to compensate a half-done capture. The reverse we
need was built; nothing drove it for a *settled* payment.

## Decision

A refund is a new, short, event-driven action that reuses the existing reversal:

- **Ticketing drives it on cancel.** `cancelEvent`, in the same transaction that marks the event
  CANCELLED, appends one `RefundOrder(paymentId)` to its outbox for each CONFIRMED order that has a
  payment, correlated by the order's original request id. This is the first time ticketing produces
  events at all — step 34 only called payment-service over HTTP — so it grows an outbox, wired from
  the shared starter, rather than a bespoke producer.
- **Payment reverses it, once.** A new `RefundOrderListener` calls `Payments.refund`, which loads the
  saga row `FOR UPDATE` and acts only if the state is `Captured`; anything else (already refunded,
  failed, still in flight, unknown) is a logged no-op, so a redelivered command or a second cancel is
  harmless. On a Captured payment it emits the existing `RevokeCaptures`, moves the saga to a new
  terminal `Refunded`, and announces `PaymentRefunded` on the step-34 outcome topic — all in one
  transaction. No case is added to `PaymentSaga.on`; the transition function stays pure.
- **`Captured` now remembers who paid and how much.** The refund announcement needs the account and
  amount, and the saga row has no account column, so `Captured` carries `accountId` and `amount`
  alongside its capture ids. A `Captured` row written before this change has neither; `refund`
  guards for that and skips it with a warning rather than failing the listener forever.
- **Ticketing closes the loop.** `PaymentOutcomeListener` already owns the outcome topic for
  `PaymentCaptured` and `PaymentFailed`; it gains a third case for `PaymentRefunded`, moving the
  order CONFIRMED → REFUNDED and its seat SOLD → FREE, matched on payment/account/amount/currency so
  a replay does nothing.
- **The SPA shows it as money returned, not a failure.** REFUNDED is a terminal order status with a
  brand-toned badge (distinct from FAILED's error tone) and a line that the event was cancelled and
  the money is back.

This extends ADR 0008's one outcome topic — a third event type rides it, consumers that read one read
all three — and reuses ADR 0004's compensation primitive instead of adding a parallel one.

## Consequences

- **No ledger or settlement change.** The whole money reversal is the revoke that already existed;
  this step only names the request and the answer and triggers it. The diff is two events, a listener
  and a method per service, not a new transactional path through the ledger.
- **A cancel is a fan-out.** One cancel of a sold-out N-seat event emits N `RefundOrder`s → N
  `RevokeCaptures` → N TREASURY→buyer transfers, every one debiting the single TREASURY wallet. The
  step 35 bench measures cancel-to-all-REFUNDED latency and whether that row becomes a contention
  point under a large cancel.
- **A `PENDING_PAYMENT` order at cancel time is not refunded by this cancel.** It still rides its
  outcome to CONFIRMED; its money is only returned if the event is cancelled again afterwards. A
  cheap upgrade is for `confirm()` to check the event status and refund itself if the event is
  already CANCELLED; left for later and named here.
- **Deferred, still.** Self-service and partial buyer refunds; the organiser payout that is the other
  half of the money story; and refunding the "charged, no ticket" sweep orphan from ADR 0008 (a
  different trigger, but the same `RefundOrder` primitive now that it exists).
