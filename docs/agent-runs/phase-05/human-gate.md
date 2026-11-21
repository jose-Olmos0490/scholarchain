# Phase 05 human gate: entity resolution

## What was reviewed
- entity-resolution module: normalizers, features, er-v1 scoring policy (revision er-v1-r2), thresholds, conflict handling, LLM adjudication port with fake adjudicator, decision provenance
- review queue (review-queue-v1.csv) and gold-set evaluation (Part B)
- test-report.md, review-report.md (final verdict: APPROVE), repair-01.md

## Gold set
- 92 candidate pairs labeled manually by me: 88 SAME, 4 DIFFERENT, 0 UNSURE.
- Labeled blind (no system scores or decisions shown), using a computed fact sheet with no suggested labels.
- 3 labels corrected after the code-reviewer flagged them (rq1-3118e91420c49d24, rq1-752498b811b17e66, rq1-f5ddae5a4d51c57b: DIFFERENT -> SAME). I had treated conflicting IDs as proof of different entities; on review they were the same paper with dual ACM/IEEE DOIs and the same author with a duplicate ORCID. rq1-3256858c310df7a5 was rechecked and kept DIFFERENT (different award recipients).

## Decision
ACCEPTED

## Manual actions (disclosed)
- Labels were first saved by Excel as .xlsx; I merged only the human_label and notes columns back into gold-set-v1.csv with a script, matched by pair_id, and deleted the .xlsx.
- repair-01.md and its follow-up append were drafted by the orchestrator and pasted into place by me, because docs/agent-runs is human-owned.
- No manual edits to production or test code.

## Decisions made during repair
- F5 build fix, F1-F4 and F7-F13 repaired. F14 and D6-D10 deferred as known limitations.
- 9 adversarial tests for D6-D10 marked ignored with defect IDs: a deliberate reversal of "leave failing tests in place" so that sbt check passes.
- F7 thresholds kept in the er-v1 module properties file (no core change). Policy kept as er-v1, revision er-v1-r2.
- N5: evaluator package excluded from the code fingerprint; N6: superseded weak F11 tests removed.
- Part B was accepted from the run that completed during the laptop-sleep interruption instead of being redone; after the relabeling, only the evaluator was rerun.

## Remaining risks
- Gold set is 92 pairs, below the 200 minimum (instructor approval requested; top-up planned in Phase 16 from EMR output).
- Gold set is heavily imbalanced (88 SAME / 4 DIFFERENT), so precision estimates are weak.
- LLM adjudication evaluated with the fake adjudicator only; Bedrock config deferred to Phase 9.
- r2 decisions are not byte-for-byte reproducible from the final tree (fingerprint changed after the run); accepted as a limitation.
- Dual-DOI (ACM/IEEE) and duplicate-ORCID cases resolve to CONFLICT rather than merge; recognizing them is deferred to a future policy revision.
- Exact-ID group expansion is quadratic for very large groups.
- ErEmptyFieldsSpec.scala:232-235 is vacuous; follow-up for test-engineer.

- Reviewer findings N1-N4 recorded as known limitations, not fixed in code (see modules/entity-resolution KNOWN-LIMITATIONS.md); N3 and N4 remain open.

## Accepted commit
See commit.txt.
