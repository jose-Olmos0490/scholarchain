package scholarchain.adversarial.er

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.model.{MatchEntityType, MatchOutcome, SourceType}
import scholarchain.er.features.Features
import scholarchain.er.ids.{ErIdType, ExactIdRules}
import scholarchain.er.model.ResearcherView
import scholarchain.er.policy.MatchPolicy
import scholarchain.er.text.TextNormalize

/** Empty and missing fields: no crash, no spurious ACCEPTED. */
class ErEmptyFieldsSpec extends AnyFunSuite with Matchers:
  import ErAdv.*

  private val blankNames =
    Seq("", " ", "\t\n", "\u00A0", "\u200B", "\uFEFF", ".", ",", ", ", "-", "?", null)

  test("blank, punctuation-only, suffix-only and null names parse to the empty name") {
    for raw <- blankNames do
      withClue(s"raw <$raw>: ") {
        val n = TextNormalize.parseName(raw)
        n.surnameKey shouldBe ""
        n.key shouldBe ""
      }
  }

  // D10: known limitation, deferred in Phase 5 (modules/entity-resolution/KNOWN-LIMITATIONS.md).
  ignore("a name that is only a dblp homonym suffix ('0001') parses to the empty name") {
    TextNormalize.parseName("0001").key shouldBe ""
    TextNormalize.parseName(" 0001 ").surnameKey shouldBe ""
  }

  test("two empty names score 0 name similarity (two blanks are not the same person)") {
    val e = TextNormalize.parseName("")
    Features.nameSimilarity(e, e) shouldBe 0.0
    Features.surnameSimilarity(e, e) shouldBe 0.0
  }

  test("empty coauthor lists are unavailable evidence, not perfect overlap") {
    Features.coauthorOverlap(Vector.empty, Vector.empty) shouldBe None
    Features.coauthorOverlap(Vector("", " ", "\u200B"), Vector("", "-")) shouldBe None
    Features.coauthorOverlap(Vector("Li Wang"), Vector.empty) shouldBe None
  }

  test("empty affiliations, venues, titles and references are unavailable, not a match") {
    Features.affiliationCompatibility(Vector(""), Vector("")) shouldBe None
    Features.affiliationCompatibility(Vector("of the"), Vector("and")) shouldBe None
    Features.venueCompatibility(Vector(""), Vector(" ")) shouldBe None
    Features.titleSimilarity("", "") shouldBe None
    Features.titleSimilarity("<i></i>", ".") shouldBe None
    Features.citationNeighborhood(Set.empty, Set.empty) shouldBe None
    Features.yearDistance(None, None, 3.0) shouldBe None
  }

  test("blank and null-like identifiers are rejected by every normalizer") {
    for
      t <- ErIdType.values.toSeq
      raw <- Seq("", "   ", "\u200B", "\uFEFF", null)
    do withClue(s"$t <$raw>: ") { ExactIdRules.normalize(t, raw).isLeft shouldBe true }
  }

  test("textual null placeholders are not valid work or researcher identifiers") {
    val nonDblp = ErIdType.values.toSeq.filterNot(_ == ErIdType.DBLP_AUTHOR_KEY)
    for
      t <- nonDblp
      raw <- Seq("null", "None", "N/A", "undefined", "-")
    do withClue(s"$t <$raw>: ") { ExactIdRules.normalize(t, raw).isLeft shouldBe true }
  }

  // D9: known limitation, deferred in Phase 5 (modules/entity-resolution/KNOWN-LIMITATIONS.md).
  ignore("a blank identifier value on both sides is never exact-ID agreement") {
    val a = work(
      dblpWorkId("conf/a/E1"),
      title = "Indexing time series",
      workIds = Map(ErIdType.DOI -> Set(""))
    )
    val b = work(
      dblpWorkId("conf/b/E2"),
      title = "Wireless mesh routing",
      year = Some(2003),
      authors = Vector("Ann Lee"),
      workIds = Map(ErIdType.DOI -> Set(""))
    )
    val r = decide(pair(a, b))
    r.decision.rulesFired should not contain "EXACT_ID_AGREE:DOI"
    withClue(explain(r)) { r.decision.decision should not be MatchOutcome.ACCEPTED }
  }

  test("an empty identifier set on one side is not a disagreement") {
    ExactIdRules
      .compare(Map(ErIdType.ORCID -> Set.empty), Map(ErIdType.ORCID -> Set(orcidA)))
      .agreementValue shouldBe 0.0
  }

  private def bare(ref: String, name: String, src: SourceType = SourceType.ORCID) =
    ResearcherView(
      ref = ref,
      source = src,
      sourceRecordId = "",
      snapshot = "2026-11-01",
      name = name,
      position = None,
      teamSize = None,
      work = None,
      coauthorNames = Vector.empty,
      affiliations = Vector.empty,
      ids = Map.empty,
      workIds = Map.empty
    )

  private val w1 = dblpWorkId("conf/a/P1")
  private val w2 = dblpWorkId("conf/b/P2")

  test(
    "two researchers with empty names and no other fields: decided without error, not ACCEPTED"
  ) {
    for n <- blankNames.filter(_ != null) do
      val res =
        MatchPolicy.decide(pair(bare(mentionId(w1, 1), n), bare(mentionId(w2, 1), n)), ctx())
      withClue(s"name <$n>: ") {
        res.isRight shouldBe true
        res.toOption.get.decision.decision should not be MatchOutcome.ACCEPTED
      }
  }

  test("a null name on both sides is decided without error and not ACCEPTED") {
    val res = MatchPolicy.decide(
      pair(bare(mentionId(w1, 1), null), bare(mentionId(w2, 1), null)),
      ctx(adjudication = Some(service(acceptingAdjudicator)))
    )
    res.isRight shouldBe true
    res.toOption.get.decision.decision should not be MatchOutcome.ACCEPTED
  }

  test("an empty name vs 'Wei Zhang': not ACCEPTED, even with an accepting LLM") {
    val r = decide(
      pair(bare(mentionId(w1, 1), ""), bare(mentionId(w2, 1), "Wei Zhang")),
      ctx(adjudication = Some(service(acceptingAdjudicator)))
    )
    withClue(explain(r)) { r.decision.decision should not be MatchOutcome.ACCEPTED }
  }

  test("textual placeholder names ('null', 'Unknown', 'Anonymous', 'N/A') are not ACCEPTED") {
    for n <- Seq("null", "Unknown", "Anonymous", "N/A", "et al.", "Unknown Author") do
      val r = decide(pair(bare(mentionId(w1, 1), n), bare(mentionId(w2, 1), n)))
      withClue(s"name <$n>: ") { r.decision.decision should not be MatchOutcome.ACCEPTED }
  }

  test("profile vs profile with only a shared name and nothing else: not ACCEPTED") {
    val r = decide(pair(bare(mentionId(w1, 1), "Wei Zhang"), bare(mentionId(w2, 1), "Wei Zhang")))
    withClue(explain(r)) { r.decision.decision should not be MatchOutcome.ACCEPTED }
  }

  test("works with empty titles, no year, no venue, no IDs: not ACCEPTED") {
    val a = work(dblpWorkId("conf/a/T1"), title = "", year = None, venues = Vector.empty)
    val b = work(dblpWorkId("conf/b/T2"), title = "", year = None, venues = Vector.empty)
    val r = decide(pair(a, b))
    withClue(explain(r)) { r.decision.decision should not be MatchOutcome.ACCEPTED }
  }

  test("works with empty titles and empty author lists: decided, not ACCEPTED") {
    val a = work(dblpWorkId("conf/a/T1"), title = "", authors = Vector.empty)
    val b = work(dblpWorkId("conf/b/T2"), title = " ", authors = Vector.empty)
    val res = MatchPolicy.decide(pair(a, b), ctx())
    res.isRight shouldBe true
    res.toOption.get.decision.decision should not be MatchOutcome.ACCEPTED
  }

  test(
    "two different editorials (same generic title, editor, journal, year; no DOI): not ACCEPTED"
  ) {
    // Journals publish several "Editorial" items a year by the same editor. With the DOI missing,
    // equal generic title + year + author list is not identity.
    val a = work(
      dblpWorkId("journals/tods/Editorial18a"),
      title = "Editorial.",
      year = Some(2018),
      authors = Vector("Christian S. Jensen"),
      venues = Vector("ACM Trans. Database Syst.")
    )
    val b = work(
      dblpWorkId("journals/tods/Editorial18b"),
      title = "Editorial",
      year = Some(2018),
      authors = Vector("Christian S. Jensen"),
      venues = Vector("ACM Trans. Database Syst.")
    )
    val r = decide(pair(a, b))
    withClue(explain(r)) { r.decision.decision should not be MatchOutcome.ACCEPTED }
  }

  test("a missing ORCID and a missing DOI on both mentions fall back to non-ID evidence") {
    val r = decide(bandPair)
    r.decision.rulesFired.filter(_.startsWith("EXACT_ID_")) shouldBe empty
    r.decision.decision shouldBe MatchOutcome.UNRESOLVED // in the band, no adjudicator configured
    r.decision.reasonCodes shouldBe Vector("LLM_BAND_NO_ADJUDICATOR")
  }

  test("mismatched view types for the entity kind are refused, not thrown") {
    val r = bare(mentionId(w1, 1), "Wei Zhang")
    val w = work(dblpWorkId("conf/a/T1"))
    val p = pair(r, bare(mentionId(w2, 1), "Wei Zhang"))
      .copy(right = w, kind = MatchEntityType.RESEARCHER)
    MatchPolicy.decide(p, ctx()).isLeft shouldBe true
  }

  test("a mention with no position or team size has no author-order feature (not 1.0)") {
    Features.mentionAuthorOrder(None, Some(3), Some(1), Some(3)) shouldBe None
    Features.mentionAuthorOrder(Some(1), None, Some(1), Some(3)) shouldBe None
  }

  test("a work with zero-size team and position 0 does not divide by zero") {
    Features.mentionAuthorOrder(Some(0), Some(0), Some(0), Some(0)) shouldBe Some(1.0)
  }

  // --- configuration omissions --------------------------------------------------------------

  test("a blank accept threshold fails configuration loading (no silent default)") {
    loadConfig(erEnv(accept = "")).isLeft shouldBe true
  }

  test("non-numeric, NaN and out-of-range thresholds fail configuration loading") {
    for bad <- Seq("abc", "NaN", "Infinity", "1.5", "-0.1", "0.8.0") do
      withClue(s"accept <$bad>: ") { loadConfig(erEnv(accept = bad)).isLeft shouldBe true }
  }

  test("inverted thresholds (reject above accept) fail configuration loading") {
    loadConfig(
      erEnv(accept = "0.40", reject = "0.60", bandMin = "0.60", bandMax = "0.60")
    ).isLeft shouldBe true
  }

  test("a missing policy version fails configuration loading or policy construction") {
    val env = erEnv() - "SCHOLARCHAIN_ER_POLICY_VERSION"
    loadConfig(env).flatMap(scholarchain.er.policy.Policy.fromConfig).isLeft shouldBe true
  }
