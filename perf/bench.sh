#!/usr/bin/env bash
# usage: perf/bench.sh <label>   e.g. perf/bench.sh step15   (all seven services up)
# The fixed suite: same three scenarios at the same load, at the end of every step. The label
# names the checkpoint; history.sh charts every label that starts with "step", so the trend
# across steps is the suite and nothing else. One-off experiments go through run.sh with their own label.
set -euo pipefail
cd "$(dirname "$0")/.."
LABEL="${1:?label, e.g. step15}"
RATE=100 DURATION=60s  perf/run.sh transfer "$LABEL"
RATE=50  DURATION=30s  perf/run.sh holds    "$LABEL"
RATE=100 DURATION=180s perf/run.sh payments "$LABEL"
perf/history.sh
