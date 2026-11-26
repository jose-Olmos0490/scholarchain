# ScholarChain

CS441 course project. Evidence-bearing temporal coauthorship graph and
Graph-RAG collaboration analysis, built with Scala 3, Hadoop, Spark, AWS and a
controlled Claude Code agent factory.

**Student:** Jose Olmos, jolmo5@uic.edu (individual submission).

## Status at a glance

State at commit `5862b7b`, 2026-11-25. This is Phase 17 part 1.

| Area | State |
|---|---|
| Build and tests | `sbt clean check` passed: 2391 tests, 0 failed, **24 ignored** (deliberate deferrals) |
| Hadoop, entity resolution, Spark | Run **locally** on a bounded sample. **Not run on EMR** |
| AWS infrastructure | Applied in us-east-2 (without EMR) |
| Runtime services | Deployed. **No evidence-backed answer has been observed** |
| Neptune | Cluster created; **no graph load or query recorded** |
| Bedrock | **Blocked** account-wide; 0 vectors written |
| Security | Incident contained, **not resolved**; dev ALB is **plain HTTP** |
| Retrieval and answer evaluation | PENDING |
| Final report, runbook, release manifest, video | PENDING |

## Problem

Bibliographic queries by author string cannot say who really has the broadest
collaboration network, how two researchers are connected, or who bridges
separate communities. Those questions need identity resolution, coauthor-pair
generation, graph analytics and auditable evidence. ScholarChain prepares DBLP
and enrichment records with Hadoop, builds a temporal weighted coauthorship
graph with Spark, serves it from AWS stores, and answers questions with
evidence IDs, where code verifies every claim and a language model only selects
which verified facts to present.

## Architecture summary

| Stage | Technology | Module |
|---|---|---|
| Contracts, stable IDs, configuration | Scala 3.3.7 | `modules/core` |
| Parsing, normalization, blocking, coauthor pairs | Hadoop MapReduce 3.4.2 | `modules/hadoop` |
| Identity resolution | rules, features, bounded LLM band (fake adjudicator only) | `modules/entity-resolution` |
| Graph, metrics, components, paths, communities, bridges, k-core | Spark 4.1.1 | `modules/spark` |
| Neptune, DynamoDB, S3, S3 Vectors, SQS | AWS SDK v2 | `modules/storage` |
| Query workflow, evidence, verification, prompts | Bedrock ports | `modules/rag`, `prompts/` |
| query-api, graph-evidence-api, embedding-worker | cask on ECS Fargate | `modules/services` |
| Infrastructure and automation | Terraform, bash | `infra/terraform`, `scripts` |

Diagrams: [docs/architecture.md](docs/architecture.md). Optional OpenSearch,
OpenTelemetry and GitHub Actions are **not implemented**.

## Agentic workflow summary

The main Claude Code session is the orchestrator. It sends one work order to
one owning subagent; subagents never call each other; `code-reviewer` is
read-only; hooks block destructive commands and stale-test completion; the
human ratifies, applies Terraform and commits. Ownership map:
[docs/agents.md](docs/agents.md). Project memory: [CLAUDE.md](CLAUDE.md). Phase
records: [docs/agent-runs/](docs/agent-runs/).

## Required local software (tool-version record)

Observed on the development machine on 2026-11-25 (Ubuntu 24.04.3 LTS on WSL2,
kernel 6.6.87.2, 8 CPUs, 7 GiB RAM):

| Tool | Version | Needed for |
|---|---|---|
| Claude Code | 2.1.332 | agent workflow |
| Model of the session that wrote this README | Claude Sonnet 5.5 (`claude-sonnet-5-5`) | record only. Model configuration of earlier phases is not recorded in this README |
| JDK | OpenJDK 17.0.21.1 | build |
| sbt | 1.13.1 (`project/build.properties`); launcher script 2.0.12 | build |
| Scala | 3.3.7; scalafmt 3.11.5 (fetched by sbt) | build |
| Terraform | 1.17.2 (required `>= 1.10.0`; AWS provider 6.74.0) | infra checks |
| AWS CLI | 2.40.3 | cloud only |
| Docker | 29.1.3 | images, two MCP servers |
| git | 2.43.0 | all |
| Python | 3.12.3 (PyYAML for experiment tests) | validators, experiments |
| uv / uvx | 0.13.9 | MCP servers, evaluation regeneration |
| jq / curl | 1.7 / 8.5.0 | scripts, hooks |
| ShellCheck / checkov | 0.9.0 / 3.4.8 | script and IaC checks |

Library versions: `project/Dependencies.scala`.

## AWS prerequisites and environment variables

Cloud use needs: an AWS account allowed to create resources in the chosen
Region (this account allowed only us-east-2), a paid plan (Neptune), Bedrock
model access (**currently denied for this account**), a `terraform.tfvars` from
`terraform.tfvars.example`, and your own public IP /32 for ALB ingress. See
[docs/service-limits.md](docs/service-limits.md).

Names only. Never put values or secrets in the repository.

| Purpose | Variables |
|---|---|
| Claude Code and MCP | `GITHUB_PERSONAL_ACCESS_TOKEN` (fine-grained, read-only), `AWS_REGION`, `SCHOLARCHAIN_AWS_READONLY_PROFILE` |
| Approve live environments (human, before starting) | `SCHOLARCHAIN_APPROVED_ENVS` |
| Live scripts | `AWS_PROFILE`, `AWS_REGION`, `APPROVED_PLAN_SHA256`, `SCHOLARCHAIN_API_TOKEN`, `SMOKE_RESEARCHER_ID`, `EVIDENCE_S3_PREFIX` |
| Cleanup | `CONFIRM_CLEANUP`, `APPROVED_DESTROY_SHA256`, `CLEANUP_LOGS_PRESERVED`, `CLEANUP_LOCAL_EVIDENCE_ACK` |
| Local pipeline | `SCHOLARCHAIN_ENV`, `SCHOLARCHAIN_HADOOP_NUM_REDUCERS`, `SCHOLARCHAIN_HADOOP_DBLP_DTD_PATH`, `SCHOLARCHAIN_ER_*`, `SCHOLARCHAIN_GRAPH_*`, `SCHOLARCHAIN_SPARK_*`, `SCHOLARCHAIN_COMMUNITY_*` |
| Application configuration | listed in `modules/core/src/main/resources/reference.conf` and `modules/services/src/main/resources/scholarchain-services.conf` |
| AWS integration tests (off by default) | `SCHOLARCHAIN_AWS_IT`, `SCHOLARCHAIN_AWS_PROFILE` |

The `aws-api` MCP server needs a read-only profile (default name
`scholarchain-readonly`). That profile was never created for this project, so
the server could not be used.

## Build, test and package

From the repository root. Replay results are in the [replay record](#replay-record).

```bash
sbt fmt                                  # format
sbt Test/compile                         # compile (covered by check)
sbt check                                # format check, compile, tests, test marker
sbt clean check                          # from a clean checkout; about 36 minutes here
sbt packageJobs                          # Hadoop and Spark assembly jars
sbt dockerStage                          # image build contexts, no Docker daemon
sbt dockerLocal                          # three local images, never pushed
```

Terraform, without credentials:

```bash
terraform fmt -check -recursive infra/terraform
terraform -chdir=infra/terraform/environments/dev init -backend=false -input=false
terraform -chdir=infra/terraform/environments/dev validate
```

Terraform plan (**not replayed**: needs credentials or a `terraform.tfvars`,
and the documentation agent may not run scripts):

```bash
scripts/plan.sh --env dev --region us-east-2 --profile <profile>
scripts/plan.sh --env dev --region us-east-2 --offline     # review only, never applicable
```

A human reviews the plan and applies that exact file by hand. No script and no
agent applies it. Archived human runs:
[phase-12 gate](docs/agent-runs/phase-12/human-gate.md),
[phase-13 gate](docs/agent-runs/phase-13/human-gate.md).

Other checks:

```bash
.claude/hooks/validate-agent-files.sh --all
(cd datasets/samples && sha256sum -c SHA256SUMS)
python3 -m unittest discover experiments/tests
python3 -m unittest discover load-tests/tests
experiments/evaluation/regenerate.sh partA-1b0e893-20261126T014300Z
```

`scripts/check.sh` runs all gates, including the dataset validators
(`datasets/scripts/validate-manifest.sh`, `validate-samples.sh`). **Not
replayed** here: agent script-execution rules block them for the documentation
agent. Archived result: [check-output.txt](docs/agent-runs/phase-13/check-output.txt).

## Bounded local data run (no cloud)

Run in order, from the repository root.

```bash
# 1. Hadoop (LocalJobRunner) on the committed samples
SCHOLARCHAIN_ENV=dev SCHOLARCHAIN_HADOOP_NUM_REDUCERS=2 \
SCHOLARCHAIN_HADOOP_DBLP_DTD_PATH=datasets/samples/dblp/dblp-2023-06-28.dtd \
sbt "hadoop/runMain scholarchain.hadoop.driver.PipelineDriver --input-root datasets/samples --output-root target/phase04/run-b4"

# 2. Entity resolution (fake adjudicator, not a model)
env $(grep -v '^#' modules/entity-resolution/config/er-v1-thresholds.properties | xargs) SCHOLARCHAIN_ENV=dev \
sbt "entityResolution/runMain scholarchain.er.run.ResolveSample --hadoop-root target/phase04/run-b4 --output-root target/er-replay --adjudicator fake --decided-at 2026-11-20T21:40:18Z --er-policy-properties modules/entity-resolution/config/er-v1-thresholds.properties"

# 3. Spark graph and analytics
env $(grep -v '^#' modules/spark/config/local-run.properties | xargs) \
SCHOLARCHAIN_SPARK_MATCH_DECISIONS_DIR="$(ls -d target/er-replay/run_id=*)" \
SCHOLARCHAIN_SPARK_OUTPUT_ROOT=target/spark-readme \
sbt "sparkAnalytics/runMain scholarchain.spark.run.GraphPipeline --local --code-version $(git rev-parse --short HEAD)"

# 4. Storage load, dry run (no AWS client is built)
RUN="run:$(ls target/spark-readme/graph/runs | sed 's/^run_id=//')"
sbt "storage/runMain scholarchain.storage.loader.LoadMain --spark-output target/spark-readme/graph --run-id $RUN --snapshot 2026-11-01 --dry-run"
```

Expected (observed 2026-11-25): Hadoop run `run:fa68b48b...`, 24 works;
entity resolution 77 accepted, 3 conflict, 1 rejected, 11 unresolved; Spark
89 researchers, 334 coauthor edge rows, 28 paths; loader
`steps done: dynamo, s3, neptune-csv; rejects recorded: 0`. Hadoop refuses to
overwrite an incomplete output root; a completed one is skipped. Parquet file
counts, and so the loader's object count, can differ between runs. Details:
[docs/data-lineage.md](docs/data-lineage.md).

The same Hadoop stage is also wrapped by
`scripts/run-bounded-pipeline.sh --local` (**not replayed**).

## Reproducing the agentic workflow

1. Clone the private repository and check out the submitted tag.

   ```bash
   git clone https://github.com/jose-Olmos0490/scholarchain.git
   cd scholarchain
   git checkout <submitted-tag>        # PENDING: no tag exists in this document yet
   ```

2. Install Claude Code 2.1.332 and the tools in the version record.
3. Configure only what you need. For local work, nothing. For MCP, export
   `GITHUB_PERSONAL_ACCESS_TOKEN`; for AWS read access, a read-only profile
   named by `SCHOLARCHAIN_AWS_READONLY_PROFILE`. Never place secrets in the
   repository. Availability checks: `docker --version` (github and terraform
   MCP), `uvx --version` (aws-api and aws-docs MCP), `claude --version`.
4. Start Claude Code **from the repository root** with `claude`. Starting
   elsewhere loads no agents or hooks (observed in
   [phase-07](docs/agent-runs/phase-07/human-gate.md),
   [08](docs/agent-runs/phase-08/human-gate.md) and
   [09](docs/agent-runs/phase-09/human-gate.md)). For long builds the project
   used `CLAUDE_CODE_DISABLE_BACKGROUND_TASKS=1` and raised
   `BASH_DEFAULT_TIMEOUT_MS` and `BASH_MAX_TIMEOUT_MS` to 90 minutes.
5. Confirm loading: `/agents` lists 18 project agents; `/hooks` shows
   PreToolUse, PostToolUse, Stop and SubagentStop; `/mcp` shows `github`,
   `aws-api`, `terraform`, `aws-docs`; `CLAUDE.md` imports `docs/agents.md`.
6. Validate the agent system and the dangerous-command guard:

   ```bash
   .claude/hooks/validate-agent-files.sh --all     # includes safe guard probes
   ```

   Safe manual probe (**not replayed** by hand; the validator runs the same
   probe). The guard only reads the JSON; nothing is executed. Exit code 2 means
   blocked:

   ```bash
   printf '{"tool_name":"Bash","tool_input":{"command":"terraform destroy"}}' | .claude/hooks/guard-dangerous.sh; echo $?
   ```

7. To replay a phase, open a fresh session (or `/clear`), paste the exact work
   order from `docs/agent-runs/phase-NN/prompt.md`, and name the owning agent.
   **Gaps:** phases 01 and 15 have no `prompt.md`, and the `prompt.md` files of
   phases 08 and 09 contain unfilled placeholders, so those four cannot be
   replayed from the exact work order.
8. Invoke `test-engineer` or `code-reviewer` exactly when the phase record has a
   `test-report.md` or `review-report.md`.
9. Route every `BLOCKED-ON` result through the orchestrator to the named owner.
   Do not edit another owner's files.
10. Compare the new report, tests and diff with the archived evidence. Wording
    will differ; artifacts, contracts, tests, gates and observable results
    should match.
11. Start a fresh session before the next phase.

How reports are preserved: the orchestrator writes `agent-report.md`,
`review-report.md` and `test-report.md` verbatim; the human writes
`human-gate.md`, `repair-NN.md` and `commit.txt`.

### Bounded replay exercise (no cloud, about one hour)

On a throwaway branch of a fresh clone:

1. `.claude/hooks/validate-agent-files.sh --all`. Expected:
   `PASS=396 FAIL=0 SKIP=0`.
2. `sbt clean check` (about 36 minutes here). Expected: `[success]`, eight test
   summaries totalling 2391 passed, 0 failed, 24 ignored.
3. `echo '{"hook_event_name":"Stop"}' | CLAUDE_PROJECT_DIR="$PWD" .claude/hooks/verify-tests-ran.sh; echo $?`.
   Expected `0` after step 2.
4. Run `touch modules/core/src/main/resources/reference.conf` and repeat step 3.
   Expected `2` (stale marker). **This second case was not replayed** for this
   document (the guard blocked the agent's command).
5. Replay a small test-hardening task. In a `claude` session, paste:

   > Use the test-engineer agent to re-run the Spark known-graph and
   > determinism suites under tests/ and report exact counts. Do not edit
   > production code.

   Compare with [evaluation.md 2.8](docs/evaluation.md#28-known-small-graph-tests)
   (SparkDeterminismSpec 4/4, SparkGraphShapeSpec 8/8, and the rest) and the
   report format in [docs/agents.md](docs/agents.md). **This step was not
   replayed** for this document.
6. Run the bounded local data run above and compare with the expected values.

## Agent catalog

One row per file in `.claude/agents/`. "Report" is the common field list, in
order: `STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN,
TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED,
BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE`. Tool fence is the `tools`
frontmatter: **S** = Read, Grep, Glob, Write, Edit, Bash; **docs** = aws-docs
search and read. Every agent needs a session started at the repository root;
extra prerequisites are noted under Purpose. Verification commands are the
owner's deterministic gate. Per-module `sbt <module>/test` commands were **not
run individually** for this document (the same suites ran inside
`sbt clean check`); those marked (cloud) or (not replayed) were not run at all.
Example instructions are illustrative.

| Agent | Purpose (prerequisites) | Owned paths | Tool fence | Example orchestrator instruction | Verification command | Report fields |
|---|---|---|---|---|---|---|
| `factory-engineer` | Agent system (human ratification, restart) | `CLAUDE.md`, `docs/agents.md`, `.claude/agents/*`, `hooks/*`, `commands/*`, `settings.json`, `.mcp.json` | S | `Use the factory-engineer agent to propose a guard fix for the false positive on run_id= and print the full diff.` | `.claude/hooks/validate-agent-files.sh --all` | ten sections: Request, Matrix basis, Changes, Scope deltas, Floor invariants, Audits, Validation, Diff, Refusals, Ratification |
| `build-engineer` | Build and packaging (JDK, sbt, Docker for images) | `build.sbt`, `project/**`, `.scalafmt.conf`, ignore files, compose, `Dockerfile*`, `docker/**` | S | `Use the build-engineer agent to add a packaging check for the services logback.xml.` | `sbt check` | Report |
| `domain-engineer` | Shared contracts | `modules/core/**` | S | `Use the domain-engineer agent to add LLM backoff configuration keys with contract tests.` | `sbt core/test`, then `sbt check` | Report |
| `dataset-engineer` | Datasets, licenses, samples (curl, Python) | `datasets/**` | S | `Use the dataset-engineer agent to verify the OpenAlex manifest entry.` | `datasets/scripts/validate-manifest.sh` (not replayed) | Report |
| `hadoop-engineer` | MapReduce ingestion | `modules/hadoop/**` | S | `Use the hadoop-engineer agent to bound decompressed input size in the DBLP reader.` | `sbt hadoop/test` and the local run | Report |
| `entity-resolution-engineer` | Identity resolution (human gold labels) | `modules/entity-resolution/**` | S | `Use the entity-resolution-engineer agent to evaluate er-v1 on the gold set.` | `sbt entityResolution/test` | Report |
| `spark-engineer` | Graph and analytics (about 3 GiB heap for tests) | `modules/spark/**` | S | `Use the spark-engineer agent to add a cross_community_edge entry to the DynamoDB load manifest.` | `sbt sparkAnalytics/test` and the local run | Report |
| `storage-engineer` | Ports, adapters, loaders (aws-docs MCP) | `modules/storage/**` | S, docs | `Use the storage-engineer agent to dead-letter rejected embedding items.` | `sbt storage/test` | Report |
| `rag-engineer` | Runtime agents, prompts | `modules/rag/**`, `prompts/**` | S | `Use the rag-engineer agent to split chunks on all Unicode whitespace (defect T11).` | `sbt rag/test` | Report |
| `service-engineer` | REST services | `modules/services/**` | S | `Use the service-engineer agent to expire jobs stuck in RUNNING (defect T7).` | `sbt services/test` | Report |
| `test-engineer` | Adversarial and cross-module tests | `tests/**` | S | `Use the test-engineer agent to attack POST /v1/query with malformed bodies.` | `sbt adversarialTests/test` | Report; `TEST RESULTS` as `passed= failed= skipped= errored=`, plus `DEFECTS FOUND`, `COVERAGE BY REQUIREMENT` |
| `code-reviewer` | Independent review (diff pasted in; github MCP) | none | Read, Grep, Glob, five github read tools. No write, no shell | `Use the code-reviewer agent to review the pasted diff and return APPROVE or REQUEST_CHANGES.` | read-only verdict; no command | Report plus `VERDICT`, `FINDINGS` table |
| `infra-engineer` | Terraform and scripts (Terraform; aws-api, terraform, aws-docs MCP) | `infra/terraform/**`, `scripts/**`, `.github/workflows/**` | S, aws-api (read), terraform, docs | `Use the infra-engineer agent to add retention to the Neptune audit log group and produce a plan. Do not apply.` | `terraform fmt -check -recursive infra/terraform` and `validate` | Report plus `PLAN CHECKSUM`, `STATEFUL REPLACEMENTS AND IAM CHANGES`, `ESTIMATED HIGH-COST RESOURCES` |
| `deploy-engineer` | Approved deployment (approved env, plan checksum, clean tree, AWS credentials) | `reports/deployments/**` | Read, Grep, Glob, Write, Bash, aws-api. No Edit | `Use the deploy-engineer agent to deploy commit <sha> with plan checksum <sha256> to dev.` (or `/deploy`) | `scripts/smoke-test.sh --env dev` (cloud) | Report plus `COMMIT SHA / PLAN CHECKSUM / ...`, `DEPLOYED IMAGE DIGESTS AND TASK REVISIONS`, `ROLLBACK ACTION` |
| `experiment-engineer` | Experiments (approved manifest and env) | `experiments/**`, `load-tests/**` | S | `Use the experiment-engineer agent to run the approved C-embedding-backlog manifest.` (or `/experiment`) | `python3 -m unittest discover experiments/tests` | Report plus `MANIFEST / COMMIT SHA / ...`, `RESULTS SUMMARY AND LIMITATIONS`, `ESTIMATED COST` |
| `documentation-engineer` | Documentation (aws-docs MCP) | `README.md`, `docs/**` except `agents.md`, `agent-runs`, `incidents`; `reports/**` except `deployments` | S, docs | `Use the documentation-engineer agent to write the runbook from verified state.` | link check and command replay | Report plus `UNVERIFIED OR ILLUSTRATIVE STATEMENTS` |
| `incident-responder` | Diagnosis and rollback (logs read access) | `docs/incidents/**` | Read, Grep, Glob, Write, Bash, aws-api, two github read tools. No Edit | `Use the incident-responder agent to diagnose the failing smoke evidence check.` (or `/incident`) | `scripts/smoke-test.sh --env dev` (cloud) | Report plus `INCIDENT ID / DETECTION / IMPACT / ROOT CAUSE / RECOVERY ACTION` |
| `dependency-updater` | Version bumps (major needs approval) | version values in `project/Dependencies.scala` | Read, Grep, Glob, Edit, Bash. No Write | `Use the dependency-updater agent to apply patch updates and report CVE status.` | `sbt check` | Report plus `VERSION CHANGES` |

Commands: `/deploy`, `/rollback`, `/incident`, `/run-pipeline`, `/experiment`
(`.claude/commands/`).

## Deployment and smoke test: PENDING

PENDING: final deployment path and smoke result, after the pending fixes. What
follows is the current script path and was **not replayed**.

```bash
sbt packageJobs dockerLocal            # or scripts/package.sh
scripts/plan.sh --env dev --region us-east-2
# human reviews and applies the saved plan by hand
APPROVED_PLAN_SHA256=<sha256> scripts/deploy.sh --env dev --region us-east-2
SCHOLARCHAIN_API_TOKEN=<token> SMOKE_RESEARCHER_ID=<researcher id> \
  scripts/smoke-test.sh --env dev --region us-east-2 --base-url <url>
scripts/rollback.sh --env dev --to-sha <previous sha> --region us-east-2
```

Last recorded state: commit `3c3e1a1`, smoke **7 of 8**, `evidence` check
failed ([deploy report](docs/agent-runs/phase-15/deploy-report-3c3e1a1.md)).
Do not send a real token over HTTP. Deployed application link: PENDING.

## Experiments: PENDING

PENDING: reproduction of experiments beyond C, and Part B evaluation. Command
form (**not replayed**; needs an approved manifest and environment):

```bash
scripts/run-experiment.sh --env dev --manifest experiments/manifests/C-embedding-backlog.yaml
scripts/run-bounded-pipeline.sh --env dev      # needs EMR, which was never enabled
scripts/archive-evidence.sh --env dev
```

## Results: PENDING

PENDING: retrieval quality, answer quality and final results summary. Offline
Part A results, including negative results, are in
[docs/evaluation.md](docs/evaluation.md) (draft). Archived experiment data:
[experiments/raw/](experiments/raw/), [experiments/derived/](experiments/derived/).
In short: only experiment C ran; it scaled 0 to 2 to 0 tasks and **failed** with
no vectors written.

## API query examples

See [docs/api-reference.md](docs/api-reference.md). Illustrative body:

```json
{"operations": ["DIRECT_COAUTHORS"], "researcherIds": ["researcher:orcid:<64 hex>"], "windowStartYear": 2010, "windowEndYear": 2013}
```

## Final report and video

- Final report: PENDING (`reports/final-report.md`).
- Demonstration video: PENDING. Draft script: [docs/demo-script.md](docs/demo-script.md).
- Submitted tag and commit SHA: PENDING.

## Cleanup

Human-only, interactive terminal required. **Not replayed; not yet rehearsed**
according to the records read.

```bash
scripts/cleanup.sh --env dev --region us-east-2            # dry run, exit 3
CONFIRM_CLEANUP=<env>-<account-id> APPROVED_DESTROY_SHA256=<sha256> \
CLEANUP_LOGS_PRESERVED=yes scripts/cleanup.sh --env dev --region us-east-2
```

First archive evidence, apply a reviewed change with `protect_stateful=false`,
and empty buckets deliberately. See
[infra README, Cleanup](infra/terraform/README.md#cleanup). Planned window: the
dev environment stays up until Sunday 2026-11-29, then cleanup after the demo
([phase-16 gate](docs/agent-runs/phase-16/human-gate.md)). Resource inventory
before and after: PENDING. Rotate the API token at cleanup.

## Known limitations

- Bounded sample only (24 works, 89 researchers); course scale minimums
  (100,000 works, 1,000,000 edges, 10,000 chunks) not met.
- No EMR run of Hadoop or Spark; no EMR entry point for entity resolution.
- No Neptune load or query recorded.
- Bedrock account block: no embeddings, no live model call, no vectors.
- No evidence-backed answer observed from the deployed system.
- Nothing enqueues embedding work items; rejected items are acknowledged
  instead of dead-lettered.
- Gold set 92 of 200 pairs, 4 DIFFERENT; LLM adjudication evaluated only with a
  fake.
- Betweenness not implemented; community results coincide with components on the
  sample data.
- 24 ignored tests; 5 deferred MAJOR defects
  ([phase-11 gate](docs/agent-runs/phase-11/human-gate.md)).
- Security: [threat model](docs/threat-model.md) (credential logging incident,
  plain HTTP, Neptune audit log, administrator identity, local Terraform state).
- Experiments A, B, D, E, F not run.
- Deviations recorded by the human: Region us-east-2; paid account plan;
  48-hour environment window extended. Instructor approval of any deviation:
  PENDING (none is recorded; approval for the smaller gold set was requested).

## Deliverable index

| ID | Deliverable | Where | State |
|---|---|---|---|
| D01 | Private repository | `github.com/jose-Olmos0490/scholarchain` | instructor access and tags: PENDING |
| D02 | Agentic constitution | [CLAUDE.md](CLAUDE.md), [docs/agents.md](docs/agents.md), [.claude/](.claude/), [.mcp.json](.mcp.json), this README | present |
| D03 | Agent run evidence | [docs/agent-runs/](docs/agent-runs/) | present with gaps (step 7 above) |
| D04 | Deterministic build | [build.sbt](build.sbt), [project/](project/) | present |
| D05 | Datasets | [datasets/](datasets/) | present |
| D06 | Hadoop | [modules/hadoop](modules/hadoop/) | no EMR run |
| D07 | Entity resolution | [modules/entity-resolution](modules/entity-resolution/) | gold set below minimum |
| D08 | Spark | [modules/spark](modules/spark/) | local only |
| D09 | Storage | [modules/storage](modules/storage/) | Neptune and vectors not loaded |
| D10 | Runtime analysis | [modules/rag](modules/rag/), [prompts/](prompts/) | tested with fakes |
| D11 | Microservices | [modules/services](modules/services/) | deployed, smoke 7/8 |
| D12 | Terraform | [infra/terraform](infra/terraform/) | applied without EMR |
| D13 | Automation | [scripts/](scripts/) | present; cleanup not rehearsed |
| D14 | Deployment package | [phase-14](docs/agent-runs/phase-14/), [phase-15](docs/agent-runs/phase-15/) records | release manifest PENDING |
| D15 | Experiments | [experiments/](experiments/), [load-tests/](load-tests/) | only C |
| D16 | Evaluation | [docs/evaluation.md](docs/evaluation.md) | Part B PENDING |
| D17 | Documentation | [architecture](docs/architecture.md), [lineage](docs/data-lineage.md), [threat model](docs/threat-model.md), [limits](docs/service-limits.md), [API](docs/api-reference.md), [evaluation](docs/evaluation.md) | runbook PENDING |
| D18 | Final report | `reports/final-report.md` | PENDING |
| D19 | Video | | PENDING |
| D20 | Cleanup evidence | | PENDING |

## Replay record

Replayed 2026-11-25 by `documentation-engineer` in a fresh local clone of
`5862b7b` unless noted.

| Command | Observed |
|---|---|
| `sbt clean check` | exit 0, 36 min 12 s; test summaries 212, 695, 369, 148, 244, 75, 69, 579 passed (2391); 0 failed; 24 ignored |
| `sbt fmt` | exit 0, no file changed |
| `sbt packageJobs` | exit 0; Hadoop and Spark assembly jars 0.1.0 |
| `sbt dockerStage` / `sbt dockerLocal` | exit 0 / exit 0, three images built locally |
| `.claude/hooks/validate-agent-files.sh --all` (working repository) | `PASS=396 FAIL=0 SKIP=0` |
| `terraform fmt -check -recursive`; `init -backend=false`; `validate` | exit 0; `The configuration is valid.` |
| `sha256sum -c SHA256SUMS` (samples) | 7 of 7 OK |
| `python3 -m unittest discover` (experiments, load-tests), with `PYTHONDONTWRITEBYTECODE=1` | 54 OK; 53 OK |
| `experiments/evaluation/regenerate.sh partA-...` | exit 0, 580 raw files verified, tree unchanged |
| Bounded local data run, steps 1 to 4 | all exit 0; results above |
| test-marker hook, fresh marker | exit 0 |

`sbt check` and `sbt Test/compile` were not run separately; `sbt clean check`
includes them.

Not replayed: per-module `sbt <module>/test` commands, `scripts/*.sh` (agent execution rules; several need AWS), dataset
validator scripts (guard blocked), Terraform plan, apply and cleanup, deploy,
smoke, rollback, experiments, `curl` examples, `claude` interactive steps, the
manual guard probe, the stale-marker case, `git clone` from GitHub, and
`IngestMain --dry-run` beyond its configuration error.
