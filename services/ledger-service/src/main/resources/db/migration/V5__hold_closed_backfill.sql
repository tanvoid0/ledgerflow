-- Release and capture were silent until now. A projection built from the topic would still count
-- every hold closed before this migration as open, so tell the log about them once, here.
WITH ev AS (
    SELECT gen_random_uuid() AS event_id, h.* FROM funds_holds h WHERE h.status IN ('RELEASED', 'CAPTURED')
)
INSERT INTO outbox (id, aggregate_id, topic, event_type, payload, occurred_at)
SELECT event_id, id, 'ledgerflow.ledger.hold-closed.events.v1', 'ledger.HoldClosed',
       jsonb_build_object(
           'eventId', event_id, 'eventType', 'ledger.HoldClosed', 'schemaVersion', 1,
           'aggregateId', id, 'aggregateVersion', 2,
           'occurredAt', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
           'correlationId', NULL, 'causationId', NULL,
           'payload', jsonb_build_object(
               'holdId', id,
               'wallets', jsonb_build_array(jsonb_build_object('accountId', account_id, 'label', wallet_code)),
               'totalAmount', jsonb_build_object('minorUnits', amount_minor, 'currency', currency),
               'reference', reference,
               'outcome', status)),
       now()
FROM ev;
