#!/usr/bin/env bash
# One payment through payment-service: fund A-1 if it's run dry, hold it, watch it settle, print
# the balance change and the trace id, so the recorder has a Tempo URL to paste.
#   scripts/scenario-authorize-capture.sh
set -euo pipefail
cd "$(dirname "$0")/.."

ACCOUNT=11111111-1111-1111-1111-111111111111
ACCOUNT_URL=http://localhost:8080
PAYMENT_URL=http://localhost:8085
BALANCE_URL=http://localhost:8083

balance_of() { curl -sf "$BALANCE_URL/api/v1/balances/$ACCOUNT/A-1" | jq -r .balanceMinor; }

bal=$(balance_of)
if [ "$bal" -lt 100 ]; then
  echo "A-1 is at $bal minor, funding it from TREASURY first..."
  wallets=$(curl -sf "$ACCOUNT_URL/api/v1/accounts/$ACCOUNT" | jq -c .wallets)
  treasury=$(jq -r '.[] | select(.label=="TREASURY") | .id' <<< "$wallets")
  target=$(jq -r '.[] | select(.label=="A-1") | .id' <<< "$wallets")
  curl -sf -X POST "$ACCOUNT_URL/api/v1/transfers" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: demo-fund-a1-$(date +%s)" \
    -d "$(jq -n --arg f "$treasury" --arg t "$target" \
        '{fromWalletId: $f, toWalletId: $t, amountMinor: 10000, currency: "GBP", description: "demo funding"}')" > /dev/null
  bal=$(balance_of)
fi
echo "A-1 balance before: $bal minor"

echo "POST /api/v1/payments: 100 minor GBP on A-1 to shop-1..."
resp=$(curl -si -X POST "$PAYMENT_URL/api/v1/payments" -H 'Content-Type: application/json' \
  -d "$(jq -n --arg a "$ACCOUNT" '{accountId: $a, wallets: ["A-1"], amountMinor: 100, currency: "GBP", beneficiary: "shop-1"}')")
request_id=$(tr -d '\r' <<< "$resp" | grep -i '^x-request-id:' | head -1 | cut -d' ' -f2)
body=$(tr -d '\r' <<< "$resp" | sed -n '/^{/,$p')
id=$(jq -r .paymentId <<< "$body")
echo "payment $id, trace $request_id"

echo "polling for Captured..."
deadline=$((SECONDS + 20))
last=""
while :; do
  state=$(curl -sf "$PAYMENT_URL/api/v1/payments/$id" | jq -r .state)
  if [ "$state" != "$last" ]; then echo "$(date -u +%T) $state"; last=$state; fi
  [ "$state" = Captured ] && break
  [ "$state" = Failed ] && { echo "failed, expected Captured"; exit 1; }
  if [ "$SECONDS" -ge "$deadline" ]; then echo "timed out waiting for Captured"; exit 1; fi
  sleep 0.2
done

echo "waiting for balance-service's projection to catch up (Captured is settlement's word, not the read model's)..."
deadline=$((SECONDS + 10))
after=$(balance_of)
while [ "$after" -ne $((bal - 100)) ]; do
  if [ "$SECONDS" -ge "$deadline" ]; then echo "balance did not drop by 100 (still $after)"; exit 1; fi
  sleep 0.2
  after=$(balance_of)
done
echo "A-1 balance after: $after minor (dropped by 100)"

tempo="http://localhost:3000/explore?schemaVersion=1&panes=%7B%22t%22%3A%7B%22datasource%22%3A%22tempo%22%2C%22queries%22%3A%5B%7B%22query%22%3A%22$request_id%22%2C%22queryType%22%3A%22traceql%22%2C%22datasource%22%3A%7B%22type%22%3A%22tempo%22%2C%22uid%22%3A%22tempo%22%7D%2C%22refId%22%3A%22A%22%7D%5D%2C%22range%22%3A%7B%22from%22%3A%22now-1h%22%2C%22to%22%3A%22now%22%7D%7D%7D&orgId=1"
echo "trace: $tempo"
echo "if that URL 404s (datasource uid varies by install): Grafana -> Explore -> Tempo -> search by trace id $request_id"
