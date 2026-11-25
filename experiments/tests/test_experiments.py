"""Offline tests of manifests, collector and plot builder.

    python3 -m unittest discover -s experiments/tests

A test name ending in _fNN guards phase 15 review finding NN. The collector and plot builder run
from a throwaway copy of the tools (fakes.make_repo), so nothing is written into the repository.
"""

import datetime
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest

import yaml

sys.dont_write_bytecode = True
REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(REPO, "load-tests", "tests"))
sys.path.insert(0, os.path.join(REPO, "load-tests"))

import fakes  # noqa: E402
import query_load  # noqa: E402

MANIFESTS = os.path.join(REPO, "experiments", "manifests")
NAMES = sorted(os.listdir(MANIFESTS))
RUN_C = "C-embedding-backlog-abc1234-20260101T000000Z"


def load(name):
    with open(os.path.join(MANIFESTS, name)) as handle:
        return yaml.safe_load(handle)


def read(path):
    with open(os.path.join(REPO, path)) as handle:
        return handle.read()


def approval_form_problems(doc):
    """Why a committed approval state is not acceptable: neither the untouched pending block nor a
    well-formed approval, or an approval on a blocked manifest."""
    approval = doc.get("approval")
    if not isinstance(approval, dict) or set(approval) != {"status", "approved_by", "approved_on"}:
        return ["the approval block must hold status, approved_by and approved_on"]
    if approval["status"] == "PENDING_HUMAN_APPROVAL":
        if approval["approved_by"] is None and approval["approved_on"] is None:
            return []
        return ["a pending approval names an approver or a date"]
    problems = []
    if approval["status"] != "APPROVED":
        problems.append("status is %r, not exactly APPROVED" % approval["status"])
    who, when = approval["approved_by"], approval["approved_on"]
    if not isinstance(who, str) or not who.strip():
        problems.append("approved_by is empty")
    if isinstance(when, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}", when):
        try:
            when = datetime.date.fromisoformat(when)
        except ValueError:
            pass
    if type(when) is not datetime.date:
        problems.append("approved_on is %r, not a YYYY-MM-DD date" % approval["approved_on"])
    blocked_precondition = any(str(text).strip().upper().startswith("BLOCKED")
                               for entry in doc.get("preconditions") or []
                               for text in (entry if isinstance(entry, dict) else [entry]))
    if doc.get("blocked") is not False or blocked_precondition:
        problems.append("a blocked manifest is approved")
    return problems


class ManifestTest(unittest.TestCase):
    def test_one_manifest_per_experiment_with_the_required_fields(self):
        self.assertEqual([load(n)["id"] for n in NAMES], list("ABCDEF"))
        for name in NAMES:
            doc = load(name)
            for key in ("purpose", "dataset_snapshot", "commit_sha", "resource_bounds", "cost_cap_usd",
                        "stop_conditions", "metrics"):
                self.assertTrue(doc.get(key), "%s lacks %s" % (name, key))
            self.assertEqual((doc["region"], doc["environment"]), ("us-east-2", "dev"))
            self.assertEqual(name, doc["name"] + ".yaml")
            estimate = doc["cost_estimate_usd"]
            self.assertLessEqual(estimate.get("worst_case", estimate["incremental"]), doc["cost_cap_usd"])
            if doc["runner"] == "load-test":
                self.assertEqual(doc["args"], ["--manifest", "experiments/manifests/" + name])

    def test_blocked_is_stated_and_b_and_f_are_blocked_f03(self):
        self.assertEqual({load(n)["id"]: load(n).get("blocked") for n in NAMES},
                         {"A": False, "B": True, "C": False, "D": False, "E": False, "F": True})
        # a BLOCKED precondition blocks as much as the flag does
        held = {load(n)["id"] for n in NAMES
                if any(str(p).startswith("BLOCKED") for p in load(n).get("preconditions") or [])}
        self.assertEqual(held, {"B", "F"})

    def test_every_approval_is_pending_or_well_formed_and_no_blocked_manifest_is_approved(self):
        found = {name: approval_form_problems(load(name)) for name in NAMES}
        self.assertEqual({name: problems for name, problems in found.items() if problems}, {})

    def test_the_approval_check_refuses_malformed_and_blocked_approvals(self):
        good = {"blocked": False,
                "approval": {"status": "APPROVED", "approved_by": "A Person", "approved_on": "2026-01-01"}}
        self.assertEqual(approval_form_problems(good), [])
        dated = dict(good["approval"], approved_on=datetime.date(2026, 1, 1))
        self.assertEqual(approval_form_problems(dict(good, approval=dated)), [])
        for field, value in (("status", "Approved"), ("status", "REJECTED"), ("approved_by", ""),
                             ("approved_by", None), ("approved_on", "11/25/2026"), ("approved_on", "2026-13-01"),
                             ("approved_on", "20260101"), ("approved_on", None)):
            doc = dict(good, approval=dict(good["approval"], **{field: value}))
            self.assertTrue(approval_form_problems(doc), (field, value))
        for blocked in (dict(good, blocked=True), dict(good, preconditions=["BLOCKED: x"])):
            self.assertEqual(approval_form_problems(blocked), ["a blocked manifest is approved"])
        pending = {"status": "PENDING_HUMAN_APPROVAL", "approved_by": "A Person", "approved_on": None}
        self.assertTrue(approval_form_problems(dict(good, approval=pending)))

    def test_every_manifest_records_the_identity_and_its_least_privilege_risk(self):
        for name in NAMES:
            identity = load(name)["identity"]
            self.assertEqual((identity["iam_user"], identity["aws_profile"]), ("CS441_Dev", "cs441"), name)
            self.assertIn("least-privilege risk", identity["risk"], name)

    def test_worker_autoscaling_matches_the_step_scaling_source(self):
        source = read("infra/terraform/modules/compute/main.tf")

        def resource(kind, name):
            return re.search(r'resource "%s" "%s" \{\n(.*?)\n\}\n' % (kind, name), source, re.S).group(1)

        def value(block, key):
            return re.search(r'^\s*%s\s*=\s*"?(\w+)' % key, block, re.M).group(1)

        threshold = re.search(r'variable "worker_backlog_per_task" \{.*?default\s*=\s*(\d+)',
                              read("infra/terraform/environments/dev/variables.tf"), re.S).group(1)
        for name in ("C-embedding-backlog.yaml", "F-poison-message.yaml"):
            scaling = load(name)["resource_bounds"]["worker_autoscaling"]
            self.assertEqual(scaling["scale_out"]["backlog_step_threshold"], int(threshold))
            for side in ("scale_out", "scale_in"):
                policy = resource("aws_appautoscaling_policy", "worker_" + side)
                alarm = resource("aws_cloudwatch_metric_alarm", "worker_" + side)
                recorded = scaling[side]
                self.assertEqual(value(policy, "policy_type"), scaling["policy_type"])
                self.assertEqual(value(policy, "adjustment_type"), recorded["adjustment_type"])
                self.assertEqual(int(value(policy, "cooldown")), recorded["cooldown_seconds"])
                self.assertEqual(int(value(alarm, "evaluation_periods")), recorded["evaluation_periods"])
                self.assertEqual(int(value(alarm, "threshold")), recorded["alarm_threshold_messages"])

    def test_c_and_f_costs_include_embedding_calls(self):
        for name, valid in (("C-embedding-backlog.yaml", "messages"), ("F-poison-message.yaml", "valid_duplicates")):
            doc = load(name)
            estimate, calls = doc["cost_estimate_usd"], doc["cost_estimate_usd"]["embedding"]
            per_pass = (calls["assumed_items"] * calls["assumed_tokens_per_item"] / 1000
                        * calls["usd_per_1000_input_tokens"])
            self.assertEqual(calls["price_source"], "from memory, unverified")
            self.assertEqual(calls["expected_full_passes"], doc["resource_bounds"]["services"]["embedding-worker"]
                             ["max_tasks"])
            # a put that fails does not fail the message, so every valid message can embed once
            self.assertEqual(calls["put_failure_full_passes"], doc["queue"][valid])
            self.assertEqual(calls["redelivery_ceiling_full_passes"],
                             doc["queue"][valid] * doc["resource_bounds"]["worker_dead_letter_max_receive_count"]
                             ["value"])
            for kind in ("expected", "put_failure", "redelivery_ceiling"):
                self.assertAlmostEqual(calls[kind + "_usd"], calls[kind + "_full_passes"] * per_pass, 6, name + kind)
            self.assertAlmostEqual(estimate["incremental"], estimate["infrastructure"] + calls["expected_usd"], 6)
            total = estimate["infrastructure"] + calls["put_failure_usd"]
            self.assertAlmostEqual(estimate["put_failure_total_usd"], total, 6)
            self.assertIs(estimate["cap_covers_put_failure"], total <= doc["cost_cap_usd"])
            self.assertEqual(doc["cost_cap_usd"], 1.0)

    def test_d_and_e_carry_the_approved_caps(self):
        d, e = load("D-query-load.yaml"), load("E-retrieval-modes.yaml")
        self.assertEqual((d["limits"]["max_duration_seconds"], d["load"]["max_load_seconds"],
                          d["load"]["estimated_usd_per_request"], e["load"]["estimated_usd_per_request"]),
                         (2400, 2100, 0.036, 0.0375))

    def test_the_parser_inside_run_experiment_accepts_every_manifest(self):
        # the validator only: the script itself is not executed
        script = read("scripts/run-experiment.sh")
        parser = re.search(r"PARSED=\"\$\(python3 -I - \"\$MANIFEST\" <<'PY'\n(.*?)\nPY\n", script, re.S).group(1)
        for name in NAMES:
            done = subprocess.run([sys.executable, "-I", "-", os.path.join(MANIFESTS, name)], input=parser,
                                  capture_output=True, text=True)
            self.assertEqual(done.returncode, 0, name + ": " + done.stderr)
            parsed = json.loads(done.stdout)
            self.assertEqual(parsed["metrics"], load(name)["metrics"])
            if parsed["runner"] == "load-test":
                target = os.path.join(REPO, "load-tests", parsed["target"])
                self.assertTrue(os.access(target, os.X_OK), target)

    def test_service_and_emr_bounds_match_terraform(self):
        variables = read("infra/terraform/environments/dev/variables.tf")
        sizing = variables[variables.index('variable "service_sizing"'):]
        for name in NAMES:
            bounds = load(name)["resource_bounds"]
            for service, values in bounds.get("services", {}).items():
                if service == "caveat":
                    continue
                block = re.search(r'"%s" = \{(.*?)\}' % service, sizing, re.S).group(1)
                found = dict(re.findall(r"(\w+)\s*=\s*(\d+)", block))
                self.assertEqual(
                    (values["cpu_units"], values["memory_mib"], values["min_tasks"], values["max_tasks"]),
                    tuple(int(found[k]) for k in ("cpu", "memory", "min_capacity", "max_capacity")))
            if "emr" in bounds:
                default = lambda var: re.search(r'variable "%s" \{.*?default\s*=\s*(\w+)' % var, variables, re.S).group(1)
                self.assertEqual(bounds["emr"]["managed_scaling_max_units"], int(default("emr_scaling_max_units")))
                self.assertEqual(bounds["emr"]["managed_scaling_min_units"], int(default("emr_scaling_min_units")))
                self.assertEqual(bounds["emr"]["initial_core_nodes"], int(default("emr_core_instance_count")))
                self.assertEqual(bounds["emr"]["idle_timeout_seconds"], int(default("emr_idle_timeout_seconds")))
                self.assertEqual(load(name)["limits"]["emr_max_nodes"], int(default("emr_scaling_max_units")))

    def test_planned_load_fits_the_caps(self):
        questions = json.loads(read("load-tests/questions/query-v1.json"))
        for name in NAMES:
            doc = load(name)
            duration = doc["limits"]["max_duration_seconds"]
            if "load" in doc:
                phases = query_load.plan_phases(doc["load"], questions["questions"], questions["window"])
                query_load.check_bounds(doc["load"], phases, len(questions["questions"]))
                self.assertLess(doc["load"]["max_load_seconds"] + doc["load"]["request_timeout_seconds"], duration)
            if "queue" in doc:
                self.assertLess(doc["queue"]["max_watch_seconds"], duration)
            ids = [m["id"] for m in doc["metrics"]]
            self.assertEqual(len(ids), len(set(ids)), name)
            self.assertTrue(all(re.fullmatch(r"[a-z][A-Za-z0-9_]*", i) for i in ids))

    def test_d_time_caps_leave_room_for_every_level_at_its_worst_f04(self):
        doc = load("D-query-load.yaml")
        load_plan, questions = doc["load"], json.loads(read("load-tests/questions/query-v1.json"))["questions"]
        levels = len(load_plan["levels"])
        timeout = load_plan["request_timeout_seconds"]
        worst = (len(questions) * timeout + levels * (load_plan["level_max_seconds"] + timeout)
                 + (levels - 1) * load_plan["cooldown_seconds"])
        self.assertGreaterEqual(load_plan["max_load_seconds"], worst)
        self.assertGreater(doc["limits"]["max_duration_seconds"], load_plan["max_load_seconds"] + timeout)

    def test_the_price_estimate_fits_the_cost_cap_f04(self):
        for name in NAMES:
            doc = load(name)
            if "load" in doc:
                price = doc["load"].get("estimated_usd_per_request")
                self.assertIsNotNone(price, name)
                self.assertLessEqual(price * doc["load"]["max_total_requests"], doc["cost_cap_usd"] + 1e-9)

    def test_the_plain_http_decision_is_a_precondition_of_d_and_e_f21(self):
        for name in ("D-query-load.yaml", "E-retrieval-modes.yaml"):
            text = " ".join(load(name).get("preconditions") or [])
            for words in ("SC_ALLOW_PLAINTEXT_HTTP=1", "dedicated short-lived API token", "revokes that token",
                          "this experiment"):
                self.assertIn(words, text, name)

    def test_no_resource_name_endpoint_or_account_is_hardcoded(self):
        for name in NAMES:
            text = read("experiments/manifests/" + name)
            self.assertNotRegex(text, r"\b\d{12}\b|amazonaws\.com|arn:aws|scholarchain-dev")


class RepoTest(unittest.TestCase):
    """A temporary repository with the tools, a stub AWS CLI and a stub git."""

    SECRETS = ("123456789012", "secret-cluster", "secret-queue", "secret-dlq", "secret-log-group", "amazonaws")

    def setUp(self):
        self.tmp = os.path.realpath(tempfile.mkdtemp())
        self.addCleanup(self.remove)
        self.repo, git_env, self.committed = fakes.make_repo(self.tmp)
        self.build = os.path.join(self.tmp, "build")
        self.env = fakes.make_stub(self.tmp)
        self.env.update(git_env)
        self.env.update(SC_BUILD_DIR=self.build, SCHOLARCHAIN_APPROVED_ENVS="dev", SC_ECS_CLUSTER="secret-cluster",
                        SC_EMBEDDING_QUEUE_NAME="secret-queue", SC_EMBEDDING_DLQ_NAME="secret-dlq",
                        SC_WORKER_LOG_GROUP="secret-log-group")

    def remove(self):
        for base, _, _ in os.walk(self.tmp):
            os.chmod(base, 0o755)
        shutil.rmtree(self.tmp)

    def stub(self, **options):
        path = self.env["STUB_STATE"]
        with open(path) as handle:
            doc = json.load(handle)
        doc.update(options)
        with open(path, "w") as handle:
            json.dump(doc, handle)

    def state(self):
        with open(self.env["STUB_STATE"]) as handle:
            return json.load(handle)


class CollectorBase(RepoTest):
    """A finished C run with complete results, ready to collect. Holds no tests."""

    def setUp(self):
        super().setUp()
        self.runner = os.path.join(self.build, "experiments", RUN_C)
        os.makedirs(self.runner)
        self.write_runner()
        self.record = os.path.join(self.build, "experiment-" + RUN_C + ".json")
        self.write_record()

    def manifest(self, name="C-embedding-backlog"):
        return os.path.join(self.repo, "experiments", "manifests", name + ".yaml")

    def write_runner(self, **run):
        doc = {"status": "STOPPED", "stopReason": "NO_SCALE_OUT", "messagesPlanned": 300, "messagesAccepted": 0,
               "messagesRefused": 0, "messagesOutcomeUnknown": 0, "milestones": {},
               "vectorWriteEvidence": {"status": "OK", "newSucceededPutBatches": 0, "newFailedPutBatches": 0},
               "manifestSha256": query_load.sclib.sha256_file(
                   os.path.join(self.committed, "experiments", "manifests", "C-embedding-backlog.yaml"))}
        doc.update(run)
        with open(os.path.join(self.runner, "run.json"), "w") as handle:
            json.dump(doc, handle)
        with open(os.path.join(self.runner, "sends.jsonl"), "w") as handle:
            handle.write("")
        with open(os.path.join(self.runner, "observations.jsonl"), "w") as handle:
            for second, visible in ((0, 0), (15, 10)):
                handle.write(json.dumps({"atUtc": "2026-01-01T00:00:%02d.000Z" % second, "phase": "drain",
                                         "visible": visible, "inFlight": 0, "dlqVisible": 0, "desired": 0,
                                         "running": 0, "pending": 0}) + "\n")

    def write_record(self, ended="2026-01-01T00:10:00Z", name="C-embedding-backlog", runner="load-test"):
        with open(self.record, "w") as handle:
            json.dump({"experiment": name, "manifest": "experiments/manifests/%s.yaml" % name, "runner": runner,
                       "environment": "dev", "region": "us-east-2", "commitSha": "abc1234",
                       "startedAtUtc": "2026-01-01T00:00:30Z", "endedAtUtc": ended, "runnerExitCode": 1,
                       "timedOut": False, "resourceLimits": {"max_duration_seconds": 3600},
                       "rawResultDirectory": self.runner, "metricLocations": []}, handle)

    def collect(self, env=None, name="C-embedding-backlog", extra=()):
        return fakes.run_tool(os.path.join(self.repo, "experiments", "bin", "collect.py"), [
            "--env", "dev", "--manifest", self.manifest(name), "--record", self.record] + list(extra),
            env or self.env)

    def without(self, name):
        return {k: v for k, v in self.env.items() if k != name}

    @property
    def raw(self):
        return os.path.join(self.repo, "experiments", "raw")

    @property
    def run_dir(self):
        return os.path.join(self.raw, "C-embedding-backlog", RUN_C)

    def raw_json(self, name):
        with open(os.path.join(self.run_dir, name)) as handle:
            return json.load(handle)

    def build_summary(self):
        out = os.path.join(self.repo, "experiments", "derived", "t")
        done = fakes.run_tool(os.path.join(self.repo, "experiments", "bin", "build_plots.py"),
                              ["--run", self.run_dir, "--out", out], self.env)
        self.assertEqual(done.returncode, 0, done.stderr)
        contents = {}
        for name in sorted(os.listdir(out)):
            with open(os.path.join(out, name), "rb") as handle:
                contents[name] = handle.read()
        return contents

    def assert_refused_and_rerunnable(self, done, words):
        self.assertEqual(done.returncode, 1, done.stdout + done.stderr)
        self.assertIn(words, done.stderr)
        self.assertFalse(os.path.exists(self.run_dir))


class CollectorTest(CollectorBase):
    def test_collects_a_failed_run_read_only_at_60s_without_resource_names(self):
        done = self.collect()
        self.assertEqual(done.returncode, 0, done.stderr)
        metrics = self.raw_json("metrics.json")
        self.assertEqual(metrics["periodSeconds"], 60)
        self.assertEqual((metrics["startUtc"], metrics["endUtc"]), ("2025-12-31T23:55:00Z", "2026-01-01T00:13:00Z"))
        self.assertEqual({q["MetricStat"]["Period"] for q in self.state()["queries"]}, {60})
        self.assertEqual(len(metrics["series"]), len(load("C-embedding-backlog.yaml")["metrics"]))
        self.assertTrue(os.path.isfile(os.path.join(self.run_dir, "runner", "observations.jsonl")))
        for base, _, files in os.walk(self.run_dir):
            self.assertEqual(stat.S_IMODE(os.stat(base).st_mode), 0o555)
            for name in files:
                self.assertEqual(stat.S_IMODE(os.stat(os.path.join(base, name)).st_mode), 0o444)
        self.assertEqual(os.listdir(os.path.dirname(self.run_dir)), [RUN_C])
        stored = fakes.text_of(self.run_dir)
        self.assertNotIn(self.tmp, stored)  # no absolute local path in raw data
        everything = stored + done.stdout + done.stderr
        for secret in self.SECRETS:
            self.assertNotIn(secret, everything)
        self.assertIn('"abc1234"', stored)
        for call in self.state()["calls"]:
            self.assertRegex(call, r" (get-|describe-|filter-)")

    def test_refuses_to_overwrite_an_existing_run_directory(self):
        self.assertEqual(self.collect().returncode, 0)
        before = fakes.text_of(self.run_dir)
        again = self.collect()
        self.assertEqual(again.returncode, 1)
        self.assertIn("already exists", again.stderr)
        self.assertEqual(fakes.text_of(self.run_dir), before)

    def test_refuses_before_the_window_closes_and_without_approval(self):
        self.write_record("2099-01-01T00:00:00Z")
        self.assertIn("window closes", self.collect().stderr)
        self.write_record()
        self.assertEqual(self.collect(dict(self.env, SCHOLARCHAIN_APPROVED_ENVS="prod")).returncode, 1)
        self.assertFalse(os.path.exists(self.run_dir))

    def test_a_missing_variable_seals_nothing_and_can_be_rerun_f02(self):
        for variable in ("SC_EMBEDDING_DLQ_NAME", "SC_ECS_CLUSTER", "SC_WORKER_LOG_GROUP"):
            self.assert_refused_and_rerunnable(self.collect(self.without(variable)), variable)
        self.assertEqual(self.state()["calls"], [])
        self.assertEqual(self.collect().returncode, 0)

    def test_missing_runner_results_seal_nothing_and_can_be_rerun_f02(self):
        self.assert_refused_and_rerunnable(self.collect(dict(self.env, SC_BUILD_DIR=self.tmp + "/elsewhere")),
                                           "runner result directory")
        os.rename(os.path.join(self.runner, "observations.jsonl"), os.path.join(self.runner, "aside"))
        self.assert_refused_and_rerunnable(self.collect(), "observations.jsonl")
        os.rename(os.path.join(self.runner, "aside"), os.path.join(self.runner, "observations.jsonl"))
        shutil.move(self.runner, self.runner + ".gone")
        self.assert_refused_and_rerunnable(self.collect(), "runner result directory")
        shutil.move(self.runner + ".gone", self.runner)
        self.assertEqual(self.collect().returncode, 0)

    def test_a_missing_pipeline_record_seals_nothing_f02(self):
        self.write_record(name="A-emr-baseline", runner="pipeline")
        env = dict(self.env, SC_EMR_CLUSTER_ID="j-secret")
        done = self.collect(env, name="A-emr-baseline")
        self.assertEqual(done.returncode, 1, done.stderr)
        self.assertIn("--pipeline-record", done.stderr)
        self.assertFalse(os.path.exists(os.path.join(self.raw, "A-emr-baseline", RUN_C)))

    def test_a_deployed_service_that_is_not_found_seals_nothing_f02(self):
        self.stub(absent_services=["query-api"])
        self.assert_refused_and_rerunnable(self.collect(), "deployed service")
        self.stub(absent_services=[])
        self.assertEqual(self.collect().returncode, 0)

    def test_allow_missing_seals_and_records_what_is_missing_f02(self):
        shutil.rmtree(self.runner)
        done = self.collect(self.without("SC_ECS_CLUSTER"), extra=["--allow-missing"])
        self.assertEqual(done.returncode, 0, done.stderr)
        missing = " ".join(m["item"] for m in self.raw_json("collection.json")["missingInputs"])
        self.assertIn("SC_ECS_CLUSTER", missing)
        self.assertIn("runner:directory", missing)
        self.assertIn("SC_ECS_CLUSTER", self.build_summary()["summary.md"].decode())

    def test_a_truncated_run_json_can_still_be_sealed_on_request_f02(self):
        with open(os.path.join(self.runner, "run.json"), "w") as handle:
            handle.write('{"status": ')
        self.assert_refused_and_rerunnable(self.collect(), "readable runner file run.json")
        done = self.collect(extra=["--allow-missing"])
        self.assertEqual(done.returncode, 0, done.stderr)

    def test_a_result_made_by_another_manifest_is_refused_f18(self):
        self.write_runner(manifestSha256="0" * 64)
        self.assert_refused_and_rerunnable(self.collect(), "another manifest")

    def in_process(self, setup):
        """Runs collect.main with a fault injected by `setup` (Python source, `c` is the module)."""
        script = "\n".join(("import sys", "sys.dont_write_bytecode = True", "sys.path.insert(0, %r)"
                            % os.path.join(self.repo, "experiments", "bin"), "import collect as c", setup,
                            "sys.exit(c.main(sys.argv[1:]))"))
        return subprocess.run([sys.executable, "-c", script, "--env", "dev", "--manifest", self.manifest(),
                               "--record", self.record], env=fakes.clean_env(self.env), capture_output=True,
                              text=True, timeout=120)

    def test_a_failed_seal_leaves_nothing_and_can_be_rerun_f15(self):
        done = self.in_process("def fail(directory):\n    open(directory + '/SHA256SUMS', 'w').write('half')\n"
                               "    raise OSError(28, 'disk full')\nc.seal = fail")
        self.assertEqual(done.returncode, 1, done.stderr)
        self.assertFalse(os.path.exists(self.run_dir))
        again = self.collect()
        self.assertEqual(again.returncode, 0, again.stderr)

    def test_a_failed_move_leaves_nothing_and_can_be_rerun_f15(self):
        done = self.in_process(
            "import os, shutil\ndef partial(src, dst, *a, **k):\n    os.makedirs(dst)\n"
            "    open(os.path.join(dst, 'half'), 'w').write('x')\n    raise OSError(28, 'disk full')\n"
            "def cross(src, dst):\n    raise OSError(18, 'cross-device')\n"
            "os.rename = cross\nshutil.copytree = partial")
        self.assertEqual(done.returncode, 1, done.stderr)
        self.assertFalse(os.path.exists(self.run_dir))
        again = self.collect()
        self.assertEqual(again.returncode, 0, again.stderr)

    def test_the_manifest_of_the_run_commit_decides_everything_f10(self):
        with open(self.manifest()) as handle:
            doc = yaml.safe_load(handle)
        doc["dataset_snapshot"] = {"date": "edited-after-the-run"}
        doc["metrics"] = doc["metrics"][:1]
        with open(self.manifest(), "w") as handle:
            yaml.safe_dump(doc, handle)
        done = self.collect()
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertEqual(self.raw_json("collection.json")["datasetSnapshot"]["date"], "2026-11-01")
        self.assertEqual(len(self.raw_json("metrics.json")["series"]),
                         len(load("C-embedding-backlog.yaml")["metrics"]))
        with open(os.path.join(self.run_dir, "manifest.yaml")) as handle:
            self.assertNotIn("edited-after-the-run", handle.read())

    def test_a_manifest_absent_from_the_run_commit_seals_nothing_f10(self):
        os.remove(os.path.join(self.committed, "experiments", "manifests", "C-embedding-backlog.yaml"))
        self.assert_refused_and_rerunnable(self.collect(), "run's commit")

    def test_images_are_dated_at_collection_and_never_carry_the_registry_f11(self):
        self.stub(image="123456789012.dkr.ecr.x.amazonaws.com/app")
        self.assert_refused_and_rerunnable(self.collect(), "deployment:query-api:imageTag")
        done = self.collect(extra=["--allow-missing"])
        self.assertEqual(done.returncode, 0, done.stderr)
        deployment = self.raw_json("deployment.json")
        self.assertEqual({t for s in deployment["services"] for t in s["imageTags"]}, {None})
        self.assertIn("observedAtUtc", deployment)
        self.assertNotIn("123456789012", fakes.text_of(self.run_dir))
        summary = self.build_summary()["summary.md"].decode()
        self.assertIn("imageTagsAtCollectionTime", summary)
        self.assertIn("untagged", summary)
        self.assertNotIn("deployedImageTags", summary)

    def test_a_raw_root_elsewhere_cannot_be_chosen_f16(self):
        done = self.collect(extra=["--raw-root", os.path.join(self.tmp, "anywhere")])
        self.assertEqual(done.returncode, 2, done.stderr)
        self.assertFalse(os.path.exists(os.path.join(self.tmp, "anywhere")))

    def test_a_symlinked_experiment_directory_is_refused_f16(self):
        os.makedirs(self.raw)
        os.makedirs(os.path.join(self.tmp, "anywhere"))
        os.symlink(os.path.join(self.tmp, "anywhere"), os.path.join(self.raw, "C-embedding-backlog"))
        done = self.collect()
        self.assertEqual(done.returncode, 1, done.stderr)
        self.assertEqual(os.listdir(os.path.join(self.tmp, "anywhere")), [])

    def test_derived_output_stays_under_experiments_derived_f16(self):
        self.assertEqual(self.collect().returncode, 0)
        other = os.path.join(self.tmp, "someones-directory")
        os.makedirs(other)
        with open(os.path.join(other, "summary.md"), "w") as handle:
            handle.write("keep me")
        done = fakes.run_tool(os.path.join(self.repo, "experiments", "bin", "build_plots.py"),
                              ["--run", self.run_dir, "--out", other], self.env)
        self.assertEqual(done.returncode, 1)
        with open(os.path.join(other, "summary.md")) as handle:
            self.assertEqual(handle.read(), "keep me")

    def test_a_failed_aws_call_writes_nothing_and_hides_the_message_f19(self):
        self.stub(fail_ops={"get-metric-data": 1})
        done = self.collect()
        self.assertEqual(done.returncode, 1)
        self.assertFalse(os.path.exists(self.run_dir))
        self.assertEqual(os.listdir(os.path.dirname(self.run_dir)), [])
        self.assertIn("AccessDenied", done.stderr)
        for secret in ("arn:", "123456789012", "secret-cluster", "secret-queue"):
            self.assertNotIn(secret, done.stdout + done.stderr)

    def test_only_this_runs_traces_are_kept_from_the_stage_log_f09(self):
        self.write_record(name="D-query-load")
        env = dict(self.env, SC_QUERY_API_LOG_GROUP="secret-log-group", SC_ALB_ARN_SUFFIX="x",
                   SC_TG_QUERY_API_ARN_SUFFIX="x", SC_NEPTUNE_CLUSTER_ID="x", SC_DDB_TABLE_QUERY_SESSION="x",
                   SC_DDB_TABLE_EVIDENCE_BUNDLE="x", SC_DDB_TABLE_GRAPH_READ_MODEL="x")
        run_dir = os.path.join(self.raw, "D-query-load", RUN_C)
        digest = query_load.sclib.sha256_file(os.path.join(self.committed, "experiments", "manifests",
                                                           "D-query-load.yaml"))
        for trace, expected in (("t1", ["t1"]), (None, [])):
            with open(os.path.join(self.runner, "run.json"), "w") as handle:
                json.dump({"manifestSha256": digest, "status": "COMPLETED", "requestsSent": 1,
                           "plannedPhases": [{"phase": "level-1", "concurrency": 1, "planned": 1}]}, handle)
            with open(os.path.join(self.runner, "requests.jsonl"), "w") as handle:
                handle.write(json.dumps({"traceId": trace, "status": 200}) + "\n")
            if trace is None:  # without a trace id the stage log cannot be attributed: that is missing
                done = self.collect(env, name="D-query-load")
                self.assertIn("log:workflow_stages (NO_TRACE_IDS)", done.stderr)
                self.assertFalse(os.path.exists(run_dir))
            done = self.collect(env, name="D-query-load", extra=[] if trace else ["--allow-missing"])
            self.assertEqual(done.returncode, 0, done.stderr)
            path = os.path.join(run_dir, "workflow_stages.jsonl")
            kept = [r["traceId"] for r in fakes.lines(path)] if os.path.exists(path) else []
            self.assertEqual(kept, expected)
            for base, _, _ in os.walk(run_dir):
                os.chmod(base, 0o755)
            shutil.rmtree(run_dir)

    def test_derived_outputs_are_reproducible_and_changed_raw_data_is_refused(self):
        done = self.collect()
        self.assertEqual(done.returncode, 0, done.stderr)
        first = self.build_summary()
        self.assertEqual(first, self.build_summary())
        for name in ("summary.md", "metrics.csv", "queue-timeline.csv", "queue-timeline.svg", "ecs-tasks.svg",
                     "sqs-depth.svg", "worker-outcomes.csv"):
            self.assertIn(name, first)
        self.assertIn(b"NO_SCALE_OUT", first["summary.md"])
        self.assertIn(b"reject UNDECODABLE_MESSAGE,1", first["worker-outcomes.csv"])
        victim = os.path.join(self.run_dir, "metrics.json")
        os.chmod(victim, 0o644)
        with open(victim, "a") as handle:
            handle.write(" ")
        done = fakes.run_tool(os.path.join(self.repo, "experiments", "bin", "build_plots.py"),
                              ["--run", self.run_dir], self.env)
        self.assertEqual(done.returncode, 1)
        self.assertIn("changed", done.stderr)


class AllowMissingTest(CollectorBase):
    """Human decision 2: --allow-missing is explicit, every omission is listed and shown."""

    def items(self):
        return [m["item"] for m in self.raw_json("collection.json")["missingInputs"]]

    def derived(self):
        return {name: content.decode() for name, content in self.build_summary().items()}

    def assert_all_shown(self, out):
        for item in self.items():
            self.assertIn(item, out["missing-inputs.csv"])
            self.assertIn("| %s |" % item, out["summary.md"])

    def refused(self, done, *items):
        self.assertEqual(done.returncode, 1, done.stdout + done.stderr)
        for item in items:
            self.assertIn(item + " (", done.stderr)
        self.assertFalse(os.path.exists(self.run_dir))

    def table(self, text):
        rows = [line.split(",") for line in text.splitlines()]
        return {r[0]: dict(zip(rows[0], r)) for r in rows[1:]}

    def test_only_the_full_flag_typed_on_the_command_line_allows_an_omission(self):
        self.stub(no_data=["sqs_age_oldest"])
        for flag in ("--allow", "--allow-m", "--allow-missin"):
            done = self.collect(extra=[flag])
            self.assertEqual(done.returncode, 2, flag + done.stderr)
            self.assertFalse(os.path.exists(self.run_dir))
        env = dict(self.env, SC_ALLOW_MISSING="1", ALLOW_MISSING="1", SCHOLARCHAIN_ALLOW_MISSING="true")
        self.refused(self.collect(env), "metric:sqs_age_oldest")
        committed = os.path.join(self.committed, "experiments", "manifests", "C-embedding-backlog.yaml")
        with open(committed) as handle:
            doc = yaml.safe_load(handle)
        doc["allow_missing"] = doc["collect"]["allow_missing"] = True
        with open(committed, "w") as handle:
            yaml.safe_dump(doc, handle)
        self.write_runner()
        self.refused(self.collect(), "metric:sqs_age_oldest")

    def test_metrics_and_logs_without_data_refuse_then_are_listed_and_shown(self):
        self.stub(no_data=["sqs_age_oldest", "sqs_depth_dlq"], log_messages=["WARN reject store=s key=k code=X"])
        expected = ["metric:sqs_depth_dlq", "metric:sqs_age_oldest", "log:worker_outcomes:batch-lines"]
        self.refused(self.collect(), *expected)
        done = self.collect(extra=["--allow-missing"])
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertEqual(sorted(self.items()), sorted(expected))
        self.assertTrue(self.raw_json("collection.json")["allowMissing"])
        out = self.derived()
        self.assert_all_shown(out)
        summary = self.table(out["metrics-summary.csv"])["sqs_age_oldest"]
        self.assertEqual((summary["datapoints"], summary["min"], summary["max"]), ("missing",) * 3)
        rows = [r.split(",") for r in out["metrics.csv"].splitlines()[1:]]
        # one row per minute of the 18 minute window, for a metric with no datapoint and one with one
        self.assertEqual([r[5] for r in rows if r[1] == "sqs_age_oldest"], ["missing"] * 18)
        self.assertEqual([r[5] for r in rows if r[1] == "sqs_depth_visible"], ["1.5"] + ["missing"] * 17)
        self.assertIn("sqs_age_oldest (missing: no datapoints)", out["sqs-age.svg"])
        self.assertIn("received,missing", out["worker-outcomes.csv"])

    def test_an_empty_log_and_an_unparsed_line_are_listed(self):
        self.stub(log_messages=[])
        self.refused(self.collect(), "log:worker_outcomes")
        self.stub(log_messages=["batch received=1 acknowledged=1 retried=0 deadLettered=0", "batch received=new"])
        self.refused(self.collect(), "log:worker_outcomes:unparsed")

    def test_every_missing_item_is_listed_by_itself_and_shown(self):
        shutil.rmtree(self.runner)
        env = self.without("SC_ECS_CLUSTER")
        del env["SC_WORKER_LOG_GROUP"]
        done = self.collect(env, extra=["--allow-missing"])
        self.assertEqual(done.returncode, 0, done.stderr)
        doc = load("C-embedding-backlog.yaml")
        ecs = [m["id"] for m in doc["metrics"] if "SC_ECS_CLUSTER" in m["dimensionsFrom"].values()]
        expected = (["variable:SC_ECS_CLUSTER", "variable:SC_WORKER_LOG_GROUP", "runner:directory",
                     "runner:file:run.json", "runner:file:sends.jsonl", "runner:file:observations.jsonl",
                     "log:worker_outcomes"]
                    + ["metric:" + m for m in ecs]
                    + ["deployment:" + s for s in doc["collect"]["deployed_services"]])
        self.assertEqual(sorted(self.items()), sorted(expected))
        out = self.derived()
        self.assert_all_shown(out)
        self.assertEqual(out["deployment.csv"].splitlines()[1:],
                         ["%s,missing,missing" % s for s in doc["collect"]["deployed_services"]])
        self.assertEqual({v["status"] for v in self.table(out["runner-files.csv"]).values()}, {"missing"})
        self.assertEqual(out["sends.csv"].splitlines()[1], "valid,300,missing,missing,missing,missing,missing")
        self.assertIn("missing,missing,missing", out["queue-timeline.csv"])
        for name in ("queue-timeline.svg", "worker-timeline.svg"):
            self.assertIn("missing: no datapoints", out[name])
        self.assertIn("worker_outcomes,SKIPPED_NO_CONFIG,missing", out["log-sources.csv"])
        self.assertIn("deadLettered,missing", out["worker-outcomes.csv"])
        for metric in ecs:
            self.assertIn(",%s," % metric, out["metrics.csv"])
        self.assertIn("| messagesAccepted | missing |", out["summary.md"])

    def test_truncated_runner_files_are_listed_and_shown_not_shortened(self):
        self.write_runner(messagesAccepted=3, vectorWriteEvidence={"status": "UNAVAILABLE"})
        sent = {"kind": "valid", "accepted": True, "index": 0}
        with open(os.path.join(self.runner, "sends.jsonl"), "w") as handle:
            handle.write(json.dumps(sent) + '\n{"kind": "va')
        with open(os.path.join(self.runner, "observations.jsonl"), "a") as handle:
            handle.write(json.dumps({"atUtc": "2026-01-01T00:00:45.000Z", "error": "sqs: timed out"})
                         + '\n{"atUtc": "20')
        expected = ["runner:vectorWriteEvidence", "runner:file:sends.jsonl:unreadable-lines",
                    "runner:file:sends.jsonl:records", "runner:file:observations.jsonl:unreadable-lines",
                    "runner:observations:failed-poll:2026-01-01T00:00:45.000Z"]
        self.refused(self.collect(), *expected)
        done = self.collect(extra=["--allow-missing"])
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertEqual(sorted(self.items()), sorted(expected))
        out = self.derived()
        self.assert_all_shown(out)
        self.assertEqual(out["sends.csv"].splitlines()[1], "valid,300,1,1,0,0,records missing")
        self.assertEqual(out["queue-timeline.csv"].splitlines()[-1].split(",")[:3], ["missing"] * 3)
        self.assertIn('sends.jsonl,"present, incomplete: unreadable-lines, records"', out["runner-files.csv"])

    def test_a_truncated_run_json_can_still_be_plotted(self):
        with open(os.path.join(self.runner, "run.json"), "w") as handle:
            handle.write('{"status": ')
        done = self.collect(extra=["--allow-missing"])
        self.assertEqual(done.returncode, 0, done.stderr)
        out = self.derived()
        self.assert_all_shown(out)
        self.assertIn("| runnerStatus | missing |", out["summary.md"])
        self.assertIn('run.json,"present, incomplete: unreadable"', out["runner-files.csv"])

    def test_missing_pipeline_record_fields_are_listed_and_shown(self):
        self.write_record(name="A-emr-baseline", runner="pipeline")
        env = dict(self.env, SC_EMR_CLUSTER_ID="j-secret")
        pipeline = os.path.join(self.tmp, "pipeline-run.json")
        with open(pipeline, "w") as handle:
            json.dump({"commitSha": "abc1234", "status": "SUCCEEDED", "stages": [], "runIds": {},
                       "datasetSnapshots": {"dblp": {"date": None}}}, handle)
        run_dir = os.path.join(self.raw, "A-emr-baseline", RUN_C)
        done = self.collect(env, name="A-emr-baseline", extra=["--pipeline-record", pipeline])
        fields = ["pipeline:field:" + f for f in ("startedAtUtc", "endedAtUtc", "stages", "executionName",
                                                  "datasetSnapshots:dblp")]
        self.assertEqual(done.returncode, 1, done.stderr)
        for item in fields:
            self.assertIn(item, done.stderr)
        self.assertFalse(os.path.exists(run_dir))
        done = self.collect(env, name="A-emr-baseline", extra=["--pipeline-record", pipeline, "--allow-missing"])
        self.assertEqual(done.returncode, 0, done.stderr)
        with open(os.path.join(run_dir, "collection.json")) as handle:
            self.assertEqual([m["item"] for m in json.load(handle)["missingInputs"]], fields)
        out = os.path.join(self.repo, "experiments", "derived", "a")
        done = fakes.run_tool(os.path.join(self.repo, "experiments", "bin", "build_plots.py"),
                              ["--run", run_dir, "--out", out], self.env)
        self.assertEqual(done.returncode, 0, done.stderr)
        with open(os.path.join(out, "pipeline-run.csv")) as handle:
            table = handle.read()
        for row in ("startedAtUtc,missing", "stages,missing", "executionName,missing",
                    "datasetSnapshot dblp,missing"):
            self.assertIn(row, table)

    def test_an_unreadable_pipeline_record_is_a_listed_omission(self):
        self.write_record(name="A-emr-baseline", runner="pipeline")
        env = dict(self.env, SC_EMR_CLUSTER_ID="j-secret")
        pipeline = os.path.join(self.tmp, "pipeline-run.json")
        with open(pipeline, "w") as handle:
            handle.write('{"status": ')
        done = self.collect(env, name="A-emr-baseline", extra=["--pipeline-record", pipeline])
        self.assertIn("pipeline:record:unreadable (", done.stderr)
        done = self.collect(env, name="A-emr-baseline", extra=["--pipeline-record", pipeline, "--allow-missing"])
        self.assertEqual(done.returncode, 0, done.stderr)


def request(sequence, phase="level-1", status=200, mode=None, **fields):
    row = {"sequence": sequence, "phase": phase, "status": status, "latencyMs": 100.0 * sequence,
           "startedAtUtc": "2026-01-01T00:00:%02d.000Z" % sequence, "traceId": "t%d" % sequence,
           "abstained": False, "retrievalMode": mode, "inFlightAtStart": 1, "claimCount": 2, "evidenceCount": 3,
           "removedClaimCount": 1}
    row.update(fields)
    return row


class DerivedTest(RepoTest):
    """build_plots on hand-made raw directories."""

    def derive(self, files):
        raw = tempfile.mkdtemp(prefix="raw-run-", dir=self.tmp)
        out = os.path.join(self.repo, "experiments", "derived", os.path.basename(raw))
        fakes.make_raw(raw, files)
        done = fakes.run_tool(os.path.join(self.repo, "experiments", "bin", "build_plots.py"),
                              ["--run", raw, "--out", out], self.env)
        self.assertEqual(done.returncode, 0, done.stderr)
        contents = {}
        for name in os.listdir(out):
            with open(os.path.join(out, name)) as handle:
                contents[name] = handle.read()
        return contents

    def table(self, text):
        rows = [line.split(",") for line in text.splitlines()]
        return {r[0]: dict(zip(rows[0], r)) for r in rows[1:]}

    def test_request_tables_from_load_results(self):
        rows = [request(n, status=s) for n, s in enumerate((200, 200, 429, 503), 1)]
        out = self.derive({"runner/requests.jsonl": rows, "manifest.yaml": "load: {}\n"})
        row = out["requests-by-phase.csv"].splitlines()[1].split(",")
        self.assertEqual(row[:7], ["level-1", "4", "2", "1", "1", "0", "0"])
        self.assertEqual(row[7:10], ["100.0", "200.0", "200.0"])
        self.assertEqual(row[11], "0.25")

    def test_absent_answer_values_are_missing_not_zero_f06(self):
        nothing = {"claimCount": None, "evidenceCount": None, "removedClaimCount": None, "inFlightAtStart": None,
                   "abstained": None}
        rows = [request(1, "modes", mode="fused", **nothing), request(2, "modes", mode="graph_only"),
                request(3, "modes", mode="graph_only", **nothing)]
        out = self.derive({"runner/requests.jsonl": rows, "manifest.yaml": "load: {}\n"})
        modes = self.table(out["requests-by-retrieval-mode.csv"])
        for column in ("meanClaims", "meanEvidenceIds", "removedClaims", "maxInFlight", "abstained"):
            self.assertEqual(modes["fused"][column], "missing", column)
        self.assertEqual((modes["graph_only"]["meanClaims"], modes["graph_only"]["ok200WithoutClaimCount"]),
                         ("2.0", "1"))

    def test_gaps_failed_polls_skipped_logs_and_unrun_levels_are_shown_f07(self):
        observation = lambda second, **fields: dict(
            {"atUtc": "2026-01-01T00:00:%02d.000Z" % second, "phase": "drain", "visible": 4, "inFlight": 0,
             "dlqVisible": 0, "desired": 1, "running": 1, "pending": 0}, **fields)
        failed = {"atUtc": "2026-01-01T00:00:30.000Z", "error": "sqs: timed out"}
        series = {"id": "sqs_depth_visible", "namespace": "AWS/SQS", "metric": "m", "stat": "Maximum",
                  "status": "Complete", "values": [1, 2, 3, 4],
                  "timestamps": ["2026-01-01T00:%02d:00Z" % m for m in (0, 1, 5, 6)]}
        skipped = dict(series, id="sqs_depth_dlq", status="SKIPPED_NO_CONFIG", values=[], timestamps=[])
        out = self.derive({
            "manifest.yaml": "queue: {}\ncollect: {logs: [{kind: worker_outcomes, logGroupFrom: X}]}\n",
            "runner/observations.jsonl": [observation(0), observation(15), failed, observation(45), observation(59)],
            "collection.json": {"logs": [{"kind": "worker_outcomes", "status": "SKIPPED_NO_CONFIG"}]},
            "metrics.json": {"periodSeconds": 60, "startUtc": "2026-01-01T00:00:00Z",
                             "endUtc": "2026-01-01T00:07:00Z", "series": [series, skipped]},
        })
        # no line crosses a gap: two polylines per series, not one
        self.assertEqual(out["sqs-depth.svg"].count("<polyline"), 2)
        self.assertIn("missing: no datapoints", out["sqs-depth.svg"])
        self.assertEqual(out["queue-timeline.svg"].count("<polyline"), 6)
        self.assertIn("2026-01-01T00:00:30.000Z,missing,missing", out["queue-timeline.csv"])
        self.assertIn("| 5 | 1 |", out["summary.md"])
        self.assertIn("| worker_outcomes | SKIPPED_NO_CONFIG | missing |", out["summary.md"])
        self.assertIn("## Worker outcomes\n\nmissing", out["summary.md"])
        self.assertIn("| sqs_depth_dlq | m | Maximum | SKIPPED_NO_CONFIG | missing | missing | missing |",
                      out["summary.md"])
        self.assertIn("| milestones | missing |", out["summary.md"])

    def test_planned_levels_that_never_ran_read_missing_f07(self):
        run = {"status": "STOPPED", "stopReason": "ERROR_RATE",
               "plannedPhases": [{"phase": "level-1", "concurrency": 1, "planned": 1},
                                 {"phase": "level-5", "concurrency": 5, "planned": 60}],
               "phases": [{"phase": "level-1", "planned": 1, "completed": 1}]}
        out = self.derive({"manifest.yaml": "load: {}\n", "runner/run.json": run,
                           "runner/requests.jsonl": [request(1)]})
        self.assertIn("level-5,5,60,missing,not run,missing,missing,missing,missing", out["load-plan.csv"])
        phases = self.table(out["requests-by-phase.csv"])
        self.assertEqual({v for k, v in phases["level-5"].items() if k != "group"}, {"missing"})

    def test_absent_sections_read_missing_f07(self):
        out = self.derive({"manifest.yaml": "load: {}\ncollect: {logs: [{kind: workflow_stages, logGroupFrom: X}]}\n"})
        for title in ("Requests by phase", "Workflow stage latency", "Metrics"):
            self.assertIn("## %s\n\nmissing" % title, out["summary.md"])

    def test_worker_counts_without_a_batch_line_are_missing_f07(self):
        out = self.derive({"manifest.yaml": "queue: {}\n",
                           "collection.json": {"logs": [{"kind": "worker_outcomes", "status": "OK", "rows": 1}]},
                           "worker_outcomes.jsonl": [{"event": "reject", "code": "X", "store": "s"}]})
        self.assertIn("received,missing", out["worker-outcomes.csv"])

    def test_without_run_json_the_manifest_plan_is_shown_as_missing(self):
        manifest = "load: {kind: levels, levels: [1, 5], requests_per_level: 60}\n"
        out = self.derive({"manifest.yaml": manifest, "collection.json": {"missingInputs": [
            {"item": "runner:directory", "detail": "d"}, {"item": "runner:file:requests.jsonl", "detail": "d"}]}})
        phases = self.table(out["requests-by-phase.csv"])
        self.assertEqual(list(phases), ["preflight", "level-1", "level-5"])
        self.assertEqual({v for row in phases.values() for k, v in row.items() if k != "group"}, {"missing"})
        plan = self.table(out["load-plan.csv"])
        self.assertEqual((plan["level-5"]["planned"], plan["level-5"]["completed"], plan["level-5"]["endedBy"]),
                         ("60", "missing", "missing"))
        modes = self.derive({"manifest.yaml": "load: {kind: retrieval_modes, concurrency: 1}\n",
                             "runner/requests.jsonl": [request(1, "modes", mode="fused")]})
        table = self.table(modes["requests-by-retrieval-mode.csv"])
        for mode in ("graph_only", "vector_only"):
            self.assertEqual({v for k, v in table[mode].items() if k != "group"}, {"missing"})

    def test_short_request_records_and_missing_stage_lines_are_marked(self):
        rows = [request(1, "modes", mode="fused"), request(2, "modes", mode="graph_only")]
        out = self.derive({
            "manifest.yaml": "load: {}\ncollect: {logs: [{kind: workflow_stages, logGroupFrom: X}]}\n",
            "runner/run.json": {"plannedPhases": [{"phase": "modes", "concurrency": 1, "planned": 5}],
                                "phases": [{"phase": "modes", "planned": 5, "completed": 5}]},
            "runner/requests.jsonl": rows,
            "workflow_stages.jsonl": [{"traceId": "t1", "stage": "retrieve", "latencyMs": 20, "outcome": "OK"}]})
        self.assertEqual(self.table(out["requests-by-phase.csv"])["modes"]["dataStatus"], "records missing")
        self.assertIn("graph_only,missing,missing,missing,missing,missing", out["stage-latency.csv"])
        self.assertIn("t2,modes,graph_only,missing", out["stage-lines-missing.csv"])

    def test_interleaved_modes_have_no_per_mode_throughput_f08(self):
        rows = [request(n, "modes", mode=m) for n, m in enumerate(("fused", "graph_only", "vector_only"), 1)]
        out = self.derive({"runner/requests.jsonl": rows, "manifest.yaml": "load: {}\n"})
        self.assertEqual({r["ok200PerSecond"] for r in self.table(out["requests-by-retrieval-mode.csv"]).values()},
                         {"not applicable"})

    def test_stage_latency_excludes_preflight_and_separates_modes_f09(self):
        rows = [request(1, "preflight", mode="fused"), request(2, "modes", mode="fused"),
                request(3, "modes", mode="graph_only")]
        stages = [{"traceId": t, "stage": "retrieve", "latencyMs": ms, "outcome": "OK"}
                  for t, ms in (("t1", 9000), ("t2", 20), ("t3", 30), ("other", 7000))]
        out = self.derive({"runner/requests.jsonl": rows, "workflow_stages.jsonl": stages,
                           "manifest.yaml": "load: {}\ncollect: {logs: [{kind: workflow_stages, logGroupFrom: X}]}\n",
                           "collection.json": {"logs": [{"kind": "workflow_stages", "status": "OK", "rows": 4}]}})
        self.assertEqual(out["stage-latency.csv"].splitlines()[1:],
                         ["fused,retrieve,1,20,20,20", "graph_only,retrieve,1,30,30,30"])


if __name__ == "__main__":
    unittest.main()
