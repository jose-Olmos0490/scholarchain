#!/usr/bin/env python3
"""Builds tables (CSV, Markdown) and plots (SVG) from raw data only. No network, no AWS.

    experiments/bin/build_plots.py --run experiments/raw/<experiment>/<run-id> [--out DIR]

Verifies SHA256SUMS first and refuses changed raw data. Output goes to
experiments/derived/<experiment>/<run-id>/ (--out must also lie under experiments/derived/) and
may be regenerated at any time; the same raw data gives byte-identical output. A value that was
not measured is written as `missing`, never as zero, and plot lines break at gaps. Every item in
collection.json `missingInputs` is listed in missing-inputs.csv, and the table or plot it belongs
to keeps its place with `missing` entries. Nothing here is typed by hand and nothing is
interpreted.
"""

import argparse
import csv
import datetime
import json
import os
import sys
from xml.sax.saxutils import escape

import yaml

sys.dont_write_bytecode = True
sys.path.insert(
    0, os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "load-tests"))

import sclib  # noqa: E402
from query_load import RETRIEVAL_MODES, percentile  # noqa: E402

COLOURS = ("#1f77b4", "#d62728", "#2ca02c", "#9467bd", "#ff7f0e", "#8c564b", "#17becf", "#7f7f7f")
MISSING = "missing"
NOT_APPLICABLE = "not applicable"


def verify(run_dir):
    sums = os.path.join(run_dir, "SHA256SUMS")
    if not os.path.isfile(sums):
        raise sclib.ConfigError("no SHA256SUMS in the run directory")
    listed = set()
    with open(sums) as handle:
        for line in handle:
            digest, name = line.rstrip("\n").split("  ", 1)
            path = os.path.join(run_dir, name)
            if not os.path.isfile(path) or sclib.sha256_file(path) != digest:
                raise sclib.ConfigError("raw file missing or changed: " + name)
            listed.add(name)
    present = {os.path.relpath(os.path.join(b, f), run_dir) for b, _, fs in os.walk(run_dir) for f in fs}
    extra = present - listed - {"SHA256SUMS"}
    if extra:
        raise sclib.ConfigError("files not covered by SHA256SUMS: " + ", ".join(sorted(extra)))


def read_json(run_dir, name):
    """The document, or None when the file is absent or cannot be read."""
    path = os.path.join(run_dir, name)
    if not os.path.isfile(path):
        return None
    try:
        with open(path) as handle:
            return json.load(handle)
    except ValueError:  # truncated by a killed run
        return None


def read_lines(run_dir, name):
    """The readable object lines. A half-written line is skipped here; the tables say so."""
    path = os.path.join(run_dir, name)
    rows = []
    if os.path.isfile(path):
        with open(path, errors="replace") as handle:
            for line in handle:
                try:
                    row = json.loads(line)
                except ValueError:
                    continue
                if isinstance(row, dict):
                    rows.append(row)
    return rows


def flagged(items, prefix):
    return any(i == prefix or i.startswith(prefix + ":") for i in items)


def shown(value):
    return MISSING if value is None else value


def write_csv(path, header, rows):
    with open(path, "w", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(header)
        writer.writerows([shown(v) for v in row] for row in rows)


def markdown(header, rows):
    cell = lambda v: str(shown(v)).replace("|", "\\|")
    lines = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    lines += ["| " + " | ".join(cell(v) for v in row) + " |" for row in rows]
    return "\n".join(lines) + "\n"


def absent(sections, title, why):
    sections.append("## %s\n\n%s: %s\n" % (title, MISSING, why))


def table_out(out, name, header, rows):
    """Writes name.csv and returns the same table as Markdown."""
    write_csv(os.path.join(out, name + ".csv"), header, rows)
    return markdown(header, rows)


def missing_row(header, *first):
    return tuple(first) + (None,) * (len(header) - len(first))


def missing_svg(path, title):
    """A plot with nothing to draw: it exists and says so, and has no axis that implies a value."""
    with open(path, "w") as handle:
        handle.write('<svg xmlns="http://www.w3.org/2000/svg" width="900" height="80" font-family="sans-serif" '
                     'font-size="14">\n<rect width="100%%" height="100%%" fill="white"/>\n'
                     '<text x="70" y="30">%s</text>\n<text x="70" y="56">%s: no datapoints</text>\n</svg>\n'
                     % (escape(title), MISSING))


def segments(points):
    """The unbroken runs of [(time, value or None)]."""
    run = []
    for t, v in points:
        if v is None:
            if run:
                yield run
            run = []
        else:
            run.append((t, v))
    if run:
        yield run


def svg_chart(path, title, series, start, end, ylabel):
    """Step-free line chart with points. series: [(label, [(datetime, value or None)])]. A None
    value is a gap: no line crosses it."""
    width, height, left, right, top = 900, 360, 70, 20, 30
    plot_h = 250
    span = max((end - start).total_seconds(), 1.0)
    peak = max((v for _, points in series for _, v in points if v is not None), default=None)
    scale = peak if peak is not None and peak > 0 else 1.0
    x = lambda t: left + (t - start).total_seconds() / span * (width - left - right)
    y = lambda v: top + plot_h - v / scale * plot_h
    out = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" font-family="sans-serif" font-size="11">'
           % (width, height + 16 * len(series)),
           '<rect width="100%" height="100%" fill="white"/>',
           '<text x="%d" y="18" font-size="14">%s</text>' % (left, escape(title))]
    # with nothing measured there is no value axis to draw
    for i in range(5 if peak is not None else 0):
        value = scale * i / 4
        out.append('<line x1="%d" x2="%d" y1="%.1f" y2="%.1f" stroke="#ddd"/>' % (left, width - right, y(value), y(value)))
        out.append('<text x="%d" y="%.1f" text-anchor="end">%.4g</text>' % (left - 6, y(value) + 4, value))
    for i in range(6):
        moment = start + datetime.timedelta(seconds=span * i / 5)
        out.append('<text x="%.1f" y="%d" text-anchor="middle">%s</text>' % (x(moment), top + plot_h + 18,
                                                                             moment.strftime("%H:%M:%S")))
    out.append('<text x="14" y="%d" transform="rotate(-90 14 %d)" text-anchor="middle">%s</text>'
               % (top + plot_h / 2, top + plot_h / 2, escape(ylabel)))
    out.append('<text x="%d" y="%d" text-anchor="middle">UTC, %s</text>'
               % (width / 2, top + plot_h + 36, start.strftime("%Y-%m-%d")))
    for index, (label, points) in enumerate(series):
        colour = COLOURS[index % len(COLOURS)]
        measured = 0
        for run in segments(points):
            measured += len(run)
            if len(run) > 1:
                out.append('<polyline fill="none" stroke="%s" stroke-width="1.5" points="%s"/>'
                           % (colour, " ".join("%.1f,%.1f" % (x(t), y(v)) for t, v in run)))
            out += ['<circle cx="%.1f" cy="%.1f" r="2" fill="%s"/>' % (x(t), y(v), colour) for t, v in run]
        note = " (missing: no datapoints)" if not measured else (" (has gaps)" if measured < len(points) else "")
        out.append('<text x="%d" y="%d" fill="%s">%s%s</text>'
                   % (left, height - 14 + 16 * index, colour, escape(label), note))
    out.append("</svg>")
    with open(path, "w") as handle:
        handle.write("\n".join(out) + "\n")


# metric-id prefix -> (file, title, y label); one chart per group so units are not mixed
CHARTS = (
    ("tasks_", "ecs-tasks", "ECS tasks", "tasks"),
    ("cpu_", "ecs-cpu", "ECS CPU utilization", "percent"),
    ("mem_", "ecs-memory", "ECS memory utilization", "percent"),
    ("sqs_depth_", "sqs-depth", "SQS messages", "messages"),
    ("sqs_age_", "sqs-age", "Age of oldest message", "seconds"),
    ("sqs_flow_", "sqs-flow", "SQS messages per minute", "messages"),
    ("alb_latency_", "alb-latency", "ALB target response time", "seconds"),
    ("alb_count_", "alb-requests", "ALB requests and errors per minute", "count"),
    ("emr_nodes_", "emr-capacity", "EMR capacity (instances)", "instances"),
    ("emr_load_", "emr-load", "EMR load", "value"),
    ("ddb_", "dynamodb", "DynamoDB", "count"),
    ("neptune_", "neptune", "Neptune", "value"),
)


def on_grid(points, start, end, period):
    """[(time, value or None)] with an explicit None for every period of the window without a datapoint."""
    measured = dict(points)
    moment = start
    gaps = []
    while moment < end:
        if not any(0 <= (t - moment).total_seconds() < period for t in measured):
            gaps.append((moment, None))
        moment += datetime.timedelta(seconds=period)
    return sorted(list(measured.items()) + gaps, key=lambda point: point[0])


def metric_outputs(run_dir, out, sections, manifest):
    doc = read_json(run_dir, "metrics.json")
    header = ("id", "metric", "stat", "status", "datapoints", "min", "max")
    value_header = ("timestampUtc", "id", "namespace", "metric", "stat", "value")
    if not doc:
        absent(sections, "Metrics", "no metrics.json in the raw data")
        specs = manifest.get("metrics") or []
        write_csv(os.path.join(out, "metrics.csv"), value_header,
                  [missing_row(value_header, None, s["id"]) for s in specs])
        sections.append(table_out(out, "metrics-summary", header,
                                  [missing_row(header, s["id"], s.get("metric"), s.get("stat")) for s in specs]))
        return
    start, end = sclib.parse_iso(doc["startUtc"]), sclib.parse_iso(doc["endUtc"])
    period = doc["periodSeconds"]
    rows, summary, grids = [], [], {}
    for series in doc["series"]:
        values = series["values"]
        points = [(sclib.parse_iso(t), v) for t, v in zip(series["timestamps"], values)]
        grids[series["id"]] = grid = on_grid(points, start, end, period)
        rows += [(t, (t.strftime("%Y-%m-%dT%H:%M:%SZ"), series["id"], series["namespace"], series["metric"],
                      series["stat"], v)) for t, v in grid]
        summary.append((series["id"], series["metric"], series["stat"], series["status"],
                        len(values) or None, min(values) if values else None, max(values) if values else None))
    write_csv(os.path.join(out, "metrics.csv"), value_header,
              [row for _, row in sorted(rows, key=lambda r: (r[0], r[1][1]))])
    sections.append("## Metrics (%s to %s, %d s period)\n\n%s" % (
        doc["startUtc"], doc["endUtc"], period, table_out(out, "metrics-summary", header, summary)))
    for prefix, name, title, ylabel in CHARTS:
        group = [(s["id"], grids[s["id"]]) for s in doc["series"] if s["id"].startswith(prefix)]
        if group:
            svg_chart(os.path.join(out, name + ".svg"), title, group, start, end, ylabel)


def request_row(label, rows):
    if not rows:  # planned and never sent, or its records are missing
        return missing_row(REQUEST_HEADER, label)
    ok = [r for r in rows if r["status"] == 200]
    latencies = [r["latencyMs"] for r in ok]
    starts = [sclib.parse_iso(r["startedAtUtc"]) for r in rows]
    wall = (max(s + datetime.timedelta(milliseconds=r["latencyMs"]) for s, r in zip(starts, rows))
            - min(starts)).total_seconds()
    limited = sum(r["status"] == 429 for r in rows)
    errors = sum(r["status"] == 0 or r["status"] >= 500 for r in rows)
    answered = [r for r in ok if r.get("abstained") is not None]
    return (label, len(rows), len(ok), limited, errors, len(rows) - len(ok) - limited - errors,
            sum(r["abstained"] is True for r in answered) if answered or not ok else None,
            percentile(latencies, 0.50), percentile(latencies, 0.95), percentile(latencies, 0.99),
            round(len(ok) / wall, 3) if wall > 0 else None,
            round(errors / len(rows), 4),
            max((r["inFlightAtStart"] for r in rows if r.get("inFlightAtStart") is not None), default=None))


REQUEST_HEADER = ("group", "requests", "ok200", "http429", "errors5xxOrTransport", "otherStatus",
                  "abstained", "p50Ms", "p95Ms", "p99Ms", "ok200PerSecond", "errorRate", "maxInFlight")
PHASE_HEADER = REQUEST_HEADER + ("dataStatus",)
ANSWER_FIELDS = ("claimCount", "evidenceCount", "removedClaimCount")
MODE_HEADER = REQUEST_HEADER + ("meanClaims", "meanEvidenceIds", "removedClaims", "ok200WithoutClaimCount",
                                "ok200WithoutEvidenceCount", "ok200WithoutRemovedClaimCount", "dataStatus")
LOAD_HEADER = ("phase", "concurrency", "planned", "completed", "endedBy", "meanMs", "p50Ms", "p95Ms", "maxMs")
RECORDS_MISSING = "records missing"


def planned_phases(run, manifest):
    """The planned phases, from run.json; without it, from the manifest the run was made with."""
    if run.get("plannedPhases"):
        return run["plannedPhases"]
    load = manifest.get("load") or {}
    preflight = {"phase": "preflight", "concurrency": 1, "planned": None}
    if load.get("kind") == "levels":
        return [preflight] + [{"phase": "level-%d" % n, "concurrency": n, "planned": load.get("requests_per_level")}
                              for n in load.get("levels") or []]
    if load.get("kind") == "retrieval_modes":
        return [preflight, {"phase": "modes", "concurrency": load.get("concurrency"), "planned": None}]
    return []


def request_outputs(run_dir, out, sections, run, manifest, expected, items):
    if not expected:
        sections.append("## Requests by phase\n\n%s (not a request load)\n" % NOT_APPLICABLE)
        return
    rows = read_lines(run_dir, "runner/requests.jsonl")
    planned = [p["phase"] for p in planned_phases(run, manifest)]
    done = {p["phase"]: p.get("completed") for p in run.get("phases") or []}
    short = flagged(items, "runner:file:requests.jsonl")
    if not rows and not planned:
        absent(sections, "Requests by phase", "no requests recorded in the raw data")
        sections.append(table_out(out, "requests-by-phase", PHASE_HEADER, [missing_row(PHASE_HEADER)]))
        return
    rows.sort(key=lambda r: r["sequence"])
    phases = list(dict.fromkeys(planned + [r["phase"] for r in rows]))
    table = []
    for phase in phases:
        group = [r for r in rows if r["phase"] == phase]
        completed = done.get(phase)
        incomplete = (short and completed is None) or (completed is not None and len(group) < completed)
        status = None if not group else RECORDS_MISSING if incomplete else "complete"
        table.append(request_row(phase, group) + (status,))
    sections.append("## Requests by phase\n\nLatency percentiles are nearest-rank over HTTP 200 responses, "
                    "measured at the client. A planned phase with no request reads %s.\n\n" % MISSING
                    + table_out(out, "requests-by-phase", PHASE_HEADER, table))
    modes = {r["retrievalMode"] for r in rows if r.get("retrievalMode") and r["phase"] != "preflight"}
    if (manifest.get("load") or {}).get("kind") == "retrieval_modes":
        modes |= set(RETRIEVAL_MODES)
    if modes:
        table = []
        for mode in sorted(modes):
            group = [r for r in rows if r.get("retrievalMode") == mode and r["phase"] != "preflight"]
            if not group:
                table.append(missing_row(MODE_HEADER, mode))
                continue
            ok = [r for r in group if r["status"] == 200]
            known = {key: [r[key] for r in ok if r.get(key) is not None] for key in ANSWER_FIELDS}
            mean = lambda key: round(sum(known[key]) / len(known[key]), 3) if known[key] else None
            row = list(request_row(mode, group))
            # the modes are interleaved in one phase: wall time cannot be split between them
            row[REQUEST_HEADER.index("ok200PerSecond")] = NOT_APPLICABLE
            removed = known["removedClaimCount"]
            table.append(tuple(row) + (mean("claimCount"), mean("evidenceCount"),
                                       sum(removed) if removed else None)
                         + tuple(len(ok) - len(known[key]) for key in ANSWER_FIELDS)
                         + (RECORDS_MISSING if short else "complete",))
        sections.append("## Requests by retrieval mode\n\nMeans and sums are over the responses that carried the "
                        "value; the ok200Without columns count those that did not. Throughput per mode is %s: the "
                        "modes share one interleaved phase.\n\n" % NOT_APPLICABLE
                        + table_out(out, "requests-by-retrieval-mode", MODE_HEADER, table))


def load_outputs(out, sections, run, manifest, expected):
    """Planned against completed load, from run.json."""
    if not expected:
        return
    recorded = bool(run.get("plannedPhases"))
    done = {p["phase"]: p for p in run.get("phases") or []}
    table = []
    for plan in planned_phases(run, manifest):
        phase = done.get(plan["phase"])
        latency = (phase or {}).get("observedLatencyMs") or {}
        if phase is None:
            ended = "not run" if recorded else None  # without run.json nobody knows whether it ran
        else:
            ended = phase.get("endedBy") or ("all requests sent" if phase["completed"] >= phase["planned"] else None)
        table.append((plan["phase"], plan["concurrency"], plan["planned"], (phase or {}).get("completed"), ended,
                      latency.get("mean"), latency.get("p50"), latency.get("p95"), latency.get("max")))
    text = table_out(out, "load-plan", LOAD_HEADER, table or [missing_row(LOAD_HEADER)])
    if recorded:
        sections.append("## Planned and completed load\n\n" + text)
    else:
        absent(sections, "Planned and completed load",
               "run.json has no plannedPhases; the plan below is the manifest's and what ran is not known")
        sections.append(text)


SEND_HEADER = ("kind", "planned", "records", "accepted", "refused", "outcomeUnknown", "dataStatus")


def send_outputs(run_dir, out, sections, run, manifest, expected, items):
    if not expected:
        return
    queue = manifest.get("queue") or {}
    planned = {}
    if queue.get("scenario") == "backlog":
        planned = {"valid": queue.get("messages")}
    elif queue.get("scenario") == "poison":
        planned = {"poison_undecodable": queue.get("poison_undecodable"),
                   "poison_unknown_run": queue.get("poison_unknown_run"), "valid": queue.get("valid_duplicates")}
    rows = read_lines(run_dir, "runner/sends.jsonl")
    present = os.path.isfile(os.path.join(run_dir, "runner", "sends.jsonl"))
    short = flagged(items, "runner:file:sends.jsonl")
    # an empty file is a measured zero only when run.json says nothing was sent
    nothing_sent = present and not rows and not short and run.get("messagesAccepted") == 0
    table = []
    for kind in list(dict.fromkeys(list(planned) + [r["kind"] for r in rows])) or [None]:
        group = [r for r in rows if r["kind"] == kind]
        if not group and not nothing_sent:
            table.append(missing_row(SEND_HEADER, kind, planned.get(kind)))
            continue
        table.append((kind, planned.get(kind), len(group), sum(r["accepted"] is True for r in group),
                      sum(r["accepted"] is False for r in group), sum(r["accepted"] is None for r in group),
                      RECORDS_MISSING if short else "complete"))
    sections.append("## Messages sent\n\n" + table_out(out, "sends", SEND_HEADER, table))


def observation_outputs(run_dir, out, sections, expected, items):
    if not expected:
        sections.append("## Queue observations\n\n%s (not a queue scenario)\n" % NOT_APPLICABLE)
        return
    rows = read_lines(run_dir, "runner/observations.jsonl")
    values = ("visible", "inFlight", "dlqVisible", "desired", "running", "pending")
    keys = ("atUtc", "phase") + values + ("error",)
    table = [[r.get(k, "" if k == "error" else None) for k in keys] for r in rows]
    if not rows or flagged(items, "runner:file:observations.jsonl"):
        table.append(list(missing_row(keys)))
    write_csv(os.path.join(out, "queue-timeline.csv"), keys, table)
    charts = (("queue-timeline.svg", "Queue depth (polled)", ("visible", "inFlight", "dlqVisible"), "messages"),
              ("worker-timeline.svg", "Embedding-worker tasks (polled)", ("desired", "running", "pending"), "tasks"))
    if not rows:
        absent(sections, "Queue observations", "no observations in the raw data")
        for name, title, _, _ in charts:
            missing_svg(os.path.join(out, name), title)
        return
    failed = sum("error" in r for r in rows)
    sections.append("## Queue observations\n\n" + markdown(("polls", "failedPolls"), [(len(rows), failed)]))
    start, end = sclib.parse_iso(rows[0]["atUtc"]), sclib.parse_iso(rows[-1]["atUtc"])
    for name, title, fields, ylabel in charts:
        svg_chart(os.path.join(out, name), title,
                  [(f, [(sclib.parse_iso(r["atUtc"]), r.get(f)) for r in rows]) for f in fields], start, end, ylabel)


def stage_outputs(run_dir, out, sections):
    stages = read_lines(run_dir, "workflow_stages.jsonl")
    requests = read_lines(run_dir, "runner/requests.jsonl")
    # a stage line is attributed to a phase and mode through the trace id of its request
    by_trace = {r["traceId"]: r for r in requests if r.get("traceId")}
    measured = [(by_trace[s["traceId"]], s) for s in stages if s["traceId"] in by_trace]
    mode_of = lambda r: r.get("retrievalMode") or NOT_APPLICABLE
    used = [(mode_of(r), s) for r, s in measured if r["phase"] != "preflight"]
    header = ("retrievalMode", "stage", "samples", "p50Ms", "p95Ms", "maxMs")
    table = []
    for mode, stage in sorted({(m, s["stage"]) for m, s in used}):
        values = [s["latencyMs"] for m, s in used if (m, s["stage"]) == (mode, stage)]
        table.append((mode, stage, len(values), percentile(values, 0.5), percentile(values, 0.95), max(values)))
    with_lines = {s["traceId"] for s in stages}
    unseen = [r for r in requests
              if r.get("traceId") and r.get("status") == 200 and r["traceId"] not in with_lines]
    table += [missing_row(header, m) for m in sorted(
        {mode_of(r) for r in unseen if r["phase"] != "preflight"} - {m for m, _ in used})]
    if not used:
        absent(sections, "Workflow stage latency", "no stage line belongs to a measured request of this run")
        sections.append(table_out(out, "stage-latency", header, table or [missing_row(header)]))
    else:
        sections.append("## Workflow stage latency (from service logs)\n\nPreflight requests are excluded (%d "
                        "lines); %d lines match no request of this run.\n\n"
                        % (len(measured) - len(used), len(stages) - len(measured))
                        + table_out(out, "stage-latency", header, table))
    if unseen:
        header = ("traceId", "phase", "retrievalMode", "stageLines")
        sections.append("## Answered requests without a stage line\n\nThe stage table does not cover them.\n\n"
                        + table_out(out, "stage-lines-missing", header,
                                    [(r["traceId"], r["phase"], mode_of(r), MISSING) for r in unseen]))


def worker_outputs(run_dir, out, sections):
    outcomes = read_lines(run_dir, "worker_outcomes.jsonl")
    batches = [o for o in outcomes if o["event"] == "batch"]
    table = [(k, sum(b[k] for b in batches) if batches else None)
             for k in ("received", "acknowledged", "retried", "deadLettered")]
    codes = [o["code"] for o in outcomes if o["event"] == "reject"]
    table += [("reject " + c, codes.count(c)) for c in sorted(set(codes))]
    text = table_out(out, "worker-outcomes", ("outcome", "count"), table)
    if not outcomes:
        absent(sections, "Worker outcomes", "no worker log line in the raw data")
        sections.append(text)
        return
    sections.append("## Worker outcomes (from service logs)\n\n" + text)


def log_outputs(run_dir, out, sections, collection, kinds):
    logs = collection.get("logs") or []
    if not kinds and not logs:
        sections.append("## Log sources\n\n%s (the manifest names no log)\n" % NOT_APPLICABLE)
        return
    by_kind = {entry["kind"]: entry for entry in logs}
    header = ("kind", "status", "rows")
    table = [(k, by_kind.get(k, {}).get("status"), by_kind.get(k, {}).get("rows"))
             for k in sorted(set(kinds) | set(by_kind))]
    sections.append("## Log sources\n\n" + table_out(out, "log-sources", header, table))
    names = {row[0] for row in table}
    if "workflow_stages" in names:
        stage_outputs(run_dir, out, sections)
    if "worker_outcomes" in names:
        worker_outputs(run_dir, out, sections)


def as_json(value):
    return None if value is None else json.dumps(value, sort_keys=True)


def runner_file_outputs(run_dir, out, sections, record, files, items):
    if record.get("runner") == "pipeline" or not files:
        return
    table = []
    for name in files:
        problems = [i for i in items if i.startswith("runner:file:%s:" % name)]
        if not os.path.isfile(os.path.join(run_dir, "runner", name)):
            state = MISSING
        elif problems:
            state = "present, incomplete: " + ", ".join(p.rsplit(":", 1)[1] for p in problems)
        else:
            state = "present"
        table.append((name, state))
    sections.append("## Runner files\n\n" + table_out(out, "runner-files", ("file", "status"), table))


def deployment_rows(deployment, manifest):
    """One row per service the manifest lists or the collector saw."""
    found = {s["service"]: s for s in deployment.get("services") or []}
    listed = (manifest.get("collect") or {}).get("deployed_services") or []
    rows = []
    for name in dict.fromkeys(list(listed) + list(found)):
        service = found.get(name) or {}
        tags = ",".join(t or "untagged" for t in service.get("imageTags") or []) or None
        rows.append((name, service.get("revision"), tags))
    return rows


def deployment_outputs(out, sections, deployment, manifest):
    rows = deployment_rows(deployment, manifest)
    if rows:
        sections.append("## Deployed services (at collection time)\n\n"
                        + table_out(out, "deployment", ("service", "revision", "imageTags"), rows))


def pipeline_outputs(out, sections, record, pipeline):
    if record.get("runner") != "pipeline" and not pipeline:
        return
    fields = [(k, pipeline.get(k)) for k in ("status", "commitSha", "startedAtUtc", "endedAtUtc", "executionName")]
    fields.append(("stages", ",".join(pipeline.get("stages") or []) or None))
    snapshots = pipeline.get("datasetSnapshots") or {}
    fields += [("datasetSnapshot " + n, (snapshots[n] or {}).get("date")) for n in sorted(snapshots)]
    if not snapshots:
        fields.append(("datasetSnapshots", None))
    sections.append("## Pipeline run\n\n" + table_out(out, "pipeline-run", ("field", "value"), fields))


def missing_input_outputs(out, sections, missing):
    header = ("item", "detail")
    table = [(m["item"], m["detail"]) for m in missing]
    write_csv(os.path.join(out, "missing-inputs.csv"), header, table)
    sections.append("## Missing inputs\n\n" + (markdown(header, table) if table else "none recorded\n"))


def run_facts(record, collection, run, pipeline, deployment, manifest):
    facts = [(k, record.get(k)) for k in ("experiment", "environment", "region", "commitSha", "startedAtUtc",
                                          "endedAtUtc", "runnerExitCode", "timedOut")]
    stop = run.get("stopReason") or ("none" if run.get("status") else None)
    missing = collection.get("missingInputs") or []
    # a manifest taken from the working tree does not say what the run used
    unknown_manifest = any(m["item"] == "manifest:at-run-commit" for m in missing)
    facts += [("runId", collection.get("runId")),
              ("datasetSnapshot", None if unknown_manifest else as_json(collection.get("datasetSnapshot"))),
              ("manifestSource", collection.get("manifestSource")),
              ("resourceLimits", as_json(record.get("resourceLimits"))),
              ("missingInputs", "%d, listed under Missing inputs" % len(missing) if missing else "none recorded")]
    if record.get("runner") != "pipeline":
        facts += [("runnerStatus", run.get("status")), ("stopReason", stop)]
    if "queue" in manifest:
        evidence = run.get("vectorWriteEvidence") or {}
        facts += [(k, run.get(k)) for k in ("messagesPlanned", "messagesAccepted", "messagesRefused",
                                            "messagesOutcomeUnknown")]
        facts += [("vectorWriteEvidence", evidence.get("status")),
                  ("newSucceededPutBatches", evidence.get("newSucceededPutBatches")),
                  ("newFailedPutBatches", evidence.get("newFailedPutBatches")),
                  ("milestones", as_json(run.get("milestones") or None))]
    if "load" in manifest:
        facts += [(k, run.get(k)) for k in ("requestsSent", "estimatedSpendUsd", "costCapUsd")]
    facts.append(("pipelineStatus", pipeline.get("status") if record.get("runner") == "pipeline" or pipeline
                  else NOT_APPLICABLE))
    images = "; ".join("%s=%s" % (name, shown(tags)) for name, _, tags in deployment_rows(deployment, manifest))
    status = deployment.get("status")
    facts += [("imageTagsAtCollectionTime", NOT_APPLICABLE if status == "NOT_REQUESTED" else images or None),
              ("imageTagsObservedAtUtc", deployment.get("observedAtUtc")),
              ("imageTagsStatus", status)]
    return facts


def build(run_dir, out):
    verify(run_dir)
    record = read_json(run_dir, "record.json") or {}
    collection = read_json(run_dir, "collection.json") or {}
    run = read_json(run_dir, "runner/run.json")
    run = run if isinstance(run, dict) else {}
    pipeline = read_json(run_dir, "pipeline-run.json") or {}
    deployment = read_json(run_dir, "deployment.json") or {}
    manifest_path = os.path.join(run_dir, "manifest.yaml")
    manifest = {}
    if os.path.isfile(manifest_path):
        with open(manifest_path) as handle:
            manifest = yaml.safe_load(handle) or {}
    # what the experiment should have produced: from its manifest, else from what the tool says
    is_load = "load" in manifest or run.get("tool") == "query_load"
    is_queue = "queue" in manifest or run.get("tool") == "queue_scenario"
    kinds = [spec["kind"] for spec in (manifest.get("collect") or {}).get("logs") or []]
    missing = collection.get("missingInputs") or []
    items = [m["item"] for m in missing]
    files = ("run.json", "requests.jsonl") if is_load else (
        ("run.json", "sends.jsonl", "observations.jsonl") if is_queue else ())
    os.makedirs(out, exist_ok=True)
    facts = run_facts(record, collection, run, pipeline, deployment, manifest)
    sections = ["# Run %s\n\nGenerated by build_plots.py from raw data. Do not edit. `%s` means not measured.\n"
                % (shown(collection.get("runId")), MISSING),
                "## Run\n\n" + markdown(("field", "value"), facts)]
    missing_input_outputs(out, sections, missing)
    runner_file_outputs(run_dir, out, sections, record, files, items)
    pipeline_outputs(out, sections, record, pipeline)
    deployment_outputs(out, sections, deployment, manifest)
    load_outputs(out, sections, run, manifest, is_load)
    request_outputs(run_dir, out, sections, run, manifest, is_load, items)
    send_outputs(run_dir, out, sections, run, manifest, is_queue, items)
    observation_outputs(run_dir, out, sections, is_queue, items)
    log_outputs(run_dir, out, sections, collection, kinds)
    metric_outputs(run_dir, out, sections, manifest)
    with open(os.path.join(out, "summary.md"), "w") as handle:
        handle.write("\n".join(sections))


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--run", required=True)
    parser.add_argument("--out")
    args = parser.parse_args(argv)
    run_dir = os.path.abspath(args.run)
    out = os.path.abspath(args.out) if args.out else os.path.join(
        sclib.REPO_ROOT, "experiments", "derived", os.path.basename(os.path.dirname(run_dir)),
        os.path.basename(run_dir))
    try:
        derived = os.path.join(os.path.realpath(sclib.REPO_ROOT), "experiments", "derived")
        if not os.path.realpath(out).startswith(derived + os.sep):
            raise sclib.ConfigError("derived output must lie under experiments/derived/")
        build(run_dir, os.path.realpath(out))
    except sclib.ConfigError as error:
        return sclib.fail(str(error))
    sclib.say("derived outputs: " + out)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
