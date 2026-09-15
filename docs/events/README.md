# What is wrong with our first event (written at step 07, ticked off as steps land)

Seen on the wire at step 07, one hold request with two wallets:

```
key:     240ae386-8101-419a-8928-2b422b8c1cf0
value:   {"holdId":"240ae386-…","accountId":"11111111-…","wallets":["A-12","A-13"],"expiresAt":"2026-09-14T19:59:14Z"}
headers: __TypeId__ = io.ledgerflow.ledger.domain.event.FundsHeld
```

Contracts, one file per event: `ledger.FundsHeld.md`.

1. Published AFTER the transaction committed. If the broker is down, the hold
   exists and nobody will ever hear about it.                     -> step 10 DONE
   (transactional outbox: the event is a row in the same commit as the hold, a
   poller sends it; measured in `docs/measurements/step-10-dual-write.md`)
2. The event class is defined twice, once in each service. They will drift.
   The `__TypeId__` header already names a class notification does not have;
   the consumer only works because it ignores the header.         -> step 08 DONE
   (one record in ledgerflow-events, a JSON schema next to it, a registry gate)
3. The topic name says nothing about who owns it or which version it is.
                                                                  -> step 08 DONE
   (`ledgerflow.ledger.wallet-hold.events.v1`, keyed by hold id - re-keyed by wallet at
   step 18, `docs/events/ledger.FundsHeld.md`)
4. No eventId, so a consumer cannot tell a redelivery from a new event.
                                                                  -> step 08 DONE (the id), step 11 (using it)
5. The listener's offset commits as soon as it returns, and it returns whether
   or not the email was actually sent. A crash inside the handler redelivers;
   a handler that swallows its own failure loses the work silently.
                                                                  -> step 09 DONE
   (`ack.acknowledge()` after the work; measured in
   `docs/measurements/step-09-consumer-restart.md`. Step 15 moved the commit
   itself from per record to per poll: same guarantee, one round trip per
   batch. Step 19 fixed the group itself: every consumer runs the
   cooperative-sticky assignor and a static `group.instance.id` per instance
   and listener container, 30s session timeout, so a restart is not a
   rebalance at all - `docs/measurements/step-19-groups.md`)
6. A message the listener throws on is retried 10 times back to back, then
   logged and skipped. Not forever: worse. Gone.                  -> step 09 DONE
   (three non-blocking retries on `.retry-*` topics, then `.dlt`; poison
   pills skip the retries)
7. One request placed two holds but published one event carrying the first
   hold's id. The other hold is invisible to every listener.      -> step 08 DONE (one event per hold)
8. No amount on the event. A notification that cannot say "we held GBP 45.00"
   is not much of a notification.                                 -> step 08 DONE (`totalAmount`)
9. The closed, single-type schema pinned one payload shape per topic. When step 12 wanted a
   second event on the hold topic, the registry refused the `oneOf` as a type change under
   BACKWARD, and `HoldRejected` had to take its own topic. New topics start as `oneOf`.
                                                                  -> step 12 (lesson, not a fix)

## payment.PaymentRequested (v1)

Topic:     ledgerflow.payment.requested.events.v1, keyed by paymentId (= envelope.aggregateId).
Fields:    paymentId, accountId, wallets, amount (minorUnits + currency), beneficiary (nullable -
           absent from the JSON entirely, or explicit null, when the caller sent none).
Producer:  payment-service, appended to the outbox inside `Payments.start`'s own transaction - the
           same commit that writes the saga row and queues `ReserveWallets`. Not a saga step: nothing
           replies to it, and a payment risk-service never saw still captures.
Consumers: risk-service only, on its own group, scoring every payment for fraud risk beside the
           authorisation path rather than on it. Full contract: docs/measurements/step-16-risk.md.

## How long a topic remembers

Redpanda has no volume, so `scripts/demo.sh` sets these on every run (create with `-c`, then
`alter-config` unconditionally, so an existing volume converges on the same policy).

| topic kind                    | retention.ms          | cleanup.policy | why |
|--------------------------------|------------------------|----------------|-----|
| `*.events.v1`                  | 2592000000 (30d)       | delete         | the outbox table is the archive; the topic is only the replay window a consumer group can be rebuilt from |
| `*.commands.v1`                | 86400000 (1d)          | delete         | a command older than the saga's 15s deadline is already Failed; a day is for the post-mortem |
| `*.retry-1000\|3000\|9000`     | 86400000 (1d)          | delete         | same lifetime as the topic they retry |
| `*.dlt`                        | 2592000000 (30d)       | delete         | someone has to look |
| `ledgerflow.balance.snapshots.v1` | n/a                 | compact        | keyed by `accountId:label`, keeps the latest snapshot per wallet instead of a window of history; `segment.ms=10000` in dev only (with the cluster floor `log_segment_ms_min` lowered to match, or Redpanda ignores it), so the cleaner runs often enough to watch - never in production |

Replication is a topic property too, set by `scripts/topics.sh`: rf 1 daily, rf 3 under
`scripts/cluster3.sh` - `docs/measurements/step-21-broker.md`.

`Balances.REMEMBER` (step 13's dedup TTL for processed events) now tracks the same 30d as the
events topics it dedups against.
