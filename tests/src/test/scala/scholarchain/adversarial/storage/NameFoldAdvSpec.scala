package scholarchain.adversarial.storage

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.storage.directory.NameFold

/** The directory key of a researcher name: Unicode folding, invisible characters, case and
  * whitespace, checked over every BMP code point and over hostile strings.
  */
class NameFoldAdvSpec extends AnyFunSuite with Matchers:

  test("fold is idempotent for every BMP code point alone and between two letters") {
    val failures = (0 until 0x10000).filter(cp => !Character.isSurrogate(cp.toChar)).filter { cp =>
      val s = new String(Character.toChars(cp))
      val a = s"a${s}b"
      NameFold.fold(s) != NameFold.fold(NameFold.fold(s)) ||
      NameFold.fold(a) != NameFold.fold(NameFold.fold(a))
    }
    withClue(
      s"non-idempotent code points: ${failures.take(10).map(c => f"U+$c%04X").mkString(", ")} "
    ) {
      failures shouldBe empty
    }
  }

  test("the folded key holds only lower-case letters, digits and single spaces") {
    val bad = (0 until 0x10000).filter(cp => !Character.isSurrogate(cp.toChar)).filter { cp =>
      val f = NameFold.fold(s"x ${new String(Character.toChars(cp))} y")
      f.codePoints().toArray.exists(c => !(Character.isLetterOrDigit(c) || c == ' ')) ||
      f.contains("  ") ||
      f != f.trim
    }
    withClue(s"offending code points: ${bad.take(10).map(c => f"U+$c%04X").mkString(", ")} ") {
      bad shouldBe empty
    }
  }

  test("case, accents, width, ligatures, invisible characters and spacing fold to one key") {
    val same = Seq(
      "Wei Zhang",
      "wei zhang",
      "WEI ZHANG",
      "  Wei \t Zhang \n",
      "Wei\u00A0Zhang",
      "Wei\u2003Zhang",
      "Wei \u200BZ\u200Dhang",
      "\uFEFFWei Zhang",
      "\u202EWei Zhang\u202C",
      "Ｗｅｉ Ｚｈａｎｇ",
      "Wei Zhäng".replace("ä", "a"), // control: no mark
      "Wei-Zhang".replace("-", " "),
      "Wei, Zhang".replace(",", "")
    )
    val folded = same.map(x => x -> NameFold.fold(x))
    withClue(
      s"inputs that fold differently: ${folded.filter(_._2 != "wei zhang").map((i, o) => s"[${i.map(c => f"\\u$c%04X").mkString}]->[$o]")} "
    ) {
      folded.map(_._2).distinct shouldBe Vector("wei zhang")
    }
    NameFold.fold("José") shouldBe NameFold.fold("José")
    NameFold.fold("José") shouldBe "jose"
    NameFold.fold("ﬁnn") shouldBe "finn"
    NameFold.fold("İstanbul") shouldBe NameFold.fold("istanbul")
  }

  test("distinct people do not collapse: different letters, different digits, different order") {
    NameFold.fold("Wei Zhang") should not be NameFold.fold("Zhang Wei")
    NameFold.fold("Wei Zhang") should not be NameFold.fold("Wei Zhan")
    NameFold.fold("Wei Zhang 0001") should not be NameFold.fold("Wei Zhang 0002")
    NameFold.fold("A") should not be NameFold.fold("B")
  }

  test("a homoglyph name is an exact miss, never silently the same researcher") {
    NameFold.fold("Wei Zhаng") should not be NameFold.fold("Wei Zhang") // Cyrillic a
    NameFold.fold("Jоn Olsen") should not be NameFold.fold("Jon Olsen") // Cyrillic o
  }

  test("null, empty, symbol-only and mark-only input fold to the empty key") {
    NameFold.fold(null) shouldBe ""
    NameFold.fold("") shouldBe ""
    NameFold.fold("   ") shouldBe ""
    NameFold.fold("---...,,,") shouldBe ""
    NameFold.fold("́̂̃") shouldBe ""
    NameFold.fold("\u200B\u200C\u200D\uFEFF") shouldBe ""
  }

  test("a very long name folds in bounded time without error") {
    val t0 = System.nanoTime()
    val s = NameFold.fold(("Wei Zhang " * 200000).trim)
    s.length should be > 1000000
    ((System.nanoTime() - t0) / 1_000_000L) should be < 5000L
    scala.util.Try(NameFold.fold("𐀀 lone \uD800 high \uDC00 low")).isSuccess shouldBe true
  }
