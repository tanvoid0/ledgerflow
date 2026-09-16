#!/usr/bin/env bash
# Topic creation and retention policy, pulled out of demo.sh so cluster3.sh can reuse it: swapping
# the redpanda overlay in or out recreates the cluster from nothing (no volume), so every topic has
# to be made again. Safe to call twice - creates are idempotent, alter-config just reapplies.
#   scripts/topics.sh                     RF=1, no min.insync.replicas (the daily single-node default)
#   RF=3 MIN_ISR=2 scripts/topics.sh      three-node cluster (scripts/cluster3.sh up)
#   RPK="kubectl exec deploy/redpanda -- rpk" scripts/topics.sh    the kind cluster (scripts/k8s-up.sh)
set -euo pipefail
cd "$(dirname "$0")/.."

RF=${RF:-1}
RPK=${RPK:-docker exec ledgerflow-redpanda rpk}
isr=()
[ -n "${MIN_ISR:-}" ] && isr=(-c "min.insync.replicas=$MIN_ISR")   # Redpanda accepts this per-topic and ignores it (broker-level only); set here for the reader who expects it

# retention.ms by kind - see docs/events/README.md#how-long-a-topic-remembers for the why
policy() {
  local topic="$1" retention_ms="$2"
  $RPK topic create "$topic" -p 3 -r "$RF" "${isr[@]}" -c "retention.ms=$retention_ms" > /dev/null 2>&1 || true   # exists already: fine
  $RPK topic alter-config "$topic" --set "retention.ms=$retention_ms" > /dev/null   # brings an existing volume's topic to the same policy
}

for t in ledger.wallet-hold.events ledger.hold-rejected.events ledger.hold-closed.events account.entry.events \
         issuer.authorization.events settlement.capture.events settlement.merchant.events payment.requested.events; do
  policy "ledgerflow.$t.v1" 2592000000   # 30d: the outbox is the archive, the topic is the replay window
done
for t in ledger.hold.commands issuer.authorization.commands settlement.capture.commands; do
  policy "ledgerflow.$t.v1" 86400000   # 1d: a command older than the saga's 15s deadline is already Failed
done
$RPK topic create ledgerflow.balance.snapshots.v1 -p 3 -r "$RF" "${isr[@]}" \
  -c cleanup.policy=compact -c segment.ms=10000 > /dev/null 2>&1 || true   # segment.ms=10000 makes the cleaner visible in dev; never in production
# Redpanda silently clamps a topic's segment.ms to this cluster floor (10 minutes by default); dev only, like the segment.ms above
$RPK cluster config set log_segment_ms_min 1000 > /dev/null

# the retry and dlt topics only exist once a consumer has started - a no-op the first time this runs
existing_topics=$($RPK topic list | awk 'NR>1{print $1}')
for t in $(echo "$existing_topics" | grep -E '\.retry-[0-9]+$' || true); do
  policy "$t" 86400000   # 1d, same as the commands/events they retry
done
for t in $(echo "$existing_topics" | grep -E '\.dlt$' || true); do
  policy "$t" 2592000000   # 30d: someone has to look
done
