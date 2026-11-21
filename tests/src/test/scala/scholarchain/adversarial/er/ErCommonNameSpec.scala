package scholarchain.adversarial.er

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.model.{MatchOutcome, SourceType}
import scholarchain.er.adjudication.{FakeAdjudicator, InMemoryAdjudicationCache}
import scholarchain.er.features.Features
import scholarchain.er.ids.ErIdType

/** Very common names: distinct people called "Wei Zhang" must not be merged on name evidence.
  * Over-merging corrupts every downstream coauthor count, path and bridge score (spec, Phase 5).
  */
class ErCommonNameSpec extends AnyFunSuite with Matchers:
  import ErAdv.*

  private def solo(key: String, title: String, year: Int, venue: String, name: String) =
    mention(
      work(
        dblpWorkId(key),
        title = title,
        authors = Vector(name),
        year = Some(year),
        venues = Vector(venue)
      ),
      1
    )

  test("two single-author 'Wei Zhang' papers with nothing else in common: not ACCEPTED") {
    val a = solo("conf/a/Z1", "Approximate query answering", 2011, "VLDB", "Wei Zhang")
    val b = solo("conf/b/Z2", "Wireless sensor routing", 2019, "INFOCOM", "Wei Zhang")
    decide(pair(a, b)).decision.decision should not be MatchOutcome.ACCEPTED
  }

  test("two 'Wei Zhang' mentions with no context at all: not ACCEPTED under any adjudicator") {
    val a = solo("conf/a/Z1", "Approximate query answering", 2011, "VLDB", "Wei Zhang")
    val b = solo("conf/b/Z2", "Wireless sensor routing", 2019, "INFOCOM", "Wei Zhang")
    val withFake = ctx(adjudication = Some(service(FakeAdjudicator())))
    decide(pair(a, b), withFake).decision.decision should not be MatchOutcome.ACCEPTED
  }

  test("two 'Wei Zhang' in the same venue and year, disjoint coauthors: not ACCEPTED") {
    val w1 = work(
      dblpWorkId("conf/vldb/ZhangAB18"),
      title = "Learned index structures for spatial data",
      authors = Vector("Wei Zhang", "Anna Berg", "Tom Kraus")
    )
    val w2 = work(
      dblpWorkId("conf/vldb/ZhangCD18"),
      title = "Crowdsourced entity matching",
      authors = Vector("Wei Zhang", "Carlos Ruiz", "Dana Ito")
    )
    decide(pair(mention(w1, 1), mention(w2, 1))).decision.decision should not be
      MatchOutcome.ACCEPTED
  }

  test("distinct coauthors whose surname+initial keys collide are not full coauthor overlap") {
    // Li Wang vs Lei Wang and Yang Liu vs Yu Liu are different people, but under the
    // `<surname> <first initial>` key every one of these collapses ("wang l", "liu y").
    val a = Vector("Li Wang", "Yang Liu")
    val b = Vector("Lei Wang", "Yu Liu")
    Features.coauthorOverlap(a, b) should not be Some(1.0)
  }

  test(
    "two different 'Wei Zhang' with key-colliding coauthors and a common affiliation: not ACCEPTED"
  ) {
    // Realistic over-merge: very common Chinese surnames make coauthor keys collide, and a large
    // university is a weak affiliation signal. Titles, years and venues all differ.
    val w1 = work(
      dblpWorkId("conf/icde/ZhangWL12"),
      title = "Top-k query processing over uncertain data",
      year = Some(2012),
      authors = Vector("Wei Zhang", "Li Wang", "Yang Liu"),
      venues = Vector("ICDE")
    )
    val w2 = work(
      dblpWorkId("journals/tmc/ZhangWL18"),
      title = "Energy harvesting in vehicular networks",
      year = Some(2018),
      authors = Vector("Wei Zhang", "Lei Wang", "Yu Liu"),
      venues = Vector("IEEE Transactions on Mobile Computing")
    )
    val aff = Vector("Tsinghua University")
    val r = decide(pair(mention(w1, 1, affiliations = aff), mention(w2, 1, affiliations = aff)))
    withClue(explain(r)) { r.decision.decision should not be MatchOutcome.ACCEPTED }
  }

  test("two authors named 'Wei Zhang' on the SAME paper (positions 1 and 3) are never ACCEPTED") {
    // One person occupies one slot of an author list. Two slots of one work with the same name
    // are two people (this happens for very common names).
    val w = work(
      dblpWorkId("conf/kdd/ZhangWZL20"),
      authors = Vector("Wei Zhang", "Li Wang", "Wei Zhang", "Yang Liu")
    )
    val r = decide(pair(mention(w, 1), mention(w, 3)))
    withClue(explain(r)) { r.decision.decision should not be MatchOutcome.ACCEPTED }
  }

  test("same DOI in two sources, 'Wei Zhang' at slots 1 and 3: not ACCEPTED") {
    val doi = "10.1145/3394486.3403100"
    val authors = Vector("Wei Zhang", "Li Wang", "Wei Zhang", "Yang Liu")
    val d =
      work(dblpWorkId("conf/kdd/ZhangWZL20"), authors = authors, workIds = ids(ErIdType.DOI -> doi))
    val c = work(
      doiWorkId(doi),
      SourceType.CROSSREF,
      authors = authors,
      workIds = ids(ErIdType.DOI -> doi)
    )
    val r = decide(pair(mention(d, 1), mention(c, 3)))
    withClue(explain(r)) { r.decision.decision should not be MatchOutcome.ACCEPTED }
  }

  // D6: known limitation, deferred in Phase 5 (modules/entity-resolution/KNOWN-LIMITATIONS.md).
  ignore("an LLM ACCEPT that cites only name evidence does not merge two 'Wei Zhang'") {
    // "A title or author-name match is not identity" (spec, Phase 5). An adjudication whose only
    // cited evidence is name similarity is a name-only merge.
    val nameOnly = ScriptedAdjudicator(req =>
      Right(
        response(
          "ACCEPT",
          Seq(req.leftRef, req.rightRef),
          cited = Seq("features.nameSimilarity", "left.name", "right.name"),
          confidence = ujson.Num(0.99),
          reasons = Seq("SAME_NAME")
        ).render()
      )
    )
    val a = solo("conf/a/Z1", "Approximate query answering", 2011, "VLDB", "Wei Zhang")
    val b = solo("conf/b/Z2", "Wireless sensor routing", 2019, "INFOCOM", "Wei Zhang")
    val r = decide(pair(a, b), ctx(adjudication = Some(service(nameOnly))))
    nameOnly.calls shouldBe 1 // the pair is in the configured LLM band
    withClue(explain(r)) { r.decision.decision should not be MatchOutcome.ACCEPTED }
  }

  test("a name-only pair gets confidence 0 when UNRESOLVED (no outcome is asserted)") {
    val a = solo("conf/a/Z1", "Approximate query answering", 2011, "VLDB", "Wei Zhang")
    val b = solo("conf/b/Z2", "Wireless sensor routing", 2019, "INFOCOM", "Wei Zhang")
    val r = decide(pair(a, b))
    if r.decision.decision == MatchOutcome.UNRESOLVED then r.decision.confidence shouldBe 0.0
    else r.decision.decision shouldBe MatchOutcome.REJECTED
  }

  test("the decision for a common-name pair is deterministic across repeated runs") {
    val a = solo("conf/a/Z1", "Approximate query answering", 2011, "VLDB", "Wei Zhang")
    val b = solo("conf/b/Z2", "Wireless sensor routing", 2019, "INFOCOM", "Wei Zhang")
    val runs = (1 to 3).map(_ =>
      decisionJson(
        decide(
          pair(a, b),
          ctx(adjudication = Some(service(FakeAdjudicator(), InMemoryAdjudicationCache())))
        )
      )
    )
    runs.distinct.size shouldBe 1
  }
