package scholarchain.spark

import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Encoders, SparkSession}
import scholarchain.core.ids.{StableIds, WorkNamespace}
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions
import scholarchain.spark.graph.GraphInputs

/** Builds Hadoop-export and entity-resolution inputs as wire JSON lines, encoded from the core case
  * classes, so the tests exercise the same read schemas as the real job.
  */
object Fixture:
  val HadoopRunId: String = "run:" + "1" * 64
  val ErRunId: String = "run:" + "2" * 64
  val Snapshot = "2026-11-01"
  val IdentityVersion = "v1"
  val ErPolicy = "er-v1"

  // Valid ORCID iDs (check digit correct).
  val Orcid1 = "0000-0002-1825-0097"
  val Orcid2 = "0000-0001-5109-3700"

  def ok[A](e: Either[String, A]): A = e.fold(m => throw new IllegalArgumentException(m), a => a)

  def prov(ds: SourceType, rec: String): Provenance =
    Provenance(
      SchemaVersions.Provenance,
      ds,
      rec,
      Snapshot,
      HadoopRunId,
      None,
      None,
      None,
      Vector.empty
    )

  def dblpWorkId(name: String): String = ok(StableIds.workId(WorkNamespace.Dblp, s"conf/fx/$name"))
  def oaWorkId(n: Int): String = ok(StableIds.workId(WorkNamespace.OpenAlex, s"W$n"))
  def mentionId(workId: String, position: Int): String = ok(StableIds.mentionId(workId, position))
  def researcher(key: String): String = ok(StableIds.researcherIdScholarChain(IdentityVersion, key))
  def orcidResearcher(orcid: String): String =
    ok(StableIds.researcherIdFromExternal(scholarchain.core.ids.ResearcherNamespace.Orcid, orcid))
  def openAlexResearcher(id: String): String =
    ok(StableIds.researcherIdFromExternal(scholarchain.core.ids.ResearcherNamespace.OpenAlex, id))

  /** A DBLP work: authors are DBLP author keys in position order. */
  final case class DblpWork(
      name: String,
      year: Option[Int],
      authors: Vector[String],
      oversize: Boolean = false,
      orcids: Map[Int, String] = Map.empty
  ):
    val workId: String = dblpWorkId(name)
    def mention(position: Int): String = mentionId(workId, position)

  /** A non-DBLP (OpenAlex) author mention, identity evidence only. */
  final case class OpenAlexMention(
      workNumber: Int,
      position: Int,
      name: String,
      authorId: Option[String],
      orcid: Option[String]
  ):
    val workId: String = oaWorkId(workNumber)
    val mentionId: String = Fixture.mentionId(workId, position)

  final case class Tables(
      authorPaper: Vector[String],
      coauthorPairs: Vector[String],
      works: Vector[String],
      authorMentions: Vector[String],
      oversizeTeams: Vector[String],
      decisions: Vector[String]
  )

  def decision(
      source: String,
      target: String,
      outcome: MatchOutcome,
      confidence: Double = 0.97
  ): String =
    val id = ok(StableIds.matchDecisionId(source, target, ErPolicy))
    WireCodec.encode(
      MatchDecision(
        schemaVersion = SchemaVersions.MatchDecision,
        decisionId = id,
        sourceId = source,
        targetId = target,
        features = Map("score" -> confidence),
        rulesFired = Vector("FIXTURE"),
        decision = outcome,
        confidence = confidence,
        reasonCodes = Vector("FIXTURE"),
        policyVersion = ErPolicy,
        modelVersion = None,
        promptVersion = None,
        decidedAt = "2026-11-20T00:00:00Z",
        runId = ErRunId,
        provenance = Provenance(
          SchemaVersions.Provenance,
          SourceType.SCHOLARCHAIN,
          id,
          Snapshot,
          ErRunId,
          Some(ErPolicy),
          None,
          None,
          Vector(source, target)
        )
      )
    )

  /** Encodes works the way Hadoop J07/J09 emits them: author-paper and author-mention rows per
    * author, all n(n-1)/2 pair rows for a dated, non-oversize team of >= 2, an oversize-team row
    * (and no pairs) for an oversize work.
    */
  def tables(
      works: Vector[DblpWork],
      openAlex: Vector[OpenAlexMention] = Vector.empty,
      decisions: Vector[String] = Vector.empty,
      extraPairs: Vector[String] = Vector.empty,
      dropPairs: Set[String] = Set.empty
  ): Tables =
    val ap = Vector.newBuilder[String]
    val pairs = Vector.newBuilder[String]
    val ws = Vector.newBuilder[String]
    val ms = Vector.newBuilder[String]
    val over = Vector.newBuilder[String]
    works.foreach { w =>
      val n = w.authors.size
      val p = prov(SourceType.DBLP, s"conf/fx/${w.name}")
      val mentions = w.authors.zipWithIndex.map { (key, i) =>
        AuthorMention(
          SchemaVersions.AuthorMention,
          w.mention(i + 1),
          w.workId,
          i + 1,
          key,
          key.toLowerCase,
          Some(key),
          w.orcids.get(i + 1),
          p
        )
      }
      mentions.foreach { m =>
        ms += WireCodec.encode(m)
        ap += WireCodec.encode(
          AuthorPaperRecord(
            SchemaVersions.AuthorPaperRecord,
            m.mentionId,
            w.workId,
            m.position,
            n,
            w.year,
            m.normalizedName,
            m.sourceAuthorKey,
            m.orcid,
            p
          )
        )
      }
      ws += WireCodec.encode(
        WorkRecord(
          SchemaVersions.WorkRecord,
          w.workId,
          SourceType.DBLP,
          s"conf/fx/${w.name}",
          "inproceedings",
          s"Work ${w.name}",
          s"work ${w.name.toLowerCase}",
          w.year,
          None,
          None,
          None,
          mentions,
          None,
          p
        )
      )
      val team = PublicationTeam(
        SchemaVersions.PublicationTeam,
        w.workId,
        w.year,
        mentions.map(_.mentionId),
        n,
        p
      )
      if w.oversize then over += WireCodec.encode(team)
      else
        w.year.foreach { y =>
          for i <- 1 to n; j <- (i + 1) to n do
            val c = ok(CoauthorPairCandidate.fromPositions(w.workId, i, j, y, n, p))
            if !dropPairs.contains(s"${w.name}:$i-$j") then pairs += WireCodec.encode(c)
        }
    }
    openAlex.foreach { o =>
      ms += WireCodec.encode(
        AuthorMention(
          SchemaVersions.AuthorMention,
          o.mentionId,
          o.workId,
          o.position,
          o.name,
          o.name.toLowerCase,
          o.authorId,
          o.orcid,
          prov(SourceType.OPENALEX, s"W${o.workNumber}")
        )
      )
    }
    Tables(
      ap.result(),
      pairs.result() ++ extraPairs,
      ws.result(),
      ms.result(),
      over.result(),
      decisions
    )

  private def frame(spark: SparkSession, lines: Vector[String], schema: StructType): DataFrame =
    spark.read
      .schema(schema)
      .option("mode", "FAILFAST")
      .json(spark.createDataset(java.util.Arrays.asList(lines*))(Encoders.STRING))

  def inputs(spark: SparkSession, t: Tables): GraphInputs =
    GraphInputs(
      authorPaper = frame(spark, t.authorPaper, Schemas.AuthorPaper),
      coauthorPairs = frame(spark, t.coauthorPairs, Schemas.CoauthorPair),
      works = frame(spark, t.works, Schemas.Work),
      authorMentions = frame(spark, t.authorMentions, Schemas.AuthorMention),
      oversizeTeams = frame(spark, t.oversizeTeams, Schemas.PublicationTeam),
      venues = frame(spark, Vector.empty, Schemas.Venue),
      topics = frame(spark, Vector.empty, Schemas.Topic),
      institutions = frame(spark, Vector.empty, Schemas.Institution),
      graphEdges = frame(spark, Vector.empty, Schemas.GraphEdge),
      decisions = frame(spark, t.decisions, Schemas.MatchDecision)
    )

  /** The work order's hand-checkable fixture. */
  val HandCheckable: Vector[DblpWork] = Vector(
    DblpWork("W1", Some(2010), Vector("A", "B", "C")),
    DblpWork("W2", Some(2012), Vector("A", "B")),
    DblpWork("W3", Some(2015), Vector("C", "D")),
    DblpWork("W4", Some(2016), Vector("D", "E", "F")),
    DblpWork("W5", Some(2018), Vector("E", "F")),
    DblpWork("W6", Some(2019), Vector("G", "H")),
    DblpWork("W7", Some(2020), Vector("I"))
  )

  val AllYears: AnalysisWindow = AnalysisWindow(2000, 2025, allYears = true)
  val W2010to2015: AnalysisWindow = AnalysisWindow(2010, 2015, allYears = false)

  /** DBLP author keys for A..I whose fallback researcher IDs sort in name order (A < B < ... < I),
    * found by search: the digest of key i starts with hex digit i+1. The work order's community and
    * bridge oracle was hand-computed with labels ordered like the names; label propagation breaks
    * ties on the smallest ID, so it reproduces that oracle exactly when IDs sort like the names.
    * The default keys ("A".."I") sort as C < E < G < H < I < F < D < A < B (see CommunitySpec).
    */
  val OrderedKeys: Map[String, String] = Map(
    "A" -> "A 0002",
    "B" -> "B 0001",
    "C" -> "C 0005",
    "D" -> "D 0025",
    "E" -> "E 0076",
    "F" -> "F 0005",
    "G" -> "G 0011",
    "H" -> "H 0035",
    "I" -> "I 0020"
  )

  /** The hand-checkable fixture with the [[OrderedKeys]] author keys (same works and years). */
  val HandCheckableOrdered: Vector[DblpWork] =
    HandCheckable.map(w => w.copy(authors = w.authors.map(OrderedKeys)))
