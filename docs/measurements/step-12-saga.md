# Step 12: a workflow across three services, with no transaction spanning them

Six services on one laptop, compose Postgres + Redpanda, `ledgerflow.step-deadline` 15s.
Payment-service orchestrates; ledger, issuer and settlement each answer commands on their
own topic and reply with events. Every command and reply goes out through an outbox and in
through an inbox, so nothing below depends on a lucky delivery.

## Happy path

```
POST /api/v1/payments {"accountId":"1111…","wallets":["A-12"],"amountMinor":4500,"currency":"GBP"}
```

| after | payment state | ledger `funds_holds` | account `A-12` |
|---|---|---|---|
| POST returns (202) | Requested | - | 100.00 |
| ~1s | AuthorizationPending | HELD | 100.00 |
| ~2s | CapturePending | HELD | 100.00 |
| ~3s | Captured, one capture id | CAPTURED | 55.00 |

payment-service log, one line per transition:

```
payment 23d8ea2b: Requested + FundsHeld -> AuthorizationPending
payment 23d8ea2b: AuthorizationPending + PaymentAuthorized -> CapturePending
payment 23d8ea2b: CapturePending + CapturesIssued -> Captured
```

## The three failures

| failure | how it was forced | final state | hold | issuer row | commands sent to undo |
|---|---|---|---|---|---|
| issuer declines | `amountMinor: 1` (the stub's rule) | Failed / PAYMENT_DECLINED at AUTHORIZE | RELEASED | none | ReleaseWallets |
| capture fails | `amountMinor: 20000` on a wallet holding 100.00; account answers 422 | Failed / ISSUE_FAILED at ISSUE | RELEASED | REFUNDED | RevokeCaptures, RefundPayment, ReleaseWallets |
| issuer never answers | `scripts/freeze.sh 8086`, wait past 15s | Failed / TIMED_OUT at AUTHORIZE | RELEASED | see below | RefundPayment, ReleaseWallets |

## The late reply

Thaw the issuer after the saga has already failed the payment:

```
payment de72a3f0: Requested + FundsHeld -> AuthorizationPending
payment de72a3f0 waited too long at AUTHORIZE
payment de72a3f0: AuthorizationPending + StepTimedOut -> Failed
PaymentAuthorized changes nothing for payment de72a3f0 in Failed
```

The thawed issuer reads its topic in order: `AuthorizePayment` first (it authorizes and replies),
then the `RefundPayment` the saga had already queued (it reverses it). Issuer's row ends REFUNDED,
the wallet ends RELEASED, and the reply that arrived after the fact was absorbed by the terminal
state. That last line is the whole reason the transition function ignores instead of throwing.

## What the registry taught us on the way

Adding `HoldRejected` to the v1 hold topic was refused: turning the closed payload object into a
`oneOf` is a type change under BACKWARD. The rejection got its own topic; the topics created in this
step start as `oneOf` so a second command or reply can join them later.
