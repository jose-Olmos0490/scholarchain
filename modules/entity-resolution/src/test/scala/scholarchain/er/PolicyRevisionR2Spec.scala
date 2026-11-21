package scholarchain.er

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.core.validation.RecordContract.given
import scholarchain.er.TestData.*
import scholarchain.er.adjudication.*
import scholarchain.er.ids.ErIdType
import scholarchain.er.model.*
import scholarchain.er.policy.*

/** Feature tests for policy er-v1 revision er-v1-r2 (Phase 05 repair-01, findings F1-F4,
  * F7-F13 and defect D2). Each test fails on revision r1.
  */
class PolicyRevisionR2Spec extends AnyFunSuite with Matchers:

  private def decide(p: PairInput, c: DecisionContext = ctx()): DecisionResult =
    ok(MatchPolicy.decide(p, c))

  private val orcidA = "0000-0002-1825-0097"
  private val orcidB = "0000-0001-5109-3700"
  private val orcidC = "0000-0002-1694-233X"

  private def acceptingAdjudicator =
    ScriptedAdjudicator(r => Right(response("ACCEPT", Seq(r.leftRef, r.rightRef))))

  /** Threshold settings from the most to the least permissive, plus the test default. */
  private val everyThreshold = Seq(
    Thresholds(0.0, 0.0, 0.0, 0.0),
    Thresholds(1.0, 0.0, 0.0, 1.0),
    Thresholds(0.5, 0.1, 0.2, 0.5),
    thresholds
  )

  private def neverAccepted(p: PairInput): Unit =
    for th <- everyThreshold do
      val r = decide(p, ctx(th, Some(service(acceptingAdjudicator))))
      withClue(s"$th ${r.decision.reasonCodes} ${r.decision.rulesFired}: ") {
        r.decision.decision should not be MatchOutcome.ACCEPTED
      }

  // --- F1: duplicate JSON keys -----------------------------------------------------------------

  private def bandRequest: AdjudicationRequest =
    val p = borderlineResearcherPair
    AdjudicationRequests.build(p, Map("nameSimilarity" -> 0.9), 0.7)

  test("F1: an output repeating 'outcome' (REJECT then ACCEPT) is rejected as DUPLICATE_KEY") {
    val req = bandRequest
    val ids = s"""["${req.leftRef}"]"""
    val raw =
      s"""{"outcome":"REJECT","candidateIds":$ids,"citedFields":["features.nameSimilarity"],""" +
        s""""confidence":0.95,"reasonCodes":["X"],"outcome":"ACCEPT"}"""
    val r = AdjudicationSchema.parse(raw, req)
    r.isLeft shouldBe true
    r.left.toOption.get should contain(s"${AdjudicationSchema.DuplicateKeyError}: outcome")
  }

  test("F1: duplicates are found after unescaping and inside nested objects") {
    val req = bandRequest
    val ok1 = response("ACCEPT", Seq(req.leftRef))
    val escaped = ok1.dropRight(1) + ",\"\\u006futcome\":\"REJECT\"}"
    AdjudicationSchema.parse(escaped, req).left.toOption.get.head should startWith(
      AdjudicationSchema.DuplicateKeyError
    )
    AdjudicationSchema.duplicateKeys("""{"a":{"b":1,"b":2},"c":[{"d":1},{"d":2}]}""") shouldBe
      Vector("b")
    AdjudicationSchema.duplicateKeys("""{"k":"a,\"k\":","k2":"}{"}""") shouldBe empty
    AdjudicationSchema.parse(ok1, req).isRight shouldBe true
  }

  test("F1: a duplicate-key output leaves a band pair UNRESOLVED, also when replayed from cache") {
    val dup = ScriptedAdjudicator(r =>
      Right(
        response("REJECT", Seq(r.leftRef, r.rightRef)).dropRight(1) + ",\"outcome\":\"ACCEPT\"}"
      )
    )
    val cache = InMemoryAdjudicationCache()
    val c = ctx(Thresholds(0.999, 0.01, 0.02, 0.998), Some(service(dup, cache)))
    for _ <- 1 to 2 do
      val r = decide(borderlineResearcherPair, c)
      r.decision.decision shouldBe MatchOutcome.UNRESOLVED
      r.decision.reasonCodes shouldBe Vector("LLM_OUTPUT_INVALID")
    dup.calls shouldBe 1 // second decision came from the cache and was re-validated
  }

  // --- F2 / F11: partially overlapping identifier sets -----------------------------------------

  private def sameDoiPair(
      left: Map[ErIdType, Set[String]],
      right: Map[ErIdType, Set[String]]
  ): PairInput =
    val doi = "10.1145/3770743.3803897"
    val authors = Vector("Feng Guo", "Ann Other")
    val w1 = work(
      oaWorkId("W7223249703"),
      SourceType.OPENALEX,
      authors = authors,
      ids = Map(ErIdType.DOI -> Set(doi))
    )
    val w2 = work(doiWorkId(doi), SourceType.CROSSREF, authors = authors, ids = w1.ids)
    pair(mention(w1, 1, "Feng Guo", left), mention(w2, 1, "Feng Guo", right))

  test("F2: ORCID {A,C} vs {B,C} on one slot of one DOI is a CONFLICT, never ACCEPTED") {
    val p = sameDoiPair(
      Map(ErIdType.ORCID -> Set(orcidA, orcidC)),
      Map(ErIdType.ORCID -> Set(orcidB, orcidC))
    )
    val r = decide(p)
    r.decision.decision shouldBe MatchOutcome.CONFLICT
    r.decision.reasonCodes shouldBe Vector("EXACT_ID_CONFLICT")
    r.decision.rulesFired should contain allOf ("EXACT_ID_AGREE:ORCID", "EXACT_ID_DISAGREE:ORCID")
  }

  test("F11: no CONFLICT input is ever ACCEPTED, including overlapping identifier sets") {
    val conflicts = Vector(
      sameDoiPair(Map(ErIdType.ORCID -> Set(orcidA)), Map(ErIdType.ORCID -> Set(orcidB))),
      sameDoiPair(
        Map(ErIdType.ORCID -> Set(orcidA, orcidC)),
        Map(ErIdType.ORCID -> Set(orcidB, orcidC))
      ),
      sameDoiPair(Map(ErIdType.ORCID -> Set(orcidA)), Map(ErIdType.ORCID -> Set(orcidA, orcidC))),
      sameDoiPair(
        Map(ErIdType.OPENALEX_AUTHOR -> Set("A1", "A2")),
        Map(ErIdType.OPENALEX_AUTHOR -> Set("A2", "A3"))
      ),
      sameDoiPair(
        Map(ErIdType.ORCID -> Set(orcidA), ErIdType.OPENALEX_AUTHOR -> Set("A1")),
        Map(ErIdType.ORCID -> Set(orcidA), ErIdType.OPENALEX_AUTHOR -> Set("A2"))
      )
    )
    for
      p <- conflicts
      th <- everyThreshold
    do
      val adj = acceptingAdjudicator
      val r = decide(p, ctx(th, Some(service(adj))))
      withClue(s"${p.left.ids} vs ${p.right.ids} under $th: ") {
        r.decision.decision shouldBe MatchOutcome.CONFLICT
      }
      adj.calls shouldBe 0
  }

  // --- F3: two author slots of one work ---------------------------------------------------------

  private val zhangAuthors = Vector("Wei Zhang", "Li Wang", "Wei Zhang", "Yang Liu")

  test("F3: two 'Wei Zhang' mentions at positions 1 and 3 of one record are REJECTED") {
    val w = work(dblpWorkId("conf/kdd/ZhangWZL20"), authors = zhangAuthors)
    val p = pair(mention(w, 1, "Wei Zhang"), mention(w, 3, "Wei Zhang"))
    val r = decide(p)
    r.decision.decision shouldBe MatchOutcome.REJECTED
    r.decision.reasonCodes shouldBe Vector("SAME_WORK_DISTINCT_AUTHOR_SLOTS")
    r.decision.rulesFired should contain("SAME_WORK_DISTINCT_POSITIONS")
    neverAccepted(p)
  }

  test("F3: same DOI in two sources, slots 1 and 3: UNRESOLVED for review, never ACCEPTED") {
    val doi = "10.1145/3394486.3403100"
    val d = work(
      dblpWorkId("conf/kdd/ZhangWZL20"),
      authors = zhangAuthors,
      ids = Map(ErIdType.DOI -> Set(doi))
    )
    val c = work(doiWorkId(doi), SourceType.CROSSREF, authors = zhangAuthors, ids = d.ids)
    val p = pair(mention(d, 1, "Wei Zhang"), mention(c, 3, "Wei Zhang"))
    val adj = acceptingAdjudicator
    val r = decide(p, ctx(Thresholds(0.0, 0.0, 0.0, 0.0), Some(service(adj))))
    r.decision.decision shouldBe MatchOutcome.UNRESOLVED
    r.decision.reasonCodes shouldBe Vector("SAME_WORK_DIFFERENT_POSITION")
    r.decision.confidence shouldBe 0.0
    adj.calls shouldBe 0
    neverAccepted(p)
  }

  test("F3: bibliographically identical records without identifiers, different slots") {
    val d = work(dblpWorkId("conf/kdd/ZhangWZL20"), authors = zhangAuthors)
    val c = work(doiWorkId("10.1145/3394486.3403100"), SourceType.CROSSREF, authors = zhangAuthors)
    val p = pair(mention(d, 1, "Wei Zhang"), mention(c, 3, "Wei Zhang"))
    decide(p).decision.reasonCodes shouldBe Vector("SAME_WORK_DIFFERENT_POSITION")
    neverAccepted(p)
  }

  test("F3: one ORCID on two different slots of one work is a CONFLICT") {
    val w = work(dblpWorkId("conf/kdd/ZhangWZL20"), authors = zhangAuthors)
    val id = Map(ErIdType.ORCID -> Set(orcidA))
    val r = decide(pair(mention(w, 1, "Wei Zhang", id), mention(w, 3, "Wei Zhang", id)))
    r.decision.decision shouldBe MatchOutcome.CONFLICT
    r.decision.reasonCodes shouldBe Vector("EXACT_ID_SAME_WORK_DISTINCT_POSITIONS")
  }

  test("F3: the same slot in two sources is still ACCEPTED deterministically") {
    val doi = "10.1145/3394486.3403100"
    val d =
      work(dblpWorkId("conf/kdd/X"), authors = zhangAuthors, ids = Map(ErIdType.DOI -> Set(doi)))
    val c = work(doiWorkId(doi), SourceType.CROSSREF, authors = zhangAuthors, ids = d.ids)
    val r = decide(pair(mention(d, 3, "Wei Zhang"), mention(c, 3, "W. Zhang")))
    r.decision.decision shouldBe MatchOutcome.ACCEPTED
    r.decision.reasonCodes shouldBe Vector("SAME_WORK_SAME_POSITION")
  }

  // --- D2: key-colliding coauthors --------------------------------------------------------------

  test("D2: two 'Wei Zhang' whose coauthors only collide on surname+initial are not ACCEPTED") {
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
    val r = decide(
      pair(
        mention(w1, 1, "Wei Zhang", affiliations = aff),
        mention(w2, 1, "Wei Zhang", affiliations = aff)
      )
    )
    r.decision.features("coauthorOverlap") shouldBe 0.0
    r.decision.decision should not be MatchOutcome.ACCEPTED
  }

  // --- F4: generic titles -----------------------------------------------------------------------

  private def genericPair(title: String, ids: Map[ErIdType, Set[String]] = Map.empty) =
    val a = work(
      dblpWorkId("journals/tods/Editorial18a"),
      title = title + ".",
      year = Some(2018),
      authors = Vector("Christian S. Jensen"),
      venues = Vector("ACM Trans. Database Syst."),
      ids = ids
    )
    val b = a.copy(
      ref = dblpWorkId("journals/tods/Editorial18b"),
      title = title,
      ids = if ids.isEmpty then Map.empty else ids
    )
    pair(a, b)

  test("F4: generic titles are never ACCEPTED on title, year, author and venue equality") {
    for t <- Seq("Editorial", "Preface", "Foreword", "Introduction", "Erratum", "Editorial 2018")
    do
      val p = genericPair(t)
      val r = decide(p)
      withClue(t) {
        r.decision.decision shouldBe MatchOutcome.UNRESOLVED
        r.decision.reasonCodes shouldBe Vector("GENERIC_TITLE_NEEDS_IDENTIFIER")
        r.decision.rulesFired should contain("GENERIC_TITLE")
      }
      neverAccepted(p)
  }

  test("F4: a generic-title pair is never sent to the LLM") {
    val adj = acceptingAdjudicator
    decide(genericPair("Editorial"), ctx(Thresholds(1.0, 0.0, 0.0, 1.0), Some(service(adj))))
    adj.calls shouldBe 0
  }

  test("F4: a generic title with a shared DOI is accepted on the identifier") {
    val r = decide(genericPair("Editorial", Map(ErIdType.DOI -> Set("10.1145/3183713.3183714"))))
    r.decision.decision shouldBe MatchOutcome.ACCEPTED
    r.path shouldBe MatchPath.EXACT_ID
  }

  test("F4: the generic-title list is a versioned resource referenced by the policy") {
    parameters.genericTitlesResource shouldBe "scholarchain/er/policy/generic-titles-v1.txt"
    parameters.genericTitles should contain allOf ("editorial", "preface", "foreword", "erratum")
    GenericTitles.isGeneric("Erratum II", parameters.genericTitles) shouldBe true
    GenericTitles.isGeneric("Editorial process mining", parameters.genericTitles) shouldBe false
  }

  // --- F7: gating cutoffs come from the er-v1 properties file: see CutoffsPropertiesSpec ------

  // --- F8 / F11: warm vs cold cache -------------------------------------------------------------

  test("F8: a decision is byte-identical whether the adjudication came from the cache or a call") {
    val band = Thresholds(0.999, 0.01, 0.02, 0.998)
    val adj = acceptingAdjudicator
    val c = ctx(band, Some(service(adj, InMemoryAdjudicationCache())))
    val cold = decide(borderlineResearcherPair, c)
    val warm = decide(borderlineResearcherPair, c)
    cold.adjudication.get.cacheHit shouldBe false
    warm.adjudication.get.cacheHit shouldBe true
    WireCodec.encode(warm.decision) shouldBe WireCodec.encode(cold.decision)
    val fresh = decide(borderlineResearcherPair, ctx(band, Some(service(acceptingAdjudicator))))
    WireCodec.encode(fresh.decision) shouldBe WireCodec.encode(cold.decision)
    cold.decision.rulesFired.filter(_.startsWith("LLM_")) should contain("LLM_ADJUDICATION")
  }

  // --- F10: weak identifiers --------------------------------------------------------------------

  test("F10: two mentions sharing only an unsuffixed dblp key are not an exact-ID merge") {
    val w1 = work(
      dblpWorkId("conf/a/Z1"),
      title = "Approximate query answering",
      year = Some(2011),
      authors = Vector("Wei Zhang"),
      venues = Vector("VLDB")
    )
    val w2 = work(
      dblpWorkId("conf/b/Z2"),
      title = "Wireless sensor routing",
      year = Some(2019),
      authors = Vector("Wei Zhang"),
      venues = Vector("INFOCOM")
    )
    val key = Map(ErIdType.DBLP_AUTHOR_KEY -> Set("Wei Zhang"))
    val r = decide(pair(mention(w1, 1, "Wei Zhang", key), mention(w2, 1, "Wei Zhang", key)))
    r.decision.rulesFired should not contain "EXACT_ID_AGREE:DBLP_AUTHOR_KEY"
    r.path should not be MatchPath.EXACT_ID
    r.decision.decision should not be MatchOutcome.ACCEPTED
    val suffixed = Map(ErIdType.DBLP_AUTHOR_KEY -> Set("Wei Zhang 0001"))
    decide(
      pair(mention(w1, 1, "Wei Zhang", suffixed), mention(w2, 1, "Wei Zhang", suffixed))
    ).decision.rulesFired should contain("EXACT_ID_AGREE:DBLP_AUTHOR_KEY")
  }

  test("F10: two different works linking one repository are not merged on the repository") {
    val repo = Map(ErIdType.REPOSITORY_URL -> Set("https://github.com/lab/toolkit"))
    val a = work(dblpWorkId("conf/a/P1"), title = "A toolkit for graph mining", ids = repo)
    val b = work(
      dblpWorkId("journals/b/P2"),
      title = "Benchmarking streaming joins",
      year = Some(2012),
      authors = Vector("Ann Lee"),
      ids = repo
    )
    val r = decide(pair(a, b))
    r.path should not be MatchPath.EXACT_ID
    r.decision.decision should not be MatchOutcome.ACCEPTED
  }

  // --- F13: prompt text in the cache key --------------------------------------------------------

  test("F13: an edited prompt with an unchanged version string does not reuse cached answers") {
    val cache = InMemoryAdjudicationCache()
    val adj = acceptingAdjudicator
    val edited = prompt.copy(text = prompt.text + "\nAn extra rule.")
    edited.version shouldBe prompt.version
    val band = Thresholds(0.999, 0.01, 0.02, 0.998)
    decide(
      borderlineResearcherPair,
      ctx(band, Some(AdjudicationService(adj, cache, prompt, "er-v1")))
    )
    val second = decide(
      borderlineResearcherPair,
      ctx(band, Some(AdjudicationService(adj, cache, edited, "er-v1")))
    )
    adj.calls shouldBe 2
    second.adjudication.get.cacheHit shouldBe false
    cache.all.map(_.promptSha256).toSet shouldBe Set(prompt.sha256, edited.sha256)
  }

  // --- Versioning -------------------------------------------------------------------------------

  test("the policy revision and feature version are bumped and recorded on every decision") {
    parameters.policyVersion shouldBe "er-v1"
    parameters.policyRevision shouldBe Policy.ImplementedRevision
    parameters.policyRevision shouldBe "er-v1-r2"
    parameters.featureVersion shouldBe "er-features-v2"
    val d = decide(borderlineResearcherPair).decision
    d.rulesFired should contain("POLICY_REVISION:er-v1-r2")
    d.rulesFired should contain("FEATURE_VERSION:er-features-v2")
  }
