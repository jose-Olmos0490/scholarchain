# Phase 9 human gate

## Reviewed
- All agent reports (agent-report.md combines them in order) and all four code-reviewer reports (review-report-01..03.md, final review-report.md = APPROVE).
- Repair loops recorded in repair-01.md.
- Benchmark expected answers (modules/rag/src/test/resources/benchmark/questions-v1.json): human spot-checked 10 of 30.

## Accepted / rejected
- Accepted: runtime collaboration-analysis workflow (planner, resolver, metrics, coauthors, paths, community, semantic context, evidence builder, synthesis, deterministic claim verification, abstention, workflow runner, audit).
- Accepted: domain-engineer repair 01 (QueryAuditRecord, 11 config keys; defaults were agent-chosen and are accepted by the human).
- Accepted: storage-engineer repair 01; build-engineer repair 03a; rag-engineer repairs 02-04.
- Final review: APPROVE. Four minor items accepted (see Remaining risk).

## Manual changes / repairs
- No manual code edits. All code changes were made by owning agents.
- Human decisions: audit stored in existing QuerySession table (no new table); name directory as port + fake; storage gaps deferred; structural fix (code renders all facts) chosen over further free-text patching; VerificationPolicyVersion named claim-verification-v2; finding 9 (Bedrock import in modules/rag from Phase 8) accepted as a Phase 8 deviation.
- Two labeled checkpoint commits were made mid-phase (after repair 01, after work order A).

## Disclosures
- domain-engineer repair 01 first run was backgrounded and ended with the session before reporting; it left 12 modified + 2 new files in modules/core. The rerun inspected and kept that work unchanged.
- One resumed orchestrator session started in /home/olmos (outside the repo), so agents and hooks did not load; the orchestrator refused to work inline or via a general-purpose agent. Restarted from the repo root.
- Subagents spawn in the background by default in this Claude Code version. Set CLAUDE_CODE_DISABLE_BACKGROUND_TASKS=1 and raised BASH_DEFAULT_TIMEOUT_MS / BASH_MAX_TIMEOUT_MS to 90 minutes in the user shell (~/.bashrc, not project settings).
- Hooks fired as designed: verify-tests-ran.sh refused orchestrator completion while tests were stale; guard-dangerous.sh blocked an orchestrator write to an unowned path (agent-report-B.md). The orchestrator wrote to the permitted name agent-report.md and the human renamed files.
- storage-engineer repair 01 report is the orchestrator's relay summary; the agent's verbatim text was not captured.
- Synthesis tested with a scripted fake LLM; live Bedrock run deferred to post-deployment (Phase 14/15).
- No AWS resources were used in Phase 9; all tests ran against fakes.

## Remaining risk
- DEFERRED to a pre-Phase-14 follow-up: export components/communities/cross-community counts/bridge metrics to a store (spark + storage + infra); edge-support index for supporting work IDs (storage + infra); name-directory loader from entity-resolution outputs (storage). Until then AnalyticsReader, EdgeSupportReader and ResearcherDirectory are fake-only.
- Neptune paths/<d> and topK templates unverified against a live cluster.
- ResearcherMetricCache is an optional table; without it rankings are dropped from evidence (no node-window fallback, by design).
- DEFERRED: prompts/ packaging into the service image (Phase 10); code-point limits and display caps as config keys (review finding 7; later domain-engineer repair).
- Phase 10 must keep WorkflowResult.removed diagnostic-only (finding 4), select planner prompt v1 + synthesis prompt v3/schema v2, map IdempotencyConflict to 409, and render « » delimiters.
- Accepted minors from review 04: ID-failure uncertainty/status path untested; summary may name a researcher from an ID-failed claim; a length cap can leave an unclosed «; gap/partial-reason text is unquoted (code strings only today).

## Accepted commit
(see commit.txt)
