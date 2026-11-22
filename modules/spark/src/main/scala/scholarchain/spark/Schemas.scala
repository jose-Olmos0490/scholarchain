package scholarchain.spark

import org.apache.spark.sql.Column
import org.apache.spark.sql.functions.{array, lit, struct}
import org.apache.spark.sql.types.*
import scholarchain.core.schema.SchemaVersions

/** Explicit read schemas of the Hadoop export tables (J09 `scholarchain/spark_<table>`) and of the
  * entity-resolution decision files. Fields the Spark jobs do not use are omitted, so a reader
  * never infers types from data.
  */
object Schemas:
  private def str(n: String) = StructField(n, StringType, nullable = true)
  private def int(n: String) = StructField(n, IntegerType, nullable = true)
  private def strArray(n: String) = StructField(n, ArrayType(StringType, true), nullable = true)

  /** `provenance-v1`. */
  val Provenance: StructType = StructType(
    Seq(
      str("schemaVersion"),
      str("sourceDataset"),
      str("sourceRecordId"),
      str("snapshot"),
      str("runId"),
      str("policyVersion"),
      str("modelVersion"),
      str("promptVersion"),
      strArray("derivedFrom")
    )
  )

  private val prov = StructField("provenance", Provenance, nullable = true)

  /** `author-mention-v1`. */
  val AuthorMention: StructType = StructType(
    Seq(
      str("schemaVersion"),
      str("mentionId"),
      str("workId"),
      int("position"),
      str("rawName"),
      str("normalizedName"),
      str("sourceAuthorKey"),
      str("orcid"),
      prov
    )
  )

  /** `author-paper-v1` (J07, DBLP works only). */
  val AuthorPaper: StructType = StructType(
    Seq(
      str("schemaVersion"),
      str("mentionId"),
      str("workId"),
      int("position"),
      int("teamSize"),
      int("year"),
      str("normalizedName"),
      str("sourceAuthorKey"),
      str("orcid"),
      prov
    )
  )

  /** `coauthor-pair-candidate-v1/v2`. */
  val CoauthorPair: StructType = StructType(
    Seq(
      str("schemaVersion"),
      str("candidateId"),
      str("workId"),
      str("memberA"),
      str("memberB"),
      int("positionA"),
      int("positionB"),
      int("year"),
      int("teamSize"),
      prov
    )
  )

  /** `publication-team-v1` (also the row type of `spark_oversize_team`). */
  val PublicationTeam: StructType = StructType(
    Seq(
      str("schemaVersion"),
      str("workId"),
      int("year"),
      strArray("memberMentionIds"),
      int("teamSize"),
      prov
    )
  )

  /** `work-v1`, including the nested author mentions so a Work node row stays a full
    * `WorkRecord`.
    */
  val Work: StructType = StructType(
    Seq(
      str("schemaVersion"),
      str("workId"),
      str("source"),
      str("sourceRecordId"),
      str("recordType"),
      str("title"),
      str("normalizedTitle"),
      int("year"),
      str("venueId"),
      str("doi"),
      str("arxivId"),
      StructField("authors", ArrayType(AuthorMention, true), nullable = true),
      str("abstractTextRef"),
      prov
    )
  )

  /** `venue-v1`. */
  val Venue: StructType = StructType(
    Seq(
      str("schemaVersion"),
      str("venueId"),
      str("name"),
      str("venueType"),
      str("sourceVenueKey"),
      strArray("issns"),
      prov
    )
  )

  /** `topic-v1`. */
  val Topic: StructType = StructType(
    Seq(str("schemaVersion"), str("topicId"), str("name"), str("parentTopicId"), prov)
  )

  /** `institution-v1`. */
  val Institution: StructType = StructType(
    Seq(
      str("schemaVersion"),
      str("institutionId"),
      str("name"),
      str("rorId"),
      str("countryCode"),
      prov
    )
  )

  /** `graph-edge-v1` (source-asserted edges: HAS_TOPIC, PUBLISHED_IN, AFFILIATED_WITH). */
  val GraphEdge: StructType = StructType(
    Seq(
      str("schemaVersion"),
      str("edgeId"),
      str("fromId"),
      str("edgeType"),
      str("toId"),
      StructField("properties", MapType(StringType, StringType, true), nullable = true),
      str("policyVersion"),
      strArray("sourceEvidenceIds"),
      prov
    )
  )

  /** `match-decision-v1` (features and rules are not needed here). */
  val MatchDecision: StructType = StructType(
    Seq(
      str("schemaVersion"),
      str("decisionId"),
      str("sourceId"),
      str("targetId"),
      str("decision"),
      StructField("confidence", DoubleType, nullable = true),
      str("policyVersion"),
      str("runId"),
      prov
    )
  )

  private val nullStr: Column = lit(null).cast(StringType)

  /** A derived-record `provenance-v1` struct (source dataset `SCHOLARCHAIN`).
    *
    * @param sourceRecordId
    *   the stable ID of the primary input of the derived row
    * @param derivedFrom
    *   further input IDs (array<string>)
    */
  def derivedProvenance(
      sourceRecordId: Column,
      snapshot: String,
      runId: String,
      policyVersion: Option[String],
      derivedFrom: Column
  ): Column =
    struct(
      lit(SchemaVersions.Provenance).as("schemaVersion"),
      lit("SCHOLARCHAIN").as("sourceDataset"),
      sourceRecordId.as("sourceRecordId"),
      lit(snapshot).as("snapshot"),
      lit(runId).as("runId"),
      policyVersion.fold(nullStr)(v => lit(v)).as("policyVersion"),
      nullStr.as("modelVersion"),
      nullStr.as("promptVersion"),
      derivedFrom.as("derivedFrom")
    )

  /** An empty `array<string>` column. */
  def emptyStrings: Column = array().cast(ArrayType(StringType, true))
