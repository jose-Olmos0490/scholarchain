# Entity resolution: known limitations (policy er-v1, revision er-v1-r2)

Owner: entity-resolution-engineer.

The human architect deferred the items below in Phase 05 repair-01: "Do NOT fix F14 or D6-D10;
record them as known limitations". Each one stays open. The 9 adversarial tests named below are
marked `ignore(...)` (repair-01 reversal; each cites its defect ID and this file), so `sbt check`
reports them as ignored, not failed. They stay ignored until a later work order brings the item
into scope. (Correction, re-review finding N1: an earlier version of this file said these tests
"still fail on purpose" and headed the column "Failing adversarial test(s)". That was stale: the
tests were changed to `ignore(...)` and are not red.)
Defect IDs come from `docs/agent-runs/phase-05/test-report.md`. F14 comes from
`docs/agent-runs/phase-05/review-report.md`.

| ID | Behaviour today | Ignored adversarial test(s) | Why deferred |
|---|---|---|---|
| F14 | Heuristic name decisions are recorded as certain. A same-work pair with incompatible names is REJECTED `SAME_WORK_DIFFERENT_AUTHOR` at confidence 1.0, although the incompatibility comes from a surname similarity cutoff, not from a fact. The same is true of the name gate's deterministic reject reasons. | Shown through D7 and D8 below | Human deferral. A fix means calibrated confidence for heuristic rejections, or routing them to UNRESOLVED. Both change the outcomes the gold set evaluates. |
| D6 | When an LLM ACCEPT cites only name evidence (`features.nameSimilarity`, `left.name`, `right.name`), it still merges a band pair. Nothing checks that the cited evidence goes beyond the name. | `ErCommonNameSpec`: "an LLM ACCEPT that cites only name evidence does not merge two 'Wei Zhang'" | Human deferral, and a judgement case per the test report. The prompt already says a name match is not identity. Enforcing this in policy needs a rule for cited-evidence sufficiency. |
| D7 | Family-name-first order without a comma (`Zhang Wei`, `ZHANG Wei`) is parsed as given name `zhang`, surname `wei`. On the same DOI and the same author slot as `Wei Zhang`, the pair is REJECTED `SAME_WORK_DIFFERENT_AUTHOR` at confidence 1.0. | `ErNameOrderSpec`: "same DOI and slot, 'Wei Zhang' vs family-first 'Zhang Wei': not REJECTED with certainty", and "... vs 'ZHANG Wei' (upper-case family name): not REJECTED" | Human deferral. The fix needs name-order detection, for example token-set comparison on the same slot or upper-case family-name cues. That changes parsing for every name. |
| D8 | `TextNormalize` does no Unicode confusable folding. A Cyrillic `С` or a Greek `Ο` inside a Latin name gives a different comparison key. This lowers coauthor overlap, and a same-DOI, same-slot pair is REJECTED at confidence 1.0. | `ErUnicodeConfusableSpec`: "a mixed-script surname (Cyrillic Es in 'Chen') folds to the same comparison key"; "a Greek Omicron in a surname folds to the same comparison key as Latin O"; "same DOI, same author slot, one homoglyph in the surname: not REJECTED with certainty"; "confusable-only coauthor spellings still count as coauthor overlap" | Human deferral. Confusable folding (a UTS #39 skeleton) must apply only to mixed-script tokens, so genuine Cyrillic and Greek names are not altered. That is a normalizer redesign. |
| D9 | `ExactIdRules.compare` treats a blank value (`""`) on both sides as agreement. `ViewBuilder` normalizes every identifier and never produces a blank, so this path is reachable only by other callers that build views by hand. | `ErEmptyFieldsSpec`: "a blank identifier value on both sides is never exact-ID agreement" | Human deferral. Low severity: not reachable from Phase 4 input. |
| D10 | `TextNormalize.parseName("0001")` returns surname `0001`. A name made only of a dblp homonym suffix is not reduced to the empty name. | `ErEmptyFieldsSpec`: "a name that is only a dblp homonym suffix ('0001') parses to the empty name" | Human deferral. Low severity: dblp never emits a suffix-only author name. |

## Other limitations recorded in this repair

- **Policy revision is not a provenance field (re-review N2; resolved by ratified decision).** The
  human ratified option (a) in repair-01: every decision records `POLICY_REVISION:er-v1-r2` in
  `rulesFired`, and its `provenance.runId` points at a run manifest that records the revision.
  No core field is added, so this is no longer blocked on domain-engineer. The limitation that
  remains: `core` `Provenance` (and `MatchDecision`) has no typed field for the revision. A reader
  must parse `rulesFired` or follow the run ID to the manifest, and `provenance.policyVersion`
  stays `er-v1`. A typed field would be a core contract change (owner domain-engineer) and needs
  a new human decision.
- **F3 costs recall.** Two sources sometimes list the same author at different positions (an
  author dropped or reordered). Those pairs are now UNRESOLVED rather than ACCEPTED: 8 such
  pairs in the bounded sample.
- **Policy version naming.** The decision logic changed under the configured policy version
  `er-v1`. The change is recorded as revision `er-v1-r2` and feature version
  `er-features-v2`. `MatchDecision.policyVersion` stays `er-v1`, as do the decision IDs derived
  from it, because the human kept the er-v1 thresholds and the adversarial suite configures
  `er-v1`. Run IDs, rules fired, the manifest and the cache key all carry the revision. Renaming
  to `er-v2` would need a configuration change, a change to the default in `tests/**` (owner:
  test-engineer), and a human decision.
- **Review queue.** `review-queue/review-queue-v1.csv` and its sealed key were generated with
  revision er-v1-r1. On human instruction they were not regenerated, and the pair set is
  unchanged. A PART B evaluation must say which revision's decisions it scores.

## Repair-01 follow-up (re-review findings N1-N6, gold relabeling)

The human ruled N1-N4 minor and not to be fixed in code. They are recorded here. N5 and N6 were
fixed in code (see POLICY-CHANGELOG.md and the end of this section).

- **N1, stale wording.** Corrected above: the D6-D10 tests are ignored, not failing.
- **N2, revision provenance.** Recorded above under "Policy revision is not a provenance field".
- **N3, built-in evaluator defaults.** `EvaluateGoldSet` has built-in defaults for the scored run
  directory (pinned to `local-run/er-v1/run_id=7449…`, the r2 run), the review key
  (`local-run/er-v1/review-queue-v1-key.csv`) and the output directory (`evaluation/er-v1`). Only
  the gold-set path is a required argument. After a later decision run (r3 or a new policy), the
  documented command still scores the stale r2 run, unless `--run-dir` is given, and writes into
  `evaluation/er-v1`. The scored run ID is printed in `summary.md` and `summary.json`; check it.
  A future fix: a versioned evaluation properties file passed as an argument, or refusing a
  default run that is not the newest.
- **N4, evaluation outputs are not write-once.** Unlike run outputs (F9), re-running the evaluator
  overwrites `gold-eval-results.csv`, `summary.json` and `summary.md` in the output directory
  in place, with no existence check. A run with another gold set or another run replaces committed
  evidence silently. Only git history keeps the old evaluation ID. This happened deliberately in
  the repair-01 follow-up: the relabeled gold set replaced the `evaluation/er-v1` and
  `evaluation/er-v1/compare-r1-r2` outputs.
- **N5, code fingerprint scope (fixed; r2 is still not byte-reproducible).** The run fingerprint
  now leaves out the evaluator package `scholarchain/er/evaluation/`, so later evaluator changes do
  not change decision run IDs. The fix itself changed decision-run code (`run/CodeFingerprint`),
  so the documented r2 command on this tree gives a new fingerprint and a new run ID. It does not
  reproduce `run_id=7449…`. A replay into a scratch root produced decision content that is byte-identical
  apart from the embedded run ID. The committed r2 directory was neither regenerated nor modified.
  Before the fix, the compiled module without the evaluator fingerprinted to exactly the r2 value
  (`sha256:f6ebe3b7…`).
- **N6, weak F11 tests.** Removed from `MatchPolicySpec`. The stronger replacements stay:
  `PolicyRevisionR2Spec` (overlapping, subset and multi-type ID sets under four threshold
  settings) and `ResolveSampleSpec` (warm vs cold cache, byte for byte).

### Gold set: only 4 negatives

After the human relabeling in the repair-01 follow-up, the gold set
(`docs/agent-runs/phase-05/gold-set-v1.csv`, sha256 `3767a6ca…3f73`) has 92 pairs: 88 SAME and
4 DIFFERENT (researcher 68 SAME / 3 DIFFERENT, work 20 SAME / 1 DIFFERENT), 0 UNSURE.

- Precision and specificity rest on 3 researcher negatives and 1 work negative. They are very
  weakly supported. Work specificity is 1/1 with a Wilson 95% interval of [0.207, 1.000], and
  researcher specificity is 3/3, [0.439, 1.000]. One false positive among so few negatives would
  not show at all, and the counts cannot separate a good policy from a lenient one. Read the
  counts, not the rates.
- The set is below the 200-pair minimum and comes from the r1 review queue strata, not a random
  sample (accepted shortfall, repair-01).
- Relabeled pairs that the policy marks CONFLICT (two dual ACM/IEEE DOI work pairs, one
  duplicate-ORCID researcher pair) are now false negatives. By design, an exact-identifier
  conflict is never merged. Recovering them would need a ratified policy rule, for example for
  co-published dual DOIs. Tuning on these labels is not allowed.
