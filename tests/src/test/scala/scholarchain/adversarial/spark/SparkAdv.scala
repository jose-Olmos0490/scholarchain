package scholarchain.adversarial.spark

import java.io.File
import java.nio.file.Files
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Encoders, Row, SparkSession}
import scholarchain.core.config.BenchmarkPair
import scholarchain.core.ids.{ResearcherNamespace, StableIds, WorkNamespace}
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions
import scholarchain.spark.graph.{
  Analytics,
  AnalyticsOutputs,
  AnalyticsParams,
  BuildParams,
  GraphBuilder,
  GraphInputs,
  GraphOutputs
}
import scholarchain.spark.run.GraphPipeline
import scholarchain.spark.{AnalysisWindow, CoreRecords, Schemas, Windows}

/** Support code for the adversarial Spark suite. Written independently of the spark module's own
  * test helpers (module test classes are not on this classpath). Inputs are built as wire JSON
  * lines encoded from the core case classes and read with the job's own explicit schemas, so the
  * tests exercise the same read path as the real job. Everything runs in local mode; scratch
  * output (checkpoints) goes only below `tests/target/spark-adversarial-scratch`.
  */
object SparkAdv:

  // ------------------------------------------------------------------ session

  lazy val spark: SparkSession =
    val s = GraphPipeline
      .configure(
        SparkSession
          .builder()
          .master("local[2]")
          .appName("scholarchain-adversarial-spark")
          .config("spark.ui.enabled", "false")
          // Bounded status-store history: the suites run hundreds of SQL executions in one JVM.
          .config("spark.sql.ui.retainedExecutions", "10")
          .config("spark.ui.retainedJobs", "20")
          .config("spark.ui.retainedStages", "20")
          .config("spark.ui.retainedTasks", "200"),
        4
      )
      .getOrCreate()
    s.sparkContext.setLogLevel("ERROR")
    val scratch = new File("tests/target/spark-adversarial-scratch").getAbsoluteFile
    scratch.mkdirs()
    s.sparkContext.setCheckpointDir(
      Files.createTempDirectory(scratch.toPath, "ckpt").toString
    )
    s

  // ------------------------------------------------------------------ identifiers

  val HadoopRunId: String = "run:" + "a" * 64
  val ErRunId: String = "run:" + "b" * 64
  val RunId: String = "run:" + "c" * 64
  val Snapshot = "2026-11-01"
  val IdentityVersion = "v1"
  val ErPolicy = "er-adv-v1"

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

  def dblpWorkId(name: String): String = ok(StableIds.workId(WorkNamespace.Dblp, s"conf/adv/$name"))
  def oaWorkId(n: Int): String = ok(StableIds.workId(WorkNamespace.OpenAlex, s"W$n"))
  def mentionId(workId: String, position: Int): String = ok(StableIds.mentionId(workId, position))

  /** The fallback researcher ID of a DBLP author key (no merge). */
  def rid(key: String): String = ok(StableIds.researcherIdScholarChain(IdentityVersion, key))
  def orcidRid(orcid: String): String =
    ok(StableIds.researcherIdFromExternal(ResearcherNamespace.Orcid, orcid))
  def openAlexRid(id: String): String =
    ok(StableIds.researcherIdFromExternal(ResearcherNamespace.OpenAlex, id))

  /** A valid ORCID iD built from 15 digits (ISO 7064 11-2 check character computed here). */
  def orcidFrom(fifteenDigits: String): String =
    require(fifteenDigits.length == 15 && fifteenDigits.forall(_.isDigit))
    val total = fifteenDigits.foldLeft(0)((acc, c) => (acc + (c - '0')) * 2)
    val r = (12 - total % 11) % 11
    val check = if r == 10 then 'X' else ('0' + r).toChar
    val all = fifteenDigits + check
    all.grouped(4).mkString("-")

  // ------------------------------------------------------------------ fixture records

  /** A DBLP work: `authors` are DBLP author keys in position order (a key may repeat). */
  final case class Work(
      name: String,
      year: Option[Int],
      authors: Vector[String],
      oversize: Boolean = false,
      orcids: Map[Int, String] = Map.empty
  ):
    val workId: String = dblpWorkId(name)
    def mention(position: Int): String = mentionId(workId, position)

  /** A non-DBLP (OpenAlex) author mention: identity evidence only. */
  final case class OaMention(
      workNumber: Int,
      position: Int,
      name: String,
      authorId: Option[String],
      orcid: Option[String]
  ):
    val workId: String = oaWorkId(workNumber)
    val mentionId: String = SparkAdv.mentionId(workId, position)

  final case class Tables(
      authorPaper: Vector[String],
      coauthorPairs: Vector[String],
      works: Vector[String],
      authorMentions: Vector[String],
      oversizeTeams: Vector[String],
      decisions: Vector[String]
  ):
    def reversed: Tables =
      Tables(
        authorPaper.reverse,
        coauthorPairs.reverse,
        works.reverse,
        authorMentions.reverse,
        oversizeTeams.reverse,
        decisions.reverse
      )

  def decision(
      source: String,
      target: String,
      outcome: MatchOutcome,
      confidence: Double = 0.95
  ): String =
    val id = ok(StableIds.matchDecisionId(source, target, ErPolicy))
    WireCodec.encode(
      MatchDecision(
        schemaVersion = SchemaVersions.MatchDecision,
        decisionId = id,
        sourceId = source,
        targetId = target,
        features = Map("score" -> confidence),
        rulesFired = Vector("ADVERSARIAL"),
        decision = outcome,
        confidence = confidence,
        reasonCodes = Vector("ADVERSARIAL"),
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

  /** One pair-candidate wire line (positions are 1-based). */
  def pairLine(w: Work, i: Int, j: Int, year: Int): String =
    WireCodec.encode(
      ok(
        CoauthorPairCandidate
          .fromPositions(w.workId, i, j, year, w.authors.size, prov(SourceType.DBLP, w.name))
      )
    )

  /** The same pair candidate with memberA/memberB (and positions) swapped. */
  def swappedPairLine(w: Work, i: Int, j: Int, year: Int): String =
    val c = ok(
      CoauthorPairCandidate
        .fromPositions(w.workId, i, j, year, w.authors.size, prov(SourceType.DBLP, w.name))
    )
    WireCodec.encode(
      c.copy(
        memberA = c.memberB,
        memberB = c.memberA,
        positionA = c.positionB,
        positionB = c.positionA
      )
    )

  /** Encodes works the way Hadoop J07/J09 emits them: one author-paper and author-mention row per
    * author slot, all n(n-1)/2 pair rows for a dated, non-oversize team of at least two, and a
    * publication-team row (with no pairs) for an oversize work.
    */
  def tables(
      works: Vector[Work],
      openAlex: Vector[OaMention] = Vector.empty,
      decisions: Vector[String] = Vector.empty,
      extraPairs: Vector[String] = Vector.empty
  ): Tables =
    val ap = Vector.newBuilder[String]
    val pairs = Vector.newBuilder[String]
    val ws = Vector.newBuilder[String]
    val ms = Vector.newBuilder[String]
    val over = Vector.newBuilder[String]
    works.foreach { w =>
      val n = w.authors.size
      val p = prov(SourceType.DBLP, s"conf/adv/${w.name}")
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
          s"conf/adv/${w.name}",
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
          for i <- 1 to n; j <- (i + 1) to n do pairs += pairLine(w, i, j, y)
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

  private def frame(lines: Vector[String], schema: StructType): DataFrame =
    spark.read
      .schema(schema)
      .option("mode", "FAILFAST")
      .json(spark.createDataset(java.util.Arrays.asList(lines*))(Encoders.STRING))

  def inputs(t: Tables): GraphInputs =
    GraphInputs(
      authorPaper = frame(t.authorPaper, Schemas.AuthorPaper),
      coauthorPairs = frame(t.coauthorPairs, Schemas.CoauthorPair),
      works = frame(t.works, Schemas.Work),
      authorMentions = frame(t.authorMentions, Schemas.AuthorMention),
      oversizeTeams = frame(t.oversizeTeams, Schemas.PublicationTeam),
      venues = frame(Vector.empty, Schemas.Venue),
      topics = frame(Vector.empty, Schemas.Topic),
      institutions = frame(Vector.empty, Schemas.Institution),
      graphEdges = frame(Vector.empty, Schemas.GraphEdge),
      decisions = frame(t.decisions, Schemas.MatchDecision)
    )

  // ------------------------------------------------------------------ windows and parameters

  val AllYears: AnalysisWindow = AnalysisWindow(2000, 2025, allYears = true)
  val W2010to2015: AnalysisWindow = AnalysisWindow(2010, 2015, allYears = false)
  val W2016to2020: AnalysisWindow = AnalysisWindow(2016, 2020, allYears = false)
  val ThreeWindows: Vector[AnalysisWindow] = Vector(AllYears, W2010to2015, W2016to2020)

  def params(
      windows: Vector[AnalysisWindow] = ThreeWindows,
      maxClusterSize: Int = 50,
      maxIterations: Int = 60,
      k: Int = 3
  ): BuildParams =
    BuildParams(
      runId = RunId,
      snapshot = Snapshot,
      windows = windows,
      scope = AnalysisWindow(2000, 2025, allYears = false),
      coauthorPolicyVersion = "coauthor-policy-v1",
      representativeWorkLimit = k,
      identityVersion = IdentityVersion,
      maxClusterSize = maxClusterSize,
      componentsMaxIterations = maxIterations,
      checkpointInterval = 3,
      checkpoint = true
    )

  def analyticsParams(
      benchmark: Vector[BenchmarkPair] = Vector.empty,
      maxDepth: Int = 8,
      communityMaxIterations: Int = 40,
      kcoreMaxIterations: Int = 200
  ): AnalyticsParams =
    AnalyticsParams(
      communityMaxIterations = communityMaxIterations,
      kcoreMaxIterations = kcoreMaxIterations,
      pathMaxDepth = maxDepth,
      benchmarkPairs = benchmark,
      samplePairCount = 3,
      pathSeed = 23L,
      diameterSampleSources = 4,
      diameterSeed = 29L,
      diameterMinComponentSize = 2,
      distanceDepthBound = 60,
      topEntityLimit = 3,
      memberLimit = 5
    )

  def build(t: Tables, p: BuildParams = params()): GraphOutputs =
    GraphBuilder.build(spark, inputs(t), p)

  def analytics(
      out: GraphOutputs,
      a: AnalyticsParams = analyticsParams(),
      p: BuildParams = params()
  ): AnalyticsOutputs = Analytics.build(spark, out, p, a)

  // ------------------------------------------------------------------ row access (tests only)

  /** Rows of one window (bounded fixture data; tests only). */
  def inWindow(df: DataFrame, w: AnalysisWindow): Vector[Row] =
    df.where(col(Windows.Start) === w.startYear && col(Windows.End) === w.endYear)
      .collect()
      .toVector

  def long(r: Row, f: String): Long = r.getAs[Any](f) match
    case n: java.lang.Number => n.longValue
    case other => throw new IllegalStateException(s"$f is not a number: $other")

  def dbl(r: Row, f: String): Double = r.getAs[Any](f) match
    case n: java.lang.Number => n.doubleValue
    case other => throw new IllegalStateException(s"$f is not a number: $other")

  def str(r: Row, f: String): String = r.getAs[String](f)

  def strs(r: Row, f: String): Vector[String] =
    if r.isNullAt(r.fieldIndex(f)) then Vector.empty else r.getSeq[String](r.fieldIndex(f)).toVector

  /** Contract-violation messages of `df` against its core record. */
  def violations(df: DataFrame, kind: CoreRecords.Kind): Vector[String] =
    CoreRecords
      .withViolations(df, kind)
      .where(col("contractViolation").isNotNull)
      .select("contractViolation")
      .collect()
      .map(_.getString(0))
      .toVector

  /** Every row of `df` as a canonical JSON string, sorted (for exact table equality). */
  def canonical(df: DataFrame): Vector[String] =
    df.select(df.columns.sorted.map(col).toSeq*).toJSON.collect().toVector.sorted

/** Base of the adversarial Spark suites. All suites share one forked JVM (and one local
  * SparkSession) with the Hadoop adversarial suites. The jobs persist intermediate frames for
  * their single run, and a suite's retained outputs keep plans, broadcasts and cached blocks
  * reachable. Each suite therefore keeps its shared outputs in [[memo]] holders, and when it
  * finishes it drops them, releases every cached block and requests a GC so Spark's context
  * cleaner can free broadcasts and shuffles. Without this the default test heap is exhausted.
  */
trait SparkSuite extends org.scalatest.funsuite.AnyFunSuite with org.scalatest.BeforeAndAfterAll:
  final class Memo[A](f: => A):
    private var v: Option[A] = None
    def apply(): A = v.getOrElse { val x = f; v = Some(x); x }
    def clear(): Unit = v = None

  private val memos = scala.collection.mutable.ListBuffer.empty[Memo[?]]

  /** A lazily computed shared value that is released when the suite finishes. */
  protected def memo[A](f: => A): Memo[A] =
    val m = Memo(f)
    memos += m
    m

  override def afterAll(): Unit =
    try
      memos.foreach(_.clear())
      SparkSession.getDefaultSession.foreach { s =>
        s.catalog.clearCache()
        s.sparkContext.getPersistentRDDs.values.foreach(_.unpersist(blocking = true))
      }
      System.gc()
    finally super.afterAll()
