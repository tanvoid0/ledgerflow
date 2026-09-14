-- One capture per hold: the journal entry account-service posted for it. The UNIQUE on hold_id and the
-- Idempotency-Key sent to account are the same fact stated twice: a hold is captured at most once.
CREATE TABLE captures (
    id           UUID         PRIMARY KEY,    -- account's journal entry id
    payment_id   UUID         NOT NULL,
    hold_id      UUID         NOT NULL UNIQUE,
    wallet_id    UUID         NOT NULL,
    amount_minor BIGINT       NOT NULL,
    currency     CHAR(3)      NOT NULL,
    status       VARCHAR(16)  NOT NULL,       -- CAPTURED | REVOKED
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_captures_payment ON captures (payment_id);
