STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 9 repair 03b. Make the structural fix for review findings A-G (review-report-02.md). Everything a user reads as fact is rendered by code from VERIFIED assertions. Bump the synthesis prompt and schema versions. Only modules/rag/** and prompts/** were written.

SUMMARY OF THE DESIGN
- The model no longer writes free text. Output schema `answer-synthesis-v2` has one key, `claims`. Each claim has only `evidenceIds`, `assertions` (at least 1) and `interpretation` (a template ID or null). There is no `summary`, no `uncertainty` and no claim `text`. A payload with any of those keys fails schema validation, is retried within the bound, then fails as MODEL_OUTPUT_INVALID with nothing audited.
- Code renders:
  - `ClaimRendering.sentence` writes one sentence per verified assertion: names and IDs, numbers, relations (coauthor edge, path hop, community, identity), and windows.
  - The summary and the uncertainty note are written in `AnswerWorkflow.assemble`. They come from verified assertions, recorded gaps, removed and partial counts, and stored identity confidence (below 1).
  - Rendered numbers are the STORED values. Rendered windows are the STORED windows of the matched fact.
- Interpretation: a fixed, versioned set of 7 templates (`InterpretationTemplates`, version `interpretation-templates-v1`).
  - The model picks an ID only.
  - Placeholders `{subject} {object} {value} {field} {window}` are filled in one pass from one verified assertion of the same claim, so a stored value containing `{value}` is not expanded.
  - A template that does not fit the claim's verified assertions is dropped, and the claim becomes PARTIALLY_SUPPORTED with a reason.
  - The text is shown only in the labeled uncertainty block and in `WorkflowResult.interpretations`. It is never in `claims`.
- Removed claims are described by `ClaimRendering.requested` ("not verified, requested: ..."), sanitized and capped at 500 code points.

PROMPT VERSIONS, SCHEMAS, ALLOWED OPERATIONS
- New: `prompts/answer-synthesis-prompt-v3.prompt.txt` and `prompts/schemas/answer-synthesis-v2.schema.json`.
- Both are in `prompts/manifest.json` with sha256. v1 and v2 prompts and the v1 schema are untouched and still hash-checked.
- New assertion field `PATH_WITHIN_HOPS` (PATH_HOP kind).
- The test-wired config is now `SynthesisConfig("answer-synthesis-prompt-v3","answer-synthesis-v2")`. Production wiring (service-engineer) must select these versions.
- Verification policy name is still `claim-verification-v1`, though its rules changed. This was not renamed; see RISKS.

VERIFICATION RULES CHANGED
- D: `PATH_LENGTH` is the minimum over every stored path of the pair in the bundle, same window.
  - It is accepted only when the path query was complete: nothing dropped, depth not lowered, result limit not reached, and every returned path present in the bundle.
  - A wrong length is CONFLICTING even when a longer path with that length is stored.
  - If the query was not complete, or the bounds are missing, `PATH_LENGTH` is UNSUPPORTED. Only `PATH_WITHIN_HOPS` may be asserted.
  - `PATH_WITHIN_HOPS` N is verified when N is at least the shortest stored length and at most the query depth. N below the shortest is CONFLICTING when the query was complete, otherwise UNSUPPORTED.
  - To carry the bounds, `EvidenceBuilderAgent` now appends ` | query depthLimit=.. resultLimit=.. pathsReturned=.. droppedPaths=.. depthCapped=..` to path evidence text. `PathReport` gained `depthCapped: Boolean = false`. `EvidenceFacts` parses this into `ItemFacts.pathScope`.
- E: an asserted window must equal the stored fact's window, and a fact without a window takes none. An invented window is UNSUPPORTED. Rendering uses the stored window.
- The `+1000` mutation in the benchmark is removed (wrong length is `+1`). A longer-path case was added.

FINDINGS-TO-TESTS TABLE
"Failed before" means a temporary spec with the reviewer's exact inputs was run on the pre-change code (5 of 5 failed). The spec was then deleted. Its inputs were converted into the final tests below, in the new model. Tests marked "n/a (new)" cover new behavior and cannot compile against the old API, so there is no old-code run for them.

| Finding | Reviewer failing input | Test (ClaimBindingSpec unless noted) | Failed before |
|---|---|---|---|
| A | Summary "...are the same person, and they coauthored many papers"; uncertainty "...same community as Lin" | "A: a model summary or uncertainty that states a relation is refused, never shown" | Yes (the summary was shown) |
| A | Same | "A: the summary and the uncertainty are written by code from verified assertions" (exact summary string; identity confidence 0.97 in the uncertainty) | n/a (new) |
| B | "IX coauthors", "a single coauthor", and in the test "Ⅸ", "one", "a couple", "dos", "drei", "twice" | "B: a claim text with a number in any form is refused: the schema has no text field" | Yes (IX and "single" were VERIFIED) |
| B | Numbers beyond words | "B: every number a person reads is the stored value, rendered by code"; "B: a real value is rendered exactly as stored, and a rounded one is refused" | n/a (new) |
| C | "works with", "colleagues" (also "teammates", "one and the same") | "C: a model that writes relation words in a claim is refused: the schema has no text" | Yes (both VERIFIED) |
| C | Relation backed by another pair's edge | "C: a relation is written only for a verified assertion, about exactly its two researchers" (edge of (1,3) does not back (1,4)); "C: a claim with no assertion is refused..." | n/a (new) |
| D | Longer path of the pair (length 4, shortest 3) asserted as PATH_LENGTH | "D: the shortest length verifies; a longer stored path's length is a conflict"; "D: the fixture stores a shortest and a longer path of the pair" | Yes (VERIFIED) |
| D | Truncation | "D: when the path query was truncated only 'within N hops' may be asserted" (depth cap, dropped paths, result limit, missing path item, no bounds); "D: 'within N hops' needs a stored path of at most N hops and N within the query depth" | n/a (new field) |
| D | `+1000` masking | BenchmarkSpec: mutation is now `+1` for PATH_LENGTH, so the "wrong value is removed" benchmark covers it | n/a |
| E | IDENTITY assertion with window 1900-2100 | "E: an invented window on a fact that has none is not accepted and never rendered"; "E: the rendered window is the stored window; another window is not evidence" | Yes (display showed window 1900-2100) |
| 2 remnants | Prose-only relations | Gone by construction: A, B, C tests. BenchmarkSpec adversarial set rewritten as 7 structural attacks on every answerable question (22/22 held) | n/a |
| 8 remnants | Circular benchmark, masking | BenchmarkSpec: no `+1000` masking; adversarial set rewritten | n/a |
| Interpretation | Template rules | "an interpretation is a fixed template filled from the verified assertion only"; "a template that does not fit... is dropped and labeled"; "a template is never filled from an assertion that did not verify"; "a placeholder inside a stored value is not expanded"; "every template holds only known placeholders..." | n/a (new) |
| Names / injection | Injected stored name | "a stored name is shown quoted next to its ID, and a quote inside it cannot break out" | n/a (new) |
| Structure | Schema and prompt | AnswerPromptSpec: "the model has no key for prose", "interpretation enum is exactly the fixed template set", "v3 tells the model it writes no text...", old versions still hash-checked. SynthesisVerificationSpec: malformed-output test extended with unknown template, `text` key, `summary` key, empty assertions and empty evidence | n/a (new) |

F and G
- F: the interpretation block is now fixed template text. Showing at most 5 interpretations in the uncertainty (a one-line change) keeps them under the 4000 cap, so no label or quote is cut in practice. It is fixed by construction, and no separate test was added.
- G:
  - The unused `removed` argument of `abstainingResponse` is removed.
  - The test gap on `CANDIDATE_CONFIDENCE_INVALID` at workflow level was not closed. I tried it, and the directory fake rejects a NaN confidence on `put`, so the case cannot be built. The agent-level test in ResolverRunnerRepairSpec still covers it.
  - The remaining benchmark circularity (`factsOf` uses `EvidenceFacts`) is not removed. See RISKS.

FILES CREATED:
- /home/olmos/scholarchain/modules/rag/src/main/scala/scholarchain/rag/answer/Interpretations.scala
- /home/olmos/scholarchain/prompts/answer-synthesis-prompt-v3.prompt.txt
- /home/olmos/scholarchain/prompts/schemas/answer-synthesis-v2.schema.json
(The earlier untracked rag files from repair 02 are unchanged.)

FILES MODIFIED:
- modules/rag/src/main/scala/scholarchain/rag/answer/{AnswerModel,ClaimText,ClaimVerificationAgent,EvidenceFacts}.scala
- modules/rag/src/main/scala/scholarchain/rag/workflow/AnswerWorkflow.scala
- modules/rag/src/main/scala/scholarchain/rag/agents/{EvidenceBuilderAgent,PathFinderAgent}.scala
- prompts/manifest.json (v3 prompt and v2 schema added)
- Tests: ClaimBindingSpec (rewritten), SynthesisVerificationSpec, AnswerPromptSpec, AbstentionAuditSpec (v3 version strings), BenchmarkSpec, WorkflowWorld (helpers)
- Removed from main code: the relation lexicon, the number-word and digit checks, `ClaimKind` and `KnownIds`. They are obsolete because the model writes no prose.
- build.sbt was already modified in the working tree before this task (repair 03a). I did not touch it.

COMMANDS RUN (all observed):
- `sbt "rag/testOnly scholarchain.rag.answer.TmpFailingFirstSpec"` on the old code: 5 tests, 5 failed. The spec was then deleted.
- `sbt rag/test` several times while iterating. Final: 218 tests, 0 failures; "BENCHMARK adversarial: 22 / 22", "BENCHMARK total: 30 / 30".
- `sbt fmt` (success).
- `sbt check` once, as a single foreground command, no pipe, timeout 5400000 ms, output to /home/olmos/scholarchain/modules/rag/target/check-r03b.log. SBT_EXIT=0 observed (about 31 minutes).

TEST RESULTS:
In the `sbt check` run, 0 failed in every block:
- 695, 212, 216 and 66 tests succeeded.
- rag: 218 tests succeeded.
- 75 tests succeeded.
- 352 tests succeeded, 9 ignored.
- The log has 0 warn lines.

VALIDATION RESULTS:
- The benchmark suite is part of `rag/test` and passed: total 30/30, adversarial 22/22. The adversarial set was rewritten for the new model (unstored relation, path bound and exact length for a pair without paths, invented window, wrong-community subject, never-stored window, citation not in the bundle).
- The prompt and schema hashes are verified by `PromptRepository` at load.

ASSUMPTIONS:
- An interpretation attached to a fully verified claim leaves the claim VERIFIED. It is labeled and is code-written fixed text, not a stored fact. Overall status is VERIFIED unless a template did not fit.
- A researcher's stored name (from the identity evidence) is shown quoted next to its ID. It is stored data, sanitized and cut at 100 code points. A name that contains instruction-like text is therefore shown verbatim as data inside quotes. That is the existing behavior of the abstention candidates line.
- For a path pair, "complete" is read from the query bounds that the evidence builder now writes into the evidence text. Evidence without them (older bundles) never counts as complete.

RISKS:
- The verification policy version string is still `claim-verification-v1` although the rules changed. The orchestrator or a human should decide whether to bump it. The code that records it is in `AnswerWorkflow.VerificationPolicyVersion`.
- The service layer (Phase 10) must:
  - select prompt `answer-synthesis-prompt-v3` and schema `answer-synthesis-v2`;
  - map `StageFailure.IdempotencyConflict` to HTTP 409;
  - show `WorkflowResult.interpretations` as a separate block.
- Residuals from earlier repairs that this change does not remove:
  - Benchmark facts are still derived with `EvidenceFacts` (the parser under test). The citations are structural.
  - All 30 questions use structured fields, so the model planner path is not benchmarked.
  - The benchmark report path is cwd-relative.
  - The replay re-runs the model before the audit comparison. A nondeterministic model can cause a legitimate-replay conflict.
- Changing the path evidence text adds the ` | query ...` suffix. Evidence IDs are hashed from stored fields and not from this text, but any external consumer parsing path text must tolerate the suffix.
- A model can still choose a poorly fitting interpretation template. A template that does not fit is dropped. One that fits states only the fixed template reading, but the reading is still an opinion, labeled "Interpretation, not a verified fact".
- Interpretations are folded into `response.uncertainty` because core `AnswerClaim` has no separate field. This is unchanged from repair 02.

MANUAL ACTION REQUIRED: none for rag. Route to service-engineer for Phase 10 as listed under RISKS.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: retrieval and groundedness evaluation, code-reviewer re-review
