"""Shared helpers for the ScholarChain load generators and the metric collector.

Standard library plus PyYAML. Nothing here holds an endpoint, a resource name or a credential:
they come from environment variables, and none of them is written to an output or a log.
"""

import datetime
import hashlib
import json
import os
import re
import signal
import subprocess
import sys
import threading

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class ConfigError(Exception):
    """A missing or invalid setting. The message names keys, never values."""


class AwsError(Exception):
    """A failed AWS CLI call. Carries the error code only: CLI messages can hold ARNs."""


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc)


def iso(moment):
    return moment.strftime("%Y-%m-%dT%H:%M:%S.") + "%03dZ" % (moment.microsecond // 1000)


def parse_iso(text):
    return datetime.datetime.fromisoformat(text.replace("Z", "+00:00"))


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def load_manifest(path):
    """Loads a manifest. A relative path is resolved against the repository root, because
    run-experiment.sh starts the runner in whatever directory the operator was in."""
    import yaml

    full = path if os.path.isabs(path) else os.path.join(REPO_ROOT, path)
    with open(full) as handle:
        doc = yaml.safe_load(handle)
    if not isinstance(doc, dict):
        raise ConfigError("manifest must be a mapping")
    return doc, full


def require_env(*names):
    missing = [n for n in names if not os.environ.get(n, "").strip()]
    if missing:
        raise ConfigError("required environment variables are not set: " + ", ".join(missing))
    return [os.environ[n].strip() for n in names]


# Values that look like a template, never like a human's name or decision.
PLACEHOLDER = re.compile(
    r"(change[_ -]?me|tbd|todo|pending.*|filled.*|undetermined|unknown|none|null|n/?a|<.*>|\$\{.*\}|x+|\?+|-+|\.+)",
    re.IGNORECASE)
# The result directory run-experiment.sh creates: <name>-<short sha>-<UTC stamp>.
RUN_DIRECTORY = re.compile(r"(.+)-[0-9a-f]{7,40}-\d{8}T\d{6}Z")


def approval_problems(manifest):
    """Why a manifest may not run. Empty only when every item below holds; anything missing,
    mistyped or placeholder-like is a problem.

    filled:      approval.status is exactly APPROVED, approval.approved_by is a non-placeholder
                 name, approval.approved_on is a YYYY-MM-DD date not after tomorrow (UTC).
    not blocked: the top-level key `blocked` is present and is the boolean false, and no entry of
                 `preconditions` starts with BLOCKED.
    """
    problems = []
    if manifest.get("blocked") is not False:
        problems.append("`blocked` must be present and false")
    for entry in manifest.get("preconditions") or []:
        texts = list(entry) if isinstance(entry, dict) else [entry]
        if any(str(t).strip().upper().startswith("BLOCKED") for t in texts):
            problems.append("a precondition is marked BLOCKED")
            break
    approval = manifest.get("approval")
    if not isinstance(approval, dict):
        return problems + ["the approval block is missing"]
    if approval.get("status") != "APPROVED":
        problems.append("approval.status is not APPROVED")
    who = approval.get("approved_by")
    if not isinstance(who, str) or not who.strip() or PLACEHOLDER.fullmatch(who.strip()):
        problems.append("approval.approved_by is missing or a placeholder")
    when = approval.get("approved_on")
    if isinstance(when, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}", when):
        try:
            when = datetime.date.fromisoformat(when)
        except ValueError:
            when = None
    if type(when) is not datetime.date or when > utc_now().date() + datetime.timedelta(days=1):
        problems.append("approval.approved_on is not a past YYYY-MM-DD date")
    return problems


def require_runnable(manifest, full_path, env_flag=None):
    """Refuses a load tool unless the manifest it was given is approved, is not blocked, names an
    approved environment and is the manifest this run was launched for. Applies to direct
    invocation and to run-experiment.sh alike.

    env_flag is the tool's --env; without it SC_ENV (set by run-experiment.sh) is used. Either
    one must equal the manifest environment. It never widens SCHOLARCHAIN_APPROVED_ENVS."""
    problems = approval_problems(manifest)
    if problems:
        raise ConfigError("the manifest may not run: " + "; ".join(problems))
    approved = [e.strip() for e in os.environ.get("SCHOLARCHAIN_APPROVED_ENVS", "").split(",") if e.strip()]
    if manifest.get("environment") not in approved:
        raise ConfigError("the manifest environment is not listed in SCHOLARCHAIN_APPROVED_ENVS")
    requested = env_flag if env_flag is not None else os.environ.get("SC_ENV", "").strip() or None
    if requested is not None and requested != manifest.get("environment"):
        raise ConfigError("--env (or SC_ENV) differs from the manifest environment; refused")
    name = manifest.get("name")
    if os.path.splitext(os.path.basename(full_path))[0] != name:
        raise ConfigError("the manifest file name differs from its `name`; refused")
    args = manifest.get("args")
    if (not isinstance(args, list) or len(args) != 2 or args[0] != "--manifest"
            or os.path.realpath(os.path.join(REPO_ROOT, str(args[1]))) != os.path.realpath(full_path)):
        raise ConfigError("the manifest `args` do not name the manifest itself; refused")
    # run-experiment.sh passes no manifest path, only SC_EXPERIMENT_OUT named after the manifest
    # it was launched with
    launched = RUN_DIRECTORY.fullmatch(os.path.basename(os.environ.get("SC_EXPERIMENT_OUT", "").rstrip("/")))
    if launched and launched.group(1) != name:
        raise ConfigError("the run was launched for another manifest than the one named in `args`; refused")


def number(section, key, low, high):
    """section[key] as a number in [low, high]. Booleans, strings and absent keys are refused."""
    value = section.get(key)
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not low <= value <= high:
        raise ConfigError("%s must be a number in %s..%s" % (key, low, high))
    return value


def check_region(manifest):
    """The Region run-experiment.sh resolved must be the one the manifest was approved for."""
    (region,) = require_env("SC_REGION")
    if region != manifest.get("region"):
        raise ConfigError("SC_REGION differs from the manifest region; refused")
    return region


class JsonlWriter:
    """Appends one JSON object per line and flushes, so a killed run keeps what it measured."""

    def __init__(self, path):
        self._handle = open(path, "x")
        self._lock = threading.Lock()

    def write(self, record):
        line = json.dumps(record, sort_keys=True)
        with self._lock:
            self._handle.write(line + "\n")
            self._handle.flush()

    def close(self):
        self._handle.close()


def write_json(path, doc):
    with open(path, "x") as handle:
        json.dump(doc, handle, indent=2, sort_keys=True)
        handle.write("\n")


def stop_event_on_signals():
    """An event set by TERM or INT (run-experiment.sh sends TERM at max_duration_seconds)."""
    event = threading.Event()
    for number in (signal.SIGTERM, signal.SIGINT):
        signal.signal(number, lambda *_: event.set())
    return event


# The profile of every aws_json call, set by use_profile(). None: the CLI's own credential chain.
_profile = None


def use_profile(flag):
    """Selects the profile of every later aws_json call: the --profile value, else AWS_PROFILE.

    It is passed to the CLI as --profile: AWS_PROFILE alone loses to static credential
    variables (AWS_ACCESS_KEY_ID)."""
    global _profile
    if flag is not None and not flag.strip():
        raise ConfigError("--profile needs a value")
    _profile = (flag if flag is not None else os.environ.get("AWS_PROFILE", "")).strip() or None
    return _profile


def aws_json(args, region, timeout=60, max_attempts=None):
    """Runs one AWS CLI command and returns its parsed JSON output ({} when empty).

    SC_AWS_BIN overrides the executable (the offline tests point it at a stub)."""
    command = [
        os.environ.get("SC_AWS_BIN", "aws"),
        "--region", region,
        "--output", "json",
        "--cli-connect-timeout", "10",
        "--cli-read-timeout", "30",
    ] + (["--profile", _profile] if _profile else []) + list(args)
    env = dict(os.environ)
    if max_attempts is not None:
        env["AWS_MAX_ATTEMPTS"] = str(max_attempts)
    name = " ".join(args[:2])
    try:
        done = subprocess.run(command, capture_output=True, text=True, timeout=timeout, env=env)
    except subprocess.TimeoutExpired:
        raise AwsError("%s: timed out" % name) from None
    except OSError as error:
        raise AwsError("%s: cannot run the AWS CLI (%s)" % (name, type(error).__name__)) from None
    if done.returncode != 0:
        code = re.search(r"An error occurred \(([A-Za-z0-9._-]+)\)", done.stderr or "")
        raise AwsError("%s: exit %d %s" % (name, done.returncode, code.group(1) if code else ""))
    return json.loads(done.stdout) if done.stdout.strip() else {}


def say(message):
    print("INFO: " + message, flush=True)


def fail(message, code=1):
    print("ERROR: " + message, file=sys.stderr, flush=True)
    return code
