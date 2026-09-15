-- Events wait here, written in the same transaction as the entry that caused them.
-- A poller moves them to the broker. Either both the entry and its event exist, or neither.
CREATE TABLE outbox (
    id           UUID         PRIMARY KEY,   -- the event id
    aggregate_id UUID         NOT NULL,      -- the Kafka key
    topic        VARCHAR(128) NOT NULL,
    event_type   VARCHAR(128) NOT NULL,
    payload      JSONB        NOT NULL,      -- the whole envelope; the poller sends it as is
    occurred_at  TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_outbox_pending ON outbox (occurred_at) WHERE published_at IS NULL;

-- The book predates the topic. One EntryPosted for every entry already in it, opening balances
-- included, so a projection built from the topic alone agrees with the book from its first record.
WITH ev AS (
    SELECT gen_random_uuid() AS event_id, e.* FROM journal_entries e
)
INSERT INTO outbox (id, aggregate_id, topic, event_type, payload, occurred_at)
SELECT event_id, id, 'ledgerflow.account.entry.events.v1', 'account.EntryPosted',
       jsonb_build_object(
           'eventId', event_id, 'eventType', 'account.EntryPosted', 'schemaVersion', 1,
           'aggregateId', id, 'aggregateVersion', 1,
           'occurredAt', to_char(posted_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
           'correlationId', NULL, 'causationId', NULL,
           'payload', jsonb_build_object(
               'entryId', id,
               'description', description,
               'lines', (SELECT jsonb_agg(jsonb_build_object(
                                    'wallet', jsonb_build_object('accountId', w.account_id, 'label', w.label),
                                    'amount', jsonb_build_object('minorUnits', p.amount_minor, 'currency', p.currency))
                                ORDER BY p.id)
                           FROM postings p JOIN wallets w ON w.id = p.wallet_id
                          WHERE p.entry_id = ev.id))),
       posted_at
FROM ev;
