#!/usr/bin/env bash
# Put everything in a dead letter topic back on its source topic, same keys, and let the consumer try again.
# Fix the cause first: a poison pill replayed is a poison pill dead-lettered twice.
#   scripts/dlt-replay.sh ledgerflow.ledger.wallet-hold.events.v1.notification-service.dlt
set -euo pipefail
dlt=$1
source=${dlt%.*.dlt}   # <topic>.<group>.dlt -> <topic>
docker exec ledgerflow-redpanda rpk topic consume "$dlt" -o :end -f '%k\t%v\n' \
  | docker exec -i ledgerflow-redpanda rpk topic produce "$source" -f '%k\t%v\n'
echo "replayed $dlt -> $source"
