#!/usr/bin/env bash
# The contract gate. Every libs/ledgerflow-events/.../schemas/<subject>.json is checked against the
# registry's latest version of that subject; one incompatible answer fails the run.
#   scripts/check-schemas.sh              check
#   scripts/check-schemas.sh --register   check, then register what passed
set -euo pipefail
shopt -s nullglob
REGISTRY=${REGISTRY:-http://localhost:18081}
DIR=${SCHEMA_DIR:-libs/ledgerflow-events/src/main/resources/schemas}
CT='content-type: application/vnd.schemaregistry.v1+json'
fail=0

for f in "$DIR"/*.json; do
  subject=$(basename "$f" .json)
  body=$(jq -n --rawfile s "$f" '{schema: $s, schemaType: "JSON"}')

  # BACKWARD: new consumers must read old messages. Add optional fields; never remove, rename or retype.
  curl -sf -X PUT "$REGISTRY/config/$subject" -H "$CT" -d '{"compatibility":"BACKWARD"}' > /dev/null

  if curl -sf "$REGISTRY/subjects/$subject/versions/latest" > /dev/null; then
    verdict=$(curl -sf -X POST "$REGISTRY/compatibility/subjects/$subject/versions/latest?verbose=true" -H "$CT" -d "$body")
    if [ "$(jq -r .is_compatible <<< "$verdict")" != true ]; then
      echo "INCOMPATIBLE  $subject"; jq -r '.messages[]? | "              " + .' <<< "$verdict"; fail=1; continue
    fi
    echo "compatible    $subject"
  else
    echo "new subject   $subject"
  fi

  if [ "${1:-}" = --register ]; then
    id=$(curl -sf -X POST "$REGISTRY/subjects/$subject/versions" -H "$CT" -d "$body" | jq -r .id)
    echo "registered    $subject (schema id $id)"
  fi
done
exit $fail
