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

/** Contract tests for the Phase 6 Spark analytics records and their stable-ID formulas. The
  * valid fixtures and their golden round-trips are covered by [[WireContractSpec]] and
  * [[GoldenFreezeSpec]]; this spec proves each rule rejects what it should.
  */
class AnalyticsContractSpec extends AnyFunSuite with Matchers:

  private def paths(errors: Vector[ValidationError]): Vector[String] = errors.map(_.path)

  private def invalidAt(errors: Vector[ValidationError], path: String): Boolean =
    errors.exists(_.path == path)

  private val otherRun = "run:" + "b" * 64

  // --- IDs ----------------------------------------------------------------------------------

  test("pathId is directed, window- and run-scoped, and rejects same-endpoint queries") {
    val ab = ok(StableIds.pathId(samueliId, rhinesId, 2000, 2005, coauthorPolicy, runId))
    ab should startWith("path:")
    StableId.isValid(ab, IdKind.Path) shouldBe true
    ok(StableIds.pathId(rhinesId, samueliId, 2000, 2005, coauthorPolicy, runId)) should not be ab
    ok(StableIds.pathId(samueliId, rhinesId, 2000, 2006, coauthorPolicy, runId)) should not be ab
    ok(StableIds.pathId(samueliId, rhinesId, 2000, 2005, coauthorPolicy, otherRun)) should not be
      ab
    StableIds.pathId(samueliId, samueliId, 2000, 2005, coauthorPolicy, runId).isLeft shouldBe true
    StableIds.pathId(samueliId, rhinesId, 2006, 2005, coauthorPolicy, runId).isLeft shouldBe true
    StableIds.pathId(workId, rhinesId, 2000, 2005, coauthorPolicy, runId).isLeft shouldBe true
  }

  test("semanticFactId checks the subject kind against the subject type") {
    val r =
      StableIds.semanticFactId(SemanticSubjectType.RESEARCHER, samueliId, 2000, 2005, "m", runId)
    r.map(_.startsWith("semantic-fact:")) shouldBe Right(true)
    StableIds
      .semanticFactId(SemanticSubjectType.COMMUNITY, samueliId, 2000, 2005, "m", runId)
      .toOption should not be r.toOption
    StableIds
      .semanticFactId(SemanticSubjectType.TOPIC, samueliId, 2000, 2005, "m", runId)
      .isLeft shouldBe true
    StableIds
      .semanticFactId(SemanticSubjectType.TOPIC, topic.topicId, 2000, 2005, "m", runId)
      .isRight shouldBe true
    StableIds
      .semanticFactId(SemanticSubjectType.PATH, pathEvidence.pathId, 2000, 2005, "m", runId)
      .isRight shouldBe true
  }

  test("identityConflictId ignores member order and duplicates and needs two members") {
    val (m1, m2) = (rhinesMention.mentionId, samueliMention.mentionId)
    val id = ok(StableIds.identityConflictId("v1", erPolicy, Seq(m1, m2)))
    StableIds.identityConflictId("v1", erPolicy, Seq(m2, m1, m2)) shouldBe Right(id)
    StableIds.identityConflictId("v2", erPolicy, Seq(m1, m2)).toOption should not be Some(id)
    StableIds.identityConflictId("v1", erPolicy, Seq(m1, m1)).isLeft shouldBe true
    StableIds.identityConflictId("1", erPolicy, Seq(m1, m2)).isLeft shouldBe true
    StableIds.identityConflictId("v1", erPolicy, Seq(m1, workId)).isLeft shouldBe true
  }

  // --- coauthor-edge support ----------------------------------------------------------------

  test("CoauthorEdge.supportingWorkIdsRef must point to the edge's support rows") {
    val ref = CoauthorEdgeSupport.supportRef(coauthorEdge.edgeId)
    ref shouldBe s"coauthor-edge-support-v1:${coauthorEdge.edgeId}"
    errorsOf(coauthorEdge.copy(supportingWorkIdsRef = Some(ref))) shouldBe empty
    paths(errorsOf(coauthorEdge.copy(supportingWorkIdsRef = Some("s3://elsewhere")))) shouldBe
      Vector("supportingWorkIdsRef")
    paths(
      errorsOf(
        coauthorEdge.copy(supportingWorkIdsRef =
          Some(CoauthorEdgeSupport.supportRef("coauthor-edge:" + "0" * 64))
        )
      )
    ) shouldBe Vector("supportingWorkIdsRef")
  }

  test("CoauthorEdgeSupport: edge ID formula, canonical pair, year inside window, run ID") {
    errorsOf(edgeSupport) shouldBe empty
    errorsOf(edgeSupport.copy(workYear = None)) shouldBe empty
    paths(errorsOf(edgeSupport.copy(workYear = Some(2006)))) shouldBe Vector("workYear")
    errorsOf(edgeSupport.copy(windowEndYear = 2006)).collect { case e: StableIdMismatch =>
      e.path
    } shouldBe Vector("edgeId")
    errorsOf(
      edgeSupport.copy(researcherA = edgeSupport.researcherB, researcherB = edgeSupport.researcherA)
    ).collect { case e: PairNotCanonical => e } should have size 1
    paths(errorsOf(edgeSupport.copy(runId = otherRun))) shouldBe Vector("runId")
    paths(errorsOf(edgeSupport.copy(workId = samueliId))) shouldBe Vector("workId")
  }

  // --- components and communities -----------------------------------------------------------

  test("ComponentMembership: the component ID is the smallest member ID") {
    errorsOf(componentMembership) shouldBe empty
    // Rhines' ID sorts before Samueli's, so Samueli cannot label a component Rhines is in.
    paths(
      errorsOf(componentMembership.copy(researcherId = rhinesId, componentId = samueliId))
    ) shouldBe
      Vector("componentId")
    paths(errorsOf(componentMembership.copy(componentSize = 1))) shouldBe Vector("componentId")
    errorsOf(componentMembership.copy(componentId = samueliId, componentSize = 1)) shouldBe empty
    paths(errorsOf(componentMembership.copy(componentSize = 0))) should contain("componentSize")
    paths(errorsOf(componentMembership.copy(componentId = "component-1"))) shouldBe
      Vector("componentId")
  }

  test("CommunityMembership and CrossCommunityEdge") {
    errorsOf(communityMembership) shouldBe empty
    paths(errorsOf(communityMembership.copy(communitySize = 0))) shouldBe Vector("communitySize")
    paths(errorsOf(communityMembership.copy(windowStartYear = 2010))) shouldBe
      Vector("windowStartYear,windowEndYear")
    errorsOf(crossCommunityEdge) shouldBe empty
    paths(errorsOf(crossCommunityEdge.copy(communityB = crossCommunityEdge.communityA))) shouldBe
      Vector("communityA,communityB")
    paths(errorsOf(crossCommunityEdge.copy(jointPublicationCount = 0))) shouldBe
      Vector("jointPublicationCount")
    errorsOf(crossCommunityEdge.copy(policyVersion = "other")).collect { case e: StableIdMismatch =>
      e.path
    } shouldBe Vector("edgeId")
  }

  // --- bridges and coreness -----------------------------------------------------------------

  test("BridgeMeasures: the work-order fixture value for C (4/9) is valid") {
    val c = bridgeMeasures.copy(
      degree = 3,
      crossCommunityDegree = 1,
      communitiesConnected = 2,
      participationCoefficient = 4.0 / 9.0,
      betweenness = Some(6.0),
      betweennessMethod = Some(BetweennessMethod.EXACT),
      betweennessSampleSources = None,
      betweennessSeed = None
    )
    errorsOf(c) shouldBe empty
    errorsOf(c.copy(betweenness = None, betweennessMethod = None)) shouldBe empty
  }

  test("BridgeMeasures: range and consistency rules") {
    paths(errorsOf(bridgeMeasures.copy(crossCommunityDegree = 2))) shouldBe
      Vector("crossCommunityDegree")
    paths(errorsOf(bridgeMeasures.copy(communitiesConnected = 3))) shouldBe
      Vector("communitiesConnected")
    paths(errorsOf(bridgeMeasures.copy(participationCoefficient = 1.5))) shouldBe
      Vector("participationCoefficient")
    paths(errorsOf(bridgeMeasures.copy(participationCoefficient = Double.NaN))) shouldBe
      Vector("participationCoefficient")
    paths(
      errorsOf(bridgeMeasures.copy(crossCommunityDegree = 0, participationCoefficient = 0.5))
    ) shouldBe Vector("participationCoefficient")
    // One neighbour in a foreign community: crossCommunityDegree 1 with a coefficient of 0.
    errorsOf(bridgeMeasures) shouldBe empty
  }

  test("BridgeMeasures: optional betweenness carries its method, and k and seed iff sampled") {
    paths(
      errorsOf(
        bridgeMeasures.copy(
          betweennessMethod = None,
          betweennessSampleSources = None,
          betweennessSeed = None
        )
      )
    ) shouldBe Vector("betweennessMethod")
    paths(errorsOf(bridgeMeasures.copy(betweennessSeed = None))) shouldBe
      Vector("betweennessSampleSources")
    paths(errorsOf(bridgeMeasures.copy(betweennessSampleSources = Some(0)))) shouldBe
      Vector("betweennessSampleSources")
    paths(
      errorsOf(bridgeMeasures.copy(betweennessMethod = Some(BetweennessMethod.EXACT)))
    ) shouldBe Vector("betweennessSampleSources")
    paths(errorsOf(bridgeMeasures.copy(betweenness = Some(-1.0)))) shouldBe Vector("betweenness")
  }

  test("CorenessRecord: 0 <= coreness <= degree") {
    errorsOf(coreness) shouldBe empty
    errorsOf(coreness.copy(coreness = 0, degree = 0)) shouldBe empty
    paths(errorsOf(coreness.copy(coreness = 2))) shouldBe Vector("coreness")
    paths(errorsOf(coreness.copy(coreness = -1))) shouldBe Vector("coreness")
  }

  // --- path evidence ------------------------------------------------------------------------

  private val third = ok(StableIds.researcherIdScholarChain("v1", "Third Person 0001"))

  private def hop(from: String, to: String, works: Vector[String]): PathHop =
    val p = CanonicalPair.of(from, to).toOption.get
    PathHop(
      from,
      to,
      ok(StableIds.coauthorEdgeId(p.a, p.b, 2000, 2005, coauthorPolicy)),
      works.size.toLong,
      works.sorted
    )

  private def twoHop(hops: Vector[PathHop], target: String = third): PathEvidence =
    pathEvidence.copy(
      pathId = ok(StableIds.pathId(samueliId, target, 2000, 2005, coauthorPolicy, runId)),
      targetResearcherId = target,
      length = Some(hops.size),
      hops = hops
    )

  test("PathEvidence: a valid two-hop path with every supporting work") {
    val p = twoHop(
      Vector(
        hop(samueliId, rhinesId, Vector(workId)),
        hop(rhinesId, third, Vector(workId, openAlexWorkId))
      )
    )
    errorsOf(p) shouldBe empty
    decodeAs(GoldenCase("unused.json", p), WireCodec.encode(p)) shouldBe Right(p)
  }

  test("PathEvidence: chain, endpoints, depth, simplicity and length are enforced") {
    val h1 = hop(samueliId, rhinesId, Vector(workId))
    val h2 = hop(rhinesId, third, Vector(workId))
    paths(errorsOf(twoHop(Vector(h1, h2)).copy(maxDepth = 1))) shouldBe Vector("hops")
    paths(errorsOf(twoHop(Vector(h1, h2)).copy(length = Some(3)))) shouldBe Vector("length")
    paths(errorsOf(twoHop(Vector(h2, h1), target = rhinesId))) should contain(
      "hops[0].fromResearcherId"
    )
    paths(errorsOf(twoHop(Vector(h1, hop(samueliId, third, Vector(workId)))))) shouldBe
      Vector("hops[1].fromResearcherId")
    // Revisiting a researcher is not a simple path.
    val revisit =
      twoHop(Vector(h1, h2, hop(third, rhinesId, Vector(workId))), target = rhinesId)
        .copy(maxDepth = 3)
    errorsOf(revisit).map(_.message) shouldBe Vector("hops: a path visits each researcher once")
  }

  test("PathEvidence: a hop lists every supporting work, sorted, and its edge ID matches") {
    paths(
      errorsOf(pathEvidence.copy(hops = Vector(pathHop.copy(jointPublicationCount = 2))))
    ) shouldBe
      Vector("hops[0].supportingWorkIds")
    val unsorted = pathHop.copy(
      jointPublicationCount = 2,
      supportingWorkIds = Vector(workId, openAlexWorkId).sorted.reverse
    )
    paths(errorsOf(pathEvidence.copy(hops = Vector(unsorted)))) shouldBe
      Vector("hops[0].supportingWorkIds")
    errorsOf(pathEvidence.copy(hops = Vector(pathHop.copy(edgeId = "coauthor-edge:" + "0" * 64))))
      .collect { case e: StableIdMismatch => e.path } shouldBe Vector("hops[0].edgeId")
  }

  test("PathEvidence: NO_PATH and NOT_FOUND_WITHIN_DEPTH carry no hops and no length") {
    val none = pathEvidence.copy(status = PathStatus.NO_PATH, length = None, hops = Vector.empty)
    errorsOf(none) shouldBe empty
    errorsOf(none.copy(status = PathStatus.NOT_FOUND_WITHIN_DEPTH)) shouldBe empty
    paths(errorsOf(none.copy(length = Some(1)))) shouldBe Vector("length")
    paths(errorsOf(pathEvidence.copy(status = PathStatus.NO_PATH, length = None))) shouldBe
      Vector("hops")
    paths(errorsOf(pathEvidence.copy(hops = Vector.empty, length = Some(0)))) should contain("hops")
  }

  test("PathEvidence: the path ID is recomputed and endpoints must differ") {
    errorsOf(
      pathEvidence.copy(
        runId = otherRun,
        provenance = pathEvidence.provenance.copy(runId = otherRun)
      )
    )
      .collect { case e: StableIdMismatch => e.path } shouldBe Vector("pathId")
    paths(errorsOf(pathEvidence.copy(targetResearcherId = samueliId))) should contain(
      "targetResearcherId"
    )
  }

  // --- semantic facts -----------------------------------------------------------------------

  test("SemanticFact: subject kind, ranking order, path nodes and text bounds") {
    errorsOf(semanticFact) shouldBe empty
    paths(errorsOf(semanticFact.copy(subjectType = SemanticSubjectType.TOPIC))) shouldBe
      Vector("subjectId")
    val two = Vector(
      RankedEntity(topic.topicId, "a", 1),
      RankedEntity(ok(StableIds.topicId(TopicNamespace.OpenAlex, "T1")), "b", 3)
    )
    paths(errorsOf(semanticFact.copy(topTopics = two))) shouldBe Vector("topTopics")
    errorsOf(semanticFact.copy(topTopics = two.reverse)) shouldBe empty
    paths(
      errorsOf(semanticFact.copy(topVenues = Vector(RankedEntity(topic.topicId, "x", 1))))
    ) shouldBe
      Vector("topVenues[0].entityId")
    paths(
      errorsOf(semanticFact.copy(metrics = semanticFact.metrics :+ semanticFact.metrics.head))
    ) shouldBe Vector("metrics")
    val long = "x" * (SemanticFact.MaxTextCodePoints + 1)
    paths(errorsOf(semanticFact.copy(displayName = Some(long)))) shouldBe Vector("displayName")
    SemanticFact.truncateText(long).length shouldBe SemanticFact.MaxTextCodePoints
    paths(
      errorsOf(
        semanticFact.copy(representativeWorks =
          Vector(RepresentativeWork(workId, work.title, Some(2010)))
        )
      )
    ) shouldBe Vector("representativeWorks[0].year")
  }

  test("SemanticFact: a PATH fact lists at least two nodes in hop order") {
    val pathFact = semanticFact.copy(
      factId = ok(
        StableIds.semanticFactId(
          SemanticSubjectType.PATH,
          pathEvidence.pathId,
          2000,
          2005,
          analyticsMetricVersion,
          runId
        )
      ),
      subjectType = SemanticSubjectType.PATH,
      subjectId = pathEvidence.pathId,
      displayName = None,
      relatedResearcherIds = Vector(samueliId, rhinesId)
    )
    errorsOf(pathFact) shouldBe empty
    paths(errorsOf(pathFact.copy(relatedResearcherIds = Vector(samueliId)))) shouldBe
      Vector("relatedResearcherIds")
  }

  // --- load manifests -----------------------------------------------------------------------

  test("LoadManifest: record types and schema versions must be core contracts") {
    errorsOf(loadManifest) shouldBe empty
    val t = loadManifest.tables.head
    paths(
      errorsOf(loadManifest.copy(tables = Vector(t.copy(recordType = "SparkOnlyThing"))))
    ) shouldBe
      Vector("tables[0].recordType")
    errorsOf(loadManifest.copy(tables = Vector(t.copy(recordSchemaVersion = "coauthor-edge-v9"))))
      .collect { case e: UnsupportedSchemaVersion => e.path } shouldBe
      Vector("tables[0].recordSchemaVersion")
    paths(
      errorsOf(loadManifest.copy(tables = Vector(t.copy(tableName = "Coauthor-Edges"))))
    ) shouldBe
      Vector("tables[0].tableName")
    paths(errorsOf(loadManifest.copy(tables = Vector(t, t)))) shouldBe Vector("tables")
    paths(errorsOf(loadManifest.copy(tables = Vector.empty))) shouldBe Vector("tables")
  }

  test("LoadManifest: files carry checksums, are sorted and listed whenever there are rows") {
    val t = loadManifest.tables.head
    val f = t.files.head
    paths(
      errorsOf(
        loadManifest.copy(tables = Vector(t.copy(files = Vector(f.copy(checksum = "md5:x")))))
      )
    ) shouldBe Vector("tables[0].files[0].checksum")
    paths(errorsOf(loadManifest.copy(tables = Vector(t.copy(files = Vector.empty))))) shouldBe
      Vector("tables[0].files")
    errorsOf(
      loadManifest.copy(tables = Vector(t.copy(files = Vector.empty, rowCount = 0)))
    ) shouldBe
      empty
    val g = f.copy(path = f.path.replace("00000", "00001"))
    errorsOf(loadManifest.copy(tables = Vector(t.copy(files = Vector(f, g))))) shouldBe empty
    paths(errorsOf(loadManifest.copy(tables = Vector(t.copy(files = Vector(g, f)))))) shouldBe
      Vector("tables[0].files")
  }

  // --- identity conflicts -------------------------------------------------------------------

  private def conflictWith(
      details: Vector[IdentityConflictDetail],
      maxClusterSize: Int = 4
  ): IdentityConflict =
    identityConflict.copy(details = details, maxClusterSize = maxClusterSize)

  test("IdentityConflict: every required reason code is expressible") {
    val oa = Vector("A5023877407", "A5110371381")
    errorsOf(
      conflictWith(
        Vector(
          IdentityConflictDetail(IdentityConflictReason.SAME_WORK_MENTIONS, Vector(workId)),
          IdentityConflictDetail(
            IdentityConflictReason.MULTIPLE_ORCIDS,
            Vector("0000-0003-3175-3913", otherOrcid).sorted
          ),
          IdentityConflictDetail(IdentityConflictReason.MULTIPLE_OPENALEX_AUTHOR_IDS, oa),
          IdentityConflictDetail(IdentityConflictReason.CLUSTER_TOO_LARGE, Vector.empty)
        ),
        maxClusterSize = 1
      )
    ) shouldBe empty
  }

  test("IdentityConflict: CLUSTER_TOO_LARGE is present exactly when the cluster exceeds the max") {
    val tooLarge =
      Vector(IdentityConflictDetail(IdentityConflictReason.CLUSTER_TOO_LARGE, Vector.empty))
    errorsOf(conflictWith(tooLarge, maxClusterSize = 1)) shouldBe empty
    paths(errorsOf(conflictWith(tooLarge, maxClusterSize = 2))) shouldBe Vector("details")
    paths(errorsOf(identityConflict.copy(maxClusterSize = 1))) shouldBe Vector("details")
    paths(
      errorsOf(
        conflictWith(
          Vector(IdentityConflictDetail(IdentityConflictReason.CLUSTER_TOO_LARGE, Vector("x"))),
          maxClusterSize = 1
        )
      )
    ) shouldBe Vector("details[0].values")
  }

  test("IdentityConflict: values are validated per reason; details are ordered and unique") {
    paths(
      errorsOf(
        conflictWith(
          Vector(
            IdentityConflictDetail(IdentityConflictReason.MULTIPLE_ORCIDS, Vector(otherOrcid))
          )
        )
      )
    ) shouldBe Vector("details[0].values")
    paths(
      errorsOf(
        conflictWith(
          Vector(
            IdentityConflictDetail(
              IdentityConflictReason.MULTIPLE_ORCIDS,
              Vector(otherOrcid, "0000-0002-1825-0098")
            )
          )
        )
      )
    ) shouldBe Vector("details[0].values[1]")
    paths(errorsOf(conflictWith(identityConflict.details.reverse))) shouldBe Vector("details")
    paths(errorsOf(conflictWith(Vector.empty))) shouldBe Vector("details")
  }

  test("IdentityConflict: members are sorted, counted, and hashed into the conflict ID") {
    paths(errorsOf(identityConflict.copy(clusterSize = 3))) shouldBe Vector("clusterSize")
    paths(errorsOf(identityConflict.copy(memberIds = identityConflict.memberIds.reverse))) should
      contain("memberIds")
    errorsOf(identityConflict.copy(identityVersion = "v2")).collect { case e: StableIdMismatch =>
      e.path
    } shouldBe Vector("conflictId")
    paths(
      errorsOf(identityConflict.copy(memberIds = Vector(samueliMention.mentionId), clusterSize = 1))
    ) should
      contain("memberIds")
  }

  // --- window summaries and temporal change -------------------------------------------------

  test("WindowGraphSummary: counts, components and the community histogram are consistent") {
    errorsOf(windowGraphSummary) shouldBe empty
    paths(errorsOf(windowGraphSummary.copy(yearlessWorkCount = 1))) shouldBe
      Vector("yearlessWorkCount")
    errorsOf(windowGraphSummary.copy(yearlessWorkCount = 1, allYearsWindow = true)) shouldBe empty
    paths(errorsOf(windowGraphSummary.copy(largestComponentSize = 6))) shouldBe
      Vector("componentCount")
    paths(errorsOf(windowGraphSummary.copy(communityCount = Some(2)))) shouldBe
      Vector("communitySizeHistogram")
    paths(errorsOf(windowGraphSummary.copy(communityCount = None))) shouldBe
      Vector("communitySizeHistogram")
    val emptyWindow = windowGraphSummary.copy(
      researcherCount = 0,
      workCount = 0,
      coauthorEdgeCount = 0,
      componentCount = 0,
      largestComponentSize = 0,
      communityCount = Some(0),
      communitySizeHistogram = Vector.empty,
      maxCoreness = None
    )
    errorsOf(emptyWindow) shouldBe empty
    paths(
      errorsOf(
        windowGraphSummary.copy(iterations =
          Vector(IterationReport("label-propagation", 21, 20, converged = false))
        )
      )
    ) shouldBe Vector("iterations[0].iterations")
  }

  test("ResearcherWindowChange: ordered windows and at least one snapshot") {
    errorsOf(researcherWindowChange) shouldBe empty
    errorsOf(researcherWindowChange.copy(from = None, to = Some(windowSnapshot))) shouldBe empty
    paths(errorsOf(researcherWindowChange.copy(from = None))) shouldBe Vector("from,to")
    paths(errorsOf(researcherWindowChange.copy(toWindowStartYear = 2005))) shouldBe
      Vector("toWindowStartYear")
    paths(
      errorsOf(
        researcherWindowChange.copy(from =
          Some(windowSnapshot.copy(participationCoefficient = Some(2.0)))
        )
      )
    ) shouldBe Vector("from.participationCoefficient")
  }

  // --- shared rules -------------------------------------------------------------------------

  test("every analytics record rejects a run ID that differs from its provenance") {
    Vector[Vector[ValidationError]](
      errorsOf(componentMembership.copy(runId = otherRun)),
      errorsOf(communityMembership.copy(runId = otherRun)),
      errorsOf(crossCommunityEdge.copy(runId = otherRun)),
      errorsOf(bridgeMeasures.copy(runId = otherRun)),
      errorsOf(coreness.copy(runId = otherRun)),
      errorsOf(loadManifest.copy(runId = otherRun)),
      errorsOf(identityConflict.copy(runId = otherRun)),
      errorsOf(windowGraphSummary.copy(runId = otherRun)),
      errorsOf(researcherWindowChange.copy(runId = otherRun))
    ).foreach(e => invalidAt(e, "runId") shouldBe true)
  }

  test("a decoded analytics record without provenance is rejected") {
    val json = editObj(goldenJson("coreness.json"))(_.remove("provenance"))
    WireCodec.decode[CorenessRecord](json).left.toOption.get.collect { case e: MissingProvenance =>
      e
    } should have size 1
  }
