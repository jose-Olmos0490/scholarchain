package scholarchain.adversarial.er

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.model.{MatchOutcome, SourceType}
import scholarchain.er.features.Features
import scholarchain.er.ids.ErIdType
import scholarchain.er.text.TextNormalize

/** Reordered and initial-only names: "Zhang, Wei" vs "Wei Zhang" vs "W. Zhang". */
class ErNameOrderSpec extends AnyFunSuite with Matchers:
  import ErAdv.*

  private def pn(s: String) = TextNormalize.parseName(s)

  test("'Zhang, Wei' parses exactly like 'Wei Zhang'") {
    pn("Zhang, Wei") shouldBe pn("Wei Zhang")
    pn("Zhang, Wei").surnameKey shouldBe "zhang"
    pn("Zhang, Wei").givenNames shouldBe Vector("wei")
  }

  test("'Zhang, W.' parses exactly like 'W. Zhang'") {
    pn("Zhang, W.") shouldBe pn("W. Zhang")
  }

  test("'W. Zhang' has the comparison key of 'Wei Zhang'") {
    Features.nameKey("W. Zhang") shouldBe Features.nameKey("Wei Zhang")
    Features.nameKey("Zhang, W.") shouldBe "zhang w"
  }

  test("initials compatibility: W. vs Wei is compatible, W. vs Li is not") {
    Features.initialsCompatibility(pn("W. Zhang"), pn("Wei Zhang")) shouldBe 1.0
    Features.initialsCompatibility(pn("W. Zhang"), pn("Li Zhang")) shouldBe 0.0
  }

  test("dotted initials without spaces split: 'W.J. Zhang' == 'W. J. Zhang'") {
    pn("W.J. Zhang") shouldBe pn("W. J. Zhang")
    pn("W.J. Zhang").initials shouldBe Vector("w", "j")
  }

  test("a name with a generational suffix: 'Zhang, Jr.' is not read as given name 'Jr'") {
    pn("Wei Zhang Jr.").surnameKey shouldBe "zhang"
    pn("Zhang, Jr.").givenNames should not contain "jr"
  }

  test("surname particles: 'van der Berg, Jan' == 'Jan van der Berg'") {
    pn("van der Berg, Jan") shouldBe pn("Jan van der Berg")
    pn("Jan van der Berg").surnameKey shouldBe "berg"
  }

  test("an initial-only name is not ACCEPTED against a full name without other evidence") {
    val w1 = work(dblpWorkId("conf/x/Z10"), authors = Vector("W. Zhang"), year = Some(2010))
    val w2 = work(
      dblpWorkId("conf/y/Z19"),
      title = "Deep learning for query optimization",
      authors = Vector("Wei Zhang"),
      year = Some(2019),
      venues = Vector("SIGMOD")
    )
    val r = decide(pair(mention(w1, 1), mention(w2, 1)))
    withClue(explain(r)) { r.decision.decision should not be MatchOutcome.ACCEPTED }
  }

  test("'W. Zhang' bridges 'Wei Zhang' and 'Wen Zhang', but neither link is ACCEPTED alone") {
    // W. is compatible with both; accepting both links would transitively merge Wei and Wen.
    def solo(key: String, name: String, title: String, year: Int) =
      mention(work(dblpWorkId(key), title = title, authors = Vector(name), year = Some(year)), 1)
    val w = solo("conf/a/1", "W. Zhang", "Stream processing at scale", 2012)
    val wei = solo("conf/b/2", "Wei Zhang", "Graph neural networks", 2020)
    val wen = solo("conf/c/3", "Wen Zhang", "Protein folding kernels", 2016)
    // Name-only evidence: no deterministic or score-based accept may happen for any link.
    decide(pair(w, wei)).decision.decision should not be MatchOutcome.ACCEPTED
    decide(pair(w, wen)).decision.decision should not be MatchOutcome.ACCEPTED
    decide(pair(wei, wen)).decision.decision should not be MatchOutcome.ACCEPTED
  }

  test("dblp homonym keys 'Wei Zhang 0001' vs 'Wei Zhang 0002' are never ACCEPTED") {
    val coauthors = Vector("Li Wang", "Yang Liu")
    val w1 = work(dblpWorkId("conf/a/Z1"), authors = "Wei Zhang 0001" +: coauthors)
    val w2 = work(
      dblpWorkId("conf/a/Z2"),
      title = "Scalable graph partitioning revisited",
      authors = "Wei Zhang 0002" +: coauthors
    )
    val p = pair(
      mention(w1, 1, researcherIds = ids(ErIdType.DBLP_AUTHOR_KEY -> "Wei Zhang 0001")),
      mention(w2, 1, researcherIds = ids(ErIdType.DBLP_AUTHOR_KEY -> "Wei Zhang 0002"))
    )
    val r = decide(p, ctx(adjudication = Some(service(acceptingAdjudicator))))
    withClue(explain(r)) {
      r.decision.decision should (be(MatchOutcome.REJECTED) or be(MatchOutcome.CONFLICT))
    }
  }

  // --- family-name-first order without a comma -----------------------------------------------

  private def sameSlot(dblpName: String, crossrefName: String) =
    val doi = "10.1145/3448016.3457290"
    val authors = Vector(dblpName, "Mark Hill", "Sara Diaz")
    val d = work(
      dblpWorkId("conf/sigmod/ZhangHD21"),
      authors = authors,
      workIds = ids(ErIdType.DOI -> doi)
    )
    val c = work(
      doiWorkId(doi),
      SourceType.CROSSREF,
      authors = authors.updated(0, crossrefName),
      workIds = ids(ErIdType.DOI -> s"https://doi.org/$doi")
    )
    pair(mention(d, 1), mention(c, 1))

  test("same DOI and slot, 'Wei Zhang' vs 'Zhang, Wei': ACCEPTED") {
    decide(sameSlot("Wei Zhang", "Zhang, Wei")).decision.decision shouldBe MatchOutcome.ACCEPTED
  }

  test("same DOI and slot, 'Wei Zhang' vs 'W. Zhang': ACCEPTED") {
    decide(sameSlot("Wei Zhang", "W. Zhang")).decision.decision shouldBe MatchOutcome.ACCEPTED
  }

  // D7: known limitation, deferred in Phase 5 (modules/entity-resolution/KNOWN-LIMITATIONS.md).
  ignore(
    "same DOI and slot, 'Wei Zhang' vs family-first 'Zhang Wei': not REJECTED with certainty"
  ) {
    // Family-name-first order without a comma is common for East Asian names. Same DOI, same
    // author slot, same tokens: a certain SAME_WORK_DIFFERENT_AUTHOR rejection is a false fact.
    val r = decide(sameSlot("Wei Zhang", "Zhang Wei"))
    withClue(explain(r)) { r.decision.decision should not be MatchOutcome.REJECTED }
  }

  // D7: known limitation, deferred in Phase 5 (modules/entity-resolution/KNOWN-LIMITATIONS.md).
  ignore("same DOI and slot, 'Wei Zhang' vs 'ZHANG Wei' (upper-case family name): not REJECTED") {
    val r = decide(sameSlot("Wei Zhang", "ZHANG Wei"))
    withClue(explain(r)) { r.decision.decision should not be MatchOutcome.REJECTED }
  }
