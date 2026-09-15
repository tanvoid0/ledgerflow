#!/usr/bin/env bash
# usage: perf/settled.sh <summary.json> <since>   (since = Postgres now() taken before the run; run.sh does this)
# A payment is done when its saga row reaches Captured or Failed, not when the POST answers 202.
# Reads that from payment's database and adds it to the k6 summary as metric payment_settled,
# so `METRIC=payment_settled perf/compare.sh` prints the end-to-end row. One clock (Postgres) on both ends.
set -euo pipefail
OUT="${1:?summary.json}"; SINCE="${2:?since}"
sql() { docker exec ledgerflow-postgres psql -U ledgerflow -d payment -tA -c "$1"; }
WINDOW="created_at >= '$SINCE'::timestamptz + interval '30 seconds'"   # payments.js warms up for 30s

# the tail: every open saga either answers or hits its 15s step deadline
for _ in $(seq 40); do
  [ "$(sql "SELECT count(*) FROM sagas WHERE $WINDOW AND state NOT IN ('Captured', 'Failed')")" = 0 ] && break
  sleep 1
done

STATS=$(sql "SELECT json_build_object(
    'count', count(*), 'captured', count(*) FILTER (WHERE state = 'Captured'), 'failed', count(*) FILTER (WHERE state = 'Failed'),
    'avg', avg(ms), 'med', percentile_cont(0.5) WITHIN GROUP (ORDER BY ms),
    'p(95)', percentile_cont(0.95) WITHIN GROUP (ORDER BY ms), 'p(99)', percentile_cont(0.99) WITHIN GROUP (ORDER BY ms), 'max', max(ms))
  FROM (SELECT state, extract(epoch FROM updated_at - created_at) * 1000 AS ms
          FROM sagas WHERE $WINDOW AND state IN ('Captured', 'Failed')) t")

jq --argjson s "$STATS" '.metrics.payment_settled = {type: "trend", contains: "time", values: $s}' "$OUT" > "$OUT.tmp" \
  && mv "$OUT.tmp" "$OUT"
