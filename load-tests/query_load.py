#!/usr/bin/env python3
"""Bounded POST /v1/query load generator (experiments D and E).

    load-tests/query_load.py --manifest experiments/manifests/<name>.yaml

Run through run-experiment.sh (runner: load-test), which sets SC_EXPERIMENT_OUT and SC_REGION.
Refuses unless the manifest is approved and not blocked (sclib.approval_problems), run directly or
not. Required environment: SCHOLARCHAIN_APPROVED_ENVS, SC_BASE_URL, SCHOLARCHAIN_API_TOKEN (never
printed or written), SC_EXPERIMENT_OUT, SC_REGION. Plain http to a host other than localhost needs
SC_ALLOW_PLAINTEXT_HTTP=1 (the bearer token then crosses the network unencrypted).

Stops on the manifest's error-rate, auth, rate-limit, request, time and cost caps. A level that
reaches level_max_seconds is recorded as incomplete and the next level still runs. The cost cap is
an ESTIMATE: requests sent x load.estimated_usd_per_request, against cost_cap_usd. It is not a bill.

Writes requests.jsonl (one line per request: latency, status, traceId) and run.json.
No URL, token or question text is written. Exit 0 only when the planned load completed.
"""

import argparse
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import sclib  # noqa: E402

# No manifest may ask for more requests than this, whatever it says.
ABSOLUTE_REQUEST_CEILING = 1000
ABSOLUTE_CONCURRENCY_CEILING = 50
ABSOLUTE_RATE_CEILING = 600  # requests per minute
ABSOLUTE_REQUEST_TIMEOUT_CEILING = 120  # seconds, total per request
ABSOLUTE_TIME_CEILING = 3600  # seconds, for a level and for the whole load
# Stops that also cancel requests already reserved but not yet sent.
FAILURE_STOPS = ("AUTH_FAILURES", "ERROR_RATE", "RATE_LIMITED", "INTERNAL_ERROR")
SEMANTIC = "SEMANTIC_EXPLANATION"
RETRIEVAL_MODES = ("graph_only", "vector_only", "fused")


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None  # a redirect would carry the token to another URL


def build_body(question, window, mode, limits):
    """The request body of one question in one retrieval mode.

    The API has no retrieval-mode switch: the mode is expressed through `operations`.
    graph_only omits SEMANTIC_EXPLANATION, vector_only asks for it alone, fused asks for both.
    A question marked semantic=false stays graph-only in D (mode None)."""
    graph_ops = list(question["graphOperations"])
    if mode is None:
        operations = graph_ops + ([SEMANTIC] if question.get("semantic") else [])
    elif mode == "graph_only":
        operations = graph_ops
    elif mode == "vector_only":
        operations = [SEMANTIC]
    elif mode == "fused":
        operations = graph_ops + [SEMANTIC]
    else:
        raise sclib.ConfigError("unknown retrieval mode " + str(mode))
    return {
        "question": question["text"],
        "operations": operations,
        "researcherIds": question["researcherIds"],
        "windowStartYear": window["startYear"],
        "windowEndYear": window["endYear"],
        "graphLimit": limits["graphLimit"],
        "vectorLimit": limits["vectorLimit"] if SEMANTIC in operations else 0,
        "mode": "sync",
    }


def percentile(values, fraction):
    """Nearest-rank percentile."""
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, -(-int(round(fraction * 1000)) * len(ordered) // 1000))
    return ordered[min(rank, len(ordered)) - 1]


class Client:
    def __init__(self, base_url, token, timeout):
        parsed = urllib.parse.urlsplit(base_url)
        local = parsed.hostname in ("localhost", "127.0.0.1", "::1")
        if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username:
            raise sclib.ConfigError("SC_BASE_URL must be an http(s) URL without credentials")
        if parsed.scheme == "http" and not local and os.environ.get("SC_ALLOW_PLAINTEXT_HTTP") != "1":
            raise sclib.ConfigError("plain http needs SC_ALLOW_PLAINTEXT_HTTP=1")
        # http.client quotes a bad header value in its exception, so the token is checked first
        if not re.fullmatch(r"[\x21-\x7e]+", token):
            raise sclib.ConfigError("SCHOLARCHAIN_API_TOKEN must be one line of printable ASCII without spaces")
        self._url = base_url.rstrip("/") + "/v1/query"
        self._token = token
        self._timeout = timeout
        # no environment proxy: the token goes to the configured host only
        self._opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect())

    def _exchange(self, request):
        """(status, headers, payload, transport error class). Never raises: an exception text can
        name the host or quote a header."""
        try:
            try:
                with self._opener.open(request, timeout=self._timeout) as response:
                    return response.status, response.headers, response.read(), None
            except urllib.error.HTTPError as error:
                try:
                    payload = error.read()
                except Exception:
                    payload = b""
                return error.code, error.headers, payload, None
        except Exception as error:
            reason = getattr(error, "reason", error)
            name = type(reason).__name__ if isinstance(reason, BaseException) else type(error).__name__
            return 0, {}, b"", name

    def post(self, body, trace_id):
        request = urllib.request.Request(
            self._url,
            data=json.dumps(body).encode(),
            method="POST",
            headers={
                "Authorization": "Bearer " + self._token,
                "Content-Type": "application/json",
                "X-Trace-Id": trace_id,
            },
        )
        started = time.monotonic()
        box = []
        exchange = threading.Thread(target=lambda: box.append(self._exchange(request)), daemon=True)
        exchange.start()
        exchange.join(self._timeout)  # a total deadline: the socket timeout is only per operation
        status, headers, payload, transport = box[0] if box else (0, {}, b"", "TotalTimeout")
        latency = round((time.monotonic() - started) * 1000.0, 3)
        try:
            doc = json.loads(payload.decode()) if payload else {}
        except (ValueError, UnicodeDecodeError):
            doc = {}
        doc = doc if isinstance(doc, dict) else {}
        error = doc.get("error") if isinstance(doc.get("error"), dict) else {}
        answer = doc.get("answer") if isinstance(doc.get("answer"), dict) else {}
        claims = answer.get("claims")
        evidence = doc.get("evidenceIds")
        return {
            "status": status,
            "latencyMs": latency,
            "traceId": (headers.get("x-trace-id") if headers else None) or doc.get("traceId"),
            "transportError": transport,
            "errorCode": error.get("code"),
            "errorReason": error.get("reason"),
            "queryId": doc.get("queryId"),
            "abstained": answer.get("abstained"),
            "claimCount": len(claims) if isinstance(claims, list) else None,
            "evidenceCount": len(evidence) if isinstance(evidence, list) else None,
            "verification": doc.get("verification"),
            "removedClaimCount": doc.get("removedClaimCount"),
        }


class Runner:
    def __init__(self, client, load, writer, stop, run_tag, deadline, cost_cap_usd=None):
        self.client, self.load, self.writer, self.stop = client, load, writer, stop
        self.run_tag, self.deadline = run_tag, deadline
        self.lock = threading.Lock()
        self.sent = 0
        self.results = []
        self.auth_streak = 0
        self.abort_reason = None
        self.next_slot = 0.0
        self.in_flight = 0
        self.interval = 60.0 / load["max_requests_per_minute"]
        self.cost_cap_usd = cost_cap_usd
        self.usd_per_request = load.get("estimated_usd_per_request")
        self.time_refusal = None  # which time cap last refused a request

    def _claim(self, level_deadline):
        """Reserves the next request: its sequence number, or None when no more may be sent."""
        with self.lock:
            if self.abort_reason or self.stop.is_set():
                return None
            if self.sent >= self.load["max_total_requests"]:
                self.abort_reason = "REQUEST_CAP_REACHED"
                return None
            if self.cost_cap_usd is not None and (self.sent + 1) * self.usd_per_request > self.cost_cap_usd + 1e-9:
                self.abort_reason = "COST_CAP_REACHED"
                return None
            now = time.monotonic()
            slot = max(now, self.next_slot)
            if slot >= min(self.deadline, level_deadline):
                self.time_refusal = "LOAD_TIME_CAP" if slot >= self.deadline else "LEVEL_TIME_CAP"
                return None
            self.next_slot = slot + self.interval
            self.sent += 1
            sequence = self.sent
        stopped = self.stop.wait(max(0.0, slot - time.monotonic()))
        with self.lock:
            # a stop that tripped while this request waited for its slot cancels it
            if stopped or self.abort_reason in FAILURE_STOPS:
                self.sent -= 1
                return None
        return sequence

    def send(self, sequence, phase, concurrency, question_id, mode, body):
        trace_id = "sc-%s-%05d" % (self.run_tag, sequence)
        with self.lock:
            self.in_flight += 1
            in_flight = self.in_flight
        started = sclib.utc_now()
        try:
            record = self.client.post(body, trace_id)
        finally:
            with self.lock:
                self.in_flight -= 1
        record.update(
            sequence=sequence, phase=phase, concurrency=concurrency, questionId=question_id,
            retrievalMode=mode, traceIdSent=trace_id, startedAtUtc=sclib.iso(started),
            inFlightAtStart=in_flight,
        )
        self.writer.write(record)
        with self.lock:
            self.results.append(record)
            self._check_stop(record)
        return record

    def _check_stop(self, record):
        rules = self.load["stop"]
        self.auth_streak = self.auth_streak + 1 if record["status"] in (401, 403) else 0
        if self.auth_streak >= rules["max_consecutive_auth_failures"]:
            self.abort_reason = self.abort_reason or "AUTH_FAILURES"
        measured = [r for r in self.results if r["phase"] != "preflight"]
        if len(measured) >= rules["error_rate_min_samples"]:
            window = measured[-rules["error_rate_min_samples"]:]
            errors = sum(1 for r in window if r["status"] == 0 or r["status"] >= 500)
            limited = sum(1 for r in window if r["status"] == 429)
            if errors / len(window) > rules["max_error_rate"]:
                self.abort_reason = self.abort_reason or "ERROR_RATE"
            if limited / len(window) > rules["max_rate_limited_rate"]:
                self.abort_reason = self.abort_reason or "RATE_LIMITED"

    def phase(self, name, concurrency, jobs, max_seconds):
        """Sends `jobs` [(questionId, mode, body)] with `concurrency` clients."""
        level_deadline = time.monotonic() + max_seconds
        self.time_refusal = None
        queue = iter(jobs)
        queue_lock = threading.Lock()
        done = [0]

        def worker():
            while True:
                with queue_lock:
                    job = next(queue, None)
                if job is None:
                    return
                sequence = self._claim(level_deadline)
                if sequence is None:
                    return
                try:
                    self.send(sequence, name, concurrency, *job)
                except Exception as error:  # class name only, and no thread traceback
                    sclib.fail("a request could not be recorded (%s)" % type(error).__name__)
                    with self.lock:
                        self.abort_reason = self.abort_reason or "INTERNAL_ERROR"
                    return
                with queue_lock:
                    done[0] += 1

        threads = [threading.Thread(target=worker, daemon=True) for _ in range(concurrency)]
        started = sclib.utc_now()
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        latencies = [r["latencyMs"] for r in self.results if r["phase"] == name and r["status"] == 200]
        return {
            "phase": name, "concurrency": concurrency, "planned": len(jobs), "completed": done[0],
            "endedBy": self.time_refusal if done[0] < len(jobs) else None,
            "observedLatencyMs": {
                "ok200": len(latencies),
                "mean": round(sum(latencies) / len(latencies), 3) if latencies else None,
                "p50": percentile(latencies, 0.50), "p95": percentile(latencies, 0.95),
                "max": max(latencies, default=None),
            },
            "startedAtUtc": sclib.iso(started), "endedAtUtc": sclib.iso(sclib.utc_now()),
        }


def plan_phases(load, questions, window):
    """The phases of a run: (name, concurrency, jobs)."""
    limits = load["request_limits"]
    if load["kind"] == "levels":
        phases = []
        for level in load["levels"]:
            jobs = []
            for index in range(load["requests_per_level"]):
                q = questions[index % len(questions)]
                jobs.append((q["id"], None, build_body(q, window, None, limits)))
            phases.append(("level-%d" % level, level, jobs))
        return phases
    if load["kind"] == "retrieval_modes":
        jobs = []
        for repetition in range(load["repetitions"]):
            for index, q in enumerate(questions):
                # rotate the mode order so no mode is always first (cold caches)
                shift = (repetition + index) % len(RETRIEVAL_MODES)
                for mode in RETRIEVAL_MODES[shift:] + RETRIEVAL_MODES[:shift]:
                    jobs.append((q["id"], mode, build_body(q, window, mode, limits)))
        return [("modes", load["concurrency"], jobs)]
    raise sclib.ConfigError("load.kind must be 'levels' or 'retrieval_modes'")


def check_bounds(load, phases, preflight_count):
    total = preflight_count + sum(len(jobs) for _, _, jobs in phases)
    cap = load["max_total_requests"]
    if not 1 <= cap <= ABSOLUTE_REQUEST_CEILING:
        raise sclib.ConfigError("load.max_total_requests must be 1..%d" % ABSOLUTE_REQUEST_CEILING)
    if total > cap:
        raise sclib.ConfigError("the plan needs %d requests, over max_total_requests %d" % (total, cap))
    if any(not 1 <= c <= ABSOLUTE_CONCURRENCY_CEILING for _, c, _ in phases):
        raise sclib.ConfigError("concurrency must be 1..%d" % ABSOLUTE_CONCURRENCY_CEILING)
    sclib.number(load, "max_requests_per_minute", 1, ABSOLUTE_RATE_CEILING)
    sclib.number(load, "request_timeout_seconds", 0.1, ABSOLUTE_REQUEST_TIMEOUT_CEILING)
    sclib.number(load, "level_max_seconds", 0.1, ABSOLUTE_TIME_CEILING)
    sclib.number(load, "max_load_seconds", 0.1, ABSOLUTE_TIME_CEILING)
    if "cooldown_seconds" in load:
        sclib.number(load, "cooldown_seconds", 0, 600)


def execute(manifest_path, stop):
    manifest, full_path = sclib.load_manifest(manifest_path)
    sclib.require_runnable(manifest, full_path)
    load = manifest["load"]
    base_url, token, out_dir = sclib.require_env("SC_BASE_URL", "SCHOLARCHAIN_API_TOKEN", "SC_EXPERIMENT_OUT")
    region = sclib.check_region(manifest)
    cost_cap = sclib.number(manifest, "cost_cap_usd", 0.000001, 1000)
    usd_per_request = sclib.number(load, "estimated_usd_per_request", 0.000001, 100)
    set_path = os.path.join(sclib.REPO_ROOT, load["question_set"])
    with open(set_path) as handle:
        question_set = json.load(handle)
    questions, window = question_set["questions"], question_set["window"]
    phases = plan_phases(load, questions, window)
    preflight_mode = "fused" if load["kind"] == "retrieval_modes" else None
    check_bounds(load, phases, len(questions))
    client = Client(base_url, token, load["request_timeout_seconds"])

    started = sclib.utc_now()
    run_tag = "%s-%s" % (manifest["name"].split("-")[0], started.strftime("%Y%m%dT%H%M%S"))
    writer = sclib.JsonlWriter(os.path.join(out_dir, "requests.jsonl"))
    deadline = time.monotonic() + load["max_load_seconds"]
    runner = Runner(client, load, writer, stop, run_tag, deadline, cost_cap)
    summaries = []
    reason = None
    incomplete = False
    planned = [{"phase": "preflight", "concurrency": 1, "planned": len(questions)}]
    planned += [{"phase": n, "concurrency": c, "planned": len(j)} for n, c, j in phases]
    try:
        sclib.say("preflight: %d requests" % len(questions))
        jobs = [(q["id"], preflight_mode, build_body(q, window, preflight_mode, load["request_limits"]))
                for q in questions]
        summaries.append(runner.phase("preflight", 1, jobs, load["level_max_seconds"]))
        incomplete = summaries[0]["completed"] < summaries[0]["planned"]
        answered = [r for r in runner.results if r["status"] == 200 and r["abstained"] is False]
        if load["stop"]["require_answered_preflight"] and not answered:
            reason = "PREFLIGHT_NO_ANSWER"
        for position, (name, concurrency, jobs) in enumerate(phases):
            if reason or runner.abort_reason or stop.is_set():
                break
            if position and stop.wait(load.get("cooldown_seconds", 0)):
                break
            if time.monotonic() >= deadline:
                reason = "LOAD_TIME_CAP"
                break
            sclib.say("%s: concurrency %d, %d requests" % (name, concurrency, len(jobs)))
            summary = runner.phase(name, concurrency, jobs, load["level_max_seconds"])
            summaries.append(summary)
            if summary["endedBy"] == "LOAD_TIME_CAP":
                reason = "LOAD_TIME_CAP"
            elif summary["completed"] < summary["planned"]:
                # a slow level does not cancel the later ones: only the run's caps do
                incomplete = True
    finally:
        writer.close()
        reason = runner.abort_reason or ("TERMINATED" if stop.is_set() else reason)
        status = "COMPLETED" if reason is None else "STOPPED"
        if reason is None and incomplete:
            status, reason = "INCOMPLETE", "PHASE_INCOMPLETE_AT_TIME_CAP"
        sclib.write_json(os.path.join(out_dir, "run.json"), {
            "experiment": manifest["name"], "tool": "query_load", "region": region,
            "status": status, "stopReason": reason,
            "startedAtUtc": sclib.iso(started), "endedAtUtc": sclib.iso(sclib.utc_now()),
            "requestsSent": runner.sent, "maxTotalRequests": load["max_total_requests"],
            "costCapUsd": cost_cap, "estimatedUsdPerRequest": usd_per_request,
            "estimatedSpendUsd": round(runner.sent * usd_per_request, 6),
            "costModel": "estimate: requests sent x manifest price per request; not a bill",
            "plannedPhases": planned, "phases": summaries, "servingRunId": question_set["runId"],
            "datasetSnapshot": question_set["snapshot"],
            "questionSet": {"version": question_set["version"], "sha256": sclib.sha256_file(set_path)},
            "manifestSha256": sclib.sha256_file(full_path),
        })
    sclib.say("requests sent: %d, result: %s" % (runner.sent, reason or "COMPLETED"))
    return 0 if reason is None else 1


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--manifest", required=True)
    args = parser.parse_args(argv)
    try:
        return execute(args.manifest, sclib.stop_event_on_signals())
    except sclib.ConfigError as error:
        return sclib.fail(str(error))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
