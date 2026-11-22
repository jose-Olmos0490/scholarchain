package scholarchain.spark.identity

import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.{ArrayType, IntegerType, StringType}
import org.apache.spark.sql.{Column, DataFrame}
import scholarchain.core.schema.SchemaVersions
import scholarchain.spark.graph.ConnectedComponents
import scholarchain.spark.{Lineage, Schemas, Udfs, Versions}

/** Canonical researcher identity (rule `sc-identity-cluster-rule-v1`, human-ratified in Phase 06
  * repair 01). See modules/spark/JOBS.md, "Identity".
  *
  *   1. Identity graph: nodes are all author mentions (every source) plus one anchor node per DBLP
  *      author key (DBLP author strings are homonym-disambiguated, e.g. "Wei Wang 0001"). Edges
  *      are ACCEPTED researcher match decisions between two known mentions, and DBLP mention ->
  *      its author-key anchor. UNRESOLVED, CONFLICT and REJECTED decisions add nothing, and no
  *      edge is ever derived from name similarity.
  *   2. Clusters are the connected components of that graph (min-label propagation).
  *   3. A cluster with no ACCEPTED link is not a merge: its DBLP mentions keep the fallback ID
  *      `researcher:sc-researcher-<v>:sha256(dblpAuthorKey)` (status UNLINKED).
  *   4. A cluster with >= 1 ACCEPTED link is merged only if it has no violation: two members on
  *      the same work record (two author slots of one source record; mentions of one paper in
  *      different sources are different records and are expected to merge), more than one ORCID,
  *      more than one distinct OpenAlex author ID, more than one DBLP author key, or more members
  *      than `graph.identity.maxClusterSize`. A violating cluster is not merged: every DBLP
  *      mention keeps its fallback ID (status CONFLICT_SPLIT) and one IdentityConflict record is
  *      written with every reason.
  *   5. A merged cluster's ID follows the frozen precedence: the validated ORCID (exactly one in
  *      the cluster), else the validated OpenAlex author ID (exactly one), else the fallback ID of
  *      its single DBLP author key. If two merged clusters would receive the same external-ID
  *      researcher ID, they are evaluated as one group, which always violates rule 4 (two DBLP
  *      keys), so neither is merged and the conflict is recorded.
  *
  * Bounded driver work: only the per-group changed-label counts of the component loop.
  */
object IdentityResolver:
  val Unlinked = "UNLINKED"
  val Merged = "MERGED"
  val ConflictSplit = "CONFLICT_SPLIT"

  val SourceOrcid = "ORCID"
  val SourceOpenAlex = "OPENALEX"
  val SourceFallback = "SCHOLARCHAIN_FALLBACK"

  final case class Params(
      identityVersion: String,
      maxClusterSize: Int,
      maxIterations: Int,
      checkpointInterval: Int,
      checkpoint: Boolean,
      runId: String,
      snapshot: String,
      scopeStartYear: Int,
      scopeEndYear: Int
  )

  /** @param mentionIdentity
    *   one row per DBLP mention with a non-blank author key: mentionId, grp, researcherId,
    *   idSource, identityStatus, matchDecisionId, identityConfidence, dblpAuthorKey, orcid,
    *   openAlexAuthorId, groupDecisionIds
    * @param groups
    *   per identity group: grp, status and the evidence columns (for the run summary)
    * @param conflicts
    *   IdentityConflict rows
    * @param acceptedLinks
    *   ACCEPTED researcher decisions whose two endpoints are known mentions
    * @param unknownEndpointLinks
    *   ACCEPTED researcher decisions naming an unknown mention (ignored, reported)
    */
  final case class Output(
      mentionIdentity: DataFrame,
      groups: DataFrame,
      conflicts: DataFrame,
      acceptedLinks: DataFrame,
      unknownEndpointLinks: DataFrame,
      identityIterations: ConnectedComponents.GroupIterations
  )

  private def isMention(c: Column): Column = c.startsWith("mention:")
  private val emptyArr: Column = Schemas.emptyStrings

  /** Runs the rule.
    *
    * @param mentions
    *   mentionId, workId, sourceDataset, sourceAuthorKey, orcid (all sources, unique mentionId)
    * @param decisions
    *   match-decision-v1 rows (researcher and work decisions, every outcome)
    */
  def resolve(mentions: DataFrame, decisions: DataFrame, p: Params): Output =
    val m = mentions.select(
      col("mentionId"),
      col("workId"),
      col("sourceDataset"),
      when(
        col("sourceDataset") === "DBLP" && trim(col("sourceAuthorKey")) =!= "",
        col("sourceAuthorKey")
      )
        .as("dblpKey"),
      Udfs.normalizeOrcid(col("orcid")).as("orcidN"),
      when(
        col("sourceDataset") === "OPENALEX",
        Udfs.normalizeOpenAlexAuthor(col("sourceAuthorKey"))
      )
        .as("openAlexN")
    )

    // ACCEPTED researcher links with two known endpoints; the others are reported, not used.
    val accepted = decisions.where(
      col("decision") === "ACCEPTED" && isMention(col("sourceId")) && isMention(col("targetId"))
    )
    val known = m.select("mentionId")
    val acceptedLinks = accepted
      .join(known.withColumnRenamed("mentionId", "sourceId"), Seq("sourceId"), "left_semi")
      .join(known.withColumnRenamed("mentionId", "targetId"), Seq("targetId"), "left_semi")
      .select("decisionId", "sourceId", "targetId", "confidence", "policyVersion")
    val unknownEndpointLinks =
      accepted.join(acceptedLinks.select("decisionId"), Seq("decisionId"), "left_anti")

    // Identity graph and clusters.
    val keyNode = concat(lit("dblpkey:"), col("dblpKey"))
    val nodes = m
      .select(col("mentionId").as("node"))
      .union(m.where(col("dblpKey").isNotNull).select(keyNode.as("node")))
      .distinct()
    val edges = acceptedLinks
      .select(col("sourceId").as("src"), col("targetId").as("dst"))
      .union(
        m.where(col("dblpKey").isNotNull).select(col("mentionId").as("src"), keyNode.as("dst"))
      )
    val cc = ConnectedComponents.run(
      nodes,
      edges,
      Seq.empty,
      Vector(Vector.empty),
      p.maxIterations,
      p.checkpointInterval,
      p.checkpoint
    )
    requireConverged("identity clusters", cc)
    val members1 = m
      .join(cc.labels.select(col("node").as("mentionId"), col("label").as("grp")), "mentionId")
    val linkEnds = acceptedLinks
      .select(
        col("decisionId"),
        col("sourceId").as("mentionId"),
        col("confidence"),
        col("policyVersion")
      )
    def linksOf(members: DataFrame) =
      linkEnds.join(members.select("mentionId", "grp"), "mentionId").drop("mentionId")

    // Phase 1: evaluate clusters; find merged clusters that would share an external-ID-based ID.
    val stats1 = Lineage.cut(evaluate(members1, linksOf(members1), p), p.checkpoint)
    val clean1 = stats1
      .where(col("status") === Merged && size(col("dblpKeys")) === 1)
      .select(col("grp"), proposedId(p.identityVersion).as("proposedId"))
    val collisions = clean1
      .groupBy("proposedId")
      .agg(min("grp").as("group2"), countDistinct("grp").as("n"))
      .where(col("n") > 1)
    val regroup = clean1.join(collisions, "proposedId").select("grp", "group2")
    val members = Lineage.cut(
      members1
        .join(regroup, Seq("grp"), "left")
        .withColumn("grp", coalesce(col("group2"), col("grp")))
        .drop("group2"),
      p.checkpoint
    )

    // Phase 2: final groups.
    val groupsAll = evaluate(members, linksOf(members), p)
      .withColumn(
        "researcherIdIfMerged",
        when(col("status") === Merged && size(col("dblpKeys")) === 1, proposedId(p.identityVersion))
      )
      .withColumn(
        "idSourceIfMerged",
        when(size(col("orcids")) === 1, lit(SourceOrcid))
          .when(size(col("openAlexIds")) === 1, lit(SourceOpenAlex))
          .otherwise(lit(SourceFallback))
      )
    val groups = Lineage.cut(groupsAll, p.checkpoint)

    val directDecision = linkEnds
      .union(
        acceptedLinks.select(
          col("decisionId"),
          col("targetId").as("mentionId"),
          col("confidence"),
          col("policyVersion")
        )
      )
      .groupBy("mentionId")
      .agg(min("decisionId").as("directDecisionId"))

    val merged = col("status") === Merged
    val mentionIdentityAll = members
      .where(col("sourceDataset") === "DBLP" && col("dblpKey").isNotNull)
      .join(groups, "grp")
      .join(directDecision, Seq("mentionId"), "left")
      .select(
        col("mentionId"),
        col("grp"),
        when(merged, col("researcherIdIfMerged"))
          .otherwise(Udfs.fallbackResearcherId(lit(p.identityVersion), col("dblpKey")))
          .as("researcherId"),
        when(merged, col("idSourceIfMerged")).otherwise(lit(SourceFallback)).as("idSource"),
        col("status").as("identityStatus"),
        when(merged, col("directDecisionId")).as("matchDecisionId"),
        when(merged, col("minConfidence")).otherwise(lit(1.0)).as("identityConfidence"),
        col("dblpKey").as("dblpAuthorKey"),
        when(merged && size(col("orcids")) === 1, element_at(col("orcids"), 1)).as("orcid"),
        when(merged && size(col("openAlexIds")) === 1, element_at(col("openAlexIds"), 1))
          .as("openAlexAuthorId"),
        when(merged, col("decisionIds")).otherwise(emptyArr).as("groupDecisionIds")
      )
    val mentionIdentity = Lineage.cut(mentionIdentityAll, p.checkpoint)

    val conflicts = groups
      .where(col("status") === ConflictSplit)
      .select(
        lit(SchemaVersions.IdentityConflict).as("schemaVersion"),
        Udfs
          .identityConflictId(lit(p.identityVersion), col("policyVersion"), col("memberIds"))
          .as("conflictId"),
        col("memberIds"),
        col("clusterSize").cast(IntegerType).as("clusterSize"),
        lit(p.maxClusterSize).as("maxClusterSize"),
        col("details"),
        col("decisionIds").as("matchDecisionIds"),
        lit(p.identityVersion).as("identityVersion"),
        col("policyVersion"),
        lit(p.runId).as("runId"),
        Schemas
          .derivedProvenance(
            element_at(col("memberIds"), 1),
            p.snapshot,
            p.runId,
            Some(Versions.IdentityRule),
            col("decisionIds")
          )
          .as("provenance"),
        lit(p.scopeStartYear).as("windowStartYear"),
        lit(p.scopeEndYear).as("windowEndYear")
      )

    Output(
      mentionIdentity,
      groups,
      conflicts,
      acceptedLinks,
      unknownEndpointLinks,
      cc.groups.head
    )

  private def requireConverged(what: String, r: ConnectedComponents.Result): Unit =
    r.groups.foreach(g =>
      if !g.converged then
        throw new IllegalStateException(
          s"$what did not converge within ${g.iterations} iterations " +
            "(graph.components.maxIterations); refusing to emit partial identities"
        )
    )

  /** The researcher ID a clean merged group receives (frozen precedence). */
  private def proposedId(version: String): Column =
    Udfs.researcherId(
      when(size(col("orcids")) === 1, element_at(col("orcids"), 1)),
      when(size(col("openAlexIds")) === 1, element_at(col("openAlexIds"), 1)),
      lit(version),
      element_at(col("dblpKeys"), 1)
    )

  private def sortedSet(c: Column): Column = array_sort(collect_set(c))

  /** Per-group evidence, violations and status.
    *
    * @param members
    *   mentionId, grp, workId, sourceDataset, dblpKey, orcidN, openAlexN
    * @param links
    *   decisionId, confidence, policyVersion, grp (one row per link endpoint)
    */
  private def evaluate(members: DataFrame, links: DataFrame, p: Params): DataFrame =
    val sameWork = members
      .groupBy("grp", "workId")
      .agg(countDistinct("mentionId").as("n"))
      .where(col("n") > 1)
      .groupBy("grp")
      .agg(array_sort(collect_set("workId")).as("sameWorkIds"))
    val linkAgg = links
      .groupBy("grp")
      .agg(
        countDistinct("decisionId").as("linkCount"),
        sortedSet(col("decisionId")).as("decisionIds"),
        min("confidence").as("minConfidence"),
        min("policyVersion").as("policyVersion")
      )
    val base = members
      .groupBy("grp")
      .agg(
        countDistinct("mentionId").as("clusterSize"),
        sortedSet(col("mentionId")).as("memberIds"),
        sortedSet(col("orcidN")).as("orcids"),
        sortedSet(col("openAlexN")).as("openAlexIds"),
        sortedSet(col("dblpKey")).as("dblpKeys")
      )
    def detail(cond: Column, reason: String, values: Column): Column =
      when(
        cond,
        struct(lit(reason).as("reason"), values.cast(ArrayType(StringType, true)).as("values"))
      )
    base
      .join(sameWork, Seq("grp"), "left")
      .join(linkAgg, Seq("grp"), "left")
      .withColumn("sameWorkIds", coalesce(col("sameWorkIds"), emptyArr))
      .withColumn("linkCount", coalesce(col("linkCount"), lit(0L)))
      .withColumn("decisionIds", coalesce(col("decisionIds"), emptyArr))
      .withColumn(
        "details",
        filter(
          array(
            detail(size(col("sameWorkIds")) > 0, "SAME_WORK_MENTIONS", col("sameWorkIds")),
            detail(size(col("orcids")) > 1, "MULTIPLE_ORCIDS", col("orcids")),
            detail(
              size(col("openAlexIds")) > 1,
              "MULTIPLE_OPENALEX_AUTHOR_IDS",
              col("openAlexIds")
            ),
            detail(size(col("dblpKeys")) > 1, "MULTIPLE_DBLP_AUTHOR_KEYS", col("dblpKeys")),
            detail(col("clusterSize") > p.maxClusterSize, "CLUSTER_TOO_LARGE", emptyArr)
          ),
          (d: Column) => d.isNotNull
        )
      )
      .withColumn(
        "status",
        when(col("linkCount") === 0, lit(Unlinked))
          .when(size(col("details")) > 0, lit(ConflictSplit))
          .otherwise(lit(Merged))
      )
