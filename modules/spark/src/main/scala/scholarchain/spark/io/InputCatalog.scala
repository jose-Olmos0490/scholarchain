package scholarchain.spark.io

import java.security.MessageDigest
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import scholarchain.spark.Schemas

/** One located input table: the part files of one Hadoop export table of the selected run, or one
  * decision file. `checksum` is `sha256:<hex>` of the canonical listing (one
  * `<relative path> <sha256 of bytes>` line per file, sorted by path), which ties the run ID to the
  * bytes read.
  */
final case class InputTable(
    name: String,
    location: String,
    directory: String,
    files: Vector[String],
    checksum: String
)

/** Locates the Hadoop J09 export tables (`<root>/scholarchain/spark_<table>/snapshot=<s>/run_id=<hex>`)
  * and the entity-resolution decision files (`<decisionsDir>/decisions/<outcome>.jsonl`) and reads them
  * with the explicit schemas of [[Schemas]]. Raw DBLP or source files are never read.
  *
  * Driver work: directory listings and streamed SHA-256 of the input files (bounded by the number
  * and size of the bounded-run inputs; see JOBS.md, "Risks"). No table content is collected.
  */
object InputCatalog:
  /** Tables consumed, with their read schema. */
  val HadoopTables: Vector[(String, StructType)] = Vector(
    "spark_author_paper" -> Schemas.AuthorPaper,
    "spark_coauthor_pair" -> Schemas.CoauthorPair,
    "spark_work" -> Schemas.Work,
    "spark_author_mention" -> Schemas.AuthorMention,
    "spark_oversize_team" -> Schemas.PublicationTeam,
    "spark_venue" -> Schemas.Venue,
    "spark_topic" -> Schemas.Topic,
    "spark_institution" -> Schemas.Institution,
    "spark_graph_edge" -> Schemas.GraphEdge
  )

  val DecisionFiles: Vector[String] =
    Vector("accepted.jsonl", "conflict.jsonl", "rejected.jsonl", "unresolved.jsonl")

  final case class HadoopRun(snapshot: String, runHex: String)

  private val RunHex = "[0-9a-f]{64}".r

  private def fs(path: Path, conf: Configuration): FileSystem = path.getFileSystem(conf)

  /** Selects the Hadoop run: the configured `run:<hex>`, or the only run present. */
  def selectRun(root: String, runId: Option[String], conf: Configuration): HadoopRun =
    val base = new Path(root, "scholarchain/spark_work")
    val f = fs(base, conf)
    if !f.exists(base) then throw new IllegalArgumentException(s"no Hadoop export table at $base")
    val runs = f
      .listStatus(base)
      .toVector
      .filter(s => s.isDirectory && s.getPath.getName.startsWith("snapshot="))
      .flatMap { s =>
        f.listStatus(s.getPath)
          .toVector
          .filter(r => r.isDirectory && r.getPath.getName.startsWith("run_id="))
          .map(r =>
            HadoopRun(
              s.getPath.getName.stripPrefix("snapshot="),
              r.getPath.getName.stripPrefix("run_id=")
            )
          )
      }
      .filter(r => RunHex.matches(r.runHex))
      .sortBy(r => (r.snapshot, r.runHex))
    runId match
      case Some(id) =>
        val hex = id.stripPrefix("run:")
        runs.filter(_.runHex == hex) match
          case Vector(one) => one
          case Vector() =>
            throw new IllegalArgumentException(s"Hadoop run $id not found under $base")
          case many =>
            throw new IllegalArgumentException(s"Hadoop run $id found more than once: $many")
      case None =>
        runs match
          case Vector(one) => one
          case Vector() => throw new IllegalArgumentException(s"no Hadoop run under $base")
          case many =>
            throw new IllegalArgumentException(
              s"${many.size} Hadoop runs under $base; set spark.hadoopRunId " +
                "(SCHOLARCHAIN_SPARK_HADOOP_RUN_ID)"
            )

  /** Part files below `dir`, sorted, as paths relative to `dir`. */
  private def partFiles(dir: Path, conf: Configuration): Vector[String] =
    val f = fs(dir, conf)
    if !f.exists(dir) then Vector.empty
    else
      val it = f.listFiles(dir, true)
      val buf = Vector.newBuilder[String]
      val prefix = f.makeQualified(dir).toString.stripSuffix("/") + "/"
      while it.hasNext do
        val p = it.next().getPath
        if p.getName.startsWith("part-") then buf += f.makeQualified(p).toString.stripPrefix(prefix)
      buf.result().sorted

  private def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString

  private def sha256File(path: Path, conf: Configuration): String =
    val md = MessageDigest.getInstance("SHA-256")
    val in = fs(path, conf).open(path)
    try
      val buf = new Array[Byte](1 << 16)
      var n = in.read(buf)
      while n >= 0 do
        if n > 0 then md.update(buf, 0, n)
        n = in.read(buf)
    finally in.close()
    hex(md.digest())

  private def listingChecksum(dir: Path, files: Vector[String], conf: Configuration): String =
    val md = MessageDigest.getInstance("SHA-256")
    files.foreach { rel =>
      md.update(s"$rel ${sha256File(new Path(dir, rel), conf)}\n".getBytes("UTF-8"))
    }
    s"sha256:${hex(md.digest())}"

  /** Locates and fingerprints every Hadoop table of the run. A table directory must exist; it may
    * hold no part file (for example `spark_oversize_team` when no team was oversize).
    */
  def hadoopTables(root: String, run: HadoopRun, conf: Configuration): Vector[InputTable] =
    HadoopTables.map { (name, _) =>
      val rel = s"scholarchain/$name/snapshot=${run.snapshot}/run_id=${run.runHex}"
      val dir = new Path(root, rel)
      if !fs(dir, conf).exists(dir) then
        throw new IllegalArgumentException(s"missing Hadoop export table directory $dir")
      val files = partFiles(dir, conf)
      InputTable(name, s"hadoop:$rel", dir.toString, files, listingChecksum(dir, files, conf))
    }

  /** Locates and fingerprints the four decision files of the entity-resolution run. */
  def decisionTables(decisionsDir: String, conf: Configuration): Vector[InputTable] =
    val dir = new Path(decisionsDir, "decisions")
    val runName = new Path(decisionsDir).getName
    DecisionFiles.map { file =>
      val path = new Path(dir, file)
      if !fs(path, conf).exists(path) then
        throw new IllegalArgumentException(s"missing match-decision file $path")
      InputTable(
        s"decisions_${file.stripSuffix(".jsonl")}",
        s"er:$runName/decisions/$file",
        dir.toString,
        Vector(file),
        listingChecksum(dir, Vector(file), conf)
      )
    }

  /** Reads one located table (gzip or plain JSON lines) with `schema`; FAILFAST on malformed JSON.
    */
  def read(spark: SparkSession, t: InputTable, schema: StructType): DataFrame =
    if t.files.isEmpty then spark.createDataFrame(java.util.Collections.emptyList[Row](), schema)
    else
      spark.read
        .schema(schema)
        .option("mode", "FAILFAST")
        .json(t.files.map(f => new Path(t.directory, f).toString)*)
