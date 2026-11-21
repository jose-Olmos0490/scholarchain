package scholarchain.er

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.model.MatchOutcome
import scholarchain.er.TestData.*
import scholarchain.er.adjudication.*
import scholarchain.er.io.JsonlAdjudicationCache
import scholarchain.er.model.*
import scholarchain.er.policy.*

class AdjudicationSpec extends AnyFunSuite with Matchers:

  /** A context whose band contains the borderline fixture's score. */
  private def bandCtx(a: Adjudicator, cache: AdjudicationCache = InMemoryAdjudicationCache()) =
    ctx(Thresholds(0.999, 0.01, 0.02, 0.998), Some(service(a, cache)))

  private def request: AdjudicationRequest =
    val p = borderlineResearcherPair
    AdjudicationRequests.build(p, Map("nameSimilarity" -> 1.0, "coauthorOverlap" -> 0.0), 0.7)

  // --- Abstention ----------------------------------------------------------------------------

  test("LLM ABSTAIN leaves the pair UNRESOLVED and records model and prompt versions") {
    val adj = ScriptedAdjudicator(r =>
      Right(
        response(
          "ABSTAIN",
          Seq(r.candidateId),
          cited = Seq.empty,
          confidence = 0.0,
          reasons = Seq("INSUFFICIENT_EVIDENCE")
        )
      )
    )
    val r = ok(MatchPolicy.decide(borderlineResearcherPair, bandCtx(adj)))
    r.decision.decision shouldBe MatchOutcome.UNRESOLVED
    r.decision.reasonCodes shouldBe Vector("LLM_ABSTAINED", "LLM_INSUFFICIENT_EVIDENCE")
    r.decision.confidence shouldBe 0.0
    r.path shouldBe MatchPath.LLM_ADJUDICATED
    r.decision.modelVersion shouldBe Some("scripted-test-model-v1")
    r.decision.promptVersion shouldBe Some(Prompt.AdjudicationVersion)
  }

  test("LLM ACCEPT below the policy's minimum confidence is treated as an abstention") {
    val adj = ScriptedAdjudicator(r => Right(response("ACCEPT", Seq(r.leftRef), confidence = 0.5)))
    val r = ok(MatchPolicy.decide(borderlineResearcherPair, bandCtx(adj)))
    r.decision.decision shouldBe MatchOutcome.UNRESOLVED
    r.decision.reasonCodes.head shouldBe "LLM_LOW_CONFIDENCE"
  }

  test("LLM REJECT with enough confidence rejects") {
    val adj = ScriptedAdjudicator(r => Right(response("REJECT", Seq(r.leftRef, r.rightRef))))
    ok(MatchPolicy.decide(borderlineResearcherPair, bandCtx(adj))).decision.decision shouldBe
      MatchOutcome.REJECTED
  }

  test("the fake adjudicator abstains when evidence is insufficient") {
    val raw = ok(FakeAdjudicator().adjudicate(prompt, request))
    val parsed = AdjudicationSchema.parse(raw, request)
    parsed.map(_.outcome) shouldBe Right(AdjudicationOutcome.ABSTAIN)
  }

  test("the fake adjudicator is deterministic") {
    FakeAdjudicator()
      .adjudicate(prompt, request) shouldBe FakeAdjudicator().adjudicate(prompt, request)
  }

  // --- Fabricated identifiers ----------------------------------------------------------------

  test("an output naming an ID absent from the candidates is rejected by the schema") {
    val invented = "researcher:orcid:" + "a" * 64
    val raw = response("ACCEPT", Seq(request.leftRef, invented))
    val parsed = AdjudicationSchema.parse(raw, request)
    parsed.isLeft shouldBe true
    parsed.left.toOption.get.head should startWith(AdjudicationSchema.FabricatedIdError)
  }

  test("a fabricated ID leaves the pair UNRESOLVED with LLM_FABRICATED_ID, never ACCEPTED") {
    val adj = ScriptedAdjudicator(r =>
      Right(response("ACCEPT", Seq(r.leftRef, "doi:10.9999/made-up"), confidence = 1.0))
    )
    val r = ok(MatchPolicy.decide(borderlineResearcherPair, bandCtx(adj)))
    r.decision.decision shouldBe MatchOutcome.UNRESOLVED
    r.decision.reasonCodes shouldBe Vector("LLM_FABRICATED_ID")
    r.decision.rulesFired should contain("LLM_OUTPUT_ERROR:FABRICATED_ID")
  }

  // --- Strict schema -------------------------------------------------------------------------

  test("strict schema: the valid form parses") {
    val raw = response("ACCEPT", Seq(request.candidateId), cited = Seq("left.name", "score"))
    AdjudicationSchema.parse(raw, request).map(_.outcome) shouldBe Right(AdjudicationOutcome.ACCEPT)
  }

  test("strict schema: extra keys are rejected (an output cannot carry record edits)") {
    val raw = ujson.read(response("ACCEPT", Seq(request.leftRef))).obj
    raw("correctedName") = "Jaskirat S. Singh"
    AdjudicationSchema.parse(raw.render(), request) shouldBe
      Left(Vector("UNKNOWN_KEY: correctedName"))
  }

  test("strict schema: missing keys, bad outcome, bad confidence, bad reason codes") {
    val base = ujson.read(response("ACCEPT", Seq(request.leftRef))).obj
    def without(k: String) =
      val c = ujson.copy(base)
      c.obj.remove(k)
      c.render()
    def withValue(k: String, v: ujson.Value) =
      val c = ujson.copy(base)
      c.obj(k) = v
      c.render()
    AdjudicationSchema.parse(without("confidence"), request) shouldBe
      Left(Vector("MISSING_KEY: confidence"))
    AdjudicationSchema.parse(withValue("outcome", "MAYBE"), request).isLeft shouldBe true
    AdjudicationSchema.parse(withValue("confidence", 1.5), request) shouldBe
      Left(Vector("INVALID_CONFIDENCE"))
    AdjudicationSchema.parse(withValue("confidence", "0.9"), request).isLeft shouldBe true
    AdjudicationSchema
      .parse(withValue("reasonCodes", ujson.Arr("lower case")), request)
      .isLeft shouldBe
      true
    AdjudicationSchema.parse(withValue("reasonCodes", ujson.Arr()), request).isLeft shouldBe true
    AdjudicationSchema.parse(withValue("candidateIds", ujson.Arr()), request).isLeft shouldBe true
  }

  test("strict schema: cited fields must exist in the request; ACCEPT needs at least one") {
    AdjudicationSchema
      .parse(response("ACCEPT", Seq(request.leftRef), cited = Seq("left.email")), request)
      .isLeft shouldBe true
    AdjudicationSchema
      .parse(response("REJECT", Seq(request.leftRef), cited = Seq.empty), request)
      .isLeft shouldBe true
  }

  test("strict schema: prose, code fences and non-objects are rejected") {
    val good = response("ACCEPT", Seq(request.leftRef))
    AdjudicationSchema.parse("Sure! " + good, request).isLeft shouldBe true
    AdjudicationSchema.parse("```json\n" + good + "\n```", request).isLeft shouldBe true
    AdjudicationSchema.parse("[]", request) shouldBe Left(Vector("NOT_AN_OBJECT"))
    AdjudicationSchema.parse(null, request).isLeft shouldBe true
    AdjudicationSchema.parse("  " + good + "\n", request).isRight shouldBe true
  }

  test("an invalid model output leaves the pair UNRESOLVED with LLM_OUTPUT_INVALID") {
    val adj = ScriptedAdjudicator(_ => Right("I think they match."))
    val r = ok(MatchPolicy.decide(borderlineResearcherPair, bandCtx(adj)))
    r.decision.decision shouldBe MatchOutcome.UNRESOLVED
    r.decision.reasonCodes shouldBe Vector("LLM_OUTPUT_INVALID")
  }

  test("a transport error leaves the pair UNRESOLVED and is not cached") {
    val cache = InMemoryAdjudicationCache()
    val adj = ScriptedAdjudicator(_ => Left("timeout"))
    val r = ok(MatchPolicy.decide(borderlineResearcherPair, bandCtx(adj, cache)))
    r.decision.reasonCodes shouldBe Vector("LLM_UNAVAILABLE")
    cache.all shouldBe empty
  }

  test("the JSON Schema resource matches the parser (keys, required, outcomes, no extras)") {
    val in = getClass.getClassLoader.getResourceAsStream(AdjudicationSchema.SchemaResource)
    in should not be null
    val schema = ujson.read(new String(in.readAllBytes(), StandardCharsets.UTF_8))
    schema("additionalProperties").bool shouldBe false
    schema("required").arr.map(_.str).toSet shouldBe AdjudicationSchema.Keys
    schema("properties").obj.keySet shouldBe AdjudicationSchema.Keys
    schema("properties")("outcome")("enum").arr.map(_.str).toSet shouldBe
      AdjudicationOutcome.values.map(_.toString).toSet
  }

  test("the prompt resource carries its version string and states the no-invention rule") {
    val p = prompt
    p.version shouldBe "er-adj-prompt-v1"
    p.text should include("allowedCandidateIds")
    p.text should include("ABSTAIN")
    p.text should include("untrusted data")
  }

  // --- Source records are never modified -----------------------------------------------------

  test("adjudication never alters the compared records") {
    val p = borderlineResearcherPair
    val before = (p.left, p.right, p.hadoopFeatures)
    val adj = ScriptedAdjudicator(r => Right(response("ACCEPT", Seq(r.leftRef, r.rightRef))))
    val r = ok(MatchPolicy.decide(p, bandCtx(adj)))
    (p.left, p.right, p.hadoopFeatures) shouldBe before
    r.decision.sourceId shouldBe p.left.ref
    r.decision.targetId shouldBe p.right.ref
  }

  test("the request holds only data copied from the views, with the allowed IDs listed") {
    val req = request
    req.allowedIds shouldBe Set(req.candidateId, req.leftRef, req.rightRef)
    val json = ujson.read(req.canonicalJson)
    json("allowedCandidateIds").arr.map(_.str).toSet shouldBe req.allowedIds
    val p = borderlineResearcherPair
    json("left")("name").str shouldBe p.left.asInstanceOf[ResearcherView].name
    json("right")("ref").str shouldBe p.right.ref
  }

  // --- Cache ---------------------------------------------------------------------------------

  test("cache key changes with each of input hash, prompt version, prompt text, model, policy") {
    val k = AdjudicationKey("sha256:a", "p1", "sha256:t1", "m1", "er-v1")
    val keys = Set(
      k.cacheKey,
      k.copy(inputHash = "sha256:b").cacheKey,
      k.copy(promptVersion = "p2").cacheKey,
      k.copy(promptSha256 = "sha256:t2").cacheKey,
      k.copy(modelVersion = "m2").cacheKey,
      k.copy(policyVersion = "er-v2").cacheKey
    )
    keys.size shouldBe 6
    AdjudicationKey("sha256:a", "p1", "sha256:t1", "m1", "er-v1").cacheKey shouldBe k.cacheKey
  }

  test("cache key fields cannot run into each other") {
    AdjudicationKey("ab", "c", "t", "m", "v").cacheKey should not be
      AdjudicationKey("a", "bc", "t", "m", "v").cacheKey
  }

  test("input hash is independent of map insertion order and changes with any input field") {
    val a = request
    val b = a.copy(features = a.features.toSeq.reverse.toMap)
    a.inputHash shouldBe b.inputHash
    a.copy(score = 0.71).inputHash should not be a.inputHash
  }

  test("a repeated question is answered from the cache: the model is called once") {
    val cache = InMemoryAdjudicationCache()
    val adj = ScriptedAdjudicator(r => Right(response("ACCEPT", Seq(r.leftRef, r.rightRef))))
    val c = bandCtx(adj, cache)
    val first = ok(MatchPolicy.decide(borderlineResearcherPair, c))
    val second = ok(MatchPolicy.decide(borderlineResearcherPair, c))
    adj.calls shouldBe 1
    first.adjudication.get.cacheHit shouldBe false
    second.adjudication.get.cacheHit shouldBe true
    second.decision.rulesFired should contain("LLM_ADJUDICATION")
    second.decision.decision shouldBe first.decision.decision
    cache.all.size shouldBe 1
    cache.all.head.promptVersion shouldBe Prompt.AdjudicationVersion
    cache.all.head.policyVersion shouldBe "er-v1"
  }

  test("a different model version does not reuse another model's cached answer") {
    val cache = InMemoryAdjudicationCache()
    val a1 = ScriptedAdjudicator(r => Right(response("ACCEPT", Seq(r.leftRef))))
    val a2 = ScriptedAdjudicator(
      r => Right(response("REJECT", Seq(r.leftRef))),
      modelVersion = "other-model-v2"
    )
    ok(MatchPolicy.decide(borderlineResearcherPair, bandCtx(a1, cache)))
    val r2 = ok(MatchPolicy.decide(borderlineResearcherPair, bandCtx(a2, cache)))
    a2.calls shouldBe 1
    r2.decision.decision shouldBe MatchOutcome.REJECTED
    cache.all.size shouldBe 2
  }

  test("the JSONL cache persists entries and reloads them deterministically") {
    val dir = Files.createTempDirectory("er-cache")
    val path = dir.resolve("cache.jsonl")
    val c1 = JsonlAdjudicationCache(path)
    val adj = ScriptedAdjudicator(r => Right(response("ACCEPT", Seq(r.leftRef))))
    ok(MatchPolicy.decide(borderlineResearcherPair, bandCtx(adj, c1)))
    c1.flush()
    val bytes = Files.readAllBytes(path)
    val c2 = JsonlAdjudicationCache(path)
    c2.size shouldBe 1
    val adj2 = ScriptedAdjudicator(_ => Left("must not be called"))
    ok(
      MatchPolicy.decide(borderlineResearcherPair, bandCtx(adj2, c2))
    ).adjudication.get.cacheHit shouldBe
      true
    adj2.calls shouldBe 0
    c2.flush()
    Files.readAllBytes(path) shouldBe bytes
  }
