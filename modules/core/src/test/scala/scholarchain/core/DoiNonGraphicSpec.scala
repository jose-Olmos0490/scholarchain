package scholarchain.core

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.ids.*
import scholarchain.core.model.*

/** Feature tests: a normalized DOI never contains a non-graphic character, and every accepted
  * value is a fixed point of `normalizeDoi` (phase 04 repair 06).
  */
class DoiNonGraphicSpec extends AnyFunSuite with Matchers:

  private def show(s: String): String =
    s.flatMap(c =>
      if c < 0x20 || (c >= 0x7f && c <= 0xa0) then f"\\u${c.toInt}%04X" else c.toString
    )

  private val controlEscapes =
    Seq("%00", "%01", "%08", "%09", "%0A", "%0D", "%1B", "%1F", "%7F", "%C2%80", "%C2%85", "%C2%9F")

  test("a resolver URL that decodes to a control character is rejected, anywhere in the DOI") {
    for
      esc <- controlEscapes
      raw <- Seq(
        s"https://doi.org/10.1000/abc$esc",
        s"https://doi.org/10.1000/abc${esc}def",
        s"http://dx.doi.org/${esc}10.1000/abc",
        s"https://doi.org/10.1000/$esc"
      )
    do
      val r = ExternalIds.normalizeDoi(raw)
      withClue(raw)(r.left.toOption.get should include("non-graphic character"))
  }

  test("bare and doi: forms with an interior control character are rejected") {
    val controls =
      Seq('\u0000', '\u0001', '\u001b', '\u001f', '\u007f', '\u0080', '\u0085', '\u009f')
    for
      c <- controls
      raw <- Seq(s"10.1000/ab${c}c", s"doi:10.1000/ab${c}c", s"DOI:10.1000/abc${c}def")
    do withClue(show(raw))(ExternalIds.normalizeDoi(raw).isLeft shouldBe true)
    // A C1 control is not removed by trim, so a trailing one is rejected too.
    ExternalIds.normalizeDoi("10.1000/abc\u0085").isLeft shouldBe true
  }

  test("leading or trailing C0 controls on a bare DOI are trimmed; the result is clean") {
    ExternalIds.normalizeDoi("10.1000/abc\u0000") shouldBe Right("10.1000/abc")
    ExternalIds.normalizeDoi("\u0001doi:10.1000/ABC\u001f") shouldBe Right("10.1000/abc")
  }

  test("Unicode separators and unpaired surrogates are rejected") {
    val bad = Seq(
      "10.1000/ab c",
      "10.1000/abc ",
      "10.1000/ab c",
      "10.1000/ab c",
      "10.1000/ab　c",
      "https://doi.org/10.1000/abc%C2%A0",
      "https://doi.org/10.1000/a%E2%80%A8b",
      "10.1000/ab\ud800c",
      "10.1000/abc\udc00"
    )
    bad.foreach(raw => withClue(show(raw))(ExternalIds.normalizeDoi(raw).isLeft shouldBe true))
  }

  test("graphic non-ASCII characters, format characters and astral code points are accepted") {
    ExternalIds.normalizeDoi("10.1000/café") shouldBe Right("10.1000/café")
    ExternalIds.normalizeDoi("10.1000/a‍b") shouldBe Right("10.1000/a‍b")
    ExternalIds.normalizeDoi("https://doi.org/10.1000/%F0%9F%98%80") shouldBe
      Right("10.1000/😀")
  }

  test("the rejection message makes the control character visible") {
    val msg = ExternalIds.normalizeDoi("https://doi.org/10.1000/abc%00").left.toOption.get
    msg should include("U+0000")
    msg.exists(Character.isISOControl) shouldBe false
  }

  test("WorkRecord DOI contract and work IDs reject a control-bearing DOI") {
    StableIds.workId(WorkNamespace.Doi, "https://doi.org/10.1000/abc%00").isLeft shouldBe true
    ExternalIds.normalizeExactId(ExactIdType.DOI, "10.1000/ab\u0001c").isLeft shouldBe true
  }

  test("every accepted DOI is a fixed point, for every code point in every position and form") {
    val failures = Iterator
      .range(0, Character.MAX_CODE_POINT + 1)
      .filter(cp => cp < 0x3000 || cp % 97 == 0) // all of BMP below U+3000, sampled above
      .flatMap { cp =>
        val ch = new String(Character.toChars(cp))
        val enc = ch
          .getBytes(java.nio.charset.StandardCharsets.UTF_8)
          .map(b => f"%%${b & 0xff}%02X")
          .mkString
        Seq(
          s"10.1000/a${ch}b",
          s"10.1000/ab$ch",
          s"$ch" + "10.1000/ab",
          s"doi:10.1000/A${ch}B",
          s"https://doi.org/10.1000/a${enc}b",
          s"https://doi.org/10.1000/ab$enc",
          s"https://doi.org/${enc}10.1000/ab"
        ).iterator.flatMap { raw =>
          ExternalIds.normalizeDoi(raw) match
            case Right(v) =>
              val again = ExternalIds.normalizeDoi(v)
              if again != Right(v) || v.exists(Character.isISOControl) then
                Some(f"U+$cp%04X ${show(raw)} -> ${show(v)} -> ${again.map(show)}")
              else None
            case Left(_) => None
        }
      }
      .take(20)
      .toList
    withClue(failures.mkString("\n"))(failures shouldBe empty)
  }
