#!/usr/bin/env bash
# The four beats of docs/walkthrough.md, paced for a live demo: a keypress between each.
# Assumes scripts/demo.sh already brought the stack up.
#   scripts/walkthrough.sh
set -euo pipefail
cd "$(dirname "$0")/.."

next() { read -rp "-- enter for beat $1 -- " _; }

PORTS=(8080 8081 8082 8083 8084 8085 8086 8087)
up() { curl -sf "localhost:$1/actuator/health" > /dev/null 2>&1; }

echo "== 1. Up =="
for p in "${PORTS[@]}"; do
  up "$p" || { echo "port $p is not answering - run ./scripts/demo.sh first"; exit 1; }
done
echo "all eight services are up. same compose file, same mvnw targets as README's \"Run it\" - nothing here is a mock."
next 2

echo "== 2. One authorisation, across seven services =="
out=$(./scripts/scenario-authorize-capture.sh)
echo "$out"
request_id=$(grep '^payment ' <<< "$out" | sed 's/.*trace //')
tempo=$(grep '^trace: ' <<< "$out" | cut -d' ' -f2-)
if [ -n "$tempo" ] && curl -sf -o /dev/null "$tempo"; then
  echo "Grafana: $tempo"
else
  echo "Grafana -> Explore -> Tempo -> search by trace id $request_id"
fi
next 3

echo "== 3. Capture, and the ledger summing to zero =="
docker exec ledgerflow-postgres psql -U ledgerflow -d account -c "SELECT SUM(amount_minor) FROM postings"
next 4

echo "== 4. A burst, a flagged case =="
./scripts/scenario-fraud-flagged.sh
echo
echo "-- GET /api/v1/cases (first case) --"
curl -s localhost:8084/api/v1/cases | jq '.[0]'
