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

  /** Starts the Neptune bulk load of the CSV that `neptune-csv` staged and waits for it to finish.
    * It is the only step that calls the Neptune endpoint, so it is never part of the default
    * targets: it must be named. It needs neither `--spark-output` nor `--snapshot` when it is the
    * only target.
    */
  case NeptuneLoad extends LoadStep("neptune")

object LoadStep:
  /** The staging steps, run by `LoadRunner`; these are the default targets. */
  val all: Vector[LoadStep] = values.toVector.filterNot(_ == NeptuneLoad)

  /** Every step a user may name. */
  val named: Vector[LoadStep] = all :+ NeptuneLoad
  def parse(s: String): Option[LoadStep] = named.find(_.wire == s)

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
    workDir: Option[Path],
    neptuneTimeoutSeconds: Option[Int] = None,
    neptunePollSeconds: Option[Int] = None
)

object LoadArgs:
  val Usage: String =
    "usage: LoadMain --spark-output <dir> --run-id <run:<64 hex>> --snapshot <label> " +
      "[--dry-run] [--targets dynamo,s3,neptune-csv,neptune] [--work-dir <dir>] " +
      "[--neptune-timeout-seconds <n>] [--neptune-poll-seconds <n>]  " +
      "(`--targets neptune` alone needs only --run-id)"

  private val ValueFlags =
    Set(
      "--spark-output",
      "--run-id",
      "--snapshot",
      "--targets",
      "--work-dir",
      "--neptune-timeout-seconds",
      "--neptune-poll-seconds"
    )

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
          run <- values.get("--run-id").toRight("--run-id is required")
          _ <- ObjectKeys.runHex(run).left.map(_.message)
          targets <- parseTargets(values.get("--targets"))
          neptuneOnly = targets == Vector(LoadStep.NeptuneLoad)
          out <- values.get("--spark-output").filter(_.trim.nonEmpty) match
            case Some(o) => Right(o)
            case None if neptuneOnly => Right("")
            case None => Left("--spark-output is required")
          snap <- values.get("--snapshot").map(_.trim).filter(_.nonEmpty) match
            case Some(sn) => Right(sn)
            case None if neptuneOnly => Right("")
            case None => Left("--snapshot is required")
          sparkOut <- if out.isEmpty then Right(Paths.get("")) else path("--spark-output", out)
          work <- values.get("--work-dir") match
            case None => Right(None)
            case Some(w) => path("--work-dir", w).map(Some(_))
          timeout <- positive("--neptune-timeout-seconds", values)
          poll <- positive("--neptune-poll-seconds", values)
        yield LoadArgs(sparkOut, run, snap, dryRun, targets, work, timeout, poll)

  private def positive(flag: String, values: Map[String, String]): Either[String, Option[Int]] =
    values.get(flag) match
      case None => Right(None)
      case Some(v) =>
        v.trim.toIntOption
          .filter(_ >= 1)
          .map(Some(_))
          .toRight(s"$flag must be an integer >= 1, got '$v'")

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
            Left(s"unknown target '$bad'; allowed: ${LoadStep.named.map(_.wire).mkString(", ")}")
          case None => Right(parsed.collect { case Right(t) => t }.distinct)
