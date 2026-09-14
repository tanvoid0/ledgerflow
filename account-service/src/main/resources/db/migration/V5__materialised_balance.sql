ALTER TABLE wallets ADD COLUMN balance_minor BIGINT NOT NULL DEFAULT 0;

-- backfill from the book of record; the postings stay the truth
UPDATE wallets w SET balance_minor = COALESCE(
    (SELECT SUM(amount_minor) FROM postings p WHERE p.wallet_id = w.id AND p.currency = 'GBP'), 0);

-- The last line of defence: no Java code, however wrong, can overdraw a customer wallet.
-- TREASURY is the funding source and runs negative by construction (V4), so it is exempt.
ALTER TABLE wallets ADD CONSTRAINT wallets_balance_non_negative
    CHECK (balance_minor >= 0 OR label = 'TREASURY');
