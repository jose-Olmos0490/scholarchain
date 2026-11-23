package scholarchain.core

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.TestSupport.*
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.core.validation.RecordContract.given
import scholarchain.core.validation.ValidationError
import upickle.default.write

/** Phase 9 repair: the QueryAuditRecord wire contract. The golden byte lock is in
  * GoldenFreezeSpec; these tests pin the field set explicitly and the validation rules.
  */
class QueryAuditRecordSpec extends AnyFunSuite with Matchers:

  private val good = Fixtures.queryAudit

  private val frozenFields = Vector(
    "schemaVersion",
    "queryId",
    "runId",
    "planJson",
    "researcherIds",
    "evidenceIds",
    "metricVersions",
    "promptVersions",
    "modelVersions",
    "inputTokens",
    "outputTokens",
    "latencyMillis",
    "verificationResult",
    "answerHash",
    "provenance"
  )

  private def errors(r: QueryAuditRecord) = errorsOf(r)

  test("the encoded field set and order are exactly the frozen 15 fields") {
    ujson.read(write(good)).obj.keys.toVector shouldBe frozenFields
    goldenJson("query-audit-record.json").obj.keys.toVector shouldBe frozenFields
  }

  test("a dropped field fails decoding of the golden") {
    frozenFields.foreach { f =>
      val json = editObj(goldenJson("query-audit-record.json"))(_.remove(f))
      withClue(f)(WireCodec.decode[QueryAuditRecord](json).isLeft shouldBe true)
    }
  }

  test("a renamed field fails decoding of the golden") {
    val json = editObj(goldenJson("query-audit-record.json")) { o =>
      o("latencyMs") = o.remove("latencyMillis").get
    }
    WireCodec.decode[QueryAuditRecord](json).isLeft shouldBe true
  }

  test("no field can carry hidden model reasoning or raw prompts") {
    val banned = Seq("reason", "thought", "chain", "scratch", "rawprompt", "completion")
    frozenFields.map(_.toLowerCase).foreach(f => banned.foreach(b => f should not include b))
  }

  test("the golden decodes, carries the schema version, and the plan JSON is a valid QueryPlan") {
    val r = WireCodec.decode[QueryAuditRecord](goldenText("query-audit-record.json"))
    r shouldBe Right(good)
    r.toOption.get.schemaVersion shouldBe "query-audit-v1"
    WireCodec.decode[QueryPlan](good.planJson) shouldBe Right(Fixtures.queryPlan)
  }

  test("an unsupported schema version is rejected") {
    errors(good.copy(schemaVersion = "query-audit-v9")).head shouldBe
      a[ValidationError.UnsupportedSchemaVersion]
  }

  test("missing provenance is rejected") {
    val json = editObj(goldenJson("query-audit-record.json"))(_.remove("provenance"))
    WireCodec.decode[QueryAuditRecord](json).isLeft shouldBe true
    errors(good.copy(provenance = null)) should not be empty
  }

  test("malformed queryId, runId, researcher and evidence IDs are rejected") {
    errors(good.copy(queryId = "query:xyz")) should not be empty
    errors(good.copy(runId = good.queryId)) should not be empty
    errors(good.copy(researcherIds = Vector("researcher:nope"))) should not be empty
    errors(good.copy(evidenceIds = Vector(good.queryId))) should not be empty
  }

  test("duplicate evidence or researcher IDs are rejected") {
    errors(good.copy(evidenceIds = good.evidenceIds ++ good.evidenceIds)) should not be empty
    errors(good.copy(researcherIds = good.researcherIds ++ good.researcherIds)) should not be empty
  }

  test("blank planJson, blank version entries and a bad answer hash are rejected") {
    errors(good.copy(planJson = " ")) should not be empty
    errors(good.copy(metricVersions = Vector(""))) should not be empty
    errors(good.copy(promptVersions = Vector(" "))) should not be empty
    errors(good.copy(modelVersions = null)) should not be empty
    errors(good.copy(answerHash = "abc")) should not be empty
    errors(good.copy(answerHash = "sha256:" + "A" * 64)) should not be empty
  }

  test("negative counters are rejected, zero is allowed") {
    errors(good.copy(inputTokens = -1L)) should not be empty
    errors(good.copy(outputTokens = -1L)) should not be empty
    errors(good.copy(latencyMillis = -1L)) should not be empty
    errors(good.copy(inputTokens = 0L, outputTokens = 0L, latencyMillis = 0L)) shouldBe empty
  }

  test("an abstained answer may cite no evidence and no researchers") {
    errors(good.copy(researcherIds = Vector.empty, evidenceIds = Vector.empty)) shouldBe empty
  }

  test("every VerificationStatus value is accepted and an unknown one fails decoding") {
    VerificationStatus.values.foreach(v => errors(good.copy(verificationResult = v)) shouldBe empty)
    val json =
      editObj(goldenJson("query-audit-record.json"))(_("verificationResult") = ujson.Str("MAYBE"))
    WireCodec.decode[QueryAuditRecord](json).isLeft shouldBe true
  }
