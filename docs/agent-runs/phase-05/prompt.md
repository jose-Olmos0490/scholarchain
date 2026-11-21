# Phase 05 work order: entity resolution

Use the entity-resolution-engineer agent to implement the ScholarChain entity-resolution subsystem against the Phase 3 contracts and the Phase 4 MatchCandidate records. Work only inside modules/entity-resolution/** and its test tree. If a needed change belongs to another owner (core contracts, Hadoop output, build), return BLOCKED-ON with evidence and the owner instead of editing it.

## PART A: implementation and review queue

Required behavior:
- Exact identifier rules for DOI, arXiv ID, ORCID, OpenAlex ID, Semantic Scholar ID, and repository URL.
- Normalization for Unicode, punctuation, whitespace, title markup, initials, name order, and DOI prefixes.
- Feature functions: researcher-name similarity, initials compatibility, author order, coauthor overlap, affiliation compatibility, ORCID/external ID agreement, title similarity, year distance, venue compatibility, citation-neighborhood evidence when available, source reliability. Each feature is a pure, unit-tested function with a documented range.
- A versioned scoring policy (policyVersion "er-v1"). Thresholds acceptThreshold, rejectThreshold, llmBandMin, llmBandMax are read from configuration, never hardcoded.
- Outcomes ACCEPTED, REJECTED, UNRESOLVED, CONFLICT using the Phase 3 MatchDecision/MatchOutcome contracts.
- CONFLICT when authoritative identifiers disagree (e.g., same DOI but different ORCIDs). Conflicts must never merge.
- LLM adjudication only for candidates whose score is inside [llmBandMin, llmBandMax).
  - Define a strict JSON schema with outcomes ACCEPT, REJECT, ABSTAIN, plus candidateIds, citedFields, confidence, reasonCodes.
  - Reject any LLM output naming an ID not present in the input candidates.
  - The LLM never modifies source records.
  - Cache adjudications keyed by hash(input, promptVersion, modelVersion, policyVersion).
  - Behind a port/trait: implement a deterministic fake adjudicator for tests. The real Bedrock adapter is config-gated and not called in sbt check. Store the prompt under modules/entity-resolution with a version string.
- Every decision preserves: source record ID, candidate target ID, features, rules fired, model/prompt version when applicable, confidence, decision, reason codes, timestamp, run ID.
- Produce accepted, rejected, unresolved, and conflict outputs from the bounded local sample.

Review queue (for human gold-set labeling):
- Generate a CSV of exactly 200 candidate pairs from the bounded sample at modules/entity-resolution/review-queue/review-queue-v1.csv. Use 220 if fewer than 200 are available after dedup and report the shortfall honestly instead.
- Stratify: about 50 likely matches (shared exact ID), about 50 likely non-matches, about 100 from the borderline score band. Include both researcher pairs and work pairs, labeled in a "kind" column.
- Columns: pair_id, kind, source_dataset, source_record_id, target_dataset, target_id, left_name_or_title, right_name_or_title, left_ids, right_ids, left_coauthors, right_coauthors, left_affiliation, right_affiliation, left_year, right_year, left_venue, right_venue, human_label, notes.
- BLIND LABELING: the queue must NOT contain the system's score or decision. Store the pair_id -> score/decision mapping in a separate file.
- human_label and notes are left empty.

Tests: unit tests for every normalizer and feature, threshold boundary tests (exactly at each threshold), conflict-never-merges test, LLM-abstain test, fabricated-ID rejection test, cache-key test, decision-provenance test.

Run sbt check. Then STOP and return a report with MANUAL ACTION REQUIRED: "human labels review-queue-v1.csv".

## PART B: gold-set evaluation (run only after the human says labels are done)

- Human labels live at docs/agent-runs/phase-05/gold-set-v1.csv (human-owned evidence). The evaluator receives that path as a command-line argument. No matching, feature, or threshold code may read, reference, or be tuned from this file.
- Add an evaluator runnable as:
  sbt "entityResolution/runMain <package>.EvaluateGoldSet docs/agent-runs/phase-05/gold-set-v1.csv"
- Treat UNSURE labels as excluded from precision/recall and report how many were excluded.
- Report SEPARATELY for researcher identity and work resolution:
  precision, recall, F1, confusion matrix, coverage, unresolved rate.
- Report metrics by match path: exact ID, deterministic bibliographic, probabilistic, LLM-adjudicated. If the LLM path used only the fake adjudicator, say so explicitly; do not present fake results as model results.
- Error analysis: up to 10 false positives and 10 false negatives with pair_id, features, and reason.
- Threshold sensitivity: precision/recall at several accept/reject thresholds. Report only; do NOT change the er-v1 thresholds based on the gold set.
- Write evaluation output to modules/entity-resolution/evaluation/er-v1/ (raw results file + summary).

Run sbt check and the evaluator. Report: policy version, feature definitions, thresholds, model and prompt versions, confusion matrices, unresolved rate, representative errors, exact commands run and observed output. Never report a command as passing unless you observed its output.
