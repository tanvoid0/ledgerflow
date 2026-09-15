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

# retention.ms by kind - see docs/events/README.md#how-long-a-topic-remembers for the why
policy() {
  local topic="$1" retention_ms="$2"
  docker exec ledgerflow-redpanda rpk topic create "$topic" -p 3 -c "retention.ms=$retention_ms" > /dev/null 2>&1 || true   # exists already: fine
  docker exec ledgerflow-redpanda rpk topic alter-config "$topic" --set "retention.ms=$retention_ms" > /dev/null   # brings an existing volume's topic to the same policy
}

for t in ledger.wallet-hold.events ledger.hold-rejected.events ledger.hold-closed.events account.entry.events \
         issuer.authorization.events settlement.capture.events payment.requested.events; do
  policy "ledgerflow.$t.v1" 2592000000   # 30d: the outbox is the archive, the topic is the replay window
done
for t in ledger.hold.commands issuer.authorization.commands settlement.capture.commands; do
  policy "ledgerflow.$t.v1" 86400000   # 1d: a command older than the saga's 15s deadline is already Failed
done
docker exec ledgerflow-redpanda rpk topic create ledgerflow.balance.snapshots.v1 -p 3 \
  -c cleanup.policy=compact -c segment.ms=10000 > /dev/null 2>&1 || true   # segment.ms=10000 makes the cleaner visible in dev; never in production
# Redpanda silently clamps a topic's segment.ms to this cluster floor (10 minutes by default); dev only, like the segment.ms above
docker exec ledgerflow-redpanda rpk cluster config set log_segment_ms_min 1000 > /dev/null
./scripts/check-schemas.sh --register

./mvnw -q -T1C package -DskipTests
./scripts/start-services.sh

# the retry and dlt topics only exist once a consumer has started, so their policy lands after the services do
existing_topics=$(docker exec ledgerflow-redpanda rpk topic list | awk 'NR>1{print $1}')
for t in $(echo "$existing_topics" | grep -E '\.retry-[0-9]+$' || true); do
  policy "$t" 86400000   # 1d, same as the commands/events they retry
done
for t in $(echo "$existing_topics" | grep -E '\.dlt$' || true); do
  policy "$t" 2592000000   # 30d: someone has to look
done

./scripts/scenario-authorize-capture.sh
./scripts/scenario-insufficient-funds.sh
./scripts/scenario-fraud-flagged.sh

echo
echo "the eight services are still up. logs: .local/logs/<service>.log. scripts/stop-services.sh when done."
