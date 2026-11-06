#!/usr/bin/env bash
# Buys one ticket, then has ops cancel the event and watches the money come back: the order goes
# REFUNDED, the seat returns to FREE, and alice's MAIN balance is restored to what it was before she
# paid. Exercises step 35's refund-on-cancel end to end through the gateway.
#   scripts/scenario-refund.sh
set -euo pipefail
cd "$(dirname "$0")/.."

GW=${GW:-http://localhost:8088}
ALICE=$(scripts/token.sh alice)
OPS=$(scripts/token.sh ops)

main_balance() { curl -sf -H "Authorization: Bearer $ALICE" "$GW/api/v1/balances/$ACCT" | jq -r '.wallets[] | select(.label=="MAIN") | .balanceMinor'; }

EVENT=$(curl -sf "$GW/api/v1/events" | jq -r '.[0].id')
DETAIL=$(curl -sf "$GW/api/v1/events/$EVENT")
SEAT=$(jq -r '[.seats[] | select(.status=="FREE")][0].seat' <<< "$DETAIL")
PRICE=$(jq -r .priceMinor <<< "$DETAIL")
CUR=$(jq -r .currency <<< "$DETAIL")
echo "event $EVENT, seat $SEAT, $PRICE $CUR"

echo "ops funds alice's MAIN with $PRICE from TREASURY..."
curl -sf -X POST -H "Authorization: Bearer $OPS" "$GW/api/v1/transfers" -H 'content-type: application/json' \
  -H "Idempotency-Key: refund-fund-$(date +%s%N)" \
  -d "$(jq -n --argjson a "$PRICE" --arg c "$CUR" \
      '{fromWalletId: "99999999-9999-9999-9999-999999999999", toWalletId: "22222222-0000-0000-0000-000000000001",
        amountMinor: $a, currency: $c, description: "refund scenario funding"}')" > /dev/null

echo "alice holds seat $SEAT..."
ORDER=$(curl -sf -H "Authorization: Bearer $ALICE" -X POST "$GW/api/v1/events/$EVENT/holds" \
  -H 'content-type: application/json' -d '{"seats":["'"$SEAT"'"]}' | jq -r .id)
ACCT=$(curl -sf -H "Authorization: Bearer $ALICE" "$GW/api/v1/orders/$ORDER" | jq -r .accountId)

# the figure the refund must restore: alice's MAIN once funded, before she pays
sleep 1
BEFORE=$(main_balance)
echo "alice (account $ACCT) MAIN before paying: $BEFORE"

echo "alice checks out order $ORDER..."
PAY=$(curl -sf -H "Authorization: Bearer $ALICE" -X POST "$GW/api/v1/orders/$ORDER/checkout" | jq -r .paymentId)

echo "polling for CONFIRMED..."
deadline=$((SECONDS + 20))
while :; do
  s=$(curl -sf -H "Authorization: Bearer $ALICE" "$GW/api/v1/orders/$ORDER" | jq -r .status)
  [ "$s" = CONFIRMED ] && break
  [ "$s" = FAILED ] && { echo "order failed, expected CONFIRMED"; exit 1; }
  [ "$SECONDS" -ge "$deadline" ] && { echo "timed out waiting for CONFIRMED (still $s)"; exit 1; }
  sleep 0.2
done
echo "order $ORDER CONFIRMED, payment $PAY, seat SOLD"

echo "ops cancels event $EVENT..."
curl -sf -X POST -H "Authorization: Bearer $OPS" "$GW/api/v1/events/$EVENT/cancel" > /dev/null

echo "polling for REFUNDED..."
deadline=$((SECONDS + 30))
while :; do
  s=$(curl -sf -H "Authorization: Bearer $ALICE" "$GW/api/v1/orders/$ORDER" | jq -r .status)
  [ "$s" = REFUNDED ] && break
  [ "$SECONDS" -ge "$deadline" ] && { echo "timed out waiting for REFUNDED (still $s)"; exit 1; }
  sleep 0.3
done
echo "order $ORDER REFUNDED"

echo "seat $SEAT is FREE again..."
curl -sf "$GW/api/v1/events/$EVENT" | jq -e --arg s "$SEAT" '.seats[] | select(.seat==$s) | .status=="FREE"' > /dev/null

echo "polling for alice's MAIN to return to $BEFORE..."
deadline=$((SECONDS + 20))
while :; do
  AFTER=$(main_balance)
  [ "$AFTER" = "$BEFORE" ] && break
  [ "$SECONDS" -ge "$deadline" ] && { echo "balance not restored: before $BEFORE, after $AFTER"; exit 1; }
  sleep 0.3
done
echo "alice's MAIN back to $AFTER — the money came back"

echo "refunded order $ORDER on cancelled event $EVENT end to end: payment $PAY reversed, seat $SEAT free, balance restored"
