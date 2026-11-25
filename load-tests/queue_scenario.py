#!/usr/bin/env python3
"""Embedding-queue scenarios (experiments C and F).

    load-tests/queue_scenario.py --manifest experiments/manifests/<name>.yaml

THIS TOOL WRITES TO SQS (SendMessage on the embedding work queue). It refuses to start unless the
manifest is approved and not blocked (sclib.approval_problems), run directly or not, and the
human sets SC_ALLOW_QUEUE_WRITES=1. It never receives, deletes or purges a message and never
changes an ECS service: scaling is whatever the deployed autoscaling does.

Run through run-experiment.sh (runner: load-test). Required environment:
SCHOLARCHAIN_APPROVED_ENVS, SC_DDB_TABLE_RUN_LEDGER (read only), SC_EXPERIMENT_OUT, SC_REGION,
SC_ALLOW_QUEUE_WRITES=1, SC_EMBEDDING_QUEUE_URL, SC_EMBEDDING_DLQ_URL, SC_ECS_CLUSTER,
SC_EMBEDDING_RUN_ID (a run whose ingestion manifest is already staged in S3),
SC_EMBEDDING_SNAPSHOT. The AWS profile must come from AWS_PROFILE: run-experiment.sh --profile
does not reach a load-test runner.

Writes sends.jsonl, observations.jsonl and run.json. No URL or resource name is written.

COMPLETED needs evidence that vectors were written. The worker logs no created or skipped counts,
but VectorIngestor records a `vector-ingest-<index>-b<n>` SUCCEEDED run-ledger entry only after a
successful PutVectors. The entries of the ingestion run are listed before the send and after the
watch. No new SUCCEEDED entry (every item rejected, or every item skipped as unchanged) or no
readable ledger gives status FAILED.
"""

import argparse
import hashlib
import os
import re
import sys
import json
import time

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import sclib  # noqa: E402

ABSOLUTE_MESSAGE_CEILING = 1000
RUN_ID = re.compile(r"run:[0-9a-f]{64}")
WORK_ITEM_VERSION = "embedding-work-item-v1"
# Sort key of the run ledger (DynamoRunLedger): <sequence>#<stage>#<attempt>#<rank>-<state>
LEDGER_PUT_BATCH = re.compile(r"\d{5}#vector-ingest-[a-z0-9_-]+#\d{3}#1-(SUCCEEDED|FAILED)")


def work_item(run_id, snapshot):
    return json.dumps(
        {"schemaVersion": WORK_ITEM_VERSION, "runId": run_id, "snapshot": snapshot},
        separators=(",", ":"),
    )


def plan_messages(spec, run_id, snapshot, tag):
    """[(kind, body)] in send order: poison first, valid items last. A standard SQS queue does
    not guarantee that delivery follows send order."""
    if spec["scenario"] == "backlog":
        return [("valid", work_item(run_id, snapshot))] * spec["messages"]
    if spec["scenario"] == "poison":
        plan = [("poison_undecodable", "sc-experiment-poison %s %d" % (tag, n))
                for n in range(spec["poison_undecodable"])]
        for n in range(spec["poison_unknown_run"]):
            absent = "run:" + hashlib.sha256(("sc-poison|%s|%d" % (tag, n)).encode()).hexdigest()
            plan.append(("poison_unknown_run", work_item(absent, snapshot)))
        # identical copies: the second delivery of the same work must be harmless
        plan += [("valid", work_item(run_id, snapshot))] * spec["valid_duplicates"]
        return plan
    raise sclib.ConfigError("queue.scenario must be 'backlog' or 'poison'")


class Observer:
    def __init__(self, region, queue_url, dlq_url, cluster, service, writer):
        self.region, self.queue_url, self.dlq_url = region, queue_url, dlq_url
        self.cluster, self.service, self.writer = cluster, service, writer

    def _attributes(self, url):
        doc = sclib.aws_json(
            ["sqs", "get-queue-attributes", "--queue-url", url, "--attribute-names",
             "ApproximateNumberOfMessages", "ApproximateNumberOfMessagesNotVisible"],
            self.region)
        attributes = doc["Attributes"]
        return (int(attributes["ApproximateNumberOfMessages"]),
                int(attributes["ApproximateNumberOfMessagesNotVisible"]))

    def sample(self, phase):
        visible, in_flight = self._attributes(self.queue_url)
        dlq_visible, _ = self._attributes(self.dlq_url)
        doc = sclib.aws_json(
            ["ecs", "describe-services", "--cluster", self.cluster, "--services", self.service],
            self.region)
        services = doc.get("services") or []
        if len(services) != 1:
            raise sclib.AwsError("ecs describe-services: the worker service was not found")
        s = services[0]
        observation = {
            "atUtc": sclib.iso(sclib.utc_now()), "phase": phase, "visible": visible,
            "inFlight": in_flight, "dlqVisible": dlq_visible, "desired": s["desiredCount"],
            "running": s["runningCount"], "pending": s["pendingCount"],
        }
        self.writer.write(observation)
        return observation


def send(region, queue_url, messages, writer, stop, tally, deadline):
    """SendMessageBatch in tens, one attempt each (a retried send could duplicate messages).

    `tally` is updated batch by batch, so it stays true when a later batch fails. A batch whose
    call failed is recorded with accepted=null: it may or may not have been delivered."""
    for start in range(0, len(messages), 10):
        if stop.is_set() or time.monotonic() >= deadline:
            break
        batch = messages[start:start + 10]
        entries = [{"Id": "m%d" % (start + n), "MessageBody": body} for n, (_, body) in enumerate(batch)]
        failure, ids = None, {}
        try:
            doc = sclib.aws_json(
                ["sqs", "send-message-batch", "--queue-url", queue_url, "--entries", json.dumps(entries)],
                region, max_attempts=1)
            ids = {e["Id"]: e["MessageId"] for e in doc.get("Successful", [])}
        except sclib.AwsError as error:
            failure = error
        except (ValueError, KeyError, TypeError, AttributeError):
            failure = sclib.AwsError("sqs send-message-batch: unreadable response")
        for n, (kind, body) in enumerate(batch):
            key = "m%d" % (start + n)
            accepted = None if failure else key in ids
            tally["unknown" if failure else "accepted" if accepted else "refused"] += 1
            writer.write({
                "atUtc": sclib.iso(sclib.utc_now()), "index": start + n, "kind": kind,
                "accepted": accepted, "messageId": ids.get(key),
                "bodySha256": hashlib.sha256(body.encode()).hexdigest(),
            })
        if failure:
            raise failure
        if len(ids) != len(batch):
            raise sclib.AwsError("sqs send-message-batch: some entries were refused")


def ledger_put_batches(region, table, run_id, timeout=60):
    """The sort keys of the run's vector put batches in the run ledger. Read only."""
    doc = sclib.aws_json(
        ["dynamodb", "query", "--table-name", table, "--key-condition-expression", "pk = :pk",
         "--expression-attribute-values", json.dumps({":pk": {"S": "run#" + run_id}}),
         "--projection-expression", "sk", "--consistent-read"],
        region, timeout=timeout)
    try:
        keys = [item["sk"]["S"] for item in doc["Items"]]
    except (KeyError, TypeError):
        raise sclib.AwsError("dynamodb query: unreadable response") from None
    return {k for k in keys if LEDGER_PUT_BATCH.fullmatch(k)}


def watch(observer, spec, baseline_dlq, poison_count, stop, deadline):
    """Polls until the queue drained and the worker returned to zero, or a stop condition.

    Returns (stopReason or None, milestones). A milestone never observed stays null."""
    interval = spec["poll_interval_seconds"]
    started = time.monotonic()
    marks = {"firstTaskRunningAfterSeconds": None, "maxRunningObserved": None, "maxDesiredObserved": None,
             "drainedAfterSeconds": None, "scaledToZeroAfterSeconds": None, "dlqIncrease": None,
             "maxVisibleObserved": None}

    def highest(key, value):
        marks[key] = value if marks[key] is None else max(marks[key], value)

    empty_polls = 0
    failures = 0
    while True:
        if stop.wait(interval):
            return "TERMINATED", marks
        elapsed = round(time.monotonic() - started, 1)
        if time.monotonic() >= deadline:
            return "TIME_CAP", marks
        try:
            o = observer.sample("scale_in" if marks["drainedAfterSeconds"] is not None else "drain")
            failures = 0
        except sclib.AwsError as error:
            failures += 1
            observer.writer.write({"atUtc": sclib.iso(sclib.utc_now()), "error": str(error)})
            if failures >= 5:
                return "OBSERVATION_FAILED", marks
            continue
        highest("maxRunningObserved", o["running"])
        highest("maxDesiredObserved", o["desired"])
        highest("maxVisibleObserved", o["visible"])
        highest("dlqIncrease", max(0, o["dlqVisible"] - baseline_dlq))
        if o["running"] > 0 and marks["firstTaskRunningAfterSeconds"] is None:
            marks["firstTaskRunningAfterSeconds"] = elapsed
        if o["desired"] > spec["expected_max_workers"]:
            return "WORKER_BOUND_EXCEEDED", marks
        if marks["dlqIncrease"] > poison_count:
            return "UNEXPECTED_DEAD_LETTERS", marks
        if marks["firstTaskRunningAfterSeconds"] is None and elapsed > spec["scale_out_timeout_seconds"]:
            return "NO_SCALE_OUT", marks
        empty = o["visible"] == 0 and o["inFlight"] == 0
        if not empty and marks["drainedAfterSeconds"] is not None:
            marks["drainedAfterSeconds"] = None  # the earlier zeros were not a drain
            empty_polls = 0
        if marks["drainedAfterSeconds"] is None:
            # SQS counts are approximate and can read 0 just after a send: a drain needs a worker
            # to have been seen running, then two empty polls in a row
            seen = marks["firstTaskRunningAfterSeconds"] is not None
            empty_polls = empty_polls + 1 if empty and seen else 0
            if empty_polls >= 2:
                marks["drainedAfterSeconds"] = elapsed
            elif elapsed > spec["drain_timeout_seconds"]:
                return "DRAIN_TIMEOUT", marks
        else:
            if o["desired"] == 0 and o["running"] == 0 and o["pending"] == 0:
                marks["scaledToZeroAfterSeconds"] = elapsed
                if marks["dlqIncrease"] < poison_count:
                    return "POISON_NOT_DEAD_LETTERED", marks
                return None, marks
            if elapsed - marks["drainedAfterSeconds"] > spec["scale_in_timeout_seconds"]:
                return "NO_SCALE_IN", marks


def check_times(spec):
    sclib.number(spec, "poll_interval_seconds", 1, 300)
    for key in ("scale_out_timeout_seconds", "drain_timeout_seconds", "scale_in_timeout_seconds",
                "max_watch_seconds"):
        sclib.number(spec, key, 0.1, 3600)


def execute(manifest_path, stop):
    manifest, full_path = sclib.load_manifest(manifest_path)
    sclib.require_runnable(manifest, full_path)
    spec = manifest["queue"]
    if os.environ.get("SC_ALLOW_QUEUE_WRITES") != "1":
        raise sclib.ConfigError("this scenario writes to SQS: the human must set SC_ALLOW_QUEUE_WRITES=1")
    region = sclib.check_region(manifest)
    check_times(spec)
    out_dir, queue_url, dlq_url, cluster, run_id, snapshot, ledger = sclib.require_env(
        "SC_EXPERIMENT_OUT", "SC_EMBEDDING_QUEUE_URL", "SC_EMBEDDING_DLQ_URL", "SC_ECS_CLUSTER",
        "SC_EMBEDDING_RUN_ID", "SC_EMBEDDING_SNAPSHOT", "SC_DDB_TABLE_RUN_LEDGER")
    if not RUN_ID.fullmatch(run_id):
        raise sclib.ConfigError("SC_EMBEDDING_RUN_ID must be run:<64 hex>")
    if queue_url == dlq_url:
        raise sclib.ConfigError("the queue and the dead-letter queue must differ")
    started = sclib.utc_now()
    tag = started.strftime("%Y%m%dT%H%M%S")
    messages = plan_messages(spec, run_id, snapshot, tag)
    if not 1 <= len(messages) <= min(spec["max_messages"], ABSOLUTE_MESSAGE_CEILING):
        raise sclib.ConfigError("the plan has %d messages, outside the cap" % len(messages))
    poison_count = sum(1 for kind, _ in messages if kind.startswith("poison"))

    sends = sclib.JsonlWriter(os.path.join(out_dir, "sends.jsonl"))
    observations = sclib.JsonlWriter(os.path.join(out_dir, "observations.jsonl"))
    observer = Observer(region, queue_url, dlq_url, cluster, spec["worker_service"], observations)
    deadline = time.monotonic() + spec["max_watch_seconds"]
    reason, marks, baseline, before = "ERROR", {}, None, None
    tally = {"accepted": 0, "refused": 0, "unknown": 0}
    evidence = {"source": "run ledger vector-ingest put batches new since before the send",
                "status": "NOT_CHECKED", "newSucceededPutBatches": None, "newFailedPutBatches": None}
    try:
        try:
            baseline = observer.sample("precondition")
            if baseline["visible"] or baseline["inFlight"]:
                reason = "PRECONDITION_QUEUE_NOT_EMPTY"
            elif baseline["desired"] or baseline["running"] or baseline["pending"]:
                reason = "PRECONDITION_WORKERS_NOT_ZERO"
            else:
                before = ledger_put_batches(region, ledger, run_id)  # unreadable: send nothing
                sclib.say("sending %d messages (%d poison)" % (len(messages), poison_count))
                send(region, queue_url, messages, sends, stop, tally, deadline)
                observer.sample("sent")
                reason, marks = watch(observer, spec, baseline["dlqVisible"], poison_count, stop, deadline)
        except sclib.AwsError as error:
            reason = "TERMINATED" if stop.is_set() else "AWS_CALL_FAILED"
            sclib.fail(str(error))
        if before is not None:
            try:
                new = ledger_put_batches(region, ledger, run_id, timeout=20 if stop.is_set() else 60) - before
                evidence.update(status="OK",
                                newSucceededPutBatches=sum(k.endswith("-SUCCEEDED") for k in new),
                                newFailedPutBatches=sum(k.endswith("-FAILED") for k in new))
            except sclib.AwsError as error:
                evidence["status"] = "UNAVAILABLE"
                sclib.fail(str(error))
    finally:
        sends.close()
        observations.close()
        written = evidence["newSucceededPutBatches"]
        if reason is None and not written:
            reason = "WRITE_EVIDENCE_UNAVAILABLE" if written is None else "NO_VECTORS_WRITTEN"
        # without proof of a write the run FAILED, whatever else stopped it
        status = "COMPLETED" if reason is None else "STOPPED" if written else "FAILED"
        sclib.write_json(os.path.join(out_dir, "run.json"), {
            "experiment": manifest["name"], "tool": "queue_scenario", "region": region,
            "scenario": spec["scenario"], "status": status,
            "stopReason": reason, "startedAtUtc": sclib.iso(started),
            "endedAtUtc": sclib.iso(sclib.utc_now()), "messagesPlanned": len(messages),
            "messagesAccepted": tally["accepted"], "messagesRefused": tally["refused"],
            "messagesOutcomeUnknown": tally["unknown"], "poisonMessages": poison_count,
            "vectorWriteEvidence": evidence,
            "ingestionRunId": run_id, "datasetSnapshot": snapshot,
            "dlqBaseline": None if baseline is None else baseline["dlqVisible"],
            "milestones": marks, "manifestSha256": sclib.sha256_file(full_path),
        })
    sclib.say("result: %s %s %s" % (status, reason or "", json.dumps(marks, sort_keys=True)))
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
