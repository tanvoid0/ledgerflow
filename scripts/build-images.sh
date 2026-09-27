#!/usr/bin/env bash
# Builds container images for the nine deployable services with Buildpacks (spring-boot:build-image),
# plus the web app's plain docker build (Buildpacks builds jars, not an nginx-served SPA).
# build-image is a direct CLI goal invocation, not phase-bound, so Maven runs it on every reactor
# project it is handed - including a lib module with no main class if -am pulls one in. Fix: install
# the libs into ~/.m2 first (so the services resolve them normally), then run build-image with -pl
# only, no -am, restricted to the eight service modules.
#   scripts/build-images.sh                 # build all eight
#   scripts/build-images.sh -o /dev/null     # extra args pass straight through to mvnw
set -euo pipefail
cd "$(dirname "$0")/.."

./mvnw -q -T1C install -DskipTests

./mvnw -q -pl services/account-service,services/ledger-service,services/notification-service,services/balance-service,services/risk-service,services/payment-service,services/issuer-service,services/settlement-service,services/gateway-service spring-boot:build-image -DskipTests "$@"

docker build -t ledgerflow/web:1.0.0-SNAPSHOT web

docker image ls 'ledgerflow/*'
