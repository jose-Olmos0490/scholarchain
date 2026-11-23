package scholarchain.rag.agents

import scholarchain.core.config.{PlannerConfig, RetrievalConfig}
import scholarchain.core.ids.{IdKind, StableId}
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions as SV
import scholarchain.core.validation.RecordContract
import scholarchain.rag.llm.*
import scholarchain.rag.render.TextSanitizer

/** The structured fields of a request. They are the first source of a plan and win over anything
  * a model extracts from free text. Operation and metric names arrive as wire strings (the REST
  * boundary), so the allowlist is enforced here and a name outside it is rejected.
  */
final case class StructuredFields(
    operations: Vector[String] = Vector.empty,
    researcherNames: Vector[String] = Vector.empty,
    researcherIds: Vector[String] = Vector.empty,
    windowStartYear: Option[Int] = None,
    windowEndYear: Option[Int] = None,
    topics: Vector[String] = Vector.empty,
    venues: Vector[String] = Vector.empty,
    institutions: Vector[String] = Vector.empty,
    rankingMetric: Option[String] = None,
    graphLimit: Option[Int] = None,
    vectorLimit: Option[Int] = None,
    maxPathDepth: Option[Int] = None
)

/** @param queryId
  *   the `query:<sha256>` chosen by the service
  * @param runId
  *   the run that produced the stored graph and metrics being queried
  * @param snapshot
  *   the dataset snapshot that run served
  * @param question
  *   free text; untrusted. It is only used when the structured fields are not sufficient.
  */
final case class PlanRequest(
    queryId: String,
    runId: String,
    snapshot: String,
    question: Option[String],
    fields: StructuredFields
)

/** @param conflicts
  *   fields where the model's value differed from the structured value (the structured value won)
  * @param steps
  *   the allowlisted stage, template IDs and port operations of every non-modifier operation
  */
final case class PlanOutcome(
    plan: QueryPlan,
    steps: Vector[PlannedStep],
    usage: LlmUsage,
    llmUsed: Boolean,
    conflicts: Vector[String]
)

final case class PlanResult(outcome: SResult[PlanOutcome], usage: LlmUsage)

/** Builds a [[QueryPlan]].
  *
  * Order of authority: (1) the request's structured fields; (2) a model only when the structured
  * fields are not sufficient and a question is present. The model returns fields validated
  * against the versioned JSON schema `rag.planner.schemaVersion`; it never returns query text.
  * A field group (operations, researcher names and IDs, window, topics, venues, institutions,
  * metric) is taken whole from the structured fields when they set it, otherwise from the model.
  *
  * Operations and metrics are matched by exact name against the core enums. Anything else is
  * rejected, whichever source it came from. Requested limits are capped by the configured
  * retrieval limits. The question text is never copied into the plan.
  */
final class QueryPlannerAgent(
    planner: PlannerConfig,
    retrieval: RetrievalConfig,
    llm: StructuredLlmCall,
    prompts: PromptRepository,
    maxQuestionCodePoints: Int
):
  /** The plan, or the reason there is none. `usage` is what the model calls cost, also when the
    * plan was refused, so the audit record can count every token.
    */
  def plan(request: PlanRequest): PlanResult =
    var spent = LlmUsage.Zero
    val outcome =
      for
        structured <- validateStructured(request.fields)
        extraction <-
          if sufficient(structured) then Right(None)
          else
            request.question.filter(_.trim.nonEmpty) match
              case Some(q) => extract(q, u => spent = u).map(Some(_))
              case None => Right(None)
        done <- assemble(request, structured, extraction)
      yield done
    PlanResult(outcome, spent)

  // ---- structured fields ------------------------------------------------------------------

  final private case class Checked(
      operations: Vector[QueryOperation],
      names: Vector[String],
      ids: Vector[String],
      window: Option[(Int, Int)],
      topics: Vector[String],
      venues: Vector[String],
      institutions: Vector[String],
      metric: Option[CollaborationMetric],
      f: StructuredFields
  )

  private def parseOps(names: Vector[String]): SResult[Vector[QueryOperation]] =
    names.foldLeft[SResult[Vector[QueryOperation]]](Right(Vector.empty)) { (acc, n) =>
      acc.flatMap(done =>
        OperationAllowlist
          .parseOperation(n)
          .map(op => if done.contains(op) then done else done :+ op)
          .toRight(
            StageFailure.Rejected(
              "OPERATION_NOT_ALLOWED",
              s"an operation outside the allowlist was requested (${n.take(40).filter(c => c.isLetterOrDigit || c == '_')})"
            )
          )
      )
    }

  private def parseMetric(name: Option[String]): SResult[Option[CollaborationMetric]] =
    name match
      case None => Right(None)
      case Some(n) =>
        OperationAllowlist
          .parseMetric(n)
          .map(Some(_))
          .toRight(
            StageFailure
              .Rejected("METRIC_NOT_ALLOWED", "a metric outside the allowlist was requested")
          )

  private def window(start: Option[Int], end: Option[Int]): SResult[Option[(Int, Int)]] =
    (start, end) match
      case (None, None) => Right(None)
      case (Some(s), Some(e)) if s <= e => Right(Some((s, e)))
      case (Some(_), Some(_)) =>
        Left(StageFailure.Rejected("WINDOW_INVALID", "window start is after window end"))
      case _ =>
        Left(
          StageFailure.Rejected(
            "WINDOW_INCOMPLETE",
            "a window needs both a start year and an end year"
          )
        )

  private def validateStructured(f: StructuredFields): SResult[Checked] =
    for
      ops <- parseOps(f.operations)
      metric <- parseMetric(f.rankingMetric)
      w <- window(f.windowStartYear, f.windowEndYear)
      _ <- f.researcherIds
        .find(id => StableId.parseAs(id, Set(IdKind.Researcher)).isLeft)
        .fold[SResult[Unit]](Right(()))(_ =>
          Left(
            StageFailure.Rejected(
              "RESEARCHER_ID_INVALID",
              "a researcher ID is not a well-formed researcher stable ID"
            )
          )
        )
      _ <-
        if List(f.graphLimit, f.maxPathDepth).flatten.exists(_ < 1) || f.vectorLimit.exists(_ < 0)
        then Left(StageFailure.Rejected("LIMIT_INVALID", "a requested limit is below its minimum"))
        else Right(())
    yield Checked(
      ops,
      f.researcherNames,
      f.researcherIds,
      w,
      f.topics,
      f.venues,
      f.institutions,
      metric,
      f
    )

  /** Enough to plan without a model: an operation, and the researchers it needs. */
  private def sufficient(c: Checked): Boolean =
    c.operations.nonEmpty &&
      c.operations.forall { op =>
        c.names.size + c.ids.size >= OperationAllowlist.spec(op).minResearchers
      }

  // ---- model extraction ---------------------------------------------------------------------

  final private case class Extracted(
      operations: Vector[QueryOperation],
      names: Vector[String],
      window: Option[(Int, Int)],
      topics: Vector[String],
      venues: Vector[String],
      institutions: Vector[String],
      metric: Option[CollaborationMetric],
      usage: LlmUsage
  )

  private def strings(o: ujson.Obj, k: String): Vector[String] =
    o(k).arr.toVector.map(_.str)

  private def extract(question: String, spend: LlmUsage => Unit): SResult[Extracted] =
    for
      prompt <- prompts.prompt(planner.promptVersion)
      schema <- prompts.schema(planner.schemaVersion)
      input = ujson
        .Obj("question" -> ujson.Str(TextSanitizer.clean(question, maxQuestionCodePoints)))
        .render()
      called = llm.run(prompt.version, prompt.text, input, schema)
      _ = spend(called.usage)
      json <- called.outcome
      ops <- parseOps(strings(json, "operations"))
      metric <- parseMetric(json("rankingMetric").strOpt)
      w <- window(
        json("windowStartYear").numOpt.map(_.toInt),
        json("windowEndYear").numOpt.map(_.toInt)
      )
    yield Extracted(
      ops,
      strings(json, "researcherNames"),
      w,
      strings(json, "topics"),
      strings(json, "venues"),
      strings(json, "institutions"),
      metric,
      called.usage
    )

  // ---- assembly -----------------------------------------------------------------------------

  private def assemble(
      r: PlanRequest,
      c: Checked,
      x: Option[Extracted]
  ): SResult[PlanOutcome] =
    val conflicts = Vector.newBuilder[String]
    def pick[A](name: String, structured: A, isSet: A => Boolean, model: Option[A]): A =
      if isSet(structured) then
        model.filter(m => isSet(m) && m != structured).foreach(_ => conflicts += name)
        structured
      else model.getOrElse(structured)
    val operations =
      pick[Vector[QueryOperation]]("operations", c.operations, _.nonEmpty, x.map(_.operations))
    val hasResearchers = c.names.nonEmpty || c.ids.nonEmpty
    val names = if hasResearchers then
      x.filter(e => e.names.nonEmpty && e.names != c.names && c.ids.isEmpty)
        .foreach(_ => conflicts += "researcherNames")
      c.names
    else x.map(_.names).getOrElse(Vector.empty)
    val win = pick[Option[(Int, Int)]]("window", c.window, _.isDefined, x.map(_.window))
    val topics = pick[Vector[String]]("topics", c.topics, _.nonEmpty, x.map(_.topics))
    val venues = pick[Vector[String]]("venues", c.venues, _.nonEmpty, x.map(_.venues))
    val institutions =
      pick[Vector[String]]("institutions", c.institutions, _.nonEmpty, x.map(_.institutions))
    val metric =
      pick[Option[CollaborationMetric]]("rankingMetric", c.metric, _.isDefined, x.map(_.metric))

    for
      _ <-
        if operations.nonEmpty then Right(())
        else
          Left(
            StageFailure.Rejected("NO_OPERATION", "no allowlisted operation could be determined")
          )
      _ <- requirements(operations, names.size + c.ids.size, metric, topics, venues, institutions)
      graphLimit = math.min(c.f.graphLimit.getOrElse(retrieval.graphLimit), retrieval.graphLimit)
      vectorLimit = math.min(
        c.f.vectorLimit.getOrElse(retrieval.vectorLimit),
        retrieval.vectorLimit
      )
      depth = math.min(c.f.maxPathDepth.getOrElse(retrieval.maxPathDepth), retrieval.maxPathDepth)
      effective = operations
        .collectFirst {
          case QueryOperation.MAXIMUM_TEAM_SIZE if metric.isEmpty =>
            CollaborationMetric.MAXIMUM_COAUTHORS_ON_ONE_PAPER
        }
        .orElse(metric)
      usage = x.map(_.usage).getOrElse(LlmUsage.Zero)
      prov = Provenance(
        SV.Provenance,
        SourceType.SCHOLARCHAIN,
        r.queryId,
        r.snapshot,
        r.runId,
        None,
        Option.when(usage.calls > 0)(usage.modelVersions.mkString(",")).filter(_.nonEmpty),
        Option.when(usage.calls > 0)(planner.promptVersion),
        Vector.empty
      )
      plan = QueryPlan(
        SV.QueryPlan,
        r.queryId,
        operations,
        names,
        c.ids,
        win.map(_._1),
        win.map(_._2),
        topics,
        venues,
        institutions,
        effective,
        graphLimit,
        vectorLimit,
        depth,
        s"${planner.promptVersion}/${planner.schemaVersion}",
        prov
      )
      _ <- {
        val violations = RecordContract[QueryPlan].violations(plan, "")
        if violations.isEmpty then Right(())
        else
          Left(
            StageFailure.Rejected("PLAN_INVALID", violations.map(_.path).distinct.mkString(", "))
          )
      }
    yield PlanOutcome(
      plan,
      OperationAllowlist.steps(operations, effective, topics.nonEmpty, depth),
      usage,
      x.isDefined,
      conflicts.result()
    )

  private def requirements(
      ops: Vector[QueryOperation],
      researchers: Int,
      metric: Option[CollaborationMetric],
      topics: Vector[String],
      venues: Vector[String],
      institutions: Vector[String]
  ): SResult[Unit] =
    val problems = ops.distinct.flatMap { op =>
      val sp = OperationAllowlist.spec(op)
      Vector(
        Option.when(researchers < sp.minResearchers)(
          s"$op needs at least ${sp.minResearchers} researcher(s)"
        ),
        Option.when(sp.maxResearchers > 0 && researchers > sp.maxResearchers)(
          s"$op takes at most ${sp.maxResearchers} researcher(s)"
        ),
        Option.when(sp.needsMetric && metric.isEmpty)(s"$op needs a ranking metric"),
        Option.when(
          op == QueryOperation.BRIDGE_RANKING && metric.exists(!OperationAllowlist.bridgeMetrics(_))
        )(
          "BRIDGE_RANKING needs a bridge metric"
        ),
        Option.when(sp.needs.contains("topics") && topics.isEmpty)(s"$op needs topics"),
        Option.when(sp.needs.contains("venues") && venues.isEmpty)(s"$op needs venues"),
        Option.when(sp.needs.contains("institutions") && institutions.isEmpty)(
          s"$op needs institutions"
        )
      ).flatten
    }
    if problems.isEmpty then Right(())
    else Left(StageFailure.Rejected("PLAN_INCOMPLETE", problems.mkString("; ")))
