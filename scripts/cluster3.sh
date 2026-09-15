#!/usr/bin/env bash
# Swaps the single-node Redpanda for a three-node cluster and back. No named volume is declared
# for redpanda in compose, but the image itself declares one (VOLUME /var/lib/redpanda/data) and
# compose quietly carries that anonymous volume over on a recreate - --renew-anon-volumes below is
# what actually gets a fresh cluster; without it the "new" cluster boots with the old single-node
# data dir and every topic answers "exists already", staying at RF=1. Either direction recreates
# the cluster from nothing - topics, groups and schemas are gone until this script's last two
# lines rebuild them. Services are the caller's business: stop them before calling this, start
# them after.
#   scripts/cluster3.sh up     # one node -> three, RF=3, min.insync.replicas=2
#   scripts/cluster3.sh down   # three nodes -> one, RF=1
set -euo pipefail
cd "$(dirname "$0")/.."

BASE=infra/compose/docker-compose.yml
OVERLAY=infra/compose/docker-compose.redpanda3.yml

case "${1:-}" in
  up)
    # --renew-anon-volumes scoped to the redpanda services only - postgres has its own named
    # volume and redis/lgtm are stateless by design, none of them need touching here.
    docker compose -f "$BASE" -f "$OVERLAY" up -d --wait --renew-anon-volumes redpanda redpanda-2 redpanda-3
    # the retry/dlt topics consumers create ask for the broker's default replication: make it 3 here
    docker exec ledgerflow-redpanda rpk cluster config set default_topic_replications 3
    RF=3 MIN_ISR=2 ./scripts/topics.sh
    ./scripts/check-schemas.sh --register
    echo "up: three nodes, RF=3."
    ;;
  down)
    docker compose -f "$BASE" -f "$OVERLAY" down redpanda-2 redpanda-3
    docker compose -f "$BASE" up -d --wait --renew-anon-volumes redpanda   # config differs from the overlay's - up recreates it in single-node form
    ./scripts/topics.sh
    ./scripts/check-schemas.sh --register
    echo "down: single node, RF=1."
    ;;
  *)
    echo "usage: scripts/cluster3.sh up|down"; exit 1 ;;
esac
