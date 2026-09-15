#!/bin/bash
# Runs once, the first time the volume is created.
# `account` already exists (POSTGRES_DB); the rest are for later services.
# One database per service: services never read each other's tables.
set -e
for db in ledger notification payment issuer settlement balance risk; do
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "CREATE DATABASE \"$db\";"
done

# risk-role.sql, inlined: compose mounts this script alone, not a directory, so the file
# is not on the container's disk to `-f`. infra/compose/risk-role.sql stays the copy the IT
# and a live-instance operator run by hand; this is the one that runs on a fresh volume.
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "CREATE ROLE risk LOGIN PASSWORD 'risk';"
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "ALTER DATABASE risk OWNER TO risk;"
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c \
  "REVOKE CONNECT ON DATABASE account, ledger, notification, payment, issuer, settlement, balance FROM PUBLIC;"
