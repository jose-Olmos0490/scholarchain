package scholarchain.spark

import org.apache.spark.sql.api.java.{UDF1, UDF2, UDF3, UDF4, UDF5, UDF6}
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions.udf
import org.apache.spark.sql.types.StringType
import scholarchain.core.ids.{ExternalIds, StableIds}
import scholarchain.core.model.{EdgeType, SemanticFact, SemanticSubjectType}

/** Spark UDFs over the frozen core stable-ID formulas and identifier normalizers.
  *
  * Scala 3 has no TypeTags, so every UDF is a Java `UDFn` with an explicit return type. The
  * functions are pure. A formula `Left` means an upstream invariant was broken (inputs are
  * validated before they reach a UDF), so it fails the task with the core message instead of
  * writing a malformed ID.
  */
object Udfs:
  private def orFail(e: Either[String, String]): String =
    e.fold(
      msg => throw new IllegalArgumentException(s"stable-ID formula failed: $msg"),
      Predef.identity
    )

  private def seq(v: scala.collection.Seq[String]): Seq[String] =
    if v == null then Seq.empty else v.toSeq

  /** `StableIds.coauthorEdgeId(a, b, windowStart, windowEnd, policyVersion)`. */
  val coauthorEdgeId: UserDefinedFunction = udf(
    new UDF5[String, String, Integer, Integer, String, String]:
      def call(a: String, b: String, s: Integer, e: Integer, p: String): String =
        orFail(StableIds.coauthorEdgeId(a, b, s.intValue, e.intValue, p))
    ,
    StringType
  )

  /** `StableIds.researcherIdScholarChain(identityVersion, dblpAuthorKey)`: the fallback ID. */
  val fallbackResearcherId: UserDefinedFunction = udf(
    new UDF2[String, String, String]:
      def call(version: String, key: String): String =
        orFail(StableIds.researcherIdScholarChain(version, key))
    ,
    StringType
  )

  /** `StableIds.researcherId(validatedOrcid, None, validatedOpenAlex, version, dblpAuthorKey)`:
    * the frozen precedence ORCID > dblp pid > OpenAlex > ScholarChain identity.
    */
  val researcherId: UserDefinedFunction = udf(
    new UDF4[String, String, String, String, String]:
      def call(orcid: String, openAlex: String, version: String, key: String): String =
        orFail(StableIds.researcherId(Option(orcid), None, Option(openAlex), version, key))
    ,
    StringType
  )

  /** `StableIds.authorshipId(workId, mentionId, researcherId)`. */
  val authorshipId: UserDefinedFunction = udf(
    new UDF3[String, String, String, String]:
      def call(w: String, m: String, r: String): String = orFail(StableIds.authorshipId(w, m, r))
    ,
    StringType
  )

  /** `StableIds.metricsId(researcherId, windowStart, windowEnd, metricVersion, runId)`. */
  val metricsId: UserDefinedFunction = udf(
    new UDF5[String, Integer, Integer, String, String, String]:
      def call(r: String, s: Integer, e: Integer, v: String, run: String): String =
        orFail(StableIds.metricsId(r, s.intValue, e.intValue, v, run))
    ,
    StringType
  )

  /** `StableIds.identityConflictId(identityVersion, policyVersion, memberIds)`. */
  val identityConflictId: UserDefinedFunction = udf(
    new UDF3[String, String, scala.collection.Seq[String], String]:
      def call(v: String, p: String, members: scala.collection.Seq[String]): String =
        orFail(StableIds.identityConflictId(v, p, seq(members)))
    ,
    StringType
  )

  /** `StableIds.graphEdgeId(HAS_TOPIC, from, to, policyVersion, sourceEvidenceIds)`. */
  val hasTopicEdgeId: UserDefinedFunction = udf(
    new UDF3[String, String, scala.collection.Seq[String], String]:
      def call(from: String, to: String, evidence: scala.collection.Seq[String]): String =
        orFail(
          StableIds
            .graphEdgeId(EdgeType.HAS_TOPIC, from, to, Versions.HasTopicPolicy, seq(evidence))
        )
    ,
    StringType
  )

  /** Normalized ORCID iD, or null when absent or malformed. */
  val normalizeOrcid: UserDefinedFunction = udf(
    new UDF1[String, String]:
      def call(v: String): String =
        if v == null then null else ExternalIds.normalizeOrcid(v).toOption.orNull
    ,
    StringType
  )

  /** Normalized OpenAlex author ID (`A<digits>`), or null when absent or malformed. */
  val normalizeOpenAlexAuthor: UserDefinedFunction = udf(
    new UDF1[String, String]:
      def call(v: String): String =
        if v == null then null else ExternalIds.normalizeOpenAlex(v, 'A').toOption.orNull
    ,
    StringType
  )

  /** `StableIds.semanticFactId(subjectType, subjectId, windowStart, windowEnd, metricVersion,
    * runId)`.
    */
  val semanticFactId: UserDefinedFunction = udf(
    new UDF6[String, String, Integer, Integer, String, String, String]:
      def call(t: String, id: String, s: Integer, e: Integer, v: String, run: String): String =
        orFail(
          StableIds
            .semanticFactId(SemanticSubjectType.valueOf(t), id, s.intValue, e.intValue, v, run)
        )
    ,
    StringType
  )

  /** `SemanticFact.truncateText`: the first 1024 code points (never splits a surrogate pair). */
  val truncateText: UserDefinedFunction = udf(
    new UDF1[String, String]:
      def call(v: String): String = if v == null then null else SemanticFact.truncateText(v)
    ,
    StringType
  )
