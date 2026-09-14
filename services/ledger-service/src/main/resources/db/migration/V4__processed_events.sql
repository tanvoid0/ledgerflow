-- The inbox. Ledger now takes commands from Kafka, and a command delivered twice must act once.
CREATE TABLE processed_events (
    event_id       UUID        NOT NULL,
    consumer_group VARCHAR(64) NOT NULL,
    processed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer_group)
);
