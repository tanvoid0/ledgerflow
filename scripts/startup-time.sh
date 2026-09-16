#!/usr/bin/env bash
# usage: scripts/startup-time.sh <service> [runs]   - seconds-to-Started, plain start vs AOT-cached start, appended to docs/perf/startup-<service>.txt
set -euo pipefail
cd "$(dirname "$0")/.."
SVC="${1:?service, e.g. account}"; RUNS="${2:-3}"
JAR=$(ls services/${SVC}-service/target/${SVC}-service-*.jar | head -1)
WORK=$(mktemp -d)
java -Djarmode=tools -jar "$JAR" extract --destination "$WORK/app" > /dev/null
APP=$(ls "$WORK"/app/*.jar | head -1)
started() { grep -o 'Started .* in [0-9.]* seconds' | grep -o '[0-9.]* seconds' | cut -d' ' -f1; }

# Prefer the one-step -XX:AOTCacheOutput training run; fall back to the two-step
# record/create form if this JDK build rejects it.
AOT_MODE="two-step"
if ( cd "$WORK/app" && java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -jar "$APP" > /dev/null 2>&1 ); then
  AOT_MODE="one-step"
else
  echo "one-step AOTCacheOutput rejected by this JDK build, using two-step record/create" >&2
  ( cd "$WORK/app" && java -XX:AOTMode=record -XX:AOTConfiguration=app.aotconf -Dspring.context.exit=onRefresh -jar "$APP" > /dev/null 2>&1 )
  ( cd "$WORK/app" && java -XX:AOTMode=create -XX:AOTConfiguration=app.aotconf -XX:AOTCache=app.aot > /dev/null 2>&1 )
fi

{
  echo "# ${SVC}-service startup, $(date -u +%FT%TZ), AOT mode: ${AOT_MODE}"
  for i in $(seq "$RUNS"); do
    plain=$( cd "$WORK/app" && timeout 90 java -Dserver.port=0 -jar "$APP" 2>&1 | started | head -1 || true )
    cached=$( cd "$WORK/app" && timeout 90 java -XX:AOTCache=app.aot -Dserver.port=0 -jar "$APP" 2>&1 | started | head -1 || true )
    echo "run $i: plain ${plain}s  aot-cache ${cached}s"
  done
} | tee -a docs/perf/startup-${SVC}.txt

rm -rf "$WORK"
