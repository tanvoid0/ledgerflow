# Step 18 — partitions decide everything

## The key we had

`ledgerflow.ledger.wallet-hold.events.v1`, three partitions, keyed by the hold
id (`aggregateId`). A hold id occurs exactly once - a hold only ever gets one
`FundsHeld` - so "one key, one partition" was trivially true and told a
consumer nothing. The question that matters is what happens to one *wallet*
across its several holds, and decoding the topic by `payload.wallets[0].label`
answered it: wallet A-1's five pre-migration holds landed on partitions 2, 0,
1, 1, 2 - three different partitions for the same wallet, because a random
UUID carries no relationship to the wallet it belongs to. A consumer that
needs "everything about wallet A-1 in order" got no such guarantee.

## The key we chose

`PlaceHold.partitionKey`: one wallet keys by `accountId:label`; a hold that
spans several wallets falls back to `accountId` alone, coarser but still
ordered per wallet. Placing HTTP holds and a saga hold (`ReserveWallets`, the
same code path through `HoldCommandListener.reserve`) against a running stack
confirmed it holds across both call paths: every `A-1` `FundsHeld`, old test
data and new, landed on partition 2; every `A-2` on partition 0.

Not account-only everywhere. This demo has one account, so an account-only key
would put every hold event this system produces on a single partition -
correct ordering, zero parallelism, for a load the wallet key already spreads
across three. And the aggregate stays the hold, not the wallet: `aggregateId`
still names one hold and its `aggregateVersion` (1 on placement, 2 on close)
still counts what happened to *that* hold. The partition key is a separate,
optional column (`outbox.partition_key`) precisely so the envelope's identity
and the topic's ordering unit don't have to be the same thing. The multi-wallet
branch of `partitionKey` exists for that reason and is otherwise unexercised:
`PlaceHold.place` saves one `FundsHold` per wallet and appends one event per
hold, so every event ledger-service builds today carries exactly one wallet.

## Broken on purpose

No HTTP release endpoint exists - release only ever arrives as a Kafka
command, sent by `PaymentSaga` on failure - so "place a hold and release it"
became `POST /api/v1/payments` with `amountMinor: 1`, always declined by the
issuer, which reserves the wallet then releases it through the saga's normal
compensation. `OutboxPublisher.java`'s `kafka.send(p.topic(), p.key(),
p.payload())` was cut back to `kafka.send(p.topic(), p.payload())` - null key,
round-robin/sticky partitioning - rebuilt, and run 25 rounds (5, then 20 more
once the first 5 showed nothing) against wallet A-1, with `GET
/api/v1/balances/.../A-1` polled every 50ms the whole time:

```
wallet-hold.events.v1 (FundsHeld, unkeyed/broken)   25/25 on A-1:
  partition 1: 22 records     partition 2: 3 records
hold-closed.events.v1 (HoldClosed, unkeyed/broken)  25/25 on A-1:
  partition 0: 25 records
```

Same wallet, same topic, split across two partitions for `FundsHeld` - the
defect the key fixes, reproduced live. `heldMinor` never showed the fault a
consumer relying on order would fear: every one of 297+ samples was either the
account's pre-existing baseline or one hold's worth above it, never negative,
never stacked. That is not the fix working - the build under test had no key
at all - it is `balance-service` already being safe against reordering by
construction (an idempotent, order-independent Lua apply, step 13), the same
reason `FundsHeld` and `HoldClosed` landing on different partitions was never
going to corrupt a balance even though it can still reorder a log a human or
an audit query reads back. Reverted (`git checkout
libs/ledgerflow-starter-messaging`), rebuilt, restarted, 5 more rounds:

```
wallet-hold.events.v1 (FundsHeld, keyed):   5/5 on partition 2
hold-closed.events.v1 (HoldClosed, keyed):  5/5 on partition 2
```

Every pair on the same partition on both topics - more than the design
promises (`FundsHeld` and `HoldClosed` are still two separate logs Kafka never
orders against each other), true here because both topics have three
partitions and the same key hashes the same way on each.

## Skew

`scripts/partition-skew.sh <topic>` against all ten `ledgerflow.*.v1` topics
after a 100/s, 60s payments run:

| topic | p0 | p1 | p2 | max/min |
|---|---:|---:|---:|---:|
| account.entry.events.v1 | 2277 | 2191 | 2240 | 1.04 |
| issuer.authorization.commands.v1 | 2140 | 2288 | 2274 | 1.07 |
| issuer.authorization.events.v1 | 2140 | 2285 | 2274 | 1.07 |
| ledger.hold-closed.events.v1 | 1682 | 1033 | 3984 | 3.86 |
| ledger.hold-rejected.events.v1 | 0 | 0 | 1 | n/a |
| ledger.hold.commands.v1 | 4280 | 4571 | 4548 | 1.07 |
| ledger.wallet-hold.events.v1 | 1660 | 1055 | 3990 | 3.78 |
| payment.requested.events.v1 | 2140 | 2286 | 2274 | 1.07 |
| settlement.capture.commands.v1 | 2127 | 2279 | 2266 | 1.07 |
| settlement.capture.events.v1 | 2127 | 2276 | 2266 | 1.07 |

Every payment-keyed topic sits within a few percent of even: a high-cardinality
key (`paymentId`, tens of thousands of distinct values) spreads near-uniformly
no matter how it hashes. The two wallet-keyed topics are 3.8-3.9x skewed for
the opposite reason: this demo account has 20 wallets, so the key space is 20
values wide, not thousands, and 20 hashes into 3 buckets unevenly by
construction; no number of added consumers changes that split, it's fixed the
moment the key space is fixed. `ledger.hold.commands.v1` carries the same
holds and stays balanced, because `ReserveWallets`/`ReleaseWallets` are keyed
by `paymentId`, not the wallet. A production account with a single wallet
would be the extreme case already named above: one key, one partition, all of
that account's hold traffic serialised on one broker partition forever -
correct ordering, zero parallelism, the same trade-off any single-key stream
makes.

## What stays unordered

`account.EntryPosted` stays keyed by entry id. An entry has two wallets and
one key; a per-wallet ordering guarantee would mean one event per posting
line instead of one per entry, which is a schema change, not a key change -
left for a later step.

## Kept

- `outbox.partition_key`: nullable column on every service's outbox table,
  `COALESCE(partition_key, aggregate_id::text)` at publish, so every other
  producer in the system keeps keying by aggregate with no code change.
- The key rule: `ledger.FundsHeld` / `HoldClosed` / `HoldRejected` key by
  `accountId:label`, one wallet per hold in practice; payment's commands and
  replies stay keyed by `paymentId`, the saga's own ordering unit.
- `scripts/partition-skew.sh`: the check for "is this key spreading load or
  concentrating it," wrapping `rpk topic describe -p` - reusable the next time
  a topic gets a non-random key.
