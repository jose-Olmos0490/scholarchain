package scholarchain.core

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.Fixtures.*
import scholarchain.core.TestSupport.*
import scholarchain.core.ids.*
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.core.validation.ValidationError
import scholarchain.core.validation.ValidationError.*

/** One group per validation rule required by the Phase 3 work order. */
class ValidationSpec extends AnyFunSuite with Matchers:

  // ---------------------------------------------------------------------------------------
  // Rule 1: missing provenance
  // ---------------------------------------------------------------------------------------

  GoldenCases.all.filter(_.contract.requiresProvenance).foreach { c =>
    test(s"missing provenance: ${c.recordType} JSON without provenance is rejected") {
      val json = editObj(goldenJson(c.file))(_.remove("provenance"))
      decodeAs(c, json).left.toOption.get should contain(MissingProvenance("provenance"))
    }
  }

  test("missing provenance: a null provenance in JSON is rejected") {
    val json = editObj(goldenJson("coauthor-edge.json"))(_("provenance") = ujson.Null)
    WireCodec.decode[CoauthorEdge](json) shouldBe Left(Vector(MissingProvenance("provenance")))
  }

  test("missing provenance: a nested record without provenance is rejected with its path") {
    val json = editObj(goldenJson("work-record.json"))(_("authors").arr(2).obj.remove("provenance"))
    WireCodec.decode[WorkRecord](json) shouldBe Left(
      Vector(MissingProvenance("authors[2].provenance"))
    )
    val bundleJson =
      editObj(goldenJson("evidence-bundle.json"))(
        _("semanticEvidence").arr(0).obj.remove("provenance")
      )
    WireCodec.decode[EvidenceBundle](bundleJson) shouldBe
      Left(Vector(MissingProvenance("semanticEvidence[0].provenance")))
    val answerJson =
      editObj(goldenJson("answer-response.json"))(_("claims").arr(0).obj.remove("provenance"))
    WireCodec.decode[AnswerResponse](answerJson) shouldBe Left(
      Vector(MissingProvenance("claims[0].provenance"))
    )
  }

  test("missing provenance: an in-memory null provenance is rejected") {
    errorsOf(coauthorEdge.copy(provenance = null)) should contain(MissingProvenance("provenance"))
  }

  test("missing provenance: blank provenance fields are rejected as incomplete") {
    val p = coauthorEdge.provenance
    errorsOf(coauthorEdge.copy(provenance = p.copy(snapshot = " "))) should contain(
      IncompleteProvenance("provenance", "snapshot")
    )
    errorsOf(coauthorEdge.copy(provenance = p.copy(sourceRecordId = ""))) should contain(
      IncompleteProvenance("provenance", "sourceRecordId")
    )
    errorsOf(coauthorEdge.copy(provenance = p.copy(runId = ""))) should contain(
      IncompleteProvenance("provenance", "runId")
    )
  }

  // ---------------------------------------------------------------------------------------
  // Rule 2: confidence outside [0, 1]
  // ---------------------------------------------------------------------------------------

  test("confidence: values outside [0, 1] or NaN are rejected on a match decision") {
    Seq(-0.01, 1.01, Double.NaN, Double.PositiveInfinity).foreach { v =>
      errorsOf(matchDecision.copy(confidence = v)).collect { case e: ConfidenceOutOfRange =>
        e.path
      } shouldBe
        Vector("confidence")
    }
  }

  test("confidence: the bounds 0 and 1 are accepted") {
    errorsOf(matchDecision.copy(confidence = 0.0)) shouldBe empty
    errorsOf(matchDecision.copy(confidence = 1.0)) shouldBe empty
  }

  test("confidence: every confidence field is range-checked") {
    errorsOf(researcher.copy(identityConfidence = 1.5)) should contain(
      ConfidenceOutOfRange("identityConfidence", 1.5)
    )
    errorsOf(authorship.copy(matchConfidence = -1.0)) should contain(
      ConfidenceOutOfRange("matchConfidence", -1.0)
    )
    errorsOf(bundle.copy(identityConfidence = Map(samueliId -> 1.2))) should contain(
      ConfidenceOutOfRange(s"identityConfidence.$samueliId", 1.2)
    )
  }

  test("confidence: an out-of-range value in JSON is rejected after decoding") {
    val json = editObj(goldenJson("match-decision.json"))(_("confidence") = ujson.Num(1.5))
    WireCodec.decode[MatchDecision](json) shouldBe Left(
      Vector(ConfidenceOutOfRange("confidence", 1.5))
    )
  }

  // ---------------------------------------------------------------------------------------
  // Rule 3: unsupported schemaVersion (see also SchemaVersionSpec)
  // ---------------------------------------------------------------------------------------

  test("unsupported schemaVersion: an in-memory record with an unknown version is rejected") {
    errorsOf(coauthorEdge.copy(schemaVersion = "coauthor-edge-v9")) shouldBe Vector(
      UnsupportedSchemaVersion(
        "schemaVersion",
        "CoauthorEdge",
        "coauthor-edge-v9",
        Vector("coauthor-edge-v1")
      )
    )
  }

  // ---------------------------------------------------------------------------------------
  // Rule 4: malformed stable IDs
  // ---------------------------------------------------------------------------------------

  private def malformedPaths(errors: Vector[ValidationError]): Vector[String] =
    errors.collect { case e: MalformedStableId => e.path }

  test("malformed stable ID: a raw source key instead of a work ID is rejected") {
    malformedPaths(errorsOf(topic.copy(topicId = "openalex:T10363"))) shouldBe Vector("topicId")
    malformedPaths(errorsOf(team.copy(workId = "conf/dac/NewtonRMSB00"))) shouldBe Vector("workId")
  }

  test("malformed stable ID: a well-formed ID of the wrong kind is rejected") {
    errorsOf(authorship.copy(researcherId = workId)).collect { case e: MalformedStableId =>
      (e.path, e.expected)
    } should
      contain(("researcherId", Vector(IdKind.Researcher)))
  }

  test("malformed stable ID: bad digest, case, namespace and shape are rejected") {
    val digest = workId.split(":").last
    Seq(
      s"work:dblp:${digest.toUpperCase}",
      s"work:dblp:${digest.dropRight(1)}",
      s"work:mag:$digest",
      s"work:$digest",
      s"researcher:sc-researcher-v0:$digest",
      s"researcher:sc-researcher-1:$digest",
      s"researcher:scholarchain-v1:$digest",
      s"wrk:dblp:$digest",
      s"work:dblp:$digest:extra",
      ""
    ).foreach { bad =>
      withClue(bad) {
        malformedPaths(errorsOf(team.copy(workId = bad))) shouldBe Vector("workId")
      }
    }
  }

  test("malformed stable ID: a malformed run ID in provenance is rejected") {
    val p = topic.provenance.copy(runId = "run-2026-11-19")
    malformedPaths(errorsOf(topic.copy(provenance = p))) shouldBe Vector("provenance.runId")
  }

  test("malformed stable ID: an ID that does not match its formula is rejected") {
    val other = ok(StableIds.coauthorEdgeId(samueliId, rhinesId, 2000, 2006, coauthorPolicy))
    errorsOf(coauthorEdge.copy(edgeId = other)) shouldBe Vector(
      StableIdMismatch("edgeId", other, coauthorEdge.edgeId)
    )
    errorsOf(samueliMention.copy(position = 5)).collect { case e: StableIdMismatch =>
      e.path
    } shouldBe
      Vector("mentionId")
  }

  // ---------------------------------------------------------------------------------------
  // Rule 5: undirected pair ordering and self-pairs
  // ---------------------------------------------------------------------------------------

  test("pair ordering: a coauthor edge with researcherA > researcherB is rejected") {
    val swapped = coauthorEdge.copy(
      researcherA = coauthorEdge.researcherB,
      researcherB = coauthorEdge.researcherA
    )
    errorsOf(swapped) shouldBe Vector(
      PairNotCanonical(
        "researcherA,researcherB",
        coauthorEdge.researcherB,
        coauthorEdge.researcherA
      )
    )
  }

  test("pair ordering: a coauthor self-pair is rejected") {
    val self = coauthorEdge.copy(researcherB = coauthorEdge.researcherA)
    errorsOf(self) should contain(SelfPair("researcherA,researcherB", coauthorEdge.researcherA))
  }

  test("pair ordering: a pair candidate in the wrong order or a self-pair is rejected") {
    val swapped =
      pairCandidate.copy(memberA = pairCandidate.memberB, memberB = pairCandidate.memberA)
    errorsOf(swapped).collect { case e: PairNotCanonical => e.path } shouldBe Vector(
      "memberA,memberB"
    )
    errorsOf(pairCandidate.copy(memberB = pairCandidate.memberA)).collect { case e: SelfPair =>
      e.path
    } shouldBe
      Vector("memberA,memberB")
  }

  test("pair ordering: an undirected graph edge must be canonical, a directed one need not be") {
    val swapped = graphEdge.copy(fromId = graphEdge.toId, toId = graphEdge.fromId)
    errorsOf(swapped).collect { case e: PairNotCanonical => e.path } shouldBe Vector("fromId,toId")
    val authored = GraphEdge(
      schemaVersion = graphEdge.schemaVersion,
      edgeId = ok(
        StableIds.graphEdgeId(
          EdgeType.AUTHORED,
          samueliId,
          workId,
          coauthorPolicy,
          Seq(authorship.authorshipId)
        )
      ),
      fromId = samueliId,
      edgeType = EdgeType.AUTHORED,
      toId = workId,
      properties = Map.empty,
      policyVersion = coauthorPolicy,
      sourceEvidenceIds = Vector(authorship.authorshipId),
      provenance = authorship.provenance
    )
    samueliId.compareTo(workId) should be < 0
    errorsOf(authored) shouldBe empty
  }

  test("pair ordering: wrong order in JSON is rejected after decoding") {
    val json = editObj(goldenJson("coauthor-edge.json")) { o =>
      val a = o("researcherA"); o("researcherA") = o("researcherB"); o("researcherB") = a
    }
    WireCodec.decode[CoauthorEdge](json).left.toOption.get.collect { case e: PairNotCanonical =>
      e.path
    } shouldBe
      Vector("researcherA,researcherB")
  }

  // ---------------------------------------------------------------------------------------
  // Rule 6: windowStartYear > windowEndYear
  // ---------------------------------------------------------------------------------------

  test("window: a coauthor edge whose start year exceeds its end year is rejected") {
    val bad = coauthorEdge.copy(windowStartYear = 2006, windowEndYear = 2005)
    errorsOf(bad) should contain(InvalidWindow("windowStartYear,windowEndYear", 2006, 2005))
  }

  test("window: researcher metrics and query plans reject inverted windows") {
    errorsOf(metrics.copy(windowStartYear = 2010, windowEndYear = 2009)) should contain(
      InvalidWindow("windowStartYear,windowEndYear", 2010, 2009)
    )
    errorsOf(
      queryPlan.copy(windowStartYear = Some(2026), windowEndYear = Some(2015))
    ) shouldBe Vector(
      InvalidWindow("windowStartYear,windowEndYear", 2026, 2015)
    )
  }

  test("window: a single-year window (start == end) is valid") {
    val edge = coauthorEdge.copy(
      windowStartYear = 2000,
      windowEndYear = 2000,
      edgeId = ok(StableIds.coauthorEdgeId(samueliId, rhinesId, 2000, 2000, coauthorPolicy))
    )
    errorsOf(edge) shouldBe empty
  }

  test("window: collaboration years outside the window are rejected") {
    val bad = coauthorEdge.copy(firstCollaborationYear = 1999)
    errorsOf(bad).map(_.path) should contain("firstCollaborationYear")
  }

  // ---------------------------------------------------------------------------------------
  // Further contract checks
  // ---------------------------------------------------------------------------------------

  test("evidence: inlined text must match its text hash") {
    errorsOf(chunkEvidence.copy(text = Some("tampered"))).map(_.path) should contain("textHash")
  }

  test("evidence bundle: graph facts and semantic context stay in their own sections") {
    val mixed =
      bundle.copy(graphEvidence = Vector(chunkEvidence), semanticEvidence = Vector(edgeEvidence))
    errorsOf(mixed).map(_.path) should contain allOf (
      "graphEvidence[0].evidenceType",
      "semanticEvidence[0].evidenceType"
    )
  }

  VerificationStatus.values.foreach { status =>
    test(s"answer claim: a $status claim with no evidence IDs is rejected") {
      errorsOf(claim.copy(verificationStatus = status, evidenceIds = Vector.empty)) shouldBe Vector(
        InvalidValue("evidenceIds", s"a $status claim must cite at least one evidence ID")
      )
    }

    test(s"answer claim: a $status claim citing evidence is valid") {
      errorsOf(claim.copy(verificationStatus = status)) shouldBe empty
    }
  }

  test("answer claim: duplicate evidence IDs are rejected") {
    val e = edgeEvidence.evidenceId
    errorsOf(claim.copy(evidenceIds = Vector(e, e))) shouldBe Vector(
      InvalidValue("evidenceIds", "contains duplicates")
    )
  }

  test("answer claim: an empty-evidence claim inside an answer is rejected with its path") {
    val bad = answer.copy(claims =
      Vector(
        claim.copy(verificationStatus = VerificationStatus.UNSUPPORTED, evidenceIds = Vector.empty)
      )
    )
    errorsOf(bad).map(_.path) shouldBe Vector("claims[0].evidenceIds")
  }

  test("answer response: each claimId must be claimId(queryId, index of the claim)") {
    val shifted = claim.copy(claimId = ok(StableIds.claimId(queryId, 1)))
    errorsOf(answer.copy(claims = Vector(shifted))) shouldBe Vector(
      StableIdMismatch("claims[0].claimId", shifted.claimId, claim.claimId)
    )
    errorsOf(answer.copy(claims = Vector(claim, shifted))) shouldBe empty
  }

  test("answer response: an abstaining answer must state its uncertainty") {
    errorsOf(answer.copy(abstained = true)).map(_.path) should contain("uncertainty")
    errorsOf(
      answer.copy(abstained = true, uncertainty = Some("Identity is ambiguous."))
    ) shouldBe empty
  }

  test("work record: a DOI must be stored normalized") {
    errorsOf(work.copy(doi = Some("https://doi.org/10.1145/337292.337434")))
      .map(_.path) should contain("doi")
  }

  test("match decision: runId must equal provenance.runId") {
    val other = ok(StableIds.runId(runManifest.copy(codeVersion = "other")))
    errorsOf(matchDecision.copy(runId = other)).map(_.path) should contain("runId")
  }

  // ---------------------------------------------------------------------------------------
  // Stable-ID recomputation where the record holds the formula inputs
  // ---------------------------------------------------------------------------------------

  test("work record: a dblp workId of another key than sourceRecordId is rejected") {
    val otherId = ok(StableIds.workId(WorkNamespace.Dblp, "conf/dac/X"))
    val bad = work.copy(workId = otherId, authors = Vector.empty)
    errorsOf(bad) shouldBe Vector(StableIdMismatch("workId", otherId, workId))
    val badKey = work.copy(sourceRecordId = "conf/dac/X")
    errorsOf(badKey) shouldBe Vector(
      StableIdMismatch("workId", workId, otherId)
    )
  }

  test("work record: a dblp workId needs source DBLP, an openalex workId needs source OPENALEX") {
    errorsOf(work.copy(source = SourceType.CROSSREF)) shouldBe Vector(
      InvalidValue("workId", "a dblp work ID requires source DBLP, got CROSSREF")
    )
    val oaId = ok(StableIds.workId(WorkNamespace.OpenAlex, "W2040559498"))
    val oa = work.copy(workId = oaId, sourceRecordId = "W2040559498", authors = Vector.empty)
    errorsOf(oa) shouldBe Vector(
      InvalidValue("workId", "a openalex work ID requires source OPENALEX, got DBLP")
    )
    errorsOf(oa.copy(source = SourceType.OPENALEX)) shouldBe empty
  }

  test("work record: a doi workId is recomputed from doi, an arxiv workId from arxivId") {
    val doiId = ok(StableIds.workId(WorkNamespace.Doi, "10.1145/337292.337434"))
    val doiWork = work.copy(workId = doiId, source = SourceType.CROSSREF, authors = Vector.empty)
    errorsOf(doiWork) shouldBe empty
    val otherDoi = "10.1145/1065579.1065662"
    errorsOf(doiWork.copy(doi = Some(otherDoi))) shouldBe Vector(
      StableIdMismatch("workId", doiId, ok(StableIds.workId(WorkNamespace.Doi, otherDoi)))
    )
    errorsOf(doiWork.copy(doi = None)) shouldBe Vector(
      InvalidValue("doi", "required for a doi work ID")
    )
    val arxivId = ok(StableIds.workId(WorkNamespace.Arxiv, "2101.00001"))
    val arxivWork = work.copy(workId = arxivId, authors = Vector.empty)
    errorsOf(arxivWork) shouldBe Vector(InvalidValue("arxivId", "required for a arxiv work ID"))
    errorsOf(arxivWork.copy(arxivId = Some("2101.00001"))) shouldBe empty
  }

  test("work record: a malformed sourceRecordId for a dblp workId is reported") {
    errorsOf(work.copy(sourceRecordId = "no slash")).map(_.path) shouldBe Vector("workId")
  }

  test("venue: venueId must match sourceVenueKey (dblp, openalex) or one of issns (issn)") {
    val otherKey = "journals/cacm"
    errorsOf(venue.copy(sourceVenueKey = Some(otherKey))) shouldBe Vector(
      StableIdMismatch(
        "venueId",
        venueId,
        ok(StableIds.venueId(VenueNamespace.Dblp, otherKey))
      )
    )
    errorsOf(venue.copy(sourceVenueKey = None)) shouldBe Vector(
      InvalidValue("sourceVenueKey", "required for a dblp venue ID")
    )
    val issnId = ok(StableIds.venueId(VenueNamespace.Issn, "0738-100X"))
    errorsOf(venue.copy(venueId = issnId)) shouldBe empty
    errorsOf(venue.copy(venueId = issnId, issns = Vector.empty)) shouldBe Vector(
      InvalidValue("venueId", "an issn venue ID must be derived from one of issns")
    )
  }

  test("institution: a ror ID is recomputed from rorId, an openalex ID from OpenAlex provenance") {
    val rorId = ok(StableIds.institutionId(InstitutionNamespace.Ror, "00t33hh48"))
    errorsOf(institution.copy(institutionId = rorId)) shouldBe empty
    errorsOf(institution.copy(institutionId = rorId, rorId = Some("03yrm5c26"))) shouldBe Vector(
      StableIdMismatch(
        "institutionId",
        rorId,
        ok(StableIds.institutionId(InstitutionNamespace.Ror, "03yrm5c26"))
      )
    )
    errorsOf(institution.copy(institutionId = rorId, rorId = None)) shouldBe Vector(
      InvalidValue("rorId", "required for a ror institution ID")
    )
    val p = institution.provenance.copy(sourceRecordId = "https://openalex.org/I1")
    errorsOf(institution.copy(provenance = p)) shouldBe Vector(
      StableIdMismatch(
        "institutionId",
        institution.institutionId,
        ok(StableIds.institutionId(InstitutionNamespace.OpenAlex, "I1"))
      )
    )
  }

  test("topic: an openalex topicId from OpenAlex must match provenance.sourceRecordId") {
    val p = topic.provenance.copy(sourceRecordId = "https://openalex.org/T1")
    errorsOf(topic.copy(provenance = p)) shouldBe Vector(
      StableIdMismatch(
        "topicId",
        topic.topicId,
        ok(StableIds.topicId(TopicNamespace.OpenAlex, "T1"))
      )
    )
    val derived =
      topic.provenance.copy(sourceDataset = SourceType.SCHOLARCHAIN, sourceRecordId = "x")
    errorsOf(topic.copy(provenance = derived)) shouldBe empty
  }

  // ---------------------------------------------------------------------------------------
  // Run manifest
  // ---------------------------------------------------------------------------------------

  test("run manifest: every input needs a sha256 checksum") {
    val in = runManifest.inputs.head
    Seq(null, "", "md5:0123456789abcdef0123456789abcdef", "sha256:xyz").foreach { c =>
      withClue(String.valueOf(c)) {
        errorsOf(runManifest.copy(inputs = Vector(in.copy(checksum = c)))).map(_.path) shouldBe
          Vector("inputs[0].checksum")
      }
    }
  }

  test("run manifest: two inputs naming the same dataset and location are rejected") {
    val in = runManifest.inputs.head
    val dup = in.copy(checksum = "sha256:" + "1" * 64)
    errorsOf(runManifest.copy(inputs = Vector(in, dup))) shouldBe Vector(
      InvalidValue("inputs", "contains duplicates")
    )
  }

  // ---------------------------------------------------------------------------------------
  // Canonical researcher: ID precedence ORCID > dblp pid > OpenAlex > sc-researcher-v1
  // ---------------------------------------------------------------------------------------

  private val pid = "71/4882"
  private val pidId = ok(StableIds.researcherIdFromExternal(ResearcherNamespace.DblpPid, pid))
  private val oaId = ok(
    StableIds.researcherIdFromExternal(ResearcherNamespace.OpenAlex, "A5023877407")
  )
  private val scId = ok(StableIds.researcherIdScholarChain("v1", "dblp:Henry Samueli"))

  private def precedenceErrors(r: CanonicalResearcher): Vector[String] =
    errorsOf(r).collect { case InvalidValue("researcherId", m) if m.startsWith("precedence") => m }

  test("researcher precedence: an ORCID-based ID with ORCID, pid and OpenAlex present is valid") {
    errorsOf(researcher.copy(dblpPid = Some(pid))) shouldBe empty
  }

  test("researcher precedence: a lower-level ID while an ORCID is present is rejected") {
    precedenceErrors(researcher.copy(researcherId = oaId)) shouldBe Vector(
      "precedence ORCID > dblp pid > OpenAlex > sc-researcher: the identifiers present require " +
        "namespace 'orcid', found 'openalex'"
    )
    precedenceErrors(researcher.copy(researcherId = pidId, dblpPid = Some(pid))) should have size 1
  }

  test("researcher precedence: a dblp-pid ID is valid when no ORCID is present") {
    val r = researcher.copy(researcherId = pidId, orcid = None, dblpPid = Some(pid))
    errorsOf(r) shouldBe empty
    // ... but not an OpenAlex ID when the pid is present.
    precedenceErrors(r.copy(researcherId = oaId)) should have size 1
  }

  test("researcher precedence: a dblp-pid ID must match dblpPid and needs it") {
    val r = researcher.copy(researcherId = pidId, orcid = None, dblpPid = Some("71/4882-1"))
    errorsOf(r) shouldBe Vector(
      StableIdMismatch(
        "researcherId",
        pidId,
        ok(StableIds.researcherIdFromExternal(ResearcherNamespace.DblpPid, "71/4882-1"))
      )
    )
  }

  test("researcher precedence: an OpenAlex ID is valid when only OpenAlex is present") {
    errorsOf(researcher.copy(researcherId = oaId, orcid = None)) shouldBe empty
  }

  test("researcher precedence: sc-researcher-v1 only when no external ID is present") {
    val sc = researcher.copy(
      researcherId = scId,
      orcid = None,
      openAlexAuthorId = None,
      identityVersion = Some("v1")
    )
    errorsOf(sc) shouldBe empty
    errorsOf(sc.copy(identityVersion = None)) shouldBe Vector(
      InvalidValue("identityVersion", "must be Some(v1) for namespace sc-researcher-v1")
    )
    precedenceErrors(sc.copy(openAlexAuthorId = Some("A5023877407"))) should have size 1
    precedenceErrors(sc.copy(dblpPid = Some(pid))) should have size 1
  }

  test("researcher: dblpPid must be a bare, well-formed pid and not null") {
    errorsOf(researcher.copy(dblpPid = Some("https://dblp.org/pid/71/4882.xml"))) shouldBe Vector(
      InvalidValue("dblpPid", "must be stored in bare form '71/4882'")
    )
    errorsOf(researcher.copy(dblpPid = Some("Henry Samueli"))).map(_.path) shouldBe Vector(
      "dblpPid"
    )
    errorsOf(researcher.copy(dblpPid = null)) shouldBe Vector(
      InvalidValue("dblpPid", "null option")
    )
  }

  test("researcher: the dblpPid field is on the wire and decodes") {
    val json = editObj(goldenJson("canonical-researcher.json")) { o =>
      o("researcherId") = ujson.Str(pidId)
      o("orcid") = ujson.Null
      o("dblpPid") = ujson.Str(pid)
    }
    WireCodec.decode[CanonicalResearcher](json).map(_.dblpPid) shouldBe Right(Some(pid))
  }

  // ---------------------------------------------------------------------------------------
  // Claim evidence rule (human decision 2, 2026-11-20)
  // ---------------------------------------------------------------------------------------

  /** A model-generated claim that its cited evidence does not support: edgeEvidence records 1
    * joint publication, the claim says 40.
    */
  private val unsupportedClaim = claim.copy(
    text = "Henry Samueli and Walden C. Rhines have 40 joint publications in 2000 to 2005.",
    evidenceIds = Vector(edgeEvidence.evidenceId),
    verificationStatus = VerificationStatus.UNSUPPORTED,
    provenance = claim.provenance.copy(modelVersion = Some("illustrative-synthesis-model"))
  )

  test("claim rule: an UNSUPPORTED claim citing non-supporting evidence is valid") {
    errorsOf(unsupportedClaim) shouldBe empty
    errorsOf(answer.copy(claims = Vector(unsupportedClaim))) shouldBe empty
  }

  test("claim rule: an UNSUPPORTED claim with no evidence is rejected") {
    errorsOf(unsupportedClaim.copy(evidenceIds = Vector.empty)) shouldBe Vector(
      InvalidValue("evidenceIds", "a UNSUPPORTED claim must cite at least one evidence ID")
    )
  }

  test("claim rule: an AnswerResponse containing an evidence-free claim is rejected") {
    val bad = answer.copy(claims =
      Vector(
        claim,
        unsupportedClaim.copy(
          claimId = ok(StableIds.claimId(queryId, 1)),
          evidenceIds = Vector.empty
        )
      )
    )
    errorsOf(bad) shouldBe Vector(
      InvalidValue(
        "claims[1].evidenceIds",
        "a UNSUPPORTED claim must cite at least one evidence ID"
      )
    )
    WireCodec.validate(bad).isLeft shouldBe true
    WireCodec.encodeValidated(bad).isLeft shouldBe true
  }

  test("claim rule: decoding an answer whose claim has evidenceIds [] fails validation") {
    val json = editObj(goldenJson("answer-response.json"))(
      _("claims").arr(0).obj("evidenceIds") = ujson.Arr()
    )
    WireCodec.decode[AnswerResponse](json) shouldBe Left(
      Vector(
        InvalidValue("claims[0].evidenceIds", "a VERIFIED claim must cite at least one evidence ID")
      )
    )
  }
