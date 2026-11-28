package scholarchain.adversarial.provider

import java.util.concurrent.atomic.AtomicBoolean
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.config.RagLlmConfig
import scholarchain.er.adjudication.*
import scholarchain.rag.agents.StageFailure
import scholarchain.rag.llm.{Prompt as _, *}
import ProviderAdvKit.*

/** Both Anthropic adapters (`rag` AnthropicLlmClient and `entityResolution`
  * AnthropicAdjudicator) attacked with a loopback stub. The two hold duplicated HTTP and
  * redaction code, so every attack is run against both.
  */
class AnthropicAdaptersAdvSpec extends AnyFunSuite with Matchers:

  private val schema =
    JsonSchema
      .parse(
        "adv-v1",
        """{"type":"object","additionalProperties":false,"required":["a"],
          |"properties":{"a":{"type":"integer"}}}""".stripMargin
      )
      .toOption
      .get

  private def errorBody(errorType: String, text: String): String =
    ujson
      .Obj(
        "type" -> "error",
        "error" -> ujson.Obj("type" -> errorType, "message" -> text),
        "request_id" -> text
      )
      .render()

  test("every status is classified and bounded identically by both adapters") {
    val table = Vector(
      429 -> "Overloaded",
      529 -> "Overloaded",
      500 -> "Transient",
      501 -> "Transient",
      503 -> "Transient",
      504 -> "Transient",
      520 -> "Transient",
      599 -> "Transient",
      201 -> "Permanent",
      301 -> "Permanent",
      400 -> "Permanent",
      401 -> "Permanent",
      403 -> "Permanent",
      408 -> "Permanent",
      409 -> "Permanent",
      418 -> "Permanent",
      425 -> "Permanent"
    )
    for (status, expected) <- table do
      script(Reply(status, errorBody("api_error", "x"))) { s =>
        var before = 0
        bothAdapters(s.url).foreach { (name, call) =>
          val seen = call()
          withClue(s"$name HTTP $status: ") {
            seen.kind shouldBe expected
            s.requests.size - before shouldBe (if expected == "Permanent" then 1 else 3)
          }
          before = s.requests.size
        }
      }
  }

  test("429 then 529 then success succeeds on the third request, with the configured delays") {
    val replies =
      Seq(Reply(429, errorBody("rate_limit_error", "slow down")), Reply(529), okMessage("{}"))
    script(replies*) { s =>
      val pauses = new Pauses
      ragClient(s.url, pauses = pauses).complete(llmRequest).map(_.text) shouldBe Right("{}")
      pauses.all.toVector shouldBe Vector(7L, 14L)
      s.requests.size shouldBe 3
      // a retry sends exactly the same request, still with one key header
      s.requests.map(r => (r.body, r.headers("x-api-key"))).distinct.size shouldBe 1
    }
    script(replies*) { s =>
      val pauses = new Pauses
      erAdapter(s.url, pauses = pauses)
        .call(prompt, adjudicationRequest)
        .map(_.text) shouldBe Right("{}")
      pauses.all.toVector shouldBe Vector(7L, 14L)
      s.requests.size shouldBe 3
    }
  }

  test("success on the last allowed attempt succeeds; one failure more fails") {
    for retries <- Seq(0, 1, 3) do
      for failures <- Seq(retries, retries + 1) do
        val replies = Vector.fill(failures)(Reply(429)) :+ okMessage("{}")
        val expected = if failures <= retries then "ok" else "Overloaded"
        for name <- Seq("rag", "er") do
          script(replies*) { s =>
            val call = bothAdapters(s.url, retries = retries).toMap.apply(name)
            withClue(s"$name retries=$retries failures=$failures: ") {
              call().kind shouldBe expected
              s.requests.size shouldBe retries + 1
            }
          }
  }

  test("hostile retry-after values never lengthen a delay past the maximum or shorten the base") {
    val values = Seq(
      "999999999",
      "9223372036854775807",
      "99999999999999999999999999",
      "-5",
      "-9223372036854775808",
      "1.5",
      "NaN",
      "Wed, 21 Oct 2099 07:28:00 GMT",
      ""
    )
    for value <- values do
      val limited = Reply(429, headers = Map("retry-after" -> value))
      for name <- Seq("rag", "er", "voyage") do
        val pauses = new Pauses
        script(limited) { s =>
          name match
            case "rag" => ragClient(s.url, retries = 2, pauses = pauses).complete(llmRequest)
            case "er" =>
              erAdapter(s.url, retries = 2, pauses = pauses).call(prompt, adjudicationRequest)
            case _ =>
              an[scholarchain.rag.embed.EmbeddingProviderException] should be thrownBy
                voyage(s.url, retries = 2, pauses = pauses).embed(Seq("a"))
        }
        withClue(s"$name retry-after='$value': ") {
          pauses.all.size shouldBe 2
          pauses.all.foreach { p =>
            p should be >= Delays.baseDelayMs
            p should be <= Delays.maxDelayMs
          }
        }
  }

  test("a stalled answer is TimedOut, retried to the bound and returns in bounded time") {
    for name <- Seq("rag", "er") do
      stub((_, _) => Reply(200, "{}", stall = true)) { s =>
        val call = bothAdapters(s.url, retries = 2, timeoutMs = 250).toMap.apply(name)
        val started = System.nanoTime()
        val seen = call()
        val tookMs = (System.nanoTime() - started) / 1000000L
        withClue(s"$name: ") {
          seen.kind shouldBe "TimedOut"
          s.requests.size shouldBe 3
          tookMs should be >= 700L
          tookMs should be < 10000L
        }
      }
  }

  test("a cut-off body and a non-HTTP answer are Transient and bounded") {
    val raws = Seq(
      "HTTP/1.1 200 OK\r\nContent-Length: 500\r\nContent-Type: application/json\r\n\r\n{\"mod",
      "NOT HTTP AT ALL\r\n\r\n",
      ""
    )
    for raw <- raws; name <- Seq("rag", "er") do
      rawServer(raw) { (url, connections) =>
        val seen = bothAdapters(url, retries = 1, timeoutMs = 3000).toMap.apply(name)()
        withClue(s"$name raw='${raw.take(20)}': ") {
          seen.kind shouldBe "Transient"
          connections() should be >= 2
          connections() should be <= 4
        }
      }
  }

  test("malformed and hostile 200 envelopes never succeed, never throw, never echo the body") {
    val k = AnthropicKey
    val bodies = Seq(
      "",
      " ",
      "null",
      "42",
      "[]",
      s"\"$k\"",
      s"<html>$k</html>",
      s"""{"content":[{"type":"text","text":"$k"}""",
      """{"content":null,"usage":{"output_tokens":1}}""",
      """{"content":[null],"usage":{"output_tokens":1}}""",
      """{"content":[{"text":"x"}],"usage":{"output_tokens":1}}""",
      """{"content":[{"type":"text","text":5}],"usage":{"output_tokens":1}}""",
      """{"content":[{"type":"text","text":"x"}]}""",
      """{"content":[{"type":"text","text":"x"}],"usage":null}""",
      """{"content":[{"type":"text","text":"x"}],"usage":{}}""",
      """{"content":[{"type":"text","text":"x"}],"usage":{"output_tokens":"2"}}""",
      s"""{"type":"error","error":{"type":"api_error","message":"$k"}}"""
    )
    for body <- bodies; name <- Seq("rag", "er") do
      script(Reply(200, body)) { s =>
        val seen = bothAdapters(s.url).toMap.apply(name)()
        withClue(s"$name body='${body.take(60)}': ") {
          seen.kind shouldBe "Transient"
          seen.text should not include Canary
          s.requests.size shouldBe 1
        }
      }
  }

  private val deep = "[" * 200000 + "]" * 200000

  /** Reports a stack overflow as a failed test; left uncaught it would abort the whole suite. */
  private def typedKind(call: () => Seen1): String =
    try call().kind
    catch case _: StackOverflowError => "StackOverflowError escaped the adapter"

  test("rag adapter: a deeply nested 200 body is a typed failure, not a stack overflow") {
    for body <- Seq(deep, s"""{"content":$deep,"usage":{"output_tokens":1}}""") do
      script(Reply(200, body)) { s =>
        typedKind(bothAdapters(s.url).toMap.apply("rag")) shouldBe "Transient"
      }
  }

  test("entity-resolution adapter: a deeply nested 200 body is a typed failure") {
    for body <- Seq(deep, s"""{"content":$deep,"usage":{"output_tokens":1}}""") do
      script(Reply(200, body)) { s =>
        typedKind(bothAdapters(s.url).toMap.apply("er")) shouldBe "Transient"
      }
  }

  test("key-shaped text in error bodies, error types and headers is redacted") {
    val k = AnthropicKey
    val bodies = Seq(
      errorBody("authentication_error", s"invalid x-api-key: $k"),
      errorBody(k, k),
      errorBody(s"api_error $k", k),
      errorBody(s"api_error\n$k", k),
      s"""{"error":"$k"}""",
      s"""{"error":{"type":["$k"]}}""",
      s"""{"$k":{"type":"api_error"}}""",
      s"Bearer $k",
      "{\"error\":{\"type\":" * 3000 + s"\"$k\"" + "}}" * 3000,
      deep
    )
    for status <- Seq(400, 401, 429, 500, 529); body <- bodies; name <- Seq("rag", "er") do
      script(Reply(status, body, Map("x-echo" -> k, "retry-after" -> k))) { s =>
        val seen = bothAdapters(s.url, retries = 1).toMap.apply(name)()
        withClue(s"$name HTTP $status body='${body.take(60)}': ") {
          seen.kind should not be "ok"
          seen.text should include(s"HTTP $status")
          seen.text should not include Canary
          seen.text should not include "sk-ant"
        }
      }
  }

  test("the key travels only in the x-api-key header: not in the URL or the body") {
    script(okMessage("{}")) { s =>
      bothAdapters(s.url).foreach((_, call) => call())
      s.requests.size shouldBe 2
      s.requests.foreach { r =>
        r.headers("x-api-key") shouldBe AnthropicKey
        r.path shouldBe "/v1/messages"
        r.body should not include Canary
        (r.headers - "x-api-key").values.mkString should not include Canary
      }
    }
  }

  test("missing, injectable or unreadable keys are Permanent, send nothing and are not echoed") {
    val k = AnthropicKey
    val suppliers: Seq[(String, () => String)] = Seq(
      "null" -> (() => null),
      "empty" -> (() => ""),
      "white space" -> (() => " \t\r\n "),
      "CRLF injection" -> (() => s"$k\r\nx-injected: 1"),
      "LF inside" -> (() => s"$k\nmore"),
      "CR inside" -> (() => s"$k\rmore"),
      "NUL inside" -> (() => k + "\u0000more"),
      "space inside" -> (() => s"$k more"),
      "tab inside" -> (() => s"$k\tmore"),
      "DEL" -> (() => k + "\u007f"),
      "non-ASCII" -> (() => k + "\u00e9"),
      "line separator" -> (() => k + "\u2028x"),
      "supplier throws" -> (() => throw new RuntimeException(s"secret was $k"))
    )
    script(okMessage("{}")) { s =>
      for (label, supplier) <- suppliers do
        bothAdapters(s.url, key = supplier).foreach { (name, call) =>
          val seen = call()
          withClue(s"$name $label: ") {
            seen.kind shouldBe "Permanent"
            seen.text should not include Canary
          }
        }
      s.requests shouldBe empty
    }
  }

  test("surrounding white space of a key is trimmed before it becomes a header") {
    script(okMessage("{}")) { s =>
      bothAdapters(s.url, key = () => s"  $AnthropicKey\r\n").foreach((_, call) => call())
      s.requests.map(_.headers("x-api-key")) shouldBe Vector(AnthropicKey, AnthropicKey)
    }
  }

  test("no redirect status is followed, so the key never reaches the redirect target") {
    stub((_, _) => okMessage("{}")) { target =>
      for status <- Seq(301, 302, 303, 307, 308) do
        script(Reply(status, headers = Map("location" -> s"${target.url}/v1/messages"))) { s =>
          bothAdapters(s.url).foreach { (name, call) =>
            withClue(s"$name $status: ")(call().kind shouldBe "Permanent")
          }
          s.requests.size shouldBe 2
        }
      target.requests shouldBe empty
    }
  }

  test("an interrupt while a request is in flight stops all further requests, both adapters") {
    for name <- Seq("rag", "er") do
      stub((_, _) => Reply(200, "{}", stall = true)) { s =>
        val done = new AtomicBoolean(false)
        val (seen, interrupted) = onThread {
          val worker = Thread.currentThread()
          val watchdog = new Thread(() =>
            while s.requests.isEmpty && !done.get do Thread.sleep(5)
            worker.interrupt()
          )
          watchdog.start()
          try bothAdapters(s.url, retries = 5, timeoutMs = 20000).toMap.apply(name)()
          finally done.set(true)
        }
        Thread.sleep(400)
        withClue(s"$name: ") {
          interrupted shouldBe true
          seen.kind shouldBe "Transient"
          s.requests.size shouldBe 1
        }
      }
  }

  test("an interrupt during the retry delay stops all further requests, both adapters") {
    final class Interrupting extends Backoff:
      def pause(millis: Long): Unit = throw new InterruptedException
    script(Reply(429)) { s =>
      val (_, ragInterrupted) = onThread {
        new AnthropicLlmClient(
          anthropicConfig(s.url, retries = 5),
          () => AnthropicKey,
          Delays,
          new Interrupting
        ).complete(llmRequest)
      }
      ragInterrupted shouldBe true
      s.requests.size shouldBe 1
      val (_, erInterrupted) = onThread {
        new AnthropicAdjudicator(
          anthropicConfig(s.url, retries = 5),
          () => AnthropicKey,
          64,
          7L,
          50L,
          _ => throw new InterruptedException
        ).call(prompt, adjudicationRequest)
      }
      erInterrupted shouldBe true
      s.requests.size shouldBe 2
    }
  }

  /** A request that is started and cancelled at once can still reach (and be billed by) the
    * provider, so the call is repeated on one adapter with an open connection, as a batch run
    * would hold; no interrupted call may leave the process. Returns the requests beyond the
    * warm-up ones and whether the interrupt status was always kept.
    */
  private def interruptedCallsReaching(name: String): (Int, Boolean) =
    stub((_, _) => okMessage("{}")) { s =>
      val call = bothAdapters(s.url).toMap.apply(name)
      var warmUps = 0
      var stillInterrupted = true
      for _ <- 1 to 40 do
        call().kind shouldBe "ok"
        warmUps += 1
        val (_, interrupted) = onThread {
          Thread.currentThread().interrupt()
          call()
        }
        stillInterrupted &&= interrupted
      Thread.sleep(1000)
      (s.requests.size - warmUps, stillInterrupted)
    }

  test("rag adapter: a call on an already interrupted thread sends nothing") {
    interruptedCallsReaching("rag") shouldBe ((0, true))
  }

  test("entity-resolution adapter: a call on an already interrupted thread sends nothing") {
    interruptedCallsReaching("er") shouldBe ((0, true))
  }

  // --- key echoed in a successful answer -------------------------------------------------------

  test("rag adapter: a key echoed in the model-name field is not recorded as the model version") {
    for echoed <- Seq(AnthropicKey, s"Bearer $AnthropicKey", s"$AnthropicKey\n") do
      script(okMessage("{}", model = echoed)) { s =>
        val reply = ragClient(s.url).complete(llmRequest).toOption.get
        reply.modelVersion should not include Canary
      }
  }

  test("entity-resolution adapter: a key echoed in the model-name field is not recorded") {
    for echoed <- Seq(AnthropicKey, s"Bearer $AnthropicKey", s"$AnthropicKey\n") do
      script(okMessage("{}", model = echoed)) { s =>
        val reply = erAdapter(s.url).call(prompt, adjudicationRequest).toOption.get
        reply.modelVersion.getOrElse("") should not include Canary
      }
  }

  // --- the structured call on top of the real adapter ------------------------------------------

  private def structured(url: String, retries: Int, llmRetries: Int, timeoutMs: Int = 10000) =
    new StructuredLlmCall(ragClient(url, retries, timeoutMs), RagLlmConfig(llmRetries, 64))
      .run("p-v1", "SYSTEM", """{"question":"q"}""", schema)

  test("persistent 529: requests are bounded by (adapter retries+1) x (call retries+1)") {
    script(Reply(529, errorBody("overloaded_error", AnthropicKey))) { s =>
      val result = structured(s.url, retries = 2, llmRetries = 1)
      s.requests.size shouldBe 6
      val failure = result.outcome.left.toOption.get
      failure shouldBe a[StageFailure.Overloaded]
      failure.toString should not include Canary
      result.usage.calls shouldBe 2
    }
  }

  test("persistent stall ends as a stage time-out; a permanent status sends one request") {
    stub((_, _) => Reply(200, "{}", stall = true)) { s =>
      val result = structured(s.url, retries = 0, llmRetries = 1, timeoutMs = 250)
      result.outcome.left.toOption.get shouldBe a[StageFailure.Timeout]
      s.requests.size shouldBe 2
    }
    script(Reply(401, errorBody("authentication_error", AnthropicKey))) { s =>
      val result = structured(s.url, retries = 3, llmRetries = 3)
      s.requests.size shouldBe 1
      result.outcome.left.toOption.get.toString should not include Canary
    }
  }

  test("invalid model text, a key echoed in it included, is rejected and not repeated") {
    val texts = Seq(
      AnthropicKey,
      s"""{"a":"$AnthropicKey"}""",
      s"""{"a":1} $AnthropicKey""",
      "```json\n{\"a\":1}\n```",
      "{\"a\":NaN}",
      "{\"a\":Infinity}",
      "{\"a\":1.5}",
      "{}",
      ""
    )
    for text <- texts do
      script(okMessage(text)) { s =>
        val result = structured(s.url, retries = 0, llmRetries = 1)
        withClue(s"text='$text': ") {
          val failure = result.outcome.left.toOption.get
          failure.code shouldBe "MODEL_OUTPUT_INVALID"
          failure.toString should not include Canary
          s.requests.size shouldBe 2
        }
      }
  }

  test("a property name chosen by the model is not repeated in the rejection") {
    script(okMessage(s"""{"a":1,"$AnthropicKey":2}""")) { s =>
      val failure = structured(s.url, retries = 0, llmRetries = 0).outcome.left.toOption.get
      failure.code shouldBe "MODEL_OUTPUT_INVALID"
      failure.toString should not include Canary
    }
  }

  // --- the adjudication service on top of the real adapter -------------------------------------

  private def adjudicate(url: String, cache: InMemoryAdjudicationCache, retries: Int = 1) =
    new AdjudicationService(erAdapter(url, retries), cache, prompt, "er-v1")
      .adjudicate(adjudicationRequest)

  test("no provider failure is cached, and none of its text enters the result") {
    val k = AnthropicKey
    val replies = Seq(
      Reply(429, errorBody("rate_limit_error", k)),
      Reply(529, k),
      Reply(500, k),
      Reply(401, errorBody("authentication_error", k)),
      Reply(200, s"not json $k"),
      okMessage(k, stop = "refusal"),
      okMessage("")
    )
    for reply <- replies do
      script(reply) { s =>
        val cache = new InMemoryAdjudicationCache
        val result = adjudicate(s.url, cache)
        withClue(s"HTTP ${reply.status}: ") {
          result.rawOutput shouldBe None
          result.parsed.left.toOption.get.head should startWith("TRANSPORT_ERROR")
          result.toString should not include Canary
          cache.all shouldBe empty
        }
      }
  }

  test("after a failed run, a later run asks again and can succeed") {
    val answer = ujson
      .Obj(
        "outcome" -> "ABSTAIN",
        "candidateIds" -> ujson.Arr("cand-1"),
        "citedFields" -> ujson.Arr(),
        "confidence" -> 0,
        "reasonCodes" -> ujson.Arr("INSUFFICIENT_EVIDENCE")
      )
      .render()
    script(Reply(529), Reply(529), okMessage(answer)) { s =>
      val cache = new InMemoryAdjudicationCache
      adjudicate(s.url, cache).parsed.isLeft shouldBe true
      val second = adjudicate(s.url, cache)
      second.cacheHit shouldBe false
      second.parsed.map(_.outcome) shouldBe Right(AdjudicationOutcome.ABSTAIN)
      cache.all.size shouldBe 1
      s.requests.size shouldBe 3
    }
  }

  test("non-finite, out-of-range and invented adjudication values are rejected") {
    def answer(confidence: String, id: String = "cand-1") =
      s"""{"outcome":"ACCEPT","candidateIds":["$id"],"citedFields":["score"],""" +
        s""""confidence":$confidence,"reasonCodes":["X"]}"""
    for text <- Seq(
        answer("NaN"),
        answer("Infinity"),
        answer("\"NaN\""),
        answer("1e999"),
        answer("-0.1"),
        answer("1.0000001"),
        answer("0.9", "cand-invented")
      )
    do
      script(okMessage(text)) { s =>
        val result = adjudicate(s.url, new InMemoryAdjudicationCache)
        withClue(s"$text: ")(result.parsed.isLeft shouldBe true)
      }
  }
