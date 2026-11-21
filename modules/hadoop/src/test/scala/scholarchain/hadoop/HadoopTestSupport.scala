package scholarchain.hadoop

import java.io.{BufferedReader, File, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as JPath, StandardCopyOption}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.compress.CompressionCodecFactory
import scholarchain.core.config.HadoopConfig
import scholarchain.hadoop.driver.{DriverArgs, JobReport, PipelineDriver, PipelineRun}
import scholarchain.hadoop.dblp.DtdEntities

/** Fixtures and helpers shared by the Hadoop specs. Everything runs in local mode (LocalJobRunner,
  * file:///) under `target/`.
  */
object HadoopTestSupport:

  /** Repository root, found by walking up from the working directory (tests fork in the module
    * directory; runMain forks in the repository root).
    */
  lazy val repoRoot: File =
    Iterator
      .iterate(new File(".").getAbsoluteFile)(_.getParentFile)
      .takeWhile(_ != null)
      .find(d => new File(d, "datasets/samples/dblp").isDirectory)
      .getOrElse(throw new IllegalStateException("cannot find the repository root"))

  lazy val dtdFile: File = new File(repoRoot, "datasets/samples/dblp/dblp-2023-06-28.dtd")
  lazy val samplesDir: File = new File(repoRoot, "datasets/samples")
  lazy val entities: DtdEntities = DtdEntities.load(Files.readAllBytes(dtdFile.toPath))

  def fixture(name: String): File =
    val url = getClass.getResource(s"/fixtures/$name")
    require(url != null, s"missing test fixture $name")
    new File(url.toURI)

  /** A fresh scratch directory under the module's target directory. */
  def scratch(name: String): File =
    val base = new File("target/test-scratch").getAbsoluteFile
    base.mkdirs()
    Files.createTempDirectory(base.toPath, name + "-").toFile

  /** Input root with `dblp/<name>` copied from the DBLP fixture (plus optional extra files). */
  def inputRoot(name: String, dblpFixture: File, extra: Seq[(String, File)] = Nil): File =
    val root = scratch(name)
    val dblp = new File(root, "dblp")
    dblp.mkdirs()
    Files.copy(dblpFixture.toPath, new File(dblp, s"dblp-2024-01-01-${dblpFixture.getName}").toPath)
    extra.foreach { (rel, f) =>
      val target = new File(root, rel)
      target.getParentFile.mkdirs()
      Files.copy(f.toPath, target.toPath, StandardCopyOption.REPLACE_EXISTING)
    }
    root

  def config(
      numReducers: Int = 2,
      maxBlockSize: Int = 200,
      largeTeamThreshold: Int = 50,
      maxPairExpansionTeamSize: Int = 2000,
      yearFrom: Int = 2000,
      yearTo: Int = 2025
  ): HadoopConfig =
    HadoopConfig(
      numReducers = numReducers,
      maxBlockSize = maxBlockSize,
      largeTeamThreshold = largeTeamThreshold,
      maxPairExpansionTeamSize = maxPairExpansionTeamSize,
      intermediateCodec = "org.apache.hadoop.io.compress.DefaultCodec",
      outputCodec = "org.apache.hadoop.io.compress.GzipCodec",
      dblpRecordTypes = Vector("article", "inproceedings"),
      yearFrom = yearFrom,
      yearTo = yearTo,
      dblpDtdPath = dtdFile.getAbsolutePath
    )

  def localConf(extra: (String, String)*): Configuration =
    val c = new Configuration()
    c.set("mapreduce.framework.name", "local")
    c.set("fs.defaultFS", "file:///")
    extra.foreach((k, v) => c.set(k, v))
    c

  final case class Result(outputRoot: File, reports: Vector[JobReport], log: Vector[String]):
    def report(slug: String): JobReport = reports.find(_.slug == slug).get
    def counter(slug: String, name: String): Long =
      report(slug).counters(classOf[HadoopCounter].getName)(name)

  def runPipeline(
      input: File,
      output: File,
      hadoop: HadoopConfig = config(),
      maxWorks: Int = 100000,
      conf: Configuration = localConf()
  ): Result =
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

  /** Directories `<dataset>/<table>/snapshot=*\/run_id=*` of one table under an output root. */
  def tableDirs(root: File, dataset: String, table: String): Vector[File] =
    val t = new File(root, s"$dataset/$table")
    if !t.isDirectory then Vector.empty
    else
      t.listFiles.toVector
        .filter(_.isDirectory)
        .flatMap(_.listFiles.toVector.filter(_.isDirectory))
        .sortBy(_.getPath)

  /** All rows of all part files below `dir` (recursively), decompressed. */
  def rowsUnder(dir: File): Vector[String] =
    val conf = new Configuration()
    val factory = new CompressionCodecFactory(conf)
    def files(f: File): Vector[File] =
      if f.isDirectory then f.listFiles.toVector.sortBy(_.getName).flatMap(files)
      else if f.getName.startsWith("part-") then Vector(f)
      else Vector.empty
    files(dir).flatMap { f =>
      val p = new Path(f.getAbsolutePath)
      val codec = factory.getCodec(p)
      val raw = Files.newInputStream(f.toPath)
      val in = if codec == null then raw else codec.createInputStream(raw)
      val r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
      try Iterator.continually(r.readLine()).takeWhile(_ != null).filter(_.nonEmpty).toVector
      finally r.close()
    }

  def rows(root: File, dataset: String, table: String): Vector[String] =
    tableDirs(root, dataset, table).flatMap(rowsUnder)

  def json(rows: Vector[String]): Vector[ujson.Value] = rows.map(ujson.read(_))

  /** Relative path -> sorted rows of every part-file directory, plus counters.json contents. */
  def logicalContent(root: File): Map[String, Vector[String]] =
    def walk(f: File): Vector[File] =
      if f.isDirectory then f.listFiles.toVector.flatMap(walk) else Vector(f)
    val base = root.toPath
    walk(root)
      .filter(f =>
        f.getName.startsWith("part-") || f.getName == "counters.json" || f.getName.endsWith(".json")
      )
      .groupBy(f => base.relativize(f.getParentFile.toPath).toString)
      .map { (dir, fs) =>
        val parts = fs.filter(_.getName.startsWith("part-"))
        val others = fs
          .filterNot(_.getName.startsWith("part-"))
          .sortBy(_.getName)
          .flatMap(f =>
            Vector(s"## ${f.getName}") ++ Files
              .readAllLines(f.toPath)
              .toArray
              .toVector
              .map(_.toString)
          )
        dir -> (parts.sortBy(_.getName).flatMap(p => rowsUnder(p)).sorted ++ others)
      }

  def write(
      file: File,
      content: String,
      charset: java.nio.charset.Charset = StandardCharsets.UTF_8
  ): File =
    file.getParentFile.mkdirs()
    Files.write(file.toPath, content.getBytes(charset))
    file

  def relPath(p: JPath): String = p.toString
