#!/usr/bin/env bash
# Buys one ticket end to end through the gateway: browse public, hold and pay as alice, watch the
# saga confirm the order, then check it landed everywhere ops can see it (payment, orders, dev portal).
#   scripts/scenario-buy-ticket.sh
set -euo pipefail
cd "$(dirname "$0")/.."

GW=${GW:-http://localhost:8088}
ALICE=$(scripts/token.sh alice)
OPS=$(scripts/token.sh ops)

echo "GET /api/v1/events is public..."
[ "$(curl -s -o /dev/null -w '%{http_code}' "$GW/api/v1/events")" = 200 ]

EVENT=$(curl -sf "$GW/api/v1/events" | jq -r '.[0].id')
DETAIL=$(curl -sf "$GW/api/v1/events/$EVENT")
SEAT=$(jq -r '[.seats[] | select(.status=="FREE")][0].seat' <<< "$DETAIL")
PRICE=$(jq -r .priceMinor <<< "$DETAIL")
echo "event $EVENT, seat $SEAT, $PRICE minor"

# every run spends real money from alice's MAIN: top it up by the seat price first so repeated runs never drain her
echo "ops funds alice's MAIN with $PRICE from TREASURY..."
curl -sf -X POST -H "Authorization: Bearer $OPS" "$GW/api/v1/transfers" -H 'content-type: application/json' \
  -H "Idempotency-Key: buy-ticket-fund-$(date +%s%N)" \
  -d "$(jq -n --argjson a "$PRICE" --arg c "$(jq -r .currency <<< "$DETAIL")" \
      '{fromWalletId: "99999999-9999-9999-9999-999999999999", toWalletId: "22222222-0000-0000-0000-000000000001",
        amountMinor: $a, currency: $c, description: "scenario funding"}')" > /dev/null

echo "a hold without a token is rejected..."
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GW/api/v1/events/$EVENT/holds" \
    -H 'content-type: application/json' -d '{"seats":["'"$SEAT"'"]}')" = 401 ]

echo "alice holds seat $SEAT..."
ORDER=$(curl -sf -H "Authorization: Bearer $ALICE" -X POST "$GW/api/v1/events/$EVENT/holds" \
  -H 'content-type: application/json' -d '{"seats":["'"$SEAT"'"]}' | jq -r .id)

echo "alice checks out order $ORDER..."
PAY=$(curl -sf -H "Authorization: Bearer $ALICE" -X POST "$GW/api/v1/orders/$ORDER/checkout" | jq -r .paymentId)

echo "polling for CONFIRMED..."
deadline=$((SECONDS + 20))
s=""
while :; do
  s=$(curl -sf -H "Authorization: Bearer $ALICE" "$GW/api/v1/orders/$ORDER" | jq -r .status)
  [ "$s" = CONFIRMED ] && break
  [ "$s" = FAILED ] && { echo "order failed, expected CONFIRMED"; exit 1; }
  if [ "$SECONDS" -ge "$deadline" ]; then echo "timed out waiting for CONFIRMED (still $s)"; exit 1; fi
  sleep 0.2
done
echo "order $ORDER confirmed, payment $PAY"

echo "seat $SEAT is SOLD..."
curl -sf "$GW/api/v1/events/$EVENT" | jq -e --arg s "$SEAT" '.seats[] | select(.seat==$s) | .status=="SOLD"' > /dev/null

echo "ops sees the payment Captured..."
curl -sf -H "Authorization: Bearer $OPS" "$GW/api/v1/payments/$PAY" | jq -e '.state=="Captured"' > /dev/null

echo "ops sees the order in /orders/all..."
curl -sf -H "Authorization: Bearer $OPS" "$GW/api/v1/orders/all" | jq -e --arg o "$ORDER" 'map(select(.id==$o)) | length==1' > /dev/null

echo "ops reads the dev portal's Prometheus proxy..."
curl -sf -H "Authorization: Bearer $OPS" "$GW/api/v1/dev/prom/query?query=up" | jq -e '.status=="success"' > /dev/null

echo "alice is refused the dev portal..."
[ "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $ALICE" "$GW/api/v1/dev/prom/query?query=up")" = 403 ]

echo "bought seat $SEAT on event $EVENT end to end: order $ORDER, payment $PAY"
