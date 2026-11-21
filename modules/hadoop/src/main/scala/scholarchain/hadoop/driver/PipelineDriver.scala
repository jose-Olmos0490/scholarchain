package scholarchain.hadoop.driver

import java.io.{BufferedReader, InputStreamReader}
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.apache.hadoop.conf.{Configuration, Configured}
import org.apache.hadoop.fs.{FileSystem, Path, PathFilter}
import org.apache.hadoop.io.Text
import org.apache.hadoop.io.compress.CompressionCodecFactory
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, TextInputFormat}
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.util.{Tool, ToolRunner}
import scala.collection.immutable.TreeMap
import scala.jdk.CollectionConverters.*
import scholarchain.core.config.{ConfigLoader, DatasetConfig, HadoopConfig}
import scholarchain.core.ids.StableIds
import scholarchain.core.model.{RunInput, RunManifest, SourceType}
import scholarchain.core.schema.SchemaVersions
import scholarchain.hadoop.HadoopCounter
import scholarchain.hadoop.blocking.MatchFeatures
import scholarchain.hadoop.dblp.DblpInputFormat
import scholarchain.hadoop.io.*
import scholarchain.hadoop.jobs.*
import scholarchain.hadoop.sources.SourceEdges
import upickle.default.{read, write}

/** Accepts only part files, plus the table directories that hold them, as job input. Keeps
  * `counters.json` and markers out of every job's input.
  */
class PartFileFilter extends PathFilter:
  def accept(p: Path): Boolean =
    val n = p.getName
    n.startsWith("part-") || n.startsWith("run_id=") || n.startsWith("year=")

/** Command line of the driver. */
final case class DriverArgs(
    inputRoot: String,
    outputRoot: String,
    snapshotOverrides: Map[String, String],
    codeVersion: String,
    local: Boolean
)

object DriverArgs:
  val Usage: String =
    """usage: PipelineDriver --input-root <dir> --output-root <dir> [--local]
      |                      [--snapshot <dataset-or-relative-path>=<label>]... [--code-version <v>]
      |  Hadoop generic options (-D key=value, -conf file) go before these arguments.""".stripMargin

  def parse(args: Seq[String]): Either[String, DriverArgs] =
    def loop(rest: List[String], acc: DriverArgs): Either[String, DriverArgs] = rest match
      case Nil => Right(acc)
      case "--input-root" :: v :: t => loop(t, acc.copy(inputRoot = v))
      case "--output-root" :: v :: t => loop(t, acc.copy(outputRoot = v))
      case "--code-version" :: v :: t => loop(t, acc.copy(codeVersion = v))
      case "--local" :: t => loop(t, acc.copy(local = true))
      case "--snapshot" :: v :: t =>
        v.split("=", 2) match
          case Array(k, l) if Layout.isSafeSegment(l) =>
            loop(t, acc.copy(snapshotOverrides = acc.snapshotOverrides + (k -> l)))
          case _ =>
            Left(s"--snapshot expects <dataset-or-path>=<label> with a path-safe label, got '$v'")
      case other :: _ => Left(s"unknown argument '$other'")
    loop(args.toList, DriverArgs("", "", Map.empty, PipelineDriver.DefaultCodeVersion, false))
      .filterOrElse(
        a => a.inputRoot.nonEmpty && a.outputRoot.nonEmpty,
        "--input-root and --output-root are required"
      )

/** Main class of the Phase 4 pipeline. Runs J01 -> J01C -> J02 -> J03/J04 -> J05 -> J05b -> J06 ->
  * J07/J08 -> J09 as real MapReduce jobs, in local mode (`--local`, or whatever the loaded Hadoop
  * configuration says; on EMR that is YARN). See modules/hadoop/JOBS.md.
  */
object PipelineDriver:
  /** Code version recorded in the run manifest unless `--code-version` is given (EMR runs pass
    * the Git SHA of the packaged commit).
    */
  val DefaultCodeVersion = "scholarchain-hadoop-0.1.3-phase04"
  val PipelineStage = "hadoop-ingestion"

  def main(args: Array[String]): Unit =
    val code = ToolRunner.run(new Configuration(), new PipelineTool, args)
    if code != 0 then sys.exit(code)

/** Result of running (or skipping) one job. */
final case class JobReport(
    slug: String,
    name: String,
    status: String,
    mapTasks: Int,
    reduceTasks: Int,
    counters: Map[String, Map[String, Long]],
    tables: Vector[String]
) derives upickle.default.ReadWriter

class PipelineTool extends Configured with Tool:

  private def log(msg: String): Unit = println(s"[scholarchain-hadoop] $msg")

  override def run(args: Array[String]): Int =
    DriverArgs.parse(args.toIndexedSeq) match
      case Left(err) =>
        System.err.println(err)
        System.err.println(DriverArgs.Usage)
        2
      case Right(a) =>
        val env = sys.env
        (
          ConfigLoader.loadSection(HadoopConfig, env),
          ConfigLoader.loadSection(DatasetConfig, env)
        ) match
          case (Left(e), _) =>
            System.err.println(s"configuration error: $e")
            2
          case (_, Left(e)) =>
            System.err.println(s"configuration error: $e")
            2
          case (Right(h), Right(d)) =>
            new PipelineRun(getConf, a, h, d.dblpSubset.maxWorks, log).execute()
            0

/** One pipeline run: plan, idempotency checks, job chain, counters and summary. */
final class PipelineRun(
    baseConf: Configuration,
    args: DriverArgs,
    hadoop: HadoopConfig,
    maxWorks: Int,
    log: String => Unit
):
  private val conf = new Configuration(baseConf)
  if args.local then
    conf.set("mapreduce.framework.name", "local")
    conf.set("fs.defaultFS", "file:///")
  // Local runs keep Hadoop scratch space under ./target instead of /tmp, unless set explicitly.
  if conf.get("mapreduce.framework.name", "local") == "local" &&
    Option(conf.getPropertySources("hadoop.tmp.dir")).forall(_.forall(_.endsWith("-default.xml")))
  then conf.set("hadoop.tmp.dir", new java.io.File("target/hadoop-tmp").getAbsolutePath)

  private val inputRoot = qualify(new Path(args.inputRoot))
  private val outputRoot = qualify(new Path(args.outputRoot))
  private val outFs = outputRoot.getFileSystem(conf)

  private def qualify(p: Path): Path = p.getFileSystem(conf).makeQualified(p)

  // ---------------------------------------------------------------------------
  // Input discovery
  // ---------------------------------------------------------------------------

  private val DatePattern =
    "(?<![0-9])([0-9]{4})-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])(?![0-9])".r

  private def sha256(path: Path): String =
    val md = MessageDigest.getInstance("SHA-256")
    val in = path.getFileSystem(conf).open(path)
    try
      val buf = new Array[Byte](1 << 16)
      var n = in.read(buf)
      while n > 0 do
        md.update(buf, 0, n)
        n = in.read(buf)
    finally in.close()
    "sha256:" + md.digest().map(b => f"${b & 0xff}%02x").mkString

  /** Snapshot label rule (deterministic): an explicit `--snapshot` for the relative path, then for
    * the dataset; else a `snapshot=<label>` directory segment; else the first calendar date
    * `YYYY-MM-DD` in the file name; else `undated-<first 12 hex of the file's SHA-256>`.
    */
  def snapshotLabel(relative: String, dataset: String, checksum: String): String =
    args.snapshotOverrides
      .get(relative)
      .orElse(args.snapshotOverrides.get(dataset))
      .orElse(relative.split('/').find(_.startsWith("snapshot=")).map(_.stripPrefix("snapshot=")))
      .orElse(DatePattern.findFirstIn(relative.split('/').last))
      .getOrElse(s"undated-${checksum.stripPrefix("sha256:").take(12)}")

  private def kindOf(dataset: String, name: String): Option[InputKind] =
    val n = name.toLowerCase(java.util.Locale.ROOT)
    val jsonl = n.endsWith(".jsonl") || n.endsWith(".jsonl.gz") || n.endsWith(".jsonl.bz2")
    val xml = n.endsWith(".xml") || n.endsWith(".xml.gz") || n.endsWith(".xml.bz2")
    dataset match
      case "dblp" if xml => Some(InputKind.DblpXml)
      case "openalex" if jsonl && n.contains("author") => Some(InputKind.OpenAlexAuthors)
      case "openalex" if jsonl => Some(InputKind.OpenAlexWorks)
      case "crossref" if jsonl => Some(InputKind.CrossrefWorks)
      case "orcid" if xml => Some(InputKind.OrcidRecord)
      case _ => None

  def discoverInputs(): Vector[InputFile] =
    val fs = inputRoot.getFileSystem(conf)
    val rootStr = inputRoot.toUri.getPath.stripSuffix("/") + "/"
    val it = fs.listFiles(inputRoot, true)
    val files = Vector.newBuilder[(String, Path)]
    while it.hasNext do
      val st = it.next()
      val rel = st.getPath.toUri.getPath.stripPrefix(rootStr)
      files += rel -> st.getPath
    files.result().sortBy(_._1).flatMap { (rel, path) =>
      val segs = rel.split('/')
      if segs.length < 2 then None
      else
        val dataset = segs.head
        kindOf(dataset, segs.last).map { kind =>
          val checksum = sha256(path)
          InputFile(
            path = path.toString,
            relative = rel,
            dataset = JobSupport.sourceOfDataset(dataset),
            kind = kind,
            snapshot = snapshotLabel(rel, dataset, checksum),
            checksum = checksum
          )
        }
    }

  // ---------------------------------------------------------------------------
  // Idempotency
  // ---------------------------------------------------------------------------

  private def dirPath(rel: String): Path = new Path(outputRoot, rel)

  /** `Some(true)`: every table complete (skip); `Some(false)`: none exists (run). Throws for a
    * partial state; output is never deleted.
    */
  private def tablesState(slug: String, tables: Vector[String]): Boolean =
    val temp = new Path(outputRoot, "_temporary")
    if outFs.exists(temp) then
      throw new IllegalStateException(
        s"$temp exists: an earlier job attempt under this output root did not finish. " +
          "ScholarChain never deletes output. Inspect it, then move or remove it by hand (a human " +
          "data operation), or use a different --output-root."
      )
    val states = tables.map(t =>
      (t, outFs.exists(dirPath(t)), outFs.exists(new Path(dirPath(t), Layout.SuccessMarker)))
    )
    if states.forall(_._3) then true
    else if states.forall(s => !s._2) then false
    else
      val bad = states.filterNot(_._3).map(s => dirPath(s._1).toString)
      throw new IllegalStateException(
        s"job $slug: output exists without ${Layout.SuccessMarker}: ${bad.mkString(", ")}. This is an " +
          "incomplete earlier attempt of the same run ID. ScholarChain never deletes output: move or " +
          "remove these directories by hand (a human data operation), or use a different --output-root."
      )

  // ---------------------------------------------------------------------------
  // Job execution
  // ---------------------------------------------------------------------------

  private def newJob(slug: String, name: String, shared: Configuration): Job =
    val job = Job.getInstance(new Configuration(shared), s"scholarchain-$slug-$name")
    job.setJarByClass(classOf[PipelineTool])
    job.getConfiguration.set(PipelineConf.JobSlug, slug)
    job.setMapOutputKeyClass(classOf[Text])
    job.setMapOutputValueClass(classOf[Text])
    job.setOutputKeyClass(classOf[org.apache.hadoop.io.NullWritable])
    job.setOutputValueClass(classOf[Text])
    JobSupport.configureOutput(job, outputRoot, hadoop.intermediateCodec, hadoop.outputCodec)
    job

  private def addTableInputs(job: Job, tables: Vector[String]): Unit =
    FileInputFormat.setInputPathFilter(job, classOf[PartFileFilter])
    tables.foreach(t => FileInputFormat.addInputPath(job, dirPath(t)))

  /** Task counters that depend only on the data (no timings, heap sizes or path lengths), so
    * counters.json is identical for identical runs.
    */
  private val DeterministicTaskCounters = Set(
    "MAP_INPUT_RECORDS",
    "MAP_OUTPUT_RECORDS",
    "MAP_OUTPUT_BYTES",
    "MAP_OUTPUT_MATERIALIZED_BYTES",
    "COMBINE_INPUT_RECORDS",
    "COMBINE_OUTPUT_RECORDS",
    "REDUCE_INPUT_GROUPS",
    "REDUCE_INPUT_RECORDS"
  )

  private def countersOf(job: Job): Map[String, Map[String, Long]] =
    val ours = classOf[HadoopCounter].getName
    val task = "org.apache.hadoop.mapreduce.TaskCounter"
    val all = job.getCounters
    val pipeline =
      TreeMap.from(HadoopCounter.values.map(c => c.name -> all.findCounter(c).getValue))
    val taskCounters = TreeMap.from(
      all
        .getGroup(task)
        .asScala
        .filter(c => DeterministicTaskCounters.contains(c.getName))
        .map(c => c.getName -> c.getValue)
    )
    TreeMap(ours -> pipeline, task -> taskCounters)

  private def finish(report: JobReport): JobReport =
    val json = write(report, indent = 2)
    report.tables.foreach { t =>
      val d = dirPath(t)
      outFs.mkdirs(d)
      val c = outFs.create(new Path(d, Layout.CountersFile), false)
      try c.write(json.getBytes(StandardCharsets.UTF_8))
      finally c.close()
      outFs.create(new Path(d, Layout.SuccessMarker), false).close()
    }
    report

  private def printReport(r: JobReport): Unit =
    log(
      s"JOB ${r.slug} ${r.name}: ${r.status} (map tasks ${r.mapTasks}, reduce tasks ${r.reduceTasks})"
    )
    r.counters.foreach { (group, cs) =>
      cs.foreach((n, v) => log(s"  ${group.split('.').last}.$n = $v"))
    }
    r.tables.foreach(t => log(s"  output ${dirPath(t)}"))

  /** Runs one job unless all of its tables are complete; then writes counters.json and _SUCCESS
    * into every declared table directory.
    */
  private def runJob(slug: String, name: String, tables: Vector[String])(
      configure: Job => Unit
  ): JobReport =
    val declared = tables.distinct.sorted
    if tablesState(slug, declared) then
      val existing = read[JobReport](
        new String(
          {
            val in = outFs.open(new Path(dirPath(declared.head), Layout.CountersFile));
            try in.readAllBytes()
            finally in.close()
          },
          StandardCharsets.UTF_8
        )
      )
      log(
        s"JOB $slug $name: SKIPPED, every output table already has ${Layout.SuccessMarker} (run is idempotent)"
      )
      printReport(existing)
      existing
    else
      val job = newJob(slug, name, sharedConf)
      configure(job)
      val splits = org.apache.hadoop.util.ReflectionUtils
        .newInstance(job.getInputFormatClass, job.getConfiguration)
        .getSplits(job)
        .size
      if !job.waitForCompletion(false) then
        throw new IllegalStateException(s"job $slug $name failed: ${job.getStatus.getFailureInfo}")
      val r = finish(
        JobReport(
          slug,
          name,
          "SUCCEEDED",
          splits,
          job.getNumReduceTasks,
          countersOf(job),
          declared
        )
      )
      printReport(r)
      r

  private var sharedConf: Configuration = null

  // ---------------------------------------------------------------------------
  // Plan and chain
  // ---------------------------------------------------------------------------

  def execute(): Vector[JobReport] =
    val inputs = discoverInputs()
    val dblp = inputs.filter(_.kind == InputKind.DblpXml)
    if dblp.isEmpty then throw new IllegalArgumentException(s"no DBLP XML under $inputRoot/dblp")
    val dblpSnapshots = dblp.map(_.snapshot).distinct
    if dblpSnapshots.size != 1 then
      throw new IllegalArgumentException(
        s"one DBLP snapshot per run expected, got ${dblpSnapshots.mkString(", ")}"
      )
    val seed = dblpSnapshots.head
    val dtdPath = qualify(new Path(hadoop.dblpDtdPath))
    val dtdChecksum = sha256(dtdPath)
    val manifest = RunManifest(
      schemaVersion = SchemaVersions.RunManifest,
      pipelineStage = PipelineDriver.PipelineStage,
      codeVersion = args.codeVersion,
      inputs = inputs.map(i => RunInput(i.dataset, i.snapshot, i.relative, i.checksum)) :+
        RunInput(
          SourceType.DBLP,
          snapshotLabel(dtdPath.getName, "dblp", dtdChecksum),
          s"dtd:${hadoop.dblpDtdPath}",
          dtdChecksum
        ),
      parameters = TreeMap(
        "hadoop.numReducers" -> hadoop.numReducers.toString,
        "hadoop.maxBlockSize" -> hadoop.maxBlockSize.toString,
        "hadoop.largeTeamThreshold" -> hadoop.largeTeamThreshold.toString,
        "hadoop.maxPairExpansionTeamSize" -> hadoop.maxPairExpansionTeamSize.toString,
        "hadoop.intermediateCodec" -> hadoop.intermediateCodec,
        "hadoop.outputCodec" -> hadoop.outputCodec,
        "hadoop.dblpRecordTypes" -> hadoop.dblpRecordTypes.mkString(","),
        "hadoop.yearFrom" -> hadoop.yearFrom.toString,
        "hadoop.yearTo" -> hadoop.yearTo.toString,
        "datasets.dblpSubset.maxWorks" -> maxWorks.toString
      ),
      policyVersions = TreeMap(
        "matchFeatureVersion" -> MatchFeatures.FeatureVersion,
        "sourceEdgePolicyVersion" -> SourceEdges.PolicyVersion
      )
    )
    val runId = StableIds.runId(manifest).fold(e => throw new IllegalArgumentException(e), identity)
    log(s"run ID $runId (seed snapshot dblp=$seed)")
    inputs.foreach(i =>
      log(
        s"input ${i.relative} dataset=${i.dataset} kind=${i.kind} snapshot=${i.snapshot} ${i.checksum}"
      )
    )

    sharedConf = new Configuration(conf)
    sharedConf.set(PipelineConf.RunId, runId)
    sharedConf.set(PipelineConf.SeedSnapshot, seed)
    PipelineConf.setInputs(sharedConf, inputs)
    sharedConf.setInt(PipelineConf.MaxBlockSize, hadoop.maxBlockSize)
    sharedConf.setInt(PipelineConf.LargeTeamThreshold, hadoop.largeTeamThreshold)
    sharedConf.setInt(PipelineConf.MaxPairExpansionTeamSize, hadoop.maxPairExpansionTeamSize)
    sharedConf.set(PipelineConf.DblpRecordTypes, hadoop.dblpRecordTypes.mkString(","))
    sharedConf.setInt(PipelineConf.YearFrom, hadoop.yearFrom)
    sharedConf.setInt(PipelineConf.YearTo, hadoop.yearTo)
    sharedConf.set(PipelineConf.DtdCacheName, PipelineConf.DefaultDtdCacheName)

    def t(dataset: String, table: String, snapshot: String = seed) =
      Layout.tableDir(dataset, table, snapshot, runId)
    def sc(table: String) = t("scholarchain", table)

    // Run manifest (written once; idempotent like a table).
    val manifestTable = sc("run_manifest")
    if !tablesState("manifest", Vector(manifestTable)) then
      outFs.mkdirs(dirPath(manifestTable))
      val out = outFs.create(new Path(dirPath(manifestTable), "run-manifest.json"), false)
      try out.write(write(manifest, indent = 2).getBytes(StandardCharsets.UTF_8))
      finally out.close()
      finish(
        JobReport("manifest", "RunManifest", "WRITTEN", 0, 0, Map.empty, Vector(manifestTable))
      )

    val reports = Vector.newBuilder[JobReport]

    // J01 DblpExtractJob
    reports += runJob(
      "j01",
      "DblpExtractJob",
      Vector(
        t("dblp", DblpExtractJob.WorkExtractedTable),
        t("dblp", DblpExtractJob.HistogramTable),
        t("dblp", "rejects_j01")
      )
    ) { job =>
      job.setInputFormatClass(classOf[DblpInputFormat])
      dblp.foreach(i => FileInputFormat.addInputPath(job, new Path(i.path)))
      job.addCacheFile(new URI(dtdPath.toUri.toString + "#" + PipelineConf.DefaultDtdCacheName))
      job.setMapperClass(classOf[DblpExtractMapper])
      job.setReducerClass(classOf[DblpExtractReducer])
      job.setNumReduceTasks(hadoop.numReducers)
    }

    // Cap limit from the histogram.
    val histogram = readRows(t("dblp", DblpExtractJob.HistogramTable)).map(read[CapBucket](_))
    val counts = new Array[Long](DblpExtractJob.Buckets)
    histogram.foreach(b => counts(b.bucket) += b.works)
    val totalWorks = counts.sum
    val limit =
      if totalWorks <= maxWorks then DblpExtractJob.Buckets
      else
        var acc = 0L
        var b = 0
        while b < counts.length && acc + counts(b) <= maxWorks do
          acc += counts(b)
          b += 1
        b
    val keptWorks = counts.take(limit).sum
    log(
      s"maxWorks cap: $totalWorks distinct subset works, maxWorks=$maxWorks, bucket limit $limit, kept $keptWorks"
    )
    sharedConf.setInt(PipelineConf.CapBucketLimit, limit)

    // J01C WorkCapJob
    val dblpTables = Vector("work", "author_mention", "venue", "work_node").map(t("dblp", _))
    reports += runJob("j01c", "WorkCapJob", dblpTables :+ t("dblp", "rejects_j01c")) { job =>
      job.setInputFormatClass(classOf[TextInputFormat])
      addTableInputs(job, Vector(t("dblp", DblpExtractJob.WorkExtractedTable)))
      job.setMapperClass(classOf[WorkCapMapper])
      job.setCombinerClass(classOf[DedupCombiner])
      job.setReducerClass(classOf[DedupReducer])
      job.setNumReduceTasks(hadoop.numReducers)
    }

    // J02 SourceNormalizeJob
    val external = inputs.filterNot(_.kind == InputKind.DblpXml)
    def tablesOf(k: InputKind): Vector[String] = k match
      case InputKind.OpenAlexWorks =>
        Vector(
          "work",
          "author_mention",
          "work_node",
          "mention_node",
          "institution",
          "topic",
          "venue",
          "graph_edge"
        )
      case InputKind.OpenAlexAuthors => Vector("researcher_node", "institution", "graph_edge")
      case InputKind.CrossrefWorks =>
        Vector("work", "author_mention", "work_node", "mention_node", "venue", "graph_edge")
      case InputKind.OrcidRecord => Vector("researcher_node")
      case InputKind.DblpXml => Vector.empty
    val sourceTables = external.flatMap { i =>
      val ds = JobSupport.datasetDir(i.dataset)
      (tablesOf(i.kind) :+ "rejects_j02").map(tb => (ds, tb, i.snapshot))
    }.distinct
    if external.nonEmpty then
      reports += runJob("j02", "SourceNormalizeJob", sourceTables.map((d, tb, s) => t(d, tb, s))) {
        job =>
          job.setInputFormatClass(classOf[SourceInputFormat])
          external.foreach(i => FileInputFormat.addInputPath(job, new Path(i.path)))
          job.setMapperClass(classOf[SourceNormalizeMapper])
          job.setCombinerClass(classOf[DedupCombiner])
          job.setReducerClass(classOf[DedupReducer])
          job.setNumReduceTasks(hadoop.numReducers)
      }

    def produced(table: String): Vector[String] =
      (dblpTables.filter(_.startsWith(s"dblp/$table/")) ++
        sourceTables.filter(_._2 == table).map((d, tb, s) => t(d, tb, s))).distinct.sorted

    val workTables = produced("work")

    // J03+J04 ExactIdentifierJob
    reports += runJob(
      "j03",
      "ExactIdentifierJob",
      Vector(sc(ExactIdentifierJob.Table), sc("rejects_j03"))
    ) { job =>
      job.setInputFormatClass(classOf[TextInputFormat])
      addTableInputs(job, workTables ++ produced("researcher_node"))
      job.setMapperClass(classOf[ExactIdentifierMapper])
      job.setReducerClass(classOf[ExactIdentifierReducer])
      job.setNumReduceTasks(hadoop.numReducers)
    }

    // J05 BlockingKeyJob
    reports += runJob(
      "j05",
      "BlockingKeyJob",
      Vector(
        BlockingJobs.BlockTable,
        BlockingJobs.RekeyedTable,
        BlockingJobs.StatsPrimary,
        "rejects_j05"
      ).map(sc)
    ) { job =>
      job.setInputFormatClass(classOf[TextInputFormat])
      addTableInputs(job, workTables)
      job.setMapperClass(classOf[BlockingKeyMapper])
      job.setReducerClass(classOf[BlockingKeyReducer])
      job.setNumReduceTasks(hadoop.numReducers)
    }

    // J05b FallbackBlockingJob
    reports += runJob(
      "j05b",
      "FallbackBlockingJob",
      Vector(
        BlockingJobs.FallbackBlockTable,
        BlockingJobs.OversizeTable,
        BlockingJobs.StatsFallback,
        "rejects_j05b"
      ).map(sc)
    ) { job =>
      job.setInputFormatClass(classOf[TextInputFormat])
      addTableInputs(job, Vector(sc(BlockingJobs.RekeyedTable)))
      job.setMapperClass(classOf[FallbackBlockingMapper])
      job.setReducerClass(classOf[FallbackBlockingReducer])
      job.setNumReduceTasks(hadoop.numReducers)
    }

    // J06 CandidateJoinJob
    reports += runJob(
      "j06",
      "CandidateJoinJob",
      Vector(BlockingJobs.CandidateTable, "rejects_j06").map(sc)
    ) { job =>
      job.setInputFormatClass(classOf[TextInputFormat])
      addTableInputs(job, Vector(sc(BlockingJobs.BlockTable), sc(BlockingJobs.FallbackBlockTable)))
      job.setMapperClass(classOf[CandidateJoinMapper])
      job.setCombinerClass(classOf[CandidateMinCombiner])
      job.setReducerClass(classOf[CandidateJoinReducer])
      job.setNumReduceTasks(hadoop.numReducers)
    }

    // J07+J08 PublicationTeamJob
    val teamTables = PublicationTeamJob.Tables.map(t("dblp", _)) :+ t("dblp", "rejects_j07") :+ sc(
      PublicationTeamJob.StatsTable
    )
    reports += runJob("j07", "PublicationTeamJob", teamTables) { job =>
      job.setInputFormatClass(classOf[TextInputFormat])
      addTableInputs(job, Vector(t("dblp", "work")))
      job.setMapperClass(classOf[PublicationTeamMapper])
      job.setReducerClass(classOf[PublicationTeamReducer])
      job.setNumReduceTasks(hadoop.numReducers)
    }

    // J09 GraphCandidateExportJob
    val exportInputs =
      (dblpTables ++ PublicationTeamJob.Tables.map(t("dblp", _)) ++
        sourceTables.filterNot(_._2.startsWith("rejects")).map((d, tb, s) => t(d, tb, s)) ++
        Vector(sc(ExactIdentifierJob.Table), sc(BlockingJobs.CandidateTable))).distinct.sorted
    val exportTables = exportInputs
      .flatMap(p => Layout.parseTableRef(p + "/x"))
      .flatMap(r => GraphCandidateExportJob.Exported.get(r.table))
      .distinct
      .map(sc) :+ sc("rejects_j09")
    reports += runJob("j09", "GraphCandidateExportJob", exportTables) { job =>
      job.setInputFormatClass(classOf[TextInputFormat])
      addTableInputs(job, exportInputs)
      job.setMapperClass(classOf[GraphCandidateExportMapper])
      job.setCombinerClass(classOf[DedupCombiner])
      job.setReducerClass(classOf[DedupReducer])
      job.setNumReduceTasks(hadoop.numReducers)
    }

    val all = reports.result()
    summarize(all, runId, seed, totalWorks, keptWorks, limit, sc)
    all

  /** All JSON rows of a table (driver-side read of small statistics tables only). */
  def readRows(tableDir: String): Vector[String] =
    val dir = dirPath(tableDir)
    if !outFs.exists(dir) then Vector.empty
    else
      val factory = new CompressionCodecFactory(conf)
      outFs
        .listStatus(dir)
        .toVector
        .map(_.getPath)
        .filter(_.getName.startsWith("part-"))
        .sortBy(_.getName)
        .flatMap { p =>
          val codec = factory.getCodec(p)
          val raw = outFs.open(p)
          val in = if codec == null then raw else codec.createInputStream(raw)
          val r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
          try Iterator.continually(r.readLine()).takeWhile(_ != null).filter(_.nonEmpty).toVector
          finally r.close()
        }

  private def summarize(
      reports: Vector[JobReport],
      runId: String,
      seed: String,
      totalWorks: Long,
      keptWorks: Long,
      limit: Int,
      sc: String => String
  ): Unit =
    def best(table: String, metric: String): Option[StatRow] =
      readRows(sc(table))
        .map(read[StatRow](_))
        .filter(_.metric == metric)
        .sortBy(s => (-s.value, s.key))
        .headOption
    val maxTeam = best(PublicationTeamJob.StatsTable, "maxTeamSize")
    val maxPairs = best(PublicationTeamJob.StatsTable, "largestPairExpansion")
    val blocks = Vector(
      best(BlockingJobs.StatsPrimary, "largestBlockingGroup"),
      best(BlockingJobs.StatsFallback, "largestBlockingGroup")
    ).flatten
    val largestBlock = blocks.sortBy(s => (-s.value, s.key)).headOption
    val summary = ujson.Obj(
      "runId" -> runId,
      "seedSnapshot" -> seed,
      "outputRoot" -> outputRoot.toString,
      "dblpSubsetWorks" -> totalWorks.toDouble,
      "dblpWorksKeptAfterCap" -> keptWorks.toDouble,
      "capBucketLimit" -> limit,
      "maxTeamSize" -> maxTeam
        .map(s => ujson.Obj("value" -> s.value.toDouble, "workId" -> s.key))
        .getOrElse(ujson.Null),
      "largestPairExpansion" -> maxPairs
        .map(s => ujson.Obj("pairs" -> s.value.toDouble, "workId" -> s.key))
        .getOrElse(ujson.Null),
      "largestBlockingGroup" -> largestBlock
        .map(s => ujson.Obj("size" -> s.value.toDouble, "blockKey" -> s.key, "job" -> s.job))
        .getOrElse(ujson.Null),
      "jobs" -> ujson.Arr.from(
        reports.map(r => ujson.Obj("slug" -> r.slug, "name" -> r.name, "status" -> r.status))
      )
    )
    log(s"SUMMARY ${summary.render(indent = 2)}")
