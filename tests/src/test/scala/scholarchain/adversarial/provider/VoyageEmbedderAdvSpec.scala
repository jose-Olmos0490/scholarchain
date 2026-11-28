package scholarchain.adversarial.provider

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.rag.embed.*
import scholarchain.rag.provider.ProviderFailureKind
import ProviderAdvKit.*

/** The Voyage embeddings adapter attacked with a loopback stub. */
class VoyageEmbedderAdvSpec extends AnyFunSuite with Matchers:

  private val ok = "[1,0,0,0]"

  private def failureOf(e: VoyageEmbedder, texts: Seq[String]): RuntimeException =
    try
      val out = e.embed(texts)
      fail(s"accepted: ${out.map(_.toVector)}")
    catch
      case x: EmbeddingProviderException => x
      case x: EmbeddingDimensionException => x

  private def clean(t: Throwable): Unit =
    val text = everything(t)
    text should not include Canary
    text should not include "SECRET INPUT TEXT"
    t.getCause shouldBe null
    t.getSuppressed shouldBe empty

  test("429 then success is retried for that batch only; earlier batches are not resent") {
    stub((i, h) => if i == 1 || i == 2 then Reply(if i == 1 then 429 else 529) else vectorsFor(h)) {
      s =>
        val pauses = new Pauses
        voyage(s.url, pauses = pauses).embed(Seq("a", "b", "c", "d", "e")).size shouldBe 5
        s.requests.map(r => ujson.read(r.body)("input").arr.map(_.str).mkString) shouldBe
          Vector("ab", "cd", "cd", "cd", "e")
        pauses.all.toVector shouldBe Vector(7L, 14L)
    }
  }

  test("statuses are classified and bounded; error bodies with key-shaped text are redacted") {
    val body = s"""{"detail":"bad key $VoyageKey for input SECRET INPUT TEXT"}"""
    val table = Vector(
      429 -> ProviderFailureKind.Overloaded,
      529 -> ProviderFailureKind.Overloaded,
      500 -> ProviderFailureKind.Transient,
      504 -> ProviderFailureKind.Transient,
      400 -> ProviderFailureKind.Permanent,
      401 -> ProviderFailureKind.Permanent,
      408 -> ProviderFailureKind.Permanent,
      413 -> ProviderFailureKind.Permanent,
      422 -> ProviderFailureKind.Permanent
    )
    for (status, kind) <- table do
      script(Reply(status, body, Map("x-echo" -> VoyageKey))) { s =>
        val failure = failureOf(voyage(s.url), Seq("SECRET INPUT TEXT"))
        withClue(s"HTTP $status: ") {
          failure.asInstanceOf[EmbeddingProviderException].kind shouldBe kind
          failure.getMessage should include(s"HTTP $status")
          s.requests.size shouldBe (if kind == ProviderFailureKind.Permanent then 1 else 3)
          clean(failure)
        }
      }
  }

  test("a stalled answer is TimedOut, retried to the bound and returns in bounded time") {
    stub((_, _) => Reply(200, "{}", stall = true)) { s =>
      val started = System.nanoTime()
      val failure = failureOf(voyage(s.url, retries = 2, timeoutMs = 250), Seq("a"))
      failure.asInstanceOf[EmbeddingProviderException].kind shouldBe ProviderFailureKind.TimedOut
      s.requests.size shouldBe 3
      (System.nanoTime() - started) / 1000000L should be < 10000L
    }
  }

  test("a cut-off body is Transient, never a partial result") {
    rawServer("HTTP/1.1 200 OK\r\nContent-Length: 900\r\n\r\n{\"data\":[") { (url, connections) =>
      val failure = failureOf(voyage(url, retries = 1, timeoutMs = 3000), Seq("a"))
      failure.asInstanceOf[EmbeddingProviderException].kind shouldBe ProviderFailureKind.Transient
      connections() should be >= 2
      connections() should be <= 4
    }
  }

  test("malformed 200 bodies are refused without echoing them") {
    val k = VoyageKey
    val bodies = Seq(
      "",
      "null",
      "[]",
      s"<html>$k</html>",
      s"""{"data":"$k"}""",
      """{"data":null}""",
      """{"data":[null]}""",
      """{"data":[{"embedding":[1,0,0,0]}]}""",
      s"""{"data":[{"index":"$k","embedding":$ok}]}""",
      """{"data":[{"index":0,"embedding":null}]}""",
      s"""{"data":[{"index":0,"embedding":"$k"}]}""",
      """{"data":[{"index":0,"embedding":[1,0,0,null]}]}""",
      """{"data":[{"index":0,"embedding":[[1],0,0,0]}]}""",
      s"""{"data":[{"index":0,"embedding":$ok}"""
    )
    for body <- bodies do
      script(Reply(200, body)) { s =>
        val failure = failureOf(voyage(s.url), Seq("SECRET INPUT TEXT"))
        withClue(s"body='${body.take(60)}': ") {
          clean(failure)
          s.requests.size shouldBe 1
        }
      }
  }

  test("a deeply nested 200 body is a typed failure, not a stack overflow") {
    val deep = "[" * 200000 + "]" * 200000
    for body <- Seq(deep, s"""{"data":$deep}""") do
      script(Reply(200, body)) { s =>
        try clean(failureOf(voyage(s.url), Seq("a")))
        // left uncaught it would abort the whole suite
        catch case _: StackOverflowError => fail("StackOverflowError escaped the embedder")
      }
  }

  test("NaN, Infinity and float-overflow values are refused, normalized or not") {
    val values = Seq(
      "NaN",
      "Infinity",
      "-Infinity",
      "\"NaN\"",
      "\"Infinity\"",
      "\"-Infinity\"",
      "1e999",
      "-1e999",
      "1e39",
      "-3.5e38"
    )
    for v <- values; normalize <- Seq(true, false) do
      script(Reply(200, vectorsBody("0" -> s"[1,0,0,$v]"))) { s =>
        withClue(s"value=$v normalize=$normalize: ") {
          clean(failureOf(voyage(s.url, normalize = normalize), Seq("a")))
        }
      }
  }

  test("large finite values normalize to a finite unit vector") {
    script(Reply(200, vectorsBody("0" -> "[3e38,3e38,3e38,3e38]"))) { s =>
      voyage(s.url, normalize = true).embed(Seq("a")).head.toVector shouldBe
        Vector.fill(4)(0.5f)
    }
  }

  test("a wrong dimension anywhere fails the whole call, and no later batch is sent") {
    for wrong <- Seq("[]", "[1,0,0]", "[1,0,0,0,0]") do
      // the first batch is fine, the second vector of the second batch is wrong
      stub((i, h) =>
        if i == 1 then Reply(200, vectorsBody("0" -> ok, "1" -> wrong)) else vectorsFor(h)
      ) { s =>
        val failure = failureOf(voyage(s.url), Seq("a", "b", "c", "d", "e"))
        failure shouldBe a[EmbeddingDimensionException]
        s.requests.size shouldBe 2
        clean(failure)
      }
  }

  test("duplicate, missing, negative, extra and oversize indexes are refused") {
    val sets = Seq(
      Seq("0", "0"),
      Seq("1", "1"),
      Seq("0"),
      Seq("1"),
      Seq("0", "2"),
      Seq("1", "2"),
      Seq("-1", "0"),
      Seq("0", "1", "2"),
      Seq("0", "1", "1"),
      Seq("0", "4294967297"),
      Seq("0", "2147483648"),
      Seq("0", "1e999")
    )
    for indexes <- sets do
      script(Reply(200, vectorsBody(indexes.map(_ -> ok)*))) { s =>
        withClue(s"indexes=$indexes: ") {
          failureOf(voyage(s.url), Seq("a", "b")) shouldBe a[EmbeddingDimensionException]
        }
      }
  }

  test("a fractional index is refused, not truncated to a position") {
    for indexes <- Seq(Seq("0.7", "1.2"), Seq("-0.5", "1"), Seq("0", "1.999")) do
      script(Reply(200, vectorsBody(indexes.map(_ -> ok)*))) { s =>
        withClue(s"indexes=$indexes: ") {
          failureOf(voyage(s.url), Seq("a", "b"))
        }
      }
  }

  test("an empty batch sends nothing and does not even ask for the key") {
    val asked = new AtomicInteger
    stub((_, h) => vectorsFor(h)) { s =>
      val e = voyage(
        s.url,
        key = () =>
          asked.incrementAndGet()
          ""
      )
      e.embed(Seq.empty) shouldBe empty
      e.embed(Vector.empty) shouldBe empty
      s.requests shouldBe empty
      asked.get shouldBe 0
      e.tokensUsed shouldBe 0L
    }
  }

  test("an empty or short data array for a non-empty batch is refused") {
    for body <- Seq(vectorsBody(), vectorsBody("0" -> ok)) do
      script(Reply(200, body)) { s =>
        failureOf(voyage(s.url), Seq("a", "b")) shouldBe a[EmbeddingDimensionException]
      }
  }

  test("missing or injectable keys are Permanent, send nothing and are not echoed") {
    val k = VoyageKey
    val suppliers: Seq[(String, () => String)] = Seq(
      "null" -> (() => null),
      "blank" -> (() => "  \n"),
      "CRLF injection" -> (() => s"$k\r\nx-injected: 1"),
      "NUL" -> (() => k + "\u0000x"),
      "space inside" -> (() => s"$k x"),
      "non-ASCII" -> (() => k + "\u00e9"),
      "supplier throws" -> (() => throw new IllegalStateException(s"value $k"))
    )
    stub((_, h) => vectorsFor(h)) { s =>
      for (label, supplier) <- suppliers do
        withClue(s"$label: ") {
          val failure = failureOf(voyage(s.url, key = supplier), Seq("a"))
          failure.asInstanceOf[EmbeddingProviderException].kind shouldBe
            ProviderFailureKind.Permanent
          clean(failure)
        }
      s.requests shouldBe empty
    }
  }

  test("a key that disappears between batches fails the call and sends nothing more") {
    val asked = new AtomicInteger
    stub((_, h) => vectorsFor(h)) { s =>
      val e = voyage(s.url, key = () => if asked.incrementAndGet() == 1 then VoyageKey else "")
      failureOf(e, Seq("a", "b", "c"))
      s.requests.size shouldBe 1
    }
  }

  test("no redirect is followed and the key stays on the configured host") {
    stub((_, h) => vectorsFor(h)) { target =>
      for status <- Seq(301, 302, 303, 307, 308) do
        script(Reply(status, headers = Map("location" -> s"${target.url}/v1/embeddings"))) { s =>
          val failure = failureOf(voyage(s.url), Seq("a"))
          failure.asInstanceOf[EmbeddingProviderException].kind shouldBe
            ProviderFailureKind.Permanent
          s.requests.size shouldBe 1
        }
      target.requests shouldBe empty
    }
  }

  test("an interrupt during a request stops retries and later batches") {
    stub((_, _) => Reply(200, "{}", stall = true)) { s =>
      val done = new AtomicBoolean(false)
      val (failure, interrupted) = onThread {
        val worker = Thread.currentThread()
        val watchdog = new Thread(() =>
          while s.requests.isEmpty && !done.get do Thread.sleep(5)
          worker.interrupt()
        )
        watchdog.start()
        try failureOf(voyage(s.url, retries = 5, timeoutMs = 20000), Seq("a", "b", "c"))
        finally done.set(true)
      }
      Thread.sleep(400)
      interrupted shouldBe true
      failure shouldBe an[EmbeddingProviderException]
      s.requests.size shouldBe 1
    }
  }

  test("the key is only in the authorization header") {
    stub((_, h) => vectorsFor(h)) { s =>
      voyage(s.url).embed(Seq("a"))
      val r = s.requests.head
      r.headers("authorization") shouldBe s"Bearer $VoyageKey"
      r.path shouldBe "/v1/embeddings"
      r.body should not include Canary
      (r.headers - "authorization").values.mkString should not include Canary
    }
  }

  test("a vector that is only a key echo in unused fields does not leak into the result") {
    val body =
      s"""{"model":"$VoyageKey","data":[{"index":0,"embedding":$ok,"object":"$VoyageKey"}],""" +
        s""""usage":{"total_tokens":"$VoyageKey"}}"""
    script(Reply(200, body)) { s =>
      val e = voyage(s.url)
      e.embed(Seq("a")).head.toVector shouldBe Vector(1f, 0f, 0f, 0f)
      e.spec.toString should not include Canary
      e.tokensUsed shouldBe 0L
    }
  }
