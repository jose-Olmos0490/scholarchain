"""Offline fakes: a localhost /v1/query server, a stub AWS CLI, a stub git, fixture manifests and a
throwaway copy of the tools. Nothing here reaches AWS, and no committed manifest is approved:
fixture approvals exist only in temporary directories."""

import hashlib
import http.server
import json
import os
import shutil
import subprocess
import sys
import threading
import time

import yaml

LOAD_TESTS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(LOAD_TESTS)
TOKEN = "CHANGE_ME-test-token"
VALID_RUN_ID = "run:" + "a" * 64
OLD_PUT_BATCH = "00001#vector-ingest-collab-b00001#001#1-SUCCEEDED"


class FakeApi:
    def __init__(self, status=200, abstained=False, redirect=False, delay=0.0, trickle=False):
        self.bodies = []
        self.auth_headers = []
        self.arrivals = []
        fake = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                fake.arrivals.append(time.monotonic())
                fake.bodies.append(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
                fake.auth_headers.append(self.headers.get("Authorization"))
                trace = self.headers.get("X-Trace-Id")
                code = 302 if redirect else status
                if code == 200:
                    doc = {"queryId": "query:x", "traceId": trace, "verification": "VERIFIED",
                           "evidenceIds": ["evidence:1"], "removedClaimCount": 0,
                           "answer": {"abstained": abstained, "claims": [{}]}}
                else:
                    doc = {"error": {"code": "E%d" % code}, "traceId": trace}
                payload = json.dumps(doc).encode()
                time.sleep(delay)
                self.send_response(code)
                if redirect:
                    self.send_header("Location", "http://127.0.0.1:1/elsewhere")
                self.send_header("X-Trace-Id", trace)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                try:
                    if trickle:  # slower than any total deadline, faster than a socket timeout
                        for i in range(12):
                            self.wfile.write(payload[i:i + 1])
                            self.wfile.flush()
                            time.sleep(0.25)
                        payload = payload[12:]
                    self.wfile.write(payload)
                except OSError:
                    pass

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.handle_error = lambda *args: None
        self.url = "http://127.0.0.1:%d" % self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


STUB = r'''#!%(python)s
import json, os, sys
state_path = os.environ["STUB_STATE"]
state = json.load(open(state_path))
argv = sys.argv[1:]
# skip the global options (all take a value)
i = 0
while argv[i].startswith("--"):
    i += 2
service, operation, rest = argv[i], argv[i + 1], argv[i + 2:]
def opt(name):
    return rest[rest.index(name) + 1]
def save():
    json.dump(state, open(state_path, "w"))
def refuse():
    save()
    sys.stderr.write("An error occurred (AccessDenied) arn:aws:x:123456789012 secret-cluster secret-queue")
    sys.exit(254)
state["calls"].append(service + " " + operation)
count = state["calls"].count(service + " " + operation)
if operation in state["fail_ops"] and count >= state["fail_ops"][operation]:
    refuse()
out = {}
hidden = state["lag"] > 0  # the approximate counts still read 0 shortly after a send
if operation == "get-queue-attributes":
    if opt("--queue-url").endswith("dlq"):
        visible = state["dlq"]
    else:
        visible = 0 if hidden else state["visible"]
    out = {"Attributes": {"ApproximateNumberOfMessages": str(visible),
                          "ApproximateNumberOfMessagesNotVisible": "0"}}
elif operation == "send-message-batch":
    entries = json.loads(opt("--entries"))
    state["bodies"] += [e["MessageBody"] for e in entries]
    state["visible"] += len(entries)
    ok = entries
    if count == state["fail_send"]:
        if state["fail_send_mode"] == "error":
            refuse()
        ok = entries[:3]
    out = {"Successful": [{"Id": e["Id"], "MessageId": "mid-" + e["Id"]} for e in ok]}
elif service == "ecs" and operation == "describe-services":
    # while messages wait the worker scales out, then drains them, then returns to zero
    if hidden and state["bodies"]:
        state["lag"] -= 1
    elif state["scales"] and state["visible"]:
        if state["tasks"] < 2:
            state["tasks"] += 1
        else:
            valid = [b for b in state["bodies"] if "run:" + "a" * 64 in b]
            state["dlq"] += len(state["bodies"]) - len(valid)
            if valid and state["writes"]:
                ending = "SUCCEEDED" if state["writes"] is True else "FAILED"
                state["ledger"].append("00001#vector-ingest-collab-b00001#002#1-" + ending)
            state["bodies"] = []
            state["visible"] = 0
    elif not state["visible"] and state["tasks"]:
        state["tasks"] -= 1
    names = [n for n in rest[rest.index("--services") + 1:] if n not in state["absent_services"]]
    out = {"services": [{"serviceName": n, "taskDefinition": "arn:aws:ecs:x:123456789012:task-definition/" + n + ":5",
                         "desiredCount": state["tasks"], "runningCount": state["tasks"], "pendingCount": 0}
                        for n in names]}
elif service == "dynamodb" and operation == "query":
    out = {"Items": [{"sk": {"S": k}} for k in state["ledger"]]}
elif operation == "describe-task-definition":
    out = {"images": [state["image"]], "revision": 5}
elif operation == "get-metric-data":
    queries = json.loads(opt("--metric-data-queries"))
    start = opt("--start-time")
    state["queries"] += queries
    out = {"MetricDataResults": [{"Id": q["Id"], "StatusCode": "Complete",
                                  "Timestamps": [] if q["Id"] in state["no_data"] else [start],
                                  "Values": [] if q["Id"] in state["no_data"] else [1.5]}
                                 for q in queries]}
elif operation == "filter-log-events":
    if "stage" in opt("--filter-pattern"):
        messages = ["INFO stage traceId=%%s stage=retrieve latencyMs=7 outcome=OK" %% t for t in ("t1", "other-client")]
    else:
        messages = ["INFO batch received=1 acknowledged=0 retried=0 deadLettered=1 secret-ish text",
                    "WARN reject store=embedding-queue key=mid-7 code=UNDECODABLE_MESSAGE"]
    if state["log_messages"] is not None:
        messages = state["log_messages"]
    out = {"events": [{"timestamp": n, "message": m} for n, m in enumerate(messages, 1)]}
else:
    refuse()
save()
print(json.dumps(out))
'''


def make_stub(directory, tasks=0, scales=True, visible=0, **options):
    """Returns the environment that points the tools at a stub AWS CLI with fresh state."""
    stub = os.path.join(directory, "stub-aws")
    with open(stub, "w") as handle:
        # the template holds literal % signs, so only the interpreter marker is substituted
        handle.write(STUB.replace("%(python)s", sys.executable).replace("%%", "%"))
    os.chmod(stub, 0o755)
    state = os.path.join(directory, "stub-state.json")
    doc = {"visible": visible, "dlq": 0, "tasks": tasks, "scales": scales, "bodies": [], "calls": [],
           "queries": [], "ledger": [OLD_PUT_BATCH], "writes": True, "fail_send": 0, "fail_send_mode": "error",
           "fail_ops": {}, "lag": 0, "absent_services": [], "no_data": [], "log_messages": None,
           "image": "123456789012.dkr.ecr.x.amazonaws.com/app:abc1234"}
    unknown = set(options) - set(doc)
    assert not unknown, unknown
    doc.update(options)
    with open(state, "w") as handle:
        json.dump(doc, handle)
    return {"SC_AWS_BIN": stub, "STUB_STATE": state}


def fixture_manifest(directory, source, change=None, **sections):
    """A copy of a committed manifest, approved FOR THE TEST ONLY, written into a temporary
    directory. `sections` update nested mappings; `change` may edit the document freely."""
    with open(os.path.join(REPO, "experiments", "manifests", source)) as handle:
        doc = yaml.safe_load(handle)
    path = os.path.join(directory, doc["name"] + ".yaml")
    doc["approval"] = {"status": "APPROVED", "approved_by": "offline-test-fixture", "approved_on": "2026-01-01"}
    doc["blocked"] = False
    if "preconditions" in doc:  # C and F are committed as blocked; the tool tests need them runnable
        doc["preconditions"] = [p for p in doc["preconditions"] if not str(p).startswith("BLOCKED")]
    doc["args"] = ["--manifest", path]
    for section, values in sections.items():
        doc[section].update(values)
    if change:
        change(doc)
    with open(path, "w") as handle:
        yaml.safe_dump(doc, handle)
    return path


GIT_STUB = r'''#!%(python)s
import os, sys
sha, path = sys.argv[sys.argv.index("show") + 1].split(":", 1)
source = os.path.join(os.environ["STUB_GIT_TREE"], path)
if not os.path.isfile(source):
    sys.exit(128)
sys.stdout.buffer.write(open(source, "rb").read())
'''


def make_repo(directory):
    """A throwaway repository: the tools and manifests, so that their output stays in `directory`.
    Returns (root, environment for the stub git, directory that plays the run's commit)."""
    root = os.path.join(directory, "repo")
    for part in ("load-tests", os.path.join("experiments", "bin"), os.path.join("experiments", "manifests")):
        os.makedirs(os.path.join(root, part))
        for name in os.listdir(os.path.join(REPO, part)):
            if name.endswith((".py", ".yaml")):
                shutil.copy(os.path.join(REPO, part, name), os.path.join(root, part, name))
    committed = os.path.join(directory, "committed")
    shutil.copytree(os.path.join(root, "experiments", "manifests"), os.path.join(committed, "experiments", "manifests"))
    git = os.path.join(directory, "stub-git")
    with open(git, "w") as handle:
        handle.write(GIT_STUB.replace("%(python)s", sys.executable))
    os.chmod(git, 0o755)
    return root, {"SC_GIT_BIN": git, "STUB_GIT_TREE": committed}, committed


def make_raw(directory, files):
    """A sealed raw run directory from {relative name: JSON document, list of lines or text}."""
    for name, content in files.items():
        path = os.path.join(directory, name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as handle:
            if name.endswith(".jsonl"):
                handle.writelines(json.dumps(r) + "\n" for r in content)
            elif name.endswith(".json"):
                json.dump(content, handle)
            else:
                handle.write(content)
    with open(os.path.join(directory, "SHA256SUMS"), "w") as handle:
        for name in sorted(files):
            with open(os.path.join(directory, name), "rb") as data:
                handle.write("%s  %s\n" % (hashlib.sha256(data.read()).hexdigest(), name))


def clean_env(extra):
    """The caller's AWS and SC_ settings are removed, so no test can reach a real account."""
    env = {k: v for k, v in os.environ.items() if not k.startswith(("AWS_", "SC_", "SCHOLARCHAIN_"))}
    env.update(AWS_CONFIG_FILE=os.devnull, AWS_SHARED_CREDENTIALS_FILE=os.devnull,
               PYTHONDONTWRITEBYTECODE="1", SC_AWS_BIN="/nonexistent-aws")
    env.update(extra)
    return env


def run_tool(path, args, env):
    return subprocess.run([sys.executable, os.path.join(REPO, path)] + args, env=clean_env(env),
                          capture_output=True, text=True, timeout=120)


def start_tool(path, args, env):
    return subprocess.Popen([sys.executable, os.path.join(REPO, path)] + args, env=clean_env(env),
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)


def lines(path):
    with open(path) as handle:
        return [json.loads(line) for line in handle]


def text_of(directory):
    out = []
    for base, _, files in os.walk(directory):
        for name in files:
            with open(os.path.join(base, name), errors="replace") as handle:
                out.append(handle.read())
    return "\n".join(out)
