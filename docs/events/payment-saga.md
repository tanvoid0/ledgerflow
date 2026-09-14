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
| `ledgerflow.ledger.wallet-hold.events.v1` | ledger → payment, notification | `ledger.FundsHeld` (+ optional `reference` since this step) | holdId |
| `ledgerflow.ledger.hold-rejected.events.v1` | ledger → payment | `ledger.HoldRejected` (reference, reason) | reference |
| `ledgerflow.issuer.authorization.commands.v1` | payment → issuer | `issuer.AuthorizePayment` (paymentId, amount) · `issuer.RefundPayment` (paymentId) | paymentId |
| `ledgerflow.issuer.authorization.events.v1` | issuer → payment | `issuer.PaymentAuthorized` (paymentId, authorizationId, amount) · `issuer.PaymentDeclined` (paymentId, reason) | paymentId |
| `ledgerflow.settlement.capture.commands.v1` | payment → settlement | `settlement.IssueCaptures` (paymentId, accountId, holds[], amount) · `settlement.RevokeCaptures` (paymentId) | paymentId |
| `ledgerflow.settlement.capture.events.v1` | settlement → payment | `settlement.CapturesIssued` (paymentId, captureIds[]) · `settlement.IssueFailed` (paymentId, reason) | paymentId |

### Guarantees

Delivery: at least once, everywhere; every consumer dedupes on `eventId` through the inbox.
Ordering: per key. One payment's commands to one service arrive in the order they were queued,
which is what lets a compensation follow the command it undoes on the same topic.
Compensations (`ReleaseWallets`, `RefundPayment`, `RevokeCaptures`) are idempotent and take no reply:
"nothing to undo" is a normal outcome, and the saga is already terminal when it sends them.
