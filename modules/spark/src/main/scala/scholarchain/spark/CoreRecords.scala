package scholarchain.spark

import org.apache.spark.sql.api.java.UDF1
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions.{col, struct, udf}
import org.apache.spark.sql.types.StringType
import org.apache.spark.sql.{Column, DataFrame, Row}
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.core.validation.RecordContract

/** Explicit mapping between Spark rows and core records, used only at the edges: in tests, for
  * the small window-summary output, and by the distributed contract check below. Spark tables
  * stay untyped (Scala 3 has no TypeTags for case-class encoders).
  */
object CoreRecords:
  private def str(r: Row, f: String): String = r.getAs[String](f)
  private def opt(r: Row, f: String): Option[String] = Option(r.getAs[String](f))
  private def int(r: Row, f: String): Int = r.getAs[java.lang.Integer](f).intValue
  private def optInt(r: Row, f: String): Option[Int] =
    Option(r.getAs[java.lang.Integer](f)).map(_.intValue)
  private def long(r: Row, f: String): Long = r.getAs[java.lang.Number](f).longValue
  private def dbl(r: Row, f: String): Double = r.getAs[java.lang.Number](f).doubleValue
  private def strs(r: Row, f: String): Vector[String] =
    Option(r.getAs[scala.collection.Seq[String]](f)).map(_.toVector).getOrElse(Vector.empty)

  def provenance(r: Row): Provenance =
    if r == null then null
    else
      Provenance(
        schemaVersion = str(r, "schemaVersion"),
        sourceDataset = Option(str(r, "sourceDataset")).map(SourceType.valueOf).orNull,
        sourceRecordId = str(r, "sourceRecordId"),
        snapshot = str(r, "snapshot"),
        runId = str(r, "runId"),
        policyVersion = opt(r, "policyVersion"),
        modelVersion = opt(r, "modelVersion"),
        promptVersion = opt(r, "promptVersion"),
        derivedFrom = strs(r, "derivedFrom")
      )

  private def prov(r: Row): Provenance = provenance(r.getAs[Row]("provenance"))

  def coauthorEdge(r: Row): CoauthorEdge = CoauthorEdge(
    schemaVersion = str(r, "schemaVersion"),
    edgeId = str(r, "edgeId"),
    researcherA = str(r, "researcherA"),
    researcherB = str(r, "researcherB"),
    windowStartYear = int(r, "windowStartYear"),
    windowEndYear = int(r, "windowEndYear"),
    jointPublicationCount = long(r, "jointPublicationCount"),
    firstCollaborationYear = int(r, "firstCollaborationYear"),
    lastCollaborationYear = int(r, "lastCollaborationYear"),
    representativeWorkIds = strs(r, "representativeWorkIds"),
    supportingWorkIdsRef = opt(r, "supportingWorkIdsRef"),
    policyVersion = str(r, "policyVersion"),
    provenance = prov(r)
  )

  def edgeSupport(r: Row): CoauthorEdgeSupport = CoauthorEdgeSupport(
    schemaVersion = str(r, "schemaVersion"),
    edgeId = str(r, "edgeId"),
    researcherA = str(r, "researcherA"),
    researcherB = str(r, "researcherB"),
    windowStartYear = int(r, "windowStartYear"),
    windowEndYear = int(r, "windowEndYear"),
    workId = str(r, "workId"),
    workYear = optInt(r, "workYear"),
    policyVersion = str(r, "policyVersion"),
    runId = str(r, "runId"),
    provenance = prov(r)
  )

  def researcherMetrics(r: Row): ResearcherMetrics = ResearcherMetrics(
    schemaVersion = str(r, "schemaVersion"),
    metricsId = str(r, "metricsId"),
    researcherId = str(r, "researcherId"),
    windowStartYear = int(r, "windowStartYear"),
    windowEndYear = int(r, "windowEndYear"),
    publicationCount = long(r, "publicationCount"),
    distinctCoauthorCount = long(r, "distinctCoauthorCount"),
    totalCoauthorOccurrences = long(r, "totalCoauthorOccurrences"),
    averageCoauthorsPerPaper = dbl(r, "averageCoauthorsPerPaper"),
    maximumCoauthorsOnOnePaper = long(r, "maximumCoauthorsOnOnePaper"),
    maximumTeamWorkIds = strs(r, "maximumTeamWorkIds"),
    weightedDegree = dbl(r, "weightedDegree"),
    componentId = str(r, "componentId"),
    componentSize = long(r, "componentSize"),
    communityId = opt(r, "communityId"),
    bridgeMetric = opt(r, "bridgeMetric").map(CollaborationMetric.valueOf),
    bridgeScore = Option(r.getAs[java.lang.Double]("bridgeScore")).map(_.doubleValue),
    coreness = optInt(r, "coreness"),
    metricVersion = str(r, "metricVersion"),
    provenance = prov(r)
  )

  def componentMembership(r: Row): ComponentMembership = ComponentMembership(
    schemaVersion = str(r, "schemaVersion"),
    researcherId = str(r, "researcherId"),
    componentId = str(r, "componentId"),
    componentSize = long(r, "componentSize"),
    windowStartYear = int(r, "windowStartYear"),
    windowEndYear = int(r, "windowEndYear"),
    metricVersion = str(r, "metricVersion"),
    runId = str(r, "runId"),
    provenance = prov(r)
  )

  def identityConflict(r: Row): IdentityConflict = IdentityConflict(
    schemaVersion = str(r, "schemaVersion"),
    conflictId = str(r, "conflictId"),
    memberIds = strs(r, "memberIds"),
    clusterSize = int(r, "clusterSize"),
    maxClusterSize = int(r, "maxClusterSize"),
    details = Option(r.getAs[scala.collection.Seq[Row]]("details"))
      .map(_.toVector)
      .getOrElse(Vector.empty)
      .map(d =>
        IdentityConflictDetail(IdentityConflictReason.valueOf(str(d, "reason")), strs(d, "values"))
      ),
    matchDecisionIds = strs(r, "matchDecisionIds"),
    identityVersion = str(r, "identityVersion"),
    policyVersion = str(r, "policyVersion"),
    runId = str(r, "runId"),
    provenance = prov(r)
  )

  def researcher(r: Row): CanonicalResearcher = CanonicalResearcher(
    schemaVersion = str(r, "schemaVersion"),
    researcherId = str(r, "researcherId"),
    displayName = str(r, "displayName"),
    orcid = opt(r, "orcid"),
    dblpPid = opt(r, "dblpPid"),
    openAlexAuthorId = opt(r, "openAlexAuthorId"),
    dblpAuthorKeys = strs(r, "dblpAuthorKeys"),
    identityVersion = opt(r, "identityVersion"),
    affiliationInstitutionIds = strs(r, "affiliationInstitutionIds"),
    identityConfidence = dbl(r, "identityConfidence"),
    provenance = prov(r)
  )

  def authorship(r: Row): AuthorshipRecord = AuthorshipRecord(
    schemaVersion = str(r, "schemaVersion"),
    authorshipId = str(r, "authorshipId"),
    researcherId = str(r, "researcherId"),
    workId = str(r, "workId"),
    mentionId = str(r, "mentionId"),
    position = int(r, "position"),
    year = optInt(r, "year"),
    matchConfidence = dbl(r, "matchConfidence"),
    matchDecisionId = opt(r, "matchDecisionId"),
    provenance = prov(r)
  )

  def rejectRecord(r: Row): RejectRecord = RejectRecord(
    schemaVersion = str(r, "schemaVersion"),
    source = SourceType.valueOf(str(r, "source")),
    sourceRecordRef = str(r, "sourceRecordRef"),
    stage = str(r, "stage"),
    reasonCode = str(r, "reasonCode"),
    rawExcerpt = str(r, "rawExcerpt"),
    runId = str(r, "runId"),
    provenance = prov(r)
  )

  def graphEdge(r: Row): GraphEdge = GraphEdge(
    schemaVersion = str(r, "schemaVersion"),
    edgeId = str(r, "edgeId"),
    fromId = str(r, "fromId"),
    edgeType = EdgeType.valueOf(str(r, "edgeType")),
    toId = str(r, "toId"),
    properties = Option(r.getAs[scala.collection.Map[String, String]]("properties"))
      .map(_.toMap)
      .getOrElse(Map.empty),
    policyVersion = str(r, "policyVersion"),
    sourceEvidenceIds = strs(r, "sourceEvidenceIds"),
    provenance = prov(r)
  )

  private def optLong(r: Row, f: String): Option[Long] =
    Option(r.getAs[java.lang.Number](f)).map(_.longValue)
  private def optDbl(r: Row, f: String): Option[Double] =
    Option(r.getAs[java.lang.Number](f)).map(_.doubleValue)
  private def rows(r: Row, f: String): Vector[Row] =
    Option(r.getAs[scala.collection.Seq[Row]](f)).map(_.toVector).getOrElse(Vector.empty)

  def communityMembership(r: Row): CommunityMembership = CommunityMembership(
    schemaVersion = str(r, "schemaVersion"),
    researcherId = str(r, "researcherId"),
    communityId = str(r, "communityId"),
    communitySize = long(r, "communitySize"),
    windowStartYear = int(r, "windowStartYear"),
    windowEndYear = int(r, "windowEndYear"),
    metricVersion = str(r, "metricVersion"),
    runId = str(r, "runId"),
    provenance = prov(r)
  )

  def crossCommunityEdge(r: Row): CrossCommunityEdge = CrossCommunityEdge(
    schemaVersion = str(r, "schemaVersion"),
    edgeId = str(r, "edgeId"),
    researcherA = str(r, "researcherA"),
    researcherB = str(r, "researcherB"),
    communityA = str(r, "communityA"),
    communityB = str(r, "communityB"),
    windowStartYear = int(r, "windowStartYear"),
    windowEndYear = int(r, "windowEndYear"),
    jointPublicationCount = long(r, "jointPublicationCount"),
    policyVersion = str(r, "policyVersion"),
    metricVersion = str(r, "metricVersion"),
    runId = str(r, "runId"),
    provenance = prov(r)
  )

  def bridgeMeasures(r: Row): BridgeMeasures = BridgeMeasures(
    schemaVersion = str(r, "schemaVersion"),
    researcherId = str(r, "researcherId"),
    communityId = str(r, "communityId"),
    windowStartYear = int(r, "windowStartYear"),
    windowEndYear = int(r, "windowEndYear"),
    degree = long(r, "degree"),
    crossCommunityDegree = long(r, "crossCommunityDegree"),
    communitiesConnected = long(r, "communitiesConnected"),
    participationCoefficient = dbl(r, "participationCoefficient"),
    betweenness = optDbl(r, "betweenness"),
    betweennessMethod = opt(r, "betweennessMethod").map(BetweennessMethod.valueOf),
    betweennessSampleSources = optInt(r, "betweennessSampleSources"),
    betweennessSeed = optLong(r, "betweennessSeed"),
    metricVersion = str(r, "metricVersion"),
    runId = str(r, "runId"),
    provenance = prov(r)
  )

  def coreness(r: Row): CorenessRecord = CorenessRecord(
    schemaVersion = str(r, "schemaVersion"),
    researcherId = str(r, "researcherId"),
    windowStartYear = int(r, "windowStartYear"),
    windowEndYear = int(r, "windowEndYear"),
    coreness = int(r, "coreness"),
    degree = long(r, "degree"),
    metricVersion = str(r, "metricVersion"),
    runId = str(r, "runId"),
    provenance = prov(r)
  )

  def pathHop(h: Row): PathHop = PathHop(
    fromResearcherId = str(h, "fromResearcherId"),
    toResearcherId = str(h, "toResearcherId"),
    edgeId = str(h, "edgeId"),
    jointPublicationCount = long(h, "jointPublicationCount"),
    supportingWorkIds = strs(h, "supportingWorkIds")
  )

  def pathEvidence(r: Row): PathEvidence = PathEvidence(
    schemaVersion = str(r, "schemaVersion"),
    pathId = str(r, "pathId"),
    sourceResearcherId = str(r, "sourceResearcherId"),
    targetResearcherId = str(r, "targetResearcherId"),
    windowStartYear = int(r, "windowStartYear"),
    windowEndYear = int(r, "windowEndYear"),
    selection = PathSelection.valueOf(str(r, "selection")),
    status = PathStatus.valueOf(str(r, "status")),
    maxDepth = int(r, "maxDepth"),
    length = optInt(r, "length"),
    hops = rows(r, "hops").map(pathHop),
    policyVersion = str(r, "policyVersion"),
    runId = str(r, "runId"),
    provenance = prov(r)
  )

  private def rankedEntities(r: Row, f: String): Vector[RankedEntity] =
    rows(r, f).map(e => RankedEntity(str(e, "entityId"), str(e, "label"), long(e, "count")))

  def semanticFact(r: Row): SemanticFact = SemanticFact(
    schemaVersion = str(r, "schemaVersion"),
    factId = str(r, "factId"),
    subjectType = SemanticSubjectType.valueOf(str(r, "subjectType")),
    subjectId = str(r, "subjectId"),
    displayName = opt(r, "displayName"),
    windowStartYear = int(r, "windowStartYear"),
    windowEndYear = int(r, "windowEndYear"),
    metrics = rows(r, "metrics").map(m =>
      MetricValue(CollaborationMetric.valueOf(str(m, "metric")), dbl(m, "value"))
    ),
    topTopics = rankedEntities(r, "topTopics"),
    topVenues = rankedEntities(r, "topVenues"),
    topInstitutions = rankedEntities(r, "topInstitutions"),
    relatedResearcherIds = strs(r, "relatedResearcherIds"),
    representativeWorks = rows(r, "representativeWorks").map(w =>
      RepresentativeWork(str(w, "workId"), str(w, "title"), optInt(w, "year"))
    ),
    metricVersion = str(r, "metricVersion"),
    runId = str(r, "runId"),
    provenance = prov(r)
  )

  private def snapshot(s: Row): ResearcherWindowSnapshot = ResearcherWindowSnapshot(
    publicationCount = long(s, "publicationCount"),
    distinctCoauthorCount = long(s, "distinctCoauthorCount"),
    averageCoauthorsPerPaper = dbl(s, "averageCoauthorsPerPaper"),
    maximumCoauthorsOnOnePaper = long(s, "maximumCoauthorsOnOnePaper"),
    componentSize = long(s, "componentSize"),
    communitySize = optLong(s, "communitySize"),
    crossCommunityDegree = optLong(s, "crossCommunityDegree"),
    communitiesConnected = optLong(s, "communitiesConnected"),
    participationCoefficient = optDbl(s, "participationCoefficient"),
    coreness = optInt(s, "coreness")
  )

  def windowChange(r: Row): ResearcherWindowChange = ResearcherWindowChange(
    schemaVersion = str(r, "schemaVersion"),
    researcherId = str(r, "researcherId"),
    fromWindowStartYear = int(r, "fromWindowStartYear"),
    fromWindowEndYear = int(r, "fromWindowEndYear"),
    toWindowStartYear = int(r, "toWindowStartYear"),
    toWindowEndYear = int(r, "toWindowEndYear"),
    from = Option(r.getAs[Row]("from")).map(snapshot),
    to = Option(r.getAs[Row]("to")).map(snapshot),
    metricVersion = str(r, "metricVersion"),
    runId = str(r, "runId"),
    provenance = prov(r)
  )

  /** A record kind whose rows can be checked against its core contract. */
  enum Kind:
    case Edge, EdgeSupport, Metrics, Component, Conflict, Researcher, Authorship, Reject, HasTopic,
      Community, CrossEdge, Bridge, Coreness, Path, Fact, WindowChange

  private def check[A](a: => A)(using c: RecordContract[A]): String =
    try
      WireCodec.validate(a) match
        case Right(_) => null
        case Left(errors) => errors.mkString("; ")
    catch case e: RuntimeException => s"row does not map to the core record: $e"

  /** Violations of one row (null when valid). Runs on executors, no shared state. */
  def violations(kind: Kind, r: Row): String = kind match
    case Kind.Edge => check(coauthorEdge(r))
    case Kind.EdgeSupport => check(edgeSupport(r))
    case Kind.Metrics => check(researcherMetrics(r))
    case Kind.Component => check(componentMembership(r))
    case Kind.Conflict => check(identityConflict(r))
    case Kind.Researcher => check(researcher(r))
    case Kind.Authorship => check(authorship(r))
    case Kind.Reject => check(rejectRecord(r))
    case Kind.HasTopic => check(graphEdge(r))
    case Kind.Community => check(communityMembership(r))
    case Kind.CrossEdge => check(crossCommunityEdge(r))
    case Kind.Bridge => check(bridgeMeasures(r))
    case Kind.Coreness => check(coreness(r))
    case Kind.Path => check(pathEvidence(r))
    case Kind.Fact => check(semanticFact(r))
    case Kind.WindowChange => check(windowChange(r))

  private def violationsUdf(kind: Kind): UserDefinedFunction =
    val name = kind.toString
    udf(
      new UDF1[Row, String]:
        def call(r: Row): String = violations(Kind.valueOf(name), r)
      ,
      StringType
    )

  /** `df` with a `contractViolation` column (null when the row satisfies its core contract). */
  def withViolations(df: DataFrame, kind: Kind): DataFrame =
    val all: Column = struct(df.columns.toSeq.map(col)*)
    df.withColumn("contractViolation", violationsUdf(kind)(all))
