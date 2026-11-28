# Phase 16c work order: bounded real-data run on EMR (lean; no new data routes)

Constraints: deadline Sunday 2026-11-29 23:00 CST. One human cloud window on Saturday. Small budget overrun approved by the instructor; avoid unnecessary AWS cost. Reuse existing code; no new dataset download routes. No AWS calls, plan, apply, deploy or commit in this phase. Never read tfstate, real tfvars or .env.

Human decisions (binding):
- DBLP input: the full dblp.xml.gz snapshot downloaded by the human with download.sh --full into ~/sc-raw/dblp. Bounded by the existing deterministic WorkCapJob cap: DBLP_SUBSET_MAX_WORKS = 100000 (fallback 30000), record types article,inproceedings, years 2000-2025. This resolves manifest open question dblp-max-works-selection; dataset-engineer records it.
- Enrichment: OpenAlex, ORCID and Crossref use the committed real samples (datasets/samples). Scale of enrichment linkage is a recorded limitation.
- Execution: EMR runs the full Hadoop -> ER -> Spark pipeline once (Experiment A) in the human's window. Experiment B is skipped. A local rehearsal on the same real input runs first.

Delegations (orchestrator routes; subagents never call each other):
1. infra-engineer:
   a. K1: environments/dev/variables.tf aws_region must not default to us-east-1 (no default, or us-east-2); fix backend.tf.example and README mentions.
   b. K3: set SCHOLARCHAIN_STORAGE_PUT_VECTORS_BATCH_SIZE to the Voyage batch size (128) for embedding-worker.
   c. Document the exact S3 raw layout the Step Functions pipeline reads, and add scripts/stage-raw.sh: verified local file(s) -> that layout for one dataset and snapshot date, sha256 recorded, refuses to overwrite, strict mode, greppable markers, prints the aws commands it runs. Human runs it.
   d. Add --input-root <dir> to run-bounded-pipeline.sh --local so the local rehearsal can read a real raw root laid out like the S3 raw zone.
   e. A commented "real-data run" block in terraform.tfvars.example with every pipeline_environment key the EMR jobs require (from modules/core reference.conf), filled with the decisions above; EMR at minimum capacity, managed scaling max no more than 4 units, auto-termination, step timeouts sized for a full DBLP parse; cost estimate.
   fmt, validate, check.sh. No plan.
2. dataset-engineer: record the dblp-max-works-selection decision and the enrichment-scale limitation in the manifest; no download code changes.
3. hadoop-engineer: local rehearsal with run-bounded-pipeline.sh --local --input-root on the real DBLP file (wait for ~/sc-raw/dblp to verify complete; if it is not complete, BLOCKED-ON human) plus the committed enrichment samples, stages hadoop,spark. Report works kept, emitted pairs, max team size, largest block, reject counts, Spark node/edge/component/community counts, runtime. Fix only defects in owned code; route others.
4. experiment-engineer: N2: correct the stale redelivery assertion in experiments/tests and the C/F cost estimates for the new redelivery behaviour; new manifest C2-embedding-backlog-voyage.yaml (at least 10,000 vectors, Voyage pricing, cost cap); update A for this run; reduce D to concurrency 1/5/10/25 with at most 50 requests per level; F stays optional. Manifests are left unapproved for the human.
5. test-engineer, then code-reviewer on the whole phase diff.

Stop only for: an unroutable BLOCKED-ON, a REQUEST_CHANGES finding that needs my decision, or final APPROVE. Archive agent reports verbatim; if you must condense, say so.
Gate: scripts/check.sh PASS, reviewer APPROVE, rehearsal counters reported.
