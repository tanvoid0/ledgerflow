#!/usr/bin/env bash
# Buys one ticket, then alice cancels her own order (no organiser, no sweep) and watches the money
# come back: REFUNDED, seat FREE, balance restored. Also checks the three guards: a stranger can't
# cancel someone else's order (404), a second cancel is a no-op (409), and an order on an event
# starting inside the 24h cutoff can't be self-cancelled (409, order stays CONFIRMED).
# Exercises step 37's buyer-initiated refund end to end through the gateway.
#   scripts/scenario-buyer-cancel.sh
set -euo pipefail
cd "$(dirname "$0")/.."

GW=${GW:-http://localhost:8088}
ALICE=$(scripts/token.sh alice)
BOB=$(scripts/token.sh bob)
OPS=$(scripts/token.sh ops)

main_balance() { curl -sf -H "Authorization: Bearer $ALICE" "$GW/api/v1/balances/$ACCT" | jq -r '.wallets[] | select(.label=="MAIN") | .balanceMinor'; }

fund() {
  local amount=$1 wallet=$2 currency=$3
  curl -sf -X POST -H "Authorization: Bearer $OPS" "$GW/api/v1/transfers" -H 'content-type: application/json' \
    -H "Idempotency-Key: buyer-cancel-fund-$(date +%s%N)" \
    -d "$(jq -n --argjson a "$amount" --arg c "$currency" --arg w "$wallet" \
        '{fromWalletId: "99999999-9999-9999-9999-999999999999", toWalletId: $w,
          amountMinor: $a, currency: $c, description: "buyer-cancel scenario funding"}')" > /dev/null
}

wait_for_order_status() {
  local order=$1 want=$2 deadline=$((SECONDS + ${3:-20}))
  while :; do
    s=$(curl -sf -H "Authorization: Bearer $ALICE" "$GW/api/v1/orders/$order" | jq -r .status)
    [ "$s" = "$want" ] && return 0
    [ "$SECONDS" -ge "$deadline" ] && { echo "timed out waiting for $order to reach $want (still $s)"; exit 1; }
    sleep 0.2
  done
}

hold_and_fund() {
  local event=$1
  local detail seat price cur order
  detail=$(curl -sf "$GW/api/v1/events/$event")
  seat=$(jq -r '[.seats[] | select(.status=="FREE")][0].seat' <<< "$detail")
  price=$(jq -r .priceMinor <<< "$detail")
  cur=$(jq -r .currency <<< "$detail")
  order=$(curl -sf -H "Authorization: Bearer $ALICE" -X POST "$GW/api/v1/events/$event/holds" \
    -H 'content-type: application/json' -d '{"seats":["'"$seat"'"]}' | jq -r .id)
  fund "$price" "22222222-0000-0000-0000-000000000001" "$cur"
  echo "$order $seat"
}

checkout_and_confirm() {
  local order=$1
  curl -sf -H "Authorization: Bearer $ALICE" -X POST "$GW/api/v1/orders/$order/checkout" > /dev/null
  wait_for_order_status "$order" CONFIRMED 20
}

# --- 1. happy path: alice cancels her own CONFIRMED order, well outside the cutoff ---
EVENT=$(curl -sf "$GW/api/v1/events" | jq -r '.[0].id')
echo "event $EVENT (seeded, weeks out — eligible)"
read -r ORDER SEAT <<< "$(hold_and_fund "$EVENT")"
ACCT=$(curl -sf -H "Authorization: Bearer $ALICE" "$GW/api/v1/orders/$ORDER" | jq -r .accountId)
sleep 1
BEFORE=$(main_balance)
echo "alice's MAIN before paying: $BEFORE"
checkout_and_confirm "$ORDER"
echo "order $ORDER CONFIRMED, seat $SEAT SOLD"

echo "a stranger (bob) tries to cancel alice's order..."
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $BOB" -X POST "$GW/api/v1/orders/$ORDER/cancel")
[ "$CODE" = "404" ] || { echo "expected 404 for bob cancelling alice's order, got $CODE"; exit 1; }
echo "bob got 404, as expected"

echo "alice cancels her own order $ORDER..."
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $ALICE" -X POST "$GW/api/v1/orders/$ORDER/cancel")
[ "$CODE" = "202" ] || { echo "expected 202 for alice's own cancel, got $CODE"; exit 1; }

wait_for_order_status "$ORDER" REFUNDED 30
echo "order $ORDER REFUNDED"

curl -sf "$GW/api/v1/events/$EVENT" | jq -e --arg s "$SEAT" '.seats[] | select(.seat==$s) | .status=="FREE"' > /dev/null
echo "seat $SEAT is FREE again"

deadline=$((SECONDS + 20))
while :; do
  AFTER=$(main_balance)
  [ "$AFTER" = "$BEFORE" ] && break
  [ "$SECONDS" -ge "$deadline" ] && { echo "balance not restored: before $BEFORE, after $AFTER"; exit 1; }
  sleep 0.3
done
echo "alice's MAIN back to $AFTER — the money came back"

echo "alice cancels the already-refunded order again..."
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $ALICE" -X POST "$GW/api/v1/orders/$ORDER/cancel")
[ "$CODE" = "409" ] || { echo "expected 409 for a second cancel, got $CODE"; exit 1; }
echo "second cancel got 409, as expected"

# --- 2. cutoff guard: an event starting within 24h can't be self-cancelled ---
echo "ops creates an event starting in 1 hour..."
STARTS_AT=$(python3 -c 'import datetime; print((datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=1)).isoformat())' 2>/dev/null \
  || date -u -d '+1 hour' +%Y-%m-%dT%H:%M:%SZ)
NEAR_EVENT=$(curl -sf -X POST -H "Authorization: Bearer $OPS" "$GW/api/v1/events" -H 'content-type: application/json' \
  -d "$(jq -n --arg starts "$STARTS_AT" '{title: "Cutoff Check", venue: "Test Hall", city: "Test City", category: "Test",
      startsAt: $starts, organiser: "org-cutoff-check", priceMinor: 1000, currency: "GBP", rows: 1, seatsPerRow: 1}')" \
  | jq -r .id)
echo "near event $NEAR_EVENT starts at $STARTS_AT"
read -r NEAR_ORDER NEAR_SEAT <<< "$(hold_and_fund "$NEAR_EVENT")"
checkout_and_confirm "$NEAR_ORDER"
echo "order $NEAR_ORDER CONFIRMED on the near event, seat $NEAR_SEAT SOLD"

CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $ALICE" -X POST "$GW/api/v1/orders/$NEAR_ORDER/cancel")
[ "$CODE" = "409" ] || { echo "expected 409 for a cancel inside the cutoff, got $CODE"; exit 1; }
STATUS=$(curl -sf -H "Authorization: Bearer $ALICE" "$GW/api/v1/orders/$NEAR_ORDER" | jq -r .status)
[ "$STATUS" = "CONFIRMED" ] || { echo "order should still be CONFIRMED after a refused cancel, is $STATUS"; exit 1; }
echo "cancel inside the 24h cutoff got 409, order $NEAR_ORDER stayed CONFIRMED"

echo "buyer self-service cancel end to end: own order refunded, stranger refused, double cancel refused, cutoff enforced"
