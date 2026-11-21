package scholarchain.adversarial.er

import scholarchain.core.config.{ConfigLoader, EntityResolutionConfig}
import scholarchain.core.ids.*
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions
import scholarchain.er.adjudication.*
import scholarchain.er.ids.{ErIdType, ExactIdRules}
import scholarchain.er.model.*
import scholarchain.er.policy.*

/** Support code for the entity-resolution adversarial suite (test-engineer, tests directory).
  *
  * Written independently of the entity-resolution module's own test helpers. Thresholds are never
  * hardcoded into production: every policy here is built through the core configuration loader
  * from a test-local environment map (the same path the runner uses), so a change to the
  * configuration contract is exercised too. No gold labels, review-queue keys or evaluation
  * files are read.
  */
object ErAdv:

  def ok[A](e: Either[String, A]): A = e.fold(err => throw new AssertionError(err), identity)

  /** Test-local entity-resolution configuration (er-v1 values from the work order). */
  def erEnv(
      accept: String = "0.80",
      reject: String = "0.45",
      bandMin: String = "0.60",
      bandMax: String = "0.80",
      version: String = "er-v1"
  ): Map[String, String] = Map(
    "SCHOLARCHAIN_ENV" -> "adversarial-er",
    "SCHOLARCHAIN_ER_POLICY_VERSION" -> version,
    "SCHOLARCHAIN_ER_ACCEPT_THRESHOLD" -> accept,
    "SCHOLARCHAIN_ER_REJECT_THRESHOLD" -> reject,
    "SCHOLARCHAIN_ER_LLM_BAND_MIN" -> bandMin,
    "SCHOLARCHAIN_ER_LLM_BAND_MAX" -> bandMax
  )

  def loadConfig(env: Map[String, String]): Either[String, EntityResolutionConfig] =
    ConfigLoader.loadSection(EntityResolutionConfig, env).left.map(_.toString)

  /** The er-v1 policy properties file, the only source of the gating cutoffs (Phase 05
    * repair-01, F7). Repo-relative: adversarial tests fork with the build root as working
    * directory. Cutoff values are never restated here.
    */
  val cutoffsFile: java.nio.file.Path =
    java.nio.file.Paths.get("modules/entity-resolution/config/er-v1-thresholds.properties")

  lazy val cutoffs: DecisionCutoffs = ok(DecisionCutoffs.load(cutoffsFile))

  def policyFrom(env: Map[String, String]): Policy =
    ok(loadConfig(env).flatMap(c => Policy.fromConfig(c, cutoffs)))

  /** er-v1 policy with the configured thresholds accept 0.80, reject 0.45, band [0.60, 0.80). */
  lazy val policy: Policy = policyFrom(erEnv())

  /** A policy whose configuration sends every non-deterministic score to the LLM. */
  lazy val allBandPolicy: Policy =
    policyFrom(erEnv(accept = "1.0", reject = "0.0", bandMin = "0.0", bandMax = "1.0"))

  val runManifest: RunManifest = RunManifest(
    schemaVersion = SchemaVersions.RunManifest,
    pipelineStage = "entity-resolution-adversarial",
    codeVersion = "adversarial",
    inputs = Vector(RunInput(SourceType.SCHOLARCHAIN, "adv", "fixture", "sha256:" + "1" * 64)),
    parameters = Map.empty,
    policyVersions = Map.empty
  )
  lazy val runId: String = ok(StableIds.runId(runManifest))
  val decidedAt = "2026-11-20T00:00:00Z"

  def ctx(p: Policy = policy, adjudication: Option[AdjudicationService] = None): DecisionContext =
    DecisionContext(p, runId, decidedAt, adjudication)

  // --- valid ORCID iDs (checksums verified by the core normalizer) ----------------------------
  val orcidA = "0000-0002-1825-0097"
  val orcidB = "0000-0001-5109-3700"
  val orcidC = "0000-0002-1694-233X"

  def doiWorkId(doi: String): String = ok(StableIds.workId(WorkNamespace.Doi, doi))
  def dblpWorkId(key: String): String = ok(StableIds.workId(WorkNamespace.Dblp, key))
  def mentionId(workId: String, pos: Int): String = ok(StableIds.mentionId(workId, pos))

  /** Normalized identifier map, built through the production normalizers (raw spellings in). */
  def ids(raw: (ErIdType, String)*): Map[ErIdType, Set[String]] =
    raw.foldLeft(Map.empty[ErIdType, Set[String]]) { case (m, (t, r)) =>
      val v = ok(ExactIdRules.normalize(t, r))
      m.updated(t, m.getOrElse(t, Set.empty) + v)
    }

  def work(
      ref: String,
      source: SourceType = SourceType.DBLP,
      title: String = "Scalable graph partitioning for distributed analytics",
      year: Option[Int] = Some(2018),
      authors: Vector[String] = Vector("Wei Zhang", "Li Wang", "Yang Liu"),
      venues: Vector[String] = Vector("VLDB"),
      workIds: Map[ErIdType, Set[String]] = Map.empty,
      references: Set[String] = Set.empty,
      sourceRecordId: String = "rec"
  ): WorkView =
    WorkView(
      ref,
      source,
      sourceRecordId,
      "2026-11-01",
      title,
      year,
      authors,
      venues,
      workIds,
      references
    )

  /** The mention at 1-based `position` of `w`; its name is the work's author name there unless
    * `name` overrides it (to model a second source spelling the same slot differently).
    */
  def mention(
      w: WorkView,
      position: Int,
      name: Option[String] = None,
      researcherIds: Map[ErIdType, Set[String]] = Map.empty,
      affiliations: Vector[String] = Vector.empty
  ): ResearcherView =
    ResearcherView(
      ref = mentionId(w.ref, position),
      source = w.source,
      sourceRecordId = w.sourceRecordId,
      snapshot = w.snapshot,
      name = name.getOrElse(w.authorNames(position - 1)),
      position = Some(position),
      teamSize = Some(w.authorNames.size),
      work = Some(w),
      coauthorNames = w.authorNames.zipWithIndex.collect {
        case (n, i) if i + 1 != position => n
      },
      affiliations = affiliations,
      ids = researcherIds,
      workIds = Map.empty
    )

  def pair(
      a: EntityView,
      b: EntityView,
      outcomes: Vector[ExactIdentifierOutcome] = Vector.empty
  ): PairInput =
    val (l, r) = if a.ref.compareTo(b.ref) < 0 then (a, b) else (b, a)
    PairInput(
      candidateId = ok(StableIds.matchCandidateId(l.kind, l.ref, r.ref, "adv-features")),
      kind = l.kind,
      left = l,
      right = r,
      hadoopFeatures = Map.empty,
      hadoopFeatureVersion = Some("adv-features"),
      exactOutcomes = outcomes,
      snapshot = "2026-11-01"
    )

  def decide(p: PairInput, c: DecisionContext = ctx()): DecisionResult =
    ok(MatchPolicy.decide(p, c))

  /** Human-readable evidence for a failing assertion: outcome, path, score and rules fired. */
  def explain(r: DecisionResult): String =
    s"decision=${r.decision.decision} path=${r.path} score=${"%.4f".format(r.score)} " +
      s"confidence=${r.decision.confidence} reasons=${r.decision.reasonCodes.mkString(",")} " +
      s"rules=${r.decision.rulesFired.mkString(",")}; "

  def decisionJson(r: DecisionResult): String = WireCodec.encode(r.decision)

  /** An adjudicator returning a scripted raw output; counts calls. */
  final class ScriptedAdjudicator(
      output: AdjudicationRequest => Either[String, String],
      val modelVersion: String = "adversarial-scripted-model-v1"
  ) extends Adjudicator:
    @volatile var calls = 0
    @volatile var lastRequest: Option[AdjudicationRequest] = None
    def adjudicate(prompt: Prompt, request: AdjudicationRequest): Either[String, String] =
      calls += 1
      lastRequest = Some(request)
      output(request)

  lazy val prompt: Prompt = ok(Prompt.adjudication)

  def service(
      a: Adjudicator,
      cache: AdjudicationCache = InMemoryAdjudicationCache()
  ): AdjudicationService =
    AdjudicationService(a, cache, prompt, policy.policyVersion)

  def response(
      outcome: ujson.Value,
      candidateIds: Seq[String],
      cited: Seq[String] = Seq("features.coauthorOverlap"),
      confidence: ujson.Value = ujson.Num(0.95),
      reasons: Seq[String] = Seq("COAUTHORS_SHARED")
  ): ujson.Obj =
    ujson.Obj(
      "outcome" -> outcome,
      "candidateIds" -> ujson.Arr.from(candidateIds.map(ujson.Str(_))),
      "citedFields" -> ujson.Arr.from(cited.map(ujson.Str(_))),
      "confidence" -> confidence,
      "reasonCodes" -> ujson.Arr.from(reasons.map(ujson.Str(_)))
    )

  /** An adjudicator that always ACCEPTs, naming only the request's own refs. */
  def acceptingAdjudicator: ScriptedAdjudicator =
    ScriptedAdjudicator(req =>
      Right(
        response(
          "ACCEPT",
          Seq(req.leftRef, req.rightRef).filter(_.nonEmpty),
          cited = Seq("features.nameSimilarity")
        ).render()
      )
    )

  /** A request with plausible fields, for direct schema tests. */
  lazy val sampleRequest: AdjudicationRequest =
    val w1 = work(dblpWorkId("conf/vldb/Zhang18"))
    val w2 = work(doiWorkId("10.14778/3236187.3236190"), SourceType.CROSSREF)
    val p = pair(mention(w1, 1), mention(w2, 1))
    AdjudicationRequests.build(
      p,
      Map("nameSimilarity" -> 1.0, "coauthorOverlap" -> 0.7, "titleSimilarity" -> 0.9),
      0.7
    )

  /** A researcher pair whose configured score falls in the LLM band [0.60, 0.80): same name,
    * one shared coauthor of two, different titles, years and venues. It has the features
    * `nameSimilarity` and `coauthorOverlap`, so outputs citing them are citing real evidence.
    */
  def bandPair: PairInput =
    val w1 = work(
      dblpWorkId("conf/vldb/ZhangWB15"),
      title = "Approximate query answering with sketches",
      year = Some(2015),
      authors = Vector("Wei Zhang", "Li Wang", "Anna Berg"),
      venues = Vector("VLDB")
    )
    val w2 = work(
      dblpWorkId("conf/icde/ZhangWR18"),
      title = "Wireless sensor data cleaning",
      year = Some(2018),
      authors = Vector("Wei Zhang", "Li Wang", "Carlos Ruiz"),
      venues = Vector("ICDE")
    )
    pair(mention(w1, 1), mention(w2, 1))
