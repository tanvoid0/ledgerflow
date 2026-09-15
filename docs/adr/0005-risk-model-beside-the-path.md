# ADR 0005: the risk model sits beside the path, not on it

Status: accepted (step 16)

## Context

Fraud scoring has to see every payment, but a model on the money path makes
its latency the payment's latency and its outage the payment's outage: a
slow inference or a crashed service would join the seven that already have
to answer before a payment can capture. A regulator asking why a payment was
blocked also wants a decision that is reproducible from a record, not a
number that came out of a model that has since been retrained.

## Options considered

1. **Score inside the saga, before authorising.** The saga asks risk-service
   for a decision as a step, same shape as ledger or issuer. Sees the
   payment before any money moves, so a `BLOCK` can actually stop it. Adds a
   ninth network hop, an ninth outbox and inbox, and an ninth deadline to
   every payment, and a stuck risk-service now stalls money that was never
   in question.
2. **Score the event stream, only record.** risk-service consumes
   `payment.PaymentRequested` like any other listener, decides, and writes
   the decision. It sees every payment exactly once, at its own pace, and
   cannot make a payment wait or fail on its account - which also means it
   cannot stop one.

## Decision

Beside the path. risk-service consumes `payment.PaymentRequested`
(`Payments.start` appends it to the outbox in the same transaction as the
saga's first command, so a payment risk-service never saw still captures
exactly as it did before this step existed). Features - payments in the
last 60 seconds, a z-score against the account's own running mean, whether
the beneficiary is new - are rebuilt from the stream one event at a time
into Redis db 1. `Rules.decide` checks the hard rules first, and only a rule
can produce `Block`; the model's score, at or above `risk.review-threshold`,
can only move a payment to `Review`. Every decision - Allow, Review or Block
- is one append-only row in `risk_decision`, carrying the decision, the rule
if one fired, the model version read from the ONNX file's own metadata, the
score, and the features that produced it.

Two independent things stop the model from ever moving money, deliberately
not just one: the `risk` Postgres role cannot `CONNECT` to any database but
its own (`RiskRoleIT` proves it against a real Postgres), and `ArchitectureTest`
forbids any class under `io.ledgerflow.risk..` from depending on ledger's or
account's domain model, `OutboxAppender`, or `KafkaTemplate`. Either one
closes a different door - a shared table, a Java import, an outbox row - and
between them every path open in this codebase is shut. A hand-added HTTP
call to another service's endpoint is the one door neither locks; that is
also the one a five-minute code review would catch.

## Consequences

- Decisions are recorded, not enforced. Nothing today feeds a `Review` or a
  `Block` back into `PaymentSaga` - a payment risk-service flags still
  captures. Making the decision count is a fourth saga reply
  (`RiskDecided`, next to `FundsHeld`, `PaymentAuthorized`, `CapturesIssued`)
  and a new transition that can hold or reject on it; that is a later step,
  not this one.
- A `Review` gets a case note from a local model (Ollama, `gemma4`) that
  reads the decision's own `features` column and nothing else. Nothing
  downstream reads the note back - `CasesController` is the only consumer -
  so a wrong or missing sentence can embarrass an analyst, never a payment.
- Scoring costs the payment nothing it can feel: p99 0.99ms against a 10ms
  budget, and an A/B (risk-service stopped, then started, twice each) put
  the on/off gap on settled p99 inside the spread two off runs already show
  against each other - the model is not on the ceiling step 15 already
  named.

## What I would revisit

The training set is synthetic - there is no chargeback history on this
ledger to learn from, so `scripts/train-risk-model.py` labels its own
generated rows with a hand-written rule. That is honest about where 0.79 AUC
comes from, but it is not a model that has seen a real fraud. `risk_decision`
already carries the features every decision was made from; a v2 trains on
that table once analysts have labelled enough of it as right or wrong, which
is also the point at which the rule-based label can retire.
