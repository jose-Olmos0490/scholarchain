package scholarchain.adversarial.provider

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scholarchain.core.config.{AnthropicConfig, VoyageConfig}
import scholarchain.er.adjudication.*
import scholarchain.rag.embed.*
import scholarchain.rag.llm.{Prompt as _, *}
import scholarchain.rag.provider.RetryDelays

/** Support code for the provider-adapter adversarial suites (Phase 16b, delegation 7). Written
  * independently of the module test helpers. Loopback only; every key is a synthetic canary and no
  * environment variable is read.
  */
object ProviderAdvKit:

  // Assembled at run time so that no key-shaped literal sits in the source tree.
  val Canary = "CANARY7f3a9c"
  val AnthropicKey: String = "sk-ant-" + "api03-" + Canary + "-" + "A" * 40
  val VoyageKey: String = "pa-" + Canary + "B" * 30

  final case class Hit(method: String, path: String, headers: Map[String, String], body: String)

  /** `stall` holds the answer back until the server closes. */
  final case class Reply(
      status: Int,
      body: String = "",
      headers: Map[String, String] = Map.empty,
      stall: Boolean = false
  )

  final class Stub(answer: (Int, Hit) => Reply):
    private val hits = new ConcurrentLinkedQueue[Hit]
    private val counter = new AtomicInteger
    private val release = new CountDownLatch(1)
    private val pool = Executors.newCachedThreadPool()
    private val server =
      HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    server.setExecutor(pool)
    server.createContext("/", (x: HttpExchange) => handle(x))
    server.start()

    val url: String = s"http://127.0.0.1:${server.getAddress.getPort}"
    def requests: Vector[Hit] = hits.asScala.toVector

    private def handle(x: HttpExchange): Unit =
      val hit = Hit(
        x.getRequestMethod,
        x.getRequestURI.toString,
        x.getRequestHeaders.asScala.map((k, v) => k.toLowerCase -> v.get(0)).toMap,
        new String(x.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      )
      hits.add(hit)
      val a = answer(counter.getAndIncrement(), hit)
      try
        if a.stall then release.await(30, TimeUnit.SECONDS)
        a.headers.foreach((k, v) => x.getResponseHeaders.add(k, v))
        val bytes = a.body.getBytes(StandardCharsets.UTF_8)
        x.sendResponseHeaders(a.status, if bytes.isEmpty then -1L else bytes.length.toLong)
        if bytes.nonEmpty then x.getResponseBody.write(bytes)
      catch case _: java.io.IOException => ()
      finally x.close()

    def stop(): Unit =
      release.countDown()
      server.stop(0)
      pool.shutdownNow()
      ()

  def stub[A](answer: (Int, Hit) => Reply)(body: Stub => A): A =
    val s = new Stub(answer)
    try body(s)
    finally s.stop()

  /** Answers in order; the last one repeats. */
  def script[A](replies: Reply*)(body: Stub => A): A =
    stub((i, _) => replies(math.min(i, replies.size - 1)))(body)

  /** A raw socket server that reads one request, writes `raw` and closes: for answers an HTTP
    * server library would not produce (cut-off body, not HTTP at all).
    */
  def rawServer[A](raw: String)(body: (String, () => Int) => A): A =
    val server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val connections = new AtomicInteger
    def serve(s: Socket): Unit =
      try
        s.setSoTimeout(2000)
        connections.incrementAndGet()
        val buf = new Array[Byte](65536)
        s.getInputStream.read(buf)
        s.getOutputStream.write(raw.getBytes(StandardCharsets.UTF_8))
        s.getOutputStream.flush()
        // drain what is left of the request, so closing does not reset before the bytes arrive
        s.shutdownOutput()
        while s.getInputStream.read(buf) >= 0 do ()
      catch case _: java.io.IOException => ()
      finally s.close()
    val acceptor = new Thread(() =>
      try
        while true do
          val s = server.accept()
          new Thread(() => serve(s)).start()
      catch case _: java.io.IOException => ()
    )
    acceptor.setDaemon(true)
    acceptor.start()
    try body(s"http://127.0.0.1:${server.getLocalPort}", () => connections.get)
    finally server.close()

  /** Records the delays instead of sleeping. */
  final class Pauses extends Backoff:
    val all = mutable.ArrayBuffer.empty[Long]
    def pause(millis: Long): Unit = all += millis

  /** Runs on a fresh thread so an interrupt never reaches the test runner. Returns the result and
    * whether the thread was still interrupted at the end.
    */
  def onThread[A](body: => A): (A, Boolean) =
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
    t.join(30000)
    out
      .getOrElse(throw new AssertionError("the call did not return"))
      .fold(e => throw e, identity)

  /** Every text reachable from a throwable: message, toString, causes, suppressed, stack. */
  def everything(t: Throwable): String =
    val w = new java.io.StringWriter
    t.printStackTrace(new java.io.PrintWriter(w))
    s"${t.getMessage}\n$t\n$w"

  // --- Anthropic ------------------------------------------------------------------------------

  val Model = "adv-model"
  val Delays: RetryDelays = RetryDelays(7, 50)

  def anthropicConfig(url: String, retries: Int = 2, timeoutMs: Int = 10000): AnthropicConfig =
    AnthropicConfig(Model, url, "2023-06-01", 2000, timeoutMs, retries)

  def ragClient(
      url: String,
      retries: Int = 2,
      timeoutMs: Int = 10000,
      key: () => String = () => AnthropicKey,
      pauses: Pauses = new Pauses
  ): AnthropicLlmClient =
    new AnthropicLlmClient(anthropicConfig(url, retries, timeoutMs), key, Delays, pauses)

  def erAdapter(
      url: String,
      retries: Int = 2,
      timeoutMs: Int = 10000,
      key: () => String = () => AnthropicKey,
      pauses: Pauses = new Pauses
  ): AnthropicAdjudicator =
    new AnthropicAdjudicator(
      anthropicConfig(url, retries, timeoutMs),
      key,
      64,
      Delays.baseDelayMs,
      Delays.maxDelayMs,
      pauses.pause
    )

  val llmRequest: LlmRequest = LlmRequest("p-v1", "SYSTEM", """{"question":"q"}""", 64)

  lazy val prompt: Prompt = Prompt.adjudication.fold(e => throw new AssertionError(e), identity)

  def adjudicationRequest: AdjudicationRequest =
    AdjudicationRequest(
      "cand-1",
      "RESEARCHER",
      ujson.Obj("ref" -> "left-1", "name" -> "Wei Zhang"),
      ujson.Obj("ref" -> "right-1", "name" -> "W. Zhang"),
      Map("nameSimilarity" -> 0.9),
      0.7
    )

  def okMessage(text: String, model: String = Model, stop: String = "end_turn"): Reply =
    Reply(
      200,
      ujson
        .Obj(
          "model" -> model,
          "stop_reason" -> stop,
          "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> text)),
          "usage" -> ujson.Obj("input_tokens" -> 3, "output_tokens" -> 2)
        )
        .render()
    )

  /** One adapter's view of a call: failure kind name (or "ok"), and every text it returned. */
  final case class Seen1(kind: String, text: String)

  def viaRag(client: AnthropicLlmClient): Seen1 =
    client.complete(llmRequest) match
      case Right(r) => Seen1("ok", r.toString)
      case Left(f) =>
        val kind = f match
          case _: LlmFailure.Overloaded => "Overloaded"
          case _: LlmFailure.TimedOut => "TimedOut"
          case _: LlmFailure.Transient => "Transient"
          case _: LlmFailure.Permanent => "Permanent"
        Seen1(kind, f.toString)

  def viaEr(adapter: AnthropicAdjudicator): Seen1 =
    adapter.call(prompt, adjudicationRequest) match
      case Right(r) => Seen1("ok", r.toString)
      case Left(f) => Seen1(f.kind.toString, s"$f ${f.message}")

  /** Both Anthropic adapters against one URL, by name. */
  def bothAdapters(
      url: String,
      retries: Int = 2,
      timeoutMs: Int = 10000,
      key: () => String = () => AnthropicKey
  ): Vector[(String, () => Seen1)] =
    Vector(
      "rag" -> (() => viaRag(ragClient(url, retries, timeoutMs, key))),
      "er" -> (() => viaEr(erAdapter(url, retries, timeoutMs, key)))
    )

  // --- Voyage ---------------------------------------------------------------------------------

  val Dim = 4

  def voyage(
      url: String,
      batch: Int = 2,
      retries: Int = 2,
      timeoutMs: Int = 10000,
      key: () => String = () => VoyageKey,
      pauses: Pauses = new Pauses,
      normalize: Boolean = false
  ): VoyageEmbedder =
    new VoyageEmbedder(
      VoyageConfig("adv-embed", url, Dim, batch, 2000, timeoutMs, retries),
      EmbeddingModelSpec("adv-embed", "1", Dim, normalize),
      VoyageInputType.Document,
      key,
      Delays,
      pauses
    )

  def vectorsBody(items: (String, String)*): String =
    items
      .map((index, embedding) => s"""{"index":$index,"embedding":$embedding}""")
      .mkString("""{"data":[""", ",", """],"usage":{"total_tokens":3}}""")

  /** A valid answer with one vector per input of the request. */
  def vectorsFor(hit: Hit): Reply =
    val n = ujson.read(hit.body)("input").arr.size
    Reply(200, vectorsBody((0 until n).map(i => i.toString -> "[1,0,0,0]")*))
