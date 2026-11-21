package scholarchain.er

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.util.Random
import scholarchain.core.model.SourceType
import scholarchain.er.TestData.*
import scholarchain.er.features.{Features, Similarity}
import scholarchain.er.ids.ErIdType
import scholarchain.er.text.TextNormalize.parseName

class FeaturesSpec extends AnyFunSuite with Matchers:

  private def inUnit(v: Double): Boolean = v >= 0.0 && v <= 1.0

  // --- Primitives ----------------------------------------------------------------------------

  test("jaccard: range [0, 1], 0 for two empty sets") {
    Similarity.jaccard(Set(1, 2), Set(2, 3)) shouldBe (1.0 / 3)
    Similarity.jaccard(Set.empty[Int], Set.empty[Int]) shouldBe 0.0
    Similarity.jaccard(Set("a"), Set("a")) shouldBe 1.0
  }

  test("jaro-winkler: known values and bounds") {
    Similarity.jaroWinkler("martha", "marhta") shouldBe 0.9611 +- 0.0001
    Similarity.jaroWinkler("dixon", "dicksonx") shouldBe 0.8133 +- 0.0001
    Similarity.jaroWinkler("abc", "abc") shouldBe 1.0
    Similarity.jaroWinkler("abc", "") shouldBe 0.0
    Similarity.jaroWinkler("abc", "xyz") shouldBe 0.0
  }

  test("levenshtein ratio: known values and bounds") {
    Similarity.levenshteinRatio("kitten", "sitting") shouldBe (1.0 - 3.0 / 7) +- 1e-9
    Similarity.levenshteinRatio("", "") shouldBe 1.0
    Similarity.levenshteinRatio("a", "") shouldBe 0.0
  }

  test("all string primitives stay in [0, 1] on random input (fixed seed)") {
    val rnd = new Random(20261120L)
    val alphabet = "abcdeéß王 -".toVector
    def str() = Vector.fill(rnd.nextInt(12))(alphabet(rnd.nextInt(alphabet.size))).mkString
    (1 to 500).foreach { _ =>
      val a = str()
      val b = str()
      inUnit(Similarity.jaroWinkler(a, b)) shouldBe true
      inUnit(Similarity.levenshteinRatio(a, b)) shouldBe true
      Features.titleSimilarity(a, b).forall(inUnit) shouldBe true
      inUnit(Features.nameSimilarity(parseName(a), parseName(b))) shouldBe true
      Similarity.jaroWinkler(a, b) shouldBe Similarity.jaroWinkler(a, b) // pure
    }
  }

  // --- Researcher-name similarity, surname, initials -----------------------------------------

  test("nameSimilarity: identical names 1; initial-only vs full given name with same initial 1") {
    Features.nameSimilarity(parseName("Zhi-Quan Luo"), parseName("Zhi-Quan Luo")) shouldBe 1.0
    Features.nameSimilarity(parseName("J. Singh"), parseName("Jaskirat Singh")) shouldBe 1.0
  }

  test("nameSimilarity: different first initial lowers the score; no surname gives 0") {
    Features.nameSimilarity(parseName("R. Singh"), parseName("Jaskirat Singh")) shouldBe 0.6
    Features.nameSimilarity(parseName(""), parseName("Jaskirat Singh")) shouldBe 0.0
  }

  test("nameSimilarity: missing given name on one side contributes the neutral 0.5") {
    Features.nameSimilarity(parseName("Singh"), parseName("Jaskirat Singh")) shouldBe 0.8
  }

  test("nameSimilarity: name order variants are equal") {
    Features.nameSimilarity(parseName("Singh, Jaskirat"), parseName("Jaskirat Singh")) shouldBe 1.0
  }

  test("surnameSimilarity: particle-free key and full surname both compared") {
    Features.surnameSimilarity(
      parseName("L. van Beethoven"),
      parseName("Ludwig Beethoven")
    ) shouldBe
      1.0
    Features.surnameSimilarity(parseName("Zhuolun He"), parseName("Zhezhi He")) shouldBe 1.0
    Features.surnameSimilarity(parseName("A Smith"), parseName("A Jones")) should be < 0.85
  }

  test("initialsCompatibility: {0, 0.5, 1}") {
    Features.initialsCompatibility(parseName("J. Smith"), parseName("John R. Smith")) shouldBe 1.0
    Features.initialsCompatibility(parseName("R. Smith"), parseName("John R. Smith")) shouldBe 1.0
    Features.initialsCompatibility(parseName("K. Smith"), parseName("John R. Smith")) shouldBe 0.0
    Features.initialsCompatibility(parseName("Smith"), parseName("John Smith")) shouldBe 0.5
  }

  // --- Author order and coauthors ------------------------------------------------------------

  test("mentionAuthorOrder: 1 for same position, decreasing with distance, None when unknown") {
    Features.mentionAuthorOrder(Some(2), Some(4), Some(2), Some(4)) shouldBe Some(1.0)
    Features.mentionAuthorOrder(Some(1), Some(4), Some(3), Some(4)) shouldBe Some(0.5)
    Features.mentionAuthorOrder(Some(1), Some(2), Some(9), Some(2)) shouldBe Some(0.0)
    Features.mentionAuthorOrder(None, Some(2), Some(1), Some(2)) shouldBe None
  }

  test("authorSequenceCompatibility: aligned compatible names over the longer list") {
    val a = Vector("J. Singh", "V. Nookala", "Z. Luo")
    Features.authorSequenceCompatibility(
      a,
      Vector("Jaskirat Singh", "Vidyasagar Nookala", "Zhi-Quan Luo")
    ) shouldBe
      Some(1.0)
    Features.authorSequenceCompatibility(a, Vector("Jaskirat Singh", "Vidyasagar Nookala")) shouldBe
      Some(2.0 / 3)
    Features.authorSequenceCompatibility(a, a.reverse).get should be < 0.5
    Features.authorSequenceCompatibility(a, Vector.empty) shouldBe None
  }

  test("coauthorOverlap: matching Jaccard of compatible names; None when a side has none") {
    Features.coauthorOverlap(
      Vector("A. Smith", "B. Jones"),
      Vector("Alice Smith", "Carl Wu")
    ) shouldBe
      Some(1.0 / 3)
    Features.coauthorOverlap(Vector("A. Smith"), Vector.empty) shouldBe None
  }

  test("coauthorOverlap (D2): different full given names with colliding keys do not overlap") {
    // v1 keyed names on `<surname> <first initial>`: "wang l" and "liu y" on both sides gave 1.0.
    Features.coauthorOverlap(Vector("Li Wang", "Yang Liu"), Vector("Lei Wang", "Yu Liu")) shouldBe
      Some(0.0)
    Features.coauthorNamesCompatible(parseName("Li Wang"), parseName("Lei Wang")) shouldBe false
  }

  test("coauthorOverlap (D2): initials, hyphenation and name order variants still overlap") {
    Features.coauthorOverlap(
      Vector("J. Singh", "Zhi-Quan Luo", "Nookala, Vidyasagar"),
      Vector("Jaskirat Singh", "Zhiquan Luo", "Vidyasagar Nookala")
    ) shouldBe Some(1.0)
  }

  test("coauthorOverlap (D2): matching is one-to-one, so one name cannot match twice") {
    // "W. Zhang" is compatible with both, but only one of them can be its partner.
    Features.coauthorOverlap(Vector("W. Zhang"), Vector("Wei Zhang", "Wen Zhang")) shouldBe
      Some(1.0 / 2)
  }

  // --- Affiliation, IDs ----------------------------------------------------------------------

  test("affiliationCompatibility: best token Jaccard over affiliation pairs") {
    Features
      .affiliationCompatibility(
        Vector("Purdue University, West Lafayette, IN"),
        Vector("Dept. of ECE, Purdue University West Lafayette IN", "Other place")
      )
      .get shouldBe (4.0 / 6) +- 1e-9 // "IN" is a stop word
    Features.affiliationCompatibility(Vector("MIT"), Vector.empty) shouldBe None
  }

  test("externalIdAgreement: range [-1, 1]") {
    val o1 = Map(ErIdType.ORCID -> Set("0000-0002-1825-0097"))
    val o2 = Map(ErIdType.ORCID -> Set("0000-0001-5349-1825"))
    Features.externalIdAgreement(o1, o1) shouldBe 1.0
    Features.externalIdAgreement(o1, o2) shouldBe -1.0
    Features.externalIdAgreement(o1, Map.empty) shouldBe 0.0
  }

  // --- Titles, years, venues, citations, sources ---------------------------------------------

  test("titleSimilarity: 1 for spelling variants of one title; low for different titles") {
    Features.titleSimilarity(
      "Robust gate sizing by geometric programming.",
      "Robust Gate Sizing by Geometric Programming"
    ) shouldBe Some(1.0)
    Features
      .titleSimilarity("Robust gate sizing", "Low power DSP timing closure")
      .get should be < 0.3
    Features.titleSimilarity("", "x") shouldBe None
  }

  test("titleTokenPrefix: a title without its subtitle is a prefix") {
    Features.titleTokenPrefix(
      "<i>ftd</i>",
      "ftd: an exact frequency to time domain conversion"
    ) shouldBe
      1.0
    Features.titleTokenPrefix("time domain", "ftd: an exact frequency to time domain") shouldBe 0.0
    Features.titleTokenPrefix("", "x") shouldBe 0.0
  }

  test("yearDistance: 1 for same year, linear decay to 0 over the scale, None when unknown") {
    Features.yearDistance(Some(2005), Some(2005), 3.0) shouldBe Some(1.0)
    Features.yearDistance(Some(2005), Some(2006), 4.0) shouldBe Some(0.75)
    Features.yearDistance(Some(2000), Some(2020), 3.0) shouldBe Some(0.0)
    Features.yearDistance(None, Some(2020), 3.0) shouldBe None
  }

  test("venueCompatibility: acronym and full name match; unrelated venues score low") {
    Features.venueCompatibility(
      Vector("DAC"),
      Vector("Proceedings of the 42nd annual Design Automation Conference")
    ) shouldBe Some(1.0)
    Features.venueCompatibility(Vector("DAC '26"), Vector("DAC 2003")) shouldBe Some(1.0)
    Features.venueCompatibility(Vector("Circulation Reports"), Vector("DAC")).get shouldBe 0.0
    Features.venueCompatibility(Vector.empty, Vector("DAC")) shouldBe None
  }

  test("citationNeighborhood: Jaccard of typed references; None when a side has none") {
    Features.citationNeighborhood(Set("doi:10.1/a", "doi:10.1/b"), Set("doi:10.1/b")) shouldBe
      Some(0.5)
    Features.citationNeighborhood(Set.empty, Set("doi:10.1/b")) shouldBe None
  }

  test("sourceReliability: the lower of the two; unknown sources 0.5") {
    val t = Map("DBLP" -> 0.95, "OPENALEX" -> 0.85)
    Features.sourceReliability(SourceType.DBLP, SourceType.OPENALEX, t) shouldBe 0.85
    Features.sourceReliability(SourceType.DBLP, SourceType.ORCID, t) shouldBe 0.5
  }

  test("exactBibliographic: title, year and full author sequence must all agree") {
    val a = work(dblpWorkId("conf/dac/SinghNLS05"), title = "Robust gate sizing.")
    val b = work(
      doiWorkId("10.1145/1065579.1065662"),
      source = SourceType.CROSSREF,
      title = "Robust Gate Sizing"
    )
    val g = parameters.genericTitles
    Features.exactBibliographic(a, b, g) shouldBe 1.0
    Features.exactBibliographic(a, b.copy(year = Some(2006)), g) shouldBe 0.0
    Features.exactBibliographic(a, b.copy(authorNames = b.authorNames.reverse), g) shouldBe 0.0
    Features.exactBibliographic(a, b.copy(authorNames = b.authorNames.take(2)), g) shouldBe 0.0
  }

  test("exactBibliographic (F4): a generic title is never exact bibliographic agreement") {
    val g = parameters.genericTitles
    for t <- Seq("Editorial", "Preface.", "Foreword", "Introduction", "Erratum", "<i>Editorial</i>")
    do
      val a = work(dblpWorkId("journals/x/A"), title = t, authors = Vector("Christian S. Jensen"))
      val b = work(dblpWorkId("journals/x/B"), title = t, authors = Vector("Christian S. Jensen"))
      withClue(t)(Features.exactBibliographic(a, b, g) shouldBe 0.0)
      withClue(t)(Features.work(a, b, parameters)("genericTitle") shouldBe 1.0)
  }

  // --- Feature vectors and score -------------------------------------------------------------

  test("researcher feature vector: available features only, all within documented ranges") {
    val p = borderlineResearcherPair
    val f = Features.researcher(
      p.left.asInstanceOf[scholarchain.er.model.ResearcherView],
      p.right.asInstanceOf[scholarchain.er.model.ResearcherView],
      parameters
    )
    f.keySet.subsetOf(Features.ResearcherFeatureNames.toSet) shouldBe true
    f("externalIdAgreement") shouldBe 0.0
    f.removed("externalIdAgreement").values.forall(inUnit) shouldBe true
    f.contains("affiliationCompatibility") shouldBe false // no affiliation on either side
    f.contains("authorOrder") shouldBe false // not the same publication
  }

  test("work feature vector: available features only, all within documented ranges") {
    val a = work(dblpWorkId("conf/dac/SinghNLS05"))
    val b = work(doiWorkId("10.1145/1065579.1065662"), source = SourceType.CROSSREF)
    val f = Features.work(a, b, parameters)
    f.keySet.subsetOf(Features.WorkFeatureNames.toSet) shouldBe true
    f.removed("externalIdAgreement").values.forall(inUnit) shouldBe true
    f("titleSimilarity") shouldBe 1.0
  }

  test("score: weighted mean, missing features use the neutral value, unweighted ignored") {
    val w = Map("a" -> 3.0, "b" -> 1.0)
    Features.score(Map("a" -> 1.0, "b" -> 0.0), w, 0.5) shouldBe 0.75
    Features.score(Map("a" -> 1.0), w, 0.5) shouldBe 0.875
    Features.score(Map("a" -> 1.0, "b" -> 1.0, "ignored" -> -1.0), w, 0.5) shouldBe 1.0
    Features.score(Map.empty, Map.empty, 0.5) shouldBe 0.0
  }
