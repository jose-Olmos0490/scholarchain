package scholarchain.core

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.Fixtures.*
import scholarchain.core.TestSupport.*
import scholarchain.core.ids.*
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.core.validation.RecordContract.given
import scholarchain.core.validation.ValidationError
import scholarchain.core.validation.ValidationError.*

/** Phase 4 ingestion contracts: AuthorPaperRecord, MatchCandidate, ExactIdentifierOutcome and
  * RejectRecord. Golden freezing and round trips are covered by the golden suites; this suite
  * covers IDs and validation rules.
  */
class IngestionContractSpec extends AnyFunSuite with Matchers:

  private def paths(errors: Vector[ValidationError]): Vector[String] = errors.map(_.path)

  // --- AuthorPaperRecord ---------------------------------------------------------------------

  test("AuthorPaperRecord: the fixture is valid and keyed by its mention and work") {
    errorsOf(authorPaper) shouldBe empty
    authorPaper.mentionId shouldBe ok(StableIds.mentionId(authorPaper.workId, authorPaper.position))
  }

  test("AuthorPaperRecord: a mention ID that does not match workId and position is rejected") {
    errorsOf(authorPaper.copy(position = 3)).collect { case e: StableIdMismatch =>
      e.path
    } shouldBe Vector("mentionId")
  }

  test("AuthorPaperRecord: teamSize and position must be >= 1") {
    paths(errorsOf(authorPaper.copy(teamSize = 0))) shouldBe Vector("teamSize")
    paths(errorsOf(authorPaper.copy(position = 0))) should contain("position")
  }

  test("AuthorPaperRecord: position may exceed teamSize after repeated mentions are removed") {
    val m = ok(StableIds.mentionId(workId, 6))
    errorsOf(authorPaper.copy(mentionId = m, position = 6, teamSize = 5)) shouldBe empty
  }

  test("AuthorPaperRecord: a non-normalized ORCID is rejected") {
    paths(
      errorsOf(authorPaper.copy(orcid = Some("https://orcid.org/0000-0003-3175-3913")))
    ) shouldBe
      Vector("orcid")
    errorsOf(authorPaper.copy(orcid = Some("0000-0003-3175-3913"))) shouldBe empty
  }

  test("AuthorPaperRecord: missing provenance is rejected on decode") {
    val json = editObj(goldenJson("author-paper-record.json"))(_.remove("provenance"))
    WireCodec.decode[AuthorPaperRecord](json) shouldBe Left(Vector(MissingProvenance("provenance")))
  }

  // --- MatchCandidate ------------------------------------------------------------------------

  test("MatchCandidate: the wire object has exactly the agreed fields and no decision field") {
    goldenJson("match-candidate.json").obj.keys.toVector shouldBe Vector(
      "schemaVersion",
      "candidateId",
      "entityType",
      "leftRef",
      "rightRef",
      "blockKey",
      "features",
      "featureVersion",
      "provenance"
    )
  }

  test("MatchCandidate: the ID ignores input order and block key, and depends on the rest") {
    val id = ok(StableIds.matchCandidateId(MatchEntityType.WORK, workId, openAlexWorkId, "f1"))
    ok(StableIds.matchCandidateId(MatchEntityType.WORK, openAlexWorkId, workId, "f1")) shouldBe id
    ok(StableIds.matchCandidateId(MatchEntityType.WORK, workId, openAlexWorkId, "f2")) should not be
      id
    errorsOf(matchCandidate.copy(blockKey = "another-block")) shouldBe empty
    id should startWith("match-candidate:")
  }

  test("MatchCandidate: refs must be in canonical order and distinct") {
    val swapped =
      matchCandidate.copy(leftRef = matchCandidate.rightRef, rightRef = matchCandidate.leftRef)
    errorsOf(swapped).collect { case e: PairNotCanonical => e.path } shouldBe Vector(
      "leftRef,rightRef"
    )
    val self = matchCandidate.copy(rightRef = matchCandidate.leftRef)
    errorsOf(self).collect { case e: SelfPair => e.path } shouldBe Vector("leftRef,rightRef")
    StableIds.matchCandidateId(MatchEntityType.WORK, workId, workId, "f").isLeft shouldBe true
  }

  test("MatchCandidate: ref kinds follow the entity type") {
    val wrongKind = matchCandidate.copy(entityType = MatchEntityType.RESEARCHER)
    errorsOf(wrongKind).collect { case e: MalformedStableId => e.path } shouldBe
      Vector("leftRef", "rightRef")
    StableIds
      .matchCandidateId(MatchEntityType.WORK, samueliMention.mentionId, workId, "f")
      .isLeft shouldBe true
  }

  test("MatchCandidate: a researcher candidate may pair a mention with a researcher ID") {
    val openAlexAuthor =
      ok(StableIds.researcherIdFromExternal(ResearcherNamespace.OpenAlex, "A5023877407"))
    val pair = CanonicalPair.of(openAlexAuthor, samueliMention.mentionId).toOption.get
    val c = MatchCandidate(
      schemaVersion = matchCandidate.schemaVersion,
      candidateId = ok(
        StableIds.matchCandidateId(
          MatchEntityType.RESEARCHER,
          samueliMention.mentionId,
          openAlexAuthor,
          featureVersion
        )
      ),
      entityType = MatchEntityType.RESEARCHER,
      leftRef = pair.a,
      rightRef = pair.b,
      blockKey = "samueli|h",
      features = Map("nameJaroWinkler" -> 1.0),
      featureVersion = featureVersion,
      provenance = matchCandidate.provenance
    )
    errorsOf(c) shouldBe empty
    pair.a shouldBe samueliMention.mentionId
  }

  test(
    "MatchCandidate: a wrong candidate ID, blank block key and non-finite feature are rejected"
  ) {
    paths(errorsOf(matchCandidate.copy(featureVersion = "other"))) shouldBe Vector("candidateId")
    paths(errorsOf(matchCandidate.copy(blockKey = " "))) shouldBe Vector("blockKey")
    paths(errorsOf(matchCandidate.copy(features = Map("x" -> Double.NaN)))) shouldBe
      Vector("features.x")
    paths(errorsOf(matchCandidate.copy(features = Map("y" -> Double.PositiveInfinity)))) shouldBe
      Vector("features.y")
  }

  test("MatchCandidate: an unknown entity type fails decoding") {
    val json = editObj(goldenJson("match-candidate.json"))(_("entityType") = ujson.Str("VENUE"))
    WireCodec.decode[MatchCandidate](json).left.toOption.get.head shouldBe a[DecodeFailure]
  }

  // --- ExactIdentifierOutcome ----------------------------------------------------------------

  test("ExactIdentifierOutcome: the ID normalizes the identifier and ignores outcome and records") {
    ok(
      StableIds.exactIdOutcomeId(ExactIdType.DOI, "https://doi.org/10.1145/337292.337434")
    ) shouldBe
      exactIdOutcome.outcomeId
    errorsOf(exactIdOutcome.copy(outcome = ExactIdOutcome.CONFLICT)) shouldBe empty
    ok(StableIds.exactIdOutcomeId(ExactIdType.ARXIV, "1234.56789")) should not be
      ok(StableIds.exactIdOutcomeId(ExactIdType.DOI, "10.1234/56789"))
    StableIds.exactIdOutcomeId(ExactIdType.ORCID, "0000-0003-3175-3910").isLeft shouldBe true
  }

  test("ExactIdentifierOutcome: normalizedId must already be normalized") {
    val prefixed = exactIdOutcome.copy(normalizedId = "doi:10.1145/337292.337434")
    paths(errorsOf(prefixed)) shouldBe Vector("normalizedId")
    paths(errorsOf(exactIdOutcome.copy(normalizedId = "not-a-doi"))) shouldBe Vector("normalizedId")
  }

  test("ExactIdentifierOutcome: at least two records, sorted and distinct") {
    paths(errorsOf(exactIdOutcome.copy(recordRefs = Vector(workId)))) shouldBe Vector("recordRefs")
    paths(errorsOf(exactIdOutcome.copy(recordRefs = exactIdOutcome.recordRefs.reverse))) shouldBe
      Vector("recordRefs")
    paths(errorsOf(exactIdOutcome.copy(recordRefs = Vector(workId, workId)))) shouldBe
      Vector("recordRefs")
  }

  test("ExactIdentifierOutcome: record kinds follow the identifier type") {
    val orcid = "0000-0003-3175-3913"
    val wrong = exactIdOutcome.copy(
      outcomeId = ok(StableIds.exactIdOutcomeId(ExactIdType.ORCID, orcid)),
      idType = ExactIdType.ORCID,
      normalizedId = orcid
    )
    errorsOf(wrong).collect { case e: MalformedStableId => e.path } shouldBe
      Vector("recordRefs[0]", "recordRefs[1]")
    val mentions = Vector(samueliMention.mentionId, samueliId).sorted
    errorsOf(wrong.copy(recordRefs = mentions, outcome = ExactIdOutcome.DUPLICATE)) shouldBe empty
  }

  test("ExactIdentifierOutcome: a mismatched outcome ID is rejected") {
    val other = ok(StableIds.exactIdOutcomeId(ExactIdType.DOI, "10.1145/1.2"))
    errorsOf(exactIdOutcome.copy(outcomeId = other)).collect { case e: StableIdMismatch =>
      e.path
    } shouldBe Vector("outcomeId")
  }

  // --- RejectRecord --------------------------------------------------------------------------

  test("RejectRecord: rawExcerpt allows 1024 code points and rejects 1025") {
    errorsOf(reject.copy(rawExcerpt = "a" * 1024)) shouldBe empty
    errorsOf(reject.copy(rawExcerpt = "")) shouldBe empty
    paths(errorsOf(reject.copy(rawExcerpt = "a" * 1025))) shouldBe Vector("rawExcerpt")
  }

  test("RejectRecord: rawExcerpt counts code points, so 1024 astral characters are allowed") {
    val astral = "😀" // one code point, two UTF-16 units
    errorsOf(reject.copy(rawExcerpt = astral * 1024)) shouldBe empty
    paths(errorsOf(reject.copy(rawExcerpt = astral * 1025))) shouldBe Vector("rawExcerpt")
  }

  test("RejectRecord.truncateExcerpt keeps 1024 code points and never splits a surrogate pair") {
    val astral = "😀"
    val t = RejectRecord.truncateExcerpt(astral * 2000)
    t.codePointCount(0, t.length) shouldBe 1024
    t shouldBe astral * 1024
    RejectRecord.truncateExcerpt("short") shouldBe "short"
    RejectRecord.truncateExcerpt(null) shouldBe ""
    errorsOf(reject.copy(rawExcerpt = RejectRecord.truncateExcerpt("x" * 5000))) shouldBe empty
  }

  test("RejectRecord: reason codes are upper-case identifiers") {
    paths(errorsOf(reject.copy(reasonCode = "unknown entity"))) shouldBe Vector("reasonCode")
    paths(errorsOf(reject.copy(reasonCode = "_X"))) shouldBe Vector("reasonCode")
    errorsOf(reject.copy(reasonCode = "SCHEMA_FAILURE")) shouldBe empty
  }

  test("RejectRecord: runId, source and sourceRecordRef must agree with provenance") {
    val otherRun = "run:" + "0" * 64
    paths(errorsOf(reject.copy(runId = otherRun))) shouldBe Vector("runId")
    paths(errorsOf(reject.copy(source = SourceType.CROSSREF))) shouldBe Vector("source")
    paths(errorsOf(reject.copy(sourceRecordRef = "elsewhere"))) shouldBe Vector("sourceRecordRef")
    paths(errorsOf(reject.copy(runId = "run:bad"))) should contain("runId")
  }

  test("RejectRecord: blank stage and missing provenance are rejected") {
    paths(errorsOf(reject.copy(stage = ""))) shouldBe Vector("stage")
    val json = editObj(goldenJson("reject-record.json"))(_("provenance") = ujson.Null)
    WireCodec.decode[RejectRecord](json) shouldBe Left(Vector(MissingProvenance("provenance")))
  }
