#!/usr/bin/env bash
# Throw the balance read model away and build it again. Default path: consume step 20's compacted
# snapshot topic once, seed Redis from the highest version per wallet, then seek the group past only
# what those snapshots prove was applied - the source topics themselves are never replayed. --full
# falls back to step 13's original path, a full replay from the start, for when the snapshot topic
# itself is what needs rebuilding, and for the first rebuild after the snapshot topic appeared: that
# replay publishes a snapshot for every wallet, and from then on the topic covers the whole history.
# Prints how long that took and how much it replayed: the number says how big a rebuild this still is.
#   scripts/rebuild-balance.sh
#   scripts/rebuild-balance.sh --full
set -euo pipefail
cd "$(dirname "$0")/.."
group=balance-service
jar=services/balance-service/target/balance-service-1.0.0-SNAPSHOT.jar
describe() { docker exec ledgerflow-redpanda rpk group describe $group 2>/dev/null; }
started=$(date +%s)

[ -f "$jar" ] || { echo "missing $jar - run ./mvnw -q -T1C install -DskipTests first"; exit 1; }

# a clean stop leaves the group at once; a killed JVM keeps its partitions for session.timeout.ms (30s, static membership)
echo "stopping $group..."
TOKEN=${TOKEN:-$(scripts/token.sh ops)}
curl -sf -X POST -H "Authorization: Bearer $TOKEN" localhost:8083/actuator/shutdown > /dev/null || true
until describe | grep -q '^STATE *Empty'; do sleep 1; done

echo "dropping the read model..."
docker exec ledgerflow-redis redis-cli FLUSHDB > /dev/null

if [ "${1:-}" = --full ]; then
  echo "rewinding $group to the first record..."   # only possible while nobody is in the group: hence the stop
  docker exec ledgerflow-redpanda rpk group seek $group --to start > /dev/null
else
  echo "reading ledgerflow.balance.snapshots.v1 (start to end)..."
  snapshots=$(docker exec ledgerflow-redpanda rpk topic consume ledgerflow.balance.snapshots.v1 -o :end -f '%v\n' 2>/dev/null \
    | jq -s 'group_by(.accountId + ":" + .label) | map(max_by(.version))')
  kept=$(jq length <<< "$snapshots")

  echo "seeding redis from $kept kept snapshots..."
  jq -r '.[] | "HSET account:\(.accountId) \(.label):balance \(.balanceMinor) \(.label):held \(.heldMinor) \(.label):currency \(.currency) \(.label):version \(.version)"' <<< "$snapshots" \
    | docker exec -i ledgerflow-redis redis-cli --pipe > /dev/null

  echo "seeking $group past every kept snapshot's source offset..."
  seekfile=$(mktemp)
  trap 'rm -f "$seekfile"' EXIT
  jq -r 'group_by(.source.topic + ":" + (.source.partition|tostring)) | map(max_by(.source.offset))
         | .[] | "\(.source.topic) \(.source.partition) \(.source.offset + 1)"' <<< "$snapshots" > "$seekfile"
  docker exec -i ledgerflow-redpanda rpk group seek $group --to-file /dev/stdin < "$seekfile" > /dev/null
fi

echo "starting $group..."
mkdir -p .local/logs
java -jar "$jar" > .local/logs/balance-service.log 2>&1 &
until describe | grep -q '^STATE *Stable' && describe | grep -q '^TOTAL-LAG *0$'; do sleep 1; done

elapsed=$(( $(date +%s) - started ))
if [ "${1:-}" = --full ]; then
  echo "rebuilt in ${elapsed}s: $(describe | awk '/^ledgerflow/ {n += $5 - $4} END {print n+0}') records over $(describe | grep -c '^ledgerflow') partitions"
else
  resumed=$(awk '{print $1":"$2"@"$3}' "$seekfile" | paste -sd', ' -)
  echo "rebuilt in ${elapsed}s from $kept snapshots (resumed at $resumed)"
fi
