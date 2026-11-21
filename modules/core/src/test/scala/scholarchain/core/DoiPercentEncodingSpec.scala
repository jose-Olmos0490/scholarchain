package scholarchain.core

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.ids.*
import scholarchain.core.model.*

/** Feature tests for percent-encoded DOI resolver URLs (phase 04 repair, defect 6). */
class DoiPercentEncodingSpec extends AnyFunSuite with Matchers:

  private val Decoded = "10.1002/(sici)1097-4571(199806)49:8"

  test("a percent-encoded resolver URL normalizes to the same DOI as its decoded form") {
    val forms = Seq(
      "https://doi.org/10.1002/(SICI)1097-4571(199806)49:8",
      "https://doi.org/10.1002/%28SICI%291097-4571%28199806%2949%3A8",
      "http://dx.doi.org/10.1002/%28sici%291097-4571%28199806%2949%3a8",
      "HTTPS://DOI.ORG/10.1002/%28SICI)1097-4571(199806%2949:8",
      "10.1002/(SICI)1097-4571(199806)49:8",
      "doi:10.1002/(SICI)1097-4571(199806)49:8"
    )
    forms.map(ExternalIds.normalizeDoi).distinct shouldBe Seq(Right(Decoded))
  }

  test("normalizeExactId(DOI) and workId(Doi) agree for encoded and decoded URLs") {
    val enc = "https://doi.org/10.1002/%28SICI%291097-4571%28199806%2949%3A8"
    val dec = "https://doi.org/10.1002/(SICI)1097-4571(199806)49:8"
    ExternalIds.normalizeExactId(ExactIdType.DOI, enc) shouldBe Right(Decoded)
    StableIds.workId(WorkNamespace.Doi, enc) shouldBe StableIds.workId(WorkNamespace.Doi, dec)
    StableIds.workId(WorkNamespace.Doi, enc) shouldBe StableIds.workId(WorkNamespace.Doi, Decoded)
  }

  test("%2F decodes to '/' in a resolver URL") {
    ExternalIds.normalizeDoi("https://doi.org/10.1000%2Fabc") shouldBe Right("10.1000/abc")
    ExternalIds.normalizeDoi("https://doi.org/10.1000/a%2fb") shouldBe Right("10.1000/a/b")
  }

  test("decoding is a single pass: %25 yields a literal '%' that is not decoded again") {
    ExternalIds.normalizeDoi("https://doi.org/10.1000/a%2528b") shouldBe Right("10.1000/a%28b")
  }

  test("multi-byte UTF-8 escapes decode; '+' is not a space") {
    ExternalIds.normalizeDoi("https://doi.org/10.1000/caf%C3%A9") shouldBe Right("10.1000/café")
    ExternalIds.normalizeDoi("https://doi.org/10.1000/a+b") shouldBe Right("10.1000/a+b")
  }

  test("bare and doi: forms are never percent-decoded ('%' is legal in a DOI suffix)") {
    ExternalIds.normalizeDoi("10.1000/a%28b") shouldBe Right("10.1000/a%28b")
    ExternalIds.normalizeDoi("doi:10.1000/a%28b") shouldBe Right("10.1000/a%28b")
    ExternalIds.normalizeDoi("10.1000/100%") shouldBe Right("10.1000/100%")
  }

  test("malformed escapes and invalid UTF-8 in a resolver URL are rejected, never thrown") {
    val bad = Seq(
      "https://doi.org/10.1000/abc%",
      "https://doi.org/10.1000/abc%2",
      "https://doi.org/10.1000/abc%zz",
      "https://doi.org/10.1000/a%G1b",
      "https://doi.org/10.1000/a%C3", // truncated UTF-8 sequence
      "https://doi.org/10.1000/a%FFb" // never valid in UTF-8
    )
    bad.foreach { raw =>
      val r = ExternalIds.normalizeDoi(raw)
      withClue(raw)(r.left.toOption.get should include("malformed DOI"))
    }
  }

  test("decoded whitespace still fails the DOI pattern") {
    ExternalIds.normalizeDoi("https://doi.org/10.1000/a%20b").isLeft shouldBe true
  }

  test("frozen DOI work ID is unchanged by the decoding rule") {
    val frozen = "work:doi:5e260c8ede95e178801d1278e8b61cd1efb9aadba5ffcb203862531ac719420f"
    StableIds.workId(WorkNamespace.Doi, "10.1145/337292.337434") shouldBe Right(frozen)
    StableIds.workId(WorkNamespace.Doi, "https://doi.org/10.1145%2F337292.337434") shouldBe
      Right(frozen)
  }
