#!/usr/bin/env bash
# Real-data evaluation of the condensation forecaster (protocol: docs/ML_FORECAST.md).
#   scripts/evaluate-real-data.sh [path/to/energydata_complete.csv]   default: data/energydata_complete.csv
# Writes docs/results/real-data-results.md, real-data-metrics.csv and chosen-hyperparameters.csv (small, committed)
# and the candidate model files to data/candidates/ (large, git-ignored). Deterministic: re-running gives the same
# numbers (only the measured inference times vary). Takes about 30 minutes on an 8-core laptop.
# Dataset: Candanedo, Feldheim & Deramaix (2017), UCI ML Repository, doi:10.24432/C5VC8G, CC BY 4.0.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CSV="$(realpath "${1:-$ROOT/data/energydata_complete.csv}")"
[[ -f "$CSV" ]] || { echo "dataset not found: $CSV (unzip energydata_complete.csv into data/)" >&2; exit 1; }
OUT="${EVAL_OUT:-$ROOT/docs/results}"   # EVAL_OUT overrides (e.g. a scratch dir)
MODELS="${EVAL_MODELS:-$ROOT/data/candidates}"
cd "$ROOT/platform"
mvn -q -B install -DskipTests -Djacoco.skip=true -pl ecp-forecast -am
mvn -q -B -pl ecp-forecast exec:java -Dexec.mainClass=edu.neu.ecp.forecast.eval.RealDataEvaluation \
    -Dexec.args="--csv $CSV --out $OUT --models $MODELS ${EVAL_ARGS:-}"
echo "Results written to $OUT"
