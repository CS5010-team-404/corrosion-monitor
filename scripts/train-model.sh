#!/usr/bin/env bash
# Retrains the condensation forecaster on simulated data and writes the bundled model + report.
#   scripts/train-model.sh
# The real-data evaluation (UCI Appliances Energy, doi:10.24432/C5VC8G) is separate: scripts/evaluate-real-data.sh.
set -euo pipefail
cd "$(dirname "$0")/../platform"
mvn -q -B install -DskipTests -pl ecp-forecast -am
mvn -q -B -pl ecp-forecast exec:java -Dexec.args="--out ecp-forecast/src/main/resources/models"
echo "Model and report written to platform/ecp-forecast/src/main/resources/models/"
