#!/usr/bin/env bash
# Kills every pid in .local/pids, waits for its port to go quiet, then drops the file.
#   scripts/stop-services.sh
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f .local/pids ] || { echo "no .local/pids - nothing to stop"; exit 0; }

PORTS=(8080 8081 8082 8083 8084 8085 8086 8087)
up() { curl -sf "localhost:$1/actuator/health" > /dev/null 2>&1; }

case "$(uname -s)" in
  # `$!` on a backgrounded native exe is MSYS's own pid, not the Windows one taskkill needs -
  # `ps`'s WINPID column is the translation; without it taskkill silently misses and the JVM survives.
  MINGW*|MSYS*|CYGWIN*) kill_pid() {
    local winpid=$(ps | awk -v p="$1" '$1==p {print $4}')
    taskkill //F //PID "${winpid:-$1}" > /dev/null 2>&1 || true
  } ;;
  *)                    kill_pid() { kill "$1" 2>/dev/null || true; } ;;
esac

while read -r pid; do kill_pid "$pid"; done < .local/pids

echo "waiting for the ports to go quiet..."
for port in "${PORTS[@]}"; do
  until ! up "$port"; do sleep 1; done
done

rm .local/pids
echo "stopped."
