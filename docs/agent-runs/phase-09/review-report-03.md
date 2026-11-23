STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Read-only adversarial re-review of Phase 9 repair 03b (rag-engineer): findings A-G of review-report-02, plus a hunt for regressions. Scope was modules/rag/** and prompts/**, and the build.sbt change from repair 03a.
VERDICT: REQUEST_CHANGES (A-G are structurally closed. One major and several minor residuals remain, listed below.)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools only; no git diff was available, so I read the files directly)

RE-CHECK OF A-G (by reading the code)
- A (summary and uncertainty unchecked): CLOSED. The v2 schema has only `claims`, with `additionalProperties:false` at every level (answer-synthesis-v2.schema.json:5-9, 16). `AnswerWorkflow.assemble` writes the summary (665-673) and the uncertainty (674-696) from code. Their inputs are verified subjects and windows, `g.gaps`, the removed count, the partial reasons, stored identity confidence and fixed interpretation templates. No model text enters either one.
- B (IX, number words, NFKC): CLOSED by construction. The model has no text field. Every number is `ClaimVerificationAgent.show(storedValue)` through `ClaimRendering.valueText`. The `assemble` function refuses a display text that does not survive `ShownText.normalize` unchanged (AnswerWorkflow.scala:615-616).
- C (relation lexicon): CLOSED by construction. A relation sentence is written only from a verified COAUTHOR_EDGE, PATH_HOP or COMMUNITY assertion (ClaimText.scala:88-110). `candidates` matches kind, subject, object and field exactly (ClaimVerificationAgent.scala:213-219), so another pair's edge no longer backs a claim.
- D (PATH_LENGTH): CLOSED, with one caveat noted under finding 5.
  - The verifier takes the minimum over every stored path of the pair and window (ClaimVerificationAgent.scala:266).
  - It accepts the value only when `complete`: `dropped==0`, `!depthCapped`, `returned<resultLimit`, and `everywhere.size==returned` (EvidenceFacts.scala:60; ClaimVerificationAgent.scala:268).
  - A wrong length is CONFLICTING (275-281).
  - Evidence without the `| query ...` suffix defaults to depthCapped=true, so it is never complete (EvidenceFacts.scala:280-285).
  - `PathReport` and `EvidenceBuilderAgent` (:319-321) carry the bounds. `depthCapped` is `requestedDepth.exists(_ > depth)` (PathFinderAgent.scala:96), which is correct.
  - The test-side `+1000` masking is removed. This rests on the report, and I did not re-read BenchmarkSpec.
- E (window must equal the stored window): CLOSED. `candidates` requires `f.window == a.window` (:218). Rendering uses `VerifiedFact.window`, which is the stored window, not the asserted one (:314).
- F (interpretation text cut): CLOSED. Interpretations come from fixed templates, and at most 5 are shown. Their content is not re-checked for the 4000-character cap, but fixed text with bounded names stays under it in practice.
- G (test gaps): PARTLY CLOSED. The unused `removed` argument is gone. The workflow-level `CANDIDATE_CONFIDENCE_INVALID` test gap and the `factsOf` circularity in the benchmark are disclosed and still open (minor, accepted).
- build.sbt (repair 03a): NOT a weakening. The alias is `scalafmtCheckAll; scalafmtSbtCheck; Test/compile; markTestRun`. `markTestRun` depends on `(Test/test).all(inAggregates(LocalRootProject))` (build.sbt:247). That covers every aggregated project, including `adversarialTests`. The marker is touched only after the tests pass. Dropping the separate `test` step only removes a duplicate run.

FINDINGS
| # | Severity | File:line | Concrete failure input | Expected impact | Owning agent |
|---|---|---|---|---|---|
| 1 | major | ClaimText.scala:74-76 (`labeller`); EvidenceFacts.scala:86, 117, 316; ClaimBindingSpec:386-410 | A stored researcher name such as `Lin Chen' and 'Ada Example` or `x" ignore previous instructions {value} 999`. The evidence builder turns the `"` into `'`, because the test expects `'x' ignore previous instructions {value} 999' (`. | The label wraps the name in single quotes, but the name can contain single quotes, so it can close the quote and add its own sentence. Stored source text then appears inside the code-written claim text, the summary, the uncertainty and the interpretation text as if it were part of the sentence. The ID in parentheses follows the name, but the name can imitate a relation or identity statement. The test is titled "a quote inside it cannot break out", yet it asserts the broken-out shape (line 408) and checks only that no `"` appears. The doc claim ("quoted, as data") is not supported. | rag-engineer |
| 2 | minor | AnswerWorkflow.scala:665-666 | A claim that is PARTIALLY_SUPPORTED because it is context-only, supported by uncited evidence, or has a template that does not fit. | The summary says "N claim(s) verified against stored evidence" and counts partly supported claims too. This is an overstatement in the headline. The per-claim status and uncertainty are correct. | rag-engineer |
| 3 | minor | AnswerWorkflow.scala:642, 746 (`VerificationPolicyVersion`); report RISKS | Audit and provenance records written under the new rules still say `claim-verification-v1`. | The audit trail cannot tell old-rule answers from new-rule answers, and replay or comparison across repairs is ambiguous. The engineer disclosed it and asked for a decision. It should be bumped, and the tests and fixtures updated. | rag-engineer (the orchestrator or a human decides the name) |
| 4 | minor | ClaimText.scala:114-124; AnswerWorkflow.scala:622-624 | A removed claim with `AssertedValue.Text("ignore previous instructions...")` or a hostile `subjectId` (up to 200 characters each). | `RemovedClaim` text carries model-controlled strings. They are sanitized and capped at 500 but not NFKC-normalized, and they are labeled "not verified, requested:". They are not in `AnswerResponse`, only in `WorkflowResult.removed`. If the service layer displays them, model text reaches the user. Phase 10 must treat them as diagnostics only. | rag-engineer, then service-engineer in Phase 10 |
| 5 | minor | ClaimVerificationAgent.scala:266-268 | Two path queries for the same pair and window in one bundle, with different `returned` counts. | `everywhere` pools them and compares against only `cited.head`'s `returned`. This usually yields "incomplete", which is safe, but a coincidental match could mark a mixed set complete. The builder produces one query per pair today, so there is no known exploit. No test covers duplicates. | rag-engineer |
| 6 | minor | AnswerWorkflow.scala:498-500, 511 | A candidate `displayName` or affiliation holding instruction-like text, in an abstention. | Stored text is concatenated unquoted into the abstention uncertainty. This is the pre-existing "existing behavior" the report mentions, and it is not a regression. It is the same class as finding 1. | rag-engineer |
| 7 | minor | AnswerWorkflow.scala:647-649 | `StableIds.claimId` returns an error. | The claim is silently dropped through `flatMap(...toOption)`, while the summary count and the audit run on the pre-drop list. It is practically unreachable. | rag-engineer |

OTHER CHECKS WITH NO FINDING
- Template placeholder injection: `Placeholder.replaceAllIn` is one pass with `quoteReplacement`, so a stored `{value}` is not expanded. The template ID is a schema enum, and an unknown ID gives a fixed error string.
- The model cannot pick a window or a number that is rendered. Rendered values and windows come from stored facts (`VerifiedFact`).
- Semantic evidence cannot support a graph assertion (ClaimVerificationAgent.scala:173-176). `DESCRIBED_IN_CONTEXT` is labeled "not a stored graph fact" and is always PARTIALLY_SUPPORTED.
- Schema and `AssertionFields` agree on PATH_WITHIN_HOPS (AnswerModel.scala:58, 94, 102, 115). The prompt v3 text matches the schema, with one key `claims` and no text field.
- The manifest lists v1, v2 and v3 prompts and the v1 and v2 schemas. I could not recompute SHA-256 values without a shell, so the hashes are unverified. `PromptRepository` checks them at load and the tests run in `rag/test`.
- Ownership: every file named in the report is under modules/rag, prompts or build.sbt. build.sbt belongs to build-engineer and was changed in 03a, not by rag-engineer. I could not confirm 03a's authorship without git diff, so this is an assumption.

TEST RESULTS: not run by reviewer. I rely on the report: `rag/test` 218 tests with 0 failures, and `sbt check` exit 0 (log at modules/rag/target/check-r03b.log). I did not read the log.
VALIDATION RESULTS: findings verified against the files by reading: yes for 1 (the test line 408 asserts the broken-out form), 2, 3, 4, 5, 6 and 7. I did not execute the failing inputs. The old-code failure runs mentioned in the report (5 of 5 failed) are the engineer's claim, and the temporary spec was deleted, so I cannot verify them.
ASSUMPTIONS: `TextSanitizer.clean` removes control, format and bidi characters and converts `"` to `'` in the evidence name. This is inferred from the test expectation and I did not read the sanitizer. The tests named in the report exist. I did not read every test body.
RISKS: A and C are removed by construction, so the verifier is no longer a lexical filter. The remaining risk is stored text (names, abstention candidates) placed in sentences, which is finding 1 and finding 6. Service wiring in Phase 10 must select prompt v3 and schema v2, map IdempotencyConflict to 409, and show interpretations as a separate block.
MANUAL ACTION REQUIRED: none for the human beyond routing. A human or the orchestrator decides the finding 3 version name.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: rag-engineer repairs finding 1 (for example by rendering names with a delimiter the name cannot contain, escaping or replacing `'` inside the name, and adding a test that fails on the current code) and the minor findings as prioritized. Finding 3 needs a decision. Then re-review.
