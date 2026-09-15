# Step 21 — kill a broker

## Three brokers, from an overlay

`infra/compose/docker-compose.redpanda3.yml` sits on top of the daily
single-node file: `ledgerflow-redpanda` stays node 1 (bootstrap
`localhost:9092` for every service and the schema registry),
`ledgerflow-redpanda-2` and `-3` join it on host ports 9192 and 9292.
Redpanda keeps no volume in this repo, so the cluster does not survive
`docker compose down` either way - `scripts/cluster3.sh up` brings all
three nodes up with `default_topic_replications 3`, re-creates every topic
through `scripts/topics.sh` (now `RF=3 MIN_ISR=2`), and re-registers every
schema; `down` drops back to the single node the rest of the steps run
against.

Node 1 is never the one killed below. It is where bootstrap and the schema
registry live for every service's config, and killing the node every
client resolves first is a different experiment from killing a follower -
named here as a limit, not fixed by this step.

## Raft, not ISR

The curriculum's own framing - `min.insync.replicas` as the written-down
margin, `NotEnoughReplicasException` as the system keeping its promise - is
Kafka's ISR model. Redpanda replicates with Raft instead, and the two
config keys that model expects do not do what they say here:

- `min.insync.replicas` can be set with `rpk topic alter-config` and never
  errors, but it never appears back in `rpk topic describe -c` either - the
  broker accepts the key and ignores it. Nothing enforces it, because
  nothing needs to.
- `unclean.leader.election.enable` is not a known property on this broker
  at all. There is no flag for "elect a leader with stale data" because
  Raft's leader election already requires a majority of the replica set to
  agree on the log position before anyone can become leader - the
  "availability traded for truth" decision the curriculum names is made by
  the storage engine, not by a config key. For money it comes out the same
  way every time: truth.

`acks=all` still means what step 09 measured it to mean - the producer
waits for the broker's ack - but the ack itself now stands for "a majority
of the replica set has this record," not "every in-sync replica does." A
replication factor of 3 survives exactly one dead broker with nothing
lost; losing two is the margin the curriculum asks to remove, covered
below as the two-dead-broker case rather than a `min.insync.replicas`
change, because there is no such change to make.

## A broker dies mid-stream

`scripts/cluster3.sh up`, 50 holds posted to `POST /api/v1/holds` 200ms
apart (cycling the account's twenty wallets), `docker stop ledgerflow-redpanda-2` at the 3s
mark. In = HTTP 201s; out = notification-service's processed rows for the
same 50 events, cross-checked against `rpk group describe
notification-service` lag draining back to 0.

| | value |
|---|---:|
| holds in (HTTP 201s) | 50 |
| notifications out | 50 |
| partition leaders before the stop | 2, 1, 2 |
| partition leaders after the stop | 2, 0, 0 |
| seconds until the first publish after the stop | ~1.0s (one gap in the outbox's ~300ms publish cadence, starting ~1.5s after the stop - the time the affected partition's writer needed to find the new leader) |
| peak unpublished outbox rows | 0 (never caught above 0 at 0.5s poll resolution - the redirect to a new leader lands faster than one publish cycle) |
| seconds until group lag back to 0 | ~10s |

Every leader `ledgerflow-redpanda-2` held moves to a surviving node; the
outbox (step 10) holds whatever it could not publish in the gap and drains
it once a new leader is elected - the same "the rows wait" behaviour step
10 measured against the whole broker being down, now against a third of
the cluster.

## Two dead brokers

Ten holds, `ledgerflow-redpanda-2` and `-3` both stopped, leaving only
node 0 - a minority of a 3-node Raft group, so no partition can elect a
leader. The producer does not silently drop anything; it refuses:

```
org.apache.kafka.common.errors.TimeoutException: Expiring 1 record(s) for ledgerflow.ledger.wallet-hold.events.v1-2:120000 ms has passed since batch creation
```

Ten holds still answer 201 - the write commits to Postgres and an outbox row before Kafka ever
enters the picture - but every one sits unpublished until `delivery.timeout.ms` (120s, the same
default step 10 measured) expires on the batch. `notification-service`'s `processed_events` count
does not move.

The outbox holds every event this produces `published_at IS NULL` for, the
same column step 10 read, for as long as the cluster stays leaderless.
Restarting `-2` and `-3` restores quorum without restarting anything else;
the drain that follows:

```
cluster health Healthy: true, 11s after both nodes started
outbox unpublished rows: 10 -> 0, within ~8s of Healthy (first poll after already read 0)
notification processed_events: +10, all ten notifications eventually sent
ledgerflow.ledger.wallet-hold.events.v1 -p: every partition back to REPLICAS [0 1 2]
```

## Kept

- `infra/compose/docker-compose.redpanda3.yml`, `scripts/topics.sh` (now
  parameterised on `RF` / `MIN_ISR`), `scripts/cluster3.sh up|down`.
- The daily default stays one broker: no replication factor to lose, no
  cluster to bring up before a service can start, and every earlier step's
  numbers stay comparable to a single node. `perf/bench.sh step21` runs
  after `cluster3.sh down`, on that single node, for the same reason -
  three brokers cost the row nothing it should be paying for.
- Three brokers cost three times the storage per topic and a slower cold
  start; that trade is why it lives in an overlay instead of the default.
