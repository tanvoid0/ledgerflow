# ADR 0001: a polling outbox, not CDC

Status: accepted (step 10)

## Context

ledger-service writes a hold to Postgres and tells the world with a Kafka
event. Two systems, no shared transaction. Whichever write goes first there is
a window where one has happened and the other never will, and
`docs/measurements/step-10-dual-write.md` shows the window is not
theoretical: a broker outage cost us a hold nobody heard about.

The event has to be committed together with the row that caused it. Two ways
to get there:

1. **Polling outbox.** Append the event to an `outbox` table inside the same
   transaction. A scheduled job claims unpublished rows with
   `FOR UPDATE SKIP LOCKED`, sends them, marks them. One table, one method,
   nothing new to run.
2. **Change data capture (Debezium).** Same table, but Debezium tails the
   Postgres write-ahead log and publishes rows as they commit. No polling, a
   few milliseconds of latency instead of a few hundred. Costs a Kafka Connect
   cluster to operate and a replication slot per connector; a slot whose
   consumer stalls keeps the WAL from being recycled until the disk is full.

## Decision

Polling, at 500ms, batches of 100.

For a team of four the difference between 5ms and 500ms of event latency is
invisible to every consumer we have, and the difference between "one
`@Scheduled` method" and "a Connect cluster with replication slots to
monitor" is a pager rotation. CDC is the right answer when the poll itself
becomes a cost: many services each polling, or latency that someone can
measure. Neither is true here.

## Consequences

- Delivery is at-least-once. If the broker acks and the mark fails, the row
  is sent again next tick. Consumers dedupe on `eventId` (step 11).
- More than one ledger-service instance can run: `SKIP LOCKED` hands each
  poller different rows. Per-key ordering across two pollers is not
  guaranteed; today every hold has exactly one event, so it does not matter
  yet. The moment a hold gets a second event, claim by aggregate rather than
  by row.
- The `outbox` table only grows. Published rows are not deleted; the partial
  index keeps the poll indifferent to that. A purge job is a later step.
- The poller's transaction is open while it waits for the broker's ack. With
  the broker down that is one pool connection held for up to
  `delivery.timeout.ms` (2 minutes), then a rollback and a retry. Acceptable
  for one poller; a fleet of them would want a shorter timeout.

## What I would revisit

Step 15 measured the poll, not guessed at it: the 500ms tick was the whole
authorisation path, seven outbox hops at 250ms of mean wait each, 1.75s of a
payment doing nothing. Draining a batch until it comes back short, one offset
commit per poll instead of per record, and a 50ms tick took settled p99 from
34.7s to 1.3s without touching CDC. `synchronous_commit = off` bought another
0.39s and was the one change not kept - a ledger does not trade a fsync for
tail latency, so that row stands as a measurement of the disk, not a fix.
Polling still costs an empty-poll query every tick per service, 7µs and
nothing at today's rate. It stops paying the moment either number moves: more
services than the disk has empty-poll headroom for, or a latency budget under
~50ms that no tick can hit no matter how short. Neither has happened yet;
when it does, this ADR is the one to revisit, not the tick.
