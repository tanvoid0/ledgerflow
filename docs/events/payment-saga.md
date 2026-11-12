## The payment saga's messages (v1)

Payment-service orchestrates. It sends commands on the recipient's command topic and listens for
the recipient's events as replies. Everything rides in the same `EventEnvelope`; a reply carries the
command's `correlationId` and names the command's `eventId` as its `causationId`. Every message is
keyed by the payment id (commands) or, for `FundsHeld`, by the hold; the `reference` inside points back.

Schemas: one file per topic in `libs/ledgerflow-events/src/main/resources/schemas/`, BACKWARD, closed.
A topic with several types uses `oneOf` on `payload`, keyed by `eventType`. Java: `io.ledgerflow.events.{ledger,issuer,settlement}`.

| topic | direction | types | key |
|---|---|---|---|
| `ledgerflow.ledger.hold.commands.v1` | payment → ledger | `ledger.ReserveWallets` (reference, accountId, wallets, amount) · `ledger.ReleaseWallets` (reference) · `ledger.CaptureHolds` (reference) | reference |
| `ledgerflow.ledger.wallet-hold.events.v1` | ledger → payment, notification | `ledger.FundsHeld` (+ optional `reference` since this step) | `accountId:label` (the wallet, since step 18; was holdId) |
| `ledgerflow.ledger.hold-rejected.events.v1` | ledger → payment | `ledger.HoldRejected` (reference, reason) | `accountId:label` of the first wallet (since step 18; was reference) |
| `ledgerflow.issuer.authorization.commands.v1` | payment → issuer | `issuer.AuthorizePayment` (paymentId, amount) · `issuer.RefundPayment` (paymentId) | paymentId |
| `ledgerflow.issuer.authorization.events.v1` | issuer → payment | `issuer.PaymentAuthorized` (paymentId, authorizationId, amount) · `issuer.PaymentDeclined` (paymentId, reason) | paymentId |
| `ledgerflow.settlement.capture.commands.v1` | payment → settlement | `settlement.IssueCaptures` (paymentId, accountId, holds[], amount) · `settlement.RevokeCaptures` (paymentId) | paymentId |
| `ledgerflow.settlement.capture.events.v1` | settlement → payment | `settlement.CapturesIssued` (paymentId, captureIds[]) · `settlement.IssueFailed` (paymentId, reason) | paymentId |
| `ledgerflow.payment.refund.commands.v1` | ticketing → payment (since step 35) | `payment.RefundOrder` (paymentId) | paymentId |
| `ledgerflow.payment.outcome.events.v1` | payment → anyone who paid through it (ticketing) | `payment.PaymentCaptured` (paymentId, accountId, amountMinor, currency) · `payment.PaymentFailed` (paymentId, reason, failedAt, accountId, amountMinor, currency) · `payment.PaymentRefunded` (paymentId, accountId, amountMinor, currency, since step 35) | paymentId |

The outcome topic is not a saga step - nothing replies to it. `Payments.apply` appends it once, when the
transition lands on a terminal state (`next.step() == null`), so it is not itself part of the switch above.

Refund (step 35, ADR 0009) is not a saga reply either. A `RefundOrder` for a Captured payment makes
`Payments.refund` emit the existing `settlement.RevokeCaptures` (TREASURY → buyer) and announce
`payment.PaymentRefunded` on the outcome topic, moving the saga to a terminal `Refunded`. Any
non-Captured state is a no-op, so a redelivered command or a double cancel changes nothing.

`RefundOrder` has a second trigger since step 36 (ADR 0010): a capture arriving for an order the
sweep already failed — charged, no ticket. Ticketing's `confirm()` detects the late unmatched
capture, stamps the order (its idempotency guard) and emits the same `RefundOrder`. Same money
path, new trigger.

`RefundOrder` has a third trigger since step 37 (ADR 0011): the buyer cancelling their own
CONFIRMED order outside the 24h-to-event cutoff. Same `refundOrder()` call the organiser-cancel
path uses, different caller — payment-service sees no difference between the three.

### The organiser payout (ADR 0012)

Captured money reaches the organiser outside the payment saga, through the nightly settlement batch.
On `PaymentCaptured`, ticketing's `confirm()` appends `settlement.SettleItem` (on
`ledgerflow.settlement.item.commands.v1`, keyed by payment id) naming the order's organiser as the
merchant and the event's UTC day as the business date; settlement inserts it as a `settlement_item`,
so the organiser is paid the night after the event. A refund before then marks the item REVOKED.
The nightly batch nets each merchant and
emits `MerchantSettled` (`ledgerflow.settlement.merchant.events.v1`); settlement's own
`MerchantPayoutListener` consumes it and transfers the net from the settlement wallet (TREASURY) to
the organiser's MAIN wallet, idempotency-keyed `payout-<merchantId>-<businessDate>`. None of this is
a saga reply — the saga is terminal the moment money lands in TREASURY.

### Guarantees

Delivery: at least once, everywhere; every consumer dedupes on `eventId` through the inbox.
Ordering: per key. One payment's commands to one service arrive in the order they were queued,
which is what lets a compensation follow the command it undoes on the same topic.
Compensations (`ReleaseWallets`, `RefundPayment`, `RevokeCaptures`) are idempotent and take no reply:
"nothing to undo" is a normal outcome, and the saga is already terminal when it sends them.
