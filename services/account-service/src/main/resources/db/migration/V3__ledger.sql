CREATE TABLE journal_entries (
    id              UUID         PRIMARY KEY,
    idempotency_key VARCHAR(64)  NOT NULL UNIQUE,   -- the retry guard, enforced here, not in Java
    description     VARCHAR(200) NOT NULL,
    posted_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE postings (
    id           BIGSERIAL PRIMARY KEY,
    entry_id     UUID    NOT NULL REFERENCES journal_entries(id),
    wallet_id    UUID    NOT NULL REFERENCES wallets(id),
    amount_minor BIGINT  NOT NULL,                  -- signed: + credits the wallet, - debits it
    currency     CHAR(3) NOT NULL
);

CREATE INDEX idx_postings_wallet ON postings (wallet_id);
