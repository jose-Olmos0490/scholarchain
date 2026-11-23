STATUS: FAILED
(The repair is done and `sbt rag/test` passes. The one `sbt check` run exited 1 because of a Spark out-of-memory failure in a module I did not touch. I did not rerun it, because you asked for one run. Details are under TEST RESULTS.)

OWNER: rag-engineer
WORK ORDER: Phase 9 repair 02. Fix review findings 1-6, 8 and 10 in modules/rag and prompts/ only. Findings 7 and 9 were out of scope.

FILES CREATED
- modules/rag/src/main/scala/scholarchain/rag/answer/ClaimText.scala (ShownText, Relation, RelationLexicon, ClaimRendering)
- modules/rag/src/test/scala/scholarchain/rag/answer/ClaimBindingSpec.scala (findings 1-5)
- modules/rag/src/test/scala/scholarchain/rag/answer/ResolverRunnerRepairSpec.scala (findings 6 and 10)
- prompts/answer-synthesis-prompt-v2.prompt.txt (sha256 65a9e125…8f1da47b is not stored here; the full hash is in prompts/manifest.json)

FILES MODIFIED
- modules/rag/src/main/scala/scholarchain/rag/answer/ClaimVerificationAgent.scala
- modules/rag/src/main/scala/scholarchain/rag/workflow/AnswerWorkflow.scala
- modules/rag/src/main/scala/scholarchain/rag/workflow/WorkflowSupport.scala
- modules/rag/src/main/scala/scholarchain/rag/agents/StageFailure.scala
- modules/rag/src/main/scala/scholarchain/rag/agents/ResearcherResolverAgent.scala
- prompts/manifest.json (v2 added; v1 prompt and its hash kept unchanged)
- Tests updated to the digit-free prose rule and to the v2 prompt:
  - WorkflowWorld.scala
  - SynthesisVerificationSpec.scala
  - AbstentionAuditSpec.scala
  - AnswerPromptSpec.scala
  - BenchmarkSpec.scala (rewritten, see finding 8)

No change to core or storage. No BLOCKED-ON was needed. The interpretation typing and `IdempotencyConflict` are modeled inside modules/rag.

COMMANDS RUN (all observed)
- `sbt rag/testOnly …ClaimBindingSpec …ResolverRunnerRepairSpec` against the old main code: 17 failed and 2 passed.
- `sbt rag/test` several times while iterating. The last run exited 0: 219 tests, 0 failures, "BENCHMARK total: 30 / 30", "BENCHMARK adversarial: 22 / 22".
- A temporary edit swapping the old cached pool back into StageRunner, run to re-confirm the finding 10 failure. The file was restored afterwards.
- `sbt fmt` (exit 0).
- `sbt check`, one foreground run, no pipe, output redirected to a file. **SBT_EXIT=1.**
- `sbt "sparkAnalytics/testOnly scholarchain.spark.PathsSpec"`: exit 0, 11 of 11 passed.

TEST RESULTS
- In the `sbt check` run, these modules passed: 695, 212, 216, 219 (rag) and 75 tests, 0 failures each; the last block of 352 tests had 0 failures and 9 ignored.
- The one failure was `scholarchain.spark.PathsSpec`, "semantic facts: structured values from computed data; PATH fact lists hop nodes". It failed with `SparkException: Not enough memory to build and broadcast the table`, after "Failed to allocate a page" warnings.
- That test lives in modules/spark, which I did not touch. It passes when run alone, so this looks like a memory flake under the full parallel run. It is not a rag regression, but `sbt check` did not exit 0 on this tree.
- Please rerun `sbt check`, or have someone with owner rights look at the Spark memory settings.

FINDING TABLE
"Failed before" means I ran the new test on the old main code and saw it fail. All the "yes" rows come from the one run at the start (17 failed, 2 passed).

| # | Fix | New test (spec: test) | Failed before? |
|---|---|---|---|
| 1 | Prose carries no digit or number word. A check on the text finds any Unicode numeric character (decimal digit, letter number, fraction) and English number words (two to ninety, hundred, thousand and so on; "one" is not listed). Claim text is UNSUPPORTED if it has one. Every number the person reads is written by `ClaimRendering` from the verified assertion, for example `DISTINCT_COAUTHOR_COUNT of <id> = 4, window 2010-2013`. | ClaimBindingSpec: "a true C_r=6 assertion does not license the text '6 publications'". Also: "one assertion does not license the same figure for two fields, or a window year"; "every number the person reads is rendered by code…"; "a summary or an uncertainty note with any digit is replaced or dropped"; "numbers written as words are refused…" | Yes. The C_r=6 case was VERIFIED before. The rendering test also failed. The number-words test is new and was not in the first failing run, so it has no before-fix record. |
| 2 | Added `ClaimKind`: FACT or INTERPRETATION. A claim with no assertion becomes an INTERPRETATION only if it passes the other checks and states no relation. A relation lexicon (pair, coauthorship, community, bridge, identity) requires an assertion of a matching kind, so context-only or wrong-kind assertions fail. Interpretations are not in `response.claims`. They go into `WorkflowResult.interpretations` and into `response.uncertainty` as "Interpretation, not a verified fact (…)", and their evidence IDs go into the audit record. An answer with only interpretations abstains as NO_VERIFIED_CLAIMS. | ClaimBindingSpec: "a prose-only edge, path, community, bridge or identity statement is UNSUPPORTED"; "a relation statement needs an assertion of the matching kind…"; "an assertion-free claim is shown apart from the facts, labeled as an interpretation"; "the verdict types a claim…"; "an interpretation still needs an existing citation and no number"; "a cross-community statement is backed by a stored cross-community bridge assertion"; "the response, the audit hash and the interpretation list describe the same text". SynthesisVerificationSpec: "a qualitative claim without an assertion is an interpretation, never a fact". | Yes for the first three. The prose-only claim was PARTIALLY_SUPPORTED and shown as a claim before. The four later tests were not in the first failing run. |
| 3 | Normalize first (NFKC, then strip control, format, zero-width and bidi characters, then NFKC again; the cut ends with `...`). Verify exactly that string. The shown text is the normalized prose plus the code-rendered facts. The workflow refuses any display text that does not survive normalization unchanged. IDs are scanned after normalization. | ClaimBindingSpec: "a zero-width character between digits…", "fullwidth digits…", "digits glued to letters…", "non-ASCII decimal digits…", "a fabricated ID with a zero-width character inside it is unknown, not invisible". Also "normalization is stable and folds compatibility forms…". | Yes for the five attack tests. Before, they were kept as VERIFIED and showed "42010", "９９９", "x999y", "٩٩٩" or the valid-looking fake ID. The normalization-stability test was not in the first failing run. |
| 4 | On `AlreadyExists` for the audit append, load the stored record. A matching `answerHash` is success. A different hash gives `StageFailure.IdempotencyConflict` (code IDEMPOTENCY_CONFLICT, not retryable; Phase 10 should map it to 409). The new answer is never returned. If the stored record cannot be read back, the failure is retryable. | ClaimBindingSpec: "a replay with a different answer is an IdempotencyConflict and leaves one audit record". AbstentionAuditSpec: the replay test now expects the typed conflict, stage "audit", and one audit row. | Yes. Before, the replay returned the new answer. |
| 5 | Rounded prose cannot occur now, because prose has no digits. The rendered value has one documented rule: up to six decimals, half up, trailing zeros stripped. An asserted real value must be within 1e-6 of the stored one. The old half-unit rounding is removed. | ClaimBindingSpec: "a rounded figure for a real-valued field is not accepted; the rendered value is exact" | Yes. "about 1" for a stored 0.97 was VERIFIED before. |
| 6 | Any candidate with a confidence that is NaN, infinite, below 0 or above 1 makes `resolve` return `Rejected("CANDIDATE_CONFIDENCE_INVALID", …)`. The workflow turns it into an abstention. Nothing is dropped quietly. `show` no longer throws on NaN. | ResolverRunnerRepairSpec: "a candidate with a NaN confidence is refused…" and "…an out-of-range confidence is refused, whatever its rank" | Yes. It resolved to the other candidate before. |
| 8 | BenchmarkSpec rewritten. Evidence citations now come from the stored text and structural fields of the evidence (subject and object mentioned, edge ID, path members), not from the system's own fact parser. Each fact is also made wrong separately by value, subject, object and window, and every variant must be removed. A new adversarial set runs on every answerable question: a true number written in prose, a hidden number (zero-width, fullwidth, glued), prose-only relations, and a wrong-kind assertion for the relation. The attacks must all be removed and the true claim must survive. The report prints and writes "adversarial: 22 / 22". | BenchmarkSpec: "adversarial claims over every answerable question…" and the rewritten "groundedness…" test | Not run against the old code. These cases test the same behaviour as the ClaimBindingSpec tests, which did fail before. |
| 10 | `StageRunner` now uses a bounded `ThreadPoolExecutor`: 16 threads (named constant `StageRunner.MaxThreads`, with a comment saying why it is not in config), a queue of 64, and a timed-out queued stage is removed. A full pool gives a retryable failure. | ResolverRunnerRepairSpec: "the stage runner keeps a bounded number of threads when stages ignore interruption" | Yes. 40 stuck stages used 40 threads on the old pool; the first run counted 53 because of other suites' threads, and the revised test reports 40. |

PROMPT, SCHEMA AND VERIFICATION VERSIONS
- New prompt `answer-synthesis-prompt-v2`. It says there are no digits or number words in the output text, the system writes the stored values, a relation needs a matching assertion, and an empty assertions list is an interpretation.
- The v1 prompt file is unchanged and still hash-checked. The schema `answer-synthesis-v1` is unchanged.
- The workflow tests now use v2. Verification policy is still named `claim-verification-v1`, though its rules changed. I did not rename it.

ASSUMPTIONS
- I put interpretations in `response.uncertainty` because core `AnswerClaim` has no type field. `WorkflowResult.interpretations` carries them typed for Phase 10 to show as a separate block.
- "Overall" verification for an answer with a kept interpretation is PARTIALLY_SUPPORTED, because the answer then holds unchecked text.
- An answer where only interpretations survive abstains.

RISKS
- The relation lexicon and the number-word list are broad on purpose. They can wrongly remove a correct claim, for example one that contains the word "group" or "two". The prompt tells the model to avoid them.
- A model phrase that states a relation without any word in the lexicon can still become an INTERPRETATION. It is shown labeled and never counted as a fact.
- A PATH_LENGTH assertion is checked against all stored paths of the pair, not only the shortest. Evidence "paths/4" includes longer paths, so "PATH_LENGTH 4" matches a stored longer path even though the shortest is 3. This is not in the review. I worked around it in the benchmark mutation (+1000). The path stage or verifier owner should decide whether it needs a rule.
- A replay still re-runs the whole workflow, including the model call, before the audit comparison. A nondeterministic model therefore makes some legitimate replays a conflict. Checking the audit record before the model call is a possible later improvement.
- I did not add a planner-model path to the benchmark; all 30 questions still use structured fields. The report path is still relative to the working directory.
- A stage that ignores interruption still holds one of the 16 threads until it returns.

MANUAL ACTION REQUIRED
- Rerun `sbt check` (or investigate the Spark `PathsSpec` memory flake). It is required before the human gate.
- Route to service-engineer: map `StageFailure.IdempotencyConflict` to HTTP 409, and show `WorkflowResult.interpretations` as a separate labeled block.
- Finding 7 (config keys) is still deferred to domain-engineer. Finding 9 is unchanged.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: passing `sbt check`, then code-reviewer re-review, then retrieval and groundedness evaluation
