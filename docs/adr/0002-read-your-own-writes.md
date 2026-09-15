# ADR 0002: read-your-own-writes for the balance view

Status: accepted (step 13)

## Context

The available balance is served by balance-service from a projection of two
other services' events: account's `EntryPosted` moves the balance, ledger's
`FundsHeld` and `HoldClosed` move the held amount. The projection lags the
write models by the outbox poll (up to 500ms) plus the consumer's own
latency, tens of milliseconds in practice.

A user places a hold and reloads the balance view. If the projection has not
caught up, the hold they just placed is not there. That is the one part of
CQRS a user can see, and it needs a decision, not a shrug.

## Options considered

1. **Sticky read to the write model.** For a few seconds after a write, the
   user's reads go to the service that took the write. Here there are two
   write models behind one number: account for the balance, ledger for the
   holds. The API would have to join them on the fly, which is exactly the
   query the projection exists to avoid, and ledger has no per-wallet holds
   endpoint to join with.
2. **The client sends the version it expects; the API waits for it.** Every
   write already answers with an `X-Request-Id`, and every event a write
   causes carries it as `correlationId` (the saga's commands forward the
   payment's). The projection can therefore know when a given request has
   reached a given wallet, and a read can wait, bounded, for that.
3. **Optimistic UI.** The client shows its own change immediately and lets
   the projection catch up. Cheapest for the server, but it needs a client to
   do it in, and the demo client is curl.

## Decision

Option 2, with the request id as the version.

`GET /api/v1/balances/{account}/{wallet}?after=<X-Request-Id>` waits up to
two seconds until an event carrying that request id has been applied to that
wallet, then answers. If nothing arrives in time it answers anyway, with
`X-Projection: lagging` instead of `caught-up`, so a client can decide
whether to retry or show what it has. Without `?after=` a read is a plain
read and never waits.

The token is per request and per wallet: the projection sets
`done:<requestId>:<account>:<wallet>` (60s TTL) in the same atomic step that
applies the event. A request that touched two wallets is "reached" on each
wallet independently.

## Consequences

- The client has to carry the request id from the write's response to the
  next read. That is one header; the saga already does the same internally.
- A read with `?after=` costs a Redis `EXISTS` every 25ms for at most two
  seconds. Fine at today's traffic; if reads-after-writes ever dominate,
  Redis keyspace notifications replace the poll without changing the API.
- The wait is bounded, so an outage of the projection degrades to stale
  answers with a header on them, never to a hung request.
- Events with no request id (backfilled history, holds the saga placed
  before this step made ledger forward the payment's id) can only be waited
  on by eventId, which no client knows. They are still applied; nobody can
  ask for them.
