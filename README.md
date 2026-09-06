# LedgerFlow

A double-entry payment ledger with authorisation holds, capture and refund, run
across services that communicate by events. No distributed transaction anywhere,
and no path by which the risk model can move money.

Work through it with `ledgerflow.html`, pointed at `ledgerflow.json`.
Its **The plan** tab sequences the steps into three milestones against the
market data in [skill-gaps.md](skill-gaps.md), with apply-while-building rules.

## Where this is going

Four milestones. Each one ends with something that runs and something worth
showing, so the repository is presentable from the end of week one rather than
from the end of week four.

| | Milestone | Steps | What exists at the end of it |
|---|---|---|---|
| A | The money is correct | 01 to 03 | One service. Double-entry postings that sum to zero, an idempotency key on the authorisation, and a concurrency test that overdraws the account without the lock and cannot with it. |
| B | Two services, and the events between them | 04 to 09 | A second service, a deliberate cascading failure you caused and measured, then a published event contract that removes the coupling that caused it. |
| C | Exactly once, or the money is wrong | 10 to 12 | The dual write reproduced in both directions and fixed with an outbox, consumers that survive redelivery, and an authorise to capture workflow with compensation and no distributed transaction. |
| D | Numbers, and a model that never touches the ledger | 13 to 17 | A rebuildable read model, one trace across the whole authorisation, a measured before and after on p99, a streaming fraud score, and a front door somebody will read. |

## The three properties this is judged on

Every payment system is read for the same three things. Each one gets enforced
in a named place and proved by a named test, and those pairs are what the README
leads with once they exist.

| Property | Enforced by | Proved by |
|---|---|---|
| No payment applies twice | idempotency key, unique index, optimistic lock | `AuthorizeConcurrencyTest` |
| Every posting set sums to zero | domain invariant on the aggregate | `LedgerInvariantTest` |
| No event lost between database and broker | outbox written in the business transaction | `OutboxCrashTest` |

## Deliberately not here

Kubernetes, an API gateway, an identity provider, a front end, and multi-region
anything. All five are real work, none of them is what a payments team is short
of evidence for, and each one costs a week that milestone D spends better.

The risk model is advisory by construction. Rules hold the veto and the score
only moves a payment into review, because a deterministic rule can be explained,
reproduced from the audit log and tested, and a score cannot. There is a test
that fails if the risk service is ever given a write path to the ledger.

## Stack

Java 25, Spring Boot 4.1, Maven, PostgreSQL 17, Kafka via Redpanda, Gatling,
ONNX Runtime. Everything else arrives at the step that needs it.
