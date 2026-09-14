#!/usr/bin/env bash
# Messages sitting in dead letter topics. Anything above zero is an incident, not a metric.
set -euo pipefail
for t in $(docker exec lf-redpanda rpk topic list | awk '$1 ~ /\.dlt$/ {print $1}'); do
  docker exec lf-redpanda rpk topic describe "$t" -p \
    | awk -v t="$t" 'NR > 1 { depth += $6 - $5 } END { printf "%-50s %d\n", t, depth }'
done
