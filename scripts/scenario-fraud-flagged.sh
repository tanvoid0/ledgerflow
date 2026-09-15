#!/usr/bin/env bash
# replay-fraud.sh trimmed for the demo: 10 payments in ~2s to one fresh beneficiary, one more to
# mule-1 (a hard rule, no model needed), then risk-service's case note - proof it watches and
# never touches a saga.
#   scripts/scenario-fraud-flagged.sh
set -euo pipefail
cd "$(dirname "$0")/.."

ACCOUNT=11111111-1111-1111-1111-111111111111
PAYMENT_URL=http://localhost:8085
BURST="burst-$(date +%s)"

pay() {  # pay <wallet> <beneficiary> -> prints the payment id
  curl -sf -X POST "$PAYMENT_URL/api/v1/payments" -H 'Content-Type: application/json' -d "$(jq -n \
      --arg a "$ACCOUNT" --arg w "$1" --arg b "$2" \
      '{accountId: $a, wallets: [$w], amountMinor: 5000, currency: "GBP", beneficiary: $b}')" \
    | jq -r .paymentId
}

echo "sending 10 payments to $BURST, rotating wallets A-1..A-10 (5000 minor each)..."
ids=()
for i in $(seq 1 10); do
  ids+=("$(pay "A-$i" "$BURST")")
done

echo "one more to mule-1, the blocked beneficiary..."
ids+=("$(pay A-1 mule-1)")

echo "waiting ~5s for risk-service to score all 11..."
sleep 5

echo
echo "-- risk_decision --"
docker exec ledgerflow-postgres psql -U risk -d risk -c \
  "SELECT decision, count(*) FROM risk_decision GROUP BY 1 ORDER BY 1"

review=$(docker exec ledgerflow-postgres psql -U risk -d risk -tAc "SELECT count(*) FROM risk_decision WHERE decision = 'REVIEW'")
block=$(docker exec ledgerflow-postgres psql -U risk -d risk -tAc "SELECT count(*) FROM risk_decision WHERE decision = 'BLOCK'")
[ "$review" -gt 0 ] && [ "$block" -gt 0 ] \
  || { echo "expected at least one REVIEW and one BLOCK, got REVIEW=$review BLOCK=$block"; exit 1; }

echo "-- newest case note --"
deadline=$((SECONDS + 20))
narrative=null
generated_by=null
while [ "$narrative" = null ]; do
  case=$(curl -sf "localhost:8084/api/v1/cases" | jq '[.[] | select(.decision=="REVIEW")][0]')
  narrative=$(jq -r '.narrative' <<< "$case")
  generated_by=$(jq -r '.generatedBy' <<< "$case")
  if [ "$narrative" = null ]; then
    if [ "$SECONDS" -ge "$deadline" ]; then echo "no REVIEW case got a narrative in 20s"; exit 1; fi
    sleep 0.5
  fi
done
echo "$narrative"
if [ "$generated_by" = gemma4 ]; then
  echo "gemma4 (local model)"
else
  echo "template (Ollama not running - install it and pull gemma4 for a real note)"
fi

echo "-- saga states of the 11 payments (untouched by the decision above) --"
states=$(for id in "${ids[@]}"; do curl -sf "$PAYMENT_URL/api/v1/payments/$id" | jq -r .state; done | sort | uniq -c)
echo "$states"
grep -q '11 Captured' <<< "$states" || { echo "expected all 11 Captured"; exit 1; }
