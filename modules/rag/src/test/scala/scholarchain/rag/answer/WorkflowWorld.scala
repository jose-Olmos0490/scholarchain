package scholarchain.rag.answer

import java.nio.file.Paths
import scala.collection.mutable
import scholarchain.core.config.RagConfig
import scholarchain.core.ids.StableIds
import scholarchain.core.model.*
import scholarchain.rag.RagFixtures.*
import scholarchain.rag.{RagSuite, SmokeEnv}
import scholarchain.rag.agents.*
import scholarchain.rag.llm.*
import scholarchain.rag.workflow.*
import scholarchain.storage.common.*
import scholarchain.storage.dynamo.{DynamoPorts, InMemoryItemStore}
import scholarchain.storage.graph.*
import scholarchain.storage.directory.ResearcherDirectory

/** A clock that advances a fixed step on every reading, so stage latencies are exact. */
final class TickingClock(stepMillis: Long) extends WorkflowClock:
  private var now = 0L
  def nanos: Long = synchronized { now += stepMillis * 1_000_000L; now }
  def epochSeconds: Long = 1_700_000_000L

/** One assertion as a test writes it. `value` is a JSON number, string or null. */
final case class A(
    kind: String,
    subject: String,
    field: String,
    value: ujson.Value,
    window: Option[(Int, Int)] = Some((World.WS, World.WE)),
    obj: Option[String] = None
)

/** The fixture world wired into the production workflow, with fakes at every port. */
trait WorkflowWorld extends SemanticWorld:
  self: RagSuite =>
  import World.*

  protected lazy val promptRepo: PromptRepository = PromptRepository
    .load(PromptRepository.locate(Paths.get("")).getOrElse(fail("prompts/ not found")))
    .fold(f => fail(f.message), identity)

  protected val synthCfg: SynthesisConfig =
    SynthesisConfig("answer-synthesis-prompt-v3", "answer-synthesis-v2")

  def queryIdOf(name: String): String = ok(StableIds.queryId(name))

  def request(
      fields: StructuredFields,
      question: Option[String] = Some("question"),
      name: String = "q1",
      traceId: String = "trace-1",
      idempotencyKey: Option[String] = None
  ): AnswerRequest =
    AnswerRequest(
      traceId,
      queryIdOf(name),
      runId,
      snapshot,
      question,
      fields,
      idempotencyKey,
      s"fp-$name"
    )

  /** The standard window of the fixture. */
  def fields(
      ops: Seq[String],
      names: Seq[String] = Seq.empty,
      ids: Seq[String] = Seq.empty,
      metric: Option[String] = None,
      window: Boolean = true,
      topics: Seq[String] = Seq.empty
  ): StructuredFields =
    StructuredFields(
      operations = ops.toVector,
      researcherNames = names.toVector,
      researcherIds = ids.toVector,
      windowStartYear = Option.when(window)(WS),
      windowEndYear = Option.when(window)(WE),
      rankingMetric = metric,
      topics = topics.toVector
    )

  final class Rig(
      llm: LlmClient,
      val items: InMemoryItemStore = new InMemoryItemStore,
      val spy: Spy = new Spy,
      timeoutMillis: Long = 10000L,
      clock: WorkflowClock = SystemWorkflowClock,
      pathTimeout: Boolean = false,
      defaultWindow: Option[(Int, Int)] = Some((WS, WE)),
      wrapMetrics: MetricReader => MetricReader = identity,
      extraNames: ResearcherDirectory => ResearcherDirectory = identity
  ):
    val metricCalls = mutable.ArrayBuffer.empty[String]
    val dynamo: DynamoPorts =
      DynamoPorts(
        items,
        SmokeEnv.settings.runtime.dynamoTables,
        SmokeEnv.settings.runtime.querySession.ttlSeconds
      )
    private val pathLimits = mutable.ArrayBuffer.empty[PathQueryLimits]
    private val graph = spy.graph(new FakeGraph(pathLimits, pathTimeout))
    private val cache = spy.cache(new FakeCache(metricRows))
    private val dir = spy.directory(extraNames(directory()))
    private val ana = spy.analytics(analytics())
    val structured = new StructuredLlmCall(llm, llmCfg)
    private val deps = WorkflowDependencies(
      llm = llm,
      prompts = promptRepo,
      embedder = embedder,
      audit = AuditPorts(dynamo.querySessions, dynamo.evidence, dynamo.queryAudit),
      directory = dir,
      metrics = wrapMetrics(new FakeMetrics(metricRows, metricCalls)),
      metricCache = Some(cache),
      graph = graph,
      edgeSupport = support(),
      pathReader = new SupportedPathReader(graph, support()),
      analytics = ana,
      vectors = spy.vectors(rig.index),
      objects = rig.objects,
      layout = rig.layout,
      indexNames = allIndexes
    )
    private val tuning = WorkflowTuning(
      rag = RagConfig(resolverCfg, llmCfg, plannerCfg),
      retrieval = retrieval,
      synthesis = synthCfg,
      snapshot = snapshot,
      stageTimeoutMillis = timeoutMillis,
      defaultWindow = defaultWindow,
      plannerMaxQueryCodePoints = 500,
      maxExcerptCodePoints = 200,
      maxSemanticQueryCodePoints = 300
    )
    // built through the production composition entry
    val agents: WorkflowAgents = WorkflowComposition.agents(deps, tuning)
    val workflow: AnswerWorkflow = WorkflowComposition.workflow(deps, tuning, clock)

  /** The evidence the stores give for a request (no model is called). */
  def gather(r: AnswerRequest): Gathered =
    new Rig(new ScriptedLlm(Seq.empty)).workflow.collect(r).fold(f => fail(f.message), identity)

  def bundleOf(r: AnswerRequest): EvidenceBundle = gather(r).built.bundle

  def itemsOf(b: EvidenceBundle): Vector[EvidenceItem] = b.graphEvidence ++ b.semanticEvidence

  def find(b: EvidenceBundle, pred: EvidenceItem => Boolean): EvidenceItem =
    itemsOf(b).find(pred).getOrElse(fail(s"no evidence item matches in ${itemsOf(b).map(_.text)}"))

  def profileOf(b: EvidenceBundle, n: Int): EvidenceItem =
    find(
      b,
      i =>
        i.evidenceType == EvidenceType.METRIC_RECORD &&
          i.text.exists(_.startsWith(s"researcher ${r(n)} window"))
    )

  def edgeOf(b: EvidenceBundle, x: Int, y: Int): EvidenceItem =
    find(
      b,
      i => i.evidenceType == EvidenceType.COAUTHOR_EDGE && i.referencedIds == Vector(edgeId(x, y))
    )

  def pathItem(b: EvidenceBundle): EvidenceItem = find(b, _.evidenceType == EvidenceType.GRAPH_PATH)

  def rankingOf(b: EvidenceBundle, metric: String): EvidenceItem =
    find(b, _.text.exists(_.startsWith(s"ranking by $metric ")))

  // ---- scripted model replies -------------------------------------------------------------

  private def window(w: Option[(Int, Int)]): ujson.Value =
    w.fold[ujson.Value](ujson.Null)((s, e) => ujson.Obj("start" -> s, "end" -> e))

  def assertionJson(a: A): ujson.Value = ujson.Obj(
    "kind" -> a.kind,
    "subjectId" -> a.subject,
    "objectId" -> a.obj.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
    "field" -> a.field,
    "value" -> a.value,
    "window" -> window(a.window)
  )

  /** One claim as the model sends it. `note` only documents the test: the model writes no claim
    * text, so nothing of it is sent.
    */
  def claim(note: String, evidence: Seq[String], assertions: A*): ujson.Value =
    claimWith(None, evidence, assertions*)

  def claimWith(template: Option[String], evidence: Seq[String], assertions: A*): ujson.Value =
    ujson.Obj(
      "evidenceIds" -> ujson.Arr.from(evidence.map(ujson.Str(_))),
      "assertions" -> ujson.Arr.from(assertions.map(assertionJson)),
      "interpretation" -> template.fold[ujson.Value](ujson.Null)(ujson.Str(_))
    )

  def synthJson(claims: Seq[ujson.Value]): String =
    ujson.Obj("claims" -> ujson.Arr.from(claims)).render()

  def synthReply(claims: Seq[ujson.Value]): Either[LlmFailure, LlmResponse] =
    ScriptedLlm.reply(synthJson(claims), 400, 120)

  def answerOf(claims: Seq[ujson.Value]): SynthesizedAnswer =
    SynthesizedAnswer
      .fromJson(ujson.read(synthJson(claims)).obj)
      .getOrElse(fail("the scripted answer does not parse"))

  /** The verdict of the verifier on one scripted claim. */
  def verdictOf(b: EvidenceBundle, c: ujson.Value): ClaimVerdict =
    new ClaimVerificationAgent().verify(b, answerOf(Seq(c))).verdicts.head

  /** The text a person reads for a verified claim: rendered by code from its verified assertions. */
  def shownOf(b: EvidenceBundle, c: ujson.Value): String =
    val v = verdictOf(b, c)
    withClue(s"claim not kept: ${v.reasons}")(v.kept shouldBe true)
    v.display

  def n(d: Double): ujson.Value = ujson.Num(d)
  def s(x: String): ujson.Value = ujson.Str(x)

  /** The standard request and its evidence: Ada Example's direct coauthors. */
  val adaFields: StructuredFields =
    fields(Seq("DIRECT_COAUTHORS"), names = Seq("Ada Example"))

  def adaClaims(b: EvidenceBundle): (ujson.Value, ujson.Value) =
    val profile = profileOf(b, 1)
    val edge = edgeOf(b, 1, 3)
    (
      claim(
        "Ada Example has many distinct coauthors.",
        Seq(profile.evidenceId),
        A("METRIC", r(1), "DISTINCT_COAUTHOR_COUNT", n(4))
      ),
      claim(
        "Ada Example and a coauthor share joint publications.",
        Seq(edge.evidenceId),
        A("COAUTHOR_EDGE", r(1), "JOINT_PUBLICATION_COUNT", n(3), obj = Some(r(3)))
      )
    )
