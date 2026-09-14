-- One row per payment: the current state as JSON (the sealed record, tagged by name), and next to it
-- the two things the sweeper filters on. A step that has not answered by deadline_at is timed out.
CREATE TABLE sagas (
    payment_id   UUID         PRIMARY KEY,
    state        VARCHAR(32)  NOT NULL,
    payload      JSONB        NOT NULL,
    current_step VARCHAR(32),
    deadline_at  TIMESTAMPTZ,
    correlation_id VARCHAR(64),             -- the request that started it; every command carries it on
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_sagas_deadline ON sagas (deadline_at)
    WHERE deadline_at IS NOT NULL AND state NOT IN ('Captured', 'Failed');
