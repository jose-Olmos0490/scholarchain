STATUS: BLOCKED-ON
OWNER: test-engineer
WORK ORDER: Phase 05, adversarial tests for the entity-resolution module (human request, this session)

Summary: I wrote 203 adversarial tests under tests/ and changed no production, build or module test code. They can't run under `sbt check` yet. The `adversarialTests` project in build.sbt has `.dependsOn(core, hadoop)` and not `entityResolution`, so `sbt check` stops at `Test/compile` with 56 errors ("value er is not a member of scholarchain"). No tests ran in that `sbt check` and `markTestRun` did not run. To get real results anyway, I used a temporary sbt `set` command that lasts only for the one sbt run and writes nothing to disk. With it the new tests run: 185 pass and 18 fail. The 18 failures are 10 production defects, all owned by entity-resolution-engineer. I left every failing test in place, with nothing skipped or weakened. The two failures that came from my own test mistakes are fixed: a wrong Greek Omicron input, and a loop that stopped at its first failing input.

FILES CREATED (all under /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/er/):
- ErAdv.scala: helpers. Policy thresholds come through the core ConfigLoader from a test-local env map (accept 0.80, reject 0.45, band [0.60, 0.80), plus an "everything in the band" policy). Also a scripted adjudicator and fixtures.
- ErUnicodeConfusableSpec.scala (26 tests)
- ErNameOrderSpec.scala (14)
- ErCommonNameSpec.scala (10)
- ErConflictingOrcidSpec.scala (15)
- ErMalformedLlmJsonSpec.scala (88)
- ErFabricatedIdSpec.scala (25)
- ErEmptyFieldsSpec.scala (25)

FILES MODIFIED: none outside the new files. Scalafmt reformatted only my own files.

COMMANDS RUN:
1. `sbt -batch "adversarialTests/Test/scalafmt" "adversarialTests/Test/compile"`: formatting succeeded; compile failed with "value er is not a member of scholarchain". This is the evidence for the block.
2. `sbt -batch 'set adversarialTests / Test / internalDependencyClasspath ++= (entityResolution / Compile / fullClasspath).value' "adversarialTests/testOnly scholarchain.adversarial.er.*"`, run twice. The second run, after fixing my two test mistakes: succeeded 185, failed 18.
3. `sbt -batch check` on the build as it stands: EXIT=1. `scalafmtCheckAll` [success], `scalafmtSbtCheck` [success], then "[error] 56 errors found", "(adversarialTests / Test / compileIncremental) Compilation failed". No tests ran and `markTestRun` did not run. I did not touch .claude/.last-test-run. The guard blocked one `ls` of the marker path; I did not try again.
4. `sbt -batch '<same set command>' test` over all aggregated projects: EXIT=1 after 1124 s.

TEST RESULTS:
- `sbt check` (the gate, no session wiring): did not reach tests. passed=0 failed=0 skipped=0 errored=56 (compile errors).
- Full `test` with the session-only wiring:

| Project | Suites | Passed | Failed |
|---|---|---|---|
| core | 12 | 483 | 0 |
| entityResolution (module's own tests) | 7 | 142 | 0 |
| third project | 7 | 75 | 0 |
| adversarialTests | 18 | 299 | 18 |

  - adversarialTests = the 114 existing hadoop adversarial tests, all passing, plus my 203 ER tests (185 passed, 18 failed). Canceled 0, ignored 0, pending 0 everywhere.
  - The third project is presumably hadoop: the count matches PART A's order 483/142/75/114, but the log lines I extracted don't name it.
- New ER suites (passed / failed):

| Suite | Passed | Failed |
|---|---|---|
| ErUnicodeConfusableSpec | 22 | 4 |
| ErNameOrderSpec | 12 | 2 |
| ErCommonNameSpec | 5 | 5 |
| ErConflictingOrcidSpec | 14 | 1 |
| ErMalformedLlmJsonSpec | 85 | 3 |
| ErFabricatedIdSpec | 25 | 0 |
| ErEmptyFieldsSpec | 22 | 3 |

VALIDATION RESULTS: Every policy-level failure prints the decision, path, score, rules fired and reason codes (the `explain` helper), so each defect below is backed by what I saw in the output. Baseline tests show the rejections are real: a well-formed output parses, and the band pair goes to the LLM (calls=1) and is ACCEPTED on a valid output.

DEFECTS FOUND (owner for all: entity-resolution-engineer):

| ID | Test (suite) | Input | Expected | Observed |
|---|---|---|---|---|
| D1 (high) | "two authors named 'Wei Zhang' on the SAME paper (positions 1 and 3) are never ACCEPTED"; "same DOI in two sources, 'Wei Zhang' at slots 1 and 3: not ACCEPTED" (ErCommonNameSpec) | Authors [Wei Zhang, Li Wang, Wei Zhang, Yang Liu]: mentions at positions 1 and 3, same work, or the same DOI in dblp and Crossref | Not ACCEPTED: two slots of one author list are two people | ACCEPTED, PROBABILISTIC, score 0.9075. The policy uses sameWorkContext only to accept the same slot; each mention's coauthor list contains the other mention, so coauthorOverlap is inflated |
| D2 (high) | "distinct coauthors whose surname+initial keys collide are not full coauthor overlap"; "two different 'Wei Zhang' with key-colliding coauthors and a common affiliation: not ACCEPTED" (ErCommonNameSpec) | Coauthors [Li Wang, Yang Liu] vs [Lei Wang, Yu Liu], both with affiliation "Tsinghua University"; different titles, years (2012/2018) and venues | Overlap below 1.0; not ACCEPTED | coauthorOverlap = Some(1.0); decision ACCEPTED, PROBABILISTIC, score 0.8210. The `<surname> <initial>` key collapses common Chinese names |
| D3 (high) | "strict parser rejects duplicate keys (REJECT then ACCEPT for 'outcome')"; "... duplicate 'confidence' keys"; "policy: invalid LLM output (duplicate outcome key (REJECT, ACCEPT)) ..." (ErMalformedLlmJsonSpec) | `{"outcome":"REJECT",...,"outcome":"ACCEPT"}` | Rejected by the strict parser; pair stays UNRESOLVED | Accepted (ujson keeps the last value); the pair is ACCEPTED via LLM_ADJUDICATED with confidence 0.95. The fix belongs in AdjudicationSchema.parse |
| D4 (med-high) | "one mention carrying ORCIDs {A, C} vs one carrying {B, C}: not ACCEPTED" (ErConflictingOrcidSpec) | Same DOI and slot; ORCID sets {0000-0002-1825-0097, 0000-0002-1694-233X} vs {0000-0001-5109-3700, 0000-0002-1694-233X} | CONFLICT, or at least not ACCEPTED | ACCEPTED, EXACT_ID, confidence 1.0. ExactIdRules.compare treats any shared value as agreement and ignores the A/B disagreement. ViewBuilder can give one mention two ORCIDs (authorship `orcid` plus node `orcidRaw`) |
| D5 (high) | "two different editorials (same generic title, editor, journal, year; no DOI): not ACCEPTED" (ErEmptyFieldsSpec) | Two dblp works "Editorial." and "Editorial", 2018, author Christian S. Jensen, venue "ACM Trans. Database Syst.", no DOI | Not ACCEPTED | ACCEPTED, DETERMINISTIC_BIBLIOGRAPHIC (EXACT_BIBLIOGRAPHIC_MATCH), confidence 1.0 |
| D6 (medium) | "an LLM ACCEPT that cites only name evidence does not merge two 'Wei Zhang'" (ErCommonNameSpec) | Name-only pair (score 0.6401, in the band); the LLM returns ACCEPT 0.99 citing only features.nameSimilarity, left.name and right.name | Not ACCEPTED ("a name match is not identity") | ACCEPTED, LLM_ADJUDICATED, confidence 0.99. Nothing checks whether the cited evidence goes beyond the name |
| D7 (medium) | "same DOI and slot, 'Wei Zhang' vs family-first 'Zhang Wei': not REJECTED with certainty"; "... vs 'ZHANG Wei' ..." (ErNameOrderSpec) | Same DOI, same slot, names written family-first without a comma | Not REJECTED | REJECTED, SAME_WORK_DIFFERENT_AUTHOR, confidence 1.0 (score 0.5825) |
| D8 (medium) | "a mixed-script surname (Cyrillic Es in 'Chen') folds to the same comparison key"; "a Greek Omicron in a surname folds ..."; "same DOI, same author slot, one homoglyph in the surname: not REJECTED with certainty"; "confusable-only coauthor spellings still count as coauthor overlap" (ErUnicodeConfusableSpec) | "Li Сhen" vs "Li Chen"; "Jan Οlsen" vs "Jan Olsen" | Same key; not REJECTED | Keys differ ("сhen l" vs "chen l"); coauthor overlap 0.333 instead of 1.0; same-DOI, same-slot pair REJECTED at confidence 1.0 (score 0.9075). TextNormalize does no confusable folding |
| D9 (low) | "a blank identifier value on both sides is never exact-ID agreement" (ErEmptyFieldsSpec) | WorkView ids Map(DOI -> Set("")) on both sides | No EXACT_ID_AGREE:DOI | EXACT_ID_AGREE:DOI fired, then a spurious CONFLICT (TITLE_INCOMPATIBLE). ViewBuilder normalizes IDs, so its path never produces "". This is a gap for any other caller of compare |
| D10 (low) | "a name that is only a dblp homonym suffix ('0001') parses to the empty name" (ErEmptyFieldsSpec) | parseName("0001") | Empty key | Surname "0001" |

COVERAGE BY REQUIREMENT:
- Unicode and confusables: NFC/NFD, diacritics, full-width, nine invisible or format characters, NBSP/NNBSP, stacked combining marks, DOI zero-width/BOM, homoglyph DOI/ORCID/dblp key never agreeing, Cyrillic and Greek name homoglyphs (D8).
- Reordered and initial-only names: comma order, dotted initials, particles, suffixes, W. vs Wei vs Wen transitivity, homonym keys 0001 vs 0002, family-first order (D7).
- Common names: name-only pairs, same venue and year, key collision (D2), same-paper slots (D1), LLM name-only accept (D6), determinism.
- Same DOI with conflicting ORCIDs: DOI and ORCID spelling normalization, bad checksum, CONFLICT under four threshold configurations, LLM never called, different slots, multi-ORCID sets (D4), OpenAlex disagreement, Phase 4 CONFLICT outcome, arXiv conflict, audit fields.
- Malformed LLM JSON: 68 parser inputs (truncated, trailing or leading text, fences, wrong types, NaN/Infinity/1e400/out of range, wrong-case or homoglyph outcomes, extra/missing/wrong-case keys, BOM, comments), duplicate keys (D3), 200k-deep nesting without throwing, null input; plus 11 end-to-end cases ending UNRESOLVED with confidence 0, transport errors not cached, cached invalid outputs reused, raw model text kept out of decisions, low confidence.
- Fabricated IDs: wholly or partly fabricated, on every outcome, 11 near-miss variants, IDs from another real pair, ORCID/DOI/source key offered as candidate IDs, empty ID, invented cited field; end to end the result is UNRESOLVED with LLM_FABRICATED_ID, the fake never appears in the decision or provenance, refs are unchanged, and the request offers exactly the three allowed IDs. All pass.
- Empty and missing fields: blank, null and placeholder names, empty coauthors/affiliations/venues/titles/references, blank and null-like IDs in every normalizer, decisions made without errors, mismatched view types return Left, configuration omissions (blank, NaN, out of range, inverted thresholds, missing policy version). D5, D9, D10.

ASSUMPTIONS:
- Some expectations are policy judgments I chose, not literal spec text: D6 (name-only LLM accept), D7, D8, D9, D10. D1 to D5 follow directly from the spec's "a name match is not identity" and "conflicts never merge" requirements and the human's over-merge request. The owner and reviewer may contest the judgment cases.
- The temporary sbt `set` stands in for the missing dependsOn edge and changes no file. The real wiring must come from build-engineer.
- No gold-set CSV/XLSX, review-queue key or evaluation file was read.

RISKS:
- Once build-engineer wires the dependency, `sbt check` will fail on the 18 intentional failures until entity-resolution-engineer fixes D1 to D10 or the human rules a case out of scope.
- An observation with no test: rulesFired records LLM_CALLED vs LLM_CACHE_HIT, so a decision written with a warm cache differs from one written with a cold cache.
- I did not exercise the Bedrock adapter, JSONL cache file corruption, or ViewBuilder/Phase4Input with malformed Phase 4 files.

MANUAL ACTION REQUIRED:
1. Route to build-engineer: add `entityResolution` to `adversarialTests` `.dependsOn(...)` in build.sbt.
2. Route D1 to D10 to entity-resolution-engineer.
3. The human decides whether D6 to D10 are in scope.

BLOCKED-ON OWNER: build-engineer (build.sbt wiring); entity-resolution-engineer (D1 to D10)
BLOCKED-ON EVIDENCE: build.sbt has `lazy val adversarialTests = (project in file("tests")).dependsOn(core, hadoop)`. `sbt -batch check` reports "value er is not a member of scholarchain" (18 occurrences), "[error] 56 errors found", "(adversarialTests / Test / compileIncremental) Compilation failed", EXIT=1. Logs are in /tmp/claude-1000/-home-olmos-scholarchain/2e4c9e5c-1704-49d0-8777-33b6f9b1e0fe/scratchpad/ (check.log, er-run2.log, fulltest.log).
NEXT GATE: build-engineer wires the dependency, entity-resolution-engineer repairs D1 to D10, then I rerun the tests and `sbt check`.
