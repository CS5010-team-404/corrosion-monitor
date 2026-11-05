#!/usr/bin/env bash
# Retrains the condensation forecaster and writes the bundled model + report.
#   scripts/train-model.sh                         simulated data only
#   scripts/train-model.sh energydata_complete.csv also checks on the UCI dataset (doi:10.24432/C5VC8G)
set -euo pipefail
cd "$(dirname "$0")/../platform"
ARGS="--out ecp-forecast/src/main/resources/models"
[[ $# -ge 1 ]] && ARGS="$ARGS --uci $(realpath "$1")"
mvn -q -B install -DskipTests -pl ecp-forecast -am
mvn -q -B -pl ecp-forecast exec:java -Dexec.args="$ARGS"
echo "Model and report written to platform/ecp-forecast/src/main/resources/models/"
