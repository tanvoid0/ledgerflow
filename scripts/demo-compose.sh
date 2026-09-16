#!/usr/bin/env bash
# One script, a clean machine, the whole system in containers - the compose equivalent of
# scripts/demo.sh (which runs the eight services as host JVMs, and is what the perf numbers
# in docs/measurements are taken on).
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE="docker compose -f infra/compose/docker-compose.yml -f infra/compose/docker-compose.app.yml"

$COMPOSE down -v --remove-orphans

./scripts/build-images.sh

# --wait blocks on the infra containers' own healthchecks (postgres, redpanda, redis, lgtm).
# The eight app containers have no in-container healthcheck to wait on - their Paketo run
# image ships no shell, so nothing in it can exec a check - so --wait only confirms they
# started; the loop below polls actuator/health from the host, same as start-services.sh.
$COMPOSE up -d --wait

echo "waiting for all eight service containers to answer healthy..."
declare -A PORTS=(
  [account]=8080 [ledger]=8081 [notification]=8082 [balance]=8083
  [risk]=8084 [payment]=8085 [issuer]=8086 [settlement]=8087
)
deadline=$((SECONDS + 120))
for svc in "${!PORTS[@]}"; do
  port=${PORTS[$svc]}
  until curl -sf "localhost:$port/actuator/health" > /dev/null 2>&1; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "$svc (port $port) never answered healthy - last 20 lines: docker compose logs $svc"
      $COMPOSE logs --tail 20 "$svc"
      exit 1
    fi
    sleep 2
  done
  echo "$svc is up ($port)"
done

./scripts/topics.sh
./scripts/check-schemas.sh --register

# the retry and dlt topics only exist once a consumer has started, so their policy lands after the services do
./scripts/topics.sh

curl -fsS localhost:8087/actuator/health | jq -e '.status == "UP"'
curl -fsS localhost:8087/api/v1/batch/jobs | jq -e 'length >= 3'

./scripts/scenario-authorize-capture.sh
./scripts/scenario-insufficient-funds.sh
./scripts/scenario-fraud-flagged.sh

echo
echo "the whole system is up, from nothing, with one script"
