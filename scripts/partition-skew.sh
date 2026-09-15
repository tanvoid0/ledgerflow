#!/usr/bin/env bash
# usage: scripts/partition-skew.sh <topic>   e.g. scripts/partition-skew.sh ledgerflow.ledger.wallet-hold.events.v1
# Per-partition high-water marks for one topic. Skew here is a keying problem, not a consumer-
# count problem: adding consumers spreads work across partitions that already exist, it does
# nothing for a key that only ever hashes to one or two of them.
set -euo pipefail
TOPIC="${1:?usage: scripts/partition-skew.sh <topic>}"
docker exec ledgerflow-redpanda rpk topic describe "$TOPIC" -p
