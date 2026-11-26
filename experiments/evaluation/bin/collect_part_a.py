#!/usr/bin/env python3
"""Seals one Part A run: copies a staging directory into experiments/evaluation/raw/<run-id>/, adds
the repository-sourced inputs, writes config.json and SHA256SUMS, and makes the files read-only.

    experiments/evaluation/bin/collect_part_a.py --run-id <id> --stage <dir> --clean-log <file>
        --clone <dir> --started <UTC>

It refuses an existing run directory. The staging directory holds what was produced or fetched by
separately run commands (evaluator output, test reports, read-only AWS responses, S3 objects,
factory extraction); those commands are recorded in config.json from <stage>/commands.json.
"""

import argparse
import datetime
import glob
import gzip
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
RUN_HEX = "f6023de34a312366583b3eacbc2b2a689234cf1abe79e184fb66875bac297a60"
HADOOP_INPUTS = ("spark_author_mention", "spark_author_paper", "spark_work")
SECRET = re.compile(rb"AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|aws_secret_access_key\s*[=:]|[Xx]-[Aa]mz-[Ss]ecurity-[Tt]oken:\s*\S{20}|"
                    rb"BEGIN [A-Z ]*PRIVATE KEY|ghp_[A-Za-z0-9]{30}|github_pat_[A-Za-z0-9_]{30}|"
                    rb"Authorization: (Bearer|AWS4-HMAC-SHA256) [A-Za-z0-9._=/+-]{16}|Signature=[0-9a-f]{64}")


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def git(directory, *args):
    return subprocess.run(["git", "-C", directory] + list(args), check=True, capture_output=True, text=True).stdout.strip()


def dump(path, value):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "x") as handle:
        json.dump(value, handle, indent=1, sort_keys=True)
        handle.write("\n")


def copy(source, destination):
    os.makedirs(os.path.dirname(destination), exist_ok=True)
    shutil.copyfile(source, destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    for name in ("--run-id", "--stage", "--clean-log", "--clone", "--started"):
        parser.add_argument(name, required=True)
    parser.add_argument("--raw-root", default=os.path.join(REPO, "experiments", "evaluation", "raw"))
    args = parser.parse_args()
    destination = os.path.join(os.path.abspath(args.raw_root), args.run_id)
    if os.path.exists(destination):
        sys.exit("run directory exists; raw results are never overwritten")

    # Secret-pattern scan of the human-supplied log before it is copied.
    matches, size, digest = 0, 0, hashlib.sha256()
    with open(args.clean_log, "rb") as handle:
        for line in handle:
            digest.update(line)
            size += len(line)
            matches += bool(SECRET.search(line))
    if matches:
        sys.exit("secret-like content on %d lines of the clean-clone log; nothing sealed" % matches)

    shutil.copytree(args.stage, destination, ignore=shutil.ignore_patterns("*.crc", "__pycache__"))

    base = os.path.join(REPO, "target", "pipeline-local", "phase14")
    graph = os.path.join(base, "spark", "graph")
    for name in ("run-manifest.json", "run-summary.json"):
        copy(os.path.join(graph, "runs", "run_id=" + RUN_HEX, name),
             os.path.join(destination, "graph", "run-metadata", name))
    for target in ("s3", "neptune", "dynamodb", "s3vectors-input"):
        copy(os.path.join(graph, "load-manifests", "target=" + target, "run_id=" + RUN_HEX, "load-manifest.json"),
             os.path.join(destination, "graph", "run-metadata", "load-manifests", "target=" + target, "load-manifest.json"))
    for name in HADOOP_INPUTS:
        found = glob.glob(os.path.join(base, "hadoop", "scholarchain", name, "snapshot=2026-11-01", "run_id=*"))
        if len(found) != 1:
            sys.exit("expected one Hadoop directory for " + name)
        shutil.copytree(found[0], os.path.join(destination, "graph", "hadoop-inputs", name),
                        ignore=shutil.ignore_patterns("*.crc"))

    repro = os.path.join(destination, "repro")
    os.makedirs(repro, exist_ok=True)
    with open(args.clean_log, "rb") as source, open(os.path.join(repro, "clean-check.log.gz"), "xb") as raw_out:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw_out, mtime=0) as packed:
            shutil.copyfileobj(source, packed)
    dump(os.path.join(repro, "clone-state.json"), {
        "path": args.clone, "head": git(args.clone, "rev-parse", "HEAD"),
        "porcelainLines": len(git(args.clone, "status", "--porcelain").splitlines()),
        "logSha256": digest.hexdigest(), "logBytes": size, "secretPatternMatches": matches})
    records = []
    for name in sorted(glob.glob(os.path.join(REPO, "build", "deploy-record-dev-*.json"))):
        with open(name) as handle:
            records.append(json.load(handle))
    dump(os.path.join(repro, "deploy-records.json"), records)
    copy(os.path.join(REPO, "build", "pipeline-run-phase14.json"), os.path.join(repro, "pipeline-run-phase14.json"))
    plans = {}
    for record in records:
        checksum = record["approvedPlanSha256"]
        sources = []
        for pattern in ("phase-14*/**/*", "phase-15/*"):
            for path in sorted(glob.glob(os.path.join(REPO, "docs", "agent-runs", pattern), recursive=True)):
                if os.path.isfile(path):
                    with open(path, errors="replace") as handle:
                        if checksum in handle.read():
                            sources.append(os.path.relpath(path, REPO))
        plans[checksum] = ";".join(sorted(set(sources))) or "no source found"
    dump(os.path.join(repro, "plan-checksums-in-logs.json"), plans)

    labels = os.path.join(REPO, "experiments", "evaluation", "labels")
    gold = [os.path.join("docs", "agent-runs", "phase-05", "gold-set-v1.csv")]
    topup = os.path.join(labels, "gold-topup.csv")
    if os.path.exists(topup):
        sys.exit("gold-topup.csv exists now; the evaluator output in the staging directory did not use it")
    commands_path = os.path.join(destination, "commands.json")
    with open(commands_path) as handle:
        commands = json.load(handle)
    dump(os.path.join(destination, "config.json"), {
        "runId": args.run_id, "workOrder": "Phase 16 Part A (items 1-5)",
        "commitSha": git(REPO, "rev-parse", "HEAD"),
        "workingTreeAtSeal": git(REPO, "status", "--porcelain", "--untracked-files=normal").splitlines(),
        "startedAtUtc": args.started,
        "sealedAtUtc": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "environment": "dev", "region": "us-east-2", "awsProfile": "cs441",
        "deployedGraphRun": {"runId": "run:" + RUN_HEX, "snapshot": "2026-11-01", "sparkCodeVersion": "a75b607",
                             "sparkExecution": "local (not EMR), per run-summary executionParameters"},
        "goldSetFiles": [{"path": p, "sha256": sha256(os.path.join(REPO, p))} for p in gold],
        "goldTopupPresent": False, "labelsDirectoryEntries": sorted(os.listdir(labels)),
        "seeds": {"bootstrap": 20261125, "edgeAudit": 20261125, "resamples": 1000, "auditEdges": 50},
        "pythonPackages": {"pyarrow": "21.0.0", "pyyaml": "6.0.2"},
        "commands": commands})

    sums = []
    for dirpath, _, files in os.walk(destination):
        for name in files:
            path = os.path.join(dirpath, name)
            sums.append("%s  %s\n" % (sha256(path), os.path.relpath(path, destination)))
    with open(os.path.join(destination, "SHA256SUMS"), "x") as handle:
        handle.writelines(sorted(sums, key=lambda line: line.split("  ", 1)[1]))
    for dirpath, _, files in os.walk(destination):
        for name in files:
            os.chmod(os.path.join(dirpath, name), 0o444)
    print("sealed %d files in %s" % (len(sums) + 1, os.path.relpath(destination, REPO)))


if __name__ == "__main__":
    main()
