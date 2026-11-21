package scholarchain.er

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.config.{ConfigLoader, EntityResolutionConfig}
import scholarchain.core.ids.StableIds
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions
import scholarchain.core.validation.RecordContract.given
import scholarchain.er.TestData.*
import scholarchain.er.adjudication.FakeAdjudicator
import scholarchain.er.ids.ErIdType
import scholarchain.er.model.*
import scholarchain.er.policy.*

class MatchPolicySpec extends AnyFunSuite with Matchers:

  private def decide(p: PairInput, c: DecisionContext = ctx()): DecisionResult =
    ok(MatchPolicy.decide(p, c))

  private val doi = "10.1145/3770743.3803897"
  private val orcidA = "0000-0002-1825-0097"
  private val orcidB = "0000-0001-5349-1825"

  /** Two sources' records of one paper; mention 1 on each. */
  private def sameDoiMentions(
      leftIds: Map[ErIdType, Set[String]],
      rightIds: Map[ErIdType, Set[String]],
      leftName: String = "Feng Guo",
      rightName: String = "Feng Guo"
  ): PairInput =
    val authors = Vector("Feng Guo", "Ann Other")
    val w1 = work(
      oaWorkId("W7223249703"),
      SourceType.OPENALEX,
      authors = authors,
      ids = Map(ErIdType.DOI -> Set(doi))
    )
    val w2 = work(
      doiWorkId(doi),
      SourceType.CROSSREF,
      authors = authors,
      ids = Map(ErIdType.DOI -> Set(doi))
    )
    pair(mention(w1, 1, leftName, leftIds), mention(w2, 1, rightName, rightIds))

  private def acceptingAdjudicator =
    ScriptedAdjudicator(r => Right(response("ACCEPT", Seq(r.leftRef, r.rightRef))))

  // --- Threshold boundaries (exactly at each threshold) --------------------------------------

  private lazy val s: Double = decide(borderlineResearcherPair).score

  test("the boundary fixture is decided on the probabilistic path with an interior score") {
    val r = decide(borderlineResearcherPair, ctx(Thresholds(0.99, 0.01, 0.02, 0.03)))
    r.path shouldBe MatchPath.PROBABILISTIC
    s should (be > 0.05 and be < 0.95)
  }

  test("score exactly at acceptThreshold is ACCEPTED") {
    val r = decide(borderlineResearcherPair, ctx(Thresholds(s, 0.01, 0.02, 0.03)))
    r.decision.decision shouldBe MatchOutcome.ACCEPTED
    r.decision.reasonCodes shouldBe Vector("SCORE_ACCEPT")
  }

  test("score just below acceptThreshold is not ACCEPTED") {
    val r = decide(borderlineResearcherPair, ctx(Thresholds(Math.nextUp(s), 0.01, 0.02, 0.03)))
    r.decision.decision shouldBe MatchOutcome.UNRESOLVED
  }

  test("score exactly at rejectThreshold is not REJECTED; just below it is") {
    val at = decide(borderlineResearcherPair, ctx(Thresholds(0.99, s, 0.97, 0.98)))
    at.decision.decision shouldBe MatchOutcome.UNRESOLVED
    at.decision.reasonCodes shouldBe Vector("SCORE_UNRESOLVED")
    val above = decide(borderlineResearcherPair, ctx(Thresholds(0.99, Math.nextUp(s), 0.97, 0.98)))
    above.decision.decision shouldBe MatchOutcome.REJECTED
    above.decision.reasonCodes shouldBe Vector("SCORE_REJECT")
  }

  test("score exactly at llmBandMin is adjudicated") {
    val adj = acceptingAdjudicator
    val r = decide(
      borderlineResearcherPair,
      ctx(Thresholds(0.999, 0.01, s, 0.99), Some(service(adj)))
    )
    adj.calls shouldBe 1
    r.path shouldBe MatchPath.LLM_ADJUDICATED
    r.decision.decision shouldBe MatchOutcome.ACCEPTED
  }

  test("score just below llmBandMin is not adjudicated") {
    val adj = acceptingAdjudicator
    val r = decide(
      borderlineResearcherPair,
      ctx(Thresholds(0.999, 0.01, Math.nextUp(s), 0.99), Some(service(adj)))
    )
    adj.calls shouldBe 0
    r.decision.decision shouldBe MatchOutcome.UNRESOLVED
  }

  test("score exactly at llmBandMax is not adjudicated (half-open band)") {
    val adj = acceptingAdjudicator
    val r = decide(
      borderlineResearcherPair,
      ctx(Thresholds(0.999, 0.01, 0.02, s), Some(service(adj)))
    )
    adj.calls shouldBe 0
    r.decision.decision shouldBe MatchOutcome.UNRESOLVED
    r.decision.reasonCodes shouldBe Vector("SCORE_UNRESOLVED")
  }

  test("llmBandMax == acceptThreshold == score accepts by score, without the LLM") {
    val adj = ScriptedAdjudicator(r => Right(response("REJECT", Seq(r.leftRef))))
    val r = decide(borderlineResearcherPair, ctx(Thresholds(s, 0.01, 0.02, s), Some(service(adj))))
    adj.calls shouldBe 0
    r.decision.decision shouldBe MatchOutcome.ACCEPTED
    r.path shouldBe MatchPath.PROBABILISTIC
  }

  test("outside the band nothing is sent to the LLM, inside it exactly one call") {
    val adj = acceptingAdjudicator
    val svc = Some(service(adj))
    decide(borderlineResearcherPair, ctx(Thresholds(0.99, Math.nextUp(s), 0.991, 0.992), svc))
    decide(borderlineResearcherPair, ctx(Thresholds(s, 0.01, 0.02, 0.03), svc))
    adj.calls shouldBe 0
  }

  // --- Thresholds come from configuration ----------------------------------------------------

  private def erEnv(
      accept: String,
      reject: String,
      min: String,
      max: String,
      version: String = "er-v1"
  ) =
    Map(
      "SCHOLARCHAIN_ENV" -> "er-test",
      "SCHOLARCHAIN_ER_POLICY_VERSION" -> version,
      "SCHOLARCHAIN_ER_ACCEPT_THRESHOLD" -> accept,
      "SCHOLARCHAIN_ER_REJECT_THRESHOLD" -> reject,
      "SCHOLARCHAIN_ER_LLM_BAND_MIN" -> min,
      "SCHOLARCHAIN_ER_LLM_BAND_MAX" -> max
    )

  test("thresholds and policy version are read from configuration") {
    val cfg = ConfigLoader.loadSection(EntityResolutionConfig, erEnv("0.9", "0.3", "0.5", "0.7"))
    val p = ok(cfg.left.map(_.toString).flatMap(Policy.fromConfig(_, cutoffs)))
    p.thresholds shouldBe Thresholds(0.9, 0.3, 0.5, 0.7)
    p.policyVersion shouldBe "er-v1"
  }

  test("changing only the configured accept threshold changes the decision") {
    def withAccept(a: Double) =
      val cfg = ok(
        ConfigLoader
          .loadSection(EntityResolutionConfig, erEnv(a.toString, "0.01", "0.02", "0.03"))
          .left
          .map(_.toString)
      )
      val pol = ok(Policy.fromConfig(cfg, cutoffs))
      decide(borderlineResearcherPair, DecisionContext(pol, runId, decidedAt, None))
    withAccept(s).decision.decision shouldBe MatchOutcome.ACCEPTED
    withAccept(math.min(1.0, s + 0.01)).decision.decision shouldBe MatchOutcome.UNRESOLVED
  }

  test("a configured policy version this build does not implement is refused") {
    val cfg = ok(
      ConfigLoader
        .loadSection(EntityResolutionConfig, erEnv("0.9", "0.3", "0.5", "0.7", "er-v9"))
        .left
        .map(_.toString)
    )
    Policy.fromConfig(cfg, cutoffs).isLeft shouldBe true
  }

  test("missing threshold configuration fails to load (no hardcoded default)") {
    ConfigLoader
      .loadSection(
        EntityResolutionConfig,
        erEnv("0.9", "0.3", "0.5", "0.7") - "SCHOLARCHAIN_ER_ACCEPT_THRESHOLD"
      )
      .isLeft shouldBe true
  }

  // --- Conflicts never merge -----------------------------------------------------------------

  test("same DOI, same position, different ORCIDs: CONFLICT, and the LLM is never asked") {
    val adj = acceptingAdjudicator
    val p = sameDoiMentions(Map(ErIdType.ORCID -> Set(orcidA)), Map(ErIdType.ORCID -> Set(orcidB)))
    val r = decide(p, ctx(adjudication = Some(service(adj))))
    r.decision.decision shouldBe MatchOutcome.CONFLICT
    r.decision.reasonCodes shouldBe Vector("EXACT_ID_DISAGREES_WITH_EVIDENCE")
    r.decision.rulesFired should contain("EXACT_ID_DISAGREE:ORCID")
    adj.calls shouldBe 0
  }

  test("identifiers agreeing on one type and disagreeing on another: CONFLICT") {
    val p = sameDoiMentions(
      Map(ErIdType.ORCID -> Set(orcidA), ErIdType.OPENALEX_AUTHOR -> Set("A1")),
      Map(ErIdType.ORCID -> Set(orcidA), ErIdType.OPENALEX_AUTHOR -> Set("A2"))
    )
    val r = decide(p)
    r.decision.decision shouldBe MatchOutcome.CONFLICT
    r.decision.reasonCodes shouldBe Vector("EXACT_ID_CONFLICT")
  }

  test("a Phase 4 exact-identifier CONFLICT outcome is never merged, even when IDs agree") {
    val base =
      sameDoiMentions(Map(ErIdType.ORCID -> Set(orcidA)), Map(ErIdType.ORCID -> Set(orcidA)))
    val outcome = ExactIdentifierOutcome(
      SchemaVersions.ExactIdentifierOutcome,
      ok(StableIds.exactIdOutcomeId(ExactIdType.ORCID, orcidA)),
      ExactIdType.ORCID,
      orcidA,
      Vector(base.left.ref, base.right.ref).sorted,
      ExactIdOutcome.CONFLICT,
      prov()
    )
    val r = decide(base.copy(exactOutcomes = Vector(outcome)))
    r.decision.decision shouldBe MatchOutcome.CONFLICT
    r.decision.rulesFired should contain("HADOOP_EXACT_ID_CONFLICT:ORCID")
    r.decision.provenance.derivedFrom should contain(outcome.outcomeId)
  }

  test("shared ORCID with an incompatible name: CONFLICT, not a merge") {
    val p = sameDoiMentions(
      Map(ErIdType.ORCID -> Set(orcidA)),
      Map(ErIdType.ORCID -> Set(orcidA)),
      rightName = "Maria Gonzalez"
    )
    decide(p).decision.reasonCodes shouldBe Vector("EXACT_ID_NAME_INCOMPATIBLE")
  }

  test("works: shared DOI with unrelated titles is a CONFLICT; title prefix is compatible") {
    val a = work(
      doiWorkId("10.1145/277044.277174"),
      SourceType.CROSSREF,
      title = "<i>ftd</i>",
      ids = Map(ErIdType.DOI -> Set("10.1145/277044.277174"))
    )
    val b = work(
      oaWorkId("W2079817815"),
      SourceType.OPENALEX,
      title = "ftd: an exact frequency to time domain conversion",
      ids = Map(ErIdType.DOI -> Set("10.1145/277044.277174"))
    )
    decide(pair(a, b)).decision.decision shouldBe MatchOutcome.ACCEPTED
    val c = b.copy(title = "Protein folding in yeast")
    val r = decide(pair(a.copy(title = "Gate sizing"), c))
    r.decision.decision shouldBe MatchOutcome.CONFLICT
    r.decision.reasonCodes shouldBe Vector("EXACT_ID_TITLE_INCOMPATIBLE")
  }

  test("works: different DOIs on bibliographically identical records: CONFLICT") {
    val a = work(
      dblpWorkId("conf/dac/SinghNLS05"),
      ids = Map(ErIdType.DOI -> Set("10.1145/1065579.1065662"))
    )
    val b = work(
      oaWorkId("W2131909792"),
      SourceType.OPENALEX,
      ids = Map(ErIdType.DOI -> Set("10.1109/dac.2005.193824"))
    )
    val r = decide(pair(a, b))
    r.decision.decision shouldBe MatchOutcome.CONFLICT
    r.decision.rulesFired should contain("EXACT_BIBLIOGRAPHIC_MATCH")
  }

  // --- Other paths ---------------------------------------------------------------------------

  test("exact identifier agreement accepts on the EXACT_ID path with confidence 1") {
    val r = decide(
      sameDoiMentions(Map(ErIdType.ORCID -> Set(orcidA)), Map(ErIdType.ORCID -> Set(orcidA)))
    )
    r.decision.decision shouldBe MatchOutcome.ACCEPTED
    r.path shouldBe MatchPath.EXACT_ID
    r.decision.confidence shouldBe 1.0
  }

  test("exact identifier disagreement without accepting evidence rejects") {
    val w1 =
      work(dblpWorkId("conf/dac/A01"), title = "Alpha", authors = Vector("Tao Yang 0031", "X Y"))
    val w2 = work(
      dblpWorkId("conf/dac/B02"),
      title = "Beta",
      year = Some(2015),
      authors = Vector("Tianyu Yang 0002", "P Q")
    )
    val p = pair(
      mention(w1, 1, "Tao Yang 0031", Map(ErIdType.DBLP_AUTHOR_KEY -> Set("Tao Yang 0031"))),
      mention(w2, 1, "Tianyu Yang 0002", Map(ErIdType.DBLP_AUTHOR_KEY -> Set("Tianyu Yang 0002")))
    )
    val r = decide(p)
    r.decision.decision shouldBe MatchOutcome.REJECTED
    r.decision.reasonCodes shouldBe Vector("EXACT_ID_DISAGREE")
  }

  test("same publication, same position, compatible names: deterministic ACCEPTED") {
    val r = decide(sameDoiMentions(Map.empty, Map.empty, rightName = "F. Guo"))
    r.path shouldBe MatchPath.DETERMINISTIC_BIBLIOGRAPHIC
    r.decision.decision shouldBe MatchOutcome.ACCEPTED
    r.decision.reasonCodes shouldBe Vector("SAME_WORK_SAME_POSITION")
  }

  test("same publication, incompatible names: deterministic REJECTED") {
    val r = decide(sameDoiMentions(Map.empty, Map.empty, rightName = "Ann Other"))
    r.decision.decision shouldBe MatchOutcome.REJECTED
    r.decision.reasonCodes shouldBe Vector("SAME_WORK_DIFFERENT_AUTHOR")
  }

  test("incompatible initials reject on the name gate whatever the score") {
    val p = borderlineResearcherPair
    val q = p.copy(right = p.right.asInstanceOf[ResearcherView].copy(name = "Karan Singh"))
    val r = decide(q, ctx(Thresholds(0.0, 0.0, 0.0, 0.0)))
    r.decision.decision shouldBe MatchOutcome.REJECTED
    r.decision.reasonCodes shouldBe Vector("NAME_INCOMPATIBLE")
  }

  test("works with equal normalized title, year and author sequence: deterministic ACCEPTED") {
    val a = work(
      dblpWorkId("conf/dac/SinghNLS05"),
      title = "Robust gate sizing by geometric programming."
    )
    val b = work(
      doiWorkId("10.1145/1065579.1065662"),
      SourceType.CROSSREF,
      title = "Robust Gate Sizing by Geometric Programming"
    )
    val r = decide(pair(a, b))
    r.path shouldBe MatchPath.DETERMINISTIC_BIBLIOGRAPHIC
    r.decision.decision shouldBe MatchOutcome.ACCEPTED
  }

  test("views that do not match the entity type are refused") {
    val p = borderlineResearcherPair
    MatchPolicy.decide(p.copy(kind = MatchEntityType.WORK), ctx()).isLeft shouldBe true
  }

  // --- Decision provenance -------------------------------------------------------------------

  test("decision provenance: every required field is recorded and the record is contract-valid") {
    val adj = acceptingAdjudicator
    val p = borderlineResearcherPair
    val r = decide(p, ctx(Thresholds(0.999, 0.01, 0.02, 0.998), Some(service(adj))))
    val d = r.decision
    d.sourceId shouldBe p.left.ref
    d.targetId shouldBe p.right.ref
    d.decisionId shouldBe ok(StableIds.matchDecisionId(p.left.ref, p.right.ref, "er-v1"))
    d.features.keySet should contain allOf ("score", "nameSimilarity", "hadoop.surnameEqual")
    d.features("score") shouldBe r.score
    d.rulesFired should contain allOf (
      "SCORE_IN_LLM_BAND",
      "LLM_ADJUDICATION",
      "PATH:LLM_ADJUDICATED"
    )
    d.rulesFired should contain("FEATURE_VERSION:er-features-v2")
    d.rulesFired should contain("POLICY_REVISION:er-v1-r2")
    d.decision shouldBe MatchOutcome.ACCEPTED
    d.confidence shouldBe 0.9
    d.reasonCodes should contain allOf ("LLM_ACCEPT", "LLM_COAUTHORS_SHARED")
    d.policyVersion shouldBe "er-v1"
    d.modelVersion shouldBe Some("scripted-test-model-v1")
    d.promptVersion shouldBe Some("er-adj-prompt-v1")
    d.decidedAt shouldBe decidedAt
    d.runId shouldBe runId
    d.provenance.runId shouldBe runId
    d.provenance.sourceDataset shouldBe SourceType.SCHOLARCHAIN
    d.provenance.sourceRecordId shouldBe p.candidateId
    d.provenance.policyVersion shouldBe Some("er-v1")
    d.provenance.modelVersion shouldBe Some("scripted-test-model-v1")
    d.provenance.promptVersion shouldBe Some("er-adj-prompt-v1")
    d.provenance.derivedFrom should contain allOf (p.left.ref, p.right.ref)
    WireCodec.validate(d).isRight shouldBe true
    WireCodec.decode[MatchDecision](WireCodec.encode(d)) shouldBe Right(d)
  }

  test("decisions without the LLM carry no model or prompt version") {
    val d = decide(borderlineResearcherPair).decision
    d.modelVersion shouldBe None
    d.promptVersion shouldBe None
    d.provenance.modelVersion shouldBe None
  }

  test("fake adjudicator decisions are labelled with the fake model version") {
    val r = decide(
      borderlineResearcherPair,
      ctx(Thresholds(0.999, 0.01, 0.02, 0.998), Some(service(FakeAdjudicator())))
    )
    r.decision.modelVersion shouldBe Some(FakeAdjudicator.ModelVersion)
  }
