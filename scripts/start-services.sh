#!/usr/bin/env bash
# Starts the eight service jars in the background: stdout+stderr to .local/logs/<x>-service.log,
# each pid appended to .local/pids. Refuses outright if a port already answers - a leftover JVM
# from an earlier run would otherwise get a second one stacked on top of it.
#   scripts/start-services.sh
set -euo pipefail
cd "$(dirname "$0")/.."

SERVICES=(account ledger notification balance risk payment issuer settlement)
PORTS=(8080 8081 8082 8083 8084 8085 8086 8087)

up() { curl -sf "localhost:$1/actuator/health" > /dev/null 2>&1; }

for i in "${!SERVICES[@]}"; do
  if up "${PORTS[$i]}"; then
    echo "port ${PORTS[$i]} (${SERVICES[$i]}-service) already answers - stop it first (scripts/stop-services.sh)"; exit 1
  fi
done

mkdir -p .local/logs
: > .local/pids

for i in "${!SERVICES[@]}"; do
  svc=${SERVICES[$i]}
  jar="services/$svc-service/target/$svc-service-1.0.0-SNAPSHOT.jar"
  [ -f "$jar" ] || { echo "missing $jar - run ./mvnw -q -T1C package -DskipTests first"; exit 1; }
  java -jar "$jar" > ".local/logs/$svc-service.log" 2>&1 &
  echo $! >> .local/pids
  echo "started $svc-service (pid $!)"
done

echo "waiting for all eight to answer healthy (90s budget)..."
deadline=$((SECONDS + 90))
for i in "${!SERVICES[@]}"; do
  svc=${SERVICES[$i]}; port=${PORTS[$i]}
  until up "$port"; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "$svc-service never answered healthy on $port - last 20 lines of .local/logs/$svc-service.log:"
      tail -20 ".local/logs/$svc-service.log"
      exit 1
    fi
    sleep 2
  done
  echo "$svc-service is up ($port)"
done
echo "all eight services are up."
