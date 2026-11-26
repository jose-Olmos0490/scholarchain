#!/usr/bin/env python3
"""Part A item 4: extracts agentic-factory events from docs/agent-runs/phase-00..15 (read only).

    experiments/evaluation/bin/factory_extract.py --out <new directory>

Review findings are parsed from the review reports. Everything else is a hand-coded record: each
row names a source file and an anchor string. The line number is found by searching for the anchor,
and the script fails if a source or anchor is missing, so no row can cite text that is not there.
Classifications (first pass, ownership, intervention category) are the extractor's reading of that
text; rows with needs_confirmation=yes require a human ruling.
"""

import argparse
import csv
import hashlib
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
BASE = "docs/agent-runs"

used = {}


def read(rel):
    path = os.path.join(REPO, BASE, rel)
    with open(path, "rb") as handle:
        data = handle.read()
    used[BASE + "/" + rel] = hashlib.sha256(data).hexdigest()
    return data.decode("utf-8", "replace").splitlines()


def locate(rel, anchor):
    if anchor is None:
        read(rel)
        return ""
    for number, line in enumerate(read(rel), 1):
        if anchor in line:
            return number
    sys.exit("anchor not found: %s: %r" % (rel, anchor))


def emit(out, name, header, rows, anchor_index):
    with open(os.path.join(out, name), "x", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(list(header) + ["source_file", "source_line", "anchor"])
        for row in rows:
            fields, rel, anchor = row[:anchor_index], row[anchor_index], row[anchor_index + 1]
            writer.writerow(list(fields) + [BASE + "/" + rel, locate(rel, anchor), anchor or ""])


# phase, agent, work order, first_pass (yes|no|unknown), reason, needs_confirmation, file, anchor
# Unit: one primary (non-repair) work order of a non-reviewer agent. first_pass=yes means the first
# attempt ended COMPLETE and the evidence shows no repair of that artifact before the phase gate.
WORK_ORDERS = [
    ("01", "factory-engineer", "agent control plane", "unknown", "no human-gate.md in phase-01", "yes",
     "phase-01/agent-report.md", "The control plane is built"),
    ("02", "build-engineer", "sbt build", "no", "v1 BLOCKED-ON, repairs 02 and 03 after review", "no",
     "phase-02/repair-01.md", "returned BLOCKED-ON factory-engineer"),
    ("03", "dataset-engineer", "A dataset manifest", "no", "run 1 BLOCKED-ON, review repairs", "no",
     "phase-03/agent-report.md", "run 1 (STATUS: BLOCKED-ON)"),
    ("03", "domain-engineer", "B domain contracts", "no", "repair 1 for review findings", "no",
     "phase-03/agent-report.md", "repair 1 for review findings 1 and 9-15"),
    ("04", "domain-engineer", "A ingestion contracts", "no", "later repairs 02, 04, 06 changed its contracts", "yes",
     "phase-04/test-report.md", "repair-04 (domain-engineer, defect 6)"),
    ("04", "hadoop-engineer", "B MapReduce jobs", "no", "attempt 1 BLOCKED-ON, then defect repairs", "no",
     "phase-04/agent-report.md", "attempt 1 (BLOCKED-ON)"),
    ("04", "test-engineer", "C adversarial tests", "no", "attempt 1 BLOCKED-ON", "no",
     "phase-04/test-report.md", "attempt 1 (BLOCKED-ON)"),
    ("05", "entity-resolution-engineer", "A and B entity resolution", "no", "repair-01 after review and tests", "no",
     "phase-05/repair-01.md", "The first code review returned REQUEST_CHANGES"),
    ("05", "test-engineer", "adversarial ER tests", "no", "report status BLOCKED-ON", "no",
     "phase-05/test-report.md", "STATUS: BLOCKED-ON"),
    ("06", "spark-engineer", "A and B graph analytics", "no", "repair rounds 01 and 02", "no",
     "phase-06/human-gate.md", "repair-02: LPA ID-order dependence"),
    ("06", "test-engineer", "adversarial Spark tests", "yes", "COMPLETE, no production defect", "no",
     "phase-06/test-report.md", "no production defect was found"),
    ("07", "storage-engineer", "storage ports and adapters", "no", "attempt 1 BLOCKED-ON; accepted with deferred findings", "no",
     "phase-07/agent-report-attempt1.md", "STATUS: BLOCKED-ON"),
    ("08", "storage-engineer", "WO1 vector write path", "yes", "COMPLETE, executed inline by the orchestrator, not reviewed", "yes",
     "phase-08/agent-report-storage.md", "executed inline by the orchestrator"),
    ("08", "rag-engineer", "WO2 rendering and ingestion", "no", "BLOCKED-ON build-engineer", "no",
     "phase-08/repair-01.md", "Blocked agent: rag-engineer"),
    ("09", "rag-engineer", "A deterministic workflow", "no", "first run BLOCKED-ON", "no",
     "phase-09/agent-report-A-blocked.md", "STATUS: BLOCKED-ON"),
    ("09", "rag-engineer", "B synthesis and verification", "no", "review REQUEST_CHANGES, repairs 02-04", "no",
     "phase-09/review-report-01.md", "VERDICT: REQUEST_CHANGES"),
    ("10", "service-engineer", "REST services", "yes", "ACCEPTED; code-reviewer and test-engineer not run", "yes",
     "phase-10/human-gate.md", "were not run for Phase 10"),
    ("10b", "rag-engineer", "WO1 live LLM adapter", "yes", "COMPLETE; not reviewed", "yes",
     "phase-10b/agent-report.md", "Phase 10b, work order 1 of 4"),
    ("10b", "storage-engineer", "WO2 job store", "yes", "COMPLETE with a routing note; not reviewed", "yes",
     "phase-10b/agent-report.md", "Phase 10b, work order 2 of 4"),
    ("10b", "service-engineer", "WO3 composition roots", "no", "BLOCKED-ON storage-engineer", "no",
     "phase-10b/repair-01.md", "service-engineer returned BLOCKED-ON"),
    ("10b", "build-engineer", "WO4 images", "yes", "COMPLETE; not reviewed", "yes",
     "phase-10b/agent-report.md", "Phase 10b, work order 4 of 4"),
    ("11", "test-engineer", "repository adversarial tests", "no", "attempt 1 BLOCKED-ON", "no",
     "phase-11/test-report.md", "STATUS: BLOCKED-ON build-engineer"),
    ("12", "infra-engineer", "Terraform", "no", "attempt 1 BLOCKED-ON, repair-01 after review", "no",
     "phase-12/agent-report.md", "STATUS: BLOCKED-ON"),
    ("13", "infra-engineer", "scripts", "no", "review REQUEST_CHANGES, repairs 01 and 02", "no",
     "phase-13/repair-02.md", "round 1 REQUEST_CHANGES"),
    ("14", "deploy-engineer", "first deployment", "no", "attempts FAILED", "no",
     "phase-14/agent-report-attempt1.md", "STATUS: FAILED"),
    ("14b", "storage-engineer", "loader entry point", "no", "review REQUEST_CHANGES, repair-01", "no",
     "phase-14b/human-gate.md", "REQUEST_CHANGES (2 major, 5 minor)"),
    ("15", "experiment-engineer", "experiment tooling", "no", "BLOCKED-ON, then review repairs", "no",
     "phase-15/agent-report.md", "STATUS: BLOCKED-ON"),
    ("15", "infra-engineer", "gaps for experiments C, F, D", "no", "report status BLOCKED-ON", "yes",
     "phase-15/agent-report-03.md", "STATUS: BLOCKED-ON"),
    ("15", "storage-engineer", "index comparison fix", "yes", "reviewed sound with one nit", "yes",
     "phase-15/repair-01.md", "code-reviewer (read-only) found the storage change sound"),
    ("15", "service-engineer", "logging fix", "yes", "review APPROVE", "no",
     "phase-15/review-report-04.md", "VERDICT: APPROVE"),
    ("15", "deploy-engineer", "deploy 6ce56c9", "no", "report status FAILED (smoke)", "yes",
     "phase-15/deploy-report.md", "STATUS: FAILED"),
]

# phase, agent, blocked on, archived_verbatim, needs_confirmation
BLOCKED_ON = [
    ("02", "build-engineer", "factory-engineer", "no", "no", "phase-02/repair-01.md", "returned BLOCKED-ON factory-engineer"),
    ("03", "dataset-engineer", "factory-engineer", "yes", "no", "phase-03/agent-report.md", "STATUS: BLOCKED-ON"),
    ("04", "hadoop-engineer", "build-engineer, domain-engineer", "yes", "no", "phase-04/agent-report.md", "STATUS: BLOCKED-ON"),
    ("04", "test-engineer", "build-engineer", "yes", "no", "phase-04/test-report.md", "STATUS: BLOCKED-ON"),
    ("05", "test-engineer", "build-engineer, entity-resolution-engineer", "yes", "no", "phase-05/test-report.md", "STATUS: BLOCKED-ON"),
    ("07", "storage-engineer", "build-engineer, domain-engineer", "yes", "no", "phase-07/agent-report-attempt1.md", "STATUS: BLOCKED-ON"),
    ("08", "rag-engineer", "build-engineer", "no", "no", "phase-08/repair-01.md", "Blocked on: build-engineer"),
    ("09", "rag-engineer", "storage-engineer, domain-engineer", "yes", "no", "phase-09/agent-report-A-blocked.md", "STATUS: BLOCKED-ON"),
    ("10b", "service-engineer", "storage-engineer", "yes", "no", "phase-10b/agent-report.md", "STATUS: BLOCKED-ON"),
    ("11", "test-engineer", "build-engineer", "yes", "no", "phase-11/test-report.md", "STATUS: BLOCKED-ON build-engineer"),
    ("12", "infra-engineer", "build-engineer", "yes", "no", "phase-12/agent-report.md", "STATUS: BLOCKED-ON"),
    ("15", "experiment-engineer", "infra-engineer and others", "yes", "no", "phase-15/agent-report.md", "STATUS: BLOCKED-ON"),
    ("15", "infra-engineer", "see report", "yes", "no", "phase-15/agent-report-03.md", "OWNER: infra-engineer"),
    ("15", "experiment-engineer", "human architect", "yes", "no", "phase-15/agent-report-03.md", "OWNER: experiment-engineer"),
]
# Partial blocks inside reports whose status was not BLOCKED-ON; listed, not counted.
BLOCKED_ON_PARTIAL = [
    ("10b", "storage-engineer", "COMPLETE with routing note", "phase-10b/agent-report.md", "STATUS: COMPLETE (with one routing note"),
    ("13", "infra-engineer", "COMPLETE with three BLOCKED-ON items", "phase-13/agent-report.md", "Three items are BLOCKED-ON"),
]

# phase, actor, mechanism (guard|stop-hook|permission), count, basis (exact|minimum), note, needs_confirmation
BLOCKS = [
    ("01", "factory-engineer", "guard", 1, "exact", "first smoke-test command", "no", "phase-01/agent-report.md", "the guard blocked my first s"),
    ("02", "orchestrator", "guard", 1, "exact", "write of human-owned repair record", "no", "phase-02/repair-01.md", "was blocked by"),
    ("02", "build-engineer", "guard", 1, "exact", "git check-ignore", "no", "phase-02/repair-03.md", "blocked the agent's `git check-ignore`"),
    ("03", "dataset-engineer", "guard", 3, "exact", "run 1: download.sh, chmod, bash -n loop", "no", "phase-03/agent-report.md", "BLOCKED by the guard: datasets/scripts/download.sh"),
    ("03", "dataset-engineer", "guard", 1, "exact", "scratch copy of download.sh", "no", "phase-03/agent-report.md", "BLOCKED by the guard (not worked around): running a scratch copy"),
    ("03", "domain-engineer", "permission", 1, "exact", "dev.conf write denied; mechanism not named", "yes", "phase-03/agent-report.md", "write denied"),
    ("03", "dataset-engineer", "guard", 1, "exact", "python heredoc naming a script", "no", "phase-03/agent-report.md", "one python3 heredoc that edited the manifest"),
    ("03", "dataset-engineer", "guard", 1, "exact", "rm -rf on a scratchpad variable", "no", "phase-03/agent-report.md", "\"rm -rf $S/samples\""),
    ("04", "test-engineer", "guard", 1, "exact", "command listing the test marker", "no", "phase-04/test-report.md", "The guard hook blocked it"),
    ("07", "storage-engineer", "guard", 2, "minimum", "reported as 'several'", "yes", "phase-07/agent-report.md", "blocked several shell commands"),
    ("08", "build-engineer", "guard", 1, "minimum", "grep of the evidence directory", "yes", "phase-08/agent-report-build-repair01.md", "were blocked by the guard hook"),
    ("09", "orchestrator", "stop-hook", 1, "minimum", "refused completion on stale tests", "yes", "phase-09/human-gate.md", "verify-tests-ran.sh refused"),
    ("09", "orchestrator", "guard", 1, "exact", "write to an unowned path", "no", "phase-09/human-gate.md", "blocked an orchestrator write to an unowned path"),
    ("10", "service-engineer", "guard", 1, "exact", "heredoc containing a secret-like word", "no", "phase-10/agent-report.md", "blocked one heredoc Bash write"),
    ("10b", "orchestrator", "guard", 2, "minimum", "unapproved-path writes and a heredoc", "yes", "phase-10b/human-gate.md", "blocked orchestrator writes to unapproved paths"),
    ("11", "orchestrator", "stop-hook", 1, "minimum", "refused completion on stale tests", "yes", "phase-11/human-gate.md", "Stop hook (verify-tests-ran.sh) refused"),
    ("11", "test-engineer", "guard", 1, "exact", "command referencing human-gate.md", "no", "phase-11/human-gate.md", "blocked a test-engineer shell command"),
    ("12", "infra-engineer", "guard", 1, "exact", "check-ignore loop", "no", "phase-12/agent-report.md", "The guard hook blocked it because a path contained"),
    ("12", "infra-engineer", "guard", 3, "exact", "environment dump and two heredoc/sed commands", "no", "phase-12/agent-report.md", "The guard blocked three of my commands"),
    ("12", "infra-engineer", "guard", 2, "exact", "repair-01: heredoc and recursive delete", "no", "phase-12/repair-01.md", "One guard block"),
    ("12", "orchestrator or agent", "guard", 1, "minimum", "write of human-owned repair-01.md", "yes", "phase-12/human-gate.md", "blocked agent writes to human-owned"),
    ("13", "infra-engineer", "guard", 1, "minimum", "script execution incl. --help; command count not given", "yes", "phase-13/agent-report.md", "The guard blocked executing the scripts below"),
    ("14", "deploy-engineer", "guard", 2, "exact", "attempt 2: inline env, rollback.sh view", "no", "phase-14/agent-report-attempt2.md", "The guard BLOCKED it because inline setting"),
    ("14", "deploy-engineer", "guard", 2, "exact", "attempt 3: inline env, compound aws query", "no", "phase-14/agent-report-attempt3.md", "A compound bash aws query was blocked"),
    ("15", "experiment-engineer", "guard", 5, "exact", "preparation report", "no", "phase-15/agent-report.md", "blocked five of my commands"),
    ("15", "experiment-engineer", "guard", 2, "exact", "repair report items 1 and 3", "no", "phase-15/agent-report-02.md", "The guard hook rejected a Bash heredoc edit"),
    ("15", "experiment-engineer", "permission", 1, "exact", "repair report item 2", "no", "phase-15/agent-report-02.md", "A permission denial rejected"),
    ("15", "infra-engineer", "guard", 6, "exact", "six listed commands", "no", "phase-15/agent-report-03.md", "The guard rejected these, and none of them ran"),
    ("15", "experiment-engineer", "guard", 4, "exact", "four listed commands", "no", "phase-15/agent-report-03.md", "blocked four of my shell commands"),
    ("15", "deploy-engineer", "guard", 6, "exact", "five listed plus one batched call", "yes", "phase-15/deploy-report.md", "Guard blocks, none worked around"),
    ("15", "deploy-engineer", "permission", 1, "exact", "rollback denied by the human", "no", "phase-15/deploy-report.md", "The rollback required by policy was attempted and DENIED"),
    ("15", "deploy-engineer and orchestrator", "guard", 4, "exact", "four numbered blocks", "no", "phase-15/deploy-report-3c3e1a1.md", "## Guard blocks (none worked around)"),
    ("15", "orchestrator", "guard", 2, "exact", "suffixed report names unowned", "no", "phase-15/human-gate.md", "Guard blocked agent-report-02.md and review-report-02.md"),
    ("15", "incident-responder", "guard", 1, "minimum", "compound read-only commands", "yes", "phase-15/human-gate.md", "when the guard blocked compound commands"),
]

# phase, category, actor, description, needs_confirmation
# categories: blocked-write (non-owned or human-owned write stopped), refused-by-agent (agent
# declined out-of-territory work), landed (a rule breach that took effect), unresolved
OWNERSHIP = [
    ("02", "blocked-write", "orchestrator", "write of human-owned repair record", "no", "phase-02/repair-01.md", "was blocked by"),
    ("02", "refused-by-agent", "build-engineer", "work order assigned paths owned by others", "no", "phase-02/repair-01.md", "Empty .gitkeep source folders"),
    ("04", "landed", "hadoop-engineer and domain-engineer", "three edits via Bash/Python bypassed the guard; inside own territory", "yes", "phase-04/human-gate.md", "Process deviation (F8)"),
    ("05", "unresolved", "unrecorded", "edits under tests/** during an ER repair; authorship to be confirmed", "yes", "phase-05/repair-01.md", "Edits under tests/** in this repair"),
    ("06", "unresolved", "unrecorded", "unattributed file in human-owned docs/agent-runs", "yes", "phase-06/review-report.md", "part-a-run-manifest.json"),
    ("08", "refused-by-agent", "rag-engineer", "build.sbt change routed to owner", "no", "phase-08/repair-01.md", "Blocked on: build-engineer"),
    ("08", "landed", "storage-engineer", "git rm --cached run and reverted (git rule)", "yes", "phase-08/human-gate.md", "git rm --cached"),
    ("08", "landed", "orchestrator", "specialist work executed inline", "yes", "phase-08/human-gate.md", "executed inline in the orchestrator"),
    ("09", "blocked-write", "orchestrator", "write to an unowned path", "no", "phase-09/human-gate.md", "blocked an orchestrator write to an unowned path"),
    ("11", "blocked-write", "test-engineer", "command referencing a human-owned record; may be a false positive", "yes", "phase-11/human-gate.md", "blocked a test-engineer shell command"),
    ("12", "refused-by-agent", "infra-engineer", ".gitignore change routed to build-engineer", "no", "phase-12/agent-report.md", "BLOCKED-ON OWNER: build-engineer"),
    ("12", "blocked-write", "orchestrator or agent", "write of human-owned repair-01.md", "yes", "phase-12/human-gate.md", "blocked agent writes to human-owned"),
    ("15", "blocked-write", "orchestrator", "two report names without an owner", "no", "phase-15/human-gate.md", "Guard blocked agent-report-02.md"),
    ("15", "landed", "storage-engineer and experiment-engineer", "searches printed Terraform state content (secret-handling rule)", "no", "phase-15/repair-01.md", "Terraform state contents printed"),
]

# phase, category (unplanned|gate-action|process), description, needs_confirmation
INTERVENTIONS = [
    ("00", "unplanned", "human edited factory-engineer after review (commit c84390f)", "yes", "phase-00/human-gate.md", "Manual changes: none"),
    ("02", "process", "work order amended twice", "no", "phase-02/human-gate.md", "Work order amended twice"),
    ("03", "unplanned", "removed a generated __pycache__ folder", "no", "phase-03/human-gate.md", "removed the generated"),
    ("04", "process", "human escalation ended the adversarial loop after repair 08", "no", "phase-04/human-gate.md", "Human escalation ended"),
    ("04", "unplanned", "phase code committed late and commit hash corrected", "no", "phase-04/human-gate.md", "The original commit.txt recorded"),
    ("05", "unplanned", "merged Excel-saved labels back into the gold CSV by script", "no", "phase-05/human-gate.md", "Labels were first saved by Excel"),
    ("06", "unplanned", "deleted invalid and superseded run directories; placed run manifest", "no", "phase-06/human-gate.md", "Human deleted invalid run"),
    ("07", "unplanned", "restarted session from repo root; installed uv", "no", "phase-07/human-gate.md", "Installed uv/uvx"),
    ("07", "process", "committed and pushed before review findings were resolved", "no", "phase-07/human-gate.md", "was committed and pushed by the human"),
    ("08", "unplanned", "evidence files filled in afterwards from session logs", "no", "phase-08/human-gate.md", "Files were filled in afterward"),
    ("09", "unplanned", "changed shell variables for subagent timeouts", "no", "phase-09/human-gate.md", "CLAUDE_CODE_DISABLE_BACKGROUND_TASKS"),
    ("09", "unplanned", "renamed report files after a guard block", "no", "phase-09/human-gate.md", "the human renamed files"),
    ("10b", "unplanned", "recovered from a terminal crash in a fresh session", "yes", "phase-10b/repair-01.md", "Previous session crash"),
    ("11", "gate-action", "saved a report whose path had no owner", "no", "phase-11/human-gate.md", "Human saved blocked-on-01"),
    ("12", "gate-action", "saved the repair report", "no", "phase-12/human-gate.md", "Human saved repair-01.md"),
    ("13", "unplanned", "ran seven hook-blocked --help probes", "no", "phase-13/human-gate.md", "the 7 hook-blocked scripts were run by the human"),
    ("14", "unplanned", "moved Region after account SCP denials", "no", "phase-14/human-gate.md", "Region moved us-east-1 -> us-east-2"),
    ("14", "unplanned", "upgraded the AWS account plan", "no", "phase-14/human-gate.md", "upgraded Free -> Paid"),
    ("14", "unplanned", "edited terraform.tfvars", "no", "phase-14/human-gate.md", "terraform.tfvars (git-ignored) edited"),
    ("14", "unplanned", "set API key secret and forced a redeployment by CLI", "no", "phase-14/human-gate.md", "put-secret-value"),
    ("14", "unplanned", "unplanned terraform apply started and cancelled", "no", "phase-14/human-gate.md", "An unplanned `terraform apply`"),
    ("14", "unplanned", "changed ALB ingress and allowed plaintext HTTP", "no", "phase-14/human-gate.md", "Human fix: tfvars alb_ingress_cidrs"),
    ("14", "gate-action", "ran the smoke test by hand", "no", "phase-14/human-gate.md", "Human ran scripts/smoke-test.sh"),
    ("14b", "gate-action", "ran the graph loader (live log archived)", "yes", "phase-14/human-gate.md", "loaded into the DynamoDB graph read model"),
    ("15", "unplanned", "copied two reports the guard refused to write", "no", "phase-15/human-gate.md", "human copied them"),
    ("15", "unplanned", "ran LoadMain and IngestMain from the shell, about 5 attempts", "no", "phase-15/human-gate.md", "the human ran LoadMain and IngestMain"),
    ("15", "gate-action", "denied the automatic rollback", "no", "phase-15/human-gate.md", "automatic rollback to 2365bf5 was denied"),
    ("15", "unplanned", "ran log-containment queries agents are blocked from", "no", "phase-15/human-gate.md", "Containment verified by the human"),
    ("15", "unplanned", "opened an AWS Support case for the Bedrock restriction", "no", "phase-15/human-gate.md", "AWS Support case (Account and billing) opened"),
]

# Artifacts needing substantial human rewrite: none is recorded. These rows are the evidence read.
REWRITES = [
    ("00", ".claude/agents/factory-engineer.md", "no (2 insertions, 3 deletions in commit c84390f, from git, not from this file)", "yes", "phase-00/human-gate.md", "Manual changes: none"),
    ("02", "build files", "no", "no", "phase-02/human-gate.md", "Manual changes: none to build files"),
    ("03", "source", "no", "no", "phase-03/human-gate.md", "No source edits"),
    ("04", "source", "no", "no", "phase-04/human-gate.md", "Manual changes: none to source"),
    ("05", "production and test code", "no", "no", "phase-05/human-gate.md", "No manual edits to production or test code"),
    ("06", "source", "no", "no", "phase-06/human-gate.md", "Manual changes: none to source code"),
    ("07", "generated artifacts", "no", "no", "phase-07/human-gate.md", "Manual changes: none to generated artifacts"),
    ("08", "code", "no", "no", "phase-08/human-gate.md", "No manual code edits"),
    ("09", "code", "no", "no", "phase-09/human-gate.md", "No manual code edits"),
    ("10", "any", "no", "no", "phase-10/human-gate.md", "## Manual changes"),
    ("10b", "any", "no", "no", "phase-10b/human-gate.md", "## Manual changes"),
    ("11", "code", "no", "no", "phase-11/human-gate.md", "No code was edited by hand"),
    ("12", "Terraform and scripts", "no", "no", "phase-12/human-gate.md", "No Terraform or script edited by hand"),
    ("13", "source", "no", "no", "phase-13/human-gate.md", "Manual changes: none to source"),
    ("14b", "code", "no", "no", "phase-14b/human-gate.md", "Manual changes: none to code"),
    ("15", "code", "no", "yes", "phase-15/human-gate.md", "Manual changes: none to code"),
]

# phase, repair_files_present is counted from the directory; documented rounds come from the text.
REPAIRS = [
    ("00", 0, "yes", "commit history has 'phase-00 repair'; no repair file", "phase-00/human-gate.md", None),
    ("01", 0, "yes", "no human gate", "phase-01/agent-report.md", None),
    ("02", 3, "no", "repair-01..03", "phase-02/human-gate.md", "repair-01/02/03"),
    ("03", 2, "yes", "repair-01 (guard) and repair-02; the reports also name per-agent repairs 1 and 2", "phase-03/human-gate.md", "repair-01 and repair-02"),
    ("04", 8, "yes", "repairs 01-08 cited; no repair file archived", "phase-04/human-gate.md", "repairs 01-08"),
    ("05", 1, "no", "repair-01 with follow-up", "phase-05/human-gate.md", "repair-01.md"),
    ("06", 2, "yes", "repair-02 cited; only repair-01.md archived", "phase-06/human-gate.md", "repair-02.md"),
    ("07", 2, "yes", "repair-01 and 02 cited and said added; no repair file present", "phase-07/human-gate.md", "repair-01.md and repair-02.md were missing"),
    ("08", 1, "no", "repair-01", "phase-08/repair-01.md", "build-engineer added rag -> storage dependency"),
    ("09", 4, "yes", "agent reports for repairs 01-04; human gate cites repair-01.md, none archived", "phase-09/human-gate.md", "rag-engineer repairs 02-04"),
    ("10", 0, "no", "none", "phase-10/human-gate.md", None),
    ("10b", 1, "no", "repair-01", "phase-10b/human-gate.md", "ACCEPTED after repair-01"),
    ("11", 0, "yes", "no repair loop; three test attempts; findings deferred", "phase-11/human-gate.md",
     "5 MAJOR findings are deferred"),
    ("12", 1, "no", "repair-01", "phase-12/human-gate.md", "after repair-01: APPROVE"),
    ("13", 2, "no", "repair-01 and 02", "phase-13/repair-02.md", "# Phase 13 Repair 02"),
    ("14", 3, "no", "repair-01..03", "phase-14/human-gate.md", "repair-03 (infra-engineer)"),
    ("14b", 1, "no", "repair-01", "phase-14b/human-gate.md", "repair-01"),
    ("15", 1, "yes", "repair-01.md archived; review rounds 2 and 3 also drove further changes", "phase-15/repair-01.md", "# Phase 15, repair 01"),
]

REVIEWS = [
    ("02", "review-report.md"), ("02", "review-report-02.md"), ("03", "review-report.md"), ("04", "review-report.md"),
    ("05", "review-report.md"), ("06", "review-report.md"), ("07", "review-report.md"),
    ("09", "review-report-01.md"), ("09", "review-report-02.md"), ("09", "review-report-03.md"),
    ("09", "review-report.md"), ("11", "review-report.md"), ("12", "review-report.md"), ("13", "review-report.md"),
    ("14", "review-report.md"), ("14", "repair-03-review.md"), ("14b", "review-report.md"),
    ("14b", "review-report-2.md"), ("15", "review-report.md"), ("15", "review-report-02.md"),
    ("15", "review-report-03.md"), ("15", "review-report-04.md"),
]
TABLE_ROW = re.compile(r"^\|\s*([A-Za-z]{0,2}-?\d+|[A-G])\s*\|\s*(critical|blocker|major|minor|nit)\b", re.I)
LIST_ROW = re.compile(r"^(\d+)\.\s+(CRITICAL|MAJOR|MINOR|NIT)\s+\|")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    out = parser.parse_args().out
    os.makedirs(out)

    emit(out, "work_orders.csv", ("phase", "agent", "work_order", "first_pass", "reason", "needs_confirmation"),
         WORK_ORDERS, 6)
    emit(out, "blocked_on.csv", ("phase", "agent", "blocked_on", "archived_verbatim", "needs_confirmation"),
         BLOCKED_ON, 5)
    emit(out, "blocked_on_partial.csv", ("phase", "agent", "note"), BLOCKED_ON_PARTIAL, 3)
    emit(out, "hook_blocks.csv",
         ("phase", "actor", "mechanism", "count", "count_basis", "note", "needs_confirmation"), BLOCKS, 7)
    emit(out, "ownership.csv", ("phase", "category", "actor", "description", "needs_confirmation"), OWNERSHIP, 5)
    emit(out, "interventions.csv", ("phase", "category", "description", "needs_confirmation"), INTERVENTIONS, 4)
    emit(out, "rewrites.csv", ("phase", "artifact", "substantial_human_rewrite", "needs_confirmation"), REWRITES, 4)

    repairs = []
    for phase, rounds, confirm, note, rel, anchor in REPAIRS:
        directory = os.path.join(REPO, BASE, "phase-" + phase)
        files = sorted(f for f in os.listdir(directory) if re.match(r"repair-\d+\.md$", f))
        repairs.append((phase, len(files), ";".join(files), rounds, confirm, note, rel, anchor))
    emit(out, "repairs.csv", ("phase", "repair_files_present", "files", "repair_rounds_documented",
                              "needs_confirmation", "note"), repairs, 6)

    findings, reviews = [], []
    for phase, name in REVIEWS:
        rel = "phase-%s/%s" % (phase, name)
        count, verdicts = 0, []
        for number, line in enumerate(read(rel), 1):
            verdict = re.search(r"^[#\s]*.*VERDICT:\s*(APPROVE|REQUEST_CHANGES)", line)
            if verdict:
                verdicts.append(verdict.group(1))
            match = TABLE_ROW.match(line) or LIST_ROW.match(line)
            if match:
                count += 1
                findings.append((phase, name, match.group(1), match.group(2).lower(), BASE + "/" + rel, number))
        reviews.append((phase, name, ";".join(verdicts), count, len(read(rel)), BASE + "/" + rel))
    for filename, header, rows in (
            ("review_findings.csv", ("phase", "review_file", "finding_id", "severity", "source_file", "source_line"),
             findings),
            ("reviews.csv", ("phase", "review_file", "verdicts", "findings_parsed", "file_lines", "source_file"),
             reviews)):
        with open(os.path.join(out, filename), "x", newline="") as handle:
            writer = csv.writer(handle, lineterminator="\n")
            writer.writerow(header)
            writer.writerows(rows)

    with open(os.path.join(out, "sources.sha256"), "x") as handle:
        for path in sorted(used):
            handle.write("%s  %s\n" % (used[path], path))
    for row in reviews:
        print(row[0], row[1], row[2], row[3])


if __name__ == "__main__":
    main()
