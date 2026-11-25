"""Offline tests of the load generators.  python3 -m unittest discover -s load-tests/tests

A test name ending in _fNN guards phase 15 review finding NN."""

import json
import os
import signal
import sys
import tempfile
import threading
import time
import unittest

import yaml

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import fakes  # noqa: E402

sys.path.insert(0, fakes.LOAD_TESTS)
import query_load  # noqa: E402
import queue_scenario  # noqa: E402
import sclib  # noqa: E402

RUN_ID = fakes.VALID_RUN_ID
LAUNCH_STAMP = "-abc1234-20260101T000000Z"


class ToolTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.new_out("out")

    def new_out(self, name):
        self.out = os.path.join(self.tmp.name, name)
        os.makedirs(self.out)

    def run_json(self):
        with open(os.path.join(self.out, "run.json")) as handle:
            return json.load(handle)


class LoadToolTest(ToolTest):
    def load_args(self, api, source="D-query-load.yaml", env=None, change=None, **load):
        self.addCleanup(api.close)
        defaults = {"levels": [1, 5], "requests_per_level": 6, "max_requests_per_minute": 600,
                    "cooldown_seconds": 0}
        if source.startswith("E"):
            defaults = {"repetitions": 1, "max_requests_per_minute": 600}
        defaults.update(load)
        manifest = fakes.fixture_manifest(self.tmp.name, source, change=change, load=defaults)
        environment = {"SC_BASE_URL": api.url, "SCHOLARCHAIN_API_TOKEN": fakes.TOKEN,
                       "SC_EXPERIMENT_OUT": self.out, "SC_REGION": "us-east-2", "SCHOLARCHAIN_APPROVED_ENVS": "dev"}
        environment.update(env or {})
        return ["--manifest", manifest], environment

    def run_load(self, api, **kwargs):
        args, environment = self.load_args(api, **kwargs)
        return fakes.run_tool("load-tests/query_load.py", args, environment)

    def rows(self):
        return fakes.lines(os.path.join(self.out, "requests.jsonl"))


class QueryLoadTest(LoadToolTest):
    def test_records_latency_status_and_trace_id_per_request_without_secrets(self):
        api = fakes.FakeApi()
        done = self.run_load(api)
        self.assertEqual(done.returncode, 0, done.stderr)
        rows = self.rows()
        self.assertEqual(len(rows), 5 + 12)
        self.assertEqual({r["status"] for r in rows}, {200})
        self.assertTrue(all(r["latencyMs"] > 0 and r["traceId"] == r["traceIdSent"] for r in rows))
        self.assertEqual(len({r["traceId"] for r in rows}), len(rows))
        self.assertTrue(all(1 <= r["inFlightAtStart"] <= r["concurrency"] for r in rows))
        self.assertEqual({r["phase"] for r in rows}, {"preflight", "level-1", "level-5"})
        everything = fakes.text_of(self.out) + done.stdout + done.stderr
        self.assertNotIn(fakes.TOKEN, everything)
        self.assertNotIn(api.url, everything)
        self.assertEqual(set(api.auth_headers), {"Bearer " + fakes.TOKEN})

    def test_plan_over_the_hard_cap_sends_nothing(self):
        api = fakes.FakeApi()
        done = self.run_load(api, max_total_requests=16)
        self.assertEqual(done.returncode, 1)
        self.assertEqual(api.bodies, [])
        self.assertIn("max_total_requests", done.stderr)

    def test_bad_token_stops_after_three_requests_and_keeps_the_failed_run(self):
        api = fakes.FakeApi(status=401)
        done = self.run_load(api)
        self.assertEqual(done.returncode, 1)
        self.assertEqual(len(api.bodies), 3)
        self.assertEqual(self.run_json()["stopReason"], "AUTH_FAILURES")
        self.assertEqual(len(self.rows()), 3)

    def test_abstaining_preflight_stops_before_any_load(self):
        api = fakes.FakeApi(abstained=True)
        self.assertEqual(self.run_load(api).returncode, 1)
        self.assertEqual(len(api.bodies), 5)
        self.assertEqual(self.run_json()["stopReason"], "PREFLIGHT_NO_ANSWER")

    def test_error_rate_stops_the_run(self):
        api = fakes.FakeApi(status=503)
        stop = {"max_consecutive_auth_failures": 3, "error_rate_min_samples": 4, "max_error_rate": 0.5,
                "max_rate_limited_rate": 0.5, "require_answered_preflight": False}
        done = self.run_load(api, levels=[1], requests_per_level=50, stop=stop)
        self.assertEqual(done.returncode, 1)
        self.assertEqual(len(api.bodies), 5 + 4)
        self.assertEqual(self.run_json()["stopReason"], "ERROR_RATE")

    def test_a_redirect_is_not_followed(self):
        api = fakes.FakeApi(redirect=True)
        self.run_load(api)
        self.assertEqual({r["status"] for r in self.rows()}, {302})

    def test_plaintext_http_to_another_host_is_refused(self):
        api = fakes.FakeApi()
        done = self.run_load(api, env={"SC_BASE_URL": "http://example.invalid"})
        self.assertEqual(done.returncode, 1)
        self.assertIn("SC_ALLOW_PLAINTEXT_HTTP", done.stderr)

    def test_region_must_match_the_manifest(self):
        api = fakes.FakeApi()
        done = self.run_load(api, env={"SC_REGION": "eu-west-1"})
        self.assertEqual(done.returncode, 1)
        self.assertEqual(api.bodies, [])
        self.assertIn("manifest region", done.stderr)

    def test_retrieval_modes_differ_only_in_operations_and_vector_limit(self):
        api = fakes.FakeApi()
        done = self.run_load(api, source="E-retrieval-modes.yaml")
        self.assertEqual(done.returncode, 0, done.stderr)
        rows = [r for r in self.rows() if r["phase"] == "modes"]
        self.assertEqual(sorted(r["retrievalMode"] for r in rows), sorted(query_load.RETRIEVAL_MODES * 5))
        question = {"graphOperations": ["DIRECT_COAUTHORS"], "researcherIds": ["r"], "text": "q"}
        window, limits = {"startYear": 2000, "endYear": 2025}, {"graphLimit": 5, "vectorLimit": 4}
        graph, vector, fused = (query_load.build_body(question, window, m, limits)
                                for m in query_load.RETRIEVAL_MODES)
        self.assertEqual(graph["operations"], ["DIRECT_COAUTHORS"])
        self.assertEqual(graph["vectorLimit"], 0)
        self.assertEqual(vector["operations"], ["SEMANTIC_EXPLANATION"])
        self.assertEqual(fused["operations"], ["DIRECT_COAUTHORS", "SEMANTIC_EXPLANATION"])

    def test_nearest_rank_percentile(self):
        values = list(range(1, 101))
        self.assertEqual([query_load.percentile(values, f) for f in (0.5, 0.95, 0.99)], [50, 95, 99])
        self.assertIsNone(query_load.percentile([], 0.5))

    def test_a_slow_level_does_not_cancel_the_later_levels_f04(self):
        api = fakes.FakeApi(delay=0.3)
        done = self.run_load(api, requests_per_level=10, level_max_seconds=2.0)
        run = self.run_json()
        phases = {p["phase"]: p for p in run["phases"]}
        self.assertIn("level-5", phases, run)
        self.assertEqual(phases["level-5"]["completed"], 10)
        self.assertLess(phases["level-1"]["completed"], 10)
        self.assertEqual(phases["level-1"]["endedBy"], "LEVEL_TIME_CAP")
        self.assertGreaterEqual(phases["level-1"]["observedLatencyMs"]["mean"], 300)
        self.assertEqual((run["status"], run["stopReason"]), ("INCOMPLETE", "PHASE_INCOMPLETE_AT_TIME_CAP"))
        self.assertEqual(done.returncode, 1)

    def test_the_estimated_cost_cap_stops_the_run_f04(self):
        api = fakes.FakeApi()
        done = self.run_load(api, estimated_usd_per_request=0.1,
                             change=lambda doc: doc.update(cost_cap_usd=0.8))
        self.assertEqual(done.returncode, 1)
        self.assertEqual(len(api.bodies), 8)
        run = self.run_json()
        self.assertEqual((run["stopReason"], run["estimatedSpendUsd"]), ("COST_CAP_REACHED", 0.8))
        self.assertIn("not a bill", run["costModel"])

    def test_a_missing_price_is_refused_f04(self):
        api = fakes.FakeApi()
        done = self.run_load(api, change=lambda doc: doc["load"].pop("estimated_usd_per_request", None))
        self.assertEqual(done.returncode, 1)
        self.assertEqual(api.bodies, [])

    def test_a_malformed_token_is_never_printed_or_written_f12(self):
        for number, token in enumerate(("firstPART\nsecondPART", "first PART secondPART", "secondPART-é")):
            self.new_out("token%d" % number)
            api = fakes.FakeApi()
            done = self.run_load(api, env={"SCHOLARCHAIN_API_TOKEN": token})
            everything = fakes.text_of(self.out) + done.stdout + done.stderr
            self.assertEqual(done.returncode, 1, everything)
            for part in ("firstPART", "secondPART"):
                self.assertNotIn(part, everything)
            self.assertNotIn("Traceback", everything)
            self.assertEqual(api.bodies, [])

    def test_time_and_rate_values_over_the_code_ceilings_are_refused_f13(self):
        for key, value in (("max_load_seconds", 10 ** 9), ("request_timeout_seconds", 10 ** 9),
                           ("level_max_seconds", 10 ** 9), ("max_requests_per_minute", 10 ** 9),
                           ("request_timeout_seconds", 0), ("max_load_seconds", "600")):
            api = fakes.FakeApi()
            done = self.run_load(api, **{key: value})
            self.assertEqual(done.returncode, 1, key)
            self.assertIn(key, done.stderr)
            self.assertEqual(api.bodies, [], key)

    def test_the_request_timeout_is_a_total_deadline_f13(self):
        api = fakes.FakeApi(trickle=True)
        self.run_load(api, request_timeout_seconds=1)
        rows = self.rows()
        self.assertEqual({r["transportError"] for r in rows}, {"TotalTimeout"})
        self.assertTrue(all(r["latencyMs"] < 2000 for r in rows), [r["latencyMs"] for r in rows])

    def test_a_tripped_stop_cancels_requests_waiting_for_their_slot_f14(self):
        calls = []

        class Failing:
            def post(self, body, trace_id):
                calls.append(trace_id)
                return {"status": 503, "latencyMs": 1.0, "traceId": trace_id, "abstained": None}

        class Sink:
            def write(self, record):
                pass

        load = {"max_requests_per_minute": 600, "max_total_requests": 100,
                "stop": {"max_consecutive_auth_failures": 3, "error_rate_min_samples": 3, "max_error_rate": 0.5,
                         "max_rate_limited_rate": 0.5}}
        runner = query_load.Runner(Failing(), load, Sink(), threading.Event(), "t", time.monotonic() + 30)
        runner.phase("level-10", 10, [("q", None, {})] * 20, 30)
        self.assertEqual((runner.abort_reason, len(calls), runner.sent), ("ERROR_RATE", 3, 3))

    def test_the_load_time_cap_stops_the_run_f19(self):
        api = fakes.FakeApi(delay=0.2)
        done = self.run_load(api, levels=[1], requests_per_level=100, max_load_seconds=2.0)
        self.assertEqual(done.returncode, 1)
        self.assertEqual(self.run_json()["stopReason"], "LOAD_TIME_CAP")
        self.assertLess(len(api.bodies), 15)

    def test_requests_are_paced_f19(self):
        api = fakes.FakeApi()
        done = self.run_load(api, levels=[5], requests_per_level=5, max_requests_per_minute=300)
        self.assertEqual(done.returncode, 0, done.stderr)
        # 10 requests at one every 0.2 s: no burst, whatever the concurrency
        self.assertGreaterEqual(api.arrivals[-1] - api.arrivals[0], 1.6)
        self.assertGreaterEqual(min(b - a for a, b in zip(api.arrivals, api.arrivals[1:])), 0.1)

    def test_term_keeps_the_partial_run_f19(self):
        api = fakes.FakeApi()
        args, env = self.load_args(api, levels=[1], requests_per_level=200)
        process = fakes.start_tool("load-tests/query_load.py", args, env)
        self.addCleanup(process.kill)
        while len(api.bodies) < 8 and process.poll() is None:
            time.sleep(0.05)
        process.send_signal(signal.SIGTERM)
        process.communicate(timeout=30)
        self.assertEqual(process.returncode, 1)
        self.assertEqual(self.run_json()["stopReason"], "TERMINATED")
        self.assertGreaterEqual(len(self.rows()), 7)
        self.assertLess(len(api.bodies), 100)


class ApprovalTest(LoadToolTest):
    """Finding 3 (who may run) and finding 18 (which manifest runs)."""

    UNAPPROVED = (
        ("pending", lambda d: d["approval"].update(status="PENDING_HUMAN_APPROVAL")),
        ("lower case status", lambda d: d["approval"].update(status="approved")),
        ("no approver", lambda d: d["approval"].update(approved_by=None)),
        ("placeholder approver", lambda d: d["approval"].update(approved_by="CHANGE_ME")),
        ("template approver", lambda d: d["approval"].update(approved_by="<your name>")),
        ("no date", lambda d: d["approval"].update(approved_on=None)),
        ("placeholder date", lambda d: d["approval"].update(approved_on="YYYY-MM-DD")),
        ("future date", lambda d: d["approval"].update(approved_on="2999-01-01")),
        ("no block", lambda d: d.pop("approval")),
        ("blocked", lambda d: d.update(blocked=True)),
        ("blocked unstated", lambda d: d.pop("blocked")),
        ("blocked as text", lambda d: d.update(blocked="false")),
        ("blocked precondition", lambda d: d.update(preconditions=["BLOCKED: no input"])),
        ("blocked precondition mapping", lambda d: d.update(preconditions=[{"BLOCKED": "no input"}])),
    )

    def test_approval_problems_fail_closed_f03(self):
        good = {"blocked": False,
                "approval": {"status": "APPROVED", "approved_by": "A Person", "approved_on": "2026-01-01"}}
        self.assertEqual(sclib.approval_problems(good), [])
        for label, change in self.UNAPPROVED:
            doc = json.loads(json.dumps(good))
            change(doc)
            self.assertTrue(sclib.approval_problems(doc), label)

    def test_query_load_sends_nothing_for_an_unapproved_or_blocked_manifest_f03(self):
        for label, change in self.UNAPPROVED:
            api = fakes.FakeApi()
            done = self.run_load(api, change=change)
            self.assertEqual(done.returncode, 1, label)
            self.assertIn("may not run", done.stderr, label)
            self.assertEqual(api.bodies, [], label)
        self.assertEqual(os.listdir(self.out), [])

    def test_an_unapproved_environment_is_refused_on_direct_invocation_f03(self):
        api = fakes.FakeApi()
        done = self.run_load(api, env={"SCHOLARCHAIN_APPROVED_ENVS": ""})
        self.assertEqual(done.returncode, 1)
        self.assertEqual(api.bodies, [])

    def test_query_load_env_flag_and_variable_must_match_the_manifest(self):
        for label, flags, env in (("flag", ["--env", "prod"], {}), ("variable", [], {"SC_ENV": "prod"}),
                                  ("empty flag", ["--env", ""], {})):
            api = fakes.FakeApi()
            args, environment = self.load_args(api, env=env)
            done = fakes.run_tool("load-tests/query_load.py", args + flags, environment)
            self.assertEqual(done.returncode, 1, label + done.stderr)
            self.assertIn("--env", done.stderr, label)
            self.assertEqual(api.bodies, [], label)

    def test_query_load_accepts_env_and_profile_flags_and_the_flag_wins_over_sc_env(self):
        api = fakes.FakeApi()
        args, environment = self.load_args(api, env={"SC_ENV": "prod"})
        done = fakes.run_tool("load-tests/query_load.py", args + ["--env", "dev", "--profile", "p1"], environment)
        self.assertEqual(done.returncode, 0, done.stderr)

    def test_a_manifest_whose_args_name_another_file_is_refused_f18(self):
        api = fakes.FakeApi()
        stale = ["--manifest", "experiments/manifests/D-query-load.yaml"]
        done = self.run_load(api, change=lambda d: d.update(args=stale))
        self.assertEqual(done.returncode, 1)
        self.assertIn("args", done.stderr)
        self.assertEqual(api.bodies, [])

    def test_a_run_launched_for_another_manifest_is_refused_f18(self):
        api = fakes.FakeApi()
        self.new_out("D-query-load-copy" + LAUNCH_STAMP)
        done = self.run_load(api)
        self.assertEqual(done.returncode, 1)
        self.assertIn("another manifest", done.stderr)
        self.assertEqual(api.bodies, [])
        self.new_out("D-query-load" + LAUNCH_STAMP)
        self.assertEqual(self.run_load(fakes.FakeApi()).returncode, 0)


class QueueTest(ToolTest):
    def queue_args(self, source, queue=None, env=None, change=None, drop=(), **stub):
        manifest = fakes.fixture_manifest(self.tmp.name, source, change=change,
                                          queue=dict({"poll_interval_seconds": 1}, **(queue or {})))
        environment = fakes.make_stub(self.tmp.name, **stub)
        environment.update(SC_EXPERIMENT_OUT=self.out, SC_REGION="us-east-2", SCHOLARCHAIN_APPROVED_ENVS="dev",
                           SC_EMBEDDING_QUEUE_URL="https://sqs.invalid/123456789012/work",
                           SC_EMBEDDING_DLQ_URL="https://sqs.invalid/123456789012/work-dlq",
                           SC_ECS_CLUSTER="secret-cluster", SC_EMBEDDING_RUN_ID=RUN_ID,
                           SC_EMBEDDING_SNAPSHOT="2026-11-01", SC_DDB_TABLE_RUN_LEDGER="secret-ledger",
                           SC_ALLOW_QUEUE_WRITES="1")
        environment.update(env or {})
        for name in drop:
            del environment[name]
        return ["--manifest", manifest], environment

    def run_queue(self, source, flags=(), **kwargs):
        args, env = self.queue_args(source, **kwargs)
        done = fakes.run_tool("load-tests/queue_scenario.py", args + list(flags), env)
        with open(env["STUB_STATE"]) as handle:
            return done, json.load(handle)


class QueueScenarioTest(QueueTest):
    def test_refuses_without_the_human_opt_in(self):
        done, state = self.run_queue("C-embedding-backlog.yaml", drop=("SC_ALLOW_QUEUE_WRITES",))
        self.assertEqual(done.returncode, 1)
        self.assertEqual(state["calls"], [])
        self.assertIn("SC_ALLOW_QUEUE_WRITES", done.stderr)

    def test_makes_no_call_for_an_unapproved_or_blocked_manifest_f03(self):
        for label, change in ApprovalTest.UNAPPROVED:
            done, state = self.run_queue("C-embedding-backlog.yaml", change=change)
            self.assertEqual(done.returncode, 1, label)
            self.assertIn("may not run", done.stderr, label)
            self.assertEqual(state["calls"], [], label)

    def committed(self, source):
        with open(os.path.join(fakes.REPO, "experiments", "manifests", source)) as handle:
            return yaml.safe_load(handle)

    def test_f_sends_nothing_while_it_has_no_staged_run_of_its_own(self):
        """Human ruling. Approving the committed manifest is not enough, nor is flipping `blocked`."""
        committed = self.committed("F-poison-message.yaml")
        for label, blocked in (("approved as committed", committed["blocked"]), ("boolean flipped", False)):
            change = lambda d: d.update(blocked=blocked, preconditions=committed["preconditions"])
            done, state = self.run_queue("F-poison-message.yaml", change=change)
            self.assertEqual(done.returncode, 1, label)
            self.assertIn("may not run", done.stderr, label)
            self.assertEqual(state["calls"], [], label)

    def test_committed_c_is_held_back_by_nothing_but_its_approval(self):
        committed = self.committed("C-embedding-backlog.yaml")
        change = lambda d: d.update(blocked=committed["blocked"], preconditions=committed["preconditions"])
        done, state = self.run_queue("C-embedding-backlog.yaml", change=change, queue={"messages": 2})
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertIn("sqs send-message-batch", state["calls"])

    def assert_every_call_used(self, state, profile):
        self.assertTrue({"sqs get-queue-attributes", "sqs send-message-batch", "ecs describe-services",
                         "dynamodb query"} <= set(state["calls"]), state["calls"])
        self.assertEqual(len(state["profiles"]), len(state["calls"]))
        self.assertEqual(set(state["profiles"]), {profile})
        self.assertEqual(self.run_json()["awsProfile"], profile)

    def test_the_profile_flag_reaches_every_aws_call_and_wins_over_aws_profile(self):
        done, state = self.run_queue("C-embedding-backlog.yaml", flags=["--profile", "flag-profile"],
                                     env={"AWS_PROFILE": "variable-profile"}, queue={"messages": 2})
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assert_every_call_used(state, "flag-profile")

    def test_aws_profile_is_the_fallback_for_every_aws_call(self):
        done, state = self.run_queue("C-embedding-backlog.yaml", env={"AWS_PROFILE": "variable-profile"},
                                     queue={"messages": 2})
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assert_every_call_used(state, "variable-profile")

    def test_without_a_profile_flag_or_variable_none_is_passed(self):
        done, state = self.run_queue("C-embedding-backlog.yaml", visible=3)
        self.assertEqual(done.returncode, 1)
        self.assertEqual(set(state["profiles"]), {None})
        self.assertIsNone(self.run_json()["awsProfile"])

    def test_an_empty_profile_flag_makes_no_call(self):
        done, state = self.run_queue("C-embedding-backlog.yaml", flags=["--profile", " "],
                                     env={"AWS_PROFILE": "variable-profile"})
        self.assertEqual(done.returncode, 1)
        self.assertIn("--profile needs a value", done.stderr)
        self.assertEqual(state["calls"], [])

    def test_a_wrong_or_unapproved_env_makes_no_call(self):
        mismatch, unlisted = "differs from the manifest environment", "SCHOLARCHAIN_APPROVED_ENVS"
        for label, flags, env, words in (
                ("flag", ["--env", "prod"], {}, mismatch),
                ("variable", [], {"SC_ENV": "prod"}, mismatch),
                ("approved but not the manifest's", ["--env", "prod"], {unlisted: "dev,prod"}, mismatch),
                ("a flag does not approve", ["--env", "dev"], {unlisted: ""}, unlisted)):
            done, state = self.run_queue("C-embedding-backlog.yaml", flags=flags, env=env)
            self.assertEqual(done.returncode, 1, label + done.stderr)
            self.assertIn(words, done.stderr, label)
            self.assertEqual(state["calls"], [], label)

    def test_the_env_flag_wins_over_sc_env(self):
        done, state = self.run_queue("C-embedding-backlog.yaml", flags=["--env", "dev"], env={"SC_ENV": "prod"},
                                     visible=3)
        self.assertEqual(done.returncode, 1, done.stderr)
        self.assertEqual(self.run_json()["stopReason"], "PRECONDITION_QUEUE_NOT_EMPTY", done.stderr)
        self.assertTrue(state["calls"])

    def test_backlog_records_scale_out_drain_and_scale_in_without_resource_names(self):
        done, state = self.run_queue("C-embedding-backlog.yaml", queue={"messages": 25})
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        run = self.run_json()
        self.assertEqual((run["status"], run["messagesAccepted"]), ("COMPLETED", 25))
        self.assertEqual(run["milestones"]["maxRunningObserved"], 2)
        self.assertIsNotNone(run["milestones"]["scaledToZeroAfterSeconds"])
        self.assertEqual(state["calls"].count("sqs send-message-batch"), 3)
        self.assertEqual(run["vectorWriteEvidence"]["newSucceededPutBatches"], 1)
        everything = fakes.text_of(self.out) + done.stdout + done.stderr
        for secret in ("sqs.invalid", "123456789012", "secret-cluster", "secret-ledger"):
            self.assertNotIn(secret, everything)
        for call in state["calls"]:
            self.assertNotRegex(call, "delete|purge|receive|update|put")

    def test_poison_run_needs_every_poison_message_dead_lettered(self):
        done, _ = self.run_queue("F-poison-message.yaml")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(self.run_json()["milestones"]["dlqIncrease"], 12)
        kinds = [r["kind"] for r in fakes.lines(os.path.join(self.out, "sends.jsonl"))]
        self.assertEqual((kinds.count("poison_undecodable"), kinds.count("poison_unknown_run"),
                          kinds.count("valid")), (6, 6, 2))
        self.assertEqual(kinds[-2:], ["valid", "valid"])

    def test_no_scale_out_is_a_recorded_failure(self):
        done, _ = self.run_queue("C-embedding-backlog.yaml", scales=False,
                                 queue={"messages": 12, "scale_out_timeout_seconds": 1.5})
        self.assertEqual(done.returncode, 1)
        self.assertEqual(self.run_json()["stopReason"], "NO_SCALE_OUT")
        self.assertTrue(fakes.lines(os.path.join(self.out, "observations.jsonl")))

    def test_a_non_empty_queue_sends_nothing(self):
        done, state = self.run_queue("C-embedding-backlog.yaml", visible=3)
        self.assertEqual(done.returncode, 1)
        self.assertEqual(self.run_json()["stopReason"], "PRECONDITION_QUEUE_NOT_EMPTY")
        self.assertNotIn("sqs send-message-batch", state["calls"])

    def test_a_failed_later_batch_keeps_the_accepted_count_f01(self):
        done, _ = self.run_queue("C-embedding-backlog.yaml", queue={"messages": 25}, fail_send=3)
        self.assertEqual(done.returncode, 1)
        run = self.run_json()
        self.assertEqual(run["messagesAccepted"], 20)
        self.assertEqual((run["messagesOutcomeUnknown"], run["messagesRefused"]), (5, 0))
        accepted = [r["accepted"] for r in fakes.lines(os.path.join(self.out, "sends.jsonl"))]
        self.assertEqual(accepted, [True] * 20 + [None] * 5)

    def test_a_partly_refused_batch_counts_what_was_accepted_f01(self):
        done, _ = self.run_queue("C-embedding-backlog.yaml", queue={"messages": 25}, fail_send=2,
                                 fail_send_mode="partial")
        self.assertEqual(done.returncode, 1)
        run = self.run_json()
        self.assertEqual(run["messagesAccepted"], 13)
        self.assertEqual((run["messagesRefused"], run["messagesOutcomeUnknown"]), (7, 0))

    def test_no_vector_written_is_failed_f05(self):
        # the ledger already holds an older put batch: only new ones count
        for number, (source, writes, failed_batches) in enumerate((
                ("C-embedding-backlog.yaml", False, 0), ("C-embedding-backlog.yaml", "failed", 1),
                ("F-poison-message.yaml", False, 0), ("F-poison-message.yaml", "failed", 1))):
            self.new_out("write%d" % number)
            done, _ = self.run_queue(source, writes=writes)
            self.assertEqual(done.returncode, 1, done.stdout)
            run = self.run_json()
            self.assertEqual((run["status"], run["stopReason"]), ("FAILED", "NO_VECTORS_WRITTEN"))
            evidence = run["vectorWriteEvidence"]
            self.assertEqual((evidence["newSucceededPutBatches"], evidence["newFailedPutBatches"]), (0, failed_batches))

    def test_unreadable_write_evidence_is_never_completed_f05(self):
        done, _ = self.run_queue("C-embedding-backlog.yaml", fail_ops={"query": 2})
        self.assertEqual(done.returncode, 1)
        run = self.run_json()
        self.assertEqual((run["status"], run["stopReason"]), ("FAILED", "WRITE_EVIDENCE_UNAVAILABLE"))
        self.assertIsNone(run["vectorWriteEvidence"]["newSucceededPutBatches"])

    def test_an_unreadable_ledger_before_the_send_sends_nothing_f05(self):
        done, state = self.run_queue("C-embedding-backlog.yaml", fail_ops={"query": 1})
        self.assertEqual(done.returncode, 1)
        self.assertNotIn("sqs send-message-batch", state["calls"])
        self.assertEqual(self.run_json()["status"], "FAILED")
        self.new_out("unset")
        done, state = self.run_queue("C-embedding-backlog.yaml", drop=("SC_DDB_TABLE_RUN_LEDGER",))
        self.assertIn("SC_DDB_TABLE_RUN_LEDGER", done.stderr)
        self.assertEqual(state["calls"], [])

    def test_poll_interval_zero_and_huge_time_values_are_refused_f13(self):
        for key, value in (("poll_interval_seconds", 0), ("max_watch_seconds", 10 ** 9),
                           ("drain_timeout_seconds", 10 ** 9), ("scale_out_timeout_seconds", "900")):
            done, state = self.run_queue("C-embedding-backlog.yaml", queue={key: value})
            self.assertEqual(done.returncode, 1, key)
            self.assertIn(key, done.stderr)
            self.assertEqual(state["calls"], [], key)

    def test_zero_counts_right_after_the_send_are_not_a_drain_f17(self):
        done, _ = self.run_queue("C-embedding-backlog.yaml", queue={"messages": 12}, lag=4)
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        marks = self.run_json()["milestones"]
        self.assertEqual(marks["maxRunningObserved"], 2)
        self.assertGreater(marks["drainedAfterSeconds"], marks["firstTaskRunningAfterSeconds"])

    def test_a_milestone_never_observed_is_null_not_zero_f07(self):
        class Broken:
            def sample(self, phase):
                raise sclib.AwsError("x")

            class writer:
                @staticmethod
                def write(record):
                    pass

        spec = {"poll_interval_seconds": 0.001, "scale_out_timeout_seconds": 5}
        reason, marks = queue_scenario.watch(Broken(), spec, 0, 0, threading.Event(), time.monotonic() + 10)
        self.assertEqual(reason, "OBSERVATION_FAILED")
        self.assertEqual(set(marks.values()), {None})

    def test_an_aws_error_message_never_reaches_output_or_raw_files_f19(self):
        done, state = self.run_queue("C-embedding-backlog.yaml", fail_ops={"get-queue-attributes": 3})
        self.assertEqual(done.returncode, 1)
        everything = fakes.text_of(self.out) + done.stdout + done.stderr
        self.assertIn("AccessDenied", everything)
        for secret in ("arn:", "123456789012", "secret-cluster", "secret-queue"):
            self.assertNotIn(secret, everything)

    def test_the_message_cap_sends_nothing_f19(self):
        for queue in ({"messages": 25, "max_messages": 10}, {"messages": 1001, "max_messages": 5000}):
            done, state = self.run_queue("C-embedding-backlog.yaml", queue=queue)
            self.assertEqual(done.returncode, 1)
            self.assertIn("outside the cap", done.stderr)
            self.assertEqual(state["calls"], [])

    def test_the_watch_time_cap_stops_the_run_f19(self):
        done, _ = self.run_queue("C-embedding-backlog.yaml", scales=False, queue={"max_watch_seconds": 2})
        self.assertEqual(done.returncode, 1)
        self.assertEqual(self.run_json()["stopReason"], "TIME_CAP")

    def test_term_keeps_the_partial_run_f19(self):
        args, env = self.queue_args("C-embedding-backlog.yaml", queue={"messages": 12}, scales=False)
        process = fakes.start_tool("load-tests/queue_scenario.py", args, env)
        self.addCleanup(process.kill)
        observations = os.path.join(self.out, "observations.jsonl")
        started = time.monotonic()
        while time.monotonic() - started < 30 and process.poll() is None:
            if os.path.exists(observations) and len(fakes.lines(observations)) >= 3:
                break
            time.sleep(0.05)
        process.send_signal(signal.SIGTERM)
        process.communicate(timeout=60)
        self.assertEqual(process.returncode, 1)
        run = self.run_json()
        self.assertEqual((run["stopReason"], run["messagesAccepted"]), ("TERMINATED", 12))


if __name__ == "__main__":
    unittest.main()
