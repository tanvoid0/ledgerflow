# Step 19 — the group, and the cost of changing it

## One instance owns everything

notification-service subscribes to five topics under one group id: the main
`ledgerflow.ledger.wallet-hold.events.v1` plus its three retry topics
(`.retry-1000`, `.retry-3000`, `.retry-9000`) and the `.dlt`, three partitions
each - 15 partitions total - `concurrency: 3` on every one of the five
listener containers. One instance already runs 15 consumer threads, one per
partition, so `rpk group describe notification-service` lists 45 members
once all three instances (8082, 8092, 8093) are up. Two more instances of the
same jar do not spread that load: a partition belongs to one member at a
time, and whichever instance joins first claims all 15. Confirmed with no
failure involved at all (`g-cooperative.md`): 8082 started first, took every
partition, and 8092/8093's 30 threads between them held zero for the entire
test. What three instances prove here is not throughput - that would need
more partitions than one instance's concurrency covers - it's what happens to
*ownership* when a member joins or dies, which is the point of this step.

## Eager

Default assignor (`partition.assignment.strategy` unset - `BALANCER range` in
`rpk group describe`), three instances up, then 8093 killed
(`Stop-Process -Force`) and restarted, `rpk group describe` polled every
~1s throughout.

| event | `rpk group describe` | partitions moved | unowned for |
|---|---|---|---|
| 3 instances up (8082/8092/8093) | `STATE Stable`, `BALANCER range`, `MEMBERS 45`, `TOTAL-LAG 0` | - | - |
| kill 8093 | `PreparingRebalance`, all three main-topic rows blank | all 3 of 3 revoked (only p2, the one 8093 held, actually changes owner - p0/p1 land back on the same members) | ~45.2s (43.2s detection, bound by the 45s default `session.timeout.ms`, + 2.0s rebalance) |
| restart 8093 | `PreparingRebalance` again, all three blank | all 3 of 3 revoked again (p2 lands on the rejoined instance) | ~4.5s (3.5s join, seen immediately, + 1.0s rebalance) |

Every membership change revokes all three main-topic partitions even though
only one of them changes hands - eager has no notion of "only this one
moved". A join is cheap because the coordinator hears about it at once; a
crash costs the full session timeout because nothing tells it otherwise.

## Cooperative

`partition.assignment.strategy:
org.apache.kafka.clients.consumer.CooperativeStickyAssignor` added to all
seven consumer ymls (every group, not only notification's), rebuilt,
restarted, same kill/restart of 8093 against the same poll.

| event | partitions that moved | survivors' lag | unowned for |
|---|---|---|---|
| kill 8093 (40 holds sent across the outage) | 0 of 3 - 8093 owned none of the 15 to lose | p2's offset advanced by exactly 40, the traffic sent while it was down; lag stayed 0 throughout | 0s - main-topic rows never blanked; `MEMBERS` dropped 45->30 between ticks with no other visible effect |
| restart 8093 (30 holds sent across the join) | 0 of 3, same reason | p2's offset advanced by exactly 30 through the dip; lag stayed 0 | ~2s of `rpk` showing blank rows - display lag behind the protocol round, not a real fetch pause |

8082 started first and claimed all 15 partitions before 8092/8093 existed to
compete for any of them. CooperativeStickyAssignor only moves a partition
when one consumer holds more than one extra relative to another
(max-min > 1); with 8082's 15 threads holding one partition each and every
other thread on 8092/8093 holding zero, that's max=1, min=0 - already
balanced by the assignor's own rule, so nothing ever moved to the extras, and
there was nothing on them to lose when 8093 died. The "one instance owns
everything" story above, proven again under the assignor meant to fix it.

## Static membership

`group.instance.id: <service>-${server.port}` and `session.timeout.ms: 30000`
added alongside the cooperative-sticky assignor, all seven ymls. The plain
yml version fences itself: a service's five listener containers (main, three
retry tiers, dlt) share one consumer factory, and spring-kafka only
disambiguates `-0/-1/-2` for concurrency *within* a container, not across
them - so `notification-service-8082-0` was claimed by three unrelated
containers at once, and the other four on every one of the seven services got
`FencedInstanceIdException` (ledger-service refused to boot at all). Fixed
with one `ContainerCustomizer` bean (`staticMembershipPerContainer`,
`libs/ledgerflow-starter-messaging/.../MessagingAutoConfiguration.java`) that
appends each container's own listener id, sanitised
(`replaceAll("[^A-Za-z0-9._-]", "_")` - the id contains `#`, which
`group.instance.id` rejects outright), to the configured id before the
container starts.

| event | rebalance? | member id | partitions unowned |
|---|---|---|---|
| restart 8093 within 30s (actual gap ~0.4s) | none - `STATE` never left `Stable`, `MEMBERS` never left 45 for the full 90s poll | `group.instance.id` unchanged on all 15 of 8093's members; the broker's own `memberId` UUID rotated on rejoin, which is normal and not what static membership holds constant | 0 - no blank tick at all, against cooperative's own 2-tick blip on the same restart |
| kill 8093, wait past 30s | 1, detected 28.4s after the kill | p0/p1/p2 all stayed on `notification-service-8082-...` throughout | 0 of the main topic's 3 - 8093 owned none, same structural reason as cooperative; ~31.5s total (28.4s detection + 3.1s rebalance), bound by the configured 30s |

Static membership skips the group-membership machinery entirely for a
same-identity rejoin inside the window - there's no rebalance to hide the
cost of because none is triggered. The number that matters on the kill isn't
which partition moved (still zero, same structural reason as cooperative) -
it's the dead-time bound: 31.5s against eager's 45.2s, tracking the
configured session timeout down from the default almost exactly.

## What a kill still costs

A force-killed instance never sends `LeaveGroup`, so the broker only frees
its partitions when `session.timeout.ms` expires - 45s by default, measured
at 45.2s (43.2s detection + 2.0s rebalance) through both eager and
cooperative above, since static membership is what brings the bound down to
30s, measured at 31.5s (28.4s detection + 3.1s rebalance). A clean shutdown
leaves the group in under two seconds under eager and cooperative, same as
step 9's number - but static membership changes that trade: a statically
identified consumer does not send `LeaveGroup` on close either (the
coordinator expects it back), so the broker holds its slot open for the full
`session.timeout.ms` regardless of whether the stop was clean or a kill.
Verified live against balance-service: a clean `/actuator/shutdown` left
`STATE Stable` with all 15 members still listed for ~29.9s before flipping to
`Empty`. `scripts/rebuild-balance.sh` waits on exactly that `STATE Empty`
line before it will rewind the group, so every rebuild now costs ~30s more at
the front than step 9 measured for a graceful stop - static membership is
cheap for a restart and expensive for a decommission, and
`rebuild-balance.sh` pays the expensive side.

## Checkpoint

`perf/bench.sh step19` on the committed jars: transfer p99 14 ms, holds p99
15 ms, payment POST p99 47 ms, settled p50 428 ms / p99 21.6 s, 18,010
captured, none failed. Step 18 was 528 ms / 17 s / none failed: the group
settings cost nothing in steady state, which is what they should cost - no
rebalance happens during a run, and `rpk group describe payment-service`
shows the sticky assignor handing out exactly what range did (thread *n*
owns partition *n* of every reply topic).

That row took three runs. The first two, on the same jars, settled at p50
8.7 s / p99 41 s with 2,651 of 18,008 payments past the 15 s deadline. The
machine was not idle: two `find /` scans, a Gradle daemon and a game client
were running alongside, and the per-record time of every listener that
writes to Postgres rose ~30 % uniformly (Prometheus
`spring_kafka_listener_milliseconds`, 3-minute mean mid-run) while the
Redis-only balance listener did not move:

| listener | step 18 run | loaded step 19 runs |
|---|---|---|
| payment-service replies | 7.0 ms | 9.3 ms |
| ledger-service commands | 8.4 ms | 11.0 ms |
| settlement-service captures | 21.2 ms | 26.2 ms |
| balance-service (Redis, no Postgres) | 1.0 ms | 1.0 ms |

One commit per record, one fsync per commit, on a Docker Desktop volume
somebody else was also using - that 30 % is the disk. It tipped a thread that
was already close: payment-service's reply thread 2 owns partition 2 of every
reply topic, and partition 2 of the wallet-hold topic carries 62 % of all
FundsHeld (step 18's wallet keying over the few wallets the k6 scenario
uses). At 107 records/s x 7.0 ms that thread is 74 % busy; at 9.3 ms it is
91 %, the poll that drains the wallet-hold backlog holds the authorisation
and capture replies on the same thread for ~10 s each, and the deadline does
the rest. The quiet run's 21 s p99 is the same thread at 74 %: three threads
for twelve reply partitions, one of them hot, is the saga's ceiling at 100/s.
The group cannot fix it; more partitions on the hot topic or a cheaper record
can.

## Kept

- `partition.assignment.strategy: CooperativeStickyAssignor` in all seven
  consumer ymls: only the partitions that move get revoked, everything else
  keeps flowing through a rebalance.
- `group.instance.id: <service>-${server.port}` + `session.timeout.ms: 30000`
  under `spring.kafka.consumer.properties` in all seven ymls, plus one
  `ContainerCustomizer` bean (`staticMembershipPerContainer`) that suffixes
  the configured id with each container's own sanitised listener id - static
  membership only works once every listener container on a service has its
  own distinct id, not one shared across all five.
- `scripts/start-services.sh`: optional `EXTRA_NOTIFICATION_PORTS="8092
  8093"` to bring up the two extra instances used for these measurements,
  off by default.
