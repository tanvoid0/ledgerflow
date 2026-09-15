#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

docker info > /dev/null 2>&1 || { echo "docker is not running"; exit 1; }
javaver=$(java -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"')
[ "${javaver:-0}" -ge 25 ] || { echo "need java >= 25, got: $(java -version 2>&1 | head -1)"; exit 1; }
[ -x ./mvnw ] || { echo "./mvnw not found or not executable"; exit 1; }
for p in 8080 8081 8082 8083 8084 8085 8086 8087; do
  curl -sf "localhost:$p/actuator/health" > /dev/null 2>&1 && { echo "port $p already answers - stop whatever is running first"; exit 1; }
done

docker compose -f infra/compose/docker-compose.yml up -d --wait

# a fresh volume's init-databases.sh creates the risk role; a volume older than step 16 needs it applied by hand
docker exec ledgerflow-postgres psql -U risk -d risk -c 'select 1' > /dev/null 2>&1 || {
  echo "risk role missing (a volume older than step 16) - applying infra/compose/risk-role.sql"
  docker exec -i ledgerflow-postgres psql -U ledgerflow -d postgres < infra/compose/risk-role.sql
}

./scripts/topics.sh
./scripts/check-schemas.sh --register

./mvnw -q -T1C package -DskipTests
./scripts/start-services.sh

# the retry and dlt topics only exist once a consumer has started, so their policy lands after the services do
./scripts/topics.sh

./scripts/scenario-authorize-capture.sh
./scripts/scenario-insufficient-funds.sh
./scripts/scenario-fraud-flagged.sh

echo
echo "the eight services are still up. logs: .local/logs/<service>.log. scripts/stop-services.sh when done."
