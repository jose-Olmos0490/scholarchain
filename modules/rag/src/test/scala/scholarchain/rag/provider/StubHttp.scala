package scholarchain.rag.provider

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, Executors, TimeUnit}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scholarchain.rag.llm.Backoff

/** One request the stub received. Header names are lower-cased. */
final case class Seen(method: String, path: String, headers: Map[String, String], body: String):
  def json: ujson.Value = ujson.read(body)

/** One scripted answer. `stallBeforeHeaders` and `stallInBody` hold the answer back until the
  * server closes, to exercise the client deadline.
  */
final case class Answer(
    status: Int,
    body: String,
    headers: Map[String, String] = Map.empty,
    stallBeforeHeaders: Boolean = false,
    stallInBody: Boolean = false
)

/** A loopback HTTP server that records requests and answers from a script. No network. */
final class StubHttp private (server: HttpServer, answer: (Int, Seen) => Answer):
  private val recorded = new ConcurrentLinkedQueue[Seen]
  private val release = new CountDownLatch(1)
  private val pool = Executors.newCachedThreadPool()

  def requests: Vector[Seen] = recorded.asScala.toVector
  def baseUrl: String = s"http://127.0.0.1:${server.getAddress.getPort}"

  private def handle(x: HttpExchange): Unit =
    val seen = Seen(
      x.getRequestMethod,
      x.getRequestURI.getPath,
      x.getRequestHeaders.asScala.map((k, v) => k.toLowerCase -> v.get(0)).toMap,
      new String(x.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
    )
    val index = synchronized { recorded.add(seen); recorded.size - 1 }
    val a = answer(index, seen)
    try
      if a.stallBeforeHeaders then release.await(30, TimeUnit.SECONDS)
      val bytes = a.body.getBytes(StandardCharsets.UTF_8)
      a.headers.foreach((k, v) => x.getResponseHeaders.add(k, v))
      x.sendResponseHeaders(a.status, if bytes.isEmpty then -1L else bytes.length.toLong)
      if a.stallInBody then
        x.getResponseBody.write(bytes, 0, 1)
        x.getResponseBody.flush()
        release.await(30, TimeUnit.SECONDS)
      else if bytes.nonEmpty then x.getResponseBody.write(bytes)
    catch case _: java.io.IOException => ()
    finally x.close()

  private def start(): Unit =
    server.createContext("/", x => handle(x))
    server.setExecutor(pool)
    server.start()

  def stop(): Unit =
    release.countDown()
    server.stop(0)
    pool.shutdownNow()
    ()

object StubHttp:
  def using[A](answer: (Int, Seen) => Answer)(body: StubHttp => A): A =
    val server =
      HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    val stub = new StubHttp(server, answer)
    stub.start()
    try body(stub)
    finally stub.stop()

  /** Answers in order; the last one repeats. */
  def script[A](answers: Answer*)(body: StubHttp => A): A =
    using((i, _) => answers(math.min(i, answers.size - 1)))(body)

/** Records pauses instead of sleeping. */
final class RecordingBackoff extends Backoff:
  val pauses = mutable.ArrayBuffer.empty[Long]
  def pause(millis: Long): Unit = pauses += millis
