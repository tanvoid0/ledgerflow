-- The messaging starter polls an outbox in every service that has a database, this one included. Nothing
-- writes to it yet; without it the poller logged "relation outbox does not exist" twice a second.
CREATE TABLE outbox (
    id            UUID         PRIMARY KEY,
    aggregate_id  UUID         NOT NULL,
    topic         VARCHAR(128) NOT NULL,
    event_type    VARCHAR(128) NOT NULL,
    payload       JSONB        NOT NULL,
    occurred_at   TIMESTAMPTZ  NOT NULL,
    published_at  TIMESTAMPTZ,
    trace_context JSONB        NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX idx_outbox_pending ON outbox (occurred_at) WHERE published_at IS NULL;
