# The dual write, before and after

Same experiment twice: warm producer, `docker stop ledgerflow-redpanda`,
one `POST /api/v1/holds`, broker down for 130s (longer than the Kafka
client's `delivery.timeout.ms` of 120s, on purpose - see below),
`docker start ledgerflow-redpanda`, then wait for the notification line.

| | step 09 code (publish after commit) | step 10 code (outbox) |
|---|---|---|
| API response | **500 after 60.0s** (`TimeoutException: Topic ... not present in metadata after 60000 ms`) | 201 in 0.15s |
| row in `funds_holds` | yes | yes |
| event while broker down | in the producer's buffer, then dropped | `outbox` row, `published_at IS NULL`, still there at 130s |
| after `docker start` | nothing, ever | pending 0 within 3s; "would email the customer" logged |

The step 09 row is worse than "lost event": the caller was told the request
failed, the database says it succeeded, and the one service that should have
told the customer never heard. Three systems, three different answers.

## Why 130 seconds

A `docker stop` / `sleep 10` / `docker start` does not reproduce the bug on a
warm producer. `send()` hands the record to a buffer the client keeps trying
to deliver for `delivery.timeout.ms` (120s); if the broker is back inside that
window the record arrives late and the "bug" looks fixed. The buffer dies
with the process, or with the timeout. Either loses the event; the timeout
is the one you can wait for.

The 60s in the "before" row is a different knob: with the broker gone the
topic falls out of the producer's metadata and the next `send()` blocks
`max.block.ms` waiting for it. The row was committed before that wait began.

## What the outbox did during the outage

```
20:58:06  broker stopped; hold placed, 201; outbox row pending
20:59:07  drain(): Send failed (60s metadata wait) -> transaction rolled back, row still pending
21:00:07  drain(): Send failed -> rolled back again
21:00:17  broker started
21:00:19  drain(): published 1 event(s) from the outbox
21:00:20  notification-service: would email the customer ... (request outbox-broker-down)
```

The poller holds its transaction (and the row lock) while it waits for the
ack, fails, rolls back, and tries again next tick. Nobody restarted anything.

## The mirror image

Publish first and roll back afterwards, and notification is told about a
hold that does not exist. There is no ordering of two writes that closes both
windows; the outbox closes both by making it one write. `PlaceHoldIT` has the
mirror as a test: a hold whose insert fails at commit takes its outbox row
down with it, and the (mocked) producer is never called.

## Settings that make it true

| where | what | why |
|---|---|---|
| `V2__outbox.sql` | `payload JSONB` holds the whole envelope | the poller sends the column as is; the serialisation happened inside the transaction, so a bug there rolls back with the hold |
| `V2__outbox.sql` | partial index `WHERE published_at IS NULL` | the table only grows; the poll only ever reads the pending handful |
| `PlaceHold` | `TransactionTemplate` around save + append, account call outside it | step 06's lesson stands: no connection pinned while waiting on the network |
| `OutboxPublisher` | `FOR UPDATE SKIP LOCKED`, `LIMIT 100`, every 500ms | two instances drain different rows instead of blocking each other |
| `OutboxPublisher` | join every send future before the `UPDATE` | a buffered send is not a delivered one; marking early is the bug in a new coat |
| producer | `StringSerializer` | JSON in, JSON out; the old `JacksonJsonSerializer` would encode the string a second time |
