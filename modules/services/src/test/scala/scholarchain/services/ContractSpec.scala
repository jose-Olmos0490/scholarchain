package scholarchain.services

import scala.io.Source

/** The documented examples and the route table are checked against the running service. */
class ContractSpec extends ApiSuite:

  private def resource(name: String): String =
    val in = getClass.getClassLoader.getResourceAsStream(name)
    assert(in != null, s"missing resource $name")
    try Source.fromInputStream(in, "UTF-8").mkString
    finally in.close()

  private def example(name: String): ujson.Value = ujson.read(resource(s"api-examples/$name"))

  /** Keys that may be absent or null by contract. */
  private val optional =
    Set("startedAt", "finishedAt", "abstention", "retryAfterSeconds", "reason", "details")
  private val nullable = Set("retrievalScore", "textHash", "nextCursor", "workId", "orcid")

  /** The differences between a response and its example: keys, JSON types, array element shape. */
  private def diff(actual: ujson.Value, ex: ujson.Value, path: String): Vector[String] =
    (actual, ex) match
      case (a: ujson.Obj, e: ujson.Obj) =>
        val missing = (e.value.keySet -- a.value.keySet -- optional).map(k => s"$path.$k missing")
        val extra =
          (a.value.keySet -- e.value.keySet -- optional).map(k => s"$path.$k undocumented")
        val common = (a.value.keySet intersect e.value.keySet).toVector.flatMap { k =>
          if a.value(k) == ujson.Null && (nullable(k) || e.value(k) == ujson.Null) then Vector.empty
          else if e.value(k) == ujson.Null then Vector.empty
          else diff(a.value(k), e.value(k), s"$path.$k")
        }
        missing.toVector ++ extra.toVector ++ common
      case (a: ujson.Arr, e: ujson.Arr) =>
        e.value.headOption.fold(Vector.empty[String])(h =>
          a.value.toVector.zipWithIndex.flatMap((x, i) => diff(x, h, s"$path[$i]"))
        )
      case (_: ujson.Str, _: ujson.Str) | (_: ujson.Num, _: ujson.Num) |
          (_: ujson.Bool, _: ujson.Bool) =>
        Vector.empty
      case (a, e) =>
        Vector(s"$path is ${a.getClass.getSimpleName}, the example has ${e.getClass.getSimpleName}")

  private def matches(r: Resp, status: Int, name: String): Unit =
    withClue(s"$name: ${r.raw}") {
      r.status shouldBe status
      diff(r.json, example(name), "$") shouldBe empty
    }

  test("the route table in the contract is exactly the route table of the service") {
    withHarness { h =>
      val documented = resource("api-contract.md").linesIterator
        .filter(_.matches("""\| (GET|POST) \| /\S+ \|.*"""))
        .map(_.split('|').map(_.trim))
        .map(c => (c(1), c(2), c(3)))
        .toVector
      val actual = h.runtime.api.routes.map(r => (r.method, r.template, r.scope.getOrElse("none")))
      documented.toSet shouldBe actual.toSet
    }
  }

  test("the documented error codes are the codes of the service") {
    val rows = resource("api-contract.md").linesIterator
      .filter(_.matches("""\| \d{3} \| [A-Z_]+ \|.*"""))
      .map(_.split('|').map(_.trim))
      .map(c => c(1).toInt -> c(2))
      .toMap
    rows shouldBe scholarchain.services.error.ErrorCode.values.map(c => c.status -> c.wire).toMap
  }

  test("the request example is accepted and answered as documented") {
    withHarness { h =>
      val req = resource("api-examples/query-request.json")
      val ok = h.post(req)
      matches(ok, 200, "query-response-200.json")
      val async = h.post(req.replace("\"sync\"", "\"async\""))
      matches(async, 202, "query-response-202.json")
    }
  }

  test("job, evidence, explain, node, neighbors and errors match their examples") {
    withHarness { h =>
      val answer = h.post(Bodies.coauthors())
      val queryId = answer.json("queryId").str
      val jobId = answer.json("jobId").str
      matches(h.get(s"/v1/jobs/$jobId"), 200, "job.json")
      matches(h.get(s"/v1/evidence/${answer.json("evidenceIds")(0).str}"), 200, "evidence.json")
      matches(h.get(s"/v1/explain/$queryId"), 200, "explain.json")
      matches(h.get(s"/v1/graph/nodes/${Fx.ada}"), 200, "graph-node.json")
      matches(
        h.get(
          s"/v1/graph/nodes/${Fx.ada}/neighbors",
          query = Map("windowStartYear" -> "2010", "windowEndYear" -> "2013", "limit" -> "1")
        ),
        200,
        "graph-neighbors.json"
      )
      matches(h.post(Bodies.coauthors().replace("DIRECT_COAUTHORS", "NOPE")), 422, "error-422.json")
      h.storeReady = false
      matches(h.call("GET", "/readyz", token = None), 503, "error-503-not-ready.json")
    }
  }

  test("the examples themselves are free of secrets and stack traces") {
    Seq("query-request", "query-response-200", "error-422", "job").foreach { n =>
      val text = resource(s"api-examples/$n.json")
      Seq("Bearer", "password", "Exception", "\tat ").foreach(w => text should not include w)
    }
  }
