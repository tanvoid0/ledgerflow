## ledger.FundsHeld (v1)

Topic:      ledgerflow.ledger.wallet-hold.events.v1
Kind:       event-carried state transfer
Key:        accountId:label, the wallet — the ordering unit is the wallet, not the hold (a hold and its close already share an aggregateId).
Retention:  7 days (broker default)
Producer:   ledger-service, after `POST /api/v1/holds` commits
Consumers:  notification-service, payment-service (the saga's reply), balance-service (the read model).
Sequel:     ledger.HoldClosed on ledgerflow.ledger.hold-closed.events.v1, same key, aggregateVersion 2.
Schema:     libs/ledgerflow-events/src/main/resources/schemas/ledgerflow.ledger.wallet-hold.events.v1-value.json
Java:       io.ledgerflow.events.EventEnvelope<io.ledgerflow.events.ledger.FundsHeld>
Encoding:   plain JSON, no registry framing. The registry gates the schema file, not the bytes.

### Envelope

| Field            | Type      | Null | Notes                                               |
|------------------|-----------|------|-----------------------------------------------------|
| eventId          | UUID      | no   | unique per event. Dedupe on this.                   |
| eventType        | string    | no   | `ledger.FundsHeld`                                  |
| schemaVersion    | int       | no   | 1                                                   |
| aggregateId      | UUID      | no   | the hold. See Key above for the partition key.      |
| aggregateVersion | long      | no   | 1 on placement; the HoldClosed that follows is 2    |
| occurredAt       | timestamp | no   | UTC, when the producer built the event              |
| correlationId    | string    | yes  | the `X-Request-Id` of the request that started it (the payment's, for a saga hold) |
| causationId      | string    | yes  | same request id: the HTTP call directly caused this |
| payload          | FundsHeld | no   |                                                     |

### Payload

| Field       | Type             | Null | Notes                                        |
|-------------|------------------|------|----------------------------------------------|
| holdId      | UUID             | no   | same as aggregateId                          |
| wallets     | array<WalletRef> | no   | never empty. One wallet per hold today.      |
| expiresAt   | timestamp        | no   | UTC. The hold is void after this.            |
| totalAmount | Money            | no   | minor units + ISO-4217, e.g. `{4500, "GBP"}` |

One `POST /holds` with N wallets places N holds and publishes N events, one per hold.

### Guarantees

Delivery: at least once. Consumers MUST dedupe on `envelope.eventId` (nothing does yet: step 11).
Ordering: per wallet (`accountId:label`). Two holds on the same wallet, and a hold and its
close, land on the same partition; nothing is guaranteed between different wallets.
A later event about the same hold can arrive before this one after a consumer rebalance -
check `aggregateVersion` before applying.

### Evolution

Subject `ledgerflow.ledger.wallet-hold.events.v1-value`, compatibility BACKWARD, closed content
model (`additionalProperties: false`). `scripts/check-schemas.sh` is the gate; CI runs it against
the schema `main` ships. Verified against Redpanda v25.2.1:

| change                           | verdict                                                       |
|----------------------------------|---------------------------------------------------------------|
| add an optional field            | compatible                                                    |
| remove a field                   | INCOMPATIBLE                                                  |
| make an existing field required  | INCOMPATIBLE                                                  |
| change a field's type            | INCOMPATIBLE                                                  |
| add a NEW field that is required | compatible (Redpanda gap - a reviewer has to catch this one)  |

Anything incompatible is a v2: new topic `...events.v2`, both produced until every consumer has moved.
