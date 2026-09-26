#!/usr/bin/env bash
# Resets wallet A-20 to exactly 100.00 via a balancing entry, then fires 50 concurrent 80.00 transfers at it.
set -euo pipefail
cd "$(dirname "$0")/.."
PSQL="${PSQL:-docker exec ledgerflow-postgres psql} -U ledgerflow -d account -tAc"
# tokens live 300s: mint one per run, not once and cache it. k6 sets Authorization: Bearer $TOKEN itself.
TOKEN=${TOKEN:-$(scripts/token.sh ops)}
FROM=$($PSQL "SELECT id FROM wallets WHERE label='A-20'")
TO=$($PSQL "SELECT id FROM wallets WHERE label='A-19'")
TREASURY=$($PSQL "SELECT id FROM wallets WHERE label='TREASURY'")
BAL=$($PSQL "SELECT COALESCE(SUM(amount_minor),0) FROM postings WHERE wallet_id='$FROM'")

# one balancing entry brings A-20 back to 10000 (treasury absorbs the difference)
$PSQL "WITH e AS (INSERT INTO journal_entries (id, idempotency_key, description)
                  VALUES (gen_random_uuid(), 'reset-' || clock_timestamp(), 'race reset') RETURNING id)
       INSERT INTO postings (entry_id, wallet_id, amount_minor, currency)
       SELECT id, '$FROM'::uuid, 10000 - $BAL, 'GBP' FROM e UNION ALL
       SELECT id, '$TREASURY'::uuid, $BAL - 10000, 'GBP' FROM e" > /dev/null
# the balance is a column since V5: keep it in step with the postings we just wrote
$PSQL "UPDATE wallets w SET balance_minor = (SELECT COALESCE(SUM(amount_minor),0) FROM postings p WHERE p.wallet_id = w.id AND p.currency = 'GBP')
       WHERE w.id IN ('$FROM', '$TREASURY')" > /dev/null

k6 run -q -e FROM="$FROM" -e TO="$TO" ${TOKEN:+-e TOKEN=$TOKEN} perf/k6/race.js

echo "A-20 balance after the race (should never be below 0):"
$PSQL "SELECT COALESCE(SUM(amount_minor),0) FROM postings WHERE wallet_id='$FROM'"
