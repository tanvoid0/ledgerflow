# Kill the consumer mid-stream

notification-service force-killed (`Stop-Process -Force`, no shutdown hook),
ten holds placed while it was down, then started again.

| moment | `rpk group describe notification-service` | "would email" lines |
|---|---:|---:|
| consumer dead, 10 holds placed | TOTAL-LAG 10 (4 / 3 / 3 across the partitions) | 0 |
| restarted, first 20s | TOTAL-LAG 10, no partitions assigned | 0 |
| restarted, after ~45s | TOTAL-LAG 0 | 10 |

Ten in, ten out. The 45s gap is not ours: the killed JVM never left the group,
so the broker kept its partitions assigned until `session.timeout.ms` expired.
A graceful stop (SIGTERM) leaves the group on the way out and the restart picks
up in a second or two.

## Settings that make it true

| side | setting | why |
|---|---|---|
| consumer | `enable-auto-commit: false`, `ack-mode: manual_immediate` | the offset moves when `ack.acknowledge()` runs, after the work. A crash before it means a redelivery, never a loss. |
| consumer | `isolation-level: read_committed` | rolled-back writes are never seen (matters from step 10 when the outbox publishes in a transaction) |
| consumer | `concurrency: 3` | one thread per partition; a fourth would sit idle |
| producer | `acks: all`, `enable.idempotence: true` | the broker confirms once every replica has it, and a retried send cannot double-write. Both are client defaults since Kafka 3; spelled out so nobody has to know that. |

Spring Kafka already committed after the listener returned before this step
(it turns auto-commit off and acks per poll batch), so the ten-holds test
would have passed on step 08 too. What actually changed hands is the failure
path: before, a throwing listener was retried nine times in-place and then
skipped - gone.

## Failures now

`@RetryableTopic`: a failed record is re-published to
`…events.v1.retry-1000`, `.retry-3000`, `.retry-9000` (three more attempts,
1s / 3s / 9s later) and finally `…events.v1.dlt`. The main partition never
blocks. `InvalidPayloadException` and JSON that will not parse skip the
retries: bad data does not get better with time.

Proved live with `{not json` produced by hand:

```
gave up on hold poison: Listener failed; Failed to convert from JSON (…ConversionException)
$ scripts/dlt-depth.sh
ledgerflow.ledger.wallet-hold.events.v1.dlt        1
```

`scripts/dlt-replay.sh <dlt>` puts everything back on the source topic with
the same keys. Replaying the pill without fixing anything dead-letters it
again - depth 2 - which is the right answer.
