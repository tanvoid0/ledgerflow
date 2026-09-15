#!/usr/bin/env bash
# Fire a fraud burst at one account and watch risk-service catch it: 30 payments in ~5s, same fresh
# beneficiary (a burst is exactly what countLastMinute/amountZScore/newBeneficiary are for), then one
# more to a beneficiary already on the blocked list - a hard rule, no model needed. Prints the
# decisions, the first case an analyst would open, and proves none of the 31 payment sagas were
# touched by any of it: risk-service watches, it never blocks the payment itself.
#   scripts/replay-fraud.sh
set -euo pipefail
cd "$(dirname "$0")/.."

ACCOUNT=11111111-1111-1111-1111-111111111111
BASE=http://localhost:8085
BURST="burst-$(date +%s)"   # one fresh beneficiary for the whole burst: every payment is "new" to it

pay() {  # pay <wallet> <beneficiary> -> prints the payment id
  curl -sf -X POST "$BASE/api/v1/payments" -H 'Content-Type: application/json' -d "$(jq -n \
      --arg a "$ACCOUNT" --arg w "$1" --arg b "$2" \
      '{accountId: $a, wallets: [$w], amountMinor: 5000, currency: "GBP", beneficiary: $b}')" \
    | jq -r .paymentId
}

echo "sending 30 payments to $BURST, rotating wallets A-1..A-20 (5000 minor each, 50x the k6 amount)..."
ids=()
for i in $(seq 1 30); do
  ids+=("$(pay "A-$(( (i - 1) % 20 + 1 ))" "$BURST")")
done

echo "one more to mule-1, the blocked beneficiary..."
ids+=("$(pay A-1 mule-1)")

echo "waiting ~5s for risk-service to score all 31..."
sleep 5

echo
echo "-- risk_decision --"
docker exec ledgerflow-postgres psql -U risk -d risk -c \
  "SELECT decision, count(*) FROM risk_decision GROUP BY 1 ORDER BY 1"

echo "-- first case --"
curl -s localhost:8084/api/v1/cases | jq '.[0]'

echo "-- saga states of the 31 payments (untouched by the decision above) --"
for id in "${ids[@]}"; do curl -s "$BASE/api/v1/payments/$id" | jq -r .state; done | sort | uniq -c

REVIEW=$(docker exec ledgerflow-postgres psql -U risk -d risk -tAc "SELECT count(*) FROM risk_decision WHERE decision = 'REVIEW'")
BLOCK=$(docker exec ledgerflow-postgres psql -U risk -d risk -tAc "SELECT count(*) FROM risk_decision WHERE decision = 'BLOCK'")
[ "$REVIEW" -gt 0 ] && [ "$BLOCK" -gt 0 ] \
  || { echo "expected at least one REVIEW and one BLOCK, got REVIEW=$REVIEW BLOCK=$BLOCK"; exit 1; }
