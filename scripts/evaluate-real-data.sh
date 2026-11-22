#!/usr/bin/env bash
# Real-data evaluation of the condensation forecaster (protocol: docs/ML_FORECAST.md).
#   scripts/evaluate-real-data.sh [path/to/energydata_complete.csv]   default: data/energydata_complete.csv
# Writes docs/results/real-data-results.md, real-data-metrics.csv, chosen-hyperparameters.csv and the candidate
# model files. Deterministic: re-running gives the same numbers. Takes several minutes.
# Dataset: Candanedo, Feldheim & Deramaix (2017), UCI ML Repository, doi:10.24432/C5VC8G, CC BY 4.0.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CSV="$(realpath "${1:-$ROOT/data/energydata_complete.csv}")"
[[ -f "$CSV" ]] || { echo "dataset not found: $CSV (unzip energydata_complete.csv into data/)" >&2; exit 1; }
OUT="$ROOT/docs/results"
cd "$ROOT/platform"
mvn -q -B install -DskipTests -Djacoco.skip=true -pl ecp-forecast -am
mvn -q -B -pl ecp-forecast exec:java -Dexec.mainClass=edu.neu.ecp.forecast.eval.RealDataEvaluation \
    -Dexec.args="--csv $CSV --out $OUT ${EVAL_ARGS:-}"
echo "Results written to docs/results/"
