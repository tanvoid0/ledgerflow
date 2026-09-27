-- Two demo shoppers for the ticketing storefront (alice/bob, Keycloak sub = account id).
-- Same shape as V4/V5: an opening balance is a balanced journal entry out of TREASURY,
-- not a magic number.
INSERT INTO accounts (id, name) VALUES
  ('22222222-2222-2222-2222-222222222222', 'Alice'),
  ('33333333-3333-3333-3333-333333333333', 'Bob');

INSERT INTO wallets (id, account_id, label) VALUES
  ('22222222-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', 'MAIN'),
  ('33333333-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333', 'MAIN');

INSERT INTO journal_entries (id, idempotency_key, description)
VALUES ('00000000-0000-0000-0000-00000000000b', 'seed-demo-shoppers', 'demo shopper opening balances');

INSERT INTO postings (entry_id, wallet_id, amount_minor, currency) VALUES
  ('00000000-0000-0000-0000-00000000000b', '22222222-0000-0000-0000-000000000001', 50000, 'GBP'),
  ('00000000-0000-0000-0000-00000000000b', '33333333-0000-0000-0000-000000000001', 50000, 'GBP'),
  ('00000000-0000-0000-0000-00000000000b', '99999999-9999-9999-9999-999999999999', -100000, 'GBP');

-- Deltas, as the app writes them: this runs while old replicas still post to TREASURY, and a
-- recomputed SUM would overwrite any transfer that commits after this statement's snapshot.
UPDATE wallets SET balance_minor = 50000
 WHERE id IN ('22222222-0000-0000-0000-000000000001', '33333333-0000-0000-0000-000000000001');
UPDATE wallets SET balance_minor = balance_minor - 100000
 WHERE id = '99999999-9999-9999-9999-999999999999';

-- One EntryPosted outbox row for this entry, same backfill pattern as V6.
WITH ev AS (
    SELECT gen_random_uuid() AS event_id, e.* FROM journal_entries e
     WHERE e.id = '00000000-0000-0000-0000-00000000000b'
)
INSERT INTO outbox (id, aggregate_id, topic, event_type, payload, occurred_at)
SELECT event_id, id, 'ledgerflow.account.entry.events.v1', 'account.EntryPosted',
       jsonb_build_object(
           'eventId', event_id, 'eventType', 'account.EntryPosted', 'schemaVersion', 1,
           'aggregateId', id, 'aggregateVersion', 1,
           'occurredAt', to_char(posted_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
           'correlationId', NULL, 'causationId', NULL,
           'payload', jsonb_build_object(
               'entryId', id,
               'description', description,
               'lines', (SELECT jsonb_agg(jsonb_build_object(
                                    'wallet', jsonb_build_object('accountId', w.account_id, 'label', w.label),
                                    'amount', jsonb_build_object('minorUnits', p.amount_minor, 'currency', p.currency))
                                ORDER BY p.id)
                           FROM postings p JOIN wallets w ON w.id = p.wallet_id
                          WHERE p.entry_id = ev.id))),
       posted_at
FROM ev;
