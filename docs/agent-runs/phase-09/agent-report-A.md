STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 9, work order A (rerun after repair 01). The deterministic half of the runtime collaboration-analysis workflow in modules/rag, plus prompts/. No real LLM calls. All writes are under modules/rag/ and prompts/. Ports were coded against the storage signatures as they exist now. Every required port signature was present, so there was no BLOCKED-ON.

FILES CREATED
prompts/
- prompts/manifest.json
- prompts/query-planner-prompt-v1.prompt.txt
- prompts/schemas/query-plan-v1.schema.json
modules/rag/src/main/scala/scholarchain/rag/llm/
- LlmClient.scala (LlmClient, LlmRequest, LlmResponse, LlmFailure, LlmUsage)
- JsonSchema.scala
- PromptRepository.scala
- StructuredLlmCall.scala
modules/rag/src/main/scala/scholarchain/rag/agents/
- StageFailure.scala
- OperationAllowlist.scala
- QueryPlannerAgent.scala
- ResearcherResolverAgent.scala
- CollaborationMetricsAgent.scala
- PathFinderAgent.scala (also holds DirectCoauthorsAgent)
- CommunityAnalystAgent.scala
- SemanticContextAgent.scala
- EvidenceBuilderAgent.scala
modules/rag/src/test/scala/scholarchain/rag/agents/
- AgentFixture.scala (fixture world, scripted fake LLM)
- PlannerSpec.scala
- DeterministicAgentsSpec.scala (ResolverSpec, MetricsAgentSpec, PathAgentSpec, CommunityAgentSpec)
- SemanticAndEvidenceSpec.scala (SemanticAgentSpec, EvidenceBuilderSpec)
- InjectionSpec.scala

FILES MODIFIED: none. Existing rag files are untouched. `sbt rag/scalafmtAll` reformatted only the new files.

COMMANDS RUN (all observed)
- `sbt rag/compile`, `sbt "rag/testOnly scholarchain.rag.agents.*"` (several iterations)
- `sbt rag/scalafmtAll`, `sbt rag/scalafmtCheckAll`
- `sbt check`, once, in the foreground with a 90-minute timeout and no pipe. I observed `SBT_EXIT=0`.

TEST RESULTS
- `sbt check` exited 0 and took 1959 s. markTestRun printed "tests passed; touched .claude/.last-test-run". The file is now timestamped 22:23.
- The rag module reports 131 tests in 16 suites, 0 failures. That is the 53 existing tests plus 78 new.
- Every other module's block in the log shows 0 failures: 695, 212, 216, 75, 66 and 352 tests (9 ignored in the 352 block). Tests that need AWS were skipped, as before.
- New suites: PlannerSpec 21, MetricsAgentSpec 9, ResolverSpec 10, PathAgentSpec 8, CommunityAgentSpec 7, SemanticAgentSpec 7, EvidenceBuilderSpec 10, InjectionSpec 6.

VALIDATION RESULTS (each covered by a test)
- C_r and W_r rankings differ. Top by C_r is researcher 3 (giant team). Top by W_r is researcher 5. Each ranking follows exactly the requested metric.
- The ambiguous name "Wei Zhang" returns Ambiguous with both candidates, each with source IDs, affiliations, coauthor sample, confidence and decision IDs.
- Every path hop carries both researcher IDs, jointPublicationCount, first and last year, supporting work IDs and provenance. Supporting works count equals jointPublicationCount.
- A requested depth of 9 is capped to retrieval.maxPathDepth (4). The store receives maxDepth=4. The 5-hop path from researcher 1 to researcher 7 is not returned; it is found only when the cap is raised to 5.
- StorageError.Timeout becomes StageFailure.Retryable, with retryable=true.
- An operation outside the allowlist is rejected: structured wire strings, lower-case spelling, and model output ("DROP_DATABASE" or the attack string). In the model case the failure is MODEL_OUTPUT_INVALID, and the call count is exactly 1 + maxRetries.
- A question containing "MATCH (n) DETACH DELETE n" never reaches a store. Spies on graph, metric, path, analytics, cache, vector and directory ports show no query text: structured fields only (model not called); model extraction (question travels only as JSON data); attack as an operation (no store called); attack as a researcher name (directory receives only the folded key "match n detach delete n", no other store called).
- Storage Unsupported is surfaced as typed Unsupported for a bridge ranking, a venue filter, an unloaded window (detail names the loaded windows) and topic names.
- Foreign-bucket textS3Uri is refused and the object store is never read: foreign bucket, configured non-RAG bucket, ".." path. A text that does not hash to textHash is also refused.
- Semantic items never appear in the deterministic section. Retrieved injection text appears only in semanticEvidence, sanitized (no newline or bidi characters), capped, `authoritative` false.
- Bundle bounded: evidenceMaxItems enforced with deterministic items kept before semantic; estimated tokens never exceed contextTokenBudget; a budget of 1 gives an empty but contract-valid bundle.
- Items without provenance dropped and counted; invalid-ID and duplicate items counted separately. considered = kept + droppedNoProvenance + droppedInvalid + droppedDuplicate + droppedOverBudget.
- Malformed model output: not JSON, JSON array, missing field, extra field, wrong type, code fence (all rejected); over-token-cap response; transient failure retried; permanent failure stops at once; maxRetries=0 honored; failure message never repeats the rejected text.
- Prompt manifest SHA-256 enforced: edited prompt without new version gives PROMPT_CHANGED. Schema operation and metric enums tested equal to core enums. Unsupported schema keywords refused at load.

STAGE INPUT/OUTPUT TYPES (package scholarchain.rag.agents unless noted)
- SResult[A] = Either[StageFailure, A]. StageFailure: Unsupported(detail); Retryable(operation, detail); Rejected(code, detail); NotFound(detail); Failed(operation, detail). StageFailure.fromStorage maps Timeout and Transient to Retryable, Unsupported to Unsupported, LimitExceeded and InvalidRecord to Rejected.
- llm.LlmClient.complete(LlmRequest): Either[LlmFailure, LlmResponse]. No AWS type in the trait. Scripted fake: ScriptedLlm in test sources.
- llm.StructuredLlmCall(client, RagLlmConfig).run(promptVersion, system, untrustedInput, JsonSchema): StructuredResult(outcome: SResult[ujson.Obj], usage: LlmUsage). Usage reported also on failure.
- QueryPlannerAgent(PlannerConfig, RetrievalConfig, StructuredLlmCall, PromptRepository, maxQuestionCodePoints).plan(PlanRequest(queryId, runId, snapshot, question: Option[String], StructuredFields)) returns PlanResult(outcome: SResult[PlanOutcome], usage). PlanOutcome: core QueryPlan (validated by RecordContract); steps: Vector[PlannedStep(operation, agent, templateIds, portOperations)]; usage, llmUsed, conflicts.
- ResearcherResolverAgent(ResearcherDirectory, ResolverConfig).resolve(rawName): SResult[Resolution]: Resolved(name, CandidateView, runnerUpConfidence) | Ambiguous(name, candidates, reason: LowConfidence | SmallMargin, totalFound) | NotFound(name). resolveAll(names, explicitIds): SResult[ResolutionSet(resolved, explicitIds, ambiguous, notFound)].
- CollaborationMetricsAgent(MetricReader, Option[ResearcherMetricCache], RetrievalConfig): profile(id, ws, we): MetricsReport; windows(id): Vector[MetricsReport]; rank(metric, ws, we, RankingFilter, limit) and maximumTeams(...): RankingReport(entries: RankedEntry(rank, id, displayName, metricValue, record)). MetricsReport: P_r, C_r, T_r, A_r, M_r, maximumTeamWorkIds, W_r, component id and size, community, bridge: Option[BridgeValue(metric, value, definition)], coreness, metricVersion, runId, metricsId, provenance.
- PathFinderAgent(PathReader, RetrievalConfig, snapshot).find(source, target, ws, we, requestedDepth): PathReport(paths: FoundPath(researcherIds, hops: PathHopReport), depthLimit, resultLimit, timeoutMillis, droppedPaths).
- DirectCoauthorsAgent(GraphReader, EdgeSupportReader, RetrievalConfig, snapshot).page(id, ws, we, cursor): CoauthorPageReport.
- CommunityAnalystAgent(AnalyticsReader, Option[ResearcherMetricCache], RetrievalConfig): analyse and compare: CommunityReport / CommunityComparison; componentOf: core ComponentMembership. CommunityReport has separate directDegree (C_r) and bridge fields; stored counts are Option; gaps lists those not stored.
- SemanticContextAgent(VectorIndex, ObjectStore, ObjectLayout, Embedder, indexNames, RetrievalConfig, maxExcerptCodePoints, maxQueryCodePoints).retrieve(SemanticQuery): SemanticResult(hits: SemanticHit(item: SemanticContextItem, chunkId, provenance, label="SEMANTIC_CONTEXT"), refused: RefusedHit).
- EvidenceBuilderAgent(RetrievalConfig, snapshot).build(queryId, runId, EvidenceInputs): SResult[BuiltEvidence(bundle: EvidenceBundle, stats: EvidenceStats)]. Bundle validated by RecordContract[EvidenceBundle].

ALLOWLISTED OPERATIONS (core QueryOperation, exact-name match)
- RESEARCHER_RANKING, MAXIMUM_TEAM_SIZE, BRIDGE_RANKING, TEMPORAL_COMPARISON: CollaborationMetricsAgent.
- DIRECT_COAUTHORS: DirectCoauthorsAgent.
- SHORTEST_PATH: PathFinderAgent.
- COMPONENT_LOOKUP, COMMUNITY_COMPARISON: CommunityAnalystAgent.
- SEMANTIC_EXPLANATION: SemanticContextAgent.
- TOPIC_FILTER, VENUE_FILTER, INSTITUTION_FILTER: modifiers, no step.
Metrics: the 14 core CollaborationMetric values by exact name. BRIDGE_RANKING requires APPROX_BETWEENNESS, CROSS_COMMUNITY_DEGREE, PARTICIPATION_COEFFICIENT or COMMUNITIES_CONNECTED. Per-operation researcher counts: SHORTEST_PATH exactly 2; DIRECT_COAUTHORS, COMPONENT_LOOKUP, TEMPORAL_COMPARISON at most 10; COMMUNITY_COMPARISON 1 to 10; RESEARCHER_RANKING and BRIDGE_RANKING require a metric. Graph and vector limits are capped by config, never refused.

PROMPT VERSIONS AND SCHEMAS
- Prompt query-planner-prompt-v1 (prompts/query-planner-prompt-v1.prompt.txt).
- Schema query-plan-v1 (prompts/schemas/query-plan-v1.schema.json): model-extractable fields only, additionalProperties=false, enums equal to core enums, maxItems and maxLength caps.
- prompts/manifest.json (prompts-manifest-v1) lists file hashes. rag.planner.promptVersion and rag.planner.schemaVersion select versions.
- JSON Schema validator supports a strict subset and refuses unknown keywords at load.

TEMPLATE IDS USED (storage IDs, via OperationAllowlist)
- topK/<METRIC> and topK/<METRIC>/topics
- directCoauthorsPage/first and directCoauthorsPage/after
- paths/<d>, where d is the planned depth, already capped
No other template ID is produced; a test checks every emitted template ID matches this fixed pattern.
Port operations without template IDs: ResearcherMetricCache.get and listWindows, AnalyticsReader.*, EdgeSupportReader.supportingWorkIds, VectorIndex.query, ObjectStore.getBytes.

RESOLVER CONFIG KEYS
- rag.resolver.minConfidence (0.8), rag.resolver.minMargin (0.1), rag.resolver.maxCandidates (5).
Resolved requires top confidence >= minConfidence AND lead over runner-up >= minMargin. A single candidate must meet minConfidence. Otherwise Ambiguous with up to maxCandidates. Confidence is the stored value, never recomputed.
Other keys consumed: rag.llm.maxRetries, rag.llm.maxOutputTokens, rag.planner.promptVersion, rag.planner.schemaVersion, retrieval.graphLimit, vectorLimit, maxPathDepth, pathResultLimit, pathTimeoutMillis, evidenceMaxItems, contextTokenBudget, coauthorPageSize, fusionPolicyVersion.

VERIFICATION RULES IMPLEMENTED
ClaimVerificationAgent is not in this work order. Enforced here: every evidence item has a core evidence ID checked against the core formula by RecordContract, has provenance, and a textHash matching its text. Items failing the contract are dropped and counted. The bundle is contract-validated before return.

ABSTENTION BEHAVIOR
- Planner returns a typed refusal (NO_OPERATION, PLAN_INCOMPLETE, WINDOW_*, OPERATION_NOT_ALLOWED, etc.) instead of guessing.
- An Ambiguous or NotFound name leaves researcherIds without that researcher.
- A stored count that is not stored becomes a recorded gap.
- A ranking without cache rows is dropped from the bundle and counted.

SERIALIZED EvidenceBundle EXAMPLE
(trimmed; full file written by a test to modules/rag/target/rag-examples/evidence-bundle.json)
{"schemaVersion":"evidence-bundle-v1","bundleId":"bundle:d06245d0…","queryId":"query:f4a5a957…","graphEvidence":[{"schemaVersion":"evidence-v1","evidenceId":"evidence:020a987b…","evidenceType":"METRIC_RECORD","referencedIds":["metrics:11c527a6…"],"workId":null,"sourceUri":"store:researcherMetricCache/researcher#researcher:openalex:36163…/w#2010-2013","chunkId":null,"graphPath":null,"score":null,"textHash":"sha256:44a4d59e…","text":"researcher researcher:openalex:36163… window 2010-2013: P_r=6 C_r=4 T_r=8 A_r=2 M_r=3 W_r=9 component=researcher:openalex:9b694… (size 12) community=researcher:openalex:9b694… bridge=CROSS_COMMUNITY_DEGREE=1 (number of direct coauthors outside the researcher's own community) coreness=2 metricVersion=fixture-metrics-v1 maximumTeamWorks=[work:openalex:e41b020b…]","provenance":{"schemaVersion":"provenance-v1","sourceDataset":"SCHOLARCHAIN","sourceRecordId":"researcher:openalex:36163…","snapshot":"2026-11-01","runId":"run:ba13dc4c…","policyVersion":null,"modelVersion":null,"promptVersion":null,"derivedFrom":[]}}, … MATCH_DECISION item and a GRAPH_PATH item …],"semanticEvidence":[{"schemaVersion":"evidence-v1","evidenceId":"evidence:0ea0ec80…","evidenceType":"RESEARCHER_PROFILE","referencedIds":["chunk:80139b03…"],"chunkId":"chunk:80139b03…","score":0.7606934608906266,"sourceUri":"s3://test-rag/researcher-profile-v1/researcher-profile/researcher:openalex:36163…/06e41b43….json","textHash":"sha256:06e41b43…","text":"Researcher profile for researcher:openalex:36163… the bridge between two communities","provenance":{…}}],"identityConfidence":{"researcher:openalex:9b694…":0.97},"provenance":{"schemaVersion":"provenance-v1","sourceDataset":"SCHOLARCHAIN","sourceRecordId":"query:f4a5a957…","snapshot":"2026-11-01","runId":"run:ba13dc4c…","policyVersion":"fusion-policy-v1","derivedFrom":["evidence:0517109d…","evidence:020a987b…","evidence:eaa38894…","evidence:0ea0ec80…"]}}

ASSUMPTIONS
- DIRECT_COAUTHORS and the EDGE evidence type needed a stage not in the 8 listed; added DirectCoauthorsAgent.
- Evidence ID references: METRIC [metricsId]; Ranking: ordered metricsIds (missing cache row means cited ranking is a true prefix); PATH: hop edge IDs in order with graphPath set; COMMUNITY/COMPONENT: metricsId formula over membership version and run plus communityId or componentId; IDENTITY: stored matchDecisionIds.
- Bundle snapshot is the serving snapshot passed by the caller; stored edges carry no snapshot.
- Core has no SEMANTIC_CONTEXT EvidenceType; semantic items use existing non-graph evidence types so the contract keeps them out of graphEvidence. The label "SEMANTIC_CONTEXT" is carried on SemanticHit.
- Semantic evidence text is the sanitized excerpt and textHash hashes that excerpt; the full stored text hash stays on SemanticContextItem.textHash. Retrieval-time chunk ID need not equal ingest-time chunk ID.
- NameNormalizer.fold is a copy of hadoop Normalize.fold, because rag cannot depend on hadoop.

RISKS
1. Prompts live at repo-root prompts/, read from the filesystem by PromptRepository. The service image must ship that directory and its manifest (build-engineer, service-engineer).
2. maxQuestionCodePoints, maxExcerptCodePoints and maxQueryCodePoints are constructor parameters, not config keys; they need keys from domain-engineer and services wiring.
3. All new agents exercised only against fakes. AnalyticsReader, EdgeSupportReader and ResearcherDirectory are fake-only in storage; Neptune topK and paths templates unverified live (deferred gap list).
4. Test fixture ports for GraphReader, MetricReader and the metric cache are hand-written and mimic storage's refusals; drift in storage's real refusals would not be caught.
5. Topic, venue and institution filters arrive as names and resolve only if the caller holds stable IDs; otherwise Unsupported. No topic or venue directory exists.
6. A ranking is dropped from evidence when the metric cache is absent; no fallback to node-window metrics, by design.
7. Semantic topic filter uses array values in $in; S3 Vectors array semantics unverified.
8. Token estimate is characters/4.
9. The benchmark-query suite does not exist yet; not run. Retrieval and groundedness evaluation still open.
10. ClaimVerificationAgent, cross-stage orchestration, synthesis and audit persistence belong to work order B. The InjectionSpec flow is test-only wiring.

MANUAL ACTION REQUIRED
- Orchestrator: route packaging of prompts/ (risk 1) to build-engineer and service-engineer.
- Orchestrator: route the three extra config keys (risk 2) to domain-engineer.
- The human commits.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: work order B (verification, synthesis, audit), then the benchmark-query suite and retrieval and groundedness evaluation, then code-reviewer
