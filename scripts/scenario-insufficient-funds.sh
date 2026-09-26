#!/usr/bin/env bash
# A payment for far more than A-2 holds: ledger reserves the wallet optimistically (a hold only
# checks the wallet exists, not that it can cover the amount) - the debit is where account-service
# actually refuses it, at capture. No money moves.
#   scripts/scenario-insufficient-funds.sh
set -euo pipefail
cd "$(dirname "$0")/.."

ACCOUNT=11111111-1111-1111-1111-111111111111
PAYMENT_URL=http://localhost:8085
BALANCE_URL=http://localhost:8083
TOKEN=${TOKEN:-$(scripts/token.sh ops)}
AUTH=(-H "Authorization: Bearer $TOKEN")

balance_of() { curl -sf "${AUTH[@]}" "$BALANCE_URL/api/v1/balances/$ACCOUNT/A-2" | jq -r .balanceMinor; }

before=$(balance_of)
amount=$(( before * 10 > 1000000 ? before * 10 : 1000000 ))
echo "A-2 balance: $before minor. requesting $amount minor (10x that, at least 1,000,000)..."

resp=$(curl -sf -X POST "${AUTH[@]}" "$PAYMENT_URL/api/v1/payments" -H 'Content-Type: application/json' \
  -d "$(jq -n --arg a "$ACCOUNT" --argjson amt "$amount" '{accountId: $a, wallets: ["A-2"], amountMinor: $amt, currency: "GBP"}')")
id=$(jq -r .paymentId <<< "$resp")
echo "payment $id"

deadline=$((SECONDS + 20))
last=""
body=""
while :; do
  body=$(curl -sf "${AUTH[@]}" "$PAYMENT_URL/api/v1/payments/$id")
  state=$(jq -r .state <<< "$body")
  if [ "$state" != "$last" ]; then echo "$(date -u +%T) $state"; last=$state; fi
  [ "$state" = Failed ] && break
  [ "$state" = Captured ] && { echo "captured, expected Failed"; exit 1; }
  if [ "$SECONDS" -ge "$deadline" ]; then echo "timed out waiting for Failed"; exit 1; fi
  sleep 0.2
done

reason=$(jq -r .reason <<< "$body")
echo "reason: $reason"
[ "$reason" = ISSUE_FAILED ] || { echo "expected ISSUE_FAILED, got $reason"; exit 1; }

after=$(balance_of)
echo "A-2 balance unchanged: $after minor"
[ "$after" -eq "$before" ] || { echo "balance moved: $before -> $after"; exit 1; }
