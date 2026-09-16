#!/usr/bin/env bash
# Gives the host ports back: kind delete cluster is what frees 8080-8087, 9092, 18081 and 3000
# for the compose infra (or anything else) to bind again.
set -euo pipefail
kind delete cluster --name ledgerflow
