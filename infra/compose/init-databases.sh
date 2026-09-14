#!/bin/bash
# Runs once, the first time the volume is created.
# `account` already exists (POSTGRES_DB); the rest are for later services.
# One database per service: services never read each other's tables.
set -e
for db in ledger notification payment issuer settlement balance risk; do
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "CREATE DATABASE \"$db\";"
done
