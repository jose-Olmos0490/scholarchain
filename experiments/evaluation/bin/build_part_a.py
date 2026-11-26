#!/usr/bin/env python3
"""Regenerates every Part A table and plot from raw data. No network, no AWS.

    experiments/evaluation/regenerate.sh <run-id>

Verifies the run's SHA256SUMS first and refuses changed, missing or extra raw files. Writes only
under experiments/evaluation/derived/<run-id>/ (or --out). Also reruns the Phase 15 plot builder on
the Experiment C raw directory into its own derived directory.
"""

import argparse
import os
import subprocess
import sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)

import part_a_er  # noqa: E402
import part_a_graph  # noqa: E402
import part_a_other  # noqa: E402
from part_a_common import sha256_file  # noqa: E402


def verify(raw):
    with open(os.path.join(raw, "SHA256SUMS")) as handle:
        expected = dict(reversed(line.split("  ", 1)) for line in handle.read().splitlines() if line)
    actual = {}
    for dirpath, _, files in os.walk(raw):
        for name in files:
            path = os.path.join(dirpath, name)
            relative = os.path.relpath(path, raw)
            if relative != "SHA256SUMS":
                actual[relative] = sha256_file(path)
    if actual != expected:
        changed = sorted(k for k in set(actual) | set(expected) if actual.get(k) != expected.get(k))
        sys.exit("raw data differs from SHA256SUMS: " + ", ".join(changed[:10]))
    return len(actual)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--out")
    parser.add_argument("--raw-root", default=os.path.join(REPO, "experiments", "evaluation", "raw"))
    parser.add_argument("--skip-experiment-c-plots", action="store_true")
    args = parser.parse_args()
    raw = os.path.join(os.path.abspath(args.raw_root), args.run_id)
    out = os.path.abspath(args.out) if args.out else os.path.join(REPO, "experiments", "evaluation", "derived", args.run_id)
    print("verified %d raw files" % verify(raw))
    part_a_er.build(raw, out)
    part_a_graph.build(raw, out, REPO)
    part_a_other.tests(raw, out)
    part_a_other.perf(out, REPO)
    part_a_other.factory(raw, out)
    part_a_other.repro(raw, out)
    if not args.skip_experiment_c_plots:
        subprocess.run([sys.executable, "-B", os.path.join(REPO, "experiments", "bin", "build_plots.py"),
                        "--run", os.path.join(REPO, part_a_other.C_RUN)], check=True)
    print("derived outputs: " + os.path.relpath(out, REPO))


if __name__ == "__main__":
    main()
