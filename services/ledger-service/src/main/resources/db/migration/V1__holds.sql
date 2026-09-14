-- A hold reserves an amount against a wallet that account-service owns. Several holds
-- may be open on one wallet at once (like pending card authorisations), so there is
-- no uniqueness on (account, wallet) - only an index for the questions we ask.
CREATE TABLE funds_holds (
    id           UUID         PRIMARY KEY,
    account_id   UUID         NOT NULL,
    wallet_code  VARCHAR(16)  NOT NULL,
    amount_minor BIGINT       NOT NULL,
    currency     CHAR(3)      NOT NULL,
    status       VARCHAR(16)  NOT NULL,      -- HELD | RELEASED | CAPTURED
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_funds_holds_wallet ON funds_holds (account_id, wallet_code, status);
