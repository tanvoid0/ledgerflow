-- The primary key is the dedupe logic. Keyed by group: another group must handle the same event on its own.
CREATE TABLE processed_events (
    event_id       UUID        NOT NULL,
    consumer_group VARCHAR(64) NOT NULL,
    processed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer_group)
);
