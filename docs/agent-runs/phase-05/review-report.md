STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 05 final re-review (third review) after the repair-01 follow-up. Read-only. Scope: modules/entity-resolution/** (code, tests, config, KNOWN-LIMITATIONS.md, POLICY-CHANGELOG.md, review-queue/, local-run/er-v1/ r1 and r2, evaluation/er-v1/**), tests/src/test/scala/scholarchain/adversarial/er/**, and the build.sbt `adversarialTests` edge. Earlier-phase core, hadoop and build changes were used only as context.
VERDICT: APPROVE

## Bottom line

I'm approving. Every human decision in the work order is implemented as stated, the numbers are internally consistent, and no threshold or policy value changed. I found 3 new findings, all minor, and none blocks the phase:
- R1: the leakage guard is avoided with string splitting instead of an explicit allowlist.
- R2: no test pins `ofModule` in the evaluator.
- R3: the new spec leaves temp files behind.

## Checks of the ratified decisions

**N5 (fingerprint scope; r2 not regenerated): implemented and documented honestly.**
- CodeFingerprint.scala:30-37 excludes `scholarchain/er/evaluation/` from `of` and keeps everything in `ofModule`. `included` uses startsWith on the exact prefix plus a slash, so `evaluationx/` stays included (CodeFingerprintSpec.scala:85).
- The r2 run-manifest.json:2 still records `@sha256:f6ebe3b7…`.
- Only two run directories exist under local-run/er-v1/ (r1 9241e65e… and r2 7449422…). The replay went to a scratch root, outside the repository.
- The non-reproducibility is stated plainly in three places: KNOWN-LIMITATIONS.md:69-76, POLICY-CHANGELOG.md:66-70, and agent-report.md:312-334 and :460.
- I could not observe file mtimes. "Untouched" rests on the unchanged manifest content plus the engineer's statement.

**N6 (weak F11 tests removed): done.**
- Neither old test name appears in MatchPolicySpec.
- The stronger replacements exist:
  - PolicyRevisionR2Spec.scala:117-144 uses 5 conflict shapes (disjoint, overlapping, subset, multi-valued OPENALEX_AUTHOR, and multi-type) across every threshold setting, with the accepting adjudicator called 0 times.
  - ResolveSampleSpec.scala:251 is the warm-cache versus cold-cache byte-for-byte test.
- The count arithmetic agrees: 206 − 2 + 8 = 212.

**N1-N4 (recorded, not fixed in code): the records are accurate.**
- N1: KNOWN-LIMITATIONS.md:6-11 and the column header at :15 now say "ignored" and include a correction note.
- N2: KNOWN-LIMITATIONS.md:26-33 and POLICY-CHANGELOG.md:50-54 both say this was resolved by option (a) and name the remaining limitation, which is that there is no typed field.
- N3: KNOWN-LIMITATIONS.md:55-62 matches EvaluateGoldSet.scala:30-33 and :91-93.
- N4: KNOWN-LIMITATIONS.md:63-68 matches EvaluateGoldSet.scala:134-146, and it honestly says the follow-up itself overwrote the outputs.

**N7: open on the human side.** repair-01.md:35 still says only "the human confirms authorship". The test-engineer's confirmation from its session transcripts is not yet recorded. See MANUAL ACTION REQUIRED.

**Gold relabeling: verified.**
- Gold CSV: 88 rows with `,SAME,`. The rows marked "Relabeled" are exactly lines 19, 50 and 90 (rq1-3118e914…, rq1-752498b8…, rq1-f5ddae5a…). rq1-3256858c… is still DIFFERENT ("Different DOI").
- The main summary.md:9 and compare-r1-r2/summary.md:9 both cite sha256 3767a6ca…3f73, with 88 SAME, 4 DIFFERENT and 0 UNSURE.
- Results CSV, counted by grep:

| Kind | TP | FN | TN |
|---|---|---|---|
| Researcher | 59 | 9 | 3 |
| Work | 18 | 2 | 1 |

- The 4 DIFFERENT rows (lines 15, 21, 24, 43) are all TN. rq1-3118e914… and rq1-f5ddae5a… are work, CONFLICT and FN. rq1-752498b8… is researcher, CONFLICT and FN.
- **Only the 3 relabeled rows changed class.** The previous evaluation had researcher TP 59 / FN 8 / TN 4 and work TP 18 / FN 0 / TN 3. The new deltas are exactly +1 researcher FN and +2 work FN, taken from TN. The remaining DIFFERENT set (3 researcher UNRESOLVED on the PROBABILISTIC path, plus 1 work REJECTED) is the old negative set minus its three CONFLICT rows. Decision columns still carry run:7449… and er-v1-r2.
- summary.md:40-70, summary.json:81-84 and :168-171, and the path table at summary.md:76-83 all agree with the CSV. In the path table, 10+41+8 = 59 TP and 1+8 = 9 FN.
- compare-r1-r2/summary.md:219 shows r1 researcher at 67 / 0 / 1 / 3, which matches the addendum.
- **The 4-negative limitation is stated** in agent-report.md:419, KNOWN-LIMITATIONS.md:81-97 and summary.md:29-32.
- **No tuning:**
  - er-v1-thresholds.properties:35-41 is unchanged: 0.80, 0.45, 0.60, 0.80, 0.85, 0.5, 0.7.
  - The sensitivity table is still report-only, and the configured row reproduces the run.
  - The changelog explicitly declines a dual-DOI rule without ratification (POLICY-CHANGELOG.md:58-59, KNOWN-LIMITATIONS.md:94-97).

**Earlier decisions still stand.**
- There are exactly 9 `ignore(` calls under tests/**, at the same sites as before: ErCommonName:115, ErEmptyFields:29 and :72, ErNameOrder:122 and :132, ErUnicodeConfusable:138, :144, :150 and :171.
- The F7 cutoffs are still only in the properties file.
- Option (a) provenance is intact: POLICY-CHANGELOG.md:45-54.
- ErEmptyFieldsSpec:232-235 is still deferred to test-engineer.
- build.sbt:205 is `.dependsOn(core, hadoop, entityResolution)`, and :143 entityResolution depends only on core, so the module graph stays acyclic.

## The four focus questions

**1. Leakage guard and CodeFingerprint.** This does not weaken the protection the guard exists for. No decision code can reach the gold set or the evaluator:
- CodeFingerprint reads only class and resource bytes under `scholarchain/er/` from its own classpath entry. It never opens a gold-set path, and it never loads or calls an evaluator class.
- `ofModule`, which does hash evaluator bytes, has exactly one caller: EvaluateGoldSet.scala:119. Hashing bytes leaks no labels.
- A grep of main sources finds no `Class.forName` or `loadClass`. The two `getResourceAsStream` calls (Adjudication.scala:233, PolicyDefinition.scala:224) load the prompt and the policy.
- The bytecode check (GoldSetLeakageSpec.scala:89-104) still catches any static link. A JVM reference to an evaluator class or member puts the internal name `scholarchain/er/evaluation/...` into the constant pool, and that is the needle.
- `Prefix` is a plain `val`, not a `final val`, so the concatenation is not constant-folded. Folding would have put the needle into the bytecode, and the check would have failed.

The technique does have a cost, and I record it as R1. A string split specifically to avoid a guard's needle now sits inside a guarded package. Reflection by assembled string has always been the guard's blind spot, and this shows the way around it. Allowlisting the one approved exception would be more honest than concatenating around the check. The engineer disclosed the technique (agent-report.md:300 and :464), and CodeFingerprintSpec.scala:83 pins the exact excluded value, so this is minor.

**2. CodeFingerprintSpec (8 tests).**
- The 2×2 loop tests (lines 48-65) fail on the old code, which hashed evaluator bytes: the "changing only evaluator classes" assertions would differ.
- The tests at lines 67-88 do not compile without `included`, `inModule`, `ofClasspathEntry` and `ofModule`.
- The real-module test (lines 90-113) checks that the compiled module minus the evaluator gives the same fingerprint as `of`. It asserts that the evaluator directory exists, so it is not vacuous.
- `ofModule` is correct, `locate(anchor, inModule)` at CodeFingerprint.scala:95, and EvaluateGoldSet.scala:119 uses it.
- No test pins that wiring, however. See R2.

**3. EvaluateGoldSet.scala change.** The file is untracked, so I cannot diff it. The evidence is consistent with a single in-place line change at :119:
- The line references from my previous review are unchanged: defaults at :30-33, arguments at :91-93, write at :134-146, fake-path label at :425-427, fake summary at :571-578.
- The metric and counting rules in summary.md:18-27 are unchanged.
- The deltas are fully explained by the 3 relabels.
- Metrics.scala and Evaluation.scala were not inspected byte for byte. Their outputs agree with an independent count of the CSV.

**4. Regressions in F1-F13 and D1-D5.**
- No tests/** file changed in this follow-up. The ignore sites are the same, and the adversarial count is still 308 passed and 9 ignored.
- The two removed MatchPolicySpec tests were not D-defect or F-fix carriers. Their claims are covered more strongly in PolicyRevisionR2Spec and ResolveSampleSpec.
- No decision code changed apart from CodeFingerprint (run-ID input only) and a doc comment in ResolveSample.scala:40-46. The replay's decision content is byte-identical modulo run ID, per the engineer's report.

FINDINGS:
| # | Severity | File:line | Concrete failure input | Expected impact | Owning agent |
|---|---|---|---|---|---|
| R1 | minor | modules/entity-resolution/src/main/scala/scholarchain/er/run/CodeFingerprint.scala:30 (`Prefix + "evaluation/"`); modules/entity-resolution/src/test/scala/scholarchain/er/GoldSetLeakageSpec.scala:57-67, 96 | A future change in a matching package does `Class.forName("scholarchain.er." + "evaluation.GoldSet")`, or reads a path built the same way. Neither the source-token check nor the bytecode needle fires. The repository now holds a working example of this split-string pattern inside a guarded package. | Today nothing reaches the gold set or the evaluator, and static linking is still detected. But the guard now relies on code avoiding its tokens, not on an explicit allowance. Recommendation: allowlist the single approved exception, CodeFingerprint's exclusion constant, in GoldSetLeakageSpec. Also forbid `Class.forName`, `loadClass` and assembled resource paths in matching packages. | entity-resolution-engineer |
| R2 | minor | modules/entity-resolution/src/main/scala/scholarchain/er/evaluation/EvaluateGoldSet.scala:119; no matching assertion in EvaluateGoldSetSpec.scala (a grep finds no `Fingerprint`/`codeFingerprint` there) | Revert :119 from `CodeFingerprint.ofModule` to `CodeFingerprint.of`. All 212 entityResolution tests still pass: CodeFingerprintSpec tests the functions, not the evaluator's use of them. | An evaluator-only change would silently stop changing the evaluation ID. That is the very regression the addendum says `ofModule` prevents (agent-report.md:299). Recommendation: assert that `Report.codeFingerprint` from `EvaluateGoldSet.run` equals `CodeFingerprint.ofModule(...)` and differs from `CodeFingerprint.of(...)`. | entity-resolution-engineer |
| R3 | minor | modules/entity-resolution/src/test/scala/scholarchain/er/CodeFingerprintSpec.scala:21, 30, 98 | Every `sbt check` creates about 20 temp directories and jars (`er-fingerprint-*`), plus a full copy of the module's class tree, and never deletes them. | Disk litter on developer machines and CI. No correctness impact. | entity-resolution-engineer |

Ownership: I found no violations. Phase 5 work stays within modules/entity-resolution/** and tests/**. The build edge belongs to build-engineer. project/Dependencies.scala is not touched. No core field was added. The gold CSV is human-owned, and its relabel notes are the human's.

TEST RESULTS: not run by reviewer. Orchestrator-pasted evidence:
- `sbt -batch check` EXIT=0, 2026-11-21 05:46-06:18.
- core 483, entityResolution 212, adversarialTests 308 passed with 9 ignored, hadoop 75, 0 failed.
- markTestRun touched the marker.
- The orchestrator's independent `sbt check` is pending, and this verdict assumes it passes.

VALIDATION RESULTS: findings verified against the files:
- R1 yes (CodeFingerprint.scala:27-30; GoldSetLeakageSpec.scala:57-67, 89-104; a grep found no forName or loadClass in main).
- R2 yes (EvaluateGoldSet.scala:119; a grep of src/test shows fingerprint references only in CodeFingerprintSpec and ResolveSampleSpec).
- R3 yes (CodeFingerprintSpec.scala:21, 30, 98, with no deletion anywhere in the file).

ASSUMPTIONS:
- The modules are untracked, so I could not diff the previous evaluation outputs or EvaluateGoldSet.scala. "Only 3 rows changed" and "one-line change" are inferred from my previous review's recorded counts and line numbers, against the current files.
- "r2 untouched" rests on its manifest content and the absence of any other run directory. I cannot observe mtimes.
- I read the gold CSV only for label counts and relabel markers.

RISKS:
- Only 4 negatives remain (3 researcher, 1 work). Precision 1.0 and specificity carry almost no evidential weight. This is stated in the documents.
- The committed r2 run cannot be reproduced byte for byte from any tree that contains the N5 fix. The decision content is identical. The human owns this decision.
- The LLM path has never been evaluated on real data. The Bedrock adapter cannot be enabled.
- F14 and D6-D10 remain open (9 ignored tests). N3 and N4 remain.
- The 3 relabeled CONFLICT false negatives (dual DOIs, duplicate ORCID) put pressure on someone to "fix" exact-ID conflict handling. Any such rule needs a ratified decision, not tuning on these labels.

MANUAL ACTION REQUIRED:
1. Human: update docs/agent-runs/phase-05/repair-01.md (human-owned), which is stale relative to this follow-up:
   - Record N7: test-engineer confirmed from its session transcripts that it made the tests/** edits (currently :35).
   - Update the gold counts at :67-68 (85/7 to 88/4, plus the new sha256).
   - Update the PART B result table at :86-93 (researcher recall 59/68, work 18/20).
   - Replace "Labels to recheck" at :101-102 with the relabel outcome.
   - Record the N5 non-reproducibility decision.
2. Human: confirm the orchestrator's independent `sbt check` result before the human gate.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE:
1. Optionally route R1-R3 to entity-resolution-engineer. All are minor and may be deferred.
2. Confirm the independent `sbt check`.
3. The human updates repair-01.md.
4. Human gate for Phase 05.
