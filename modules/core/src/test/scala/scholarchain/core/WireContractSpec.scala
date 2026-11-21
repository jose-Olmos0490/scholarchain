package scholarchain.core

import java.nio.file.{Files, Paths}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters.*
import scholarchain.core.TestSupport.*
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.core.validation.ValidationError
import upickle.default.{read, write}

/** Frozen wire-format contract: every record round-trips and matches its golden JSON example. */
class WireContractSpec extends AnyFunSuite with Matchers:

  private val requiredRecordTypes = Set(
    "WorkRecord",
    "AuthorMention",
    "CanonicalResearcher",
    "AuthorshipRecord",
    "PublicationTeam",
    "CoauthorPairCandidate",
    "CoauthorEdge",
    "ResearcherMetrics",
    "Venue",
    "Institution",
    "Topic",
    "Provenance",
    "MatchDecision",
    "GraphNode",
    "GraphEdge",
    "EvidenceItem",
    "EvidenceBundle",
    "QueryPlan",
    "AnswerClaim",
    "AnswerResponse",
    "AuthorPaperRecord",
    "MatchCandidate",
    "ExactIdentifierOutcome",
    "RejectRecord"
  )

  test("golden cases cover every required record type plus the run manifest") {
    GoldenCases.all.map(_.recordType).toSet shouldBe (requiredRecordTypes + "RunManifest")
  }

  test("every golden file on disk has a golden case and vice versa") {
    val dir = Paths.get(getClass.getResource("/golden/v1").toURI)
    val onDisk = Files
      .list(dir)
      .iterator()
      .asScala
      .map(_.getFileName.toString)
      .filter(_.endsWith(".json"))
      .toSet
    onDisk shouldBe GoldenCases.all.map(_.file).toSet
  }

  GoldenCases.all.foreach { c =>
    test(s"${c.recordType}: encoding the fixture equals the frozen golden JSON (${c.file})") {
      encodeJson(c) shouldBe goldenJson(c.file)
    }

    test(s"${c.recordType}: decoding the frozen golden JSON yields the fixture") {
      decodeAs(c, goldenText(c.file)) shouldBe Right(c.value)
    }

    test(s"${c.recordType}: JSON round-trip is lossless") {
      decodeAs(c, encodeJson(c).render()) shouldBe Right(c.value)
    }

    test(s"${c.recordType}: the fixture passes validation") {
      violations(c) shouldBe empty
    }

    test(s"${c.recordType}: the wire object carries schemaVersion and, for records, provenance") {
      val obj = goldenJson(c.file).obj
      obj.get("schemaVersion").collect { case ujson.Str(s) => s } shouldBe defined
      if c.contract.requiresProvenance then obj.get("provenance") shouldBe a[Some[?]]
    }
  }

  test("enums are written as bare strings equal to the case name") {
    write(MatchOutcome.CONFLICT) shouldBe "\"CONFLICT\""
    write(VerificationStatus.PARTIALLY_SUPPORTED) shouldBe "\"PARTIALLY_SUPPORTED\""
    write(EdgeType.COAUTHORED_WITH) shouldBe "\"COAUTHORED_WITH\""
    write(NodeType.DatasetSnapshot) shouldBe "\"DatasetSnapshot\""
    MatchOutcome.values.map(_.toString).toVector shouldBe Vector(
      "ACCEPTED",
      "REJECTED",
      "UNRESOLVED",
      "CONFLICT"
    )
  }

  test("every enum value round-trips") {
    SourceType.values.foreach(v => read[SourceType](write(v)) shouldBe v)
    MatchOutcome.values.foreach(v => read[MatchOutcome](write(v)) shouldBe v)
    EvidenceType.values.foreach(v => read[EvidenceType](write(v)) shouldBe v)
    CollaborationMetric.values.foreach(v => read[CollaborationMetric](write(v)) shouldBe v)
    QueryOperation.values.foreach(v => read[QueryOperation](write(v)) shouldBe v)
    VerificationStatus.values.foreach(v => read[VerificationStatus](write(v)) shouldBe v)
    NodeType.values.foreach(v => read[NodeType](write(v)) shouldBe v)
    EdgeType.values.foreach(v => read[EdgeType](write(v)) shouldBe v)
    MatchEntityType.values.foreach(v => read[MatchEntityType](write(v)) shouldBe v)
    ExactIdType.values.foreach(v => read[ExactIdType](write(v)) shouldBe v)
    ExactIdOutcome.values.foreach(v => read[ExactIdOutcome](write(v)) shouldBe v)
  }

  test("Phase 4 enums have exactly these wire spellings") {
    MatchEntityType.values.map(write(_)).toVector shouldBe Vector("\"RESEARCHER\"", "\"WORK\"")
    ExactIdOutcome.values.map(_.toString).toVector shouldBe Vector("MATCH", "DUPLICATE", "CONFLICT")
    ExactIdType.values.map(_.toString).toVector shouldBe Vector(
      "DOI",
      "ARXIV",
      "DBLP_KEY",
      "OPENALEX_WORK",
      "ORCID",
      "DBLP_PID",
      "OPENALEX_AUTHOR"
    )
  }

  test("an unknown enum value fails decoding instead of defaulting") {
    val json = editObj(goldenJson("match-decision.json"))(_("decision") = ujson.Str("MAYBE"))
    val result = WireCodec.decode[MatchDecision](json)
    result.isLeft shouldBe true
    result.left.toOption.get.head shouldBe a[ValidationError.DecodeFailure]
  }

  test("absent optional values are written as null, not omitted") {
    val obj = goldenJson("work-record.json").obj
    obj("arxivId") shouldBe ujson.Null
    obj("abstractTextRef") shouldBe ujson.Null
  }

  test("malformed JSON and non-object JSON are decode failures") {
    WireCodec
      .decode[Topic]("{not json")
      .left
      .toOption
      .get
      .head shouldBe a[ValidationError.DecodeFailure]
    WireCodec
      .decode[Topic]("[1,2]")
      .left
      .toOption
      .get
      .head shouldBe a[ValidationError.DecodeFailure]
  }

  test("encodeValidated refuses an invalid record") {
    val bad = Fixtures.matchDecision.copy(confidence = 2.0)
    WireCodec.encodeValidated(bad).isLeft shouldBe true
    WireCodec.encodeValidated(Fixtures.matchDecision).isRight shouldBe true
  }
