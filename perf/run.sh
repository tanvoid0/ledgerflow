#!/usr/bin/env bash
# usage: perf/run.sh <scenario> <label>   e.g. perf/run.sh transfer baseline
# env:   RATE, DURATION, BASE_URL, TOKEN, P99_MS pass straight through to the script
set -euo pipefail
cd "$(dirname "$0")/.."
SCENARIO="${1:?scenario, e.g. transfer}"; LABEL="${2:?label, e.g. baseline}"
OUT="docs/perf/${SCENARIO}-${LABEL}.json"
mkdir -p docs/perf

k6 run -e SUMMARY="$OUT" ${RATE:+-e RATE=$RATE} ${DURATION:+-e DURATION=$DURATION} \
       ${BASE_URL:+-e BASE_URL=$BASE_URL} ${TOKEN:+-e TOKEN=$TOKEN} ${P99_MS:+-e P99_MS=$P99_MS} \
       "perf/k6/${SCENARIO}.js" || echo "k6 exit $? (99 = a threshold failed; the summary is still written)"

jq -r '.metrics
       | "\($LABEL)  p50=\(.http_req_duration.values.med|floor)ms  p95=\(.http_req_duration.values["p(95)"]|floor)ms  p99=\(.http_req_duration.values["p(99)"]|floor)ms  rps=\(.http_reqs.values.rate|floor)  failed=\(.http_req_failed.values.rate*100|floor)%"' \
   --arg LABEL "$LABEL" "$OUT"
echo "recorded $OUT"
