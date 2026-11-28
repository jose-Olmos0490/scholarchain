package scholarchain.er

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.Executors
import org.scalatest.LoneElement
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scholarchain.core.config.{AnthropicConfig, LlmProviderConfig}
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.core.validation.RecordContract.given
import scholarchain.er.TestData.*
import scholarchain.er.adjudication.*
import scholarchain.er.io.JsonlAdjudicationCache
import scholarchain.er.policy.*
import scholarchain.er.run.ResolveSample

/** The Anthropic adapter against a loopback stub server. No network, no real key. */
class AnthropicAdjudicatorSpec extends AnyFunSuite with Matchers with LoneElement:

  private val Key = "test-key-canary-0123"
  private val Model = "test-anthropic-model"

  final private case class Seen(
      method: String,
      path: String,
      headers: Map[String, String],
      body: String
  )

  final private case class Answer(
      status: Int,
      body: String,
      headers: Map[String, String] = Map.empty,
      delayMs: Long = 0L
  )

  final private class Stub(answer: (Int, Seen) => Answer):
    private val received = scala.collection.mutable.ArrayBuffer.empty[Seen]
    private val pool = Executors.newCachedThreadPool()
    private val server =
      HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
    server.setExecutor(pool)
    server.createContext("/", (x: HttpExchange) => handle(x))
    server.start()

    val url: String = s"http://127.0.0.1:${server.getAddress.getPort}"
    def requests: Vector[Seen] = synchronized(received.toVector)
    def stop(): Unit =
      server.stop(0)
      pool.shutdownNow()
      ()

    private def handle(x: HttpExchange): Unit =
      val seen = Seen(
        x.getRequestMethod,
        x.getRequestURI.getPath,
        x.getRequestHeaders.asScala.map((k, v) => k.toLowerCase -> v.get(0)).toMap,
        new String(x.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      )
      val n = synchronized { received += seen; received.size }
      val a = answer(n, seen)
      if a.delayMs > 0 then Thread.sleep(a.delayMs)
      a.headers.foreach((k, v) => x.getResponseHeaders.add(k, v))
      val bytes = a.body.getBytes(StandardCharsets.UTF_8)
      x.sendResponseHeaders(a.status, if bytes.isEmpty then -1 else bytes.length.toLong)
      if bytes.nonEmpty then x.getResponseBody.write(bytes)
      x.close()

  private def withStub[A](answer: (Int, Seen) => Answer)(body: Stub => A): A =
    val stub = Stub(answer)
    try body(stub)
    finally stub.stop()

  private def answerWith(
      text: String,
      model: String = Model,
      stop: String = "end_turn"
  ): Answer =
    Answer(
      200,
      ujson
        .Obj(
          "model" -> model,
          "stop_reason" -> stop,
          "content" -> ujson.Arr(
            ujson.Obj("type" -> "thinking", "thinking" -> "hidden"),
            ujson.Obj("type" -> "text", "text" -> text)
          ),
          "usage" -> ujson.Obj(
            "input_tokens" -> 100,
            "cache_read_input_tokens" -> 5,
            "output_tokens" -> 20
          )
        )
        .render()
    )

  private def settings(url: String, retries: Int = 2, timeoutMs: Int = 5000) =
    AnthropicConfig(Model, url, "2000-01-02", 5000, timeoutMs, retries)

  private def adapter(
      url: String,
      retries: Int = 2,
      timeoutMs: Int = 5000,
      key: () => String = () => Key,
      pauses: scala.collection.mutable.ArrayBuffer[Long] =
        scala.collection.mutable.ArrayBuffer.empty
  ) =
    AnthropicAdjudicator(
      settings(url, retries, timeoutMs),
      key,
      321,
      10L,
      15L,
      ms => { pauses += ms; () }
    )

  private def request: AdjudicationRequest =
    AdjudicationRequests.build(borderlineResearcherPair, Map("nameSimilarity" -> 1.0), 0.7)

  private def bandCtx(a: Adjudicator, cache: AdjudicationCache) =
    ctx(Thresholds(0.999, 0.01, 0.02, 0.998), Some(service(a, cache)))

  private def abstain(id: String) =
    response("ABSTAIN", Seq(id), Seq.empty, 0.0, Seq("INSUFFICIENT_EVIDENCE"))

  test("request: path, headers, body and the reduced output schema") {
    withStub((_, _) => answerWith("{}")) { stub =>
      val req = request
      val reply = ok(adapter(stub.url).adjudicateWithUsage(prompt, req))
      reply shouldBe AdjudicatorReply("{}", Some(Model), Some(TokenUsage(105, 20)))
      val seen = stub.requests.loneElement
      seen.method shouldBe "POST"
      seen.path shouldBe "/v1/messages"
      seen.headers("x-api-key") shouldBe Key
      seen.headers("anthropic-version") shouldBe "2000-01-02"
      seen.headers("content-type") shouldBe "application/json"
      val body = ujson.read(seen.body)
      body.obj.keySet shouldBe
        Set("model", "max_tokens", "system", "messages", "output_config")
      body("model").str shouldBe Model
      body("max_tokens").num shouldBe 321
      body("system").str shouldBe prompt.text
      val user = body("messages").arr.loneElement
      user("role").str shouldBe "user"
      user("content").str should startWith("UNTRUSTED INPUT DATA")
      user("content").str should endWith(req.canonicalJson)
      val format = body("output_config")("format")
      format("type").str shouldBe "json_schema"
      val schema = format("schema")
      schema("additionalProperties").bool shouldBe false
      schema("required").arr.map(_.str).toSet shouldBe AdjudicationSchema.Keys
      schema("properties").obj.keySet shouldBe AdjudicationSchema.Keys
      schema("properties")("outcome")("enum").arr.map(_.str).toSet shouldBe
        Set("ACCEPT", "REJECT", "ABSTAIN")
      schema("properties")("candidateIds")("minItems").num shouldBe 1
      Seq("minimum", "maximum", "uniqueItems", "pattern", "$schema", "$id").foreach(k =>
        schema.render() should not include s"\"$k\""
      )
    }
  }

  test("429 and 529 are overloaded, retried maxRetries times with the configured delays") {
    for status <- Seq(429, 529) do
      withStub((_, _) => Answer(status, "")) { stub =>
        val pauses = scala.collection.mutable.ArrayBuffer.empty[Long]
        val failure =
          adapter(stub.url, pauses = pauses).call(prompt, request).left.toOption.get
        failure.kind shouldBe AnthropicFailureKind.Overloaded
        failure.detail shouldBe s"HTTP $status after 3 attempt(s)"
        stub.requests.size shouldBe 3
        pauses.toVector shouldBe Vector(10L, 15L)
      }
  }

  test("a retry succeeds after a 429; retry-after never exceeds the configured maximum") {
    withStub((n, _) =>
      if n == 1 then Answer(429, "", Map("retry-after" -> "3600")) else answerWith("{}")
    ) { stub =>
      val pauses = scala.collection.mutable.ArrayBuffer.empty[Long]
      adapter(stub.url, pauses = pauses).call(prompt, request).map(_.text) shouldBe Right("{}")
      stub.requests.size shouldBe 2
      pauses.toVector shouldBe Vector(15L)
    }
  }

  test("other 5xx including 504 is transient; other 4xx including 408 is permanent") {
    import AnthropicFailureKind.*
    for (status, kind, attempts) <- Seq(
        (500, Transient, 3),
        (504, Transient, 3),
        (400, Permanent, 1),
        (401, Permanent, 1),
        (408, Permanent, 1)
      )
    do
      withStub((_, _) => Answer(status, "")) { stub =>
        adapter(stub.url).call(prompt, request).left.map(_.kind) shouldBe Left(kind)
        stub.requests.size shouldBe attempts
      }
  }

  test("the client deadline is a time-out, one per attempt") {
    withStub((_, _) => answerWith("{}").copy(delayMs = 2000)) { stub =>
      val failure = adapter(stub.url, retries = 1, timeoutMs = 100)
        .call(prompt, request)
        .left
        .toOption
        .get
      failure.kind shouldBe AnthropicFailureKind.TimedOut
      failure.detail should endWith("after 2 attempt(s)")
    }
  }

  test("redirects are not followed, so the key does not travel") {
    withStub((_, _) => answerWith("{}")) { target =>
      withStub((_, _) => Answer(307, "", Map("location" -> s"${target.url}/v1/messages"))) { stub =>
        adapter(stub.url).call(prompt, request).left.map(_.kind) shouldBe
          Left(AnthropicFailureKind.Permanent)
        target.requests shouldBe empty
      }
    }
  }

  test("failure details hold no body, provider message or key") {
    val body = ujson
      .Obj("error" -> ujson.Obj("type" -> "authentication_error", "message" -> s"bad key $Key"))
      .render()
    withStub((_, _) => Answer(401, body)) { stub =>
      adapter(stub.url).adjudicate(prompt, request) shouldBe Left(
        "Permanent: Anthropic API: HTTP 401 (authentication_error) after 1 attempt(s)"
      )
    }
    withStub((_, _) => Answer(400, s"""{"error":{"type":"$Key"}}""")) { stub =>
      adapter(stub.url).adjudicate(prompt, request).left.toOption.get should not include Key
    }
  }

  test("a missing, blank or unusable key fails permanently and sends nothing") {
    withStub((_, _) => answerWith("{}")) { stub =>
      val keys: Seq[() => String] =
        Seq(() => null, () => "  ", () => "a\nb", () => throw new RuntimeException(Key))
      keys.foreach { key =>
        val failure = adapter(stub.url, key = key).call(prompt, request).left.toOption.get
        failure.kind shouldBe AnthropicFailureKind.Permanent
        failure.message should not include Key
        failure.message should not include "a\nb"
      }
      stub.requests shouldBe empty
    }
  }

  test("refusal, empty and unreadable answers are failures, not model outputs") {
    for (answer, kind) <- Seq(
        answerWith("{}", stop = "refusal") -> AnthropicFailureKind.Permanent,
        answerWith("") -> AnthropicFailureKind.Transient,
        Answer(200, "not json") -> AnthropicFailureKind.Transient
      )
    do
      withStub((_, _) => answer) { stub =>
        adapter(stub.url).call(prompt, request).left.map(_.kind) shouldBe Left(kind)
      }
  }

  private val deep = "[" * 200000 + "]" * 200000

  test("a deeply nested body is a typed failure, as an answer or as an error body") {
    for body <- Seq(deep, s"""{"content":$deep,"usage":{"output_tokens":1}}""") do
      withStub((_, _) => Answer(200, body)) { stub =>
        adapter(stub.url).call(prompt, request) shouldBe Left(
          AnthropicFailure(AnthropicFailureKind.Transient, "the response could not be read")
        )
      }
    withStub((_, _) => Answer(400, deep)) { stub =>
      adapter(stub.url).call(prompt, request) shouldBe Left(
        AnthropicFailure(AnthropicFailureKind.Permanent, "HTTP 400 after 1 attempt(s)")
      )
    }
  }

  test("deeply nested model output is refused before parsing; the bound ignores strings") {
    val nested = "[" * (BoundedJson.MaxDepth + 1) + "]" * (BoundedJson.MaxDepth + 1)
    AdjudicationSchema.parse(nested, request) shouldBe Left(Vector("TOO_DEEP"))
    AdjudicationSchema.parse(nested.drop(1).dropRight(1), request) shouldBe
      Left(Vector("NOT_AN_OBJECT"))
    BoundedJson.tooDeep("\"" + "[" * 1000 + "\\\"{\"") shouldBe false
    val cache = InMemoryAdjudicationCache()
    val r = ok(
      MatchPolicy.decide(
        borderlineResearcherPair,
        bandCtx(ScriptedAdjudicator(_ => Right(deep)), cache)
      )
    )
    r.decision.reasonCodes shouldBe Vector("LLM_OUTPUT_INVALID")
  }

  test("only the configured model ID or its dated snapshot is recorded from a response") {
    val echoed = Seq(
      Key,
      s"Bearer $Key",
      s"$Key\n",
      "other-model",
      s"$Model-$Key",
      s"$Model-2000010",
      s"$Model-20000101x",
      ""
    )
    for (answered, recorded) <-
        Seq(Model -> Model, s"$Model-20000101" -> s"$Model-20000101") ++ echoed.map(_ -> Model)
    do
      withStub((_, _) => answerWith("{}", model = answered)) { stub =>
        val cache = InMemoryAdjudicationCache()
        val result = service(adapter(stub.url), cache).adjudicate(request)
        result.modelVersion shouldBe recorded
        cache.all.map(_.answeredModelVersion) shouldBe Vector(recorded)
      }
  }

  test("an interrupted thread sends nothing and stays interrupted") {
    withStub((_, _) => answerWith("{}")) { stub =>
      val a = adapter(stub.url)
      Thread.currentThread().interrupt()
      val result = a.call(prompt, request)
      val stillInterrupted = Thread.interrupted() // also clears it for the other tests
      stillInterrupted shouldBe true
      result.left.map(_.kind) shouldBe Left(AnthropicFailureKind.Transient)
      result.left.toOption.get.detail should include("after 0 attempt(s)")
      Thread.sleep(200)
      stub.requests shouldBe empty
    }
  }

  test("ABSTAIN stays UNRESOLVED; the answering model and usage are recorded and replayed") {
    val answered = Model + "-20000101"
    withStub((_, seen) =>
      val content = ujson.read(seen.body)("messages")(0)("content").str
      val id = ujson.read(content.drop(content.indexOf('\n') + 1))("candidateId").str
      answerWith(abstain(id), model = answered)
    ) { stub =>
      val path = Files.createTempDirectory("er-anthropic").resolve("cache.jsonl")
      val cache = JsonlAdjudicationCache(path)
      val cold =
        ok(MatchPolicy.decide(borderlineResearcherPair, bandCtx(adapter(stub.url), cache)))
      cold.decision.decision shouldBe MatchOutcome.UNRESOLVED
      cold.decision.reasonCodes.head shouldBe "LLM_ABSTAINED"
      cold.decision.modelVersion shouldBe Some(answered)
      cold.decision.provenance.modelVersion shouldBe Some(answered)
      cold.adjudication.get.key.modelVersion shouldBe Model
      cold.adjudication.get.usage shouldBe Some(TokenUsage(105, 20))
      cache.flush()
      val warm = ok(
        MatchPolicy.decide(
          borderlineResearcherPair,
          bandCtx(adapter(stub.url), JsonlAdjudicationCache(path))
        )
      )
      warm.adjudication.get.cacheHit shouldBe true
      warm.adjudication.get.usage shouldBe Some(TokenUsage(105, 20))
      warm.decision shouldBe cold.decision
      stub.requests.size shouldBe 1
    }
  }

  test("a Bedrock-era cached decision is not reused for the Anthropic model") {
    val cache = InMemoryAdjudicationCache()
    val earlier = ScriptedAdjudicator(
      r => Right(response("ACCEPT", Seq(r.leftRef))),
      modelVersion = "test-bedrock-model"
    )
    ok(MatchPolicy.decide(borderlineResearcherPair, bandCtx(earlier, cache)))
    withStub((_, _) => answerWith("not the schema")) { stub =>
      val r =
        ok(MatchPolicy.decide(borderlineResearcherPair, bandCtx(adapter(stub.url), cache)))
      stub.requests.size shouldBe 1
      // the local schema still rejects what the provider returned
      r.decision.reasonCodes shouldBe Vector("LLM_OUTPUT_INVALID")
      r.decision.modelVersion shouldBe Some(Model)
      cache.all.map(_.modelVersion) shouldBe Vector("test-bedrock-model", Model)
    }
  }

  test("a provider failure leaves the pair UNRESOLVED and is not cached") {
    withStub((_, _) => Answer(529, "")) { stub =>
      val cache = InMemoryAdjudicationCache()
      val r =
        ok(MatchPolicy.decide(borderlineResearcherPair, bandCtx(adapter(stub.url), cache)))
      r.decision.decision shouldBe MatchOutcome.UNRESOLVED
      r.decision.reasonCodes shouldBe Vector("LLM_UNAVAILABLE")
      cache.all shouldBe empty
    }
  }

  test("an unfinished answer is a permanent failure, even with complete text; one request") {
    val unfinished = Seq(
      "max_tokens" -> "max_tokens",
      "model_context_window_exceeded" -> "model_context_window_exceeded",
      "pause_turn" -> "pause_turn",
      "tool_use" -> "tool_use",
      Key -> "no recognised stop reason"
    )
    for (stop, named) <- unfinished; text <- Seq("{\"outcome\":", "{}", "") do
      withStub((_, _) => answerWith(text, stop = stop)) { stub =>
        adapter(stub.url).call(prompt, request) shouldBe Left(
          AnthropicFailure(AnthropicFailureKind.Permanent, s"the model stopped: $named")
        )
        stub.requests.size shouldBe 1
      }
    val noStopReason = ujson.read(answerWith("{}").body)
    noStopReason.obj.remove("stop_reason")
    withStub((_, _) => Answer(200, noStopReason.render())) { stub =>
      adapter(stub.url).call(prompt, request).left.map(_.kind) shouldBe
        Left(AnthropicFailureKind.Permanent)
    }
  }

  test("a truncated answer is not cached or used; the pair is asked again on the next run") {
    withStub((n, seen) =>
      val content = ujson.read(seen.body)("messages")(0)("content").str
      val id = ujson.read(content.drop(content.indexOf('\n') + 1))("candidateId").str
      // the first answer is complete in form, but the provider says it was cut off
      if n == 1 then answerWith(abstain(id), stop = "max_tokens")
      else answerWith(abstain(id))
    ) { stub =>
      val path = Files.createTempDirectory("er-truncated").resolve("cache.jsonl")
      def decide() =
        val cache = JsonlAdjudicationCache(path)
        val result =
          ok(MatchPolicy.decide(borderlineResearcherPair, bandCtx(adapter(stub.url), cache)))
        cache.flush()
        (result, cache.size)
      val (first, cachedAfterFirst) = decide()
      first.decision.decision shouldBe MatchOutcome.UNRESOLVED
      first.decision.reasonCodes shouldBe Vector("LLM_UNAVAILABLE")
      first.adjudication.get.rawOutput shouldBe None
      cachedAfterFirst shouldBe 0
      val (second, cachedAfterSecond) = decide()
      stub.requests.size shouldBe 2
      second.adjudication.get.cacheHit shouldBe false
      second.decision.reasonCodes.head shouldBe "LLM_ABSTAINED"
      cachedAfterSecond shouldBe 1
    }
  }

  test("provider selection: anthropic builds the adapter; bedrock is refused") {
    val built = ConfiguredAdjudicator(
      LlmProviderConfig.Anthropic(settings("http://127.0.0.1:1")),
      () => Key,
      10,
      1L,
      1L
    )
    built.map(_.modelVersion) shouldBe Right(Model)
    ConfiguredAdjudicator(
      LlmProviderConfig.Bedrock("s", "v"),
      () => Key,
      10,
      1L,
      1L
    ) shouldBe Left(ConfiguredAdjudicator.BedrockUnavailable)
  }

  // --- Run entry point -------------------------------------------------------------------------

  private val fixture: Path =
    Paths.get(getClass.getClassLoader.getResource("phase4-fixture").toURI)

  private val allBand = Map(
    "SCHOLARCHAIN_ENV" -> "er-test",
    "SCHOLARCHAIN_ER_POLICY_VERSION" -> "er-v1",
    "SCHOLARCHAIN_ER_ACCEPT_THRESHOLD" -> "0.999",
    "SCHOLARCHAIN_ER_REJECT_THRESHOLD" -> "0.0",
    "SCHOLARCHAIN_ER_LLM_BAND_MIN" -> "0.0",
    "SCHOLARCHAIN_ER_LLM_BAND_MAX" -> "0.999"
  )

  private def anthropicEnv(url: String) = allBand ++ Map(
    "SCHOLARCHAIN_LLM_PROVIDER" -> "anthropic",
    "SCHOLARCHAIN_ANTHROPIC_MODEL_ID" -> Model,
    "SCHOLARCHAIN_ANTHROPIC_BASE_URL" -> url,
    "SCHOLARCHAIN_ANTHROPIC_VERSION" -> "2000-01-02",
    "SCHOLARCHAIN_ANTHROPIC_CONNECT_TIMEOUT_MS" -> "5000",
    "SCHOLARCHAIN_ANTHROPIC_REQUEST_TIMEOUT_MS" -> "5000",
    "SCHOLARCHAIN_ANTHROPIC_MAX_RETRIES" -> "0",
    "ANTHROPIC_API_KEY" -> Key
  )

  private def run(out: Path, mode: String, env: Map[String, String]) =
    ResolveSample.run(
      Seq(
        "--hadoop-root",
        fixture.toString,
        "--output-root",
        out.toString,
        "--adjudicator",
        mode,
        "--decided-at",
        "2026-11-20T00:00:00Z",
        "--er-policy-properties",
        erPropertiesFile.toString
      ),
      env
    )

  private def isEmptyDir(p: Path): Boolean =
    Using.resource(Files.list(p))(!_.iterator().hasNext)

  test("an unknown or absent provider is rejected at startup in every adjudicator mode") {
    val out = Files.createTempDirectory("er-provider")
    for mode <- Seq("off", "fake", "llm", "bedrock") do
      run(out, mode, allBand + ("SCHOLARCHAIN_LLM_PROVIDER" -> "other")).left.toOption.get should
        include("unknown provider")
      run(out, mode, allBand).left.toOption.get should include("SCHOLARCHAIN_LLM_PROVIDER")
    isEmptyDir(out) shouldBe true
  }

  test("llm mode refuses a missing key and the bedrock provider before any output") {
    val out = Files.createTempDirectory("er-provider")
    run(out, "llm", anthropicEnv("http://127.0.0.1:1") - "ANTHROPIC_API_KEY").left.toOption.get
      .should(include("ANTHROPIC_API_KEY"))
    run(
      out,
      "llm",
      allBand ++ Map(
        "SCHOLARCHAIN_LLM_PROVIDER" -> "bedrock",
        "SYNTHESIS_MODEL_ID" -> "s",
        "VERIFICATION_MODEL_ID" -> "v"
      )
    ).left.toOption.get should include("domain-engineer")
    isEmptyDir(out) shouldBe true
  }

  test("llm run: decisions, manifest and summary name the Anthropic model; no key is written") {
    withStub((_, seen) =>
      val content = ujson.read(seen.body)("messages")(0)("content").str
      val id = ujson.read(content.drop(content.indexOf('\n') + 1))("candidateId").str
      answerWith(abstain(id))
    ) { stub =>
      val out = Files.createTempDirectory("er-llm")
      val cold = ujson.read(ok[String](run(out, "llm", anthropicEnv(stub.url))))
      val calls = stub.requests.size
      calls should be > 0
      cold("adjudicationModelVersion").str shouldBe Model
      cold("adjudicationProvider").str shouldBe "anthropic"
      cold("adjudicatorIsFake").bool shouldBe false
      val answers = cold("adjudicationAnswers")
      answers("count").num shouldBe calls
      answers("modelVersions").arr.map(_.str).toVector shouldBe Vector(Model)
      answers("inputTokens").num shouldBe 105 * calls
      answers("outputTokens").num shouldBe 20 * calls
      val dir = Paths.get(cold("outputDir").str)
      val manifest = ujson.read(Files.readString(dir.resolve("run-manifest.json")))
      manifest.render() should include("\"llm.provider\":\"anthropic\"")
      manifest.render() should include(s"\"adjudicationModelVersion\":\"$Model\"")
      val adjudicated = Files
        .readString(dir.resolve("decisions/unresolved.jsonl"))
        .linesIterator
        .filter(_.nonEmpty)
        .map(l => ok(WireCodec.decode[MatchDecision](l).left.map(_.toString)))
        .filter(_.modelVersion.isDefined)
        .toVector
      adjudicated.size shouldBe calls
      adjudicated.map(_.modelVersion).distinct shouldBe Vector(Some(Model))
      // warm cache: same bytes (the commit is write-once) and no further calls
      ujson.read(ok[String](run(out, "llm", anthropicEnv(stub.url)))) shouldBe cold
      stub.requests.size shouldBe calls
      Using
        .resource(Files.walk(out))(
          _.iterator().asScala.filter(Files.isRegularFile(_)).map(Files.readString(_)).toVector
        )
        .foreach(_ should not include Key)
    }
  }
