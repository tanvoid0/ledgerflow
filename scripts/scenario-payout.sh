#!/usr/bin/env bash
# The other half of the money story: an organiser gets paid. A buyer buys one ticket (buyer MAIN ->
# TREASURY on capture), which now feeds a real settlement_item; the nightly batch nets it for the
# event's organiser and emits MerchantSettled; the new payout listener moves the net out of TREASURY
# into the organiser's MAIN wallet. Asserts the organiser's balance rises by exactly the net (gross
# minus the 2.9% + 30 fee the platform keeps). An item is dated by its event's day (UTC), the night after
# which the cron nets it; the scenario launches that date by hand. The batch runs once per date, so
# this is a first-run-for-that-date proof.
#   scripts/scenario-payout.sh
set -euo pipefail
cd "$(dirname "$0")/.."

GW=${GW:-http://localhost:8088}
ALICE=$(scripts/token.sh alice)
OPS=$(scripts/token.sh ops)

# organiser name -> deterministic payout account id (UUID.nameUUIDFromBytes("organiser:"+name)),
# the same map settlement's MerchantPayoutListener uses and V10__demo_organisers.sql seeded.
organiser_account() {
  case "$1" in
    org-wildfire)    echo ac40be4f-7b83-3793-89dc-85eb7fd35ca8 ;;
    org-laughtrack)  echo 6eb8c30d-3248-3301-b8e7-0df93bc18bab ;;
    org-hamlet)      echo d65f5cc0-12a6-3b45-b246-2acc2881e7e8 ;;
    org-sixnations)  echo 1651bd79-81ca-3f75-ac93-5a076a234fe4 ;;
    org-indienights) echo 55bc9f95-8178-3379-b389-1a14dc3cbb6f ;;
    org-tempest)     echo 11cbc430-c43b-3e30-a004-8c0bf89e6f32 ;;
    *) echo "unknown organiser $1 (not a seeded demo organiser)" >&2; return 1 ;;
  esac
}

# MAIN balance of any account, read as staff (ops has account-read). Missing projection -> 0.
main_balance() {
  curl -s -H "Authorization: Bearer $OPS" "$GW/api/v1/balances/$1" \
    | jq -r '([.wallets[]? | select(.label=="MAIN") | .balanceMinor] + [0])[0]'
}

fund() {
  curl -sf -X POST -H "Authorization: Bearer $OPS" "$GW/api/v1/transfers" -H 'content-type: application/json' \
    -H "Idempotency-Key: payout-fund-$(date +%s%N)" \
    -d "$(jq -n --argjson a "$1" --arg c "$3" --arg w "$2" \
        '{fromWalletId: "99999999-9999-9999-9999-999999999999", toWalletId: $w,
          amountMinor: $a, currency: $c, description: "payout scenario funding"}')" > /dev/null
}

wait_for_order_status() {
  local order=$1 want=$2 deadline=$((SECONDS + ${3:-20})) s
  while :; do
    s=$(curl -sf -H "Authorization: Bearer $ALICE" "$GW/api/v1/orders/$order" | jq -r .status)
    [ "$s" = "$want" ] && return 0
    [ "$SECONDS" -ge "$deadline" ] && { echo "timed out waiting for $order to reach $want (still $s)" >&2; exit 1; }
    sleep 0.2
  done
}

# --- 1. a buyer buys one ticket on a seeded-organiser event ---
EVENT=$(curl -sf "$GW/api/v1/events" | jq -r '.[0].id')
DETAIL=$(curl -sf "$GW/api/v1/events/$EVENT")
ORGANISER=$(jq -r .organiser <<< "$DETAIL")
PRICE=$(jq -r .priceMinor <<< "$DETAIL")
CUR=$(jq -r .currency <<< "$DETAIL")
SEAT=$(jq -r '[.seats[] | select(.status=="FREE")][0].seat' <<< "$DETAIL")
SEAT2=$(jq -r '[.seats[] | select(.status=="FREE")][1].seat' <<< "$DETAIL")
ORG_ACCT=$(organiser_account "$ORGANISER")
echo "event $EVENT, organiser $ORGANISER (account $ORG_ACCT), seats $SEAT + $SEAT2 at $PRICE $CUR"

buy() {
  local order
  order=$(curl -sf -H "Authorization: Bearer $ALICE" -X POST "$GW/api/v1/events/$EVENT/holds" \
    -H 'content-type: application/json' -d '{"seats":["'"$1"'"]}' | jq -r .id)
  fund "$PRICE" "22222222-0000-0000-0000-000000000001" "$CUR"
  sleep 1
  curl -sf -H "Authorization: Bearer $ALICE" -X POST "$GW/api/v1/orders/$order/checkout" > /dev/null
  wait_for_order_status "$order" CONFIRMED 20
  echo "$order"
}

ORDER=$(buy "$SEAT")
echo "order $ORDER CONFIRMED, money is in TREASURY, a settlement_item is fed for the event's day"

# a second order, refunded before tonight's batch: its item must drop out of the net, or TREASURY
# pays the same money twice (back to the buyer and out to the organiser)
REFUNDED=$(buy "$SEAT2")
curl -sf -H "Authorization: Bearer $ALICE" -X POST "$GW/api/v1/orders/$REFUNDED/cancel" > /dev/null
wait_for_order_status "$REFUNDED" REFUNDED 20
echo "order $REFUNDED CONFIRMED then cancelled by the buyer, REFUNDED before the batch"

# --- 2. net it and pay the organiser ---
FEE=$(( PRICE * 29 / 1000 + 30 ))
NET=$(( PRICE - FEE ))
BEFORE=$(main_balance "$ORG_ACCT")
echo "organiser MAIN before payout: $BEFORE (expecting +$NET = $PRICE - $FEE fee)"

DAY=$(jq -r '.startsAt[0:10]' <<< "$DETAIL")   # the cron would run this the morning after the event
CODE=$(curl -s -o /tmp/payout-launch.json -w '%{http_code}' -X POST -H "Authorization: Bearer $OPS" \
  "$GW/api/v1/batch/jobs/nightlySettlementJob" -H 'content-type: application/json' \
  -d "$(jq -n --arg d "$DAY" '{businessDate: $d}')")
if [ "$CODE" = "409" ]; then
  echo "nightlySettlementJob already ran for $DAY on this stack — rerun on a fresh stack"
elif [ "$CODE" != "200" ] && [ "$CODE" != "202" ]; then
  echo "batch launch failed ($CODE): $(cat /tmp/payout-launch.json)"; exit 1
else
  echo "launched nightlySettlementJob for $DAY"
fi

deadline=$((SECONDS + 40))
while :; do
  AFTER=$(main_balance "$ORG_ACCT")
  [ "$AFTER" = "$(( BEFORE + NET ))" ] && break
  [ "$SECONDS" -ge "$deadline" ] && { echo "organiser not paid: before $BEFORE, after $AFTER, expected $(( BEFORE + NET ))"; exit 1; }
  sleep 0.4
done
echo "organiser MAIN now $AFTER — paid $NET $CUR, the fee $FEE stayed in TREASURY"
echo "organiser payout end to end: capture -> settlement_item -> nightly batch -> MerchantSettled -> payout"
