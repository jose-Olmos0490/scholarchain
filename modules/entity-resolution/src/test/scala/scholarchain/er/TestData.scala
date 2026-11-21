package scholarchain.er

import java.nio.file.{Files, Path, Paths}
import scholarchain.core.ids.*
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions
import scholarchain.er.adjudication.*
import scholarchain.er.ids.ErIdType
import scholarchain.er.model.*
import scholarchain.er.policy.*

/** Hand-built fixtures for entity-resolution tests. No gold labels are used anywhere. */
object TestData:

  def ok[A](e: Either[String, A]): A = e.fold(err => throw new AssertionError(err), identity)

  val runManifest: RunManifest = RunManifest(
    schemaVersion = SchemaVersions.RunManifest,
    pipelineStage = "entity-resolution-test",
    codeVersion = "test",
    inputs = Vector(RunInput(SourceType.SCHOLARCHAIN, "test", "fixture", "sha256:" + "0" * 64)),
    parameters = Map.empty,
    policyVersions = Map.empty
  )
  val runId: String = ok(StableIds.runId(runManifest))
  val decidedAt = "2026-11-20T00:00:00Z"

  val parameters: PolicyParameters = ok(Policy.loadParameters("er-v1"))

  /** Thresholds used by most tests (chosen for tests only; production values are configuration).
    */
  val thresholds: Thresholds = Thresholds(
    acceptThreshold = 0.80,
    rejectThreshold = 0.45,
    llmBandMin = 0.60,
    llmBandMax = 0.80
  )

  /** The module's real er-v1 properties file (`config/er-v1-thresholds.properties`), found by
    * walking up from the compiled test classes to the module directory, so the tests do not
    * depend on the working directory.
    */
  lazy val erPropertiesFile: Path =
    val start = Paths.get(getClass.getProtectionDomain.getCodeSource.getLocation.toURI)
    Iterator
      .iterate(start)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("config").resolve("er-v1-thresholds.properties"))
      .find(Files.isRegularFile(_))
      .getOrElse(throw new AssertionError(s"er-v1-thresholds.properties not found above $start"))

  /** Cutoffs read from the real er-v1 properties file (no value is restated in the tests). */
  lazy val cutoffs: DecisionCutoffs = ok(DecisionCutoffs.load(erPropertiesFile))

  lazy val policy: Policy = Policy(parameters, thresholds, cutoffs)

  def ctx(
      th: Thresholds = thresholds,
      adjudication: Option[AdjudicationService] = None
  ): DecisionContext =
    DecisionContext(Policy(parameters, th, cutoffs), runId, decidedAt, adjudication)

  def doiWorkId(doi: String): String = ok(StableIds.workId(WorkNamespace.Doi, doi))
  def dblpWorkId(key: String): String = ok(StableIds.workId(WorkNamespace.Dblp, key))
  def oaWorkId(w: String): String = ok(StableIds.workId(WorkNamespace.OpenAlex, w))
  def mentionId(workId: String, pos: Int): String = ok(StableIds.mentionId(workId, pos))

  def work(
      ref: String,
      source: SourceType = SourceType.DBLP,
      title: String = "Robust gate sizing by geometric programming",
      year: Option[Int] = Some(2005),
      authors: Vector[String] = Vector("Jaskirat Singh", "Vidyasagar Nookala", "Zhi-Quan Luo"),
      venues: Vector[String] = Vector("DAC"),
      ids: Map[ErIdType, Set[String]] = Map.empty,
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
      ids,
      references
    )

  def mention(
      w: WorkView,
      position: Int,
      name: String,
      ids: Map[ErIdType, Set[String]] = Map.empty,
      affiliations: Vector[String] = Vector.empty
  ): ResearcherView =
    ResearcherView(
      ref = mentionId(w.ref, position),
      source = w.source,
      sourceRecordId = w.sourceRecordId,
      snapshot = w.snapshot,
      name = name,
      position = Some(position),
      teamSize = Some(w.authorNames.size),
      work = Some(w),
      coauthorNames = w.authorNames.zipWithIndex.collect {
        case (n, i) if i + 1 != position => n
      },
      affiliations = affiliations,
      ids = ids,
      workIds = Map.empty
    )

  def pair(
      a: EntityView,
      b: EntityView,
      outcomes: Vector[ExactIdentifierOutcome] = Vector.empty
  ): PairInput =
    val (l, r) = if a.ref.compareTo(b.ref) < 0 then (a, b) else (b, a)
    val kind = l.kind
    PairInput(
      candidateId = ok(StableIds.matchCandidateId(kind, l.ref, r.ref, "test-features")),
      kind = kind,
      left = l,
      right = r,
      hadoopFeatures = Map("surnameEqual" -> 1.0),
      hadoopFeatureVersion = Some("test-features"),
      exactOutcomes = outcomes,
      snapshot = "2026-11-01"
    )

  /** A researcher pair on two different works with a mid-range score (no shared identifiers). */
  def borderlineResearcherPair: PairInput =
    val w1 = work(
      dblpWorkId("conf/dac/A01"),
      title = "Gate sizing with convex optimization",
      year = Some(2001),
      authors = Vector("J. Singh", "Ann Other", "Bo Third")
    )
    val w2 = work(
      dblpWorkId("conf/iccad/B09"),
      title = "Timing closure for low power DSP",
      year = Some(2009),
      authors = Vector("Jaskirat Singh", "Carl Fourth", "Dee Fifth"),
      venues = Vector("ICCAD")
    )
    pair(mention(w1, 1, "J. Singh"), mention(w2, 1, "Jaskirat Singh"))

  def prov(snapshot: String = "2026-11-01"): Provenance = Provenance(
    SchemaVersions.Provenance,
    SourceType.SCHOLARCHAIN,
    "fixture",
    snapshot,
    runId,
    None,
    None,
    None,
    Vector.empty
  )

  /** A scripted adjudicator returning a fixed raw output and counting calls. */
  final class ScriptedAdjudicator(
      output: AdjudicationRequest => Either[String, String],
      val modelVersion: String = "scripted-test-model-v1"
  ) extends Adjudicator:
    var calls = 0
    def adjudicate(prompt: Prompt, request: AdjudicationRequest): Either[String, String] =
      calls += 1
      output(request)

  def prompt: Prompt = ok(Prompt.adjudication)

  def service(
      a: Adjudicator,
      cache: AdjudicationCache = InMemoryAdjudicationCache()
  ): AdjudicationService =
    AdjudicationService(a, cache, prompt, "er-v1")

  def response(
      outcome: String,
      ids: Seq[String],
      cited: Seq[String] = Seq("features.nameSimilarity"),
      confidence: Double = 0.9,
      reasons: Seq[String] = Seq("COAUTHORS_SHARED")
  ): String =
    ujson
      .Obj(
        "outcome" -> outcome,
        "candidateIds" -> ujson.Arr.from(ids.map(ujson.Str(_))),
        "citedFields" -> ujson.Arr.from(cited.map(ujson.Str(_))),
        "confidence" -> confidence,
        "reasonCodes" -> ujson.Arr.from(reasons.map(ujson.Str(_)))
      )
      .render()
