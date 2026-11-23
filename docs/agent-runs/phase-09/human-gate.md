# Phase 9 human gate

## Reviewed
- Work order A report (agent-report-A.md)
- Work order B report (agent-report-B.md)
- code-reviewer verdict (review-report.md)
- Benchmark expected answers: spot-checked __ of 30 by hand

## Accepted / rejected
- domain-engineer repair 01 accepted. Human verified marker timestamp, 0 failures/errors in test XML, and no changes outside modules/core. Config defaults (resolver minConfidence 0.8, minMargin 0.1, etc.) were agent-chosen and are accepted by the human.
-

## Manual changes / repairs
- Repair 01: rag-engineer BLOCKED-ON domain-engineer (QueryAuditRecord, config keys) and storage-engineer (read ports, name directory, audit log). Routed domain -> storage -> rag rerun.
- Human decisions: audit stored in existing QuerySession table (no new table); name directory as port + fake, no entity-resolution changes.

## Disclosures
- Subagents spawn in the background by default in this Claude Code version. Set CLAUDE_CODE_DISABLE_BACKGROUND_TASKS=1 in the user shell (~/.bashrc, not project settings) so later spawns run synchronously.
- Stop hook (verify-tests-ran.sh) refused orchestrator completion while core files were newer than .claude/.last-test-run. The orchestrator did not claim COMPLETE.
- rag-engineer A ran as a backgrounded subagent; report copied verbatim from the session.
- Synthesis tested with a scripted fake LLM; live Bedrock run deferred to post-deployment (Phase 14/15).

## Remaining risk
- DEFERRED to a pre-Phase-14 follow-up: (1) export components/communities/cross-community counts/bridge metrics to a store (spark + storage + infra); (2) edge-support index for supporting work IDs (storage + infra); (3) name-directory loader from entity-resolution outputs (storage). Until then AnalyticsReader, EdgeSupportReader, ResearcherDirectory are fake-only. Neptune paths/topK templates unverified against a live cluster.
- ResearcherMetricCache is an optional table; if absent, metrics-by-window fall back to the GraphReader node window only.
- ResearcherDirectory may ship fake-backed only if no loaded store holds canonical names + match confidence (see storage repair report).

## Accepted commit
(see commit.txt)
