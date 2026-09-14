# Replaying the topic, before and after the dedupe table

Four `ledger.FundsHeld` events on the topic. Stop notification-service, wait
for the group to go empty, `rpk group seek notification-service --to start`,
start it again, count `sent_notifications`.

| | sent_notifications | log |
|---|---|---|
| fresh database, first run | 0 (offsets already committed) | - |
| rewind #1, no dedupe | 4 | 4x "emailed the customer" |
| rewind #2, no dedupe | 8 | 4x "emailed the customer" |
| rewind #3, `processed_events` added | 12 | 4x "emailed the customer" (the table was empty: history before it counts as unseen) |
| rewind #4 | 12 | 4x "already handled, skipping" |

Nothing failed at any point. The consumer acked every record after the work,
the offsets were committed, and a one-line rewind sent every customer a
second email. A rebalance does the same thing without anyone typing a command.

## Splitting the transaction on purpose

`@Transactional(REQUIRES_NEW)` on `ProcessedEvents.markProcessed`, then a
notifier that throws once:

```
ReplayIdempotencyIT.aFailureAfterTheMarkRollsTheMarkBackSoTheRetryDoesTheWork
  expected: 1
   but was: 0 within 10 seconds.
```

The mark committed on its own, the send rolled back, the retry a second later
found the event "done" and skipped it. Same shape as the step 10 dual write,
one layer down. Restored; the test is what keeps it that way.

## Two things the step does not say

- Do not take the group name from the record. Retry-topic containers run under
  `notification-service-retry-1000` and so on, so a key of
  `(event_id, header group)` would treat every retry as a fresh event.
- The ack goes after the transaction, not inside it. `manual_immediate` commits
  the offset synchronously; an ack before the database commit is at-most-once
  for that one record.
