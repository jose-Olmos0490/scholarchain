package scholarchain.er

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.er.ids.{ErIdType, ExactIdRules, IdComparison}

class ExactIdRulesSpec extends AnyFunSuite with Matchers:
  import ErIdType.*

  // --- DOI -----------------------------------------------------------------------------------

  test("DOI: resolver and doi: prefixes are removed and the value lower-cased") {
    val expected = Right("10.1145/1065579.1065662")
    ExactIdRules.normalizeDoi("10.1145/1065579.1065662") shouldBe expected
    ExactIdRules.normalizeDoi("doi:10.1145/1065579.1065662") shouldBe expected
    ExactIdRules.normalizeDoi("https://doi.org/10.1145/1065579.1065662") shouldBe expected
    ExactIdRules.normalizeDoi("http://dx.doi.org/10.1145/1065579.1065662") shouldBe expected
    ExactIdRules.normalizeDoi("HTTPS://DOI.ORG/10.1145/1065579.1065662") shouldBe expected
  }

  test("DOI: zero-width and no-break characters do not make a different DOI") {
    ExactIdRules.normalizeDoi("​10.1145/1065579.1065662 ") shouldBe
      Right("10.1145/1065579.1065662")
    ExactIdRules.normalizeDoi("10.1145/1065579​.1065662") shouldBe
      Right("10.1145/1065579.1065662")
  }

  test("DOI: a trailing sentence period is dropped; malformed values are rejected") {
    ExactIdRules.normalizeDoi("10.1145/1065579.1065662.") shouldBe Right("10.1145/1065579.1065662")
    ExactIdRules.normalizeDoi("11.1145/x").isLeft shouldBe true
    ExactIdRules.normalizeDoi("not a doi").isLeft shouldBe true
    ExactIdRules.normalizeDoi(null).isLeft shouldBe true
  }

  // --- arXiv ---------------------------------------------------------------------------------

  test("arXiv: version suffix and abs/pdf URL forms are removed") {
    ExactIdRules.normalizeArxiv("2101.00001v3") shouldBe Right("2101.00001")
    ExactIdRules.normalizeArxiv("arXiv:2101.00001") shouldBe Right("2101.00001")
    ExactIdRules.normalizeArxiv("https://arxiv.org/abs/2101.00001v2") shouldBe Right("2101.00001")
    ExactIdRules.normalizeArxiv("https://arxiv.org/pdf/2101.00001v2.pdf") shouldBe
      Right("2101.00001")
    ExactIdRules.normalizeArxiv("cs.DC/0101001") shouldBe Right("cs.DC/0101001")
    ExactIdRules.normalizeArxiv("21.01").isLeft shouldBe true
  }

  // --- ORCID ---------------------------------------------------------------------------------

  test("ORCID: resolver removed, checksum enforced") {
    ExactIdRules.normalizeOrcid("https://orcid.org/0000-0002-1825-0097") shouldBe
      Right("0000-0002-1825-0097")
    ExactIdRules.normalizeOrcid("0000-0002-1825-0097​") shouldBe Right("0000-0002-1825-0097")
    ExactIdRules.normalizeOrcid("0000-0002-1825-0098").isLeft shouldBe true
  }

  // --- OpenAlex ------------------------------------------------------------------------------

  test("OpenAlex: work and author IDs from bare, resolver and API forms") {
    ExactIdRules.normalizeOpenAlex("https://openalex.org/W2131909792", 'W') shouldBe
      Right("W2131909792")
    ExactIdRules.normalizeOpenAlex("https://api.openalex.org/works/w2131909792", 'W') shouldBe
      Right("W2131909792")
    ExactIdRules.normalizeOpenAlex("A5068714995", 'A') shouldBe Right("A5068714995")
    ExactIdRules.normalizeOpenAlex("A5068714995", 'W').isLeft shouldBe true
  }

  // --- Semantic Scholar ----------------------------------------------------------------------

  test("Semantic Scholar paper: 40-hex SHA, URL form and corpus ID") {
    val sha = "649def34f8be52c8b66281af98ae884c09aef38b"
    ExactIdRules.normalizeSemanticScholarPaper(sha.toUpperCase) shouldBe Right(sha)
    ExactIdRules.normalizeSemanticScholarPaper(
      s"https://www.semanticscholar.org/paper/Some-Title-Here/$sha"
    ) shouldBe Right(sha)
    ExactIdRules.normalizeSemanticScholarPaper("CorpusId:0012345") shouldBe Right("corpusid:12345")
    ExactIdRules.normalizeSemanticScholarPaper(
      "https://api.semanticscholar.org/CorpusID:12345"
    ) shouldBe
      Right("corpusid:12345")
    ExactIdRules.normalizeSemanticScholarPaper("abc").isLeft shouldBe true
    // A 40-hex string on another host or path is not a Semantic Scholar ID.
    ExactIdRules
      .normalizeSemanticScholarPaper(s"https://example.org/paper/$sha")
      .isLeft shouldBe true
    ExactIdRules
      .normalizeSemanticScholarPaper(
        s"https://www.semanticscholar.org/author/x/$sha"
      )
      .isLeft shouldBe true
  }

  test("Semantic Scholar author: digits, bare or from an author URL") {
    ExactIdRules.normalizeSemanticScholarAuthor("1741101") shouldBe Right("1741101")
    ExactIdRules.normalizeSemanticScholarAuthor(
      "https://www.semanticscholar.org/author/Oren-Etzioni/1741101"
    ) shouldBe Right("1741101")
    ExactIdRules
      .normalizeSemanticScholarAuthor("https://www.semanticscholar.org/paper/x/1741101")
      .isLeft shouldBe
      true
    ExactIdRules.normalizeSemanticScholarAuthor("12a").isLeft shouldBe true
  }

  // --- Repository URL ------------------------------------------------------------------------

  test("repository URL: scheme, www, case, .git, trailing slash, query and deep paths normalized") {
    val expected = Right("https://github.com/scholarchain/er")
    ExactIdRules.normalizeRepositoryUrl("https://github.com/ScholarChain/ER") shouldBe expected
    ExactIdRules.normalizeRepositoryUrl(
      "http://www.github.com/scholarchain/er.git"
    ) shouldBe expected
    ExactIdRules.normalizeRepositoryUrl("github.com/scholarchain/er/") shouldBe expected
    ExactIdRules.normalizeRepositoryUrl(
      "https://github.com/scholarchain/er/tree/main?x=1#y"
    ) shouldBe
      expected
    ExactIdRules.normalizeRepositoryUrl("git@github.com:scholarchain/er.git") shouldBe expected
  }

  test("repository URL: unknown hosts and owner-only URLs are not repository identifiers") {
    ExactIdRules.normalizeRepositoryUrl("https://example.org/scholarchain/er").isLeft shouldBe true
    ExactIdRules.normalizeRepositoryUrl("https://github.com/scholarchain").isLeft shouldBe true
  }

  // --- dblp ----------------------------------------------------------------------------------

  test("dblp author key: whitespace and Cf normalized, homonym suffix and case significant") {
    ExactIdRules.normalizeDblpAuthorKey("Tao Yang 0031") shouldBe Right("Tao Yang 0031")
    ExactIdRules.normalizeDblpAuthorKey("Tao Yang 0031") should not be
      ExactIdRules.normalizeDblpAuthorKey("Tao Yang 0032")
    ExactIdRules.normalizeDblpAuthorKey(" ​ ").isLeft shouldBe true
  }

  test("dblp record key via core normalizer") {
    ExactIdRules.normalizeDblpKey(" conf/dac/SinghNLS05 ") shouldBe Right("conf/dac/SinghNLS05")
  }

  // --- Link classification -------------------------------------------------------------------

  test("classifyWorkLink: identifies DOI, arXiv, S2, OpenAlex and repository links only") {
    ExactIdRules.classifyWorkLink("https://doi.org/10.1145/775832.776046") shouldBe
      Some(DOI -> "10.1145/775832.776046")
    ExactIdRules.classifyWorkLink("https://arxiv.org/abs/2101.00001v1") shouldBe
      Some(ARXIV -> "2101.00001")
    ExactIdRules.classifyWorkLink("https://openalex.org/W4417174194") shouldBe
      Some(OPENALEX_WORK -> "W4417174194")
    ExactIdRules.classifyWorkLink("https://github.com/a/b") shouldBe
      Some(REPOSITORY_URL -> "https://github.com/a/b")
    ExactIdRules.classifyWorkLink("https://ieeexplore.ieee.org/document/1") shouldBe None
    ExactIdRules.classifyWorkLink(null) shouldBe None
  }

  // --- Comparison ----------------------------------------------------------------------------

  test("compare: agreement, disagreement and conflict") {
    val a = Map(DOI -> Set("10.1/x"), ORCID -> Set("0000-0002-1825-0097"))
    val sameDoiOtherOrcid = Map(DOI -> Set("10.1/x"), ORCID -> Set("0000-0001-5349-1825"))
    val c = ExactIdRules.compare(a, sameDoiOtherOrcid)
    c.agree shouldBe Vector(DOI)
    c.disagree shouldBe Vector(ORCID)
    c.isConflict shouldBe true
    c.agreementValue shouldBe -1.0
    ExactIdRules.compare(a, a).agreementValue shouldBe 1.0
    ExactIdRules.compare(a, Map(ARXIV -> Set("2101.00001"))).agreementValue shouldBe 0.0
  }

  test("compare (F2): work identifier sets that each hold a value the other lacks conflict") {
    val c = ExactIdRules.compare(
      Map(DOI -> Set("10.1/a", "10.1/b")),
      Map(DOI -> Set("10.1/b", "10.1/c"))
    )
    c.agree shouldBe Vector(DOI)
    c.disagree shouldBe Vector(DOI)
    c.isConflict shouldBe true
  }

  test("compare (F2): a work record knowing one more DOI than the other is agreement") {
    val c = ExactIdRules.compare(Map(DOI -> Set("10.1/a", "10.1/b")), Map(DOI -> Set("10.1/b")))
    c.agree shouldBe Vector(DOI)
    c.hasDisagreement shouldBe false
  }

  test("compare (F2): overlapping ORCID sets {A,C} vs {B,C} are a conflict, not agreement") {
    val (a, b, cc) = ("0000-0002-1825-0097", "0000-0001-5109-3700", "0000-0002-1694-233X")
    val c = ExactIdRules.compare(Map(ORCID -> Set(a, cc)), Map(ORCID -> Set(b, cc)))
    c.agree shouldBe Vector(ORCID)
    c.disagree shouldBe Vector(ORCID)
    c.isConflict shouldBe true
    // A researcher record carrying an extra iD the other lacks is also contradicted.
    ExactIdRules.compare(Map(ORCID -> Set(a)), Map(ORCID -> Set(a, cc))).isConflict shouldBe true
    ExactIdRules.compare(Map(ORCID -> Set(a, cc)), Map(ORCID -> Set(cc, a))).isConflict shouldBe
      false
  }

  test("compare (F10): an unsuffixed dblp person key is not an identifier; a suffixed one is") {
    val unsuffixed = Map(DBLP_AUTHOR_KEY -> Set("Wei Zhang"))
    ExactIdRules.compare(unsuffixed, unsuffixed).hasAgreement shouldBe false
    ExactIdRules.compare(unsuffixed, Map(DBLP_AUTHOR_KEY -> Set("Wei Zhang 0001"))) shouldBe
      IdComparison(Vector.empty, Vector.empty)
    val s1 = Map(DBLP_AUTHOR_KEY -> Set("Wei Zhang 0001"))
    ExactIdRules.compare(s1, s1).agree shouldBe Vector(DBLP_AUTHOR_KEY)
    ExactIdRules.compare(s1, Map(DBLP_AUTHOR_KEY -> Set("Wei Zhang 0002"))).disagree shouldBe
      Vector(DBLP_AUTHOR_KEY)
    ExactIdRules.isDisambiguatedDblpAuthorKey("Wei Zhang 0001") shouldBe true
    ExactIdRules.isDisambiguatedDblpAuthorKey("Wei Zhang") shouldBe false
    ExactIdRules.isDisambiguatedDblpAuthorKey("0001") shouldBe false
  }

  test("compare (F10): a shared repository URL is not proof that two works are one") {
    val repo = Map(REPOSITORY_URL -> Set("https://github.com/a/code"))
    val c = ExactIdRules.compare(repo, repo)
    c.hasAgreement shouldBe false
    c.hasDisagreement shouldBe false
  }

  test("compare: two different repository URLs are not a disagreement") {
    val c = ExactIdRules.compare(
      Map(REPOSITORY_URL -> Set("https://github.com/a/code")),
      Map(REPOSITORY_URL -> Set("https://github.com/a/data"))
    )
    c.hasDisagreement shouldBe false
    c.hasAgreement shouldBe false
  }

  test("normalize dispatches every type") {
    ErIdType.values.foreach(t => ExactIdRules.normalize(t, null).isLeft shouldBe true)
  }
