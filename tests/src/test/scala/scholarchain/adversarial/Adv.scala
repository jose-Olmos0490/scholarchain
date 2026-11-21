package scholarchain.adversarial

import java.io.{BufferedReader, File, InputStreamReader}
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.Files
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.compress.CompressionCodecFactory
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl
import org.apache.hadoop.mapreduce.{Job, TaskAttemptID}
import scala.jdk.CollectionConverters.*
import scholarchain.core.config.HadoopConfig
import scholarchain.core.model.WorkRecord
import scholarchain.hadoop.HadoopCounter
import scholarchain.hadoop.dblp.*
import scholarchain.hadoop.driver.{DriverArgs, JobReport, PipelineDriver, PipelineRun}
import scholarchain.hadoop.io.PipelineConf

/** Support code for the adversarial suite. Written independently of the hadoop module's own test
  * helpers (which are not on this classpath). Everything runs in local mode (LocalJobRunner,
  * file:///); scratch output goes only below `tests/target/adversarial-scratch`.
  */
object Adv:

  /** Repository root: forked tests start there, but walk up to be robust. */
  lazy val repoRoot: File =
    Iterator
      .iterate(new File(".").getAbsoluteFile)(_.getParentFile)
      .takeWhile(_ != null)
      .find(d => new File(d, "datasets/samples/dblp").isDirectory)
      .getOrElse(throw new IllegalStateException("cannot find the repository root"))

  lazy val dtdFile: File = new File(repoRoot, "datasets/samples/dblp/dblp-2023-06-28.dtd")
  lazy val entities: DtdEntities = DtdEntities.load(Files.readAllBytes(dtdFile.toPath))

  /** A syntactically valid run ID (the Phase 3 golden value). */
  val RunId = "run:a2439ecf7fe23fdbc3fce38038deb00c663b1e0302f1fcee7d34be779b5ce53c"

  val ctx: DblpContext =
    DblpContext(Set("article", "inproceedings"), 2000, 2025, "2024-01-01", RunId)

  def scratch(name: String): File =
    val base = new File(repoRoot, "tests/target/adversarial-scratch")
    base.mkdirs()
    Files.createTempDirectory(base.toPath, name + "-").toFile

  /** A complete DBLP document around the given records. */
  def dblpDoc(encoding: String, records: String*): String =
    s"""<?xml version="1.0" encoding="$encoding"?>
       |<!DOCTYPE dblp SYSTEM "dblp-2023-06-28.dtd">
       |<dblp>
       |${records.mkString("\n")}
       |</dblp>
       |""".stripMargin

  /** One article record. */
  def article(
      key: String,
      authors: Seq[String],
      title: String = "An adversarial paper.",
      year: String = "2010",
      extra: String = ""
  ): String =
    val as = authors.map(a => s"<author>$a</author>").mkString("\n")
    s"""<article mdate="2024-01-01" key="$key">
       |$as
       |<title>$title</title>
       |<year>$year</year>
       |<journal>ADV</journal>$extra
       |</article>""".stripMargin

  def write(file: File, content: String, charset: Charset = StandardCharsets.ISO_8859_1): File =
    file.getParentFile.mkdirs()
    Files.write(file.toPath, content.getBytes(charset))
    file

  def writeBytes(file: File, bytes: Array[Byte]): File =
    file.getParentFile.mkdirs()
    Files.write(file.toPath, bytes)
    file

  /** Parses and normalizes one record string; `Left` is a parse failure or a rejection code. */
  def parse(xml: String): Either[DblpParseFailure, DblpRawRecord] =
    DblpRecordParser.parseString(xml, entities)

  def work(xml: String): WorkRecord =
    parse(xml) match
      case Left(f) => throw new AssertionError(s"parse failed: $f")
      case Right(raw) =>
        DblpNormalizer.normalize(raw, ctx) match
          case DblpOutcome.Accepted(dw) => dw.work
          case other => throw new AssertionError(s"not accepted: $other")

  // ---------------------------------------------------------------------------------------------
  // Input format
  // ---------------------------------------------------------------------------------------------

  final case class Read(offset: Long, bytes: Array[Byte]):
    def text(cs: Charset): String = new String(bytes, cs)

  /** Reads every record of `file` with the given max split size; returns (splits, records). */
  def readAll(
      file: File,
      maxSplit: Long,
      types: String = "article,inproceedings"
  ): (Int, Vector[Read]) =
    val conf = new Configuration()
    conf.set(PipelineConf.DblpRecordTypes, types)
    conf.setLong("mapreduce.input.fileinputformat.split.maxsize", maxSplit)
    conf.setLong("mapreduce.input.fileinputformat.split.minsize", 1)
    val job = Job.getInstance(conf)
    FileInputFormat.addInputPath(job, new Path(file.getAbsolutePath))
    val format = new DblpInputFormat
    val splits = format.getSplits(job).asScala.toVector
    val records = splits.flatMap { split =>
      val tctx = new TaskAttemptContextImpl(job.getConfiguration, new TaskAttemptID())
      val reader = format.createRecordReader(split, tctx)
      reader.initialize(split, tctx)
      val out = Vector.newBuilder[Read]
      try
        while reader.nextKeyValue() do
          val v = reader.getCurrentValue
          out += Read(reader.getCurrentKey.get, java.util.Arrays.copyOf(v.getBytes, v.getLength))
      finally reader.close()
      out.result()
    }
    (splits.size, records)

  // ---------------------------------------------------------------------------------------------
  // Pipeline
  // ---------------------------------------------------------------------------------------------

  def config(
      numReducers: Int = 2,
      maxBlockSize: Int = 200,
      largeTeamThreshold: Int = 50,
      maxPairExpansionTeamSize: Int = 2000
  ): HadoopConfig =
    HadoopConfig(
      numReducers = numReducers,
      maxBlockSize = maxBlockSize,
      largeTeamThreshold = largeTeamThreshold,
      maxPairExpansionTeamSize = maxPairExpansionTeamSize,
      intermediateCodec = "org.apache.hadoop.io.compress.DefaultCodec",
      outputCodec = "org.apache.hadoop.io.compress.GzipCodec",
      dblpRecordTypes = Vector("article", "inproceedings"),
      yearFrom = 2000,
      yearTo = 2025,
      dblpDtdPath = dtdFile.getAbsolutePath
    )

  def localConf(extra: (String, String)*): Configuration =
    val c = new Configuration()
    c.set("mapreduce.framework.name", "local")
    c.set("fs.defaultFS", "file:///")
    extra.foreach((k, v) => c.set(k, v))
    c

  /** An input root holding one DBLP XML file (snapshot from its dated name). */
  def dblpInput(
      name: String,
      content: String,
      charset: Charset = StandardCharsets.ISO_8859_1
  ): File =
    val root = scratch(name + "-in")
    write(new File(root, "dblp/dblp-2024-01-01-adv.xml"), content, charset)
    root

  def dblpInputBytes(name: String, bytes: Array[Byte]): File =
    val root = scratch(name + "-in")
    writeBytes(new File(root, "dblp/dblp-2024-01-01-adv.xml"), bytes)
    root

  final case class Result(outputRoot: File, reports: Vector[JobReport], log: Vector[String]):
    def report(slug: String): JobReport =
      reports.find(_.slug == slug).getOrElse(throw new AssertionError(s"no job $slug"))
    def counter(slug: String, name: String): Long =
      report(slug).counters.get(classOf[HadoopCounter].getName).flatMap(_.get(name)).getOrElse(0L)
    def rows(dataset: String, table: String): Vector[String] = Adv.rows(outputRoot, dataset, table)
    def json(dataset: String, table: String): Vector[ujson.Value] =
      rows(dataset, table).map(ujson.read(_))

  def runPipeline(
      input: File,
      hadoop: HadoopConfig = config(),
      conf: Configuration = localConf(),
      maxWorks: Int = 1000000
  ): Result =
    val output = scratch("out")
    val lines = Vector.newBuilder[String]
    val args = DriverArgs(
      input.getAbsolutePath,
      output.getAbsolutePath,
      Map.empty,
      PipelineDriver.DefaultCodeVersion,
      true
    )
    val reports = new PipelineRun(conf, args, hadoop, maxWorks, l => lines += l).execute()
    Result(output, reports, lines.result())

  def tableDirs(root: File, dataset: String, table: String): Vector[File] =
    val t = new File(root, s"$dataset/$table")
    if !t.isDirectory then Vector.empty
    else
      t.listFiles.toVector
        .filter(_.isDirectory)
        .flatMap(_.listFiles.toVector.filter(_.isDirectory))
        .sortBy(_.getPath)

  def rowsUnder(dir: File): Vector[String] =
    val factory = new CompressionCodecFactory(new Configuration())
    def files(f: File): Vector[File] =
      if f.isDirectory then f.listFiles.toVector.sortBy(_.getName).flatMap(files)
      else if f.getName.startsWith("part-") then Vector(f)
      else Vector.empty
    files(dir).flatMap { f =>
      val codec = factory.getCodec(new Path(f.getAbsolutePath))
      val raw = Files.newInputStream(f.toPath)
      val in = if codec == null then raw else codec.createInputStream(raw)
      val r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
      try Iterator.continually(r.readLine()).takeWhile(_ != null).filter(_.nonEmpty).toVector
      finally r.close()
    }

  def rows(root: File, dataset: String, table: String): Vector[String] =
    tableDirs(root, dataset, table).flatMap(rowsUnder)
