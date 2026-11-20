package scholarchain.core

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.ids.CanonicalPair

class CanonicalPairSpec extends AnyFunSuite with Matchers:

  test("orders two distinct members lexicographically regardless of input order") {
    val p = CanonicalPair.of("researcher:orcid:b", "researcher:openalex:a").toOption.get
    p.a shouldBe "researcher:openalex:a"
    p.b shouldBe "researcher:orcid:b"
    CanonicalPair.of("researcher:openalex:a", "researcher:orcid:b") shouldBe CanonicalPair.of(
      "researcher:orcid:b",
      "researcher:openalex:a"
    )
  }

  test("rejects a self-pair") {
    CanonicalPair.of("x", "x").isLeft shouldBe true
  }

  test("uses ordinal (code-unit) order, not locale collation") {
    // Upper-case letters sort before lower-case ones in ordinal order.
    CanonicalPair.of("b", "B").toOption.get.a shouldBe "B"
    CanonicalPair.isCanonical("B", "b") shouldBe true
    CanonicalPair.isCanonical("b", "B") shouldBe false
  }

  test("isCanonical is false for a self-pair") {
    CanonicalPair.isCanonical("x", "x") shouldBe false
  }
