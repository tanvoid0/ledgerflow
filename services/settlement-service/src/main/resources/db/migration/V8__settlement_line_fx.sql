-- Non-GBP lines get their own table, same shape as settlement_line; netting reads both and reports per currency,
-- so settlement_batch's key grows a currency column too.
CREATE TABLE settlement_line_fx (
    item_id       BIGINT      PRIMARY KEY,
    business_date DATE        NOT NULL,
    merchant_id   TEXT        NOT NULL,
    currency      CHAR(3)     NOT NULL,
    gross_minor   BIGINT      NOT NULL,
    fee_minor     BIGINT      NOT NULL,
    net_minor     BIGINT      NOT NULL
);

ALTER TABLE settlement_batch DROP CONSTRAINT settlement_batch_pkey;
ALTER TABLE settlement_batch ADD PRIMARY KEY (business_date, merchant_id, currency);
