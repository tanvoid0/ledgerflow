# 0011 — The buyer cancels: self-service refund

Status: accepted (step 37)

## Context

Steps 35 and 36 (ADR 0009, 0010) built one refund primitive — `RefundOrder` (ticketing → payment)
→ `Payments.refund` (Captured-only guard, `RevokeCaptures`, flip to `Refunded`) → `PaymentRefunded`
(payment → ticketing, idempotent on the order/account/amount/currency match) — and fed it from two
triggers: an organiser cancelling the event, and the sweep's orphan-capture detector. Both deferred
lists after step 35 named the third: the buyer cancelling their own order, unprompted.

## Decision

**A buyer can cancel their own CONFIRMED order outside a 24h-to-event cutoff; it fires the same
`RefundOrder` as the other two triggers.** No change to payment-service or the wire contract.

1. **Eligibility lives in ticketing-service only** — it is the one service that knows an event's
   `starts_at` and `status`. `Ticketing.cancelOrder(orderId, accountId)`: loads the order, 404s on
   owner mismatch (same hide-existence rule as `byId` — a stranger gets the same answer as a
   non-existent order, not "not yours"), 409s if the event is not `ON_SALE` or starts within
   `CANCEL_CUTOFF` (`Duration.ofHours(24)`, a constant beside `MAX_OPEN_ORDERS`, not a config knob —
   this step proves the cutoff by picking a near event in the scenario, not by shrinking the window).
   Eligible → delegates straight to the existing `refundOrder(orderId, "buyer " + accountId)` used by
   the organiser-cancel path.
2. **`OrdersController` gains `POST /{id}/cancel`**, `@PreAuthorize("hasRole('customer')")`, 202 — a
   customer-role endpoint beside the existing staff-role `/refund`.
3. **Payment-service is untouched.** `RefundOrder` carries no "why"; the money move for a buyer
   cancel, an organiser cancel, and an orphan capture is identical. A race between a buyer cancelling
   and the organiser cancelling the same order in the same second produces two `RefundOrder`s; the
   second is a no-op under the existing Captured-only guard — the same guarantee step 35 already
   relies on for a redelivered cancel.
4. **Web**: `OrderPage.tsx` grows a `CancelAction` (ghost button → inline confirm, mirroring
   admin/Events.tsx's cancel pattern but with a plain `Button variant="danger"`, not `WriteButton` —
   a customer has no ledger-write role to show). Inside the cutoff, a static line explains why
   cancellation is closed rather than just hiding the button. A `cancelling` flag forces polling past
   CONFIRMED (previously dead once CONFIRMED) until REFUNDED. The REFUNDED card's copy, previously
   always "this event was cancelled" (a mislabel inherited from step 35, exposed by step 36's orphan
   case which has no cancelled event at all), now branches on the event's own status.

## Consequences

- Buyers get a self-service refund through the same path two other triggers already proved —
  one more caller, zero new money code, zero schema change.
- The owner-mismatch 404 (not 403) keeps "does this order exist" and "is it yours" indistinguishable
  from outside, consistent with the rest of ticketing's order lookups.
- The web fix for the REFUNDED-copy mislabel is a real bug fix riding along with this step, not new
  scope: it was wrong the moment step 36 shipped an orphan refund with no cancelled event behind it.

## Deferred (not built)

- **Partial refunds** (cancelling some but not all seats on a multi-seat order). The current schema
  can't express it: `orders` carries one `amount_minor`, one `payment_id`, one `status` for the whole
  order; `Payments.refund` revokes every CAPTURED capture under the payment id with no amount of its
  own; `RefundOrder`/`PaymentRefunded` carry no amount to partially match against; `Refunded` is a
  terminal saga state with nowhere to put "refunded 1 of 3 seats". Doing this needs an amount field on
  both events (a BACKWARD-compatible schema change), a partial-revoke in settlement's `captures`
  table, a non-terminal saga state, and either split seat/amount rows on `orders` or child order rows.
  A real subsystem change, not a one-step add; next in line if partial cancels get asked for.
- **A cooling-off window shorter than "any time before 24h"** (e.g. instant refund in the first 10
  minutes, partial after). Same primitive, just a second cutoff constant — not worth a knob until a
  step wants to demonstrate it.
- A metric on buyer-initiated cancels (`ledgerflow.ticketing.buyer_cancelled`), same shape as step
  36's deferred orphan-refund counter, if ops wants to tell the three triggers apart on a dashboard.
