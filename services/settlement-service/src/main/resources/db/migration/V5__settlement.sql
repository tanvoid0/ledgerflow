-- settlement_line and settlement_batch key on what a rerun would recompute (item_id; business_date+merchant_id),
-- so step 24's rerun of a half-finished business day just overwrites the same rows instead of doubling them.
CREATE TABLE settlement_item (
    id            BIGSERIAL   PRIMARY KEY,
    payment_id    UUID        NOT NULL,
    merchant_id   TEXT        NOT NULL,
    amount_minor  BIGINT      NOT NULL,
    currency      CHAR(3)     NOT NULL,
    business_date DATE        NOT NULL,
    status        TEXT        NOT NULL DEFAULT 'NEW'
);
CREATE INDEX settlement_item_pending ON settlement_item (business_date, status);

CREATE TABLE settlement_line (
    item_id       BIGINT      PRIMARY KEY,
    business_date DATE        NOT NULL,
    merchant_id   TEXT        NOT NULL,
    currency      CHAR(3)     NOT NULL,
    gross_minor   BIGINT      NOT NULL,
    fee_minor     BIGINT      NOT NULL,
    net_minor     BIGINT      NOT NULL
);

CREATE TABLE settlement_batch (
    business_date DATE        NOT NULL,
    merchant_id   TEXT        NOT NULL,
    currency      CHAR(3)     NOT NULL,
    gross_minor   BIGINT      NOT NULL,
    fee_minor     BIGINT      NOT NULL,
    net_minor     BIGINT      NOT NULL,
    line_count    INT         NOT NULL,
    PRIMARY KEY (business_date, merchant_id)
);
