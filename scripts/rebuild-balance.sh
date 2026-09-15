#!/usr/bin/env bash
# Throw the balance read model away and build it again from the log. Prints how long that took: the
# number says how big a projection this is still a sane way to run. Past a minute, step 20's snapshot topic.
#   scripts/rebuild-balance.sh
set -euo pipefail
cd "$(dirname "$0")/.."
group=balance-service
describe() { docker exec ledgerflow-redpanda rpk group describe $group 2>/dev/null; }
started=$(date +%s)

# a clean stop leaves the group at once; a killed JVM keeps its partitions for session.timeout.ms (45s)
echo "stopping $group (it runs on the host until step 16)..."
curl -sf -X POST localhost:8083/actuator/shutdown > /dev/null || true
until describe | grep -q '^STATE *Empty'; do sleep 1; done

echo "dropping the read model..."
docker exec ledgerflow-redis redis-cli FLUSHDB > /dev/null

echo "rewinding the group to the first record..."   # only possible while nobody is in the group: hence the stop
docker exec ledgerflow-redpanda rpk group seek $group --to start > /dev/null

echo "starting it again..."
(./mvnw -q -pl services/balance-service spring-boot:run > /tmp/balance-service.log 2>&1 &)
until describe | grep -q '^STATE *Stable' && describe | grep -q '^TOTAL-LAG *0$'; do sleep 1; done

echo "rebuilt in $(( $(date +%s) - started ))s: $(describe | awk '/^ledgerflow/ {n += $5 - $4} END {print n+0}') records over $(describe | grep -c '^ledgerflow') partitions"
