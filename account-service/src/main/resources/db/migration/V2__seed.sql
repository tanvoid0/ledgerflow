INSERT INTO accounts (id, name) VALUES
  ('11111111-1111-1111-1111-111111111111', 'Demo Arena');

INSERT INTO wallets (id, account_id, label)
SELECT gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'A-' || i
FROM generate_series(1, 20) AS i;
