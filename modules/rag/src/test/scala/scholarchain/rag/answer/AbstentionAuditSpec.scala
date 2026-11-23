package scholarchain.rag.answer

import scholarchain.core.model.*
import scholarchain.core.validation.RecordContract
import scholarchain.rag.RagFixtures.*
import scholarchain.rag.{RagSuite, SmokeEnv}
import scholarchain.rag.agents.*
import scholarchain.rag.workflow.*
import scholarchain.storage.common.*
import scholarchain.storage.dynamo.InMemoryItemStore
import scholarchain.storage.graph.*
import upickle.default.read

/** Abstention without the model, retryable failures, tracing and the audit stage. */
class AbstentionAuditSpec extends RagSuite with WorkflowWorld:
  import World.*

  private def none = new ScriptedLlm(Seq.empty)

  private def abstain(r: AnswerRequest, llm: ScriptedLlm = none, rig: Option[Rig] = None) =
    val wired = rig.getOrElse(new Rig(llm))
    val res = wired.workflow.answer(r).fold(f => fail(f.message), identity)
    llm.requests shouldBe empty
    res.response.abstained shouldBe true
    res.response.claims shouldBe empty
    res.response.uncertainty should not be empty
    RecordContract[AnswerResponse].violations(res.response, "") shouldBe empty
    res.usage.calls shouldBe 0
    (res, wired)

  test("an ambiguous name abstains with every candidate and never calls the model") {
    val llm = none
    val (res, wired) =
      abstain(request(fields(Seq("DIRECT_COAUTHORS"), names = Seq("Wei Zhang"))), llm)
    res.abstention.get.code shouldBe "AMBIGUOUS_RESEARCHER"
    res.abstention.get.candidates.map(_.researcherId).toSet shouldBe Set(r(2), r(8))
    val u = res.response.uncertainty.get
    u should include(r(2))
    u should include(r(8))
    u should include("Alpha University")
    u should include("Beta Institute")
    res.response.summary should include("match more than one researcher")
    res.verification shouldBe VerificationStatus.UNSUPPORTED
    res.bundle.graphEvidence.count(_.evidenceType == EvidenceType.MATCH_DECISION) shouldBe 2
    val stored = wired.dynamo.queryAudit.get(res.audit.queryId).toOption.get.get
    stored.researcherIds shouldBe empty
    stored.evidenceIds shouldBe empty
    stored shouldBe res.audit
  }

  test("a name nobody has abstains with RESEARCHER_NOT_FOUND") {
    val (res, _) = abstain(request(fields(Seq("DIRECT_COAUTHORS"), names = Seq("Nobody Known"))))
    res.abstention.get.code shouldBe "RESEARCHER_NOT_FOUND"
    res.response.uncertainty.get should include("nobody known")
  }

  test("a single weak candidate is not chosen silently") {
    val (res, _) = abstain(request(fields(Seq("DIRECT_COAUTHORS"), names = Seq("Sam Low"))))
    res.abstention.get.code shouldBe "AMBIGUOUS_RESEARCHER"
    res.abstention.get.candidates.map(_.researcherId) shouldBe Vector(r(5))
  }

  test("one ambiguous name among resolved ones still abstains: a required researcher is missing") {
    val (res, _) = abstain(
      request(fields(Seq("SHORTEST_PATH"), names = Seq("Ada Example", "Wei Zhang")))
    )
    res.abstention.get.code shouldBe "AMBIGUOUS_RESEARCHER"
  }

  test("planner refusals abstain with their refusal code and no store or model call") {
    val none1 = abstain(request(StructuredFields(), question = None, name = "empty"))._1
    none1.abstention.get.code shouldBe "NO_OPERATION"
    val llm = none
    val notAllowed = abstain(
      request(fields(Seq("BULK_EXPORT"), ids = Seq(r(1))), name = "bad-op"),
      llm
    )._1
    notAllowed.abstention.get.code shouldBe "OPERATION_NOT_ALLOWED"
    val incomplete = abstain(
      request(fields(Seq("RESEARCHER_RANKING")), name = "no-metric")
    )._1
    incomplete.abstention.get.code shouldBe "PLAN_INCOMPLETE"
    none1.audit.planJson should include("refusal")
    none1.audit.planJson should include("NO_OPERATION")
  }

  test("a bridge ranking the store cannot serve abstains and says why") {
    val (res, wired) = abstain(
      request(
        fields(Seq("BRIDGE_RANKING"), metric = Some("CROSS_COMMUNITY_DEGREE")),
        name = "bridge"
      )
    )
    res.abstention.get.code shouldBe "NO_DETERMINISTIC_EVIDENCE"
    res.abstention.get.gaps.mkString should include("UNSUPPORTED")
    res.abstention.get.gaps.mkString should include("no deterministic evidence for BRIDGE_RANKING")
    res.response.uncertainty.get should include("BRIDGE_RANKING")
    wired.spy.log.filter(_.startsWith("graph.")) shouldBe empty
  }

  test("an unloaded window abstains with the loaded windows named") {
    val (res, _) = abstain(
      request(
        StructuredFields(
          operations = Vector("RESEARCHER_RANKING"),
          rankingMetric = Some("WEIGHTED_DEGREE"),
          windowStartYear = Some(2006),
          windowEndYear = Some(2009)
        ),
        name = "old-window"
      )
    )
    res.abstention.get.code shouldBe "NO_DETERMINISTIC_EVIDENCE"
    res.abstention.get.gaps.mkString should include("2010")
  }

  test("a path deeper than the configured depth is a gap, not an invented path") {
    val (res, _) = abstain(
      request(fields(Seq("SHORTEST_PATH"), ids = Seq(r(1), r(7))), name = "far")
    )
    res.abstention.get.code shouldBe "NO_DETERMINISTIC_EVIDENCE"
    res.abstention.get.gaps.mkString should include("no path within 4 hop(s)")
  }

  test("a plan with no window and no configured default window abstains") {
    val llm = none
    val wired = new Rig(llm, defaultWindow = None)
    val (res, _) = abstain(
      request(fields(Seq("DIRECT_COAUTHORS"), ids = Seq(r(1)), window = false), name = "nowin"),
      llm,
      Some(wired)
    )
    res.abstention.get.code shouldBe "WINDOW_REQUIRED"
  }

  test("an answer where only some operations have evidence is kept and states the gap") {
    val rr = request(
      fields(
        Seq("RESEARCHER_RANKING", "SHORTEST_PATH"),
        ids = Seq(r(1), r(7)),
        metric = Some("WEIGHTED_DEGREE")
      ),
      name = "partial"
    )
    val b = bundleOf(rr)
    val c = claim(
      "A researcher has a weighted degree.",
      Seq(rankingOf(b, "WEIGHTED_DEGREE").evidenceId),
      A("METRIC", r(5), "WEIGHTED_DEGREE", n(20))
    )
    val llm = new ScriptedLlm(Seq(synthReply(Seq(c))))
    val res = new Rig(llm).workflow.answer(rr).fold(f => fail(f.message), identity)
    res.response.abstained shouldBe false
    res.response.uncertainty.get should include("no deterministic evidence for SHORTEST_PATH")
  }

  // ---- retryable failures and tracing ------------------------------------------------------

  test("a store time-out is surfaced as a retryable failure and the model is not called") {
    val llm = none
    val wired = new Rig(llm, pathTimeout = true)
    val f = wired.workflow
      .answer(request(fields(Seq("SHORTEST_PATH"), ids = Seq(r(1), r(5))), name = "timeout"))
      .left
      .getOrElse(fail("expected a failure"))
    f.retryable shouldBe true
    f.failure shouldBe a[StageFailure.Retryable]
    f.stage shouldBe "paths"
    llm.requests shouldBe empty
    f.trace.map(_.stage) shouldBe Vector("plan", "resolve", "metrics", "coauthors", "paths")
    f.trace.last.outcome shouldBe "RETRYABLE"
    wired.dynamo.queryAudit.get(queryIdOf("timeout")) shouldBe Right(None)
  }

  test("a stage that overruns its deadline fails as retryable") {
    val slow: MetricReader => MetricReader = inner =>
      new MetricReader:
        def loadedWindows = inner.loadedWindows
        def topK(
            metric: CollaborationMetric,
            ws: Int,
            we: Int,
            filter: ResearcherFilter,
            limit: Int
        ) =
          Thread.sleep(5000)
          inner.topK(metric, ws, we, filter, limit)
    val wired = new Rig(none, timeoutMillis = 150, wrapMetrics = slow)
    val started = System.nanoTime()
    val f = wired.workflow
      .answer(
        request(fields(Seq("RESEARCHER_RANKING"), metric = Some("WEIGHTED_DEGREE")), name = "slow")
      )
      .left
      .getOrElse(fail("expected a failure"))
    ((System.nanoTime() - started) / 1_000_000L) should be < 3000L
    f.stage shouldBe "metrics"
    f.retryable shouldBe true
    f.failure.detail should include("150 ms")
  }

  test("one trace ID goes through every stage, each with its own recorded latency") {
    val (c1, c2) = adaClaims(bundleOf(request(adaFields)))
    val seen = scala.collection.mutable.ArrayBuffer.empty[StageTrace]
    val llm = new ScriptedLlm(Seq(synthReply(Seq(c1, c2))))
    val wired = new Rig(llm, clock = new TickingClock(5))
    val traced = new AnswerWorkflow(
      wired.agents,
      AuditPorts(wired.dynamo.querySessions, wired.dynamo.evidence, wired.dynamo.queryAudit),
      WorkflowSettings(10000, Some((WS, WE)), synthCfg),
      new TickingClock(5),
      t => seen += t
    )
    val res = traced
      .answer(request(adaFields, traceId = "trace-abc:123"))
      .fold(f => fail(f.message), identity)
    res.traceId shouldBe "trace-abc:123"
    res.trace.map(_.stage) shouldBe Vector(
      "plan",
      "resolve",
      "metrics",
      "coauthors",
      "paths",
      "community",
      "semantic",
      "evidence",
      "synthesis",
      "verification",
      "audit"
    )
    res.trace.map(_.traceId).toSet shouldBe Set("trace-abc:123")
    res.trace.map(_.latencyMillis).toSet shouldBe Set(5L)
    res.trace.map(_.outcome).toSet shouldBe Set("ok")
    seen.toVector shouldBe res.trace
    res.audit.latencyMillis should be > 0L
  }

  test("an invalid trace ID, query ID or run ID is refused before any stage runs") {
    val wired = new Rig(none)
    val base = request(adaFields)
    Seq(
      base.copy(traceId = "bad id with spaces") -> "TRACE_ID_INVALID",
      base.copy(traceId = "") -> "TRACE_ID_INVALID",
      base.copy(queryId = "query:nope") -> "QUERY_ID_INVALID",
      base.copy(runId = "run:nope") -> "RUN_ID_INVALID"
    ).foreach { (r, code) =>
      val f = wired.workflow.answer(r).left.getOrElse(fail("expected a failure"))
      f.failure.code shouldBe code
      f.trace shouldBe empty
    }
    wired.spy.log shouldBe empty
  }

  // ---- audit -------------------------------------------------------------------------------

  private def answered(
      wired: Rig,
      r: AnswerRequest = request(adaFields, idempotencyKey = Some("idem-1"))
  ) = wired.workflow.answer(r).fold(f => fail(f.message), identity)

  test(
    "the audit record holds the plan, IDs, versions, tokens, latency, verification and answer hash"
  ) {
    val (c1, c2) = adaClaims(bundleOf(request(adaFields)))
    val llm = new ScriptedLlm(Seq(synthReply(Seq(c1, c2))))
    val wired = new Rig(llm, clock = new TickingClock(5))
    val rq = request(adaFields, idempotencyKey = Some("idem-1"))
    val res = answered(wired, rq)
    val a = res.audit
    RecordContract[QueryAuditRecord].violations(a, "") shouldBe empty
    a.queryId shouldBe rq.queryId
    a.runId shouldBe runId
    read[QueryPlan](a.planJson).operations shouldBe Vector(QueryOperation.DIRECT_COAUTHORS)
    a.researcherIds shouldBe Vector(r(1))
    a.evidenceIds.toSet shouldBe res.response.claims.flatMap(_.evidenceIds).toSet
    a.evidenceIds.foreach(id =>
      (res.bundle.graphEvidence ++ res.bundle.semanticEvidence).map(_.evidenceId) should contain(id)
    )
    a.metricVersions shouldBe Vector(metricVersion)
    a.promptVersions shouldBe Vector("answer-synthesis-prompt-v3")
    a.modelVersions shouldBe Vector("fake-model-v1")
    a.inputTokens shouldBe 400L
    a.outputTokens shouldBe 120L
    a.latencyMillis should be > 0L
    a.verificationResult shouldBe VerificationStatus.VERIFIED
    a.answerHash shouldBe AnswerHash.of(res.response)
    a.answerHash should fullyMatch regex "sha256:[0-9a-f]{64}"
    a.provenance.runId shouldBe runId

    // what is stored is what was returned, and the bundle and session went through their ports
    wired.dynamo.queryAudit.get(rq.queryId) shouldBe Right(Some(a))
    wired.dynamo.evidence.listForQuery(rq.queryId).toOption.get.map(_.evidenceId).toSet shouldBe
      (res.bundle.graphEvidence ++ res.bundle.semanticEvidence).map(_.evidenceId).toSet
    val session = wired.dynamo.querySessions.get(rq.queryId).toOption.get.get
    session.requestFingerprint shouldBe "fp-q1"
    session.idempotencyKey shouldBe Some("idem-1")
    wired.dynamo.querySessions.queryIdForIdempotencyKey("idem-1") shouldBe Right(Some(rq.queryId))
  }

  test("planner and synthesis tokens, prompts and models are all counted") {
    val planReply = ScriptedLlm.reply(
      ScriptedLlm.planJson(
        operations = Seq("DIRECT_COAUTHORS"),
        names = Seq("Ada Example"),
        start = Some(WS),
        end = Some(WE)
      ),
      90,
      30
    )
    val b = bundleOf(request(adaFields))
    val (c1, _) = adaClaims(b)
    val llm = new ScriptedLlm(Seq(planReply, synthReply(Seq(c1))))
    val wired = new Rig(llm)
    val res = wired.workflow
      .answer(
        request(StructuredFields(), question = Some("who does Ada Example work with"), name = "q1")
      )
      .fold(f => fail(f.message), identity)
    res.audit.inputTokens shouldBe 490L
    res.audit.outputTokens shouldBe 150L
    res.audit.promptVersions.toSet shouldBe Set(
      "query-planner-prompt-v1",
      "answer-synthesis-prompt-v3"
    )
    res.audit.modelVersions shouldBe Vector("fake-model-v1")
    res.usage.calls shouldBe 2
    res.audit.planJson should not include "who does Ada Example work with"
  }

  test("replaying the same query ID writes exactly one audit record, one session and one bundle") {
    val items = new InMemoryItemStore
    val table = SmokeEnv.settings.runtime.dynamoTables.querySession
    val r = request(adaFields, idempotencyKey = Some("idem-1"))
    val (c1, c2) = adaClaims(bundleOf(r))
    val first = answered(new Rig(new ScriptedLlm(Seq(synthReply(Seq(c1, c2)))), items = items), r)
    val auditRows =
      () => items.query(table, s"query#${r.queryId}", Some("AUDIT#"), true, None).toOption.get
    val allRows = () =>
      items.query(table, s"query#${r.queryId}", None, true, None).toOption.get.map(_("sk").str)
    auditRows().size shouldBe 1
    val before = allRows()

    val second = answered(new Rig(new ScriptedLlm(Seq(synthReply(Seq(c1, c2)))), items = items), r)
    second.response shouldBe first.response
    auditRows().size shouldBe 1
    allRows() shouldBe before
    second.audit.answerHash shouldBe first.audit.answerHash

    // a replay that words its answer differently is a typed conflict and writes nothing
    val third = new Rig(new ScriptedLlm(Seq(synthReply(Seq(c1)))), items = items).workflow
      .answer(r)
      .left
      .getOrElse(fail("expected an IdempotencyConflict"))
    third.failure shouldBe a[StageFailure.IdempotencyConflict]
    third.failure.code shouldBe "IDEMPOTENCY_CONFLICT"
    third.stage shouldBe "audit"
    auditRows().size shouldBe 1
    new Rig(none, items = items).dynamo.queryAudit
      .get(r.queryId)
      .toOption
      .get
      .get
      .answerHash shouldBe
      first.audit.answerHash
  }

  test("replaying an abstention is also one audit record") {
    val items = new InMemoryItemStore
    val table = SmokeEnv.settings.runtime.dynamoTables.querySession
    val r = request(fields(Seq("DIRECT_COAUTHORS"), names = Seq("Wei Zhang")), name = "wz")
    answered(new Rig(none, items = items), r)
    answered(new Rig(none, items = items), r)
    items
      .query(table, s"query#${r.queryId}", Some("AUDIT#"), true, None)
      .toOption
      .get
      .size shouldBe 1
  }

  test("an Idempotency-Key used by another query is refused at the audit stage") {
    val items = new InMemoryItemStore
    val (c1, _) = adaClaims(bundleOf(request(adaFields)))
    answered(
      new Rig(new ScriptedLlm(Seq(synthReply(Seq(c1)))), items = items),
      request(adaFields, name = "first", idempotencyKey = Some("idem-9"))
    )
    val rb = request(adaFields, name = "second", idempotencyKey = Some("idem-9"))
    val (d1, _) = adaClaims(bundleOf(rb))
    val f = new Rig(new ScriptedLlm(Seq(synthReply(Seq(d1)))), items = items).workflow
      .answer(rb)
      .left
      .getOrElse(fail("expected a failure"))
    f.stage shouldBe "audit"
    f.failure.code shouldBe "IDEMPOTENCY_KEY_REUSED"
    f.retryable shouldBe false
  }

  test("an audit write that times out is a retryable failure") {
    val (c1, _) = adaClaims(bundleOf(request(adaFields)))
    val wired = new Rig(new ScriptedLlm(Seq(synthReply(Seq(c1)))))
    val flaky = new scholarchain.storage.dynamo.QueryAuditLog:
      def append(record: QueryAuditRecord) = Left(StorageError.Timeout("append", 1000))
      def get(queryId: String) = Right(None)
    val flow = new AnswerWorkflow(
      wired.agents,
      AuditPorts(wired.dynamo.querySessions, wired.dynamo.evidence, flaky),
      WorkflowSettings(10000, Some((WS, WE)), synthCfg)
    )
    val f = flow.answer(request(adaFields)).left.getOrElse(fail("expected a failure"))
    f.stage shouldBe "audit"
    f.retryable shouldBe true
  }

  test("an abstaining answer where every claim failed verification reports the removed claims") {
    val b = bundleOf(request(adaFields))
    val bad = claim(
      "Ada Example has a number of distinct coauthors.",
      Seq(profileOf(b, 1).evidenceId),
      A("METRIC", r(1), "DISTINCT_COAUTHOR_COUNT", n(7))
    )
    val res = new Rig(new ScriptedLlm(Seq(synthReply(Seq(bad))))).workflow
      .answer(request(adaFields))
      .fold(f => fail(f.message), identity)
    res.response.abstained shouldBe true
    res.abstention.get.code shouldBe "NO_VERIFIED_CLAIMS"
    res.removedClaims.map(_.status) shouldBe Vector(VerificationStatus.CONFLICTING)
    res.verification shouldBe VerificationStatus.CONFLICTING
    res.audit.verificationResult shouldBe VerificationStatus.CONFLICTING
    res.audit.evidenceIds shouldBe empty
  }
