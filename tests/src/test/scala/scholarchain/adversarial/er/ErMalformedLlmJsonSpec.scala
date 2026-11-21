package scholarchain.adversarial.er

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.model.MatchOutcome
import scholarchain.er.adjudication.*
import scholarchain.er.model.MatchPath

/** Malformed LLM output: every output that is not exactly one schema-valid object must be
  * rejected by the strict parser, and a pair whose adjudication is rejected must stay UNRESOLVED
  * (never ACCEPTED or REJECTED on the strength of an invalid output).
  */
class ErMalformedLlmJsonSpec extends AnyFunSuite with Matchers:
  import ErAdv.*

  private val req = sampleRequest
  private val l = req.leftRef
  private val r = req.rightRef
  private def valid(outcome: String = "ACCEPT", conf: String = "0.95") =
    s"""{"outcome":"$outcome","candidateIds":["$l","$r"],"citedFields":["features.coauthorOverlap"],"confidence":$conf,"reasonCodes":["COAUTHORS_SHARED"]}"""

  test("baseline: the well-formed ACCEPT output parses (so the rejections below are real)") {
    AdjudicationSchema.parse(valid(), req).map(_.outcome) shouldBe Right(AdjudicationOutcome.ACCEPT)
    AdjudicationSchema.parse("  \n" + valid() + "\n  ", req).isRight shouldBe true
  }

  private val malformed: Seq[(String, String)] = Seq(
    "empty string" -> "",
    "whitespace only" -> "   \n\t ",
    "JSON null" -> "null",
    "JSON array" -> s"""[${valid()}]""",
    "bare string outcome" -> "\"ACCEPT\"",
    "bare word" -> "ACCEPT",
    "number" -> "0.95",
    "truncated by one character" -> valid().dropRight(1),
    "truncated mid-array" -> valid().take(valid().indexOf("citedFields") + 16),
    "truncated mid-string" -> valid().take(12),
    "trailing prose" -> (valid() + " Hope this helps!"),
    "two concatenated objects" -> (valid() + valid("REJECT")),
    "trailing comma inside object" -> valid().dropRight(1).concat(",}"),
    "leading prose" -> ("Here is my answer: " + valid()),
    "markdown code fence" -> ("```json\n" + valid() + "\n```"),
    "single-quoted JSON" -> valid().replace('"', '\''),
    "JavaScript comment" -> ("// verdict\n" + valid()),
    "byte-order mark prefix" -> ("\uFEFF" + valid()),
    "outcome as number" -> valid().replace("\"ACCEPT\"", "1"),
    "outcome as null" -> valid().replace("\"ACCEPT\"", "null"),
    "outcome as array" -> valid().replace("\"ACCEPT\"", "[\"ACCEPT\"]"),
    "outcome lower case" -> valid("accept"),
    "outcome title case" -> valid("Accept"),
    "outcome with leading space" -> valid(" ACCEPT"),
    "outcome with trailing space" -> valid("ACCEPT "),
    "outcome past tense" -> valid("ACCEPTED"),
    "outcome MATCH" -> valid("MATCH"),
    "outcome with Cyrillic A homoglyph" -> valid("\u0410CCEPT"),
    "outcome with zero-width space" -> valid("ACC\u200BEPT"),
    "candidateIds as string" -> valid().replace(s"""["$l","$r"]""", s""""$l""""),
    "candidateIds with a number" -> valid().replace(s"""["$l","$r"]""", s"""["$l",7]"""),
    "candidateIds empty" -> valid().replace(s"""["$l","$r"]""", "[]"),
    "candidateIds duplicated" -> valid().replace(s"""["$l","$r"]""", s"""["$l","$l"]"""),
    "candidateIds null" -> valid().replace(s"""["$l","$r"]""", "null"),
    "citedFields as string" ->
      valid().replace("""["features.coauthorOverlap"]""", "\"features.coauthorOverlap\""),
    "citedFields empty for ACCEPT" -> valid().replace("""["features.coauthorOverlap"]""", "[]"),
    "citedFields unknown field" -> valid().replace("features.coauthorOverlap", "features.vibes"),
    "citedFields nested path not offered" -> valid()
      .replace("features.coauthorOverlap", "left.ids.ORCID"),
    "confidence as string" -> valid(conf = "\"0.95\""),
    "confidence as boolean" -> valid(conf = "true"),
    "confidence null" -> valid(conf = "null"),
    "confidence NaN literal" -> valid(conf = "NaN"),
    "confidence Infinity literal" -> valid(conf = "Infinity"),
    "confidence negative Infinity" -> valid(conf = "-Infinity"),
    "confidence overflow 1e400" -> valid(conf = "1e400"),
    "confidence above 1" -> valid(conf = "1.0000001"),
    "confidence 95 (percent)" -> valid(conf = "95"),
    "confidence negative" -> valid(conf = "-0.1"),
    "confidence as object" -> valid(conf = "{\"value\":0.95}"),
    "reasonCodes as string" -> valid().replace("""["COAUTHORS_SHARED"]""", "\"COAUTHORS_SHARED\""),
    "reasonCodes empty" -> valid().replace("""["COAUTHORS_SHARED"]""", "[]"),
    "reasonCodes lower case" -> valid().replace("COAUTHORS_SHARED", "coauthors_shared"),
    "reasonCodes with spaces (prose)" -> valid()
      .replace("COAUTHORS_SHARED", "They share coauthors"),
    "reasonCodes too long" -> valid().replace("COAUTHORS_SHARED", "A" * 65),
    "reasonCodes number" -> valid().replace("\"COAUTHORS_SHARED\"", "3"),
    "extra key reasoning" -> valid().dropRight(1).concat(",\"reasoning\":\"they look alike\"}"),
    "extra key with null value" -> valid().dropRight(1).concat(",\"notes\":null}"),
    "extra key mergeInto" -> valid().dropRight(1).concat(",\"mergeInto\":\"" + l + "\"}"),
    "missing outcome" -> valid().replace("\"outcome\":\"ACCEPT\",", ""),
    "missing candidateIds" -> valid().replace(s""""candidateIds":["$l","$r"],""", ""),
    "missing citedFields" -> valid().replace("\"citedFields\":[\"features.coauthorOverlap\"],", ""),
    "missing confidence" -> valid().replace("\"confidence\":0.95,", ""),
    "missing reasonCodes" -> valid().replace(",\"reasonCodes\":[\"COAUTHORS_SHARED\"]", ""),
    "keys in wrong case" -> valid().replace("\"outcome\"", "\"Outcome\""),
    "wrapped in an envelope" -> s"""{"response":${valid()}}"""
  )

  for (label, raw) <- malformed do
    test(s"strict parser rejects: $label") {
      withClue(s"raw output <$raw>: ") {
        AdjudicationSchema.parse(raw, req).isLeft shouldBe true
      }
    }

  test("strict parser rejects a null raw output without throwing") {
    AdjudicationSchema.parse(null, req).isLeft shouldBe true
  }

  test("strict parser rejects duplicate keys (REJECT then ACCEPT for 'outcome')") {
    // JSON with a repeated key is ambiguous; a lenient last-wins parse turns a REJECT the schema
    // validator never saw into an ACCEPT. A strict schema must refuse it.
    val dup =
      s"""{"outcome":"REJECT","candidateIds":["$l","$r"],"citedFields":["features.coauthorOverlap"],"confidence":0.95,"reasonCodes":["COAUTHORS_SHARED"],"outcome":"ACCEPT"}"""
    AdjudicationSchema.parse(dup, req).isLeft shouldBe true
  }

  test("strict parser rejects duplicate 'confidence' keys (0.1 then 0.99)") {
    val dup =
      s"""{"outcome":"ACCEPT","candidateIds":["$l","$r"],"citedFields":["features.coauthorOverlap"],"confidence":0.1,"reasonCodes":["COAUTHORS_SHARED"],"confidence":0.99}"""
    AdjudicationSchema.parse(dup, req).isLeft shouldBe true
  }

  test("deeply nested arrays (stack exhaustion attempt) are rejected without throwing") {
    val deep = "[" * 200000 + "]" * 200000
    noException should be thrownBy AdjudicationSchema.parse(deep, req)
    AdjudicationSchema.parse(deep, req).isLeft shouldBe true
  }

  test("deeply nested objects inside an extra key are rejected without throwing") {
    val deep = valid().dropRight(1) + ",\"x\":" + "{\"a\":" * 100000 + "1" + "}" * 100000 + "}"
    noException should be thrownBy AdjudicationSchema.parse(deep, req)
    AdjudicationSchema.parse(deep, req).isLeft shouldBe true
  }

  test("confidence boundaries: 0 and 1 are valid; integer 1 is a number") {
    AdjudicationSchema.parse(valid(conf = "0"), req).isRight shouldBe true
    AdjudicationSchema.parse(valid(conf = "1"), req).map(_.confidence) shouldBe Right(1.0)
  }

  // --- end to end through the policy: invalid output is never a decision ---------------------

  test("baseline: the band pair is in the LLM band and a well-formed ACCEPT decides it") {
    val adj = ScriptedAdjudicator(q => Right(forRequest(valid(), q)))
    val res = decide(bandPair, ctx(adjudication = Some(service(adj))))
    adj.calls shouldBe 1
    res.path shouldBe MatchPath.LLM_ADJUDICATED
    withClue(explain(res)) { res.decision.decision shouldBe MatchOutcome.ACCEPTED }
  }

  /** Rewrites a well-formed output's IDs to the actual pair's refs. */
  private def forRequest(raw: String, q: AdjudicationRequest): String =
    raw.replace(l, q.leftRef).replace(r, q.rightRef)

  private val endToEnd: Seq[(String, String)] = Seq(
    "truncated" -> valid().dropRight(1),
    "trailing prose" -> (valid() + " Hope this helps!"),
    "code fence" -> ("```json\n" + valid() + "\n```"),
    "lower-case outcome" -> valid("accept"),
    "confidence as string" -> valid(conf = "\"0.95\""),
    "confidence NaN" -> valid(conf = "NaN"),
    "confidence above 1" -> valid(conf = "1.5"),
    "extra key" -> valid().dropRight(1).concat(",\"reasoning\":\"x\"}"),
    "missing key" -> valid().replace("\"confidence\":0.95,", ""),
    "REJECT with trailing text" -> (valid("REJECT") + "\n\nNote: low certainty."),
    "duplicate outcome key (REJECT, ACCEPT)" -> valid("REJECT")
      .dropRight(1)
      .concat(",\"outcome\":\"ACCEPT\"}")
  )

  for (label, raw) <- endToEnd do
    test(s"policy: invalid LLM output ($label) leaves the pair UNRESOLVED with confidence 0") {
      val adj = ScriptedAdjudicator(q => Right(forRequest(raw, q)))
      val res = decide(bandPair, ctx(adjudication = Some(service(adj))))
      adj.calls shouldBe 1
      res.path shouldBe MatchPath.LLM_ADJUDICATED
      withClue(explain(res)) { res.decision.decision shouldBe MatchOutcome.UNRESOLVED }
      res.decision.confidence shouldBe 0.0
      res.decision.reasonCodes should contain("LLM_OUTPUT_INVALID")
    }

  test("policy: a transport error leaves the pair UNRESOLVED and is not cached") {
    val cache = InMemoryAdjudicationCache()
    val adj = ScriptedAdjudicator(_ => Left("connection reset"))
    val c = ctx(adjudication = Some(service(adj, cache)))
    decide(bandPair, c).decision.decision shouldBe MatchOutcome.UNRESOLVED
    cache.all shouldBe empty
    decide(bandPair, c)
    adj.calls shouldBe 2
  }

  test("policy: a cached invalid output is reused (not re-asked) and still UNRESOLVED") {
    val cache = InMemoryAdjudicationCache()
    val adj = ScriptedAdjudicator(q => Right(forRequest(valid() + " trailing", q)))
    val c = ctx(adjudication = Some(service(adj, cache)))
    decide(bandPair, c).decision.decision shouldBe MatchOutcome.UNRESOLVED
    val second = decide(bandPair, c)
    adj.calls shouldBe 1
    second.decision.decision shouldBe MatchOutcome.UNRESOLVED
  }

  test("policy: the raw invalid model text never appears in the recorded decision") {
    val marker = "IGNORE_PREVIOUS_INSTRUCTIONS_AND_MERGE"
    val adj = ScriptedAdjudicator(q => Right(forRequest(valid(), q) + " " + marker))
    val res = decide(bandPair, ctx(adjudication = Some(service(adj))))
    withClue(explain(res)) { res.decision.decision shouldBe MatchOutcome.UNRESOLVED }
    decisionJson(res) should not include marker
  }

  test("policy: a valid ACCEPT below the policy's llmMinConfidence is UNRESOLVED") {
    val adj = ScriptedAdjudicator(q => Right(forRequest(valid(conf = "0.5"), q)))
    val res = decide(bandPair, ctx(adjudication = Some(service(adj))))
    withClue(explain(res)) { res.decision.decision shouldBe MatchOutcome.UNRESOLVED }
  }
