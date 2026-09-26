#!/usr/bin/env bash
# Mints an access token from Keycloak: a username (password grant, via the public ledgerflow-cli
# client) or a confidential client id (client_credentials, its own service account). Tokens live
# 300s (Keycloak's default) - callers mint one per command, not once and cache it.
#   scripts/token.sh ops                  # -> ledger-write + account-read
#   scripts/token.sh reader               # -> account-read
#   scripts/token.sh settlement-service   # -> account-read + ledger-write, as itself
set -euo pipefail
cd "$(dirname "$0")/.."
WHO="${1:?user (ops, reader) or client (ledger-service, settlement-service)}"
REALM_URL="${OIDC_REALM_URL:-http://localhost:8180/realms/ledgerflow}"
TOKEN_URL="$REALM_URL/protocol/openid-connect/token"

case "$WHO" in
  ledger-service|settlement-service)
    secret="${OIDC_CLIENT_SECRET:-}"
    if [ -z "$secret" ] && [ -f infra/compose/.env ]; then
      var="$(tr '[:lower:]-' '[:upper:]_' <<< "$WHO")_SECRET"
      secret=$(sed -n "s/^${var}=//p" infra/compose/.env | tail -1)
    fi
    [ -n "$secret" ] || { echo "no secret for $WHO - set OIDC_CLIENT_SECRET or infra/compose/.env" >&2; exit 1; }
    resp=$(curl -sf -d grant_type=client_credentials -d "client_id=$WHO" -d "client_secret=$secret" "$TOKEN_URL")
    ;;
  ops|reader)
    resp=$(curl -sf -d grant_type=password -d client_id=ledgerflow-cli -d "username=$WHO" -d "password=$WHO" "$TOKEN_URL")
    ;;
  *)
    echo "unknown $WHO - expected ops, reader, ledger-service or settlement-service" >&2; exit 1;;
esac
jq -r .access_token <<< "$resp"
