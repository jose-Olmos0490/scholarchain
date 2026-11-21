package scholarchain.adversarial.er

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.ids.StableIds
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions
import scholarchain.er.ids.{ErIdType, ExactIdRules}

/** Same DOI with conflicting ORCIDs: CONFLICT, and never merged. */
class ErConflictingOrcidSpec extends AnyFunSuite with Matchers:
  import ErAdv.*

  private val doi = "10.1109/TCAD.2020.2966451"
  private val authors = Vector("Wei Zhang", "Mark Hill", "Sara Diaz")

  /** One paper seen by dblp (DOI as a resolver URL) and by Crossref (DOI with `doi:` prefix and
    * upper case), so the DOI agreement itself goes through normalization.
    */
  private def sameDoiPair(
      leftIds: Map[ErIdType, Set[String]],
      rightIds: Map[ErIdType, Set[String]],
      leftPos: Int = 1,
      rightPos: Int = 1,
      rightName: Option[String] = None
  ) =
    val d = work(
      dblpWorkId("journals/tcad/ZhangHD20"),
      authors = authors,
      workIds = ids(ErIdType.DOI -> s"https://doi.org/$doi")
    )
    val c = work(
      doiWorkId(doi.toLowerCase(java.util.Locale.ROOT)),
      SourceType.CROSSREF,
      authors = authors,
      workIds = ids(ErIdType.DOI -> s"doi:${doi.toUpperCase(java.util.Locale.ROOT)}")
    )
    pair(
      mention(d, leftPos, researcherIds = leftIds),
      mention(c, rightPos, researcherIds = rightIds, name = rightName)
    )

  test("DOI spellings (resolver URL, doi: prefix, case) normalize to one value") {
    val a = ExactIdRules.normalizeDoi(s"https://doi.org/$doi")
    a.isRight shouldBe true
    ExactIdRules.normalizeDoi(s"doi:${doi.toUpperCase(java.util.Locale.ROOT)}") shouldBe a
    ExactIdRules.normalizeDoi(s"http://dx.doi.org/$doi.") shouldBe a
  }

  test("ORCID spellings of one iD (URL, bare, lower-case x) normalize to one value") {
    val a = ExactIdRules.normalizeOrcid(orcidC)
    a.isRight shouldBe true
    ExactIdRules.normalizeOrcid(s"https://orcid.org/$orcidC") shouldBe a
    ExactIdRules.normalizeOrcid(orcidC.toLowerCase(java.util.Locale.ROOT)) shouldBe a
    ExactIdRules.normalizeOrcid(" " + orcidC + "\u200B ") shouldBe a
  }

  test("an ORCID with a bad checksum is rejected, not kept as a distinct iD") {
    ExactIdRules.normalizeOrcid("0000-0002-1825-0098").isLeft shouldBe true
  }

  test("one ORCID iD spelled two ways on the two sides is agreement, not CONFLICT") {
    val p = sameDoiPair(
      ids(ErIdType.ORCID -> orcidC),
      ids(ErIdType.ORCID -> s"https://orcid.org/${orcidC.toLowerCase(java.util.Locale.ROOT)}")
    )
    val r = decide(p)
    withClue(explain(r)) { r.decision.decision shouldBe MatchOutcome.ACCEPTED }
    r.decision.rulesFired should contain("EXACT_ID_AGREE:ORCID")
  }

  test("same DOI, same slot, same name, ORCID A vs ORCID B: CONFLICT with confidence 1") {
    val r = decide(sameDoiPair(ids(ErIdType.ORCID -> orcidA), ids(ErIdType.ORCID -> orcidB)))
    withClue(explain(r)) { r.decision.decision shouldBe MatchOutcome.CONFLICT }
    r.decision.confidence shouldBe 1.0
    r.decision.rulesFired should contain("EXACT_ID_DISAGREE:ORCID")
  }

  test("same DOI, same slot, initial-only name, ORCID A vs B: CONFLICT") {
    val p = sameDoiPair(
      ids(ErIdType.ORCID -> orcidA),
      ids(ErIdType.ORCID -> orcidB),
      rightName = Some("W. Zhang")
    )
    decide(p).decision.decision shouldBe MatchOutcome.CONFLICT
  }

  test("conflicting ORCIDs are never sent to the LLM, even when every score is in the band") {
    val adj = acceptingAdjudicator
    val c = ctx(allBandPolicy, Some(service(adj)))
    val r = decide(sameDoiPair(ids(ErIdType.ORCID -> orcidA), ids(ErIdType.ORCID -> orcidB)), c)
    adj.calls shouldBe 0
    withClue(explain(r)) { r.decision.decision shouldBe MatchOutcome.CONFLICT }
    r.decision.modelVersion shouldBe None
  }

  test("conflicting ORCIDs stay CONFLICT under every configured threshold setting") {
    val settings = Seq(
      ("0.80", "0.45", "0.60", "0.80"),
      ("1.0", "0.0", "0.0", "1.0"),
      ("0.0", "0.0", "0.0", "0.0"),
      ("1.0", "1.0", "1.0", "1.0")
    )
    for (acc, rej, lo, hi) <- settings do
      val pol = policyFrom(erEnv(acc, rej, lo, hi))
      val r = decide(
        sameDoiPair(ids(ErIdType.ORCID -> orcidA), ids(ErIdType.ORCID -> orcidB)),
        ctx(pol, Some(service(acceptingAdjudicator)))
      )
      withClue(s"thresholds accept=$acc reject=$rej band=[$lo,$hi): ") {
        withClue(explain(r)) { r.decision.decision shouldBe MatchOutcome.CONFLICT }
      }
  }

  test("same DOI, different slots, ORCID A vs B: never ACCEPTED, never sent to the LLM") {
    val adj = acceptingAdjudicator
    val r = decide(
      sameDoiPair(ids(ErIdType.ORCID -> orcidA), ids(ErIdType.ORCID -> orcidB), rightPos = 3),
      ctx(allBandPolicy, Some(service(adj)))
    )
    adj.calls shouldBe 0
    withClue(explain(r)) {
      r.decision.decision should (be(MatchOutcome.REJECTED) or be(MatchOutcome.CONFLICT))
    }
  }

  test("one mention carrying ORCIDs {A, C} vs one carrying {B, C}: not ACCEPTED") {
    // A Crossref mention can carry two ORCIDs (authorship `orcid` plus node `orcidRaw`). The
    // shared C does not cancel the A/B disagreement: each side asserts a different person too.
    val p = sameDoiPair(
      ids(ErIdType.ORCID -> orcidA, ErIdType.ORCID -> orcidC),
      ids(ErIdType.ORCID -> orcidB, ErIdType.ORCID -> orcidC)
    )
    val r = decide(p)
    withClue(explain(r)) { r.decision.decision should not be MatchOutcome.ACCEPTED }
  }

  test("ORCID agrees but OpenAlex author IDs disagree on the same DOI slot: CONFLICT") {
    val p = sameDoiPair(
      ids(ErIdType.ORCID -> orcidA, ErIdType.OPENALEX_AUTHOR -> "A5023888391"),
      ids(ErIdType.ORCID -> orcidA, ErIdType.OPENALEX_AUTHOR -> "A5000000001")
    )
    decide(p).decision.decision shouldBe MatchOutcome.CONFLICT
  }

  test("a Phase 4 CONFLICT outcome on the DOI-group pair is never merged") {
    val base = sameDoiPair(ids(ErIdType.ORCID -> orcidA), ids(ErIdType.ORCID -> orcidA))
    val outcome = ExactIdentifierOutcome(
      SchemaVersions.ExactIdentifierOutcome,
      ok(StableIds.exactIdOutcomeId(ExactIdType.ORCID, orcidA)),
      ExactIdType.ORCID,
      orcidA,
      Vector(base.left.ref, base.right.ref).sorted,
      ExactIdOutcome.CONFLICT,
      Provenance(
        SchemaVersions.Provenance,
        SourceType.SCHOLARCHAIN,
        "fixture",
        "2026-11-01",
        runId,
        None,
        None,
        None,
        Vector.empty
      )
    )
    val adj = acceptingAdjudicator
    val r =
      decide(base.copy(exactOutcomes = Vector(outcome)), ctx(adjudication = Some(service(adj))))
    withClue(explain(r)) { r.decision.decision shouldBe MatchOutcome.CONFLICT }
    adj.calls shouldBe 0
  }

  test("ORCID present on one side only is not a disagreement (no spurious CONFLICT)") {
    val r = decide(sameDoiPair(ids(ErIdType.ORCID -> orcidA), Map.empty))
    r.decision.rulesFired.filter(_.startsWith("EXACT_ID_DISAGREE")) shouldBe empty
    withClue(explain(r)) { r.decision.decision shouldBe MatchOutcome.ACCEPTED }
  }

  test("work level: same DOI, conflicting arXiv IDs: CONFLICT, never ACCEPTED") {
    val a = work(
      dblpWorkId("conf/nips/Zhang20"),
      workIds = ids(ErIdType.DOI -> doi, ErIdType.ARXIV -> "arXiv:2001.00001v2")
    )
    val b = work(
      doiWorkId(doi.toLowerCase(java.util.Locale.ROOT)),
      SourceType.OPENALEX,
      workIds = ids(ErIdType.DOI -> doi, ErIdType.ARXIV -> "2101.99999")
    )
    val adj = acceptingAdjudicator
    val r = decide(pair(a, b), ctx(allBandPolicy, Some(service(adj))))
    withClue(explain(r)) { r.decision.decision shouldBe MatchOutcome.CONFLICT }
    adj.calls shouldBe 0
  }

  test("CONFLICT decisions keep both refs and the policy version for audit") {
    val p = sameDoiPair(ids(ErIdType.ORCID -> orcidA), ids(ErIdType.ORCID -> orcidB))
    val r = decide(p)
    Set(r.decision.sourceId, r.decision.targetId) shouldBe Set(p.left.ref, p.right.ref)
    r.decision.policyVersion shouldBe "er-v1"
    r.decision.provenance.derivedFrom should contain allOf (p.left.ref, p.right.ref)
    r.decision.reasonCodes should not be empty
  }
