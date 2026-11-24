package scholarchain.adversarial.rag

import java.nio.file.{Files, Path}
import scala.collection.mutable
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.adversarial.Adv
import scholarchain.rag.ingest.IngestMain

/** The rag ingestion entrypoint with missing, malformed and hostile arguments, input and
  * configuration. Every case must end with a non-zero code and a short message that repeats no
  * environment value and no input content. Nothing here can reach AWS: each case is refused
  * before any client is built (the environment is never complete).
  */
class IngestMainAdvSpec extends AnyFunSuite with Matchers:

  private val Marker = "ZZ-INGEST-MARKER-2"

  private def run(args: Seq[String], env: Map[String, String] = Map.empty) =
    val out = mutable.ArrayBuffer.empty[String]
    val err = mutable.ArrayBuffer.empty[String]
    val code =
      try IngestMain.execute(args, env, out += _, err += _)
      catch case t: Throwable => fail(s"execute threw ${t.getClass.getSimpleName}: ${t.getMessage}")
    (code, out.mkString("\n"), err.mkString("\n"))

  private def scratch(files: (String, Array[Byte])*): Path =
    val d = Adv.scratch("ingest-main").toPath
    files.foreach((n, b) => Files.write(d.resolve(n), b))
    d

  test("no arguments: exit 2 and a message that names the missing option") {
    val (code, out, err) = run(Seq.empty)
    code shouldBe 2
    out shouldBe empty
    err should include("--input-dir")
  }

  test("hostile and malformed options are refused, never interpreted") {
    Seq(
      Seq("--input-dir"),
      Seq("--input-dir", "/nonexistent-dir-" + Marker),
      Seq("--bogus-flag", "x"),
      Seq("--input-dir", "x", "--limit", "abc"),
      Seq("--input-dir", "x", "--limit", "-5"),
      Seq("--input-dir", "x", "--limit", "99999999999999999999"),
      Seq("--input-dir", "x", "--run-id", s"../../$Marker"),
      Seq("--input-dir", "x", "--dry-run", "--dry-run"),
      Seq("; rm -rf /", "$(reboot)")
    ).foreach { a =>
      val (code, _, err) = run(a)
      withClue(a.mkString(" ")) {
        code should not be 0
        err should not be empty
        err should not include "Exception"
      }
    }
  }

  test("an input file that is not valid UTF-8 is a typed refusal, not an uncaught exception") {
    val d = scratch("papers.jsonl" -> Array.fill[Byte](2000)(0xff.toByte))
    val (code, _, err) = run(Seq("--input-dir", d.toString, "--dry-run"))
    code should not be 0
    err should not be empty
  }

  test("an input directory with broken JSON is refused without echoing its content") {
    val d = scratch(
      "researchers.jsonl" -> (s"""{"broken": $Marker""" + "\n").getBytes("UTF-8"),
      "empty.jsonl" -> Array.emptyByteArray
    )
    val (code, _, err) = run(Seq("--input-dir", d.toString, "--dry-run"))
    code should not be 0
    err should not include Marker
    err.length should be < 2000
  }

  test("configuration omissions are reported by variable name, never by value") {
    val d = scratch()
    val env = Map(
      "SCHOLARCHAIN_ENV" -> "dev",
      "AWS_SECRET_ACCESS_KEY" -> Marker,
      "AWS_SESSION_TOKEN" -> Marker,
      "NEPTUNE_ENDPOINT" -> Marker,
      "RAW_BUCKET" -> Marker,
      "NEPTUNE_PORT" -> "not-a-port"
    )
    val (code, _, err) = run(Seq("--input-dir", d.toString, "--dry-run"), env)
    code should not be 0
    err should not include Marker
  }

  test("a live run without a run ID, snapshot or limit is refused before any AWS client exists") {
    val d = scratch()
    Seq(
      Seq("--input-dir", d.toString),
      Seq("--input-dir", d.toString, "--run-id", "run:" + "a" * 64)
    ).foreach { a =>
      val (code, _, err) = run(a)
      code should not be 0
      err should not be empty
    }
  }
