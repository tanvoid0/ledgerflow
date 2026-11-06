-- Ticketing now publishes (RefundOrder on event cancel): the outbox, as the messaging starter expects it.
CREATE TABLE outbox (
    id            UUID         PRIMARY KEY,
    aggregate_id  UUID         NOT NULL,
    topic         VARCHAR(128) NOT NULL,
    event_type    VARCHAR(128) NOT NULL,
    payload       JSONB        NOT NULL,
    occurred_at   TIMESTAMPTZ  NOT NULL,
    published_at  TIMESTAMPTZ,
    trace_context JSONB        NOT NULL DEFAULT '{}'::jsonb,
    partition_key TEXT
);
CREATE INDEX idx_outbox_pending ON outbox (occurred_at) WHERE published_at IS NULL;
