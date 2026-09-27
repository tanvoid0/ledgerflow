# ADR 0008: a shop in front of the saga

Status: accepted (step 34)

## Context

Every payment so far has been typed by hand or by k6: `POST /api/v1/payments` with an accountId,
a wallet and an amount already decided. A storefront is the more honest front door - a buyer picks
seats, not wallets - and it is also the first caller of the saga that is not the payment's own
account holder typing their own accountId into a body. Two things the saga never needed before:
something has to own seats and orders, and something has to tell that owner how the payment came out.

## Options considered

1. **Ticketing infers the outcome from the ledger's own commands** (`CaptureHolds` means captured,
   a compensation means failed). Rejected: it couples a new service to the saga's internal command
   traffic, which ADR 0004 keeps deliberately private to `payment-service`, and `HoldRejected` sends
   no command at all (PaymentSaga.java:55) - there is nothing on that path to infer failure from.
2. **`payment-service` publishes a terminal outcome event** and ticketing (or anyone else who pays
   through it) subscribes. Chosen: one new topic, `oneOf` `payment.PaymentCaptured` /
   `payment.PaymentFailed`, appended in `Payments.apply` exactly when the transition function lands
   on a state with no next step. The transition function itself does not change.
3. **A holds table, or Redis, for the seat lock.** Rejected: the hold has exactly one property
   nothing else needs - it expires - and an order row already has a natural home for `expires_at`.
   One table, one partial index (`WHERE status = 'HELD'`), a 1s `@Scheduled` sweep; no second store.
4. **A new Keycloak client for ticketing to call payment-service as itself**, mirroring
   `ledger-service`'s client-credentials call to account-service. Rejected for this path: the caller
   already holds a token that satisfies payment-service's own resource-server chain. Checkout relays
   the buyer's own bearer and `X-Request-Id`; payment-service's `correlation_id` becomes the id
   ticketing already put on the order (`request_id`), which is what makes the outcome consumer's
   fallback match (`payment_id IS NULL AND request_id = :correlationId`) mean anything.
5. **Public catalog GETs by loosening the shared starter's chain.** Rejected: `LedgerflowSecurityAutoConfiguration`
   is `@ConditionalOnMissingBean(SecurityFilterChain.class)` precisely so a service can want something
   different without every other service inheriting it. `TicketingSecurityConfig` declares its own chain,
   same shape as `GatewaySecurityConfig`; the starter is untouched.

## Decision

Options 2-5, plus what they touch downstream:

- **Ticketing owns seats and orders**, and pays through the existing saga over HTTP with the buyer's
  own token - no new service identity, no new saga step. `payment-service` gains the terminal outcome
  topic described above; the wire contract and SQL are in `.local/work/step-34-plan.md` §2.
- **The hold is the order row.** `HELD` expires on its own clock; `PENDING_PAYMENT` never does - the
  saga's own step deadlines guarantee an outcome eventually arrives, so the sweep only ever touches
  seats nobody has started paying for.
- **`sub` is the accountId.** Alice's Keycloak `id` is seeded to match the account id her orders carry;
  no mapper, no lookup, and the token is unforgeable evidence of who is asking.
- **The dev portal** (out of this ADR's own service, but decided alongside it) reads Prometheus and
  Tempo through the gateway, behind the `dev` role, rather than exposing Grafana's own port to the
  browser: one more path-prefixed route, the same bearer the storefront already sends.

This extends ADR 0004: one more event leaves the saga, the transition function `PaymentSaga.on` does
not gain a case for it, because nothing replies to it. It extends ADR 0007: a third area (buy tickets)
in the one SPA, behind the one gateway, needing no new origin and no new login.

## Consequences

- A payment-service outage now blocks ticket sales too, same as it already blocks transfers: one more
  caller of an already-single point of failure, not a new one.
- Organiser netting is deferred. Ticketing sends `beneficiary = event.organiser` so risk-service sees
  it and the settlement path is ready, but `settlement_item.merchant_id` still has no production
  feeder (only test data sets it) - an organiser payout is a later step, not this one.
- A `PENDING_PAYMENT` order whose outcome never arrives - a bug, not a timeout the saga already covers -
  stays stuck with its seats HELD forever; `ponytail:` noted at the sweep, an admin release endpoint
  is the upgrade if it ever happens outside a test.
- `TicketingSecurityConfig` permits `GET /api/v1/events` and `/api/v1/events/**` before requiring a
  token for everything else, the same override shape `GatewaySecurityConfig` already established -
  a third service doing this is the point at which it stopped being gateway-specific plumbing.
- Customer tokens made "any token" the wrong default: every service is also on its own host port, so
  the gateway's role rules were one door of several. The starter's chain now wants a staff role
  (`account-read`, `ledger-write` or `dev`); payment-service opens `POST /api/v1/payments` to any token
  (the controller checks the account) and balance-service lets a customer read its own account's
  balances, each in its own chain of the same shape.
