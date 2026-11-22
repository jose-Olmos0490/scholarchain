package scholarchain.spark.graph

import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.*
import org.apache.spark.sql.{Column, DataFrame}
import scholarchain.core.schema.SchemaVersions
import scholarchain.spark.{Schemas, Udfs, Versions, Windows}

/** Structured semantic-input facts (`sc-semantic-facts-v1`, core `SemanticFact`). Facts only: IDs,
  * windows, computed metrics, ranked entities with counts, related researcher IDs and
  * representative works with their source titles. No prose, no model call. Titles and names are
  * untrusted source text, truncated to `SemanticFact.MaxTextCodePoints` code points.
  *
  * Subjects per window: RESEARCHER (every researcher with a paper), COMMUNITY (every community),
  * PATH (every FOUND path), TOPIC and INSTITUTION (every topic / institution with an in-window
  * work). Ranking rules are in modules/spark/JOBS.md. Every list is built with a window function
  * partitioned by (window, subject) and bounded by `graph.semanticFacts.topEntityLimit`,
  * `graph.semanticFacts.memberLimit` or `graph.representativeWorkLimit` (K).
  */
object SemanticFacts:

  val RankedType: ArrayType = ArrayType(
    StructType(
      Seq(
        StructField("entityId", StringType, nullable = true),
        StructField("label", StringType, nullable = true),
        StructField("count", LongType, nullable = true)
      )
    ),
    containsNull = true
  )
  val WorksType: ArrayType = ArrayType(
    StructType(
      Seq(
        StructField("workId", StringType, nullable = true),
        StructField("title", StringType, nullable = true),
        StructField("year", IntegerType, nullable = true)
      )
    ),
    containsNull = true
  )
  val MetricsType: ArrayType = ArrayType(
    StructType(
      Seq(
        StructField("metric", StringType, nullable = true),
        StructField("value", DoubleType, nullable = true)
      )
    ),
    containsNull = true
  )
  private val StringsType = ArrayType(StringType, containsNull = true)

  final case class Inputs(
      researchers: DataFrame,
      works: DataFrame,
      venues: DataFrame,
      topics: DataFrame,
      institutions: DataFrame,
      hasTopic: DataFrame,
      researcherPapers: DataFrame,
      researcherInstitutions: DataFrame,
      metrics: DataFrame,
      communities: DataFrame,
      bridges: DataFrame,
      coreness: DataFrame,
      edges: DataFrame,
      paths: DataFrame
  )

  final case class Limits(topEntityLimit: Int, memberLimit: Int, workLimit: Int)

  private val K = Windows.Keys

  /** The first `n` rows per `keys` by `order`, as an ordered array of `item` (an array column
    * named `name`). Ranking is a window function partitioned by `keys`.
    */
  private def topN(
      df: DataFrame,
      keys: Seq[String],
      order: Seq[Column],
      n: Int,
      item: Column,
      name: String
  ): DataFrame =
    val rk = row_number().over(Window.partitionBy(keys.map(col)*).orderBy(order*))
    df.withColumn("rk", rk)
      .where(col("rk") <= n)
      .groupBy(keys.map(col)*)
      .agg(array_sort(collect_list(struct(col("rk"), item.as("item")))).as("ranked"))
      .select((keys.map(col) :+ transform(col("ranked"), r => r.getField("item")).as(name))*)

  /** Ranked entities `(keys..., entityId, label, count)` -> `name` array sorted by count desc,
    * then entity ID asc.
    */
  private def ranked(df: DataFrame, keys: Seq[String], n: Int, name: String): DataFrame =
    topN(
      df,
      keys,
      Seq(col("count").desc, col("entityId").asc),
      n,
      struct(col("entityId"), col("label"), col("count")),
      name
    )

  private def metric(name: String, value: Column): Column =
    struct(lit(name).as("metric"), value.cast(DoubleType).as("value"))

  /** Metric list without the absent (null) values. */
  private def metricList(ms: Column*): Column =
    filter(array(ms*), m => m.getField("value").isNotNull)

  private def title(c: Column): Column = Udfs.truncateText(c)

  /** `c`, or null when blank (a blank source name falls back to the entity ID as label). */
  private def nonBlank(c: Column): Column = when(trim(c) =!= "", c)

  def build(in: Inputs, p: BuildParams, l: Limits): DataFrame =
    val rk = K :+ "subjectId"
    val works = in.works.select(
      col("workId"),
      title(col("title")).as("title"),
      col("year"),
      col("venueId")
    )
    val venueNames = in.venues.select(col("venueId"), col("name").as("venueName"))
    val topicNames = in.topics.select(col("topicId"), col("name").as("topicName"))
    val instNames = in.institutions.select(col("institutionId"), col("name").as("instName"))
    val workTopics = in.hasTopic.select(col("fromId").as("workId"), col("toId").as("topicId"))
    // (window, researcherId, workId) for papers in the window.
    val rw = in.researcherPapers.select(
      col(Windows.Start),
      col(Windows.End),
      col("researcherId"),
      col("workId")
    )
    val windowWorks = rw.select(col(Windows.Start), col(Windows.End), col("workId")).distinct()

    // Subject -> works (window, subjectId, workId) for each subject type with works.
    def topicsOf(subjectWorks: DataFrame): DataFrame =
      ranked(
        subjectWorks
          .join(workTopics, "workId")
          .groupBy((rk.map(col) :+ col("topicId"))*)
          .agg(countDistinct("workId").as("count"))
          .join(topicNames, Seq("topicId"), "left")
          .select(
            (rk.map(col) :+ col("topicId").as("entityId") :+
              title(coalesce(nonBlank(col("topicName")), col("topicId"))).as("label") :+ col(
                "count"
              ))*
          ),
        rk,
        l.topEntityLimit,
        "topTopics"
      )
    def venuesOf(subjectWorks: DataFrame): DataFrame =
      ranked(
        subjectWorks
          .join(works.select("workId", "venueId"), "workId")
          .where(col("venueId").isNotNull)
          .groupBy((rk.map(col) :+ col("venueId"))*)
          .agg(countDistinct("workId").as("count"))
          .join(venueNames, Seq("venueId"), "left")
          .select(
            (rk.map(col) :+ col("venueId").as("entityId") :+
              title(coalesce(nonBlank(col("venueName")), col("venueId"))).as("label") :+ col(
                "count"
              ))*
          ),
        rk,
        l.topEntityLimit,
        "topVenues"
      )
    // Representative works ordered by `order` (applied to columns of works + extra).
    def worksOf(subjectWorks: DataFrame, order: Seq[Column]): DataFrame =
      topN(
        subjectWorks
          .join(works, "workId")
          .where(col("title").isNotNull && trim(col("title")) =!= ""),
        rk,
        order,
        l.workLimit,
        struct(col("workId"), col("title"), col("year")),
        "representativeWorks"
      )
    def instsOf(subjectInst: DataFrame /* rk, institutionId, count */ ): DataFrame =
      ranked(
        subjectInst
          .join(instNames, Seq("institutionId"), "left")
          .select(
            (rk.map(col) :+ col("institutionId").as("entityId") :+
              title(coalesce(nonBlank(col("instName")), col("institutionId"))).as("label") :+ col(
                "count"
              ))*
          ),
        rk,
        l.topEntityLimit,
        "topInstitutions"
      )

    def assemble(
        subjectType: String,
        subjects: DataFrame, // rk + displayName + metrics
        parts: Seq[DataFrame], // rk + one list column each
        related: Option[DataFrame]
    ): DataFrame =
      val joined = (parts ++ related.toSeq).foldLeft(subjects)((acc, d) => acc.join(d, rk, "left"))
      def orEmpty(name: String, t: DataType): Column =
        if joined.columns.contains(name) then coalesce(col(name), array().cast(t)).as(name)
        else array().cast(t).as(name)
      joined.select(
        lit(SchemaVersions.SemanticFact).as("schemaVersion"),
        Udfs
          .semanticFactId(
            lit(subjectType),
            col("subjectId"),
            col(Windows.Start),
            col(Windows.End),
            lit(Versions.SemanticFacts),
            lit(p.runId)
          )
          .as("factId"),
        lit(subjectType).as("subjectType"),
        col("subjectId"),
        col("displayName"),
        col(Windows.Start),
        col(Windows.End),
        col("metrics"),
        orEmpty("topTopics", RankedType),
        orEmpty("topVenues", RankedType),
        orEmpty("topInstitutions", RankedType),
        orEmpty("relatedResearcherIds", StringsType),
        orEmpty("representativeWorks", WorksType),
        lit(Versions.SemanticFacts).as("metricVersion"),
        lit(p.runId).as("runId"),
        Schemas
          .derivedProvenance(
            col("subjectId"),
            p.snapshot,
            p.runId,
            Some(Versions.SemanticFacts),
            Schemas.emptyStrings
          )
          .as("provenance")
      )

    val wk = K :+ "researcherId"
    // ------------------------------------------------------------- RESEARCHER
    val rWorks = rw.withColumnRenamed("researcherId", "subjectId")
    val rSubjects = in.metrics
      .drop("communityId", "coreness") // re-joined from the community and k-core tables below
      .join(in.communities.select((wk.map(col) :+ col("communitySize"))*), wk, "left")
      .join(
        in.bridges.select(
          (wk.map(col) :+ col("crossCommunityDegree") :+ col("communitiesConnected") :+
            col("participationCoefficient"))*
        ),
        wk,
        "left"
      )
      .join(in.coreness.select((wk.map(col) :+ col("coreness"))*), wk, "left")
      .join(
        in.researchers.select(col("researcherId"), col("displayName")),
        Seq("researcherId"),
        "left"
      )
      .select(
        col(Windows.Start),
        col(Windows.End),
        col("researcherId").as("subjectId"),
        title(col("displayName")).as("displayName"),
        metricList(
          metric("PUBLICATION_COUNT", col("publicationCount")),
          metric("DISTINCT_COAUTHOR_COUNT", col("distinctCoauthorCount")),
          metric("TOTAL_COAUTHOR_OCCURRENCES", col("totalCoauthorOccurrences")),
          metric("AVERAGE_COAUTHORS_PER_PAPER", col("averageCoauthorsPerPaper")),
          metric("MAXIMUM_COAUTHORS_ON_ONE_PAPER", col("maximumCoauthorsOnOnePaper")),
          metric("WEIGHTED_DEGREE", col("weightedDegree")),
          metric("COMPONENT_SIZE", col("componentSize")),
          metric("COMMUNITY_SIZE", col("communitySize")),
          metric("CROSS_COMMUNITY_DEGREE", col("crossCommunityDegree")),
          metric("COMMUNITIES_CONNECTED", col("communitiesConnected")),
          metric("PARTICIPATION_COEFFICIENT", col("participationCoefficient")),
          metric("CORENESS", col("coreness"))
        ).as("metrics")
      )
    val coauthors = in.edges
      .select(
        col(Windows.Start),
        col(Windows.End),
        col("researcherA").as("subjectId"),
        col("researcherB").as("other"),
        col("jointPublicationCount")
      )
      .union(
        in.edges.select(
          col(Windows.Start),
          col(Windows.End),
          col("researcherB").as("subjectId"),
          col("researcherA").as("other"),
          col("jointPublicationCount")
        )
      )
    val rRelated = topN(
      coauthors,
      rk,
      Seq(col("jointPublicationCount").desc, col("other").asc),
      l.memberLimit,
      col("other"),
      "relatedResearcherIds"
    )
    val rInst = rWorks
      .select((rk.map(col))*)
      .distinct()
      .join(
        in.researcherInstitutions.select(
          col("researcherId").as("subjectId"),
          col("institutionId"),
          col("mentionCount").as("count")
        ),
        "subjectId"
      )
    val researcherFacts = assemble(
      "RESEARCHER",
      rSubjects,
      Seq(
        topicsOf(rWorks),
        venuesOf(rWorks),
        instsOf(rInst),
        worksOf(rWorks, Seq(col("year").desc_nulls_last, col("workId").asc))
      ),
      Some(rRelated)
    )

    // ------------------------------------------------------------- COMMUNITY
    val members = in.communities.select(
      col(Windows.Start),
      col(Windows.End),
      col("researcherId"),
      col("communityId").as("subjectId")
    )
    val cWorkMembers = members
      .join(rw, wk)
      .groupBy((rk.map(col) :+ col("workId"))*)
      .agg(countDistinct("researcherId").as("memberAuthors"))
    val cWorks = cWorkMembers.select((rk.map(col) :+ col("workId"))*)
    val cSubjects = in.communities
      .select(
        col(Windows.Start),
        col(Windows.End),
        col("communityId").as("subjectId"),
        col("communitySize")
      )
      .distinct()
      .select(
        col(Windows.Start),
        col(Windows.End),
        col("subjectId"),
        lit(null).cast(StringType).as("displayName"),
        array(metric("COMMUNITY_SIZE", col("communitySize"))).as("metrics")
      )
    val cRelated = topN(
      members.join(in.metrics.select((wk.map(col) :+ col("weightedDegree"))*), wk),
      rk,
      Seq(col("weightedDegree").desc, col("researcherId").asc),
      l.memberLimit,
      col("researcherId"),
      "relatedResearcherIds"
    )
    val cInst = members
      .join(in.researcherInstitutions.select("researcherId", "institutionId"), "researcherId")
      .groupBy((rk.map(col) :+ col("institutionId"))*)
      .agg(countDistinct("researcherId").as("count"))
    val communityFacts = assemble(
      "COMMUNITY",
      cSubjects,
      Seq(
        topicsOf(cWorks),
        venuesOf(cWorks),
        instsOf(cInst),
        worksOf(cWorkMembers, Seq(col("memberAuthors").desc, col("workId").asc))
      ),
      Some(cRelated)
    )

    // ------------------------------------------------------------- PATH (FOUND paths only)
    val found = in.paths.where(col("status") === "FOUND")
    val pSubjects = found.select(
      col(Windows.Start),
      col(Windows.End),
      col("pathId").as("subjectId"),
      lit(null).cast(StringType).as("displayName"),
      array(metric("SHORTEST_PATH_LENGTH", col("length"))).as("metrics")
    )
    // Path nodes in hop order (not truncated: at most graph.paths.maxDepth + 1 IDs).
    val pRelated = found.select(
      col(Windows.Start),
      col(Windows.End),
      col("pathId").as("subjectId"),
      concat(
        array(col("sourceResearcherId")),
        transform(col("hops"), h => h.getField("toResearcherId"))
      ).as("relatedResearcherIds")
    )
    // The smallest supporting work of each hop, in hop order.
    val pWorks = found
      .select(
        col(Windows.Start),
        col(Windows.End),
        col("pathId").as("subjectId"),
        posexplode(col("hops")).as(Seq("hopIndex", "hop"))
      )
      .select(
        (rk.map(col) :+ col("hopIndex") :+
          element_at(col("hop.supportingWorkIds"), 1).as("workId"))*
      )
      .groupBy((rk.map(col) :+ col("workId"))*)
      .agg(min("hopIndex").as("hopIndex"))
    val pathFacts = assemble(
      "PATH",
      pSubjects,
      Seq(worksOf(pWorks, Seq(col("hopIndex").asc, col("workId").asc))),
      Some(pRelated)
    )

    // ------------------------------------------------------------- TOPIC
    val tWorks = windowWorks.join(workTopics, "workId").withColumnRenamed("topicId", "subjectId")
    val tSubjects = tWorks
      .groupBy(rk.map(col)*)
      .agg(countDistinct("workId").as("works"))
      .join(topicNames.withColumnRenamed("topicId", "subjectId"), Seq("subjectId"), "left")
      .select(
        col(Windows.Start),
        col(Windows.End),
        col("subjectId"),
        title(col("topicName")).as("displayName"),
        array(metric("PUBLICATION_COUNT", col("works"))).as("metrics")
      )
    val tRelated = topN(
      tWorks
        .join(rw, K :+ "workId")
        .groupBy((rk.map(col) :+ col("researcherId"))*)
        .agg(countDistinct("workId").as("n")),
      rk,
      Seq(col("n").desc, col("researcherId").asc),
      l.memberLimit,
      col("researcherId"),
      "relatedResearcherIds"
    )
    val topicFacts = assemble(
      "TOPIC",
      tSubjects,
      Seq(venuesOf(tWorks), worksOf(tWorks, Seq(col("year").desc_nulls_last, col("workId").asc))),
      Some(tRelated)
    )

    // ------------------------------------------------------------- INSTITUTION
    // Institution of a researcher = a researcher-level affiliation (not dated): every in-window
    // paper of an affiliated researcher counts for the institution.
    val iResearchers = rw
      .select(col(Windows.Start), col(Windows.End), col("researcherId"))
      .distinct()
      .join(in.researcherInstitutions.select("researcherId", "institutionId"), "researcherId")
      .withColumnRenamed("institutionId", "subjectId")
    val iWorks = iResearchers.join(rw, wk).select((rk.map(col) :+ col("workId"))*).distinct()
    val iSubjects = iWorks
      .groupBy(rk.map(col)*)
      .agg(countDistinct("workId").as("works"))
      .join(instNames.withColumnRenamed("institutionId", "subjectId"), Seq("subjectId"), "left")
      .select(
        col(Windows.Start),
        col(Windows.End),
        col("subjectId"),
        title(col("instName")).as("displayName"),
        array(metric("PUBLICATION_COUNT", col("works"))).as("metrics")
      )
    val iRelated = topN(
      iResearchers.join(in.metrics.select((wk.map(col) :+ col("publicationCount"))*), wk),
      rk,
      Seq(col("publicationCount").desc, col("researcherId").asc),
      l.memberLimit,
      col("researcherId"),
      "relatedResearcherIds"
    )
    val institutionFacts = assemble(
      "INSTITUTION",
      iSubjects,
      Seq(
        topicsOf(iWorks),
        venuesOf(iWorks),
        worksOf(iWorks, Seq(col("year").desc_nulls_last, col("workId").asc))
      ),
      Some(iRelated)
    )

    Seq(researcherFacts, communityFacts, pathFacts, topicFacts, institutionFacts)
      .map(
        _.withColumn(
          "displayName",
          when(trim(col("displayName")) === "", lit(null)).otherwise(col("displayName"))
        )
      )
      .reduce(_.unionByName(_))
