#!/usr/bin/env bash
# usage: perf/run.sh <scenario> <label>   e.g. perf/run.sh transfer baseline
# env:   RATE, DURATION, BASE_URL, TOKEN, P99_MS pass straight through to the script
# The summary lands in docs/perf/<scenario>-<label>.json stamped with commit, date and host (.meta),
# so perf/history.sh can line the runs up over time. Same label = the measurement is replaced.
set -euo pipefail
cd "$(dirname "$0")/.."
SCENARIO="${1:?scenario, e.g. transfer}"; LABEL="${2:?label, e.g. baseline}"
OUT="docs/perf/${SCENARIO}-${LABEL}.json"
mkdir -p docs/perf

# the payments scenario is measured twice: the POST by k6, the settled payment by the database (see settled.sh)
PSQL=${PSQL:-docker exec ledgerflow-postgres psql}   # see settled.sh
[ "$SCENARIO" = payments ] && SINCE=$($PSQL -U ledgerflow -d payment -tAc 'SELECT now()')

k6 run -e SUMMARY="$OUT" ${RATE:+-e RATE=$RATE} ${DURATION:+-e DURATION=$DURATION} \
       ${BASE_URL:+-e BASE_URL=$BASE_URL} ${TOKEN:+-e TOKEN=$TOKEN} ${P99_MS:+-e P99_MS=$P99_MS} \
       "perf/k6/${SCENARIO}.js" || echo "k6 exit $? (99 = a threshold failed; the summary is still written)"

[ "$SCENARIO" = payments ] && perf/settled.sh "$OUT" "$SINCE"

cpu() { case "$OSTYPE" in
  msys*|cygwin*) powershell.exe -NoProfile -Command '(Get-CimInstance Win32_Processor).Name' | tr -d '\r';;
  darwin*)       sysctl -n machdep.cpu.brand_string;;
  *)             sed -n 's/^model name\s*: //p' /proc/cpuinfo | head -1;;
esac; }
jq --arg scenario "$SCENARIO" --arg label "$LABEL" --arg commit "$(git describe --always --dirty)" \
   --arg date "$(date -u +%FT%TZ)" --arg host "$(cpu | xargs), $(nproc) threads" \
   '.meta += {scenario: $scenario, label: $label, commit: $commit, date: $date, host: $host}' "$OUT" > "$OUT.tmp" \
  && mv "$OUT.tmp" "$OUT"

jq -r '.metrics
       | "\($LABEL)  p50=\(.http_req_duration.values.med|floor)ms  p95=\(.http_req_duration.values["p(95)"]|floor)ms  p99=\(.http_req_duration.values["p(99)"]|floor)ms  rps=\(.http_reqs.values.rate|floor)  failed=\(.http_req_failed.values.rate*100|floor)%"
         + (.payment_settled // {} | if .values then "\n\($LABEL)  settled: p50=\(.values.med|floor)ms  p95=\(.values["p(95)"]|floor)ms  p99=\(.values["p(99)"]|floor)ms  captured=\(.values.captured)  failed=\(.values.failed)" else "" end)' \
   --arg LABEL "$LABEL" "$OUT"
echo "recorded $OUT"
