-- One authorization per payment. The UNIQUE is what makes a repeated AuthorizePayment harmless.
CREATE TABLE authorizations (
    id           UUID         PRIMARY KEY,
    payment_id   UUID         NOT NULL UNIQUE,
    amount_minor BIGINT       NOT NULL,
    currency     CHAR(3)      NOT NULL,
    status       VARCHAR(16)  NOT NULL,      -- AUTHORIZED | REFUNDED
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
