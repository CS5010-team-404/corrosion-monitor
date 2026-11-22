#!/usr/bin/env python3
"""Prints line and branch coverage from JaCoCo CSV reports, per package group and in total.

    python3 scripts/coverage-summary.py backend/target/site/jacoco/jacoco.csv
    python3 scripts/coverage-summary.py platform/ecp-platform-app/target/site/jacoco-aggregate/jacoco.csv

With several files, each gets its own block. Used by CI so the totals appear in the build log.
"""
import csv
import sys
from collections import defaultdict


def pct(covered, missed):
    total = covered + missed
    return f"{100 * covered / total:5.1f}% ({covered}/{total})" if total else "  n/a"


def summarise(path):
    rows = list(csv.DictReader(open(path, newline="")))
    groups = defaultdict(lambda: [0, 0, 0, 0])
    for r in rows:
        # Group by report (GROUP column, e.g. the Maven module) and the first two package levels below the root.
        parts = r["PACKAGE"].split(".")
        key = f'{r["GROUP"]}: {".".join(parts[:5])}'
        g = groups[key]
        g[0] += int(r["LINE_COVERED"]); g[1] += int(r["LINE_MISSED"])
        g[2] += int(r["BRANCH_COVERED"]); g[3] += int(r["BRANCH_MISSED"])
    print(f"Coverage: {path}")
    print(f'  {"package":<60} {"lines":>22} {"branches":>22}')
    for key in sorted(groups):
        g = groups[key]
        print(f"  {key:<60} {pct(g[0], g[1]):>22} {pct(g[2], g[3]):>22}")
    t = [sum(g[i] for g in groups.values()) for i in range(4)]
    print(f'  {"TOTAL":<60} {pct(t[0], t[1]):>22} {pct(t[2], t[3]):>22}')
    print()


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    for p in sys.argv[1:]:
        summarise(p)
