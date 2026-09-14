-- Events wait here, written in the same transaction as the change that caused them.
-- A poller moves them to the broker. Either both the hold and its event exist, or neither.
CREATE TABLE outbox (
    id           UUID         PRIMARY KEY,   -- the event id
    aggregate_id UUID         NOT NULL,      -- the Kafka key
    topic        VARCHAR(128) NOT NULL,
    event_type   VARCHAR(128) NOT NULL,
    payload      JSONB        NOT NULL,      -- the whole envelope; the poller sends it as is
    occurred_at  TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ
);

-- only the unpublished rows are indexed, so the poll stays fast however big the table grows
CREATE INDEX idx_outbox_pending ON outbox (occurred_at) WHERE published_at IS NULL;
