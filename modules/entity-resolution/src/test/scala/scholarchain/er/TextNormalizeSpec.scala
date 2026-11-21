package scholarchain.er

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.er.text.TextNormalize
import scholarchain.er.text.TextNormalize.PersonName

class TextNormalizeSpec extends AnyFunSuite with Matchers:

  // --- Unicode -------------------------------------------------------------------------------

  test("fold: removes diacritics by NFKD and lower-cases with Locale.ROOT") {
    TextNormalize.fold("Sünke Mehrgardt") shouldBe "sunke mehrgardt"
    TextNormalize.fold("JOSÉ") shouldBe "jose"
    TextNormalize.fold("İstanbul") shouldBe "istanbul"
  }

  test("fold: transliterates letters NFKD does not decompose") {
    TextNormalize.fold("Søren Kierkegaard") shouldBe "soren kierkegaard"
    TextNormalize.fold("Łukasz Straße") shouldBe "lukasz strasse"
    TextNormalize.fold("Æsir Œuvre") shouldBe "aesir oeuvre"
  }

  test("fold: compatibility forms (ligatures, full-width) fold to ASCII") {
    TextNormalize.fold("ﬁnite") shouldBe "finite"
    TextNormalize.fold("ＤＡＣ") shouldBe "dac"
  }

  test("fold: invisible format characters are removed without splitting the token") {
    TextNormalize.fold("Wa​ng") shouldBe "wang"
    TextNormalize.fold("co­author") shouldBe "coauthor"
  }

  test("fold: keeps letters of other scripts") {
    TextNormalize.fold("王 伟") shouldBe "王 伟"
    TextNormalize.fold("Иванов") shouldBe "иванов"
  }

  test("fold: null and blank give the empty string") {
    TextNormalize.fold(null) shouldBe ""
    TextNormalize.fold("  ​ ") shouldBe ""
  }

  // --- Punctuation and whitespace -----------------------------------------------------------

  test("fold: every run of punctuation becomes one space") {
    TextNormalize.fold("in-memory: computing!!") shouldBe "in memory computing"
  }

  test("stripPunctuation: keeps case and letters, drops punctuation") {
    TextNormalize.stripPunctuation("Zhi-Quan Luo, Jr.") shouldBe "Zhi Quan Luo Jr"
  }

  test("collapseWhitespace: no-break, ideographic and line separators collapse to one space") {
    TextNormalize.collapseWhitespace("  a  b　c d\t\n") shouldBe "a b c d"
    TextNormalize.collapseWhitespace("x​y") shouldBe "xy"
  }

  // --- Title markup -------------------------------------------------------------------------

  test("stripTitleMarkup: removes HTML/JATS/MathML tags without splitting words") {
    TextNormalize.stripTitleMarkup("<i>ftd</i>: exact") shouldBe "ftd: exact"
    TextNormalize.stripTitleMarkup("H<sub>2</sub>O splitting") shouldBe "H2O splitting"
    TextNormalize.stripTitleMarkup("<mml:math><mml:mi>x</mml:mi></mml:math> bounds") shouldBe
      "x bounds"
  }

  test("stripTitleMarkup: decodes character entities once") {
    TextNormalize.stripTitleMarkup("Tom &amp; Jerry &#233;t&#xE9;") shouldBe "Tom & Jerry été"
    TextNormalize.stripTitleMarkup("a &amp;lt; b") shouldBe "a &lt; b"
  }

  test("stripTitleMarkup: LaTeX commands keep their argument; $ and braces removed") {
    TextNormalize.stripTitleMarkup("On \\emph{fast} $O(n)$ {DSP}") shouldBe "On fast O(n) DSP"
  }

  test("stripTitleMarkup: drops one dblp trailing period, keeps an ellipsis") {
    TextNormalize.stripTitleMarkup("Hybrid timing closure.") shouldBe "Hybrid timing closure"
    TextNormalize.stripTitleMarkup("Wait for it...") shouldBe "Wait for it..."
  }

  test("normalizeTitle: dblp and Crossref spellings of one title are equal") {
    TextNormalize.normalizeTitle("Robust gate sizing by geometric programming.") shouldBe
      TextNormalize.normalizeTitle("Robust Gate Sizing by <i>Geometric</i> Programming")
  }

  // --- Names: initials, name order, suffixes ------------------------------------------------

  test("parseName: Given Surname order") {
    TextNormalize.parseName("Jaskirat Singh") shouldBe PersonName(
      Vector("jaskirat"),
      Vector("singh")
    )
  }

  test("parseName: 'Surname, Given' order is reordered") {
    TextNormalize.parseName("Singh, Jaskirat") shouldBe TextNormalize.parseName("Jaskirat Singh")
    TextNormalize.parseName("van der Berg, J.") shouldBe
      PersonName(Vector("j"), Vector("van", "der", "berg"))
  }

  test("parseName: dotted initials split into tokens with or without spaces") {
    TextNormalize.parseName("J.R.R. Tolkien").initials shouldBe Vector("j", "r", "r")
    TextNormalize.parseName("J. R. R. Tolkien").initials shouldBe Vector("j", "r", "r")
    TextNormalize.parseName("Sachin S. Sapatnekar").initials shouldBe Vector("s", "s")
  }

  test("parseName: hyphenated given names give one initial per part") {
    TextNormalize.parseName("Zhi-Quan Luo").initials shouldBe Vector("z", "q")
  }

  test("parseName: particles join the surname; surnameKey drops them") {
    val n = TextNormalize.parseName("Ludwig van Beethoven")
    n.surname shouldBe Vector("van", "beethoven")
    n.surnameKey shouldBe "beethoven"
  }

  test("parseName: dblp homonym suffix and generational suffixes are removed") {
    TextNormalize.parseName("Tao Yang 0031") shouldBe PersonName(Vector("tao"), Vector("yang"))
    TextNormalize.parseName("Martin Luther King Jr.").surnameKey shouldBe "king"
    // A suffix-only tail after the comma is not name order.
    TextNormalize.parseName("King, Jr.") shouldBe PersonName(Vector.empty, Vector("king"))
  }

  test("parseName: single token is a surname; empty name has no key") {
    TextNormalize.parseName("Madonna") shouldBe PersonName(Vector.empty, Vector("madonna"))
    TextNormalize.parseName("  ").key shouldBe ""
    TextNormalize.parseName(null).key shouldBe ""
  }

  test("PersonName.key is surname key plus first initial") {
    TextNormalize.parseName("Sachin S. Sapatnekar").key shouldBe "sapatnekar s"
    TextNormalize.parseName("S. Sapatnekar").key shouldBe "sapatnekar s"
  }

  // --- Venues and affiliations --------------------------------------------------------------

  test("normalizeVenue: removes year and ordinal tokens") {
    TextNormalize.normalizeVenue("DAC '26") shouldBe "dac"
    TextNormalize.normalizeVenue(
      "Proceedings of the 63rd ACM/IEEE Design Automation Conference"
    ) shouldBe
      "proceedings of the acm ieee design automation conference"
  }

  test("venueAcronym: initials of content words") {
    TextNormalize.venueAcronym("design automation conference") shouldBe "dac"
    TextNormalize.venueAcronym("proceedings of the acm ieee design automation conference") shouldBe
      "dac"
  }

  test("affiliationTokens: folded, stop words removed") {
    TextNormalize.affiliationTokens("Dept. of EE, The University of Minnesota") shouldBe
      Set("dept", "ee", "university", "minnesota")
  }
