# Phase 05 repair-01

Drafted by the orchestrator for the human architect, who pastes it into
`docs/agent-runs/phase-05/repair-01.md` (human-owned). Sources:
`review-report.md` (findings F1 to F14, first review), `test-report.md` (defects D1 to D10),
`modules/entity-resolution/POLICY-CHANGELOG.md`, `modules/entity-resolution/KNOWN-LIMITATIONS.md`,
and the PART B report in `agent-report.md`.

## Trigger

The first code review returned REQUEST_CHANGES (F1 to F14). The test-engineer's adversarial
suite found 10 defects (D1 to D10). Both reports are in this directory.

## Repairs completed

| ID | Owner | Repair |
|---|---|---|
| F5 | build-engineer | `build.sbt`: `adversarialTests` now `.dependsOn(core, hadoop, entityResolution)`, so the ER adversarial suite compiles and runs under `sbt check`. |
| F1 / D3 | entity-resolution-engineer | `AdjudicationSchema.parse` rejects LLM output with a repeated key in any object (`DUPLICATE_KEY`). Cached outputs are re-validated on replay. |
| F2 / D4 | entity-resolution-engineer | `ExactIdRules.compare`: any difference between non-empty researcher ID sets is a disagreement. A shared value plus a disagreement gives CONFLICT, so ORCID {A,C} vs {B,C} is now CONFLICT, not ACCEPTED. |
| F3 / D1 | entity-resolution-engineer | Two mentions on the same work at different author positions are never ACCEPTED and never sent to the LLM. They are CONFLICT, REJECTED or UNRESOLVED (`SAME_WORK_DIFFERENT_POSITION`). |
| D2 | entity-resolution-engineer | `coauthorOverlap` (er-features-v2) uses a one-to-one match of compatible names instead of `<surname> <initial>` keys, so `Li Wang` no longer matches `Lei Wang`. |
| F4 / D5 | entity-resolution-engineer | A versioned generic-title list (`generic-titles-v1.txt`). A generic-title work pair is never ACCEPTED without an agreeing exact ID. |
| F7 | entity-resolution-engineer | Cutoffs `minSurnameSimilarity` 0.85, `minSharedIdTitleSimilarity` 0.5 and `llmMinConfidence` 0.7 moved out of `er-v1.json` into `config/er-v1-thresholds.properties`, passed with the required `--er-policy-properties` argument. There is no fallback. The values and the file's SHA-256 are recorded in the run manifest. No domain-engineer change. |
| F8 | entity-resolution-engineer | A single `LLM_ADJUDICATION` rule for both a cache hit and a model call. `--decided-at` is required. Warm and cold caches give byte-identical output. |
| F9 | entity-resolution-engineer | The run manifest records the code fingerprint, the policy revision, the policy, generic-title and prompt hashes, and the cutoffs. Outputs are write-once. |
| F10 | entity-resolution-engineer | Unsuffixed dblp keys are not exact IDs. A shared `REPOSITORY_URL` is not identity. |
| F11 | entity-resolution-engineer | New feature tests that fail without F2 and F8: PolicyRevisionR2Spec.scala:117-144 (overlapping, subset and multi-type ID sets) and ResolveSampleSpec.scala:251 (warm vs cold cache determinism). The original weak tests in MatchPolicySpec remain (re-review N6). |
| F12 | entity-resolution-engineer | `Phase4Input.load` fails on any contract-violating row instead of dropping it. |
| F13 | entity-resolution-engineer | The adjudication cache key includes the prompt-text SHA-256 and the policy revision. |
| F6 | entity-resolution-engineer | Unblocked by the labeled gold set. The PART B evaluator and evaluation are now done (see below). |

The re-review (VERDICT: APPROVE) verified every row above in code, each with a feature test.

Edits under tests/** in this repair (owner test-engineer; the human confirms authorship, re-review N7):
- `ErAdv.scala:44-54` loads the F7 cutoffs from the properties file.
- 9 tests were changed to `ignore(...)` (next section).

## Deferred as known limitations (human decision)

F14 and D6, D7, D8, D9, D10 are not fixed. They are recorded in
`modules/entity-resolution/KNOWN-LIMITATIONS.md`.

## Reversal: ignored adversarial tests

- The first ruling deferred D6 to D10 and left their adversarial tests failing on purpose.
  That made `sbt check` fail.
- **Reversed:** the 9 adversarial tests for D6 to D10 are now `ignore(...)`. Each carries a
  comment citing its defect ID and KNOWN-LIMITATIONS.md:
  - ErCommonNameSpec: D6 ×1
  - ErNameOrderSpec: D7 ×2
  - ErUnicodeConfusableSpec: D8 ×4
  - ErEmptyFieldsSpec: D9 ×1, D10 ×1
- `sbt check` must exit 0. Observed: the adversarialTests project ran 308 succeeded, 0 failed,
  9 ignored.
- Follow-up: `KNOWN-LIMITATIONS.md` still says these tests "still fail on purpose". The
  entity-resolution-engineer should change it to "ignored".

## Ratified decisions

- Policy version `er-v1`, decision-logic revision `er-v1-r2`, feature version
  `er-features-v2`. The er-v1 thresholds are unchanged: accept 0.80, reject 0.45,
  LLM band [0.60, 0.80).
- F7 cutoffs live in the er-v1 module properties file. No core change.
- Provenance uses option (a): `POLICY_REVISION:er-v1-r2` in `rulesFired`, plus
  `provenance.runId` linking to the run manifest that records the revision. No new core field.
- Gold set: `docs/agent-runs/phase-05/gold-set-v1.csv`, 92 human-labeled pairs
  (85 SAME, 7 DIFFERENT; researcher 67/4, work 18/3). It is below the 200-pair minimum, and
  the shortfall was accepted. The review queue was not regenerated: it and its sealed key come
  from revision r1, and the pair set is unchanged.
- PART B scores the r2 run
  `run_id=7449422852604780fd2de54fcaa1ff18ebe1cf761c260d8cf4de08b368ce4e15`.
- Follow-up for test-engineer, not in this phase: the assertion at
  `tests/.../ErEmptyFieldsSpec.scala:232-235` is vacuous.

## Interrupted-session recovery

- The PART B session was believed interrupted by laptop sleep. In fact the subagent kept
  running and finished at 2026-11-21 05:23 with STATUS COMPLETE, and its `sbt check` exited 0.
- The human chose to accept that run instead of redoing PART B.
- The orchestrator replaced the earlier BLOCKED-ON "## Part B" section of `agent-report.md`
  with that report, word for word.

## PART B result (r2, fake adjudicator only, 0 pairs in the LLM band)

| Kind | P | R | F1 | Unresolved | FP | FN |
|---|---|---|---|---|---|---|
| Researcher | 59/59 | 59/67 | 0.937 | 11/71 | 0 | 8 |
| Work | 18/18 | 18/18 | 1.000 | 0/21 | 0 | 0 |

All 8 false negatives are F3 `SAME_WORK_DIFFERENT_POSITION` pairs. The r1 run had recall
67/67, so F3 traded 8 true matches for over-merge safety, with no precision gain on this gold
set. There are only 7 negatives, so the specificity intervals are wide.

## Remaining risk

- Small, easy-skewed gold set: 7 negatives, not a random sample.
- The LLM path has never been evaluated.
- The Bedrock adapter cannot be enabled.
- The policy revision is not a core provenance field.
- Labels to recheck: `rq1-3118e91420c49d24` and `rq1-752498b811b17e66` are DIFFERENT with
  "conflicting DOI/ORCID" notes.

## Accepted commit hash

(human)
## Follow-up to the re-review (findings N1-N7)

The following earlier lines of this file are out of date, and the sections below replace them:
- the gold-set counts (85/7);
- the PART B results table (researcher recall 59/67, work recall 18/18);
- "Labels to recheck";
- "the human confirms authorship" for the tests/** edits.

The second review returned APPROVE with seven minor findings, N1-N7. The human decided:

| ID | Decision | Done by | Result |
|---|---|---|---|
| N1 | Minor: record it, do not fix code | entity-resolution-engineer | KNOWN-LIMITATIONS.md now says the D6-D10 tests are ignored. The column header now reads "Ignored adversarial test(s)", and a correction note quotes the old wording. |
| N2 | Minor: record it | entity-resolution-engineer | POLICY-CHANGELOG.md no longer says "BLOCKED-ON domain-engineer". It now says this was settled by ratified decision 4, option (a). The missing core field is listed as a known limitation. |
| N3 | Minor: record it | entity-resolution-engineer | The evaluator's default run, key and output paths are listed as a known limitation. |
| N4 | Minor: record it | entity-resolution-engineer | Evaluation output is overwritten in place, not written once. Listed as a known limitation. |
| N5 | Fix it; do not regenerate r2 | entity-resolution-engineer | `CodeFingerprint.of`, which feeds the run ID, now skips `scholarchain/er/evaluation/**`. A new `ofModule` covers the whole module, and the evaluation ID now uses it. CodeFingerprintSpec adds 8 tests. r2 was not regenerated or touched. |
| N6 | Remove the weak tests | entity-resolution-engineer | Two superseded tests were removed from MatchPolicySpec: "no CONFLICT input is ever ACCEPTED…" and "decisions are deterministic…". The stronger replacements stay: PolicyRevisionR2Spec (F2/F11) and the ResolveSampleSpec warm/cold cache test. |
| N7 | test-engineer confirms authorship | test-engineer | See the block below. |

N7 resolved: the tests/** repair edits were made by a test-engineer subagent ("ErAdv cutoffs + pend D6-D10", transcript
subagents/agent-a59f2ab8367080d36.jsonl of session 2e4c9e5c), Edits at 2026-11-21T03:30:45Z-03:31:30Z, matching file mtimes.
Its report (STATUS: COMPLETE, sbt check exit 0, 2026-11-21T04:38:46Z) was not archived in this directory.
Transcript diffs show only test( -> ignore( plus a D<n> comment for the 9 tests; bodies and expected values unchanged.
No other agent wrote under tests/**/er/ after test-report.md (18:32:23 -0600).

### Can r2 be reproduced? (N5)

No. The r2 replay was run on the final tree into a scratch folder, not into the repository. It produced run_id
608bd0ea…, not the recorded 7449422852604780…. The code fingerprint is 3cb29f0e…, not f6ebe3b7….

The N5 fix is itself a change to code covered by the fingerprint, so no tree that contains the fix can reproduce
f6ebe3b7. Before any edit, the engineer recomputed the fingerprint with `evaluation/` left out and got exactly f6ebe3b7.
That shows the only drift was the evaluator.

The replayed decisions match r2 byte for byte once the embedded run ID is substituted. decision-index.csv is identical.
The manifest differs only in `codeVersion`. The committed r2 directory is unchanged.

Open human decision: either accept this r2 directory as evidence, or regenerate r2 on the final tree under a separate,
explicit work order.

### Gold set relabeled by the human

The human changed three labels from DIFFERENT to SAME:

- rq1-3118e91420c49d24 (work): the same paper with both an ACM and an IEEE DOI.
- rq1-f5ddae5a4d51c57b (work): the same paper with both an ACM and an IEEE DOI.
- rq1-752498b811b17e66 (researcher): a duplicate ORCID; the same real person.

The human reviewed rq1-3256858c310df7a5 (work) and kept it DIFFERENT: the award recipients differ.

The gold set is now 88 SAME and 4 DIFFERENT. Researchers are 68 SAME and 3 DIFFERENT; works are 20 SAME and 1 DIFFERENT.
gold-set-v1.csv sha256 is 3767a6ca3e43e03053878aa7a90f9516408cf4d13157a6d871ceab8b67493f73.

Only the evaluator was rerun, for both the main output and compare-r1-r2. No thresholds, policy or evaluator logic changed.
Only the 3 relabeled rows changed in the results. All 3 were CONFLICT under the exact-ID rule, which never merges, so they
are now false negatives.

Results against the r2 run with the fake adjudicator (0 pairs fell in the LLM band):

| Kind | Precision | Recall | F1 | Specificity | Unresolved | Conflict |
|---|---|---|---|---|---|---|
| Researcher | 59/59 | 59/68 (0.868) | 0.929 | 3/3 [0.439, 1.000] | 11/71 | 1/71 |
| Work | 18/18 | 18/20 (0.900) | 0.947 | 1/1 [0.207, 1.000] | 0/21 | 2/21 |

The r1 run, scored against the same labels, has researcher recall 67/68. Its work results are the same as r2.

**Limitation: only 4 negatives (3 researcher, 1 work).** Precision and specificity have almost no support. Work
specificity rests on a single pair, with a Wilson interval of [0.207, 1.000]. A precision of 1.0 here cannot tell a good
policy from a lenient one. The gold set has 92 pairs, below the 200-pair minimum, and is not a random sample. This is
recorded in KNOWN-LIMITATIONS.md and in the Part B addendum of agent-report.md.

Open policy question (human): should co-published dual DOIs and duplicate ORCIDs be resolved by a ratified rule? Today
they stay CONFLICT. Any such rule must not be derived from these labels.

### Third code review

VERDICT: APPROVE (docs/agent-runs/phase-05/review-report.md). Three new findings, all minor, all owned by
entity-resolution-engineer, with the human deciding whether to fix or defer them:

- R1: the leakage guard is avoided by splitting a string instead of allowlisting the exception explicitly.
- R2: no test checks that the evaluator uses `ofModule`.
- R3: CodeFingerprintSpec leaves temporary files behind.

### Gate

- entity-resolution-engineer: `sbt -batch check` EXIT=0, 2026-11-21 05:46-06:18. Results: core 483, entityResolution 212,
  adversarialTests 308 passed with 9 ignored, hadoop 75; 0 failed. markTestRun touched.
- Orchestrator's independent `sbt check`, first attempt: EXIT=1, an environment failure, not a test failure.
  - The first test pass was clean: 483/212/308 (9 ignored)/75.
  - Ubuntu's automatic updates upgraded openjdk-17 (17.0.21.0.3 to 17.0.21.1) at 06:40:28 while the run was going. In the
    second pass, inside markTestRun, every forked process then failed with "Failed to exec spawn helper".
  - A second attempt was cut off by a system reboot at 08:45.
- Orchestrator's independent `sbt check` after the reboot: <fill in>
