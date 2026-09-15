# Step 20 — topics that forget, topics that remember

## Every topic, how long, and why

Retention is a business decision wearing a config key: how far back can a
consumer rewind, and who still needs to ask. The full topic-by-topic table now
lives in `docs/events/README.md` under `## How long a topic remembers`; the
policy behind it, stated once here:

- `*.events.v1`: 30 days (`retention.ms=2592000000`). The outbox table is the
  permanent archive; the topic is only the replay window a consumer or a
  rebuild might rewind into, and 30 days covers a late investigation without
  turning Redpanda into a second database.
- `*.commands.v1`: 1 day. A command older than the saga's 15s deadline has
  already resolved to Failed; a day's retention is for the post-mortem, not
  for correctness.
- `.retry-*`: 1 day, same reasoning as the commands/events they retry.
- `.dlt`: 30 days - someone has to look at what landed there.
- `ledgerflow.balance.snapshots.v1`: `cleanup.policy=compact`, 3 partitions,
  `segment.ms=10000` in dev only, so the log cleaner runs often enough to
  watch instead of waiting on a production-sized segment to close.

`scripts/demo.sh` applies every one of these with `rpk topic create -c` for a
topic that does not exist yet and `rpk topic alter-config` for one an older
volume already made, so a fresh clone and a compose volume from step 8 end up
on the same policy without anyone deleting a topic by hand.

`Balances.REMEMBER` (step 13's dedup TTL for processed events) tracks the
same 30d as the events topics it dedups against.

## The cleaner, caught in the act

Compaction does not shrink a topic on write. The log cleaner walks a topic's
closed segments in the background and rewrites each one keeping only the
newest record per key - a table shaped like a log, if you let it finish.
Between an `rpk topic consume ledgerflow.balance.snapshots.v1` run taken
before the cleaner has had a pass and one taken after, the same key stops
repeating:

```
$ rpk topic consume ledgerflow.balance.snapshots.v1 -o :end -f 'key=%k  %v\n'   # 12 holds on wallet A-1, before the cleaner's pass
key=…:A-1  {..."heldMinor":886,"version":1,...}
key=…:A-1  {..."heldMinor":986,"version":2,...}
   ...                                              (12 records, same key, versions 1-12)
key=…:A-1  {..."heldMinor":1986,"version":12,...}

$ rpk topic consume ledgerflow.balance.snapshots.v1 -o :end -f 'key=%k  %v\n'   # after (2 more holds to close the segment, then the cleaner's pass)
key=…:A-1  {..."heldMinor":2186,"version":14,...}
```

(On this dev cluster the segment never closed until `log_segment_ms_min`, a cluster-wide floor
that defaults to 10 minutes, was lowered - a topic's own `segment.ms` below that floor is silently
ignored.)

`segment.ms=10000` is what makes that visible on a laptop inside minutes
instead of waiting on the 7-day default segment roll. Production would leave
that at the default and see compaction lag behind writes for far longer,
which costs nothing downstream: nothing here depends on the cleaner having
*already* run, only on it running eventually.

## Rebuilt from a snapshot

Every apply - whether the event changed a wallet or not - publishes a
`BalanceSnapshot(accountId, label, currency, balanceMinor, heldMinor,
version, source{topic, partition, offset})` to
`ledgerflow.balance.snapshots.v1`, keyed `accountId:label` (step 18's wallet
key). No outbox: a duplicate published twice onto a compacted topic is the
same state recorded twice, not a double-spend, so the plain `KafkaTemplate`
send balance-service already had is enough - nothing here needs the
transactional guarantee the events crossing service boundaries need.
`version` is a per-wallet counter incremented inside `apply.lua` itself, not
read off the record, because three consumer threads can apply to one wallet
off two different source topics with no ordering promise between them; a
reader keeps the highest version per key, not the last record it saw,
because "last" is not a fact Kafka gives it.

`scripts/rebuild-balance.sh` now: stop the service, `FLUSHDB` Redis, consume
the snapshot topic start to end keeping the highest version per key, seed
Redis from that, then `rpk group seek balance-service --to-file` with - per
source topic-partition - the highest source offset among the kept snapshots
plus one; a partition no kept snapshot names keeps whatever offset it had
committed. `--full` keeps the old replay-from-start path as a fallback for
when the snapshot topic itself is what needs rebuilding.

Known ceiling: an event applied but not yet committed when the JVM died,
whose snapshot the cleaner has since superseded, gets applied twice by a
rebuild run before the broker's redelivery arrives - the rebuild resumes past
the offset its own snapshot already proves was applied, so the redelivery
that would have caught the crash never comes. The script's own stop is not
exposed to this: `POST /actuator/shutdown` commits everything in flight
before the process exits, so a rebuild that follows a clean stop never has a
gap to fill twice.

One more rule, the bootstrap: the snapshot topic only knows about applies
made since the jar that publishes them, so the first rebuild after that
deploy has to be `--full` - the replay itself publishes a snapshot for every
wallet, and from then on the topic covers the whole history. The numbers
below were taken in that order; the snapshot rebuild's Redis matched the
full replay's byte for byte (`account:*` hashes dumped with sorted fields
and diffed) and `GET /api/v1/balances/1111…` agreed with the ledger's open
holds on every wallet of the busiest account.

| | step 13 (no snapshot topic yet) | step 20 |
|---|---:|---:|
| records on the three source topics | 3,017 | 316,900 |
| snapshots kept | n/a | 21 |
| full replay time (`--full`) | 12s | 280s |
| snapshot rebuild time | n/a | 40s |

## Compaction is not deduplication

The cleaner runs when it runs - a segment has to close, `segment.ms` has to
elapse - and until then a consumer reading the topic from the start sees
every record ever written for a key, repeats included, the same as any other
log. Nothing about `cleanup.policy=compact` promises a reader "one record per
key"; it only promises the *cleaner* will eventually get there. Step 11's
rule still holds because of that, not despite it: a snapshot consumer stays
idempotent on `(accountId, label, version)` the same way notification-service
stays idempotent on `eventId`, and a rebuild that reads the same version
twice - because the cleaner hadn't caught up, or because a rebuild is rerun -
changes nothing the second time.

No tombstone yet. A key with a null value is Kafka's own "forget this key
entirely," and we don't emit one: a closed wallet's last balance stays in the
compacted topic forever, which is correct for now because nothing in this
system closes a wallet. Tombstoning a closed wallet is step-later territory.

## Kept

- The retention policy in `scripts/demo.sh`: 30 days on `*.events.v1` and
  `.dlt`, 1 day on `*.commands.v1` and `.retry-*`, applied with `-c` on
  create and `alter-config` on an existing topic so a fresh clone and an old
  volume agree.
- `BalanceSnapshot`, published straight through `KafkaTemplate` after every
  apply (no outbox - a duplicate on a compacted topic is harmless), keyed by
  wallet, versioned inside `apply.lua` so the highest version wins regardless
  of arrival order.
- `scripts/rebuild-balance.sh`'s two paths: the snapshot-seeded rebuild by
  default, `--full` for a full replay when the snapshot topic itself needs
  rebuilding.
