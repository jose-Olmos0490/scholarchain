package scholarchain.storage.loader

import java.nio.file.{Path, Paths}
import scholarchain.storage.objects.ObjectKeys

/** One load step the entry point can run. */
enum LoadStep(val wire: String):
  /** Graph read model (and researcher metric cache, when configured) into DynamoDB. */
  case Dynamo extends LoadStep("dynamo")

  /** Spark output files into ordinary S3 under `run_id=<hex>/`. */
  case S3 extends LoadStep("s3")

  /** Neptune openCypher bulk-load CSV written to S3. The Neptune endpoint is never called. */
  case NeptuneCsv extends LoadStep("neptune-csv")

object LoadStep:
  val all: Vector[LoadStep] = values.toVector
  def parse(s: String): Option[LoadStep] = all.find(_.wire == s)

/** Parsed command line of `LoadMain`.
  *
  * @param sparkOutput
  *   the Spark graph output directory (the directory that holds `load-manifests/`, or its parent)
  * @param workDir
  *   scratch directory for the CSV export and the rejects file; a temp directory when absent
  */
final case class LoadArgs(
    sparkOutput: Path,
    runId: String,
    snapshot: String,
    dryRun: Boolean,
    targets: Vector[LoadStep],
    workDir: Option[Path]
)

object LoadArgs:
  val Usage: String =
    "usage: LoadMain --spark-output <dir> --run-id <run:<64 hex>> --snapshot <label> " +
      "[--dry-run] [--targets dynamo,s3,neptune-csv] [--work-dir <dir>]"

  private val ValueFlags =
    Set("--spark-output", "--run-id", "--snapshot", "--targets", "--work-dir")

  def parse(args: Seq[String]): Either[String, LoadArgs] =
    // split "--flag=value" into "--flag", "value"
    val tokens = args.flatMap { a =>
      if a.startsWith("--") && a.contains('=') then
        val i = a.indexOf('=')
        Seq(a.take(i), a.drop(i + 1))
      else Seq(a)
    }
    var values = Map.empty[String, String]
    var dryRun = false
    var error: Option[String] = None
    var i = 0
    while error.isEmpty && i < tokens.size do
      val t = tokens(i)
      if t == "--dry-run" then
        if dryRun then error = Some("--dry-run given twice")
        dryRun = true
        i += 1
      else if ValueFlags.contains(t) then
        if values.contains(t) then error = Some(s"$t given twice")
        else if i + 1 >= tokens.size || tokens(i + 1).startsWith("--") then
          error = Some(s"$t needs a value")
        else values += t -> tokens(i + 1)
        i += 2
      else error = Some(s"unknown argument '$t'")
    error match
      case Some(e) => Left(e)
      case None =>
        for
          out <- values
            .get("--spark-output")
            .filter(_.trim.nonEmpty)
            .toRight("--spark-output is required")
          run <- values.get("--run-id").toRight("--run-id is required")
          _ <- ObjectKeys.runHex(run).left.map(_.message)
          snap <- values
            .get("--snapshot")
            .map(_.trim)
            .filter(_.nonEmpty)
            .toRight("--snapshot is required")
          targets <- parseTargets(values.get("--targets"))
          sparkOut <- path("--spark-output", out)
          work <- values.get("--work-dir") match
            case None => Right(None)
            case Some(w) => path("--work-dir", w).map(Some(_))
        yield LoadArgs(sparkOut, run, snap, dryRun, targets, work)

  private def path(flag: String, raw: String): Either[String, Path] =
    try Right(Paths.get(raw))
    catch
      case e: java.nio.file.InvalidPathException =>
        Left(s"$flag is not a valid path: ${e.getMessage}")

  private def parseTargets(raw: Option[String]): Either[String, Vector[LoadStep]] = raw match
    case None => Right(LoadStep.all)
    case Some(s) =>
      val names = s.split(",", -1).toVector.map(_.trim)
      if names.exists(_.isEmpty) then Left("--targets has an empty entry")
      else
        val parsed = names.map(n => LoadStep.parse(n).toRight(n))
        parsed.collectFirst { case Left(bad) => bad } match
          case Some(bad) =>
            Left(s"unknown target '$bad'; allowed: ${LoadStep.all.map(_.wire).mkString(", ")}")
          case None => Right(parsed.collect { case Right(t) => t }.distinct)
