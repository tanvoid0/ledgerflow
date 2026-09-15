# ADR 0004: orchestration, not choreography, for the payment saga

Status: accepted (step 12)

## Context

A payment is three calls, three services, three databases: reserve the
wallets (ledger), authorize the amount (issuer), capture the holds
(settlement). Every one of those can fail on its own terms - a decline, a
422, a timeout - and a payment that dies halfway has to undo exactly what it
did, in the right order, however it died. Something has to know what "so
far" means for a given payment and what undoing it looks like.

## Options considered

1. **Choreography.** Each service reacts to the event before it and emits
   its own: ledger sees the payment request and reserves, issuer sees the
   hold and authorizes, settlement sees the authorization and captures. No
   service is special, and adding a step is adding a listener. But nobody
   owns "where is this payment": answering it means walking events across
   three services' logs, and a failure has to be un-reacted-to by every
   listener that touched the payment, in reverse, with each one guessing
   whether it still should.
2. **Orchestration.** One service holds the payment's state and sends a
   command for the next step; the others only answer the command they were
   sent. Adding a step means one more case in one switch, not a new listener
   somewhere else.

## Decision

Orchestration. `payment-service` runs the saga in `PaymentSaga.on(state,
reply)`, a pure function over a sealed `PaymentState` (`Requested`,
`AuthorizationPending`, `CapturePending`, `Captured`, `Failed`): state and
reply in, next state and the commands to send out. No IO in it - every
transition is a unit test that runs in a millisecond
(`PaymentSagaTest`) - and a state Java's switch does not cover fails the
build, not a payment. Each step gets one deadline; a sweeper feeds a
`StepTimedOut` reply through the same function every second, so a stuck
issuer is not a special case, it is a reply like any other. Every failure
path sends its compensations - release the wallets, refund the
authorization, revoke the captures - fire-and-forget, in reverse order of
what could have happened, and a reply that lands after the payment is
already terminal is absorbed rather than acted on: the compensation already
sent covers whatever the late service did.

## Consequences

- The whole flow is one file to read, top to bottom, not three services'
  listeners stitched together by imagining the events between them.
- `payment-service` is a single point that must be up for the flow to make
  progress; ledger, issuer and settlement only need to answer their own
  commands, and any of them can be down for a step without the others
  noticing.
- Every service's reply is a contract `payment-service` depends on by
  shape, not by behaviour: whatever a service does internally, `PaymentSaga`
  only ever sees `FundsHeld`, `PaymentAuthorized`, `CapturesIssued` or their
  failures.
- The saga row is the whole state. There is no in-memory workflow to lose on
  a restart; `Payments.apply` reloads it, decides, and writes the next row in
  the same transaction that queues its commands, so a crash mid-step resumes
  exactly where the row says it was.

## What I would revisit

Compensations are fire-and-forget: `undoEverything` queues `RevokeCaptures`,
`RefundPayment` and `ReleaseWallets` onto the outbox and nothing checks they
landed. The outbox makes delivery durable, but a `RevokeCaptures` that
settlement never acts on - a bug on its side, not a lost message - has
nothing chasing it; today that gap is closed by settlement's own
idempotency, not by the saga. The other candidate is the deadline itself:
step 15's tuned path holds to just above 100 payments/s before the queue
outruns the sweeper's 1s tick and the 15s-per-step deadline starts failing
payments that were only ever waiting in line, not stuck. A saga that scaled
past that ceiling would want the deadline to reflect queue depth, not a
constant.
