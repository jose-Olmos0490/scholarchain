package scholarchain.services

import scholarchain.storage.common.StorageError

class LifecycleSpec extends ApiSuite:

  test("query -> job -> result -> evidence -> explain -> graph, end to end over fakes") {
    withHarness { h =>
      // 1. query (asynchronous, so the job API is part of the lifecycle)
      val accepted = h.post(Bodies.asyncCoauthors)
      accepted.status shouldBe 202
      val jobId = accepted.json("jobId").str
      val queryId = accepted.json("queryId").str
      eventually(h.get(s"/v1/jobs/$jobId").json("state").str == "SUCCEEDED")

      // 2. result: every claim cites evidence IDs
      val result = h.get(s"/v1/jobs/$jobId/result")
      result.status shouldBe 200
      result.json("queryId").str shouldBe queryId
      val claims = result.json("answer")("claims").arr
      claims should not be empty
      val evidenceIds = claims.flatMap(_("evidenceIds").arr.map(_.str)).distinct.toVector
      evidenceIds should not be empty

      // 3. each cited evidence ID resolves to provenance, a source record and a source URI
      evidenceIds.foreach { id =>
        val e = h.get(s"/v1/evidence/$id")
        e.status shouldBe 200
        e.json("evidenceId").str shouldBe id
        e.json("provenance")("runId").str shouldBe Fx.runId
        e.json("sourceRecord")("referencedIds").arr should not be empty
        e.json("sourceUri").str should startWith("s3://")
        e.json("evidenceType").str shouldBe "COAUTHOR_EDGE"
      }

      // 4. explain shows the plan, the stages, the versions and the same evidence IDs
      val x = h.get(s"/v1/explain/$queryId")
      x.status shouldBe 200
      x.json("plan")("operations")(0).str shouldBe "DIRECT_COAUTHORS"
      x.json("stagesExecuted").arr.map(_("stage").str) shouldBe Seq("plan", "evidence", "audit")
      x.json("promptVersions")(0).str shouldBe "answer-synthesis-prompt-v3"
      x.json("modelVersions")(0).str shouldBe "test-model-v1"
      x.json("evidenceIds").arr.map(_.str).toVector shouldBe evidenceIds
      x.json("verification")("status").str shouldBe "VERIFIED"
      x.json("counters")("inputTokens").num shouldBe 120.0
      x.json("scoreComponents")
        .arr
        .map(_("evidenceId").str)
        .toSet should contain allElementsOf evidenceIds
      x.json.obj.keys.toSet.exists(_.toLowerCase.contains("reasoning")) shouldBe false
      x.json("note").str should include("never stored")

      // 5. the graph the claim is about
      val g = h.get(s"/v1/graph/nodes/${Fx.ada}/neighbors", query = window ++ Map("limit" -> "3"))
      g.status shouldBe 200
      g.json("items").arr.map(_("neighborId").str).toVector should contain(Fx.bea)
    }
  }

  private val window = Map("windowStartYear" -> "2010", "windowEndYear" -> "2013")

class EvidenceApiSpec extends ApiSuite:

  private def firstEvidenceId(h: Harness): String =
    h.post(Bodies.coauthors()).json("answer")("claims")(0)("evidenceIds")(0).str

  test("missing evidence is 404 with the typed error") {
    withHarness { h =>
      val missing = scholarchain.core.ids.StableIds
        .evidenceId(
          scholarchain.core.model.EvidenceType.COAUTHOR_EDGE,
          Vector(Fx.cyd, Fx.dee),
          Fx.runId
        )
        .toOption
        .get
      val r = h.get(s"/v1/evidence/$missing")
      r.status shouldBe 404
      r.err("code").str shouldBe "NOT_FOUND"
      r.err("retryable").bool shouldBe false
      r.traceId should not be empty
    }
  }

  test("a malformed evidence ID is 400") {
    withHarness { h =>
      h.get("/v1/evidence/not-an-id").status shouldBe 400
      h.get(s"/v1/evidence/${Fx.ada}").status shouldBe 400 // a researcher ID is not evidence
    }
  }

  test("evidence content is cut to the configured length and labeled untrusted") {
    withHarness { h =>
      val resp = h.post(Bodies.coauthors())
      resp.status shouldBe 200
      // the text chunk is in the stored bundle, not cited by a claim: read it via the store
      val items = h.dynamo.evidence.listForQuery(resp.json("queryId").str).toOption.get
      val chunk = items.find(_.text.isDefined).get
      val r = h.get(s"/v1/evidence/${chunk.evidenceId}")
      r.status shouldBe 200
      r.json("content")("trust").str shouldBe "untrusted-data"
      r.json("content")("truncated").bool shouldBe true
      r.json("content")("text")
        .str
        .codePointCount(0, r.json("content")("text").str.length) should be <=
        Fx.settings.evidence.contentMaxChars
      r.json("textPointer")("chunkId").str shouldBe chunk.chunkId.get
      r.json("textPointer")("textHash").str shouldBe chunk.textHash.get
      r.json("retrievalScore").num shouldBe 0.5
    }
  }

  test("evidence store failures: retried, then 502 or 504, and the evidence breaker opens") {
    withHarness { h =>
      val id = firstEvidenceId(h)
      h.evidenceFailure = Some(StorageError.Transient("dynamo", 3, "reset"))
      val r = h.get(s"/v1/evidence/$id")
      r.status shouldBe 502
      r.err("reason").str shouldBe "STORE_UNAVAILABLE"
      h.evidenceCalls.get() shouldBe 3 // 1 try + readRetries (2)
      h.evidenceFailure = Some(StorageError.Timeout("getItem", 1000))
      h.get(s"/v1/evidence/$id").status shouldBe 504
      h.get(s"/v1/evidence/$id").status shouldBe 504
      val open = h.get(s"/v1/evidence/$id")
      open.status shouldBe 503
      open.err("details")("dependency").str shouldBe "evidence"
      // the graph breaker is a different one
      h.get(s"/v1/graph/nodes/${Fx.ada}").status shouldBe 200
    }
  }

class JobsApiSpec extends ApiSuite:

  test("an unknown job is 404 and a malformed job ID is 400") {
    withHarness { h =>
      h.get("/v1/jobs/job-" + "0" * 32).status shouldBe 404
      h.get("/v1/jobs/whatever").status shouldBe 400
    }
  }

  test("another caller cannot see a job, its result or its explanation") {
    withHarness { h =>
      val r = h.post(Bodies.coauthors())
      val jobId = r.json("jobId").str
      val queryId = r.json("queryId").str
      h.get(s"/v1/jobs/$jobId", token = Some(Fx.bobToken)).status shouldBe 404
      h.get(s"/v1/jobs/$jobId/result", token = Some(Fx.bobToken)).status shouldBe 404
      h.get(s"/v1/explain/$queryId", token = Some(Fx.bobToken)).status shouldBe 404
      h.get(s"/v1/jobs/$jobId").status shouldBe 200
    }
  }

  test(
    "a failed job reports state, stage, timestamps and a typed error, and its result is that error"
  ) {
    withHarness { h =>
      h.engine.behavior = h.engine.failure(
        "synthesis",
        scholarchain.rag.agents.StageFailure.Failed("synthesis", "x")
      )
      val accepted = h.post(Bodies.asyncCoauthors)
      val jobId = accepted.json("jobId").str
      eventually(h.get(s"/v1/jobs/$jobId").json("state").str == "FAILED")
      val job = h.get(s"/v1/jobs/$jobId")
      job.json("stage").str shouldBe "synthesis"
      job.json("error")("code").str shouldBe "DOWNSTREAM_FAILURE"
      job.json("error")("status").num shouldBe 502.0
      job.json("createdAt").str should endWith("Z")
      job.json("finishedAt").str should endWith("Z")
      job.json("links").obj.contains("result") shouldBe false
      val result = h.get(s"/v1/jobs/$jobId/result")
      result.status shouldBe 502
      result.err("code").str shouldBe "DOWNSTREAM_FAILURE"
      result.traceId should not be empty
    }
  }

class ExplainApiSpec extends ApiSuite:

  test("an unknown query is 404, a malformed one 400") {
    withHarness { h =>
      val unknown = scholarchain.core.ids.StableIds.queryId("nope").toOption.get
      h.get(s"/v1/explain/$unknown").status shouldBe 404
      h.get("/v1/explain/not-a-query").status shouldBe 400
    }
  }

  test("a query that has not finished is a retryable 409") {
    withHarness { h =>
      val release = new java.util.concurrent.CountDownLatch(1)
      val inner = h.engine.success
      h.engine.behavior = r =>
        release.await(5, java.util.concurrent.TimeUnit.SECONDS)
        inner(r)
      val queryId = h.post(Bodies.asyncCoauthors).json("queryId").str
      val x = h.get(s"/v1/explain/$queryId")
      x.status shouldBe 409
      x.err("reason").str shouldBe "QUERY_NOT_FINISHED"
      x.err("retryable").bool shouldBe true
      release.countDown()
      eventually(h.jobs.all.forall(_.state.terminal))
    }
  }

class GraphApiSpec extends ApiSuite:
  private val window = Map("windowStartYear" -> "2010", "windowEndYear" -> "2013")
  private def neighbors(h: Harness, extra: Map[String, String] = Map.empty, id: String = Fx.ada) =
    h.get(s"/v1/graph/nodes/$id/neighbors", query = window ++ extra)

  test("a node returns its stored metrics and provenance") {
    withHarness { h =>
      val r = h.get(s"/v1/graph/nodes/${Fx.ada}")
      r.status shouldBe 200
      r.json("nodeType").str shouldBe "Researcher"
      r.json("metrics")("distinctCoauthorCount").num shouldBe 3.0
      r.json("provenance")("runId").str shouldBe Fx.runId
      r.json("links")("neighbors").str shouldBe s"/v1/graph/nodes/${Fx.ada}/neighbors"
    }
  }

  test("an unknown node is 404, a malformed ID 400, a non-researcher ID 422") {
    withHarness { h =>
      h.get(s"/v1/graph/nodes/${Fx.unknownResearcher}").status shouldBe 404
      neighbors(h, id = Fx.unknownResearcher).status shouldBe 404
      h.get("/v1/graph/nodes/xyz").status shouldBe 400
      h.get(s"/v1/graph/nodes/${Fx.runId}").status shouldBe 422
    }
  }

  test("neighbors are cursored: pages are disjoint, ordered, bounded and end") {
    withHarness { h =>
      val p1 = neighbors(h, Map("limit" -> "2"))
      p1.status shouldBe 200
      p1.json("items").arr should have size 2
      p1.json("truncated").bool shouldBe true
      val cursor = p1.json("nextCursor").str
      val p2 = neighbors(h, Map("limit" -> "2", "cursor" -> cursor))
      p2.json("items").arr should have size 1
      p2.json("nextCursor") shouldBe ujson.Null
      p2.json("truncated").bool shouldBe false
      val all = (p1.json("items").arr ++ p2.json("items").arr).map(_("neighborId").str).toVector
      all.distinct should have size 3
      all.last shouldBe Fx.dee // fewest joint publications last
      p1.json("items")(0)("edge")("provenance")("runId").str shouldBe Fx.runId
      p1.json("items")(0)("edge")("evidencePointer").str should not be empty
    }
  }

  test("the default page size comes from configuration") {
    withHarness { h =>
      neighbors(h).json("items").arr should have size Fx.limits.pageSize
    }
  }

  test("every bound is enforced: limit, depth, edge types, timeout; none is clamped") {
    withHarness { h =>
      def reason(extra: Map[String, String]) =
        val r = neighbors(h, extra)
        withClue(r.raw)(r.status shouldBe 422)
        r.err("reason").str
      reason(Map("limit" -> (Fx.limits.graphLimit + 1).toString)) shouldBe "LIMIT_OUT_OF_BOUNDS"
      reason(Map("limit" -> "0")) shouldBe "LIMIT_OUT_OF_BOUNDS"
      reason(Map("limit" -> "-1")) shouldBe "LIMIT_OUT_OF_BOUNDS"
      reason(Map("depth" -> "2")) shouldBe "DEPTH_OUT_OF_BOUNDS"
      reason(Map("depth" -> "0")) shouldBe "DEPTH_OUT_OF_BOUNDS"
      reason(Map("edgeTypes" -> "AUTHORED")) shouldBe "UNSUPPORTED_EDGE_TYPE"
      reason(Map("edgeTypes" -> "COAUTHORED_WITH,CITES")) shouldBe "UNSUPPORTED_EDGE_TYPE"
      reason(
        Map("timeoutMs" -> (Fx.limits.graphTimeoutMillis + 1).toString)
      ) shouldBe "LIMIT_OUT_OF_BOUNDS"
      h.graph.calls.get() shouldBe 0 // nothing unbounded reached the store
    }
  }

  test("a missing window, a non-numeric bound and an unknown parameter are 400") {
    withHarness { h =>
      h.get(s"/v1/graph/nodes/${Fx.ada}/neighbors")
        .json("error")("reason")
        .str shouldBe "MISSING_PARAMETER"
      neighbors(h, Map("limit" -> "all")).err("reason").str shouldBe "INVALID_PARAMETER"
      neighbors(h, Map("limit" -> "*")).status shouldBe 400
      neighbors(h, Map("limt" -> "2")).err("reason").str shouldBe "UNKNOWN_PARAMETER"
      neighbors(h, Map("cursor" -> "bm90LWEtY3Vyc29y")).err("reason").str shouldBe "INVALID_CURSOR"
      h.call("GET", s"/v1/graph/nodes/${Fx.ada}/neighbors", query = Map.empty).status shouldBe 400
      h.graph.calls.get() shouldBe 2 // only the well-formed request with a bad cursor got through
    }
  }

  test("an inverted window is 422") {
    withHarness { h =>
      val r = h.get(
        s"/v1/graph/nodes/${Fx.ada}/neighbors",
        query = Map("windowStartYear" -> "2013", "windowEndYear" -> "2010")
      )
      r.status shouldBe 422
      r.err("reason").str shouldBe "WINDOW_INVALID"
    }
  }

  test("a slow graph query is cut at the request's time limit with 504") {
    withHarness { h =>
      h.graph.delayMillis = 1500
      val r = neighbors(h, Map("timeoutMs" -> "50"))
      r.status shouldBe 504
      r.err("retryable").bool shouldBe true
      r.traceId should not be empty
    }
  }

  test("a transient store failure is retried within bounds, then 502; a store timeout is 504") {
    withHarness { h =>
      h.graph.failure = Some(StorageError.Transient("neptune", 3, "reset"))
      val r = neighbors(h)
      r.status shouldBe 502
      r.err("reason").str shouldBe "STORE_UNAVAILABLE"
      h.graph.calls.get() shouldBe 3 * 1 // 1 try + readRetries (2); the existence check fails first
      h.sleeper.sleeps.toVector shouldBe Vector(10L, 20L)
      h.graph.failure = Some(StorageError.Timeout("paths", 5000))
      neighbors(h).status shouldBe 504
    }
  }

  test("the graph breaker opens after repeated store failures and rejects with 503") {
    withHarness { h =>
      h.graph.failure = Some(StorageError.Permanent("neptune", "down"))
      (1 to Fx.settings.breaker.failureThreshold).foreach(_ => neighbors(h).status shouldBe 502)
      val calls = h.graph.calls.get()
      val open = neighbors(h)
      open.status shouldBe 503
      open.err("reason").str shouldBe "CIRCUIT_OPEN"
      open.err("details")("dependency").str shouldBe "graph"
      h.graph.calls.get() shouldBe calls
      // the breaker is per downstream: evidence reads are unaffected
      h.get(s"/v1/graph/nodes/${Fx.ada}").status shouldBe 503 // same breaker, still open
    }
  }
