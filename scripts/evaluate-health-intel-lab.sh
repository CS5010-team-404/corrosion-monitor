#!/usr/bin/env bash
# Replays the Intel Berkeley Research Lab readings through the sensor-health rules (docs/SENSOR_HEALTH.md,
# "Real-data check") and writes docs/results/intel-lab-*.{md,csv}. Deterministic; takes a few minutes.
#   scripts/evaluate-health-intel-lab.sh [data.txt.gz] [mote_locs.txt]   defaults: data/intel/...
# Data (not committed): http://db.csail.mit.edu/labdata/data.txt.gz and mote_locs.txt, saved in data/intel/.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DATA="$(realpath "${1:-$ROOT/data/intel/data.txt.gz}")"
LOCS="$(realpath "${2:-$ROOT/data/intel/mote_locs.txt}")"
for f in "$DATA" "$LOCS"; do [[ -f "$f" ]] || { echo "not found: $f (see the download note above)" >&2; exit 1; }; done
OUT="${EVAL_OUT:-$ROOT/docs/results}"
cd "$ROOT/platform"
mvn -q -B install -DskipTests -Djacoco.skip=true -pl ecp-health -am
MAVEN_OPTS="${MAVEN_OPTS:-} -Xmx6g" mvn -q -B -pl ecp-health exec:java \
    -Dexec.args="--data $DATA --locs $LOCS --out $OUT"
echo "Results written to $OUT"
