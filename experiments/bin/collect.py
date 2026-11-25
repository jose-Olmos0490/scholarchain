#!/usr/bin/env python3
"""Collects the evidence of one run into experiments/raw/<experiment>/<run-id>/ and makes it
read-only. All AWS calls are reads.

    experiments/bin/collect.py --env dev --manifest experiments/manifests/<name>.yaml
        --record build/experiment-<name>-<sha>-<stamp>.json
        [--pipeline-record build/pipeline-run-<env>-run-<sha>-<stamp>.json]
        [--pre-seconds N] [--post-seconds N] [--allow-missing]

The UTC window is the record's start and end, widened by --pre-seconds and --post-seconds
(defaults: manifest collect.pre_seconds and collect.post_seconds), aligned to whole minutes,
exported at a 60 s period. It refuses to run before the window has closed, and it refuses to
write into an existing run directory. Failed runs are collected exactly like successful ones.

The manifest is read from the run's commit (git show); --manifest only says which one.

Anything missing means nonzero exit and nothing sealed; fix it and run again. That covers: the
manifest at the run's commit; run record fields; the dimension variables its metrics name in
`dimensionsFrom`; SC_ECS_CLUSTER when it lists collect.deployed_services; the log group variables
under collect.logs; for a load-test run the runner directory under SC_BUILD_DIR (default build/)
with the tool's result files, made by the same manifest content, readable and as long as run.json
says; for a pipeline run --pipeline-record and its fields; and, from AWS, every metric without
datapoints, every listed service without a revision or image tag, and every log without a usable
line. CloudWatch publishes nothing for some zero counts, so such a metric reads as missing too.
--allow-missing seals anyway. It takes effect only when typed in full on the command line: no
default, abbreviation, environment variable or manifest field enables it. collection.json
`missingInputs` then lists every missing item as {"item": id, "detail": text}.
Variable values are used in queries and never written.
Required: SCHOLARCHAIN_APPROVED_ENVS listing --env. AWS profile: AWS_PROFILE.
"""

import argparse
import datetime
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

import yaml

sys.dont_write_bytecode = True
sys.path.insert(
    0, os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "load-tests"))

import sclib  # noqa: E402

PERIOD = 60
RUNNER_FILES = ("requests.jsonl", "sends.jsonl", "observations.jsonl", "run.json")
# the files each load-test target writes in every run that got past its start-up checks
EXPECTED_FILES = {
    "query_load.py": ("run.json", "requests.jsonl"),
    "queue_scenario.py": ("run.json", "sends.jsonl", "observations.jsonl"),
}
RECORD_KEYS = ("experiment", "manifest", "runner", "environment", "commitSha", "region", "startedAtUtc",
               "endedAtUtc", "runnerExitCode", "timedOut", "innerWaitTimeoutSeconds", "resourceLimits",
               "metricLocations")
STAGE_LINE = re.compile(r"stage traceId=(\S+) stage=(\S+) latencyMs=(\d+) outcome=(\S+)")
BATCH_LINE = re.compile(r"batch received=(\d+) acknowledged=(\d+) retried=(\d+) deadLettered=(\d+)")
REJECT_LINE = re.compile(r"reject store=(\S+) key=\S* code=([A-Z0-9_]+)")


def floor_minute(moment):
    return moment.replace(second=0, microsecond=0)


def window_of(started, ended, pre, post):
    start = floor_minute(sclib.parse_iso(started) - datetime.timedelta(seconds=pre))
    end = sclib.parse_iso(ended) + datetime.timedelta(seconds=post)
    if floor_minute(end) != end:
        end = floor_minute(end) + datetime.timedelta(minutes=1)
    return start, end


def stamp(moment):
    return moment.strftime("%Y-%m-%dT%H:%M:%SZ")


def is_set(variable):
    return bool(os.environ.get(variable, "").strip())


def lacking(item, detail):
    return {"item": item, "detail": detail}


def read_object(path):
    """The JSON object in `path`, or None when it cannot be read as one."""
    try:
        with open(path) as handle:
            doc = json.load(handle)
    except (ValueError, OSError):
        return None
    return doc if isinstance(doc, dict) else None


def jsonl_rows(path):
    """(the readable object lines, the number of lines that are not)."""
    rows, bad = [], 0
    with open(path, errors="replace") as handle:
        for line in handle:
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except ValueError:
                row = None
            if isinstance(row, dict):
                rows.append(row)
            else:
                bad += 1
    return rows, bad


def missing_variables(manifest):
    """The variables the manifest needs that are unset."""
    settings = manifest.get("collect") or {}
    names = [var for spec in manifest.get("metrics") or [] for var in (spec.get("dimensionsFrom") or {}).values()]
    if settings.get("deployed_services"):
        names.append("SC_ECS_CLUSTER")
    names += [spec["logGroupFrom"] for spec in settings.get("logs") or []]
    return sorted({n for n in names if not is_set(n)})


def metric_queries(specs):
    """(queries for get-metric-data, per-metric descriptors without any dimension value)."""
    queries, described = [], []
    for spec in specs:
        dimensions = dict(spec.get("dimensions") or {})
        sources = spec.get("dimensionsFrom") or {}
        for name, var in sources.items():
            if is_set(var):
                dimensions[name] = os.environ[var].strip()
        entry = {"id": spec["id"], "namespace": spec["namespace"], "metric": spec["metric"],
                 "stat": spec["stat"], "dimensionKeys": sorted(set(dimensions) | set(sources)),
                 "service": (spec.get("dimensions") or {}).get("ServiceName"),
                 "timestamps": [], "values": []}
        described.append(entry)
        if not all(is_set(var) for var in sources.values()):
            entry["status"] = "SKIPPED_NO_CONFIG"
            continue
        queries.append({
            "Id": spec["id"],
            "MetricStat": {
                "Metric": {"Namespace": spec["namespace"], "MetricName": spec["metric"],
                           "Dimensions": [{"Name": k, "Value": v} for k, v in sorted(dimensions.items())]},
                "Period": PERIOD,
                "Stat": spec["stat"],
            },
            "ReturnData": True,
        })
    return queries, described


def export_metrics(region, specs, start, end):
    queries, described = metric_queries(specs)
    by_id = {d["id"]: d for d in described}
    if len(by_id) != len(described):
        raise sclib.ConfigError("metric ids must be unique")
    for offset in range(0, len(queries), 100):
        doc = sclib.aws_json(
            ["cloudwatch", "get-metric-data", "--metric-data-queries", json.dumps(queries[offset:offset + 100]),
             "--start-time", stamp(start), "--end-time", stamp(end), "--scan-by", "TimestampAscending"],
            region, timeout=180)
        for result in doc.get("MetricDataResults", []):
            entry = by_id[result["Id"]]
            entry["timestamps"] += result.get("Timestamps", [])
            entry["values"] += result.get("Values", [])
            entry["status"] = result.get("StatusCode", "Complete")
    for entry in described:
        entry.setdefault("status", "NOT_RETURNED")
        if entry["status"] == "Complete" and not entry["values"]:
            entry["status"] = "NO_DATAPOINTS"
    return described


def image_tag(image):
    """The digest or tag of an image reference, None when it has neither. Never the repository:
    it holds the registry host and so the account ID."""
    if "@" in image:
        return image.split("@", 1)[1]
    last = image.rsplit("/", 1)[-1]
    return last.split(":", 1)[1] if ":" in last else None


def deployed_images(region, services):
    """Image tag and task definition revision per service, as defined WHEN COLLECTED. Never the URI
    and never the environment of the task definition."""
    observed = {"observedAtUtc": stamp(sclib.utc_now()),
                "note": "what the service definitions referenced at collection time, not necessarily during the run"}
    if not services:
        return dict(observed, status="NOT_REQUESTED", services=[])
    cluster = os.environ.get("SC_ECS_CLUSTER", "").strip()
    if not cluster:
        return dict(observed, status="SKIPPED_NO_CONFIG", services=[])
    out = []
    doc = sclib.aws_json(["ecs", "describe-services", "--cluster", cluster, "--services"] + list(services), region)
    for service in doc.get("services", []):
        task = sclib.aws_json(
            ["ecs", "describe-task-definition", "--task-definition", service["taskDefinition"],
             "--query", "{images: taskDefinition.containerDefinitions[].image, revision: taskDefinition.revision}"],
            region)
        out.append({"service": service["serviceName"], "revision": task.get("revision"),
                    "imageTags": [image_tag(i) for i in task.get("images", [])]})
    found = {s["service"] for s in out}
    return dict(observed, status="OK" if set(services) <= found else "INCOMPLETE", services=out)


def log_lines(region, group, pattern, start, end):
    doc = sclib.aws_json(
        ["logs", "filter-log-events", "--log-group-name", group, "--filter-pattern", pattern,
         "--start-time", str(int(start.timestamp() * 1000)), "--end-time", str(int(end.timestamp() * 1000))],
        region, timeout=300)
    return [(e.get("timestamp"), e.get("message", "")) for e in doc.get("events", [])]


def export_logs(region, specs, start, end, trace_ids, staging):
    """Extracts fixed fields from known log lines. Raw log text is never stored."""
    status = []
    for spec in specs:
        kind, variable = spec["kind"], spec["logGroupFrom"]
        group = os.environ.get(variable, "").strip()
        if not group:
            status.append({"kind": kind, "status": "SKIPPED_NO_CONFIG"})
            continue
        rows, unparsed = [], 0
        if kind == "workflow_stages":
            # only this run's traces: without any trace id nothing can be attributed to the run
            if not trace_ids:
                status.append({"kind": kind, "status": "NO_TRACE_IDS"})
                continue
            for at, message in log_lines(region, group, '"stage traceId"', start, end):
                found = STAGE_LINE.search(message)
                if not found:
                    unparsed += 1
                elif found.group(1) in trace_ids:
                    rows.append({"atMillis": at, "traceId": found.group(1), "stage": found.group(2),
                                 "latencyMs": int(found.group(3)), "outcome": found.group(4)[:80]})
        elif kind == "worker_outcomes":
            for at, message in log_lines(region, group, '?"batch received" ?"reject store"', start, end):
                batch, reject = BATCH_LINE.search(message), REJECT_LINE.search(message)
                if batch:
                    rows.append({"atMillis": at, "event": "batch", "received": int(batch.group(1)),
                                 "acknowledged": int(batch.group(2)), "retried": int(batch.group(3)),
                                 "deadLettered": int(batch.group(4))})
                elif reject:
                    rows.append({"atMillis": at, "event": "reject", "store": reject.group(1), "code": reject.group(2)})
                else:
                    unparsed += 1
        else:
            raise sclib.ConfigError("unknown log kind " + kind)
        with open(os.path.join(staging, kind + ".jsonl"), "w") as handle:
            handle.writelines(json.dumps(r, sort_keys=True) + "\n" for r in rows)
        status.append({"kind": kind, "status": "OK", "rows": len(rows), "unparsedLines": unparsed})
    return status


def missing_logs(statuses, staging, answered_traces):
    """What the log sources did not deliver. `answered_traces` are those of HTTP 200 requests."""
    missing = []
    for entry in statuses:
        kind = entry["kind"]
        if entry["status"] != "OK":
            missing.append(lacking("log:" + kind, entry["status"]))
            continue
        if entry["unparsedLines"]:
            missing.append(lacking("log:%s:unparsed" % kind,
                                   "%d lines matched the filter but not the format" % entry["unparsedLines"]))
        rows, _ = jsonl_rows(os.path.join(staging, kind + ".jsonl"))
        if not rows:
            missing.append(lacking("log:" + kind, "no line of this run"))
        elif kind == "workflow_stages":
            seen = {r["traceId"] for r in rows}
            missing += [lacking("log:workflow_stages:trace:" + t, "an answered request has no stage line")
                        for t in sorted(answered_traces - seen)]
        elif kind == "worker_outcomes" and not any(r["event"] == "batch" for r in rows):
            missing.append(lacking("log:worker_outcomes:batch-lines", "no batch line"))
    return missing


def missing_metrics(metrics):
    return [lacking("metric:" + m["id"], m["status"] if m["status"] != "Complete" else "NO_DATAPOINTS")
            for m in metrics if m["status"] != "Complete" or not m["values"]]


def missing_deployment(deployment, services):
    found = {s["service"]: s for s in deployment["services"]}
    missing = []
    for name in services:
        service = found.get(name)
        if service is None:
            why = deployment["status"] if deployment["status"] != "INCOMPLETE" else "deployed service not found"
            missing.append(lacking("deployment:" + name, why))
            continue
        if service["revision"] is None:
            missing.append(lacking("deployment:%s:revision" % name, "no task definition revision"))
        if not service["imageTags"] or None in service["imageTags"]:
            missing.append(lacking("deployment:%s:imageTag" % name, "an image has no tag or digest"))
    return missing


def runner_directory(record):
    """The record's result directory, only when it lies under <repo>/build/experiments."""
    raw = record.get("rawResultDirectory") or ""
    root = os.path.realpath(os.path.join(os.environ.get("SC_BUILD_DIR") or os.path.join(sclib.REPO_ROOT, "build"),
                                         "experiments"))
    real = os.path.realpath(raw) if raw else ""
    return real if real.startswith(root + os.sep) and os.path.isdir(real) else None


def regular_file(directory, name):
    path = os.path.join(directory, name)
    return os.path.isfile(path) and not os.path.islink(path)


# the run.json fields each tool writes in every run
RUN_FIELDS = {
    "query_load.py": ("status", "requestsSent", "plannedPhases"),
    "queue_scenario.py": ("status", "messagesPlanned", "messagesAccepted", "messagesRefused",
                          "messagesOutcomeUnknown", "milestones", "vectorWriteEvidence"),
}
# the run.json counts that say how many lines a result file holds
LINE_COUNTS = {
    "requests.jsonl": ("requestsSent",),
    "sends.jsonl": ("messagesAccepted", "messagesRefused", "messagesOutcomeUnknown"),
}


def records_expected(name, run):
    """How many lines run.json says the file holds, None when it does not say."""
    counts = [run.get(k) for k in LINE_COUNTS.get(name, ())]
    return sum(counts) if counts and all(isinstance(c, int) for c in counts) else None


def missing_runner_inputs(manifest, content_sha256, record, runner_dir, pipeline_record):
    """What the runner of this experiment should have left behind and did not."""
    if record.get("runner") == "pipeline":
        return [] if pipeline_record else [lacking("pipeline:record", "--pipeline-record was not given")]
    target = manifest.get("target")
    expected = EXPECTED_FILES.get(target, ("run.json",))
    if not runner_dir:
        return [lacking("runner:directory", "the runner result directory was not found (check SC_BUILD_DIR)")] + [
            lacking("runner:file:" + name, "no runner result directory") for name in expected]
    missing = [lacking("runner:file:" + name, "runner file %s is absent" % name)
               for name in expected if not regular_file(runner_dir, name)]
    run = {}
    if regular_file(runner_dir, "run.json"):
        run = read_object(os.path.join(runner_dir, "run.json"))
        if run is None:  # a killed run can leave it truncated
            run = {}
            missing.append(lacking("runner:file:run.json:unreadable", "not a readable runner file run.json"))
        else:
            if run.get("manifestSha256") != content_sha256:
                missing.append(lacking("runner:manifest-match", "the runner result was not made by this manifest "
                                       "(run.json manifestSha256 differs: the tool loaded another manifest or "
                                       "content)"))
            missing += [lacking("runner:run.json:" + f, "run.json does not hold it")
                        for f in RUN_FIELDS.get(target, ()) if run.get(f) is None]
            evidence = run.get("vectorWriteEvidence")
            if isinstance(evidence, dict) and evidence.get("status") != "OK":
                missing.append(lacking("runner:vectorWriteEvidence", str(evidence.get("status"))))
    for name in expected:
        if not name.endswith(".jsonl") or not regular_file(runner_dir, name):
            continue
        rows, bad = jsonl_rows(os.path.join(runner_dir, name))
        item = "runner:file:" + name
        if bad:
            missing.append(lacking(item + ":unreadable-lines", "%d lines cannot be read" % bad))
        count = records_expected(name, run)
        if count is not None and len(rows) < count:
            missing.append(lacking(item + ":records", "%d of %d records" % (len(rows), count)))
        elif not rows and not (name == "sends.jsonl" and count == 0):
            missing.append(lacking(item + ":no-records", "no record"))
        if name == "observations.jsonl":
            missing += [lacking("runner:observations:failed-poll:%s" % r.get("atUtc"), "a poll failed")
                        for r in rows if "error" in r]
    return missing


def sanitized_pipeline_record(path):
    """(the record without ARNs or bucket names, or None when unreadable; what it lacks)."""
    doc = read_object(path)
    if doc is None:
        return None, [lacking("pipeline:record:unreadable", "the pipeline record is absent or not readable")]
    run_ids = doc.get("runIds") if isinstance(doc.get("runIds"), dict) else {}
    configuration = doc.get("configuration") if isinstance(doc.get("configuration"), dict) else {}
    kept = {
        **{k: doc.get(k) for k in ("name", "mode", "commitSha", "region", "startedAtUtc", "endedAtUtc",
                                   "status", "stages", "datasetSnapshots")},
        "executionName": run_ids.get("executionName"),
        "configuration": {k: configuration.get(k) for k in ("waitTimeoutSeconds", "pollSeconds", "stopReason")},
    }
    missing = [lacking("pipeline:field:" + k, "the pipeline record does not hold it")
               for k in ("commitSha", "status", "startedAtUtc", "endedAtUtc", "stages", "executionName",
                         "datasetSnapshots") if not kept[k]]
    snapshots = kept["datasetSnapshots"] if isinstance(kept["datasetSnapshots"], dict) else {}
    missing += [lacking("pipeline:field:datasetSnapshots:" + name, "no snapshot date")
                for name, snapshot in sorted(snapshots.items()) if not (snapshot or {}).get("date")]
    return kept, missing


def manifest_at_commit(relative, sha):
    """The manifest bytes at the run's commit, or None. SC_GIT_BIN overrides git (offline tests)."""
    done = subprocess.run([os.environ.get("SC_GIT_BIN", "git"), "-C", sclib.REPO_ROOT, "show",
                           "%s:%s" % (sha, relative)], capture_output=True)
    return done.stdout if done.returncode == 0 else None


def seal(directory):
    """SHA256SUMS over every file, then files 0444 and directories 0555."""
    names = sorted(os.path.relpath(os.path.join(base, f), directory)
                   for base, _, files in os.walk(directory) for f in files)
    with open(os.path.join(directory, "SHA256SUMS"), "w") as handle:
        for name in names:
            handle.write("%s  %s\n" % (sclib.sha256_file(os.path.join(directory, name)), name))
    for base, _, files in os.walk(directory, topdown=False):
        for name in files:
            os.chmod(os.path.join(base, name), 0o444)
        os.chmod(base, 0o555)


def discard(staging):
    """Removes an unfinished staging directory, sealed or not."""
    if os.path.isdir(staging):
        for base, _, _ in os.walk(staging):
            os.chmod(base, 0o700)
        shutil.rmtree(staging)


def collect(args):
    approved = [e.strip() for e in os.environ.get("SCHOLARCHAIN_APPROVED_ENVS", "").split(",")]
    if args.env not in approved:
        raise sclib.ConfigError("--env is not listed in SCHOLARCHAIN_APPROVED_ENVS")
    _, manifest_path = sclib.load_manifest(os.path.abspath(args.manifest))
    with open(args.record) as handle:
        record = json.load(handle)

    # one source for everything the manifest decides: the commit the run was made from
    relative = record.get("manifest") or os.path.relpath(manifest_path, sclib.REPO_ROOT)
    content = manifest_at_commit(relative, record["commitSha"])
    source, missing = "git:" + record["commitSha"], []
    if content is None:
        missing.append(lacking("manifest:at-run-commit", "the manifest at the run's commit was not found"))
        source = "working tree (not found at the run's commit)"
        with open(manifest_path, "rb") as handle:
            content = handle.read()
    manifest = yaml.safe_load(content)
    if not isinstance(manifest, dict):
        raise sclib.ConfigError("manifest must be a mapping")

    if record.get("experiment") != manifest.get("name"):
        raise sclib.ConfigError("the record belongs to another experiment than the manifest")
    if record.get("environment") != args.env or record.get("region") != manifest.get("region"):
        raise sclib.ConfigError("the record's environment or Region does not match")
    experiment = manifest["name"]
    run_id = os.path.basename(args.record)[: -len(".json")]
    run_id = run_id[len("experiment-"):] if run_id.startswith("experiment-") else run_id
    for part in (experiment, run_id):
        if not re.fullmatch(r"[A-Za-z0-9._-]+", part) or part.startswith("."):
            raise sclib.ConfigError("unsafe experiment name or run id")
    raw_root = os.path.join(os.path.realpath(sclib.REPO_ROOT), "experiments", "raw")
    parent = os.path.join(raw_root, experiment)
    if os.path.realpath(parent) != parent:
        raise sclib.ConfigError("experiments/raw leads elsewhere through a symlink; refused")
    destination = os.path.join(parent, run_id)
    if os.path.lexists(destination):
        raise sclib.ConfigError("the run directory already exists; raw results are never overwritten")

    settings = manifest.get("collect") or {}
    pre = settings.get("pre_seconds", 0) if args.pre_seconds is None else args.pre_seconds
    post = settings.get("post_seconds", 0) if args.post_seconds is None else args.post_seconds
    start, end = window_of(record["startedAtUtc"], record["endedAtUtc"], pre, post)
    if sclib.utc_now() < end:
        raise sclib.ConfigError("the metric window closes at %s; run the collector after that" % stamp(end))
    region = record["region"]

    # every local input is checked before the first AWS call
    runner_dir = runner_directory(record)
    missing += [lacking("record:" + k, "the run record does not hold it")
                for k in ("resourceLimits", "runnerExitCode", "timedOut") if record.get(k) is None]
    missing += [lacking("variable:" + name, "the variable %s is not set" % name)
                for name in missing_variables(manifest)]
    missing += missing_runner_inputs(manifest, hashlib.sha256(content).hexdigest(), record, runner_dir,
                                     args.pipeline_record)
    pipeline = None
    if args.pipeline_record:
        pipeline, problems = sanitized_pipeline_record(args.pipeline_record)
        missing += problems
        if pipeline and pipeline["commitSha"] and pipeline["commitSha"] != record["commitSha"]:
            raise sclib.ConfigError("the pipeline record is for another commit")

    def require():
        if missing and not args.allow_missing:
            listed = "; ".join("%s (%s)" % (m["item"], m["detail"]) for m in missing)
            raise sclib.ConfigError("missing required inputs, nothing collected: " + listed
                                    + " (fix and run again, or type --allow-missing to record the omission)")

    require()

    os.makedirs(parent, exist_ok=True)
    # staged beside the destination so that publishing it is one atomic rename
    staging = tempfile.mkdtemp(prefix=".partial-", dir=parent)
    try:
        trace_ids, answered = set(), set()
        copied = []
        if runner_dir:
            os.mkdir(os.path.join(staging, "runner"))
            for name in RUNNER_FILES:
                if regular_file(runner_dir, name):
                    shutil.copyfile(os.path.join(runner_dir, name), os.path.join(staging, "runner", name))
                    copied.append(name)
            requests = os.path.join(staging, "runner", "requests.jsonl")
            if os.path.isfile(requests):
                # a half-written line stays in the raw copy and is listed as missing
                for row in jsonl_rows(requests)[0]:
                    if row.get("traceId"):
                        trace_ids.add(row["traceId"])
                        if row.get("status") == 200:
                            answered.add(row["traceId"])

        metrics = export_metrics(region, manifest.get("metrics") or [], start, end)
        sclib.write_json(os.path.join(staging, "metrics.json"), {
            "periodSeconds": PERIOD, "startUtc": stamp(start), "endUtc": stamp(end), "series": metrics})
        services = settings.get("deployed_services") or []
        deployment = deployed_images(region, services)
        sclib.write_json(os.path.join(staging, "deployment.json"), deployment)
        logs = export_logs(region, settings.get("logs") or [], start, end, trace_ids, staging)
        missing += missing_metrics(metrics) + missing_deployment(deployment, services)
        missing += missing_logs(logs, staging, answered)
        require()

        sclib.write_json(os.path.join(staging, "record.json"), {k: record.get(k) for k in RECORD_KEYS})
        if pipeline:
            sclib.write_json(os.path.join(staging, "pipeline-run.json"), pipeline)
        with open(os.path.join(staging, "manifest.yaml"), "wb") as handle:
            handle.write(content)
        sclib.write_json(os.path.join(staging, "collection.json"), {
            "experiment": experiment, "runId": run_id, "collectedAtUtc": stamp(sclib.utc_now()),
            "preSeconds": pre, "postSeconds": post, "runnerFiles": copied, "manifestSource": source,
            "datasetSnapshot": manifest.get("dataset_snapshot"), "logs": logs, "missingInputs": missing,
            "allowMissing": args.allow_missing,
            "metricStatus": {m["id"]: m["status"] for m in metrics},
        })
        seal(staging)
        if os.path.lexists(destination):
            raise sclib.ConfigError("the run directory already exists; raw results are never overwritten")
        os.rename(staging, destination)
    finally:
        discard(staging)
    sclib.say("raw results: " + os.path.relpath(destination, os.path.realpath(sclib.REPO_ROOT)))
    for entry in missing:
        print("WARN: sealed without: %s (%s)" % (entry["item"], entry["detail"]), file=sys.stderr)
    return 0


def main(argv):
    # no abbreviation: --allow-missing takes effect only when typed in full
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0], allow_abbrev=False)
    parser.add_argument("--env", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--record", required=True)
    parser.add_argument("--pipeline-record")
    parser.add_argument("--pre-seconds", type=int)
    parser.add_argument("--post-seconds", type=int)
    parser.add_argument("--allow-missing", action="store_true")
    args = parser.parse_args(argv)
    try:
        return collect(args)
    except (sclib.ConfigError, sclib.AwsError) as error:
        return sclib.fail(str(error))
    except Exception as error:  # the class name only: a message could hold a name or a path
        return sclib.fail("collection failed (%s); no run directory was written, run it again"
                          % type(error).__name__)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
