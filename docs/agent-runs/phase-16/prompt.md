# Phase 16 work order: evaluation

Sequence: experiment-engineer (run, collect, freeze) -> documentation-engineer (write-up after freeze) -> code-reviewer (read-only).
Environment: dev, us-east-2, AWS profile cs441. No production code, Terraform or script changes. No deploys.
Writes: experiment-engineer only under experiments/**; documentation-engineer only docs/evaluation.md.
Human-owned (agents must not create or edit): experiments/evaluation/labels/** (gold labels, benchmark expected answers, relevance labels, answer ratings). Agents write drafts to experiments/evaluation/review/.
Never read terraform.tfstate, .env or secrets.

Part A (offline):
1. Entity resolution: run the Phase 5 evaluation on the current human gold set. Precision, recall, F1, confusion matrix, coverage, unresolved rate, metrics by match path (exact ID, deterministic, probabilistic, LLM). Bootstrap 95% CIs (1000 resamples, fixed seed). Report gold-set size against the 200 target.
2. Graph correctness on the deployed run's Spark outputs: referential integrity, duplicate-edge rate, orphan rate, coauthor-edge support rate (supporting work IDs exist), known small-graph tests (degree, components, paths, communities, bridge, k-core) with pass counts, and a provenance audit of 50 random edges traced to source records (fixed seed).
3. Performance and elasticity: summarize Phase 15 Experiment C raw data only (scale-out and scale-in times, scaling delay, drain time, throughput, errors, cost). List A, B, D, E, F as not run.
4. Agentic factory, read-only from docs/agent-runs/phase-00..15: first-pass acceptance by agent, BLOCKED-ON count, ownership violations caught, reviewer findings by severity, repair iterations per phase, hook or guard blocks, manual interventions, artifacts needing substantial human rewrite. CSV with a source file for every count; flag counts that need human confirmation.
5. Reproducibility: clean-clone build (human supplies the log), bounded pipeline replay evidence from Phase 14/15, approved plan checksum vs deployed revision, and one command that regenerates every table and plot from raw data.

Part B (live API, only when the human says go):
6. Benchmark: at least 30 versioned questions covering coauthor breadth, max team size, direct collaboration, shortest paths, components, communities, bridges, temporal change, topics, institutions and evidence. Compute each expected answer deterministically from Spark or Neptune outputs, never from the LLM. Write a review sheet for the human.
7. Retrieval: recall@K, precision@K where labeled, MRR, nDCG where graded, for graph-only, vector-only and fused. Human-approved labels only.
8. Answers: run every benchmark question once through POST /v1/query. Record evidence-ID presence, claim support rate, edge/path/publication validity, identifier and numeric accuracy against approved expected answers, contradiction rate and abstention quality. Keep 5xx and failed answers.

Rules: raw outputs under experiments/evaluation/raw/<run-id>/ with commit SHA, UTC times and config. Raw files are immutable after collection. Never drop failed runs or negative results. Separate measured facts from interpretation.
Report: standard report fields plus the path of every raw file and the exact command that regenerates each table.
