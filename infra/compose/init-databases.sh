#!/bin/bash
# Runs once, the first time the volume is created.
# account already exists (POSTGRES_DB); these are for later steps.
set -e
for db in ledger notification payment issuer settlement balance; do
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -c "CREATE DATABASE \"$db\";"
done
