STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Read-only adversarial re-review of Phase 9 repair 04 (rag-engineer). It re-checks findings 1-7 of review-report-03 and hunts for regressions.
VERDICT: APPROVE. Findings 1-3 and 5-7 are closed, and finding 4 is untouched and still diagnostic-only. I found no bypass of the delimiter, no wrong arithmetic in the summary or uncertainty, and no ownership violation. Four minor items remain. None blocks the phase. Route them at the orchestrator's discretion.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools only)

RE-CHECK OF FINDINGS 1-7
- 1 (stored names escaping delimiters): CLOSED.
  - `ClaimRendering.quote` (ClaimText.scala:63-64) removes « and » from the stored text and then wraps it, so no stored text can end the span.
  - Name sites: `labeller` quotes names (:85-87). `valueText` quotes stored text values after NFKC normalization (:69). That covers the NAME sentence, edge IDs, and community or other text values.
  - Interpretations use the same labeller and `valueText` (Interpretations.scala:105-111). The other placeholders are the schema-enum `field`, integer windows, and numbers.
  - The summary and uncertainty use the labeller (AnswerWorkflow.scala:656-667).
  - The delimiter is stripped after NFKC, and the final `TextSanitizer.clean` cannot create « or ». U+00AB and U+00BB have no NFKC source characters.
  - Lookalike glyphs such as ‹ › 《 » inside a name stay inside the span. They are only a visual concern.
- 2 (summary counts): CLOSED. `summaryOf` (AnswerWorkflow.scala:886-897) counts VERIFIED separately from PARTIALLY_SUPPORTED. Repair04Spec test 2 would fail on the old "count all" shape.
- 3 (policy version): CLOSED. `VerificationPolicyVersion = "claim-verification-v2"` (AnswerWorkflow.scala:838) is used in the answer provenance (:644) and the audit provenance (:746). A grep finds no remaining `claim-verification-v1` outside docs/agent-runs.
- 4 (removed-claim text): UNTOUCHED, as intended. `ClaimRendering.requested` (ClaimText.scala:125-135) still carries raw model strings. It is labeled "not verified, requested:" and capped at 500. It appears only in `WorkflowResult.removed`. The response summary and uncertainty use only counts of removed claims. Phase 10 must keep it diagnostic-only.
- 5 (multiple path queries): CLOSED. `oneQuery` (ClaimVerificationAgent.scala:270-274) requires one distinct (depthLimit, resultLimit, returned, dropped, depthCapped) signature across the pair's path scopes. It is folded into `complete` (:274). The test changes `resultLimit` on the last path item. The old code would have returned VERIFIED there, so the test can fail.
- 6 (abstention candidate delimiting): CLOSED. `candidateLine` (AnswerWorkflow.scala:498-502) quotes the name and the affiliations. The ambiguous and not-found names are quoted too (:230, :235). The resolver already runs candidate text through `TextSanitizer.clean` (ResearcherResolverAgent.scala:154-160). I found no other interpolation of names into failure details.
- 7 (claimId failure): CLOSED. `claimsOf` (AnswerWorkflow.scala:843-874) returns a `RemovedClaim` with status UNSUPPORTED and reason `CLAIM_ID_FAILED: ...`.
  - The failure is added to `removedAll` (:651).
  - It is counted in the summary (:891) and in the uncertainty (:676-678), and both are covered by `AnswerHash` (WorkflowSupport.scala:141-144).
  - The overall status drops from VERIFIED to PARTIALLY_SUPPORTED (:693-695).
  - If every claim fails to get an ID, the response has no claims and fails the answer contract with a typed ANSWER_INVALID.

FINDINGS (all minor)
| # | Severity | File:line | Concrete failure input | Expected impact | Owning agent |
|---|---|---|---|---|---|
| 1 | minor | Repair04Spec.scala:209-245 | Test 7 calls only the injectable `claimsOf` and `summaryOf`. It never runs the workflow with a failing ID function. | The report says ID failures are counted in the uncertainty and the audit, and that the overall status is downgraded. Nothing tests that. If the uncertainty line (AnswerWorkflow.scala:676-678) or the downgrade (:693-695) were deleted, no test would fail. The code is correct as read. The test coverage is thinner than the report says. | rag-engineer |
| 2 | minor | AnswerWorkflow.scala:654-655 | One claim of two fails its ID. | `subjects` and `windows` come from `facts`, which includes the claim with the failed ID. The summary can say "about X" for a researcher who appears in no shown claim. It does not affect counts. Using `claimsOut` or the kept facts would be cleaner. | rag-engineer |
| 3 | minor | AnswerWorkflow.scala:518, 514-517, 688; ResearcherResolverAgent.scala:154 | Many candidates with long names or affiliations push the abstention uncertainty (cap 4000) or the summary (cap 1500) over the cap. | `TextSanitizer.clean` cuts at the cap, which can leave an unclosed « in the cut text. The text after the cut is gone, so nothing hostile can follow. This is a presentational flaw, not an injection. The test names do not exercise the cap. | rag-engineer |
| 4 | minor | AnswerWorkflow.scala:672, 680 | `g.gaps` (built from store-failure `detail` strings) and `partial` (verdict reasons) are inserted into the uncertainty unquoted. | Today they come from fixed code strings, field names and short IDs, and I found no stored name in them. This is a latent site: any future failure detail that includes stored text would bypass the delimiter. Consider a rule or a test that gap text is stored-text-free. | rag-engineer |

OTHER CHECKS WITH NO FINDING
- The placeholder pass is a single pass with `quoteReplacement`, so a stored `{value}` is never expanded.
- Interpretations are wrapped in single quotes inside the uncertainty (AnswerWorkflow.scala:684). The stored text inside is already in « », so the single quotes do not matter.
- The `stable()` re-normalization check (:617) still passes with « ». The tests reach the display text through it.
- The test hostile set (`a'b`, `a"b`, `a«b»c`, a back tick, a brace placeholder with 999, a leading », and a close-and-continue sentence) uses an `outside()` that strips the delimited spans and checks the rest. The `spans(...)` assertions require « ». The old single-quote shape fails them.
- Test 3 would fail on the v1 string. Test 2 would fail on the old count.
- Ownership: every file named in the repair report is under modules/rag. `build.sbt` and `prompts/manifest.json` also show as modified, but they come from earlier repairs and are outside this repair's report. The repair report claims no prompt or schema change, and I saw no evidence against that.

TEST RESULTS: not run by reviewer. I rely on the report (`rag/test` 225 tests, 0 failures, benchmark 30/30, adversarial 22/22, `sbt check` exit 0 at modules/rag/target/check-r04.log). I did not read the log or run it.
VALIDATION RESULTS: findings verified against the files by reading: yes for 1-4. I did not execute any inputs. The report's claim that 6 of 7 new tests failed on the old behavior is not verifiable by me. I confirmed by code reading that tests 1, 2, 3, 5 and 6 would fail on the old shapes. I did not re-read the ClaimBindingSpec edits.
ASSUMPTIONS: No NFKC compatibility mapping produces U+00AB or U+00BB. `TextSanitizer.clean` only normalizes to NFC, removes invisible characters and rewrites double quotes, so it cannot re-create the delimiters.
RISKS: Phase 10 must treat `WorkflowResult.removed` as diagnostic-only (finding 4 of report 03). Consumers of claim text must tolerate « » around names and stored text values.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Phase 9 retrieval and groundedness evaluation, then the human gate. The minor items are optional repairs for rag-engineer. The orchestrator may route them or carry them as accepted residuals.
