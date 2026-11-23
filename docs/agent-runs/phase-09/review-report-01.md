STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Read-only review of ScholarChain Phase 9 (f3b8527..HEAD plus working tree, untracked files included) across modules/core, modules/storage, modules/rag and prompts/, against the 8 focus questions.
VERDICT: REQUEST_CHANGES
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools only)

ANSWERS TO THE 8 FOCUS QUESTIONS
1. Can user text reach a DB query or arbitrary operation? No path found.
   - OperationAllowlist.scala:35-200 and QueryPlannerAgent.scala:113-126 match operations and metrics by exact enum name.
   - Researcher IDs are parsed with StableId.parseAs (QueryPlannerAgent.scala:159, ResearcherResolverAgent.scala:157).
   - Topic, venue and institution names that are not stable IDs are refused as Unsupported (CollaborationMetricsAgent.scala:121-136).
   - The Neptune templates in modules/storage GraphPorts.scala interpolate only a bounded Int depth, a bounded Long timeout and an enum metric. All other values are parameters.
   - Free question text is only sanitized and embedded (SemanticContextAgent.scala:88-95), or sent to the planner model as JSON data.
   - The semantic vector filter uses validated IDs and numeric years.
2. Can an ambiguous name resolve silently? Not in the main path.
   - ResearcherResolverAgent.decide (lines 120-138) requires the minConfidence and minMargin thresholds, and both come from config.
   - Ambiguous and not-found names abstain with candidates (AnswerWorkflow.scala:222-247), and the model is not called.
   - One narrow hole is finding 6.
3. Can a numeric, path or identity claim pass ClaimVerificationAgent without matching deterministic evidence? Yes, in the ways listed in findings 1, 2 and 3.
4. Can semantic evidence support a numeric, edge, path, community, bridge or identity assertion?
   - For a stored assertion, no. EvidenceFacts.of returns no facts for a semantic item (EvidenceFacts.scala:266), and ClaimVerificationAgent.evaluate (lines 152-155) rejects semantic-only citations.
   - Via claim text, yes in words. See finding 2.
5. Is any verification done by an LLM? No. ClaimVerificationAgent and EvidenceFacts import no model, store or network. The LLM only writes the answer, and the planner extracts fields.
6. Tests that pass even if the behavior is broken: see finding 8.
7. Hardcoded thresholds, model IDs or limits: see finding 7. No model IDs are hardcoded.
8. AWS SDK in modules/rag, and writes outside owned paths:
   - AWS SDK: Embedders.scala:6-8 and :121-124 (Bedrock) and the Bedrock test files in modules/rag/src/test (BedrockEmbedderIT.scala, EmbeddingSpec.scala). These are not in the Phase 9 diff and appear to come from Phase 8. No AWS import was found in any Phase 9 file or in llm/. Finding 9.
   - Writes: the reports list only modules/core (domain-engineer), modules/storage (storage-engineer) and modules/rag plus prompts (rag-engineer). I found no ownership violation. I have no git diff tool, so this rests on the reports and on file reads. The Dependencies.scala split was not part of this diff as far as I can see.

FINDINGS
| # | Severity | File:line | Concrete failure input | Expected impact | Owning agent |
|---|---|---|---|---|---|
| 1 | major | modules/rag/src/main/scala/scholarchain/rag/answer/ClaimVerificationAgent.scala:263-281, 293-295 | Claim text "Ada has 6 publications." with a single assertion {METRIC, Ada, DISTINCT_COAUTHOR_COUNT, 6, window}. Also text "5 coauthors and 5 papers" with only C_r=5 asserted. Also "2019 coauthors" with window 2019-2020, because window years license numbers. | `licensed()` pools the numbers of all assertions with no binding between a number in the text and the field it describes. A true assertion can license a wrong statement in the text, and the claim is VERIFIED. This breaks "exact counts come from stores". | rag-engineer |
| 2 | major | ClaimVerificationAgent.scala:117-120, 158-167; modules/rag/src/main/scala/scholarchain/rag/workflow/AnswerWorkflow.scala:636-648 | Claim text "Ada and Lin are coauthors and the same person as X" (no digits), with no assertions, or with one PUBLICATION/DESCRIBED_IN_CONTEXT assertion whose subject appears in a cited item. | The claim is kept as PARTIALLY_SUPPORTED. Edge, path, community, bridge and identity statements made in words are never matched to deterministic evidence. The only labels are the generic "no checkable assertion" and "described only in retrieved context". This leaks a semantic-only or unsupported edge or identity assertion to the user. Verification checks digits and IDs only. | rag-engineer |
| 3 | major | ClaimVerificationAgent.scala:105, 250, 292-295 versus AnswerWorkflow.scala:642 | Claim text "Ada has 9<ZWSP>99 coauthors", with assertions of 9 and 99. Also a fabricated well-formed ID with a zero-width character inside its hex. Also fullwidth or Arabic-Indic digits ("９９９"). Also a number glued to letters ("top10"), which the lookbehind at line 250 skips. | The text is verified raw, then sanitized (invisible characters removed) after verification. IdScan and NumberPattern run on the raw string. After sanitization the user sees "999" or a valid-looking unknown ID that was never checked. `\d` is ASCII-only, so other digit forms are never checked. Only the summary and uncertainty note are sanitized before the guard (`guarded`, AnswerWorkflow.scala:608-613). | rag-engineer |
| 4 | major | AnswerWorkflow.scala:729-731, 760 | A replay of the same queryId where the second synthesis differs, because the model is nondeterministic. | `AlreadyExists` on the audit append counts as success, so the caller receives the new answer while the stored audit record and answerHash describe the first one. That answer is served without a matching audit record, which breaks the audit invariant. The report concedes the first answer text is not stored (risk 7). | rag-engineer (service-engineer must treat the replay path in wiring) |
| 5 | minor | ClaimVerificationAgent.scala:273-281 | Stored W_r=2.6 with text "3"; stored PARTICIPATION_COEFFICIENT=0.4 with text "0". | Rounded text is accepted within half a unit of the last written digit. This conflicts with the prompt rule "copy exactly" and can mislead. It is documented, but not flagged to the user. | rag-engineer |
| 6 | minor | ResearcherResolverAgent.scala:122-131 | A directory with candidates A (0.9) and B (NaN or out of range). | B is dropped silently before the margin test, so A resolves although another candidate exists. `totalFound` omits B. The data is corrupt, but the agent gives no signal. | rag-engineer |
| 7 | minor | ResearcherResolverAgent.scala:106-107; AnswerWorkflow.scala:403-405, 653-656, 775; TextSanitizer caps of 80, 300, 500, 1000, 1500 and 4000; WorkflowSupport.scala:20-35 and AnswerSynthesisAgent.scala:12 (synthesis versions, stage timeout and default window are constructor inputs, not config) | n/a | Display caps, a default fallback semantic query text, the trace-ID pattern, and the synthesis and stage settings are not in configuration. The synthesis and stage keys are acknowledged in the report. | rag-engineer for the caps; domain-engineer for the config keys |
| 8 | minor | modules/rag/src/test/scala/scholarchain/rag/answer/BenchmarkSpec.scala:340-362, 412-433, 253-299; SynthesisVerificationSpec.scala:271 | n/a | Groundedness is partly circular: the "grounded" claims take their fact and evidence ID from the system's own `EvidenceFacts` parser (`found`, line 343). Mutation changes only the value (+1 or a fake researcher), never the subject, window, citation or claim text, and null values are never mutated (line 349). All 30 requests give structured fields, so the model planner path is never benchmarked. Semantic retrieval is 3 questions on fake embeddings. No test covers findings 1, 2 or 3 (a grep for zero-width, fullwidth and cross-field text found nothing). The report path is cwd-relative ("modules/rag/target/..."). The 30/30 result is real but overstated as a groundedness evaluation. | rag-engineer |
| 9 | minor | modules/rag/src/main/scala/scholarchain/rag/embed/Embedders.scala:6-8, 121-124 (pre-Phase 9) | n/a | The AWS SDK (Bedrock) is imported directly inside modules/rag, outside ports. CLAUDE.md restricts only internal module dependencies, so this is not a formal violation. It is raised because of focus point 8. | rag-engineer (architect decision) |
| 10 | minor | WorkflowSupport.scala:144-163 | A store call that ignores interruption, repeated under load. | `newCachedThreadPool` is unbounded and `future.cancel(true)` may not stop the task, so threads can accumulate. | rag-engineer |

VALIDATION RESULTS: Findings 1-8 and 10 were verified against the cited lines, which I read directly. For findings 1, 2, 3 and 4 I verified the code path by reading but did not execute the failing input, because I have no shell. Finding 9 was verified by grep. Finding 3's `\d` ASCII behavior assumes default Java regex flags, and none are set at line 250.

TEST RESULTS: not run by reviewer. I rely on the reports: sbt check exit 0, rag 192 tests, BenchmarkSpec 30/30.

ASSUMPTIONS:
- The phase-09 reports accurately list the files created and modified.
- I could not run git diff, so I reviewed the files directly, not a line-level diff.
- The domain and storage repair changes are covered only in part. I looked at the Neptune query construction for injection and at the core change lists. I did not review RecordContract, config loader or DynamoDB adapter internals line by line.

RISKS: Findings 1-3 are bypasses that need an adversarial or misaligned model output. A prompt-injected model can reach them without any change in the stores. The effect is a user-visible wrong claim that is labeled VERIFIED or PARTIALLY_SUPPORTED.

MANUAL ACTION REQUIRED: none from the human beyond routing the findings. The human decides on finding 9.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: rag-engineer repairs findings 1-4 (add tests for each), and findings 5-8 and 10 as the orchestrator prioritises; domain-engineer for the config keys in finding 7; then test-engineer adversarial tests, then re-review.
