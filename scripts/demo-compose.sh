#!/usr/bin/env bash
# One script, a clean machine, the whole system in containers - the compose equivalent of
# scripts/demo.sh (which runs the eight services as host JVMs, and is what the perf numbers
# in docs/measurements are taken on).
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE="docker compose -f infra/compose/docker-compose.yml -f infra/compose/docker-compose.app.yml"

$COMPOSE down -v --remove-orphans

./scripts/build-images.sh

# --wait blocks until every healthcheck passes - the infra ones and the eight services' own
# (bash + /dev/tcp against actuator/health, see docker-compose.app.yml) - so there is no sleep-and-hope here.
$COMPOSE up -d --wait

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
