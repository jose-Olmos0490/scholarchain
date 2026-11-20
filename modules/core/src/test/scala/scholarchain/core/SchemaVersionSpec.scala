package scholarchain.core

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.TestSupport.*
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions
import scholarchain.core.validation.ValidationError.*

class SchemaVersionSpec extends AnyFunSuite with Matchers:

  test("the frozen schema versions are exactly these v1 values") {
    SchemaVersions.supported shouldBe Map(
      "Provenance" -> Set("provenance-v1"),
      "WorkRecord" -> Set("work-v1"),
      "AuthorMention" -> Set("author-mention-v1"),
      "CanonicalResearcher" -> Set("researcher-v1"),
      "AuthorshipRecord" -> Set("authorship-v1"),
      "PublicationTeam" -> Set("publication-team-v1"),
      "CoauthorPairCandidate" -> Set("coauthor-pair-candidate-v1"),
      "CoauthorEdge" -> Set("coauthor-edge-v1"),
      "ResearcherMetrics" -> Set("researcher-metrics-v1"),
      "Venue" -> Set("venue-v1"),
      "Institution" -> Set("institution-v1"),
      "Topic" -> Set("topic-v1"),
      "MatchDecision" -> Set("match-decision-v1"),
      "GraphNode" -> Set("graph-node-v1"),
      "GraphEdge" -> Set("graph-edge-v1"),
      "EvidenceItem" -> Set("evidence-v1"),
      "EvidenceBundle" -> Set("evidence-bundle-v1"),
      "QueryPlan" -> Set("query-plan-v1"),
      "AnswerClaim" -> Set("answer-claim-v1"),
      "AnswerResponse" -> Set("answer-response-v1"),
      "RunManifest" -> Set("run-manifest-v1")
    )
  }

  test("each golden example declares its record type's current schema version") {
    GoldenCases.all.foreach { c =>
      val declared = goldenJson(c.file).obj("schemaVersion").str
      SchemaVersions.supportedFor(c.recordType) should contain(declared)
    }
  }

  GoldenCases.all.foreach { c =>
    test(s"${c.recordType}: a future schema version is rejected before field decoding") {
      val future = goldenJson(c.file).obj("schemaVersion").str.replace("-v1", "-v2")
      // Also break a field, to prove the version check runs first.
      val json = editObj(goldenJson(c.file)) { o =>
        o("schemaVersion") = ujson.Str(future)
        o("unexpectedFutureField") = ujson.Num(1)
      }
      decodeAs(c, json) match
        case Left(Vector(UnsupportedSchemaVersion("schemaVersion", t, found, _))) =>
          t shouldBe c.recordType
          found shouldBe future
        case other => fail(s"expected one UnsupportedSchemaVersion, got $other")
    }

    test(s"${c.recordType}: a missing schemaVersion is rejected") {
      val json = editObj(goldenJson(c.file))(_.remove("schemaVersion"))
      decodeAs(c, json).left.toOption.get.collect { case e: UnsupportedSchemaVersion =>
        e
      } should have size 1
    }
  }

  test("an unsupported nested provenance schema version is rejected with its path") {
    val json = editObj(goldenJson("topic.json")) { o =>
      o("provenance").obj("schemaVersion") = ujson.Str("provenance-v0")
    }
    val errors = WireCodec.decode[Topic](json).left.toOption.get
    errors.collect { case UnsupportedSchemaVersion(p, "Provenance", "provenance-v0", _) =>
      p
    } shouldBe
      Vector("provenance.schemaVersion")
  }

  test("an in-memory record with another type's schema version is rejected") {
    val wrong = Fixtures.topic.copy(schemaVersion = SchemaVersions.Venue)
    errorsOf(wrong).collect { case e: UnsupportedSchemaVersion => e.found } shouldBe Vector(
      "venue-v1"
    )
  }
