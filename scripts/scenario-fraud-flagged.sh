#!/usr/bin/env bash
# replay-fraud.sh trimmed for the demo: same 30 payments (countLastMinute is what actually
# crosses risk.review-threshold - 10 measured 0 REVIEW against every model version tried, 30 is
# reliable) to one fresh beneficiary, one more to mule-1 (a hard rule, no model needed), then
# risk-service's case note - proof it watches and never touches a saga.
#   scripts/scenario-fraud-flagged.sh
set -euo pipefail
cd "$(dirname "$0")/.."

ACCOUNT=11111111-1111-1111-1111-111111111111
ACCOUNT_URL=http://localhost:8080
BALANCE_URL=http://localhost:8083
PAYMENT_URL=http://localhost:8085
BURST="burst-$(date +%s)"
TOKEN=${TOKEN:-$(scripts/token.sh ops)}
AUTH=(-H "Authorization: Bearer $TOKEN")

pay() {  # pay <wallet> <beneficiary> -> prints the payment id
  curl -sf -X POST "${AUTH[@]}" "$PAYMENT_URL/api/v1/payments" -H 'Content-Type: application/json' -d "$(jq -n \
      --arg a "$ACCOUNT" --arg w "$1" --arg b "$2" \
      '{accountId: $a, wallets: [$w], amountMinor: 5000, currency: "GBP", beneficiary: $b}')" \
    | jq -r .paymentId
}

# a wallet hit twice in the rotation below needs 10000 to cover it; a second demo/walkthrough run on the
# same account otherwise finds it already spent - top every wallet the burst uses back up first
wallets=$(curl -sf "${AUTH[@]}" "$ACCOUNT_URL/api/v1/accounts/$ACCOUNT" | jq -c .wallets)
treasury=$(jq -r '.[] | select(.label=="TREASURY") | .id' <<< "$wallets")
fund() {  # fund <label> -> tops it up to 10000 minor if it is short
  local bal=$(curl -sf "${AUTH[@]}" "$BALANCE_URL/api/v1/balances/$ACCOUNT/$1" | jq -r .balanceMinor)
  if [ "$bal" -lt 10000 ]; then
    local target=$(jq -r --arg l "$1" '.[] | select(.label==$l) | .id' <<< "$wallets")
    curl -sf -X POST "${AUTH[@]}" "$ACCOUNT_URL/api/v1/transfers" -H 'Content-Type: application/json' \
      -H "Idempotency-Key: demo-fund-$1-$(date +%s)-$RANDOM" \
      -d "$(jq -n --arg f "$treasury" --arg t "$target" --argjson amt "$((10000 - bal))" \
          '{fromWalletId: $f, toWalletId: $t, amountMinor: $amt, currency: "GBP", description: "demo funding"}')" > /dev/null
  fi
}
echo "topping up A-1..A-20 to 10000 minor each..."
for i in $(seq 1 20); do fund "A-$i"; done

echo "sending 30 payments to $BURST, rotating wallets A-2..A-20 (5000 minor each; A-1 skipped - the"
echo "authorize-capture scenario before this one already spent 100 of it, and two 5000 hits need all 10000)..."
ids=()
for i in $(seq 1 30); do
  ids+=("$(pay "A-$(( (i - 1) % 19 + 2 ))" "$BURST")")
done

echo "one more to mule-1, the blocked beneficiary..."
ids+=("$(pay A-1 mule-1)")

echo "waiting ~5s for risk-service to score all 31..."
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
deadline=$((SECONDS + 60))   # gemma4's first call in a while is a cold model load, tens of seconds on its own
narrative=null
generated_by=null
while [ "$narrative" = null ]; do
  case=$(curl -sf "${AUTH[@]}" "localhost:8084/api/v1/cases" | jq '[.[] | select(.decision=="REVIEW")][0]')
  narrative=$(jq -r '.narrative' <<< "$case")
  generated_by=$(jq -r '.generatedBy' <<< "$case")
  if [ "$narrative" = null ]; then
    if [ "$SECONDS" -ge "$deadline" ]; then echo "no REVIEW case got a narrative in 60s"; exit 1; fi
    sleep 0.5
  fi
done
echo "$narrative"
if [ "$generated_by" = gemma4 ]; then
  echo "gemma4 (local model)"
else
  echo "template (Ollama not running - install it and pull gemma4 for a real note)"
fi

echo "-- saga states of the 31 payments (untouched by the decision above) --"
states=$(for id in "${ids[@]}"; do curl -sf "${AUTH[@]}" "$PAYMENT_URL/api/v1/payments/$id" | jq -r .state; done | sort | uniq -c)
echo "$states"
grep -q '31 Captured' <<< "$states" || { echo "expected all 31 Captured"; exit 1; }
