package scholarchain.adversarial.er

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.model.MatchOutcome
import scholarchain.er.adjudication.*
import scholarchain.er.model.MatchPath
import scholarchain.er.policy.AdjudicationRequests

/** LLM output naming a fabricated or partially fabricated identifier. "No LLM call may alter
  * source records or create an identifier not present in the candidates" (spec, Phase 5).
  */
class ErFabricatedIdSpec extends AnyFunSuite with Matchers:
  import ErAdv.*

  private val fake = "mention:sha256:" + "f" * 64

  private def out(ids: Seq[String], outcome: String = "ACCEPT") =
    response(outcome, ids, cited = Seq("features.coauthorOverlap")).render()

  private def parse(
      ids: Seq[String],
      outcome: String = "ACCEPT",
      q: AdjudicationRequest = sampleRequest
  ) =
    AdjudicationSchema.parse(out(ids, outcome), q)

  test("baseline: naming the request's own refs and candidate ID is accepted") {
    val q = sampleRequest
    parse(Seq(q.leftRef, q.rightRef)).isRight shouldBe true
    parse(Seq(q.candidateId)).isRight shouldBe true
  }

  test("a wholly fabricated ID is rejected with FABRICATED_ID") {
    val res = parse(Seq(fake))
    res.isLeft shouldBe true
    res.left.toOption.get.exists(_.startsWith(AdjudicationSchema.FabricatedIdError)) shouldBe true
  }

  test("a partially fabricated list (one real ref plus one invented) is rejected") {
    parse(Seq(sampleRequest.leftRef, fake)).isLeft shouldBe true
  }

  test("a fabricated ID is rejected even on ABSTAIN and REJECT outcomes") {
    parse(Seq(fake), "ABSTAIN").isLeft shouldBe true
    parse(Seq(sampleRequest.leftRef, fake), "REJECT").isLeft shouldBe true
  }

  private def nearMisses(ref: String): Seq[(String, String)] = Seq(
    "upper-cased" -> ref.toUpperCase(java.util.Locale.ROOT),
    "trailing space" -> (ref + " "),
    "leading space" -> (" " + ref),
    "zero-width space inside" -> (ref.take(10) + "\u200B" + ref.drop(10)),
    "last hex digit changed" -> (ref.dropRight(1) + (if ref.last == '0' then "1" else "0")),
    "truncated" -> ref.dropRight(1),
    "extended" -> (ref + "0"),
    "Cyrillic homoglyph" -> ref.replaceFirst("a", "\u0430"),
    "prefix only" -> ref.takeWhile(_ != ':'),
    "hash without kind prefix" -> ref.dropWhile(_ != ':').drop(1),
    "full-width colon" -> ref.replaceFirst(":", "\uFF1A")
  )

  for (label, idx) <- nearMisses("mention:sha256:" + "a" * 64).map(_._1).zipWithIndex do
    test(s"a near-miss of a real ref ($label) is treated as fabricated") {
      val q = sampleRequest
      val miss = nearMisses(q.leftRef)(idx)._2
      miss should not be q.leftRef
      parse(Seq(miss, q.rightRef)).isLeft shouldBe true
    }

  test("an ID from a different, real pair is fabricated for this request") {
    val other = AdjudicationRequests.build(bandPair, Map("coauthorOverlap" -> 0.3), 0.7)
    other.leftRef should not be sampleRequest.leftRef
    parse(Seq(other.leftRef, sampleRequest.rightRef)).isLeft shouldBe true
  }

  test("an ORCID, DOI or source record key from the compared fields is not a candidate ID") {
    for id <- Seq(orcidA, "10.14778/3236187.3236190", "conf/vldb/Zhang18", "rec") do
      withClue(s"id $id: ") { parse(Seq(id)).isLeft shouldBe true }
  }

  test("an empty-string ID is never allowed, even when a side has no ref") {
    val q = sampleRequest
    val noRef = q.copy(left = ujson.Obj("name" -> "Wei Zhang"))
    noRef.allowedIds should not contain ""
    AdjudicationSchema.parse(out(Seq("")), noRef).isLeft shouldBe true
    AdjudicationSchema.parse(out(Seq("", q.rightRef)), noRef).isLeft shouldBe true
  }

  test("a fabricated ID inside citedFields (left.<invented>) is rejected") {
    val q = sampleRequest
    val raw =
      response("ACCEPT", Seq(q.leftRef), cited = Seq("left.orcid_" + fake)).render()
    AdjudicationSchema.parse(raw, q).isLeft shouldBe true
  }

  // --- end to end ---------------------------------------------------------------------------

  private def run(adj: ScriptedAdjudicator) =
    decide(bandPair, ctx(adjudication = Some(service(adj))))

  test("policy: a fabricated ACCEPT leaves the pair UNRESOLVED with reason LLM_FABRICATED_ID") {
    val adj = ScriptedAdjudicator(_ => Right(out(Seq(fake))))
    val res = run(adj)
    adj.calls shouldBe 1
    res.path shouldBe MatchPath.LLM_ADJUDICATED
    withClue(explain(res)) { res.decision.decision shouldBe MatchOutcome.UNRESOLVED }
    res.decision.confidence shouldBe 0.0
    res.decision.reasonCodes shouldBe Vector("LLM_FABRICATED_ID")
  }

  test("policy: a partially fabricated ACCEPT leaves the pair UNRESOLVED") {
    val adj = ScriptedAdjudicator(q => Right(out(Seq(q.leftRef, q.rightRef, fake))))
    run(adj).decision.decision shouldBe MatchOutcome.UNRESOLVED
  }

  test("policy: a fabricated REJECT does not reject the pair either") {
    val adj = ScriptedAdjudicator(q => Right(out(Seq(q.leftRef, fake), "REJECT")))
    run(adj).decision.decision shouldBe MatchOutcome.UNRESOLVED
  }

  test("policy: a fabricated ID never enters the decision record or its provenance") {
    val adj = ScriptedAdjudicator(q => Right(out(Seq(q.leftRef, fake))))
    val res = run(adj)
    val p = bandPair
    decisionJson(res) should not include fake
    Set(res.decision.sourceId, res.decision.targetId) shouldBe Set(p.left.ref, p.right.ref)
    res.decision.provenance.derivedFrom.toSet shouldBe Set(p.left.ref, p.right.ref)
  }

  test("policy: the LLM cannot swap the decided pair by naming the candidate ID only") {
    val adj = ScriptedAdjudicator(q => Right(out(Seq(q.candidateId))))
    val res = run(adj)
    val p = bandPair
    res.decision.sourceId shouldBe p.left.ref
    res.decision.targetId shouldBe p.right.ref
  }

  test("policy: the request handed to the model offers only the three allowed IDs") {
    val adj = ScriptedAdjudicator(_ => Right(out(Seq(fake))))
    run(adj)
    val q = adj.lastRequest.get
    val p = bandPair
    q.allowedIds shouldBe Set(p.candidateId, p.left.ref, p.right.ref)
    q.toJson("allowedCandidateIds").arr.map(_.str).toSet shouldBe q.allowedIds
  }
