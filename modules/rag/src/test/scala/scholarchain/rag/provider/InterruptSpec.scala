package scholarchain.rag.provider

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scholarchain.core.config.{AnthropicConfig, RagLlmConfig, VoyageConfig}
import scholarchain.rag.RagSuite
import scholarchain.rag.embed.*
import scholarchain.rag.llm.*

/** Once the thread is interrupted (a stage deadline), no further request is made, because it could
  * still reach the provider and be billed. The interrupt status is kept.
  */
class InterruptSpec extends RagSuite:
  private val delays = RetryDelays(0, 0)
  private val request = LlmRequest("p-v1", "SYSTEM", "{}", 64)
  private val schema = JsonSchema.parse("t-v1", """{"type":"object"}""").toOption.get

  /** Runs the body on its own thread so an interrupt never leaks into the test runner. Returns
    * the result and whether the thread was still interrupted at the end.
    */
  private def onThread[A](body: => A): (A, Boolean) =
    var out: Option[Either[Throwable, (A, Boolean)]] = None
    val t = new Thread(() =>
      out = Some(
        try
          val a = body
          Right((a, Thread.currentThread().isInterrupted))
        catch case e: Throwable => Left(e)
      )
    )
    t.start()
    t.join(20000)
    out.getOrElse(fail("the call did not return")).fold(e => throw e, identity)

  /** Counts the calls of a scripted client; the first call interrupts the thread. */
  final private class Interrupting(answer: Either[LlmFailure, LlmResponse]) extends LlmClient:
    val calls = new AtomicInteger
    def complete(r: LlmRequest): Either[LlmFailure, LlmResponse] =
      calls.incrementAndGet()
      Thread.currentThread().interrupt()
      answer

  test("a structured call makes no further attempt after an interrupt") {
    for answer <- Seq(
        Left(LlmFailure.Transient("interrupted")),
        Right(LlmResponse("not json", "m", 5, 3))
      )
    do
      val client = new Interrupting(answer)
      val (result, interrupted) =
        onThread(new StructuredLlmCall(client, RagLlmConfig(3, 64)).run("p", "s", "{}", schema))
      client.calls.get shouldBe 1
      interrupted shouldBe true
      result.outcome.left.toOption.get.code shouldBe "TIMEOUT"
      result.usage.calls shouldBe 1
  }

  test("a structured call on an already interrupted thread calls nothing") {
    val client = new Interrupting(Left(LlmFailure.Transient("unused")))
    val (result, interrupted) = onThread {
      Thread.currentThread().interrupt()
      new StructuredLlmCall(client, RagLlmConfig(3, 64)).run("p", "s", "{}", schema)
    }
    client.calls.get shouldBe 0
    interrupted shouldBe true
    result.outcome.isLeft shouldBe true
  }

  test("an interrupt thrown by a client stops the loop and the status is restored") {
    val calls = new AtomicInteger
    val client = new LlmClient:
      def complete(r: LlmRequest): Either[LlmFailure, LlmResponse] =
        calls.incrementAndGet()
        throw new InterruptedException
    val (result, interrupted) =
      onThread(new StructuredLlmCall(client, RagLlmConfig(3, 64)).run("p", "s", "{}", schema))
    calls.get shouldBe 1
    interrupted shouldBe true
    result.outcome.left.toOption.get.code shouldBe "TIMEOUT"
  }

  /** A stalled request is interrupted while it waits; the whole stack then stays silent. */
  test("an interrupt during a request sends no further request through the whole stack") {
    StubHttp.script(Answer(200, "{}", stallBeforeHeaders = true)) { stub =>
      val client = new AnthropicLlmClient(
        AnthropicConfig("m", stub.baseUrl, "2023-06-01", 2000, 15000, 3),
        () => "test-key",
        delays
      )
      val done = new AtomicBoolean(false)
      val (result, interrupted) = onThread {
        val worker = Thread.currentThread()
        val watchdog = new Thread(() =>
          while stub.requests.isEmpty && !done.get do Thread.sleep(5)
          worker.interrupt()
        )
        watchdog.start()
        try new StructuredLlmCall(client, RagLlmConfig(3, 64)).run("p", "s", "{}", schema)
        finally done.set(true)
      }
      interrupted shouldBe true
      result.usage.calls shouldBe 1
      // give a wrongly sent request time to arrive
      Thread.sleep(500)
      stub.requests.size shouldBe 1
    }
  }

  test("an adapter on an interrupted thread sends nothing") {
    StubHttp.script(Answer(200, "{}")) { stub =>
      val client = new AnthropicLlmClient(
        AnthropicConfig("m", stub.baseUrl, "2023-06-01", 2000, 5000, 3),
        () => "test-key",
        delays
      )
      val (out, interrupted) = onThread {
        Thread.currentThread().interrupt()
        client.complete(request)
      }
      out.left.toOption.get shouldBe a[LlmFailure.Transient]
      // an immediate cancel can hide a started request from the stub, so check the reason too
      out.left.toOption.get.detail should include("after 0 attempt(s); no further request")
      interrupted shouldBe true
      Thread.sleep(500)
      stub.requests shouldBe empty
    }
  }

  test("Voyage sends no later batch after an interrupt between batches") {
    val vector = """{"data":[{"index":0,"embedding":[1,0,0,0]}]}"""
    StubHttp.script(Answer(200, vector)) { stub =>
      val keys = new AtomicInteger
      // the key is asked once per batch: interrupt while the second batch is being prepared
      val embedder = new VoyageEmbedder(
        VoyageConfig("v", stub.baseUrl, 4, 1, 2000, 5000, 3),
        EmbeddingModelSpec("v", "1", 4, normalize = false),
        VoyageInputType.Document,
        () =>
          if keys.incrementAndGet() == 2 then Thread.currentThread().interrupt()
          "test-key"
        ,
        delays
      )
      val (error, interrupted) = onThread {
        the[EmbeddingProviderException] thrownBy embedder.embed(Seq("a", "b", "c"))
      }
      error.kind shouldBe ProviderFailureKind.Transient
      error.getMessage should include("after 0 attempt(s); no further request")
      interrupted shouldBe true
      keys.get shouldBe 2
      Thread.sleep(500)
      stub.requests.size shouldBe 1
    }
  }
