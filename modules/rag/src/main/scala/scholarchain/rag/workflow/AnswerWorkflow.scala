package scholarchain.rag.workflow

import scholarchain.core.ids.{IdKind, StableId, StableIds}
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions as SV
import scholarchain.core.validation.RecordContract
import scholarchain.rag.agents.*
import scholarchain.rag.answer.*
import scholarchain.rag.llm.LlmUsage
import scholarchain.rag.render.TextSanitizer
import scholarchain.storage.common.StorageError
import scholarchain.storage.dynamo.QuerySessionRecord
import upickle.default.write

/** The agents of one workflow, wired once.
  *
  * @param semantic
  *   the semantic stage; its absence refuses SEMANTIC_EXPLANATION as unsupported
  */
final case class WorkflowAgents(
    planner: QueryPlannerAgent,
    resolver: ResearcherResolverAgent,
    metrics: CollaborationMetricsAgent,
    coauthors: DirectCoauthorsAgent,
    paths: PathFinderAgent,
    community: CommunityAnalystAgent,
    semantic: SemanticContextAgent,
    evidence: EvidenceBuilderAgent,
    synthesis: AnswerSynthesisAgent,
    verifier: ClaimVerificationAgent
)

/** The runtime collaboration-analysis workflow:
  *
  * planner, resolver, metrics, coauthors, paths, community, semantic, evidence, synthesis,
  * verification, audit.
  *
  * A trace ID goes through every stage; each stage has a deadline and its latency is recorded.
  * A retryable stage failure ends the workflow as a retryable [[WorkflowFailure]]. A refusal, an
  * ambiguous or unknown researcher, or a plan without deterministic evidence ends it as an
  * abstaining answer, and the model is never called for it. Every answer and abstention is audited
  * once: replaying a query ID is a success that writes nothing twice.
  */
final class AnswerWorkflow(
    agents: WorkflowAgents,
    ports: AuditPorts,
    settings: WorkflowSettings,
    clock: WorkflowClock = SystemWorkflowClock,
    sink: TraceSink = TraceSink.Silent
):
  import AnswerWorkflow.*

  private val runner = new StageRunner(settings.stageTimeoutMillis)

  // ---- public entry points ------------------------------------------------------------------

  /** Everything up to the evidence bundle. Nothing is synthesized, verified or audited. */
  def collect(request: AnswerRequest): Either[WorkflowFailure, Gathered] =
    validate(request).flatMap(_ =>
      val run = new RunTrace(request.traceId, runner, clock, sink)
      gather(run, request)
    )

  /** The whole workflow. */
  def answer(request: AnswerRequest): Either[WorkflowFailure, WorkflowResult] =
    validate(request).flatMap { _ =>
      val run = new RunTrace(request.traceId, runner, clock, sink)
      gather(run, request).flatMap(g =>
        g.abstention match
          case Some(a) => finish(run, request, g, Some(a), g.planUsage, None)
          case None => synthesizeAndVerify(run, request, g)
      )
    }

  // ---- validation ---------------------------------------------------------------------------

  private def fail(
      request: AnswerRequest,
      stage: String,
      f: StageFailure
  ): WorkflowFailure =
    WorkflowFailure(
      if request.traceId == null then "" else request.traceId.take(128),
      stage,
      f,
      Vector.empty,
      LlmUsage.Zero
    )

  private def validate(request: AnswerRequest): Either[WorkflowFailure, Unit] =
    if request.traceId == null || !TraceIdPattern.matches(request.traceId) then
      Left(
        fail(
          request,
          "request",
          StageFailure.Rejected("TRACE_ID_INVALID", "the trace ID is not valid")
        )
      )
    else if StableId.parseAs(request.queryId, Set(IdKind.Query)).isLeft then
      Left(
        fail(
          request,
          "request",
          StageFailure.Rejected("QUERY_ID_INVALID", "the query ID is not a stable query ID")
        )
      )
    else if StableId.parseAs(request.runId, Set(IdKind.Run)).isLeft then
      Left(
        fail(
          request,
          "request",
          StageFailure.Rejected("RUN_ID_INVALID", "the run ID is not a stable run ID")
        )
      )
    else Right(())

  // ---- gathering ----------------------------------------------------------------------------

  private def failAt(run: RunTrace, stage: String, f: StageFailure, usage: LlmUsage) =
    WorkflowFailure(run.traceId, stage, f, run.trace, usage)

  private def abstainable(f: StageFailure): Boolean = f match
    case _: StageFailure.Rejected | _: StageFailure.Unsupported => true
    case _ => false

  private def emptyBundle(
      run: RunTrace,
      request: AnswerRequest,
      inputs: EvidenceInputs,
      usage: LlmUsage
  ): Either[WorkflowFailure, BuiltEvidence] =
    run
      .stage("evidence")(agents.evidence.build(request.queryId, request.runId, inputs))
      .left
      .map(failAt(run, "evidence", _, usage))

  private def abstained(
      run: RunTrace,
      request: AnswerRequest,
      plan: Option[QueryPlan],
      resolution: Option[ResolutionSet],
      window: Option[(Int, Int)],
      inputs: EvidenceInputs,
      gaps: Vector[String],
      usage: LlmUsage,
      abstention: Abstention
  ): Either[WorkflowFailure, Gathered] =
    emptyBundle(run, request, inputs, usage).map(built =>
      Gathered(
        run.traceId,
        plan,
        resolution,
        window,
        built,
        gaps,
        metricVersionsOf(inputs),
        usage,
        Some(abstention),
        run.trace
      )
    )

  private def gather(
      run: RunTrace,
      request: AnswerRequest
  ): Either[WorkflowFailure, Gathered] =
    val planned = run.stage("plan")(
      Right(
        agents.planner.plan(
          PlanRequest(
            request.queryId,
            request.runId,
            request.snapshot,
            request.question,
            request.fields
          )
        )
      )
    )
    planned.left.map(failAt(run, "plan", _, LlmUsage.Zero)).flatMap { pr =>
      pr.outcome match
        case Left(f) if abstainable(f) =>
          abstained(
            run,
            request,
            None,
            None,
            None,
            EvidenceInputs(),
            Vector.empty,
            pr.usage,
            Abstention(f.code, f.detail, Vector.empty, Vector.empty)
          )
        case Left(f) => Left(failAt(run, "plan", f, pr.usage))
        case Right(outcome) => resolveAndRetrieve(run, request, outcome, pr.usage)
    }

  private def resolveAndRetrieve(
      run: RunTrace,
      request: AnswerRequest,
      outcome: PlanOutcome,
      planUsage: LlmUsage
  ): Either[WorkflowFailure, Gathered] =
    val plan = outcome.plan
    val resolved = run.stage("resolve")(
      agents.resolver.resolveAll(plan.researcherNames, plan.researcherIds)
    )
    resolved match
      case Left(f) if abstainable(f) =>
        abstained(
          run,
          request,
          Some(plan),
          None,
          None,
          EvidenceInputs(),
          Vector.empty,
          planUsage,
          Abstention(f.code, f.detail, Vector.empty, Vector.empty)
        )
      case Left(f) => Left(failAt(run, "resolve", f, planUsage))
      case Right(res) if !res.complete =>
        val candidates = res.ambiguous.flatMap(_.candidates)
        val inputs = EvidenceInputs(identities = res.resolved.map(_.candidate) ++ candidates)
        val names = (res.ambiguous.map(_.name) ++ res.notFound.map(_.name)).distinct
        val (code, detail) =
          if res.ambiguous.nonEmpty then
            (
              "AMBIGUOUS_RESEARCHER",
              s"the name(s) ${names.map(ClaimRendering.quote).mkString(", ")} match more than one researcher or none with enough confidence"
            )
          else
            (
              "RESEARCHER_NOT_FOUND",
              s"no researcher was found for ${names.map(ClaimRendering.quote).mkString(", ")}"
            )
        abstained(
          run,
          request,
          Some(plan),
          Some(res),
          None,
          inputs,
          Vector.empty,
          planUsage,
          Abstention(code, detail, candidates, Vector.empty)
        )
      case Right(res) =>
        val window = plan.windowStartYear
          .zip(plan.windowEndYear)
          .headOption
          .orElse(settings.defaultWindow)
        window match
          case None =>
            abstained(
              run,
              request,
              Some(plan),
              Some(res),
              None,
              EvidenceInputs(identities = res.resolved.map(_.candidate)),
              Vector.empty,
              planUsage,
              Abstention(
                "WINDOW_REQUIRED",
                "the question names no time window and none is configured",
                Vector.empty,
                Vector.empty
              )
            )
          case Some(w) => retrieve(run, request, outcome, res, w, planUsage)

  /** Outcome of one retrieval stage: the inputs it added and the gaps it recorded. */
  final private case class Part(inputs: EvidenceInputs, gaps: Vector[String])

  private def recoverable(f: StageFailure): Boolean = f match
    case _: StageFailure.Retryable | _: StageFailure.Failed => false
    case _ => true

  private def gapText(what: String, f: StageFailure): String =
    TextSanitizer.clean(s"$what: ${f.code}: ${f.detail}", 300)

  /** Applies `r` to `part`, or records a gap for a recoverable failure, or aborts the stage. */
  private def absorb[A](what: String, r: SResult[A], part: Part)(
      add: (Part, A) => Part
  ): SResult[Part] =
    r match
      case Right(a) => Right(add(part, a))
      case Left(f) if recoverable(f) => Right(part.copy(gaps = part.gaps :+ gapText(what, f)))
      case Left(f) => Left(f)

  private def foldIds(ids: Vector[String], start: Part)(
      step: (String, Part) => SResult[Part]
  ): SResult[Part] =
    ids.foldLeft[SResult[Part]](Right(start))((acc, id) => acc.flatMap(step(id, _)))

  private def retrieve(
      run: RunTrace,
      request: AnswerRequest,
      outcome: PlanOutcome,
      res: ResolutionSet,
      window: (Int, Int),
      planUsage: LlmUsage
  ): Either[WorkflowFailure, Gathered] =
    val plan = outcome.plan
    val (ws, we) = window
    val ids = res.researcherIds
    val ops = outcome.steps.map(_.operation).toSet
    val filter = RankingFilter(plan.topics, plan.venues, plan.institutions)
    val empty = Part(EvidenceInputs(identities = res.resolved.map(_.candidate)), Vector.empty)

    def metricsStage(p: Part): SResult[Part] =
      val ranked = outcome.steps.map(_.operation).filter { op =>
        op == QueryOperation.RESEARCHER_RANKING || op == QueryOperation.BRIDGE_RANKING ||
        op == QueryOperation.MAXIMUM_TEAM_SIZE
      }
      for
        afterRank <- ranked.foldLeft[SResult[Part]](Right(p)) { (acc, op) =>
          acc.flatMap { cur =>
            val report =
              if op == QueryOperation.MAXIMUM_TEAM_SIZE then
                agents.metrics.maximumTeams(ws, we, filter, plan.graphLimit)
              else
                plan.rankingMetric.fold[SResult[RankingReport]](
                  Left(StageFailure.Rejected("PLAN_INCOMPLETE", s"$op needs a ranking metric"))
                )(m => agents.metrics.rank(m, ws, we, filter, plan.graphLimit))
            absorb(s"metrics/$op", report, cur)((q, r) =>
              q.copy(inputs = q.inputs.copy(rankings = q.inputs.rankings :+ r))
            )
          }
        }
        done <- foldIds(ids, afterRank) { (id, cur) =>
          if ops.contains(QueryOperation.TEMPORAL_COMPARISON) then
            absorb(s"metrics/windows/${shortId(id)}", agents.metrics.windows(id), cur)((q, ws2) =>
              q.copy(inputs = q.inputs.copy(metrics = q.inputs.metrics ++ ws2))
            )
          else
            absorb(s"metrics/profile/${shortId(id)}", agents.metrics.profile(id, ws, we), cur)(
              (q, m) => q.copy(inputs = q.inputs.copy(metrics = q.inputs.metrics :+ m))
            )
        }
      yield done

    def coauthorsStage(p: Part): SResult[Part] =
      if !ops.contains(QueryOperation.DIRECT_COAUTHORS) then Right(p)
      else
        foldIds(ids, p) { (id, cur) =>
          absorb(s"coauthors/${shortId(id)}", agents.coauthors.page(id, ws, we, None), cur)(
            (q, page) => q.copy(inputs = q.inputs.copy(coauthors = q.inputs.coauthors :+ page))
          )
        }

    def pathsStage(p: Part): SResult[Part] =
      if !ops.contains(QueryOperation.SHORTEST_PATH) then Right(p)
      else if ids.size != 2 then
        Right(
          p.copy(gaps = p.gaps :+ "paths: SHORTEST_PATH needs exactly two resolved researchers")
        )
      else
        absorb(
          "paths",
          agents.paths.find(ids(0), ids(1), ws, we, Some(plan.maxPathDepth)),
          p
        )((q, report) =>
          q.copy(
            inputs = q.inputs.copy(paths = q.inputs.paths :+ report),
            gaps =
              if report.paths.isEmpty then
                q.gaps :+ s"paths: no path within ${report.depthLimit} hop(s) in this window"
              else q.gaps
          )
        )

    def communityStage(p: Part): SResult[Part] =
      val withCommunity =
        if !ops.contains(QueryOperation.COMMUNITY_COMPARISON) then Right(p)
        else
          foldIds(ids, p) { (id, cur) =>
            absorb(s"community/${shortId(id)}", agents.community.analyse(id, ws, we), cur)((q, c) =>
              q.copy(inputs = q.inputs.copy(communities = q.inputs.communities :+ c))
            )
          }
      withCommunity.flatMap { afterCommunity =>
        if !ops.contains(QueryOperation.COMPONENT_LOOKUP) then Right(afterCommunity)
        else
          foldIds(ids, afterCommunity) { (id, cur) =>
            absorb(s"component/${shortId(id)}", agents.community.componentOf(id, ws, we), cur)(
              (q, c) => q.copy(inputs = q.inputs.copy(components = q.inputs.components :+ c))
            )
          }
      }

    def semanticStage(p: Part): SResult[Part] =
      if !ops.contains(QueryOperation.SEMANTIC_EXPLANATION) then Right(p)
      else
        val topicIds =
          if plan.topics.nonEmpty &&
            plan.topics.forall(t => StableId.parseAs(t, Set(IdKind.Topic)).isRight)
          then plan.topics
          else Vector.empty
        val text = request.question
          .filter(_.trim.nonEmpty)
          .getOrElse(
            "collaboration profile and community context of the researchers"
          )
        val explicitWindow = plan.windowStartYear.zip(plan.windowEndYear).headOption
        val q = SemanticQuery(
          text,
          explicitWindow.map(_._1),
          explicitWindow.map(_._2),
          ids,
          topicIds,
          plan.vectorLimit
        )
        absorb("semantic", agents.semantic.retrieve(q), p)((cur, s) =>
          cur.copy(inputs = cur.inputs.copy(semantic = s.hits))
        )

    val stages: Vector[(String, Part => SResult[Part])] = Vector(
      "metrics" -> metricsStage,
      "coauthors" -> coauthorsStage,
      "paths" -> pathsStage,
      "community" -> communityStage,
      "semantic" -> semanticStage
    )
    val gathered = stages.foldLeft[Either[WorkflowFailure, Part]](Right(empty)) {
      case (acc, (name, f)) =>
        acc.flatMap(p => run.stage(name)(f(p)).left.map(failAt(run, name, _, planUsage)))
    }
    gathered.flatMap { part =>
      emptyBundle(run, request, part.inputs, planUsage).map { built =>
        val graph = built.bundle.graphEvidence
        val deterministic =
          outcome.steps.map(_.operation).filterNot(_ == QueryOperation.SEMANTIC_EXPLANATION)
        val unmet = deterministic.filterNot(op =>
          EvidenceCoverage.satisfied(
            op,
            OperationAllowlist.effectiveMetric(op, plan.rankingMetric),
            graph
          )
        )
        val unmetGaps = unmet.map(op => s"no deterministic evidence for $op")
        val gaps = (part.gaps ++ unmetGaps).distinct
        val everything = built.bundle.graphEvidence ++ built.bundle.semanticEvidence
        val abstention =
          if deterministic.nonEmpty && unmet.size == deterministic.size then
            Some(
              Abstention(
                "NO_DETERMINISTIC_EVIDENCE",
                "the stores returned no evidence for the requested operation(s)",
                Vector.empty,
                gaps
              )
            )
          else if everything.isEmpty then
            Some(Abstention("NO_EVIDENCE", "no evidence was found", Vector.empty, gaps))
          else None
        Gathered(
          run.traceId,
          Some(plan),
          Some(res),
          Some(window),
          built,
          gaps,
          metricVersionsOf(part.inputs),
          planUsage,
          abstention,
          run.trace
        )
      }
    }

  // ---- synthesis and verification -----------------------------------------------------------

  private def synthesizeAndVerify(
      run: RunTrace,
      request: AnswerRequest,
      g: Gathered
  ): Either[WorkflowFailure, WorkflowResult] =
    val bundle = g.built.bundle
    run.stage("synthesis")(Right(agents.synthesis.synthesize(bundle))) match
      case Left(f) => Left(failAt(run, "synthesis", f, g.planUsage))
      case Right(sr) =>
        val usage = g.planUsage + sr.usage
        sr.outcome match
          case Left(f) => Left(failAt(run, "synthesis", f, usage))
          case Right(answer) =>
            run.stage("verification")(Right(agents.verifier.verify(bundle, answer))) match
              case Left(f) => Left(failAt(run, "verification", f, usage))
              case Right(report) =>
                finish(run, request, g, None, usage, Some((answer, report)))

  // ---- response and audit -------------------------------------------------------------------

  private def modelVersionOf(usage: LlmUsage): Option[String] =
    Option.when(usage.modelVersions.nonEmpty)(usage.modelVersions.mkString(","))

  private def candidateLine(c: CandidateView): String =
    val where =
      if c.affiliations.isEmpty then ""
      else s"; ${c.affiliations.map(ClaimRendering.quote).mkString(" / ")}"
    s"${c.researcherId} (${ClaimRendering.quote(c.displayName)}$where; stored confidence ${ClaimVerificationAgent.show(c.confidence)})"

  private def abstainingResponse(
      request: AnswerRequest,
      g: Gathered,
      a: Abstention,
      usage: LlmUsage
  ): SResult[AnswerResponse] =
    val candidates =
      if a.candidates.isEmpty then ""
      else s" Candidates: ${a.candidates.map(candidateLine).mkString("; ")}."
    val gapsText = if a.gaps.isEmpty then "" else s" Gaps: ${a.gaps.mkString("; ")}."
    val uncertainty = TextSanitizer.clean(
      s"No answer was given (${a.code}): ${a.detail}.$candidates$gapsText",
      4000
    )
    val summary = TextSanitizer.clean(s"No answer: ${a.detail}.", 1500)
    response(request, g, summary, Vector.empty, abstained = true, Some(uncertainty), usage)

  private def response(
      request: AnswerRequest,
      g: Gathered,
      summary: String,
      claims: Vector[AnswerClaim],
      abstained: Boolean,
      uncertainty: Option[String],
      usage: LlmUsage
  ): SResult[AnswerResponse] =
    val r = AnswerResponse(
      SV.AnswerResponse,
      request.queryId,
      summary,
      claims,
      g.built.bundle.bundleId,
      abstained,
      uncertainty,
      Provenance(
        SV.Provenance,
        SourceType.SCHOLARCHAIN,
        request.queryId,
        request.snapshot,
        request.runId,
        g.built.bundle.provenance.policyVersion,
        modelVersionOf(usage),
        Option.when(usage.calls > 0)(settings.synthesis.promptVersion),
        claims.flatMap(_.evidenceIds).distinct
      )
    )
    val violations = RecordContract[AnswerResponse].violations(r, "")
    if violations.isEmpty then Right(r)
    else
      Left(StageFailure.Rejected("ANSWER_INVALID", violations.map(_.path).distinct.mkString(", ")))

  private def finish(
      run: RunTrace,
      request: AnswerRequest,
      g: Gathered,
      abstention: Option[Abstention],
      usage: LlmUsage,
      synthesized: Option[(SynthesizedAnswer, VerificationReport)]
  ): Either[WorkflowFailure, WorkflowResult] =
    val bundle = g.built.bundle
    val built: SResult[Assembled] =
      (abstention, synthesized) match
        case (Some(a), _) =>
          abstainingResponse(request, g, a, usage).map(r =>
            Assembled(r, Vector.empty, VerificationStatus.UNSUPPORTED, Some(a), Vector.empty)
          )
        case (None, Some((_, report))) => assemble(request, g, usage, report)
        case _ => Left(StageFailure.Failed("answer", "nothing to answer with"))
    built.left.map(failAt(run, "answer", _, usage)).flatMap { asm =>
      val resp = asm.response
      val latency = run.elapsedMillis
      val record =
        auditRecord(request, g, resp, asm.overall, usage, latency, asm.interpretations)
      run
        .stage("audit")(persist(request, bundle, record))
        .left
        .map(failAt(run, "audit", _, usage))
        .map { _ =>
          WorkflowResult(
            run.traceId,
            resp,
            bundle,
            asm.removed,
            asm.overall,
            asm.abstention,
            record,
            usage,
            run.trace,
            asm.interpretations
          )
        }
    }

  /** The answer after verification: the response, and what was left out of it. */
  final private case class Assembled(
      response: AnswerResponse,
      removed: Vector[RemovedClaim],
      overall: VerificationStatus,
      abstention: Option[Abstention],
      interpretations: Vector[Interpretation]
  )

  private def assemble(
      request: AnswerRequest,
      g: Gathered,
      usage: LlmUsage,
      report: VerificationReport
  ): SResult[Assembled] =
    val bundle = g.built.bundle
    val itemFacts = EvidenceFacts.ofBundle(bundle)
    val label = ClaimRendering.labeller(ClaimRendering.namesOf(itemFacts))
    // what is shown is what was rendered: a text that does not survive normalization unchanged
    // is not shown
    def stable(t: String): Boolean = ShownText.normalize(t, Int.MaxValue) == t
    val facts = report.kept.filter(v => v.display.nonEmpty && stable(v.display))
    val interpretations = facts.flatMap(v =>
      v.interpretation
        .filter(stable)
        .map(Interpretation(v.ordinal, _, v.claim.evidenceIds.distinct))
    )
    val removed = report.verdicts
      .filterNot(facts.contains)
      .map(v => RemovedClaim(v.ordinal, ClaimRendering.requested(v.claim), v.status, v.reasons))
    if facts.isEmpty then
      val a = Abstention(
        "NO_VERIFIED_CLAIMS",
        s"none of the ${report.verdicts.size} claim(s) could be verified against the evidence",
        Vector.empty,
        g.gaps
      )
      abstainingResponse(request, g, a, usage).map(r =>
        Assembled(r, removed, report.overall, Some(a), Vector.empty)
      )
    else
      val prov = Provenance(
        SV.Provenance,
        SourceType.SCHOLARCHAIN,
        request.queryId,
        request.snapshot,
        request.runId,
        Some(VerificationPolicyVersion),
        modelVersionOf(usage),
        Some(settings.synthesis.promptVersion),
        Vector.empty
      )
      val (claimsOut, idFailed) =
        AnswerWorkflow.claimsOf(request.queryId, facts, prov, StableIds.claimId)
      val removedAll = removed ++ idFailed
      // the summary and the uncertainty note are written here from verified assertions, the
      // recorded gaps and the stored identity confidence; no model text enters either
      val subjects = facts.flatMap(_.verified.map(_.assertion.subjectId)).distinct
      val windows = facts.flatMap(_.verified.flatMap(_.window)).distinct
      val summary = AnswerWorkflow.summaryOf(
        claimsOut.map(_.verificationStatus),
        idFailed.size,
        subjects,
        windows,
        label
      )
      val partial = facts.flatMap(_.uncertainty).distinct
      val lowIdentity = bundle.identityConfidence.toVector
        .sortBy(_._1)
        .filter((_, c) => c.isNaN || c < 1.0)
        .map((id, c) => s"${label(id)} = ${ClaimVerificationAgent.show(c)}")
      val parts = Vector(
        Option.when(lowIdentity.nonEmpty)(
          s"Stored identity confidence is below 1: ${lowIdentity.take(5).mkString("; ")}."
        ),
        Option.when(g.gaps.nonEmpty)(s"Gaps in the evidence: ${g.gaps.take(10).mkString("; ")}."),
        Option.when(removed.nonEmpty)(
          s"${removed.size} claim(s) were removed because the evidence did not support them."
        ),
        Option.when(idFailed.nonEmpty)(
          s"${idFailed.size} claim(s) could not be given an ID and are not shown."
        ),
        Option.when(partial.nonEmpty)(
          s"Some claims are only partly supported: ${partial.take(5).mkString("; ")}."
        ),
        Option.when(interpretations.nonEmpty)(
          s"Interpretation, not a verified fact (fixed template, ${InterpretationTemplates.Version}): " +
            s"${interpretations.take(5).map(i => s"'${i.text}'").mkString("; ")}"
        )
      ).flatten
      val uncertainty =
        Option.when(parts.nonEmpty)(TextSanitizer.clean(parts.mkString(" "), 4000))
      response(request, g, summary, claimsOut, abstained = false, uncertainty, usage).map(r =>
        Assembled(
          r,
          removedAll,
          if idFailed.nonEmpty && report.overall == VerificationStatus.VERIFIED then
            VerificationStatus.PARTIALLY_SUPPORTED
          else report.overall,
          None,
          interpretations
        )
      )

  private def auditRecord(
      request: AnswerRequest,
      g: Gathered,
      resp: AnswerResponse,
      overall: VerificationStatus,
      usage: LlmUsage,
      latencyMillis: Long,
      interpretations: Vector[Interpretation]
  ): QueryAuditRecord =
    val planJson = g.plan match
      case Some(p) => write(p)
      case None =>
        ujson
          .Obj(
            "refusal" -> ujson.Obj(
              "stage" -> "plan",
              "code" -> g.abstention.map(_.code).getOrElse("NONE")
            )
          )
          .render()
    val planPrompt = g.plan.flatMap(_.provenance.promptVersion)
    val prompts = (planPrompt.toVector ++ Option.when(
      usage.calls > g.planUsage.calls
    )(settings.synthesis.promptVersion)).distinct
    QueryAuditRecord(
      SV.QueryAuditRecord,
      request.queryId,
      request.runId,
      planJson,
      g.resolution.map(_.researcherIds).getOrElse(Vector.empty),
      (resp.claims.flatMap(_.evidenceIds) ++ interpretations.flatMap(_.evidenceIds)).distinct,
      g.metricVersions,
      prompts,
      usage.modelVersions,
      usage.inputTokens,
      usage.outputTokens,
      latencyMillis,
      overall,
      AnswerHash.of(resp),
      Provenance(
        SV.Provenance,
        SourceType.SCHOLARCHAIN,
        request.queryId,
        request.snapshot,
        request.runId,
        Some(VerificationPolicyVersion),
        modelVersionOf(usage),
        Option.when(prompts.nonEmpty)(prompts.mkString(",")),
        Vector.empty
      )
    )

  /** Appends the audit record. When the query already has one, the stored record is loaded: the
    * replay succeeds only if its answer hashes the same as the stored one. A replay that words
    * the answer differently is an [[StageFailure.IdempotencyConflict]]: the new answer is never
    * returned, because no audit record describes it.
    */
  private def appendAudit(
      record: QueryAuditRecord,
      idempotent: (String, Either[StorageError, Unit]) => SResult[Unit]
  ): SResult[Unit] =
    ports.audit.append(record) match
      case Left(_: StorageError.AlreadyExists) =>
        ports.audit.get(record.queryId) match
          case Right(Some(stored)) if stored.answerHash == record.answerHash => Right(())
          case Right(Some(_)) =>
            Left(
              StageFailure.IdempotencyConflict(
                "this query ID has an audit record for a different answer"
              )
            )
          case Right(None) =>
            Left(
              StageFailure.Retryable(
                "queryAuditLog.get",
                "the audit record that blocked the write could not be read back"
              )
            )
          case Left(e) => Left(StageFailure.fromStorage("queryAuditLog.get", e))
      case other => idempotent("queryAuditLog.append", other)

  /** Writes the bundle, the session and the audit record. A bundle or session that already exists
    * is the same query replayed, which is a success: nothing is written twice and nothing is
    * overwritten. The audit record is checked as described at [[appendAudit]].
    */
  private def persist(
      request: AnswerRequest,
      bundle: EvidenceBundle,
      record: QueryAuditRecord
  ): SResult[Unit] =
    def idempotent[A](what: String, r: Either[StorageError, A]): SResult[Unit] = r match
      case Right(_) => Right(())
      case Left(_: StorageError.AlreadyExists) => Right(())
      case Left(StorageError.DuplicateIdempotencyKey(_, existing)) if existing == request.queryId =>
        Right(())
      case Left(_: StorageError.DuplicateIdempotencyKey) =>
        Left(
          StageFailure.Rejected(
            "IDEMPOTENCY_KEY_REUSED",
            "the Idempotency-Key belongs to another query"
          )
        )
      case Left(_: StorageError.ConflictingContent) =>
        Left(
          StageFailure.Rejected(
            "EVIDENCE_CONFLICT",
            s"$what holds different content for this query"
          )
        )
      case Left(e) => Left(StageFailure.fromStorage(what, e))
    val session = QuerySessionRecord(
      "query-session-v1",
      request.queryId,
      request.idempotencyKey,
      request.requestFingerprint,
      clock.epochSeconds,
      record.provenance.copy(derivedFrom = Vector.empty)
    )
    for
      _ <- idempotent("evidenceStore.putBundle", ports.evidence.putBundle(bundle))
      _ <- idempotent("querySessions.create", ports.sessions.create(session))
      _ <- appendAudit(record, idempotent)
    yield ()

  // ---- helpers ------------------------------------------------------------------------------

  private def metricVersionsOf(in: EvidenceInputs): Vector[String] =
    (in.metrics.map(_.metricVersion) ++
      in.rankings.flatMap(_.entries.flatMap(_.record.map(_.metricVersion))) ++
      in.communities.map(_.membership.metricVersion) ++
      in.components.map(_.metricVersion)).distinct.filter(_.nonEmpty)

object AnswerWorkflow:
  /** The version of the verification rules; recorded in the provenance of every answer. v2: the
    * model writes no text; code renders every sentence; path-length completeness; windows must
    * equal the stored window.
    */
  val VerificationPolicyVersion = "claim-verification-v2"

  /** The answer claims of the kept verdicts. A claim whose ID cannot be made is not shown and is
    * returned as a removed claim with a typed reason: it is never dropped silently.
    */
  private[rag] def claimsOf(
      queryId: String,
      facts: Vector[ClaimVerdict],
      prov: Provenance,
      idOf: (String, Int) => Either[String, String]
  ): (Vector[AnswerClaim], Vector[RemovedClaim]) =
    val results = facts.zipWithIndex.map { (v, i) =>
      idOf(queryId, i) match
        case Right(cid) =>
          val ids = v.claim.evidenceIds.distinct
          Right(
            AnswerClaim(
              SV.AnswerClaim,
              cid,
              v.display,
              ids,
              v.status,
              v.uncertainty.map(TextSanitizer.clean(_, 500)),
              prov.copy(sourceRecordId = cid, derivedFrom = ids)
            )
          )
        case Left(why) =>
          Left(
            RemovedClaim(
              v.ordinal,
              ClaimRendering.requested(v.claim),
              VerificationStatus.UNSUPPORTED,
              Vector(s"CLAIM_ID_FAILED: ${TextSanitizer.clean(why, 200)}")
            )
          )
    }
    (results.collect { case Right(c) => c }, results.collect { case Left(r) => r })

  /** The headline, written by code. Only VERIFIED claims are counted as verified; partly
    * supported claims are counted and labeled separately.
    */
  private[rag] def summaryOf(
      statuses: Vector[VerificationStatus],
      idFailed: Int,
      subjects: Vector[String],
      windows: Vector[Window],
      label: String => String
  ): String =
    val verified = statuses.count(_ == VerificationStatus.VERIFIED)
    val partly = statuses.count(_ == VerificationStatus.PARTIALLY_SUPPORTED)
    TextSanitizer.clean(
      s"$verified claim(s) verified against stored evidence" +
        (if partly > 0 then s"; $partly claim(s) only partly supported (labeled below)" else "") +
        (if idFailed > 0 then s"; $idFailed claim(s) not shown (no claim ID)" else "") +
        " about " +
        subjects.take(5).map(label).mkString(", ") +
        (if subjects.size > 5 then s" and ${subjects.size - 5} more" else "") +
        (if windows.isEmpty then ""
         else s"; window ${windows.map(w => s"${w.start}-${w.end}").mkString(", ")}") +
        ". Each claim is listed below with its evidence.",
      1500
    )

  private val TraceIdPattern = "[A-Za-z0-9._:-]{1,128}".r

  private def shortId(id: String): String = id.takeRight(12)
