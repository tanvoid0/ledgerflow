#!/usr/bin/env bash
# One command, an empty machine, a running cluster - the kind equivalent of scripts/demo-compose.sh.
# extraPortMappings in k8s/kind-cluster.yaml claim the same host ports the compose infra uses
# (5433 is the exception - postgres has no host port in the cluster), so the two cannot run at
# once: refuse rather than land in a half-working state with one of them holding the port.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -n "$(docker compose -f infra/compose/docker-compose.yml ps -q 2>/dev/null)" ]; then
  echo "compose infra is up - stop it first: docker compose -f infra/compose/docker-compose.yml stop" >&2
  exit 1
fi

kind get clusters | grep -qx ledgerflow || kind create cluster --name ledgerflow --config k8s/kind-cluster.yaml
kubectl create namespace ledgerflow --dry-run=client -o yaml | kubectl apply -f -
kubectl config set-context --current --namespace=ledgerflow

./scripts/build-images.sh
for s in account ledger notification payment issuer settlement balance risk; do
  kind load docker-image --name ledgerflow "ledgerflow/${s}-service:1.0.0-SNAPSHOT"
done

kubectl create secret generic ledgerflow-db --from-literal=password=ledgerflow \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl create configmap init-databases --from-file=infra/compose/init-databases.sh \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl apply -k k8s/
kubectl apply -f k8s/jobs/
kubectl wait --for=condition=Available deploy --all --timeout=600s

# the retry/dlt topics only exist once a consumer has started, so a second pass lands their
# policy after the services do - same two-pass shape as demo-compose.sh
RPK="kubectl exec deploy/redpanda -- rpk" ./scripts/topics.sh
./scripts/check-schemas.sh --register
RPK="kubectl exec deploy/redpanda -- rpk" ./scripts/topics.sh

echo "cluster up"
