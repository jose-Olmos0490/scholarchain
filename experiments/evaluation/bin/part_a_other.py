"""Items 3, 4 and 5 tables. Item 3 reads only the sealed Phase 15 Experiment C raw directory."""

import collections
import datetime
import glob
import gzip
import json
import os
import re
import xml.etree.ElementTree as ElementTree

from part_a_common import read_csv, sha256_file, svg_bars, write_csv

C_RUN = "experiments/raw/C-embedding-backlog/C-embedding-backlog-6ce56c9-20261125T171458Z"


def when(text):
    return datetime.datetime.fromisoformat(text.replace("Z", "+00:00"))


def stamp(moment):
    return moment.astimezone(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def perf(out, repo):
    run = os.path.join(repo, C_RUN)
    with open(os.path.join(run, "SHA256SUMS")) as handle:
        sums = [line.split(None, 1) for line in handle.read().splitlines() if line]
    write_csv(out, "perf/c-raw-checksums.csv", ("file", "sha256", "match"), [
        [name.strip().lstrip("*"), digest,
         "yes" if sha256_file(os.path.join(run, name.strip().lstrip("*"))) == digest else "NO"]
        for digest, name in sums])

    def lines(name):
        with open(os.path.join(run, name)) as handle:
            return [json.loads(line) for line in handle if line.strip()]

    with open(os.path.join(run, "runner", "run.json")) as handle:
        summary = json.load(handle)
    sends, polls, outcomes = lines("runner/sends.jsonl"), lines("runner/observations.jsonl"), lines("worker_outcomes.jsonl")
    first_send = min(when(s["atUtc"]) for s in sends)

    def first(condition, after=None):
        return next((when(p["atUtc"]) for p in polls if condition(p) and (after is None or when(p["atUtc"]) > after)), None)

    visible = first(lambda p: p["visible"] > 0)
    desired = first(lambda p: p["desired"] > 0)
    running = first(lambda p: p["running"] > 0)
    receiving = first(lambda p: p["inFlight"] > 0)
    empty = first(lambda p: p["visible"] == 0 and p["inFlight"] == 0, after=receiving)
    in_desired = first(lambda p: p["desired"] == 0, after=empty)
    in_running = first(lambda p: p["running"] == 0, after=empty)
    batches = [o for o in outcomes if o["event"] == "batch"]
    rejects = [o for o in outcomes if o["event"] == "reject"]
    first_batch = datetime.datetime.fromtimestamp(min(b["atMillis"] for b in batches) / 1000, datetime.timezone.utc)
    last_batch = datetime.datetime.fromtimestamp(max(b["atMillis"] for b in batches) / 1000, datetime.timezone.utc)

    events = [["first send", first_send], ["first poll with visible > 0", visible],
              ["scale-out: first poll with desired > 0", desired], ["first poll with running > 0", running],
              ["first poll with in flight > 0", receiving], ["first worker batch line", first_batch],
              ["last worker batch line", last_batch], ["first empty poll (visible 0, in flight 0)", empty],
              ["scale-in: first poll with desired 0", in_desired], ["first later poll with running 0", in_running]]
    write_csv(out, "perf/c-timeline.csv", ("event", "utc", "seconds_after_first_send"), [
        [name, stamp(at) if at else "missing", "%.1f" % (at - first_send).total_seconds() if at else "missing"]
        for name, at in events])

    def seconds(a, b):
        return "%.1f" % (b - a).total_seconds() if a and b else "missing"

    gaps = [(when(b["atUtc"]) - when(a["atUtc"])).total_seconds() for a, b in zip(polls, polls[1:])]
    processing = (last_batch - first_batch).total_seconds()
    acknowledged = sum(b["acknowledged"] for b in batches)
    task_seconds = sum(a["running"] * (when(b["atUtc"]) - when(a["atUtc"])).total_seconds() for a, b in zip(polls, polls[1:]))
    measures = [
        ["run status", summary["status"], "runner/run.json"],
        ["stop reason", summary["stopReason"], "runner/run.json"],
        ["scaling delay: first send to desired > 0 (s)", seconds(first_send, desired), "observations"],
        ["scaling delay: first visible poll to desired > 0 (s)", seconds(visible, desired), "observations"],
        ["scale-out: desired > 0 to running > 0 (s)", seconds(desired, running), "observations"],
        ["drain: first send to first empty poll (s)", seconds(first_send, empty), "sends, observations"],
        ["drain: runner milestone drainedAfterSeconds", summary["milestones"]["drainedAfterSeconds"], "runner/run.json"],
        ["scale-in: first empty poll to desired 0 (s)", seconds(empty, in_desired), "observations"],
        ["runner milestone scaledToZeroAfterSeconds", summary["milestones"]["scaledToZeroAfterSeconds"], "runner/run.json"],
        ["max desired / running tasks", "%d / %d" % (max(p["desired"] for p in polls), max(p["running"] for p in polls)),
         "observations"],
        ["poll interval, median (s)", "%.1f" % sorted(gaps)[len(gaps) // 2], "observations"],
        ["messages sent / accepted", "%d / %d" % (len(sends), sum(bool(s["accepted"]) for s in sends)), "sends"],
        ["messages received / acknowledged / retried / dead-lettered", "%d / %d / %d / %d" % tuple(
            sum(b[k] for b in batches) for k in ("received", "acknowledged", "retried", "deadLettered")), "worker_outcomes"],
        ["message throughput, acknowledged per second of batch-line span",
         "%.4f" % (acknowledged / processing) if processing else "missing", "worker_outcomes"],
        ["item rejections per second of batch-line span", "%.2f" % (len(rejects) / processing) if processing else "missing",
         "worker_outcomes"],
        ["new SUCCEEDED vector put batches", summary["vectorWriteEvidence"]["newSucceededPutBatches"], "runner/run.json"],
        ["maximum DLQ visible", max(p["dlqVisible"] for p in polls), "observations"],
        ["running task-seconds (poll integration, approximate)", "%.0f" % task_seconds, "observations"],
        ["billed cost (USD)", "not measured: no billing data in raw", "-"],
        ["cost estimate (USD), from the Phase 15 report, not from raw", "about 1.5 (cap 1.0; prices unverified)",
         "docs/agent-runs/phase-15/experiment-C-report.md"],
    ]
    write_csv(out, "perf/c-measures.csv", ("measure", "value", "source"), measures)
    errors = collections.Counter((r["store"], r["code"]) for r in rejects)
    write_csv(out, "perf/c-errors.csv", ("store", "code", "items"), [[s, c, n] for (s, c), n in sorted(errors.items())])
    write_csv(out, "perf/experiments-run-status.csv", ("experiment", "status"), [
        ["A EMR baseline", "not run"], ["B EMR burst", "not run"],
        ["C embedding backlog", "run once: %s %s" % (summary["status"], summary["stopReason"])],
        ["D query load", "not run"], ["E retrieval modes", "not run"], ["F poison message", "not run"]])


def factory(raw, out):
    source = os.path.join(raw, "factory")
    work = read_csv(os.path.join(source, "work_orders.csv"))
    by_agent = collections.defaultdict(collections.Counter)
    confirm = collections.Counter()
    for row in work:
        by_agent[row["agent"]][row["first_pass"]] += 1
        confirm[row["agent"]] += row["needs_confirmation"] == "yes"
    files = lambda rows: ";".join(sorted({r["source_file"] for r in rows}))
    table = []
    for agent in sorted(by_agent):
        c = by_agent[agent]
        known = c["yes"] + c["no"]
        table.append([agent, c["yes"] + c["no"] + c["unknown"], c["yes"], c["no"], c["unknown"],
                      "%.2f" % (c["yes"] / known) if known else "n/a", confirm[agent],
                      files([r for r in work if r["agent"] == agent])])
    write_csv(out, "factory/first-pass-by-agent.csv", ("agent", "primary_work_orders", "first_pass_yes",
                                                       "first_pass_no", "unknown", "rate_over_known",
                                                       "rows_needing_confirmation", "source_files"), table)

    summary = []

    def add(measure, value, rows, needs):
        summary.append([measure, value, "yes" if needs else "no", files(rows)])

    blocked = read_csv(os.path.join(source, "blocked_on.csv"))
    add("BLOCKED-ON results (all)", len(blocked), blocked,
        any(r["needs_confirmation"] == "yes" or r["archived_verbatim"] == "no" for r in blocked))
    archived = [r for r in blocked if r["archived_verbatim"] == "yes"]
    add("BLOCKED-ON results with an archived report", len(archived), archived, False)
    partial = read_csv(os.path.join(source, "blocked_on_partial.csv"))
    add("partial blocks inside other statuses (not counted above)", len(partial), partial, True)

    ownership = read_csv(os.path.join(source, "ownership.csv"))
    for category in ("blocked-write", "refused-by-agent", "landed", "unresolved"):
        rows = [r for r in ownership if r["category"] == category]
        add("ownership or authority events: " + category, len(rows), rows,
            any(r["needs_confirmation"] == "yes" for r in rows))

    blocks = read_csv(os.path.join(source, "hook_blocks.csv"))
    for mechanism in ("guard", "stop-hook", "permission"):
        rows = [r for r in blocks if r["mechanism"] == mechanism]
        add("%s block events documented (approximate; some reports give only a minimum)" % mechanism, sum(int(r["count"]) for r in rows), rows, True)
    write_csv(out, "factory/hook-blocks-by-phase.csv", ("phase", "mechanism", "count", "contains_minimum_counts",
                                                        "source_files"), [
        [phase, mechanism, sum(int(r["count"]) for r in rows),
         "yes" if any(r["count_basis"] == "minimum" for r in rows) else "no", files(rows)]
        for phase in sorted({r["phase"] for r in blocks})
        for mechanism in ("guard", "stop-hook", "permission")
        for rows in [[r for r in blocks if r["phase"] == phase and r["mechanism"] == mechanism]] if rows])

    interventions = read_csv(os.path.join(source, "interventions.csv"))
    for category in ("unplanned", "gate-action", "process"):
        rows = [r for r in interventions if r["category"] == category]
        add("manual interventions: " + category, len(rows), rows, True)
    rewrites = read_csv(os.path.join(source, "rewrites.csv"))
    substantial = [r for r in rewrites if r["substantial_human_rewrite"].startswith("yes")]
    add("artifacts needing substantial human rewrite (documented)", len(substantial), rewrites, True)
    write_csv(out, "factory/summary.csv", ("measure", "count", "needs_human_confirmation", "source_files"), summary)

    findings = read_csv(os.path.join(source, "review_findings.csv"))
    severities = ("critical", "blocker", "major", "minor", "nit")
    rows = []
    for key in sorted({(f["phase"], f["review_file"]) for f in findings}):
        members = [f for f in findings if (f["phase"], f["review_file"]) == key]
        rows.append(list(key) + [sum(f["severity"] == s for f in members) for s in severities]
                    + [len(members), members[0]["source_file"]])
    totals = [sum(f["severity"] == s for f in findings) for s in severities]
    rows.append(["all", "all"] + totals + [len(findings), "review_findings.csv"])
    write_csv(out, "factory/review-findings-by-severity.csv", ("phase", "review_file") + severities + ("total",
                                                                                                       "source_file"), rows)
    svg_bars(out, "factory/review-findings.svg", "Reviewer findings in archived reports, by severity",
             list(zip(severities, totals)))
    gaps = [["02", "review-report-02.md is empty (0 bytes); the human gate records APPROVE"],
            ["05", "first review (findings F1-F14) is not archived, so its severities are not counted"],
            ["06", "the human gate cites a review round 2 and repair-02; neither is archived"],
            ["08", "code review skipped by human decision"], ["10", "code review not run"], ["10b", "code review not run"]]
    write_csv(out, "factory/review-evidence-gaps.csv", ("phase", "gap"), gaps)

    repairs = read_csv(os.path.join(source, "repairs.csv"))
    write_csv(out, "factory/repair-iterations.csv", ("phase", "repair_files_present", "repair_rounds_documented",
                                                     "needs_confirmation", "note", "source_file"),
              [[r[k] for k in ("phase", "repair_files_present", "repair_rounds_documented", "needs_confirmation", "note",
                               "source_file")] for r in repairs])
    queue = []
    for name in ("work_orders", "blocked_on", "hook_blocks", "ownership", "interventions", "rewrites", "repairs"):
        for r in read_csv(os.path.join(source, name + ".csv")):
            if r["needs_confirmation"] == "yes":
                detail = "; ".join("%s=%s" % (k, v) for k, v in r.items()
                                   if k not in ("source_file", "source_line", "anchor", "needs_confirmation") and v)
                queue.append([name, detail, r["source_file"], r["source_line"]])
    write_csv(out, "factory/needs-human-confirmation.csv", ("table", "row", "source_file", "source_line"), queue)


def repro(raw, out):
    source = os.path.join(raw, "repro")
    totals, successes, marker, errors, failed_lines = [], 0, [], 0, 0
    first_success = last_success = ""
    with gzip.open(os.path.join(source, "clean-check.log.gz"), "rt", errors="replace") as handle:
        for line in handle:
            match = re.search(r"Tests: succeeded (\d+), failed (\d+), canceled (\d+), ignored (\d+), pending (\d+)", line)
            if match:
                totals.append([len(totals) + 1] + list(match.groups()))
            if line.startswith("[success]"):
                successes += 1
                found = re.search(r"completed (.*)$", line.strip())
                if found:
                    first_success = first_success or found.group(1)
                    last_success = found.group(1)
            if "markTestRun: tests passed" in line:
                marker.append(line.strip())
            errors += line.startswith("[error]")
            failed_lines += "*** FAILED ***" in line
    write_csv(out, "repro/clean-clone-test-summaries.csv",
              ("summary_line_in_log_order", "succeeded", "failed", "canceled", "ignored", "pending"), totals)
    with open(os.path.join(source, "clone-state.json")) as handle:
        clone = json.load(handle)
    write_csv(out, "repro/clean-clone-outcome.csv", ("measure", "value"), [
        ["log sha256 (uncompressed)", clone["logSha256"]], ["log bytes", clone["logBytes"]],
        ["commit named in the log", "no"], ["clone HEAD observed on disk", clone["head"]],
        ["clone git status lines observed (porcelain)", clone["porcelainLines"]],
        ["[success] lines", successes], ["first [success] time (local)", first_success],
        ["last [success] time (local)", last_success],
        ["test summary lines", len(totals)], ["tests succeeded, summed over summary lines", sum(int(t[1]) for t in totals)],
        ["tests failed, summed", sum(int(t[2]) for t in totals)], ["tests ignored, summed", sum(int(t[4]) for t in totals)],
        ["[error] lines", errors], ["lines containing *** FAILED ***", failed_lines],
        ["markTestRun success line present", "yes" if marker else "no"]])

    with open(os.path.join(source, "deploy-records.json")) as handle:
        records = json.load(handle)
    with open(os.path.join(source, "plan-checksums-in-logs.json")) as handle:
        plans = json.load(handle)
    with open(os.path.join(raw, "aws", "ecs-services.json")) as handle:
        live = {s["service"]: s for s in json.load(handle)}
    rows = []
    for record in records:
        for service in record["services"]:
            name = service["service"]
            running_now = live[name]["taskDefinition"] == service["taskDefinition"]
            ecr = ""
            if record["gitSha"] == "3c3e1a1":
                with open(os.path.join(raw, "aws", "ecr-%s.json" % name)) as handle:
                    ecr = "yes" if json.load(handle)["digest"] == service["digest"] else "NO"
            rows.append([record["gitSha"], record["deployedAtUtc"], record["approvedPlanSha256"],
                         plans.get(record["approvedPlanSha256"], "no source found"), name,
                         service["taskDefinitionRevision"], service["digest"], "yes" if running_now else "no", ecr])
    write_csv(out, "repro/plan-vs-deployed.csv", ("git_sha", "deployed_utc", "approved_plan_sha256",
                                                  "checksum_also_recorded_in", "service", "task_revision",
                                                  "image_digest", "revision_live_now", "ecr_digest_matches"), rows)
    write_csv(out, "repro/pipeline-evidence.csv", ("item", "value", "source"), [
        ["Hadoop run id (local, Phase 14)", "run:dc970f17ed36a1ca8265fd4201d89ad6903ce3389bbcaeac10fe3504b73742f4",
         "docs/agent-runs/phase-14/logs/pipeline-local.log"],
        ["Spark run id (local, Phase 14)", "run:f6023de34a312366583b3eacbc2b2a689234cf1abe79e184fb66875bac297a60",
         "docs/agent-runs/phase-14/logs/spark-local.log"],
        ["first Spark stage attempt", "failed", "docs/agent-runs/phase-14/logs/pipeline-local.log"],
        ["later local pipeline record (commit 298d78e, stage spark)", "status FAILED, exit 1", "raw repro/pipeline-run-phase14.json"],
        ["full replay of the bounded pipeline with output comparison", "no evidence found", "docs/agent-runs/phase-14, phase-15"],
        ["EMR clusters listed in us-east-2", "0", "raw aws/emr-list-clusters.json"],
        ["S3 copy of the run matches the local Spark manifest checksums", "see graph/s3-vs-load-manifest.csv", "derived"]])


def tests(raw, out):
    rows = []
    for path in sorted(glob.glob(os.path.join(raw, "tests", "junit", "*", "*.xml"))):
        suite = ElementTree.parse(path).getroot()
        total, failures, errors, skipped = (int(suite.get(k) or 0) for k in ("tests", "failures", "errors", "skipped"))
        rows.append([os.path.basename(os.path.dirname(path)), suite.get("name"), total,
                     total - failures - errors - skipped, failures, errors, skipped])
    totals = [["total", module] + [sum(r[i] for r in rows if r[0] == module) for i in range(2, 7)]
              for module in sorted({r[0] for r in rows})]
    write_csv(out, "tests/known-graph-tests.csv", ("module", "suite", "tests", "passed", "failures", "errors", "skipped"),
              rows + [[t[1], "TOTAL"] + t[2:] for t in totals])
    cases = []
    for path in sorted(glob.glob(os.path.join(raw, "tests", "junit", "*", "*.xml"))):
        for case in ElementTree.parse(path).getroot().findall("testcase"):
            status = "failed" if case.find("failure") is not None or case.find("error") is not None else (
                "skipped" if case.find("skipped") is not None else "passed")
            cases.append([os.path.basename(os.path.dirname(path)), case.get("classname"), case.get("name"), status])
    write_csv(out, "tests/known-graph-test-cases.csv", ("module", "suite", "case", "status"), sorted(cases))
