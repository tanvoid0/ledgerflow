#!/usr/bin/env bash
# Charged, no ticket (step 36). Alice checks out, but payment-service's 202 is dropped on the way back
# (the "fault-" request id triggers the dev fault seam), so ticketing never learns the paymentId and the
# order sits PENDING_PAYMENT. The payment captures anyway - alice's MAIN is debited. The sweep fails the
# unanswered order, and the late PaymentCaptured lands on a FAILED order: the orphan. confirm() refunds it,
# the order goes REFUNDED and alice's MAIN is restored.
#
# Needs the stack up WITH the step-36 override (the fault seam + fast sweep), e.g.:
#   docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.step36.yml up -d
#
#   scripts/scenario-orphan-refund.sh
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
  -H "Idempotency-Key: orphan-fund-$(date +%s%N)" \
  -d "$(jq -n --argjson a "$PRICE" --arg c "$CUR" \
      '{fromWalletId: "99999999-9999-9999-9999-999999999999", toWalletId: "22222222-0000-0000-0000-000000000001",
        amountMinor: $a, currency: $c, description: "orphan scenario funding"}')" > /dev/null

echo "alice holds seat $SEAT..."
ORDER=$(curl -sf -H "Authorization: Bearer $ALICE" -X POST "$GW/api/v1/events/$EVENT/holds" \
  -H 'content-type: application/json' -d '{"seats":["'"$SEAT"'"]}' | jq -r .id)
ACCT=$(curl -sf -H "Authorization: Bearer $ALICE" "$GW/api/v1/orders/$ORDER" | jq -r .accountId)

sleep 1
BEFORE=$(main_balance)
echo "alice (account $ACCT) MAIN before paying: $BEFORE"

# the "fault-" prefix flows gateway -> ticketing -> payment on X-Request-Id; payment commits the payment,
# then drops the 202, so checkout gets no clean answer and the order stays PENDING_PAYMENT with no paymentId
echo "alice checks out order $ORDER with a faulted request (the 202 will be dropped)..."
STATUS=$(curl -sf -H "Authorization: Bearer $ALICE" -H "X-Request-Id: fault-$(date +%s%N)" \
  -X POST "$GW/api/v1/orders/$ORDER/checkout" | jq -r .status)
echo "checkout returned status $STATUS (expected PENDING_PAYMENT)"
[ "$STATUS" = PENDING_PAYMENT ] || { echo "expected PENDING_PAYMENT, got $STATUS"; exit 1; }

echo "waiting for alice to be charged (MAIN drops below $BEFORE - the capture went through)..."
deadline=$((SECONDS + 20))
while :; do
  NOW=$(main_balance)
  [ "$NOW" -lt "$BEFORE" ] && { echo "alice charged: MAIN now $NOW"; break; }
  [ "$SECONDS" -ge "$deadline" ] && { echo "timed out waiting for the charge (MAIN still $NOW)"; exit 1; }
  sleep 0.2
done

echo "polling for REFUNDED (sweep failed the orphan, the late capture refunded it)..."
deadline=$((SECONDS + 30))
while :; do
  s=$(curl -sf -H "Authorization: Bearer $ALICE" "$GW/api/v1/orders/$ORDER" | jq -r .status)
  [ "$s" = REFUNDED ] && break
  [ "$SECONDS" -ge "$deadline" ] && { echo "timed out waiting for REFUNDED (still $s)"; exit 1; }
  sleep 0.3
done
echo "order $ORDER REFUNDED"

echo "seat $SEAT is FREE (it was freed when the sweep failed the order)..."
curl -sf "$GW/api/v1/events/$EVENT" | jq -e --arg s "$SEAT" '.seats[] | select(.seat==$s) | .status=="FREE"' > /dev/null

echo "polling for alice's MAIN to return to $BEFORE..."
deadline=$((SECONDS + 20))
while :; do
  AFTER=$(main_balance)
  [ "$AFTER" = "$BEFORE" ] && break
  [ "$SECONDS" -ge "$deadline" ] && { echo "balance not restored: before $BEFORE, after $AFTER"; exit 1; }
  sleep 0.3
done
echo "alice's MAIN back to $AFTER — charged, no ticket, then made whole"

echo "orphan refund end to end: order $ORDER charged with no ticket, swept to FAILED, the late capture refunded, seat $SEAT free, balance restored"
