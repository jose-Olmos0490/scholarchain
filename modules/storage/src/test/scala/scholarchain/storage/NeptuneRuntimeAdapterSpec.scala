package scholarchain.storage

import scala.jdk.CollectionConverters.*
import scholarchain.core.config.RetryPolicyConfig
import scholarchain.core.model.CollaborationMetric
import scholarchain.storage.aws.*
import scholarchain.storage.common.*
import scholarchain.storage.graph.*
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.core.exception.ApiCallTimeoutException
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model.*

/** The Neptune read adapters for the runtime ports, against a stub client: the request shape,
  * row mapping, the typed Timeout, and that a timed-out query is not retried.
  */
class NeptuneRuntimeAdapterSpec extends StorageSuite:
  private val policy = RetryPolicyConfig(maxAttempts = 3, baseDelayMs = 10, maxDelayMs = 100)
  private val limits = GraphReaderLimits(5, 4, 3000L)

  private class Stub extends NeptunedataClient:
    var queries = Vector.empty[ExecuteOpenCypherQueryRequest]
    var onQuery: ExecuteOpenCypherQueryRequest => ExecuteOpenCypherQueryResponse = _ => rows()
    def serviceName(): String = "neptune-db"
    def close(): Unit = ()
    override def executeOpenCypherQuery(r: ExecuteOpenCypherQueryRequest) =
      queries :+= r
      onQuery(r)

  private def doc(entries: (String, Document)*): Document =
    Document.fromMap(entries.toMap.asJava)
  private def rows(rs: Document*): ExecuteOpenCypherQueryResponse =
    ExecuteOpenCypherQueryResponse.builder().results(Document.fromList(rs.asJava)).build()
  private def str(s: String) = Document.fromString(s)
  private def num(n: Long) = Document.fromNumber(n)
  private def list(ds: Document*) = Document.fromList(ds.asJava)

  private def runner(sleeper: RecordingSleeper) =
    new Retrier(policy, AwsErrors.isTransient, sleeper, MaxJitter)

  private def graphReader(stub: Stub, sleeper: RecordingSleeper = new RecordingSleeper) =
    new NeptuneGraphReader(stub, limits, runner(sleeper))
  private def metricReader(stub: Stub, sleeper: RecordingSleeper = new RecordingSleeper) =
    new NeptuneMetricReader(stub, limits, runner(sleeper))

  private def hop(id: String, a: String, b: String, n: Long) =
    doc(
      "edgeId" -> str(id),
      "researcherA" -> str(a),
      "researcherB" -> str(b),
      "jointPublicationCount" -> num(n),
      "firstCollaborationYear" -> num(2010),
      "lastCollaborationYear" -> num(2012),
      "windowStartYear" -> num(2010),
      "windowEndYear" -> num(2013),
      "evidencePointer" -> str(s"coauthor-edge-support-v1:$id"),
      "runId" -> str("run:r"),
      "policyVersion" -> str("p1")
    )

  private val q = PathQueryLimits(3, 4, 1500)

  test("paths sends the timeout hint, binds every caller value, and maps the hop maps") {
    val stub = new Stub
    stub.onQuery = _ =>
      rows(
        doc(
          "researcherIds" -> list(str("a"), str("m"), str("b")),
          "hops" -> list(hop("e1", "a", "m", 2), hop("e2", "b", "m", 1)),
          "length" -> num(2)
        )
      )
    val out = ok(graphReader(stub).paths("a", "b", 2010, 2013, q))
    out should have size 1
    out.head.researcherIds shouldBe Vector("a", "m", "b")
    out.head.edges.map(e =>
      (e.edgeId, e.researcherA, e.researcherB, e.jointPublicationCount)
    ) shouldBe
      Vector(("e1", "a", "m", 2L), ("e2", "b", "m", 1L))
    out.head.edges.head.evidencePointer shouldBe "coauthor-edge-support-v1:e1"
    val sent = stub.queries.head
    sent.openCypherQuery should startWith("USING QUERY:TIMEOUTMILLISECONDS 1500\n")
    sent.openCypherQuery should include("*1..3")
    ujson.read(sent.parameters) shouldBe ujson.Obj(
      "source" -> "a",
      "target" -> "b",
      "windowStartYear" -> 2010,
      "windowEndYear" -> 2013,
      "resultLimit" -> 4
    )
  }

  test("a path that repeats a researcher is never returned as evidence") {
    val stub = new Stub
    stub.onQuery = _ =>
      rows(
        doc(
          "researcherIds" -> list(str("a"), str("m"), str("a"), str("b")),
          "hops" -> list(hop("e1", "a", "m", 1), hop("e1", "a", "m", 1), hop("e3", "a", "b", 1)),
          "length" -> num(3)
        )
      )
    ok(graphReader(stub).paths("a", "b", 2010, 2013, q)) shouldBe empty
  }

  test("a service time-out becomes a typed Timeout at once, with no retry and no sleep") {
    val stub = new Stub
    stub.onQuery = _ => throw TimeLimitExceededException.builder().message("slow").build()
    val sleeper = new RecordingSleeper
    val e = err(graphReader(stub, sleeper).paths("a", "b", 2010, 2013, q))
    e shouldBe StorageError.Timeout("Neptune openCypher paths/3", 1500L)
    e.retryable shouldBe true
    stub.queries should have size 1
    sleeper.delays shouldBe empty
  }

  test("a client-side call time-out is also a typed Timeout") {
    val stub = new Stub
    stub.onQuery = _ => throw ApiCallTimeoutException.create(1500L)
    err(graphReader(stub).paths("a", "b", 2010, 2013, q)) shouldBe
      a[StorageError.Timeout]
    stub.queries should have size 1
  }

  test("a timed-out query without its own limit reports the configured ceiling") {
    val stub = new Stub
    stub.onQuery = _ => throw TimeLimitExceededException.builder().message("slow").build()
    err(graphReader(stub).directCoauthors("a", 2010, 2013, 3)) shouldBe
      StorageError.Timeout("Neptune openCypher directCoauthors", 3000L)
  }

  test("a transient server error is still retried, so only the time-out is exempt") {
    val stub = new Stub
    var calls = 0
    stub.onQuery = _ =>
      calls += 1
      if calls == 1 then throw ThrottlingException.builder().message("busy").statusCode(429).build()
      rows()
    ok(graphReader(stub).paths("a", "b", 2010, 2013, q)) shouldBe empty
    stub.queries should have size 2
  }

  test("a path request above a bound never reaches the service") {
    val stub = new Stub
    err(graphReader(stub).paths("a", "b", 2010, 2013, q.copy(timeoutMillis = 3001))) shouldBe
      a[StorageError.LimitExceeded]
    err(graphReader(stub).paths("a", "b", 2010, 2013, q.copy(maxDepth = 5))) shouldBe
      a[StorageError.LimitExceeded]
    stub.queries shouldBe empty
  }

  test("directCoauthorsPage fetches one extra row and turns it into an opaque next cursor") {
    val stub = new Stub
    def row(id: String, n: Long) =
      doc(
        "coauthorId" -> str(id),
        "edgeId" -> str(s"e-$id"),
        "researcherA" -> str("r1"),
        "researcherB" -> str(id),
        "jointPublicationCount" -> num(n),
        "firstCollaborationYear" -> num(2010),
        "lastCollaborationYear" -> num(2012),
        "windowStartYear" -> num(2010),
        "windowEndYear" -> num(2013),
        "evidencePointer" -> str(s"coauthor-edge-support-v1:e-$id"),
        "runId" -> str("run:r"),
        "policyVersion" -> str("p1")
      )
    stub.onQuery = _ => rows(row("r2", 3), row("r3", 3), row("r4", 3))
    val first = ok(graphReader(stub).directCoauthorsPage("r1", 2010, 2013, PageRequest(2)))
    first.items.map(_.coauthorId) shouldBe Vector("r2", "r3")
    ujson.read(stub.queries.head.parameters)("fetchLimit") shouldBe ujson.Num(3)
    val cursor = first.nextCursor.get
    stub.onQuery = _ => rows(row("r4", 3))
    val second =
      ok(graphReader(stub).directCoauthorsPage("r1", 2010, 2013, PageRequest(2, Some(cursor))))
    second.items.map(_.coauthorId) shouldBe Vector("r4")
    second.nextCursor shouldBe None
    val sent = stub.queries.last
    sent.openCypherQuery shouldBe CypherTemplates.DirectCoauthorsAfter
    val p = ujson.read(sent.parameters)
    p("afterCoauthorId") shouldBe ujson.Str("r3")
    p("afterJointPublicationCount") shouldBe ujson.Num(3)
  }

  private def rankedRow(id: String, name: String, value: Double) =
    doc(
      "researcherId" -> str(id),
      "displayName" -> str(name),
      "metricValue" -> Document.fromNumber(value),
      "window" -> str("2010-2013"),
      "P_r" -> num(7),
      "C_r" -> num(2),
      "T_r" -> num(3),
      "A_r" -> Document.fromNumber(1.5),
      "M_r" -> num(4),
      "W_r" -> Document.fromNumber(7.0),
      "componentId" -> str("cA"),
      "coreness" -> num(2),
      "metricVersion" -> str("metrics-v1")
    )

  test("topK sends the metric's template with bound parameters and maps the ranked rows") {
    val stub = new Stub
    stub.onQuery = _ => rows(rankedRow("r3", "Name r3", 7.0), rankedRow("r1", "Name r1", 5.0))
    val out = ok(
      metricReader(stub).topK(
        CollaborationMetric.WEIGHTED_DEGREE,
        2010,
        2013,
        ResearcherFilter(topicIds = Vector("t1")),
        2
      )
    )
    out.map(r => (r.researcherId, r.displayName, r.metricValue)) shouldBe
      Vector(("r3", "Name r3", 7.0), ("r1", "Name r1", 5.0))
    out.head.metrics.publicationCount shouldBe 7L
    out.head.metrics.componentId shouldBe "cA"
    out.head.metrics.coreness shouldBe Some(2)
    val sent = stub.queries.head
    sent.openCypherQuery should include("ORDER BY r.W_r DESC, id(r) ASC")
    sent.openCypherQuery should include("id(t) IN $topicIds")
    ujson.read(sent.parameters) shouldBe ujson.Obj(
      "window" -> "2010-2013",
      "limit" -> 2,
      "topicIds" -> ujson.Arr("t1")
    )
  }

  test("an empty ranking for a window no node carries is refused, naming the loaded windows") {
    val stub = new Stub
    stub.onQuery = r =>
      if r.openCypherQuery.contains("DISTINCT r.window") then
        rows(doc("window" -> str("2010-2013")))
      else rows()
    val e = err(
      metricReader(stub)
        .topK(CollaborationMetric.PUBLICATION_COUNT, 2000, 2005, ResearcherFilter.Unfiltered, 2)
    )
    e shouldBe a[StorageError.Unsupported]
    e.message should include("2010-2013")
    // an empty ranking inside a loaded window is a plain empty answer
    ok(
      metricReader(stub)
        .topK(CollaborationMetric.PUBLICATION_COUNT, 2010, 2013, ResearcherFilter.Unfiltered, 2)
    ) shouldBe empty
  }

  test("unsupported metrics and filters are refused before any query") {
    val stub = new Stub
    err(
      metricReader(stub)
        .topK(CollaborationMetric.APPROX_BETWEENNESS, 2010, 2013, ResearcherFilter.Unfiltered, 2)
    ) shouldBe a[StorageError.Unsupported]
    err(
      metricReader(stub).topK(
        CollaborationMetric.PUBLICATION_COUNT,
        2010,
        2013,
        ResearcherFilter(venueIds = Vector("venue:x")),
        2
      )
    ) shouldBe a[StorageError.Unsupported]
    err(
      metricReader(stub)
        .topK(CollaborationMetric.PUBLICATION_COUNT, 2010, 2013, ResearcherFilter.Unfiltered, 6)
    ) shouldBe a[StorageError.LimitExceeded]
    stub.queries shouldBe empty
  }
