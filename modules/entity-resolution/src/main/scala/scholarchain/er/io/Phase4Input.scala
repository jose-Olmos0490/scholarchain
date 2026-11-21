package scholarchain.er.io

import java.io.{BufferedReader, InputStream, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.core.validation.RecordContract
import scholarchain.core.validation.RecordContract.given
import upickle.default.ReadWriter

/** One Phase 4 part file: `<root>/<dataset>/<table>/snapshot=<label>/run_id=<hex>/part-*`. */
final case class TableFile(
    dataset: String,
    table: String,
    snapshot: String,
    runIdHex: String,
    path: Path,
    relative: String
)

/** The Phase 4 tables entity resolution reads, decoded and validated with the core contracts. */
final case class Phase4Data(
    works: Vector[WorkRecord],
    workNodes: Vector[GraphNode],
    mentionNodes: Vector[GraphNode],
    researcherNodes: Vector[GraphNode],
    venues: Vector[Venue],
    institutions: Vector[Institution],
    candidates: Vector[MatchCandidate],
    exactOutcomes: Vector[ExactIdentifierOutcome],
    files: Vector[TableFile],
    invalidRows: Map[String, Int]
)

/** Reads Phase 4 Hadoop output (versioned files whose schemas are defined in core). Read-only. */
object Phase4Input:

  /** Tables read, per dataset directory. */
  val SourceTables: Vector[String] =
    Vector("work", "work_node", "mention_node", "researcher_node", "venue", "institution")
  val DerivedTables: Vector[String] = Vector("match_candidate", "exact_identifier_outcome")
  val DatasetDirs: Vector[String] =
    Vector("crossref", "dblp", "openalex", "orcid", "semantic_scholar")

  private val SnapshotDir = "snapshot=(.+)".r
  private val RunDir = "run_id=([0-9a-f]{64})".r

  private def listDir(p: Path): Vector[Path] =
    if !Files.isDirectory(p) then Vector.empty
    else Using.resource(Files.list(p))(_.iterator().asScala.toVector.sortBy(_.getFileName.toString))

  /** Finds the part files of every table read. When `runIdHex` is given, only that run; otherwise
    * all files must belong to one run.
    */
  def discover(root: Path, runIdHex: Option[String]): Either[String, Vector[TableFile]] =
    val tables = DatasetDirs.flatMap(d => SourceTables.map(t => (d, t))) ++
      DerivedTables.map(t => ("scholarchain", t))
    val found = for
      (ds, table) <- tables
      snapDir <- listDir(root.resolve(ds).resolve(table))
      snapshot <- snapDir.getFileName.toString match
        case SnapshotDir(s) => Vector(s)
        case _ => Vector.empty
      runDir <- listDir(snapDir)
      run <- runDir.getFileName.toString match
        case RunDir(r) => Vector(r)
        case _ => Vector.empty
      if runIdHex.forall(_ == run)
      part <- listDir(runDir)
      if part.getFileName.toString.startsWith("part-")
    yield TableFile(ds, table, snapshot, run, part, root.relativize(part).toString)
    val runs = found.map(_.runIdHex).distinct
    if found.isEmpty then Left(s"no Phase 4 part files under $root")
    else if runs.size > 1 then
      Left(s"Phase 4 output holds several runs (${runs.mkString(", ")}); pass --hadoop-run-id")
    else if !found.exists(_.table == "match_candidate") then
      Left(s"no scholarchain/match_candidate table under $root")
    else Right(found)

  private def open(path: Path): InputStream =
    val raw = Files.newInputStream(path)
    if path.getFileName.toString.endsWith(".gz") then new GZIPInputStream(raw) else raw

  def lines(path: Path): Vector[String] =
    Using.resource(new BufferedReader(new InputStreamReader(open(path), StandardCharsets.UTF_8))) {
      r => Iterator.continually(r.readLine()).takeWhile(_ != null).filter(_.trim.nonEmpty).toVector
    }

  /** `sha256:<hex>` of the file bytes. */
  def checksum(path: Path): String =
    val md = MessageDigest.getInstance("SHA-256")
    Using.resource(Files.newInputStream(path)) { in =>
      val buf = new Array[Byte](65536)
      Iterator.continually(in.read(buf)).takeWhile(_ >= 0).foreach(n => md.update(buf, 0, n))
    }
    "sha256:" + md.digest().map(b => f"${b & 0xff}%02x").mkString

  private def decodeAll[A: ReadWriter: RecordContract](
      files: Vector[TableFile],
      table: String,
      invalid: scala.collection.mutable.Map[String, Int]
  ): Vector[A] =
    files.filter(_.table == table).flatMap { f =>
      lines(f.path).flatMap { line =>
        WireCodec.decode[A](line) match
          case Right(a) => Some(a)
          case Left(_) =>
            val k = s"${f.dataset}/$table"
            invalid.update(k, invalid.getOrElse(k, 0) + 1)
            None
      }
    }

  /** Reads and validates every table. Fails closed (finding F12): any row that does not decode
    * under its core contract stops the load with a `Left` naming the table and the row count.
    * Dropping rows silently is unsafe: a dropped `exact_identifier_outcome` CONFLICT row removes
    * the Phase 4 conflict guard for its pair, and a dropped mention or work node removes the
    * identifiers that would have contradicted a merge. On success `invalidRows` is empty.
    */
  def load(root: Path, runIdHex: Option[String]): Either[String, Phase4Data] =
    discover(root, runIdHex).flatMap { files =>
      val invalid = scala.collection.mutable.Map.empty[String, Int]
      def nodes(table: String) = decodeAll[GraphNode](files, table, invalid)
      val data = Phase4Data(
        works = decodeAll[WorkRecord](files, "work", invalid),
        workNodes = nodes("work_node"),
        mentionNodes = nodes("mention_node"),
        researcherNodes = nodes("researcher_node"),
        venues = decodeAll[Venue](files, "venue", invalid),
        institutions = decodeAll[Institution](files, "institution", invalid),
        candidates = decodeAll[MatchCandidate](files, "match_candidate", invalid),
        exactOutcomes =
          decodeAll[ExactIdentifierOutcome](files, "exact_identifier_outcome", invalid),
        files = files,
        invalidRows = invalid.toMap
      )
      if invalid.isEmpty then Right(data)
      else
        Left(
          "Phase 4 input has rows that fail their core contract (run refused, nothing decided): " +
            invalid.toVector.sorted.map((k, v) => s"$k=$v").mkString(", ")
        )
    }
