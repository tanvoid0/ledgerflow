-- Money enters the system from a treasury wallet, so even the opening
-- balances are balanced entries rather than magic numbers.
INSERT INTO wallets (id, account_id, label)
VALUES ('99999999-9999-9999-9999-999999999999', '11111111-1111-1111-1111-111111111111', 'TREASURY');

INSERT INTO journal_entries (id, idempotency_key, description)
VALUES ('00000000-0000-0000-0000-00000000000a', 'seed-opening-balances', 'opening balances');

-- 100.00 into every wallet except treasury; treasury takes the matching debit
INSERT INTO postings (entry_id, wallet_id, amount_minor, currency)
SELECT '00000000-0000-0000-0000-00000000000a', id, 10000, 'GBP'
FROM wallets WHERE label <> 'TREASURY';

INSERT INTO postings (entry_id, wallet_id, amount_minor, currency)
SELECT '00000000-0000-0000-0000-00000000000a', '99999999-9999-9999-9999-999999999999',
       -(SELECT count(*) FROM wallets WHERE label <> 'TREASURY') * 10000, 'GBP';
