#!/usr/bin/env bash
# usage: perf/compare.sh <before.json> <after.json> [max p99 regression %, default 10]
# Prints a markdown table you can paste into README, exits 1 if p99 got worse than allowed.
set -euo pipefail
BEFORE="${1:?before.json}"; AFTER="${2:?after.json}"; ALLOW="${3:-10}"

row() { jq -r --arg name "$(basename "$1" .json)" '.metrics |
  [$name, (.http_req_duration.values.med|floor), (.http_req_duration.values["p(95)"]|floor),
   (.http_req_duration.values["p(99)"]|floor), (.http_reqs.values.rate|floor),
   ((.http_req_failed.values.rate*100*100|floor)/100)] | "| \(.[0]) | \(.[1]) | \(.[2]) | \(.[3]) | \(.[4]) | \(.[5])% |"' "$1"; }

echo "| run | p50 ms | p95 ms | p99 ms | req/s | failed |"
echo "|---|---:|---:|---:|---:|---:|"
row "$BEFORE"; row "$AFTER"

B=$(jq '.metrics.http_req_duration.values["p(99)"]' "$BEFORE")
A=$(jq '.metrics.http_req_duration.values["p(99)"]' "$AFTER")
DELTA=$(jq -n --argjson a "$A" --argjson b "$B" '(($a - $b) / $b * 100 * 10 | floor) / 10')
echo
echo "p99: ${B%.*}ms -> ${A%.*}ms (${DELTA}%)"
jq -en --argjson d "$DELTA" --argjson allow "$ALLOW" '$d <= $allow' > /dev/null \
  || { echo "REGRESSION: p99 worse by more than ${ALLOW}%"; exit 1; }
