package scholarchain.core

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.Fixtures.*
import scholarchain.core.ids.*
import scholarchain.core.model.*

class StableIdsSpec extends AnyFunSuite with Matchers:

  // Frozen vectors, cross-checked with an independent Python implementation of the documented
  // preimage: "scholarchain-id/v1/<kind>\n" + concat(utf8Len(v) + ":" + v).
  test("frozen ID vectors for the v1 formulas") {
    ok(StableIds.workId(WorkNamespace.Dblp, "conf/dac/NewtonRMSB00")) shouldBe
      "work:dblp:c052950cbcb85c8a57918631ac869dd2dcf014996d311adf4fbc63c6a2d981f8"
    ok(StableIds.workId(WorkNamespace.Doi, "10.1145/337292.337434")) shouldBe
      "work:doi:5e260c8ede95e178801d1278e8b61cd1efb9aadba5ffcb203862531ac719420f"
    ok(
      StableIds.researcherIdFromExternal(ResearcherNamespace.Orcid, "0000-0003-3175-3913")
    ) shouldBe
      "researcher:orcid:26ed21108b722489f94bd746bbfaade3675a6bb3264fa8c36fc9fdf70eac337d"
    ok(StableIds.researcherIdFromExternal(ResearcherNamespace.OpenAlex, "A5110371381")) shouldBe
      "researcher:openalex:c2e53727f9756b062f5a17732f0d9985ec0aa7335a8d45197742d2eb8b135c7a"
    ok(StableIds.researcherIdScholarChain("v1", "dblp:Tudor Brown")) shouldBe
      "researcher:sc-researcher-v1:9b8fecdf80cee2c7e7ccbee3eed9786284af77e7ad115f31bab1487f1ffe06c2"
    // Syntax example only; not asserted to identify any particular person.
    ok(StableIds.researcherIdFromExternal(ResearcherNamespace.DblpPid, "71/4882")) shouldBe
      "researcher:dblp:dca23169e43945ef6653e25922955b7a2467330113bb7cc67e372159c599e4b6"
  }

  test("determinism: every formula returns the same ID on repeated calls") {
    def twice(f: => Either[String, String]) = f shouldBe f
    twice(StableIds.workId(WorkNamespace.Dblp, dblpKey))
    twice(StableIds.mentionId(workId, 3))
    twice(StableIds.coauthorEdgeId(samueliId, rhinesId, 2000, 2005, coauthorPolicy))
    twice(StableIds.chunkId(workId, DblpSnapshot, "paper-title", 0, titleHash))
    twice(
      StableIds.graphEdgeId(
        EdgeType.COAUTHORED_WITH,
        samueliId,
        rhinesId,
        coauthorPolicy,
        Seq(coauthorEdge.edgeId)
      )
    )
    StableIds.runId(runManifest) shouldBe StableIds.runId(runManifest.copy())
  }

  test("work ID: equivalent spellings of an external ID normalize to one ID") {
    val a = StableIds.workId(WorkNamespace.Doi, "10.1145/337292.337434")
    StableIds.workId(WorkNamespace.Doi, "https://doi.org/10.1145/337292.337434") shouldBe a
    StableIds.workId(WorkNamespace.Doi, "DOI:10.1145/337292.337434") shouldBe a
    StableIds.workId(WorkNamespace.Doi, " 10.1145/337292.337434 ") shouldBe a
    StableIds.workId(WorkNamespace.OpenAlex, "https://openalex.org/W2040559498") shouldBe
      StableIds.workId(WorkNamespace.OpenAlex, "W2040559498")
    StableIds.workId(WorkNamespace.Arxiv, "2101.00001v3") shouldBe StableIds.workId(
      WorkNamespace.Arxiv,
      "2101.00001"
    )
  }

  test("work ID: the namespace is part of the identity and is visible in the prefix") {
    val dblp = ok(StableIds.workId(WorkNamespace.Dblp, dblpKey))
    val doi = ok(StableIds.workId(WorkNamespace.Doi, "10.1145/337292.337434"))
    dblp should startWith("work:dblp:")
    doi should startWith("work:doi:")
    dblp.split(":").last should not be doi.split(":").last
  }

  test("work ID: malformed authoritative IDs are rejected") {
    StableIds.workId(WorkNamespace.Doi, "not-a-doi").isLeft shouldBe true
    StableIds.workId(WorkNamespace.Dblp, "no slash").isLeft shouldBe true
    StableIds.workId(WorkNamespace.OpenAlex, "A5023877407").isLeft shouldBe true
  }

  test("researcher ID: ORCID iDs are validated with the ISO 7064 MOD 11-2 checksum") {
    ExternalIds.normalizeOrcid("0000-0002-1825-0097") shouldBe Right("0000-0002-1825-0097")
    ExternalIds.normalizeOrcid("https://orcid.org/0000-0003-3175-3913") shouldBe Right(
      "0000-0003-3175-3913"
    )
    ExternalIds.normalizeOrcid("0000-0002-1825-0098").isLeft shouldBe true
    ExternalIds.normalizeOrcid("0000-0002-1825").isLeft shouldBe true
  }

  // Researcher-ID precedence (human decision 3a): ORCID > dblp pid > OpenAlex > sc-researcher-v1.
  private val orcid = "0000-0003-3175-3913"
  private val pid = "71/4882"
  private val oa = "A5023877407"
  private def ext(ns: ResearcherNamespace, v: String) = StableIds.researcherIdFromExternal(ns, v)

  test("researcher ID precedence level 1: a validated ORCID wins over every other identifier") {
    StableIds.researcherId(Some(orcid), Some(pid), Some(oa), "v1", "x") shouldBe
      ext(ResearcherNamespace.Orcid, orcid)
    StableIds.researcherId(Some(orcid), None, None, "v1", "x") shouldBe
      ext(ResearcherNamespace.Orcid, orcid)
    StableIds.researcherIdBasis(Some(orcid), Some(pid), Some(oa)) shouldBe ResearcherIdBasis.Orcid
  }

  test("researcher ID precedence level 2: no ORCID falls through to the dblp pid") {
    StableIds.researcherId(None, Some(pid), Some(oa), "v1", "x") shouldBe
      ext(ResearcherNamespace.DblpPid, pid)
    ok(StableIds.researcherId(None, Some(pid), None, "v1", "x")) should startWith(
      "researcher:dblp:"
    )
    StableIds.researcherIdBasis(None, Some(pid), Some(oa)) shouldBe ResearcherIdBasis.DblpPid
  }

  test("researcher ID precedence level 3: no ORCID and no pid falls through to OpenAlex") {
    StableIds.researcherId(None, None, Some(oa), "v1", "x") shouldBe
      ext(ResearcherNamespace.OpenAlex, oa)
    StableIds.researcherIdBasis(None, None, Some(oa)) shouldBe ResearcherIdBasis.OpenAlex
  }

  test("researcher ID precedence level 4: no external ID falls through to sc-researcher-v1") {
    StableIds.researcherId(None, None, None, "v1", "dblp:Tudor Brown") shouldBe
      StableIds.researcherIdScholarChain("v1", "dblp:Tudor Brown")
    ok(StableIds.researcherId(None, None, None, "v1", "dblp:Tudor Brown")) should startWith(
      "researcher:sc-researcher-v1:"
    )
    StableIds.researcherIdBasis(None, None, None) shouldBe ResearcherIdBasis.ScholarChain
  }

  test("researcher ID precedence: a malformed present identifier is an error, not a fallthrough") {
    StableIds
      .researcherId(Some("0000-0003-3175-3914"), Some(pid), Some(oa), "v1", "x")
      .isLeft shouldBe
      true
    StableIds.researcherId(None, Some("not a pid"), Some(oa), "v1", "x").isLeft shouldBe true
    StableIds.researcherId(None, None, Some("W2040559498"), "v1", "x").isLeft shouldBe true
  }

  test("researcher ID: the four levels give four distinct IDs and namespaces") {
    val ids = Seq(
      ok(StableIds.researcherId(Some(orcid), None, None, "v1", "a")),
      ok(StableIds.researcherId(None, Some(pid), None, "v1", "a")),
      ok(StableIds.researcherId(None, None, Some(oa), "v1", "a")),
      ok(StableIds.researcherId(None, None, None, "v1", "a"))
    )
    ids.map(_.split(":")(1)) shouldBe Seq("orcid", "dblp", "openalex", "sc-researcher-v1")
    ids.distinct should have size 4
  }

  test("researcher ID: the ScholarChain identity version is part of the ID") {
    val v1 = ok(StableIds.researcherIdScholarChain("v1", "dblp:Tudor Brown"))
    val v2 = ok(StableIds.researcherIdScholarChain("v2", "dblp:Tudor Brown"))
    v1 should startWith("researcher:sc-researcher-v1:")
    v2 should startWith("researcher:sc-researcher-v2:")
    v1.split(":").last should not be v2.split(":").last
    StableIds.researcherIdScholarChain("1", "x").isLeft shouldBe true
    StableIds.researcherIdScholarChain("v1", " ").isLeft shouldBe true
  }

  test("researcher ID: the retired scholarchain-vN namespace no longer parses") {
    val digest = "0" * 64
    StableId.isValid(s"researcher:scholarchain-v1:$digest", IdKind.Researcher) shouldBe false
    StableId.isValid(s"researcher:sc-researcher-v1:$digest", IdKind.Researcher) shouldBe true
    StableId.isValid(s"researcher:dblp:$digest", IdKind.Researcher) shouldBe true
    StableId.isValid(s"researcher:sc-researcher-v0:$digest", IdKind.Researcher) shouldBe false
  }

  test("dblp pid: numeric and legacy forms are accepted and normalized to the bare pid") {
    ExternalIds.normalizeDblpPid("71/4882") shouldBe Right("71/4882")
    ExternalIds.normalizeDblpPid(" 71/4882-1 ") shouldBe Right("71/4882-1")
    ExternalIds.normalizeDblpPid("https://dblp.org/pid/71/4882.xml") shouldBe Right("71/4882")
    ExternalIds.normalizeDblpPid("https://dblp.org/pid/71/4882.html") shouldBe Right("71/4882")
    ExternalIds.normalizeDblpPid("https://dblp.org/pid/71/4882") shouldBe Right("71/4882")
    ExternalIds.normalizeDblpPid("homepages/71/4882") shouldBe Right("71/4882")
    ExternalIds.normalizeDblpPid("h/GeoffreyEHinton") shouldBe Right("h/GeoffreyEHinton")
    ext(ResearcherNamespace.DblpPid, "https://dblp.org/pid/71/4882.xml") shouldBe
      ext(ResearcherNamespace.DblpPid, "71/4882")
  }

  test("dblp pid: malformed values are rejected") {
    Seq(
      "",
      "71",
      "71/",
      "/4882",
      "71/4882/1",
      "71/4882-",
      "71/48a2",
      "conf/dac/NewtonRMSB00",
      "H/GeoffreyEHinton",
      "h/1abc",
      "Henry Samueli",
      "https://dblp.org/pid/",
      "71 /4882"
    ).foreach(bad => withClue(bad)(ExternalIds.normalizeDblpPid(bad).isLeft shouldBe true))
  }

  test("coauthor edge ID: independent of input order, sensitive to window and policy version") {
    val ab = StableIds.coauthorEdgeId(samueliId, rhinesId, 2000, 2005, coauthorPolicy)
    StableIds.coauthorEdgeId(rhinesId, samueliId, 2000, 2005, coauthorPolicy) shouldBe ab
    StableIds.coauthorEdgeId(samueliId, rhinesId, 2000, 2006, coauthorPolicy) should not be ab
    StableIds.coauthorEdgeId(samueliId, rhinesId, 2001, 2005, coauthorPolicy) should not be ab
    StableIds.coauthorEdgeId(samueliId, rhinesId, 2000, 2005, "coauthor-v2") should not be ab
  }

  test("coauthor edge ID: self-pairs, inverted windows and non-researcher IDs are rejected") {
    StableIds.coauthorEdgeId(samueliId, samueliId, 2000, 2005, coauthorPolicy).isLeft shouldBe true
    StableIds.coauthorEdgeId(samueliId, rhinesId, 2006, 2005, coauthorPolicy).isLeft shouldBe true
    StableIds.coauthorEdgeId(samueliId, workId, 2000, 2005, coauthorPolicy).isLeft shouldBe true
  }

  test("pair candidate ID: independent of mention order; self-pairs rejected") {
    val m1 = mentions(0).mentionId
    val m2 = mentions(1).mentionId
    StableIds.pairCandidateId(workId, m1, m2) shouldBe StableIds.pairCandidateId(workId, m2, m1)
    StableIds.pairCandidateId(workId, m1, m1).isLeft shouldBe true
  }

  test("graph edge ID: undirected types ignore endpoint order, directed types do not") {
    val ev = Seq(coauthorEdge.edgeId)
    StableIds.graphEdgeId(
      EdgeType.COAUTHORED_WITH,
      samueliId,
      rhinesId,
      coauthorPolicy,
      ev
    ) shouldBe
      StableIds.graphEdgeId(EdgeType.COAUTHORED_WITH, rhinesId, samueliId, coauthorPolicy, ev)
    StableIds.graphEdgeId(
      EdgeType.DERIVED_FROM,
      samueliId,
      rhinesId,
      coauthorPolicy,
      ev
    ) should not be
      StableIds.graphEdgeId(EdgeType.DERIVED_FROM, rhinesId, samueliId, coauthorPolicy, ev)
  }

  test(
    "graph edge ID: source evidence is a set (order and duplicates ignored) and changes the ID"
  ) {
    val e1 = coauthorEdge.edgeId
    val e2 = pairCandidate.candidateId
    val id = StableIds.graphEdgeId(
      EdgeType.COAUTHORED_WITH,
      samueliId,
      rhinesId,
      coauthorPolicy,
      Seq(e1, e2)
    )
    StableIds.graphEdgeId(
      EdgeType.COAUTHORED_WITH,
      samueliId,
      rhinesId,
      coauthorPolicy,
      Seq(e2, e1, e2)
    ) shouldBe id
    StableIds.graphEdgeId(
      EdgeType.COAUTHORED_WITH,
      samueliId,
      rhinesId,
      coauthorPolicy,
      Seq(e1)
    ) should not be id
  }

  test("chunk and profile IDs depend on entity, source version, doc type, ordinal and text hash") {
    val base = StableIds.chunkId(workId, DblpSnapshot, "paper-title", 0, titleHash)
    StableIds.chunkId(workId, "2027-01-01", "paper-title", 0, titleHash) should not be base
    StableIds.chunkId(workId, DblpSnapshot, "abstract", 0, titleHash) should not be base
    StableIds.chunkId(workId, DblpSnapshot, "paper-title", 1, titleHash) should not be base
    StableIds.chunkId(
      workId,
      DblpSnapshot,
      "paper-title",
      0,
      ok(StableIds.textHash("x"))
    ) should not be base
    StableIds.chunkId(samueliId, DblpSnapshot, "paper-title", 0, titleHash) should not be base
    ok(
      StableIds.profileId(samueliId, DblpSnapshot, "researcher-profile", 0, titleHash)
    ) should startWith("profile:")
    StableIds.chunkId(workId, DblpSnapshot, "paper-title", -1, titleHash).isLeft shouldBe true
    StableIds.chunkId(workId, DblpSnapshot, "paper-title", 0, "md5:abc").isLeft shouldBe true
  }

  test("text hash is sha256 of UTF-8 bytes with a sha256: prefix") {
    StableIds.textHash("") shouldBe
      Right("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
  }

  private def runIdOf(m: RunManifest): String = ok(StableIds.runId(m))

  private val openAlexInput = RunInput(
    SourceType.OPENALEX,
    "2026-10-14",
    "datasets/samples/openalex/openalex-works-snapshot-2026-10-14-excerpt.jsonl",
    "sha256:fd3dc9fcf25a19387b667d059e97bb5ec244aaa980a0d168f8c874436453f282"
  )

  // Cross-checked with an independent Python run: sha256 of the documented preimage over
  // json.dumps(manifest, sort_keys=True, separators=(",", ":")).
  test("run ID: frozen vector for the fixture manifest") {
    runIdOf(runManifest) shouldBe
      "run:a2439ecf7fe23fdbc3fce38038deb00c663b1e0302f1fcee7d34be779b5ce53c"
  }

  test("run ID: derived from manifest content only, independent of map insertion order") {
    val reordered = runManifest.copy(
      parameters =
        Map("datasets.dblpSubset.endYear" -> "2000", "datasets.dblpSubset.startYear" -> "2000")
    )
    runIdOf(reordered) shouldBe runIdOf(runManifest)
    runIdOf(runManifest.copy(codeVersion = "abc1234")) should not be runIdOf(runManifest)
    runIdOf(
      runManifest.copy(parameters = runManifest.parameters + ("x" -> "1"))
    ) should not be runIdOf(runManifest)
  }

  test("run ID: inputs are a set, so reordered inputs give the same run ID") {
    val dblpInput = runManifest.inputs.head
    val forward = runManifest.copy(inputs = Vector(dblpInput, openAlexInput))
    val backward = runManifest.copy(inputs = Vector(openAlexInput, dblpInput))
    runIdOf(forward) shouldBe runIdOf(backward)
    StableIds.canonicalManifestJson(forward) shouldBe StableIds.canonicalManifestJson(backward)
  }

  test("run ID: tied to data, so a different input checksum gives a different run ID") {
    val restaged = runManifest.copy(
      inputs = Vector(
        runManifest.inputs.head
          .copy(checksum = "sha256:" + "0" * 64)
      )
    )
    runIdOf(restaged) should not be runIdOf(runManifest)
  }

  test("run ID: every input needs a sha256 checksum; malformed manifests are a Left") {
    def withChecksum(c: String) =
      runManifest.copy(inputs = Vector(runManifest.inputs.head.copy(checksum = c)))
    StableIds.runId(withChecksum(null)).isLeft shouldBe true
    StableIds.runId(withChecksum("")).isLeft shouldBe true
    StableIds.runId(withChecksum("md5:0123456789abcdef0123456789abcdef")).isLeft shouldBe true
    StableIds.runId(withChecksum("sha256:" + "A" * 64)).isLeft shouldBe true
    StableIds.runId(runManifest.copy(inputs = Vector.empty)).isLeft shouldBe true
    StableIds
      .runId(runManifest.copy(inputs = Vector(runManifest.inputs.head, runManifest.inputs.head)))
      .isLeft shouldBe true
    StableIds.runId(null).isLeft shouldBe true
  }

  test("run ID: the run manifest has no time field, so no clock can enter the run ID") {
    val fields = classOf[RunManifest].getDeclaredFields.map(_.getName.toLowerCase).toSet ++
      classOf[RunInput].getDeclaredFields.map(_.getName.toLowerCase).toSet
    fields.filter(f =>
      Seq("time", "date", "created", "started", "instant", "clock").exists(f.contains)
    ) shouldBe empty
  }

  test("kinds are separated: the same inputs under different kinds give different digests") {
    val chunk = ok(StableIds.chunkId(workId, DblpSnapshot, "t", 0, titleHash)).split(":").last
    val profile = ok(StableIds.profileId(workId, DblpSnapshot, "t", 0, titleHash)).split(":").last
    chunk should not be profile
  }

  test("StableId.parse accepts every generated ID and renders it back unchanged") {
    val generated = Seq(
      workId,
      samueliId,
      samueliMention.mentionId,
      authorship.authorshipId,
      pairCandidate.candidateId,
      coauthorEdge.edgeId,
      metrics.metricsId,
      venue.venueId,
      institution.institutionId,
      topic.topicId,
      matchDecision.decisionId,
      graphEdge.edgeId,
      chunkId,
      edgeEvidence.evidenceId,
      bundle.bundleId,
      claim.claimId,
      queryId,
      runId,
      ok(StableIds.snapshotId(SourceType.DBLP, DblpSnapshot))
    )
    generated.foreach(id => StableId.parse(id).map(_.render) shouldBe Right(id))
    generated
      .flatMap(id => StableId.parse(id).toOption.map(_.kind))
      .toSet should have size generated.size.toLong
  }

  test("external ID validators: ROR, ISSN and dblp venue keys") {
    ExternalIds.normalizeRor("https://ror.org/00t33hh48") shouldBe Right("00t33hh48")
    ExternalIds.normalizeRor("00t33hh4").isLeft shouldBe true
    ExternalIds.normalizeIssn("0738-100X") shouldBe Right("0738-100X")
    ExternalIds.normalizeIssn("0738-1001").isLeft shouldBe true
    ExternalIds.normalizeDblpVenueKey("conf/dac") shouldBe Right("conf/dac")
    ExternalIds.normalizeDblpVenueKey("conf/dac/2000").isLeft shouldBe true
  }

  test("null inputs: every ExternalIds function returns Left instead of throwing") {
    val results = Seq(
      ExternalIds.normalizeDoi(null),
      ExternalIds.normalizeOrcid(null),
      ExternalIds.normalizeOpenAlex(null, 'W'),
      ExternalIds.normalizeDblpKey(null),
      ExternalIds.normalizeDblpVenueKey(null),
      ExternalIds.normalizeDblpPid(null),
      ExternalIds.normalizeArxiv(null),
      ExternalIds.normalizeRor(null),
      ExternalIds.normalizeIssn(null)
    )
    results.foreach(r => r.left.toOption.get should include("must not be null"))
  }

  test("null inputs: every StableIds function returns Left instead of throwing") {
    val th = titleHash
    val m1 = mentions(0).mentionId
    val results: Seq[Either[String, String]] = Seq(
      StableIds.textHash(null),
      StableIds.workId(WorkNamespace.Doi, null),
      StableIds.workId(null, "10.1145/337292.337434"),
      StableIds.researcherIdFromExternal(ResearcherNamespace.Orcid, null),
      StableIds.researcherIdFromExternal(null, "0000-0003-3175-3913"),
      StableIds.researcherIdScholarChain(null, "x"),
      StableIds.researcherIdScholarChain("v1", null),
      StableIds.researcherId(null, None, None, "v1", "x"),
      StableIds.researcherId(None, null, None, "v1", "x"),
      StableIds.researcherId(None, None, null, "v1", "x"),
      StableIds.researcherId(None, None, None, null, null),
      StableIds.researcherIdFromExternal(ResearcherNamespace.DblpPid, null),
      StableIds.venueId(VenueNamespace.Dblp, null),
      StableIds.venueId(null, "conf/dac"),
      StableIds.institutionId(InstitutionNamespace.Ror, null),
      StableIds.institutionId(null, "00t33hh48"),
      StableIds.topicId(TopicNamespace.OpenAlex, null),
      StableIds.topicId(null, "T10363"),
      StableIds.mentionId(null, 1),
      StableIds.authorshipId(null, m1, samueliId),
      StableIds.authorshipId(workId, null, samueliId),
      StableIds.authorshipId(workId, m1, null),
      StableIds.pairCandidateId(workId, null, m1),
      StableIds.pairCandidateId(workId, m1, null),
      StableIds.coauthorEdgeId(null, rhinesId, 2000, 2005, coauthorPolicy),
      StableIds.coauthorEdgeId(samueliId, null, 2000, 2005, coauthorPolicy),
      StableIds.coauthorEdgeId(samueliId, rhinesId, 2000, 2005, null),
      StableIds.metricsId(null, 2000, 2005, metricVersion, runId),
      StableIds.metricsId(samueliId, 2000, 2005, null, runId),
      StableIds.metricsId(samueliId, 2000, 2005, metricVersion, null),
      StableIds.matchDecisionId(null, samueliId, erPolicy),
      StableIds.matchDecisionId(m1, null, erPolicy),
      StableIds.matchDecisionId(m1, samueliId, null),
      StableIds.graphEdgeId(null, samueliId, rhinesId, coauthorPolicy, Seq(coauthorEdge.edgeId)),
      StableIds.graphEdgeId(EdgeType.COAUTHORED_WITH, null, rhinesId, coauthorPolicy, Seq()),
      StableIds.graphEdgeId(EdgeType.COAUTHORED_WITH, samueliId, rhinesId, coauthorPolicy, null),
      StableIds.graphEdgeId(EdgeType.DERIVED_FROM, samueliId, rhinesId, coauthorPolicy, Seq(null)),
      StableIds.chunkId(null, DblpSnapshot, "paper-title", 0, th),
      StableIds.chunkId(workId, null, "paper-title", 0, th),
      StableIds.chunkId(workId, DblpSnapshot, null, 0, th),
      StableIds.chunkId(workId, DblpSnapshot, "paper-title", 0, null),
      StableIds.profileId(samueliId, DblpSnapshot, "researcher-profile", 0, null),
      StableIds.evidenceId(null, Seq(coauthorEdge.edgeId), runId),
      StableIds.evidenceId(EvidenceType.COAUTHOR_EDGE, null, runId),
      StableIds.evidenceId(EvidenceType.COAUTHOR_EDGE, Seq(coauthorEdge.edgeId), null),
      StableIds.bundleId(null, Seq(edgeEvidence.evidenceId)),
      StableIds.bundleId(queryId, null),
      StableIds.claimId(null, 0),
      StableIds.queryId(null),
      StableIds.snapshotId(null, DblpSnapshot),
      StableIds.snapshotId(SourceType.DBLP, null),
      StableIds.canonicalManifestJson(null),
      StableIds.runId(null),
      StableIds.runId(runManifest.copy(inputs = null)),
      StableIds.runId(runManifest.copy(inputs = Vector(null))),
      StableIds.runId(runManifest.copy(parameters = null)),
      StableIds.runId(runManifest.copy(policyVersions = Map("k" -> null)))
    )
    results.zipWithIndex.foreach((r, i) => withClue(s"case $i: ")(r.isLeft shouldBe true))
    CanonicalPair.of(null, samueliId).isLeft shouldBe true
    CanonicalPair.isCanonical(null, samueliId) shouldBe false
  }
