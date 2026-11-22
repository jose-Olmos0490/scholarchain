package scholarchain.spark.graph

import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.{ArrayType, StringType}
import org.apache.spark.sql.{Column, DataFrame, SparkSession}
import scholarchain.core.model.CoauthorEdgeSupport
import scholarchain.core.schema.SchemaVersions
import scholarchain.spark.identity.IdentityResolver
import scholarchain.spark.{AnalysisWindow, Lineage, Schemas, Udfs, Versions, Windows}

/** The Hadoop export tables and entity-resolution decisions the PART A job consumes (already read
  * with the explicit schemas in [[Schemas]]).
  */
final case class GraphInputs(
    authorPaper: DataFrame,
    coauthorPairs: DataFrame,
    works: DataFrame,
    authorMentions: DataFrame,
    oversizeTeams: DataFrame,
    venues: DataFrame,
    topics: DataFrame,
    institutions: DataFrame,
    graphEdges: DataFrame,
    decisions: DataFrame
)

/** Parameters of one build. Every value comes from configuration (see
  * [[scholarchain.spark.SparkAnalyticsSettings]]) or from the run manifest (run ID, snapshot).
  *
  * @param scope
  *   the DBLP subset years; recorded as the window of non-windowed tables
  */
final case class BuildParams(
    runId: String,
    snapshot: String,
    windows: Vector[AnalysisWindow],
    scope: AnalysisWindow,
    coauthorPolicyVersion: String,
    representativeWorkLimit: Int,
    identityVersion: String,
    maxClusterSize: Int,
    componentsMaxIterations: Int,
    checkpointInterval: Int,
    checkpoint: Boolean
)

/** All PART A outputs. Every frame carries runId, windowStartYear, windowEndYear, schemaVersion. */
final case class GraphOutputs(
    identity: IdentityResolver.Output,
    researchers: DataFrame,
    works: DataFrame,
    authored: DataFrame,
    venues: DataFrame,
    topics: DataFrame,
    institutions: DataFrame,
    hasTopic: DataFrame,
    rejects: DataFrame,
    edges: DataFrame,
    edgeSupport: DataFrame,
    metrics: DataFrame,
    wtInvariant: DataFrame,
    components: DataFrame,
    componentRun: ConnectedComponents.Result,
    edgeAggregationPlan: DataFrame,
    selfPairs: DataFrame,
    researcherPapers: DataFrame,
    windowFrame: DataFrame,
    researcherInstitutions: DataFrame
)

/** PART A jobs 1-4: canonical tables, temporal weighted coauthorship graph, collaboration metrics
  * and connected components. Definitions, partitioning and shuffle boundaries are documented in
  * modules/spark/JOBS.md; nothing here collects a node, edge or path table to the driver.
  */
object GraphBuilder:
  val PairSchemaVersions: Set[String] =
    Set(SchemaVersions.CoauthorPairCandidate, SchemaVersions.CoauthorPairCandidateV2)

  // W_r/T_r invariant row status.
  val WtEqual = "EQUAL"
  val WtExceptionOversize = "EXCEPTION_OVERSIZE_TEAM"
  val WtExceptionYearless = "EXCEPTION_YEARLESS_WORK"
  val WtExceptionBoth = "EXCEPTION_OVERSIZE_TEAM_AND_YEARLESS_WORK"
  val WtFailure = "INVARIANT_FAILURE"

  private def scopeCols(p: BuildParams): Seq[Column] =
    Seq(
      lit(p.scope.startYear).as(Windows.Start),
      lit(p.scope.endYear).as(Windows.End),
      lit(p.runId).as("runId")
    )

  /** A `reject-v1` row (core `RejectRecord`) for a rejected input of `source`. */
  private def reject(
      p: BuildParams,
      source: String,
      ref: Column,
      reason: String,
      excerpt: Column
  ): Seq[Column] =
    Seq(
      lit(SchemaVersions.RejectRecord).as("schemaVersion"),
      lit(source).as("source"),
      ref.as("sourceRecordRef"),
      lit(Versions.RejectStage).as("stage"),
      lit(reason).as("reasonCode"),
      substring(coalesce(excerpt, lit("")), 1, 1024).as("rawExcerpt"),
      lit(p.runId).as("runId"),
      struct(
        lit(SchemaVersions.Provenance).as("schemaVersion"),
        lit(source).as("sourceDataset"),
        ref.as("sourceRecordId"),
        lit(p.snapshot).as("snapshot"),
        lit(p.runId).as("runId"),
        lit(null).cast(StringType).as("policyVersion"),
        lit(null).cast(StringType).as("modelVersion"),
        lit(null).cast(StringType).as("promptVersion"),
        Schemas.emptyStrings.as("derivedFrom")
      ).as("provenance"),
      lit(p.scope.startYear).as(Windows.Start),
      lit(p.scope.endYear).as(Windows.End)
    )

  def build(spark: SparkSession, in: GraphInputs, p: BuildParams): GraphOutputs =
    val windowFrame = Windows.frame(spark, p.windows)

    // ---------------------------------------------------------------- 1. identity
    val apMentions = in.authorPaper.select(
      col("mentionId"),
      col("workId"),
      col("provenance.sourceDataset").as("sourceDataset"),
      col("sourceAuthorKey"),
      col("orcid")
    )
    val mentions = in.authorMentions
      .select(
        col("mentionId"),
        col("workId"),
        col("provenance.sourceDataset").as("sourceDataset"),
        col("sourceAuthorKey"),
        col("orcid")
      )
      .unionByName(
        apMentions.join(in.authorMentions.select("mentionId"), Seq("mentionId"), "left_anti")
      )
    val identity = IdentityResolver.resolve(
      mentions,
      in.decisions,
      IdentityResolver.Params(
        p.identityVersion,
        p.maxClusterSize,
        p.componentsMaxIterations,
        p.checkpointInterval,
        p.checkpoint,
        p.runId,
        p.snapshot,
        p.scope.startYear,
        p.scope.endYear
      )
    )
    val mid = identity.mentionIdentity

    // ---------------------------------------------------------------- 1. canonical tables
    val dblpWorks = in.works.where(col("source") === "DBLP")
    val workLinks = in.decisions
      .where(
        col("decision") === "ACCEPTED" && col("sourceId").startsWith("work:") &&
          col("targetId").startsWith("work:")
      )
      .select("decisionId", "sourceId", "targetId")
    val workLinkEnds = workLinks
      .select(col("decisionId"), col("sourceId").as("workId"), col("targetId").as("otherWorkId"))
      .union(
        workLinks.select(
          col("decisionId"),
          col("targetId").as("workId"),
          col("sourceId").as("otherWorkId")
        )
      )
    val linked = workLinkEnds
      .groupBy("workId")
      .agg(array_sort(collect_set("otherWorkId")).as("linkedWorkIds"))
    val works = dblpWorks
      .join(linked, Seq("workId"), "left")
      .withColumn("linkedWorkIds", coalesce(col("linkedWorkIds"), Schemas.emptyStrings))
      .select((dblpWorks.columns.map(col).toSeq :+ col("linkedWorkIds")) ++ scopeCols(p)*)

    // AUTHORED: author-paper rows, one per mention; integrity-checked.
    val apValid = in.authorPaper.where(col("schemaVersion") === SchemaVersions.AuthorPaperRecord)
    val apBadSchema = in.authorPaper.where(
      col("schemaVersion").isNull || col("schemaVersion") =!= SchemaVersions.AuthorPaperRecord
    )
    val rank = row_number().over(
      org.apache.spark.sql.expressions.Window
        .partitionBy("mentionId")
        .orderBy(col("workId"), col("position"))
    )
    val apRanked = apValid.withColumn("rk", rank)
    val apDup = apRanked.where(col("rk") > 1)
    val apOne = apRanked.where(col("rk") === 1).drop("rk")
    val workIds = dblpWorks.select("workId")
    val apNoWork = apOne.join(workIds, Seq("workId"), "left_anti")
    val apWithWork = apOne.join(workIds, Seq("workId"), "left_semi")
    val apNoResearcher = apWithWork.join(mid.select("mentionId"), Seq("mentionId"), "left_anti")
    val authoredBase = apWithWork.join(mid, Seq("mentionId"), "inner")
    val authored = Lineage.cut(
      authoredBase.select(
        lit(SchemaVersions.AuthorshipRecord).as("schemaVersion"),
        Udfs.authorshipId(col("workId"), col("mentionId"), col("researcherId")).as("authorshipId"),
        col("researcherId"),
        col("workId"),
        col("mentionId"),
        col("position"),
        col("year"),
        col("identityConfidence").as("matchConfidence"),
        col("matchDecisionId"),
        Schemas
          .derivedProvenance(
            col("mentionId"),
            p.snapshot,
            p.runId,
            Some(Versions.IdentityRule),
            array(col("workId"))
          )
          .as("provenance"),
        col("teamSize"),
        col("idSource"),
        col("identityStatus"),
        lit(p.scope.startYear).as(Windows.Start),
        lit(p.scope.endYear).as(Windows.End),
        lit(p.runId).as("runId")
      ),
      p.checkpoint
    )

    // Researchers: one row per canonical researcher that authors at least one graph work.
    val members = identity.groups.select("grp", "memberIds", "status")
    val affiliations = in.graphEdges
      .where(col("edgeType") === "AFFILIATED_WITH")
      .select(col("fromId").as("mentionId"), col("toId").as("institutionId"))
    val mergedGroupResearcher = mid
      .where(col("identityStatus") === IdentityResolver.Merged)
      .select("grp", "researcherId")
      .distinct()
    val affiliatedMentions = mergedGroupResearcher
      .join(members.select(col("grp"), explode(col("memberIds")).as("mentionId")), "grp")
      .join(affiliations, "mentionId")
    // Per researcher and institution: distinct affiliated mentions (semantic-fact counts).
    val researcherInstitutions = affiliatedMentions
      .groupBy("researcherId", "institutionId")
      .agg(countDistinct("mentionId").as("mentionCount"))
    val researcherAffiliations = affiliatedMentions
      .groupBy("researcherId")
      .agg(array_sort(collect_set("institutionId")).as("affiliationInstitutionIds"))
    val authoringMentions = authoredBase.select("mentionId")
    val researchers = mid
      .join(authoringMentions, Seq("mentionId"), "left_semi")
      .groupBy("researcherId")
      .agg(
        min("dblpAuthorKey").as("displayName"),
        min("orcid").as("orcid"),
        min("openAlexAuthorId").as("openAlexAuthorId"),
        array_sort(collect_set("dblpAuthorKey")).as("dblpAuthorKeys"),
        min("identityConfidence").as("identityConfidence"),
        min("idSource").as("idSource"),
        min("identityStatus").as("identityStatus"),
        countDistinct("idSource").as("idSourceCount"),
        countDistinct("mentionId").as("mentionCount"),
        min("mentionId").as("firstMentionId"),
        array_sort(array_distinct(flatten(collect_list("groupDecisionIds")))).as("decisionIds")
      )
      .join(researcherAffiliations, Seq("researcherId"), "left")
      .select(
        lit(SchemaVersions.CanonicalResearcher).as("schemaVersion"),
        col("researcherId"),
        col("displayName"),
        col("orcid"),
        lit(null).cast(StringType).as("dblpPid"),
        col("openAlexAuthorId"),
        col("dblpAuthorKeys"),
        lit(p.identityVersion).as("identityVersion"),
        coalesce(col("affiliationInstitutionIds"), Schemas.emptyStrings)
          .as("affiliationInstitutionIds"),
        col("identityConfidence"),
        Schemas
          .derivedProvenance(
            col("firstMentionId"),
            p.snapshot,
            p.runId,
            Some(Versions.IdentityRule),
            col("decisionIds")
          )
          .as("provenance"),
        col("idSource"),
        col("identityStatus"),
        col("idSourceCount"),
        col("mentionCount"),
        lit(p.scope.startYear).as(Windows.Start),
        lit(p.scope.endYear).as(Windows.End),
        lit(p.runId).as("runId")
      )

    // Pass-through enrichment node tables (whatever the inputs contain; nothing invented).
    def passThrough(df: DataFrame): DataFrame =
      df.select(df.columns.map(col).toSeq ++ scopeCols(p)*)
    val venues = passThrough(in.venues)
    val topics = passThrough(in.topics)
    val institutions = passThrough(in.institutions)

    // HAS_TOPIC re-anchored from OpenAlex works to DBLP works through ACCEPTED work links.
    val topicIds = in.topics.select("topicId")
    val oaTopic = in.graphEdges
      .where(col("edgeType") === "HAS_TOPIC")
      .select(
        col("edgeId").as("sourceEdgeId"),
        col("fromId").as("otherWorkId"),
        col("toId").as("topicId")
      )
    val reanchored = oaTopic
      .join(workLinkEnds, "otherWorkId")
      .join(workIds, Seq("workId"), "left_semi")
    val hasTopicMissing = reanchored.join(topicIds, Seq("topicId"), "left_anti")
    val hasTopic = reanchored
      .join(topicIds, Seq("topicId"), "left_semi")
      .groupBy("workId", "topicId")
      .agg(
        array_sort(
          array_distinct(flatten(collect_list(array(col("decisionId"), col("sourceEdgeId")))))
        )
          .as("evidence"),
        array_sort(collect_set("otherWorkId")).as("sourceWorkIds"),
        min("sourceEdgeId").as("firstSourceEdgeId")
      )
      .select(
        lit(SchemaVersions.GraphEdge).as("schemaVersion"),
        Udfs.hasTopicEdgeId(col("workId"), col("topicId"), col("evidence")).as("edgeId"),
        col("workId").as("fromId"),
        lit("HAS_TOPIC").as("edgeType"),
        col("topicId").as("toId"),
        map(
          lit("assertedBy"),
          lit("openalex-via-accepted-work-link"),
          lit("sourceWorkIds"),
          concat_ws(",", col("sourceWorkIds"))
        ).as("properties"),
        lit(Versions.HasTopicPolicy).as("policyVersion"),
        col("evidence").as("sourceEvidenceIds"),
        Schemas
          .derivedProvenance(
            col("firstSourceEdgeId"),
            p.snapshot,
            p.runId,
            Some(Versions.HasTopicPolicy),
            col("evidence")
          )
          .as("provenance"),
        lit(p.scope.startYear).as(Windows.Start),
        lit(p.scope.endYear).as(Windows.End),
        lit(p.runId).as("runId")
      )

    // ---------------------------------------------------------------- 2. coauthorship graph
    val pairsAll = in.coauthorPairs
    val pairsBadSchema = pairsAll.where(
      col("schemaVersion").isNull || !col("schemaVersion").isin(PairSchemaVersions.toSeq*)
    )
    val pairsOk = pairsAll.where(col("schemaVersion").isin(PairSchemaVersions.toSeq*))
    val pairsNoYear = pairsOk.where(col("year").isNull)
    val pairsYear = pairsOk.where(col("year").isNotNull)
    // Pairs are mapped through AUTHORED (same mention AND same work). The window year is the
    // work's year from AUTHORED, so edges and metrics use one year per work; a pair whose own
    // year disagrees is rejected.
    val authoredKey = authored.select("mentionId", "workId", "researcherId", "year")
    val mappedAll = pairsYear
      .join(
        authoredKey
          .withColumnRenamed("mentionId", "memberA")
          .withColumnRenamed("researcherId", "rA")
          .withColumnRenamed("year", "workYearA"),
        Seq("memberA", "workId")
      )
      .join(
        authoredKey
          .drop("year")
          .withColumnRenamed("mentionId", "memberB")
          .withColumnRenamed("researcherId", "rB"),
        Seq("memberB", "workId")
      )
    val pairsYearMismatch = mappedAll.where(
      col("workYearA").isNull || col("workYearA") =!= col("year")
    )
    val mapped = Lineage.cut(
      mappedAll
        .where(col("workYearA") === col("year"))
        .select(
          col("candidateId"),
          col("workId"),
          col("year"),
          col("rA"),
          col("rB"),
          (col("rA") === col("rB")).as("selfPair")
        ),
      p.checkpoint
    )
    val pairsUnmapped =
      pairsYear.join(mappedAll.select("candidateId"), Seq("candidateId"), "left_anti")

    val pairsW = Windows.assign(mapped, "year", windowFrame)
    val selfPairs = pairsW.where(col("selfPair"))
    val supportBase = pairsW
      .where(!col("selfPair"))
      .select(
        col(Windows.Start),
        col(Windows.End),
        least(col("rA"), col("rB")).as("researcherA"),
        greatest(col("rA"), col("rB")).as("researcherB"),
        col("workId"),
        col("year")
      )
      // Duplicate pair rows of one work (and two mention pairs of one work that resolve to the
      // same researcher pair) collapse here: jointPublicationCount counts DISTINCT works.
      .groupBy(Windows.Start, Windows.End, "researcherA", "researcherB", "workId")
      .agg(min("year").as("workYear"))
    val edgeId =
      Udfs.coauthorEdgeId(
        col("researcherA"),
        col("researcherB"),
        col(Windows.Start),
        col(Windows.End),
        lit(p.coauthorPolicyVersion)
      )
    val edgeAgg = supportBase
      .groupBy(Windows.Start, Windows.End, "researcherA", "researcherB")
      .agg(
        count(lit(1)).as("jointPublicationCount"),
        min("workYear").as("firstCollaborationYear"),
        max("workYear").as("lastCollaborationYear"),
        array_sort(collect_list("workId")).as("supportWorkIds")
      )
    val edgesPlan = edgeAgg
      .withColumn("edgeId", edgeId)
      .select(
        lit(SchemaVersions.CoauthorEdge).as("schemaVersion"),
        col("edgeId"),
        col("researcherA"),
        col("researcherB"),
        col(Windows.Start),
        col(Windows.End),
        col("jointPublicationCount"),
        col("firstCollaborationYear"),
        col("lastCollaborationYear"),
        slice(col("supportWorkIds"), 1, p.representativeWorkLimit).as("representativeWorkIds"),
        concat(lit(SchemaVersions.CoauthorEdgeSupport + ":"), col("edgeId"))
          .as("supportingWorkIdsRef"),
        lit(p.coauthorPolicyVersion).as("policyVersion"),
        Schemas
          .derivedProvenance(
            element_at(col("supportWorkIds"), 1),
            p.snapshot,
            p.runId,
            Some(p.coauthorPolicyVersion),
            slice(col("supportWorkIds"), 1, p.representativeWorkLimit)
          )
          .as("provenance"),
        lit(p.runId).as("runId")
      )
    val edges = Lineage.cut(edgesPlan, p.checkpoint)
    val edgeSupport = supportBase
      .withColumn("edgeId", edgeId)
      .select(
        lit(SchemaVersions.CoauthorEdgeSupport).as("schemaVersion"),
        col("edgeId"),
        col("researcherA"),
        col("researcherB"),
        col(Windows.Start),
        col(Windows.End),
        col("workId"),
        col("workYear"),
        lit(p.coauthorPolicyVersion).as("policyVersion"),
        lit(p.runId).as("runId"),
        Schemas
          .derivedProvenance(
            col("workId"),
            p.snapshot,
            p.runId,
            Some(p.coauthorPolicyVersion),
            array(col("edgeId"))
          )
          .as("provenance")
      )

    // ---------------------------------------------------------------- 3. metrics
    // n_p = number of DISTINCT canonical researchers on work p (one shuffle on workId).
    val oversizeWorks =
      in.oversizeTeams.select("workId").distinct().withColumn("oversize", lit(true))
    val team = authored
      .groupBy("workId")
      .agg(countDistinct("researcherId").as("np"), min("year").as("workYear"))
      .join(oversizeWorks, Seq("workId"), "left")
      .withColumn("oversize", coalesce(col("oversize"), lit(false)))
    val researcherWork = authored.select("researcherId", "workId").distinct()
    val rpBase = researcherWork.join(team, "workId")
    val rp = Lineage.cut(
      Windows
        .assign(rpBase, "workYear", windowFrame)
        .select(
          col(Windows.Start),
          col(Windows.End),
          col(Windows.AllYears),
          col("researcherId"),
          col("workId"),
          col("workYear"),
          (col("np") - 1).as("coauthorsOnPaper"),
          col("oversize"),
          (col("workYear").isNotNull && !col("oversize")).as("pairable")
        ),
      p.checkpoint
    )
    val base = Lineage.cut(
      rp
        .groupBy(Windows.Start, Windows.End, "researcherId")
        .agg(
          count(lit(1)).as("P"),
          sum("coauthorsOnPaper").as("T"),
          max("coauthorsOnPaper").as("M"),
          sum(when(col("pairable"), col("coauthorsOnPaper")).otherwise(lit(0L))).as("Tpairable"),
          sum(when(col("oversize"), lit(1L)).otherwise(lit(0L))).as("oversizeWorks"),
          sum(when(col("workYear").isNull, lit(1L)).otherwise(lit(0L))).as("yearlessWorks")
        ),
      p.checkpoint
    )
    val maxTeam = rp
      .join(
        base.select(Windows.Start, Windows.End, "researcherId", "M"),
        Seq(Windows.Start, Windows.End, "researcherId")
      )
      .where(col("coauthorsOnPaper") === col("M"))
      .groupBy(Windows.Start, Windows.End, "researcherId")
      .agg(array_sort(collect_set("workId")).as("maximumTeamWorkIds"))
    val incident = edges
      .select(
        col(Windows.Start),
        col(Windows.End),
        col("researcherA").as("researcherId"),
        col("jointPublicationCount")
      )
      .union(
        edges.select(
          col(Windows.Start),
          col(Windows.End),
          col("researcherB").as("researcherId"),
          col("jointPublicationCount")
        )
      )
      .groupBy(Windows.Start, Windows.End, "researcherId")
      .agg(count(lit(1)).as("C"), sum("jointPublicationCount").as("W"))

    // ---------------------------------------------------------------- 4. components
    val nodes = base.select(col(Windows.Start), col(Windows.End), col("researcherId").as("node"))
    val ccEdges = edges.select(
      col(Windows.Start),
      col(Windows.End),
      col("researcherA").as("src"),
      col("researcherB").as("dst")
    )
    val componentRun = ConnectedComponents.run(
      nodes,
      ccEdges,
      Windows.Keys,
      p.windows.map(w => Vector[Any](w.startYear, w.endYear)),
      p.componentsMaxIterations,
      p.checkpointInterval,
      p.checkpoint
    )
    val labels = componentRun.labels
      .select(
        col(Windows.Start),
        col(Windows.End),
        col("node").as("researcherId"),
        col("label").as("componentId")
      )
    val sizes = labels
      .groupBy(Windows.Start, Windows.End, "componentId")
      .agg(count(lit(1)).as("componentSize"))
    val membership = labels.join(sizes, Seq(Windows.Start, Windows.End, "componentId"))
    val components = membership.select(
      lit(SchemaVersions.ComponentMembership).as("schemaVersion"),
      col("researcherId"),
      col("componentId"),
      col("componentSize"),
      col(Windows.Start),
      col(Windows.End),
      lit(Versions.Components).as("metricVersion"),
      lit(p.runId).as("runId"),
      Schemas
        .derivedProvenance(
          col("researcherId"),
          p.snapshot,
          p.runId,
          Some(Versions.Components),
          Schemas.emptyStrings
        )
        .as("provenance")
    )

    val wk = Seq(Windows.Start, Windows.End, "researcherId")
    val joined = base
      .join(maxTeam, wk)
      .join(incident, wk, "left")
      .join(membership, wk)
      .withColumn("C", coalesce(col("C"), lit(0L)))
      .withColumn("W", coalesce(col("W"), lit(0L)))
    val metrics = joined.select(
      lit(SchemaVersions.ResearcherMetrics).as("schemaVersion"),
      Udfs
        .metricsId(
          col("researcherId"),
          col(Windows.Start),
          col(Windows.End),
          lit(Versions.CollaborationMetrics),
          lit(p.runId)
        )
        .as("metricsId"),
      col("researcherId"),
      col(Windows.Start),
      col(Windows.End),
      col("P").as("publicationCount"),
      col("C").as("distinctCoauthorCount"),
      col("T").as("totalCoauthorOccurrences"),
      (col("T").cast("double") / col("P").cast("double")).as("averageCoauthorsPerPaper"),
      col("M").as("maximumCoauthorsOnOnePaper"),
      col("maximumTeamWorkIds"),
      col("W").cast("double").as("weightedDegree"),
      col("componentId"),
      col("componentSize"),
      lit(null).cast(StringType).as("communityId"),
      lit(null).cast(StringType).as("bridgeMetric"),
      lit(null).cast("double").as("bridgeScore"),
      lit(null).cast("int").as("coreness"),
      lit(Versions.CollaborationMetrics).as("metricVersion"),
      Schemas
        .derivedProvenance(
          col("researcherId"),
          p.snapshot,
          p.runId,
          Some(Versions.CollaborationMetrics),
          col("maximumTeamWorkIds")
        )
        .as("provenance"),
      lit(p.runId).as("runId")
    )
    val wtStatus =
      when(col("W") === col("T"), lit(WtEqual))
        .when(
          col("W") === col("Tpairable"),
          when(col("oversizeWorks") > 0 && col("yearlessWorks") > 0, lit(WtExceptionBoth))
            .when(col("oversizeWorks") > 0, lit(WtExceptionOversize))
            .otherwise(lit(WtExceptionYearless))
        )
        .otherwise(lit(WtFailure))
    val wtInvariant = joined.select(
      lit("wt-invariant-v1").as("schemaVersion"),
      col("researcherId"),
      col(Windows.Start),
      col(Windows.End),
      col("T").as("totalCoauthorOccurrences"),
      col("W").as("weightedDegree"),
      col("Tpairable").as("pairableCoauthorOccurrences"),
      col("oversizeWorks").as("oversizeTeamWorkCount"),
      col("yearlessWorks").as("yearlessWorkCount"),
      wtStatus.as("status"),
      lit(Versions.CollaborationMetrics).as("metricVersion"),
      lit(p.runId).as("runId")
    )

    // ---------------------------------------------------------------- integrity rejects
    val rejects = Seq(
      apBadSchema.select(
        reject(p, "DBLP", col("mentionId"), "UNSUPPORTED_SCHEMA_VERSION", col("schemaVersion"))*
      ),
      apDup.select(
        reject(p, "DBLP", col("mentionId"), "DUPLICATE_AUTHORED_MENTION", col("workId"))*
      ),
      apNoWork.select(reject(p, "DBLP", col("mentionId"), "AUTHORED_WORK_MISSING", col("workId"))*),
      apNoResearcher.select(
        reject(p, "DBLP", col("mentionId"), "AUTHORED_RESEARCHER_MISSING", col("sourceAuthorKey"))*
      ),
      pairsBadSchema.select(
        reject(p, "DBLP", col("candidateId"), "UNSUPPORTED_SCHEMA_VERSION", col("schemaVersion"))*
      ),
      pairsNoYear.select(
        reject(p, "DBLP", col("candidateId"), "PAIR_MISSING_YEAR", col("workId"))*
      ),
      pairsUnmapped.select(
        reject(p, "DBLP", col("candidateId"), "PAIR_MENTION_NOT_AUTHORED", col("workId"))*
      ),
      pairsYearMismatch.select(
        reject(p, "DBLP", col("candidateId"), "PAIR_YEAR_MISMATCH", col("workId"))*
      ),
      hasTopicMissing.select(
        reject(p, "OPENALEX", col("sourceEdgeId"), "HAS_TOPIC_TOPIC_MISSING", col("topicId"))*
      ),
      identity.unknownEndpointLinks.select(
        reject(p, "SCHOLARCHAIN", col("decisionId"), "DECISION_UNKNOWN_MENTION", col("sourceId"))*
      )
    ).reduce(_.unionByName(_))

    GraphOutputs(
      identity = identity,
      researchers = researchers,
      works = works,
      authored = authored,
      venues = venues,
      topics = topics,
      institutions = institutions,
      hasTopic = hasTopic,
      rejects = rejects,
      edges = edges,
      edgeSupport = edgeSupport,
      metrics = metrics,
      wtInvariant = wtInvariant,
      components = components,
      componentRun = componentRun,
      edgeAggregationPlan = edgesPlan,
      selfPairs = selfPairs,
      researcherPapers = rp,
      windowFrame = windowFrame,
      researcherInstitutions = researcherInstitutions
    )

  /** The support-table pointer of an edge (`CoauthorEdgeSupport.supportRef`). */
  def supportRef(edgeId: String): String = CoauthorEdgeSupport.supportRef(edgeId)
