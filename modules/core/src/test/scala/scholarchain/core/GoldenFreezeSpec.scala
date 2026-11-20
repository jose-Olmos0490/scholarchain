package scholarchain.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.TestSupport.*
import scholarchain.core.schema.SchemaVersions

/** Freeze of the v1 wire format (human decision 1, 2026-11-20).
  *
  * The v1 wire format freezes when Phase 3 is accepted. From then on any wire change needs a new
  * schema version. Two locks enforce this:
  *
  *   1. Byte lock: the current encoder must reproduce every v1 golden file byte for byte (2-space
  *      indented uPickle output plus a final newline). A renamed, added, removed or reordered
  *      field, a changed enum spelling or a changed number format fails here, not only a
  *      structural difference.
  *   2. File lock: `golden/v1/SHA256SUMS` pins the bytes of every v1 golden. Editing a golden to
  *      follow a changed encoder fails here too, so the only passing route for a wire change is a
  *      new `-v2` schema version with new goldens under `golden/v2/`.
  */
class GoldenFreezeSpec extends AnyFunSuite with Matchers:

  private def sha256(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  /** `<sha256>  <file>` lines of golden/v1/SHA256SUMS. */
  private lazy val frozenSums: Map[String, String] =
    val in = getClass.getResourceAsStream("/golden/v1/SHA256SUMS")
    require(in != null, "missing golden/v1/SHA256SUMS")
    val text =
      try new String(in.readAllBytes(), StandardCharsets.UTF_8)
      finally in.close()
    text.linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { line =>
        line.split("\\s+", 2) match
          case Array(hash, file) => file -> hash
          case _ => fail(s"malformed SHA256SUMS line '$line'")
      }
      .toMap

  /** The exact bytes the current encoder produces for a golden case. */
  private def encodedBytes[A](c: GoldenCase[A]): String =
    ujson.write(encodeJson(c), indent = 2) + "\n"

  /** The byte lock used by the per-record tests below. */
  private def matchesFrozenBytes(encoded: String, file: String): Boolean =
    encoded == goldenText(file)

  test("SHA256SUMS pins exactly the v1 golden files") {
    frozenSums.keySet shouldBe GoldenCases.all.map(_.file).toSet
  }

  GoldenCases.all.foreach { c =>
    test(s"${c.recordType}: the frozen v1 golden bytes are unchanged (${c.file})") {
      sha256(goldenText(c.file)) shouldBe frozenSums(c.file)
    }

    test(s"${c.recordType}: the encoder reproduces the v1 golden byte for byte (${c.file})") {
      encodedBytes(c) shouldBe goldenText(c.file)
    }
  }

  test("every golden still declares a v1 schema version (a v2 needs new goldens)") {
    GoldenCases.all.foreach { c =>
      val declared = goldenJson(c.file).obj("schemaVersion").str
      withClue(c.file)(declared should endWith("-v1"))
      SchemaVersions.supportedFor(c.recordType) should contain(declared)
    }
  }

  // Proof that the byte lock catches each kind of encoding change.
  private val sample = GoldenCases.all.find(_.file == "canonical-researcher.json").get

  private def mutated(f: ujson.Obj => ujson.Value): String =
    val copy = ujson.read(encodedBytes(sample)).asInstanceOf[ujson.Obj]
    ujson.write(f(copy), indent = 2) + "\n"

  test("freeze proof: the unmodified encoding passes the byte lock") {
    matchesFrozenBytes(encodedBytes(sample), sample.file) shouldBe true
  }

  test("freeze proof: a renamed field fails the byte lock") {
    val renamed = mutated { o =>
      val v = o.value.remove("dblpPid").get
      o("dblpPersonId") = v
      o
    }
    matchesFrozenBytes(renamed, sample.file) shouldBe false
  }

  test("freeze proof: an added field fails the byte lock") {
    matchesFrozenBytes(mutated { o => o("newField") = ujson.Null; o }, sample.file) shouldBe false
  }

  test("freeze proof: a removed field fails the byte lock") {
    matchesFrozenBytes(mutated { o => o.value.remove("dblpPid"); o }, sample.file) shouldBe false
  }

  test("freeze proof: reordered fields fail the byte lock even though the JSON is equal") {
    val reordered = mutated(o => ujson.Obj.from(o.value.toSeq.reverse))
    ujson.read(reordered) shouldBe ujson.read(goldenText(sample.file))
    matchesFrozenBytes(reordered, sample.file) shouldBe false
  }

  test("freeze proof: a changed number format fails the byte lock") {
    val asText = encodedBytes(sample).replace("0.97", "0.970")
    asText should not be encodedBytes(sample)
    matchesFrozenBytes(asText, sample.file) shouldBe false
  }

  test("freeze proof: an edited golden fails the file lock") {
    val edited = goldenText(sample.file).replace("Henry Samueli", "Henry Samueli ")
    sha256(edited) should not be frozenSums(sample.file)
  }
