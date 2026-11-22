package scholarchain.storage.graph

import java.io.{BufferedWriter, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scholarchain.core.ids.CanonicalPair
import scholarchain.core.model.*
import scholarchain.core.validation.RecordContract
import scholarchain.storage.common.*
import scholarchain.storage.loader.StorageSchemas
import scholarchain.storage.manifest.*
import upickle.default.*

/** Header vocabulary of the Neptune openCypher bulk-load CSV (loader `format=opencypher`). */
object NeptuneSchema:
  val Id = ":ID"
  val Label = ":LABEL"
  val StartId = ":START_ID"
  val EndId = ":END_ID"
  val Type = ":TYPE"

  /** Property types used by the exports (Neptune data types, verified against the bulk-load docs). */
  val StringT = "String"
  val IntT = "Int"
  val LongT = "Long"
  val DoubleT = "Double"

  def nodeHeader(props: Vector[String]): Vector[String] = Vector(Id, Label) ++ props
  def relHeader(props: Vector[String]): Vector[String] = Vector(Id, StartId, EndId, Type) ++ props

  private val provenanceNode =
    Vector("sourceDataset:String", "snapshot:String", "runId:String")

  val ResearcherProps: Vector[String] = Vector(
    "displayName:String",
    "orcid:String",
    "dblpPid:String",
    "openAlexAuthorId:String",
    "identityVersion:String",
    "identityConfidence:Double"
  ) ++ provenanceNode

  /** Overlay file: same :ID and label as the base researcher row, so Neptune merges the properties
    * onto the existing node (documented loader behaviour for repeated node IDs).
    */
  val ResearcherMetricsProps: Vector[String] = Vector(
    "P_r:Long",
    "C_r:Long",
    "T_r:Long",
    "A_r:Double",
    "M_r:Long",
    "W_r:Double",
    "componentId:String",
    "communityId:String",
    "bridgeScore:Double",
    "coreness:Int",
    "window:String",
    "metricVersion:String"
  )

  val WorkProps: Vector[String] =
    Vector("year:Int", "recordType:String", "doi:String", "arxivId:String") ++ provenanceNode
  val VenueProps: Vector[String] =
    Vector("name:String", "venueType:String") ++ provenanceNode
  val TopicProps: Vector[String] =
    Vector("name:String", "parentTopicId:String") ++ provenanceNode
  val InstitutionProps: Vector[String] =
    Vector("name:String", "rorId:String", "countryCode:String") ++ provenanceNode

  val AuthoredProps: Vector[String] = Vector(
    "position:Int",
    "year:Int",
    "matchConfidence:Double",
    "matchDecisionId:String",
    "mentionId:String"
  ) ++ provenanceNode
  val HasTopicProps: Vector[String] =
    Vector("policyVersion:String", "sourceEvidenceIds:String") ++ provenanceNode
  val CoauthoredProps: Vector[String] = Vector(
    "jointPublicationCount:Long",
    "firstCollaborationYear:Int",
    "lastCollaborationYear:Int",
    "windowStartYear:Int",
    "windowEndYear:Int",
    "evidencePointer:String",
    "runId:String",
    "policyVersion:String"
  )

  val EvidencePointerHeader = "evidencePointer:String"

/** One CSV file in the export manifest. `role` is `node`, `node-overlay` or `relationship`. */
final case class CsvExportFile(
    file: String,
    role: String,
    label: String,
    rowCount: Long,
    sha256: String,
    sourceTable: String,
    sourceParquetFiles: Vector[String]
) derives ReadWriter

final case class SkippedTable(table: String, reason: String) derives ReadWriter

/** Export manifest, written next to (not inside) the `csv/` directory that Neptune loads, because
  * the loader reads every file under its source prefix.
  */
final case class CsvExportManifest(
    schemaVersion: String,
    sourceRunId: String,
    nodeWindow: Option[String],
    files: Vector[CsvExportFile],
    skippedTables: Vector[SkippedTable],
    rejectedRows: Long,
    provenance: Provenance
) derives ReadWriter

/** Which researcher window is stored on the Researcher node. A node is one row, so it can carry
  * one window's metrics; the other windows stay in the metrics table in DynamoDB.
  */
enum NodeWindow:
  /** The window with the widest span (ties: earliest start): the all-years window. */
  case Widest
  case Fixed(startYear: Int, endYear: Int)

final case class ExportOptions(nodeWindow: NodeWindow = NodeWindow.Widest)

object NeptuneCsvExporter:
  val CsvDir = "csv"
  val ManifestFile = "csv-export-manifest.json"

  /** Tables of the Neptune manifest that produce no CSV, with the reason. */
  private val NoCsv: Map[String, String] = Map(
    "components" -> "componentId and componentSize are denormalized into researcher_collaboration",
    "community_membership" -> "communityId is denormalized into researcher_collaboration",
    "bridge_measures" -> "bridge measures are served from DynamoDB; Neptune keeps topology",
    "coreness" -> "coreness is denormalized into researcher_collaboration",
    "cross_community_edge" -> "no relationship type is defined for it in the graph model"
  )

  /** Relationship types of the graph model that Phase 6 produces no table for. */
  val UnproducedRelationshipTypes: Vector[String] =
    Vector("PUBLISHED_IN", "AFFILIATED_WITH", "RESOLVES_TO", "DERIVED_FROM", "SUPPORTED_BY")

/** Converts the Parquet tables of a verified `NEPTUNE` load manifest into openCypher bulk-load CSV.
  *
  * Streaming: rows go from Parquet to CSV one at a time. Node and relationship IDs are the Spark
  * output IDs unchanged. A row that does not decode into its core record is recorded as a reject
  * (and counted in the export manifest); the load validator is the gate that stops a load whose
  * export lost a node an edge points at.
  */
final class NeptuneCsvExporter(rejects: RejectSink):
  import NeptuneCsvExporter.*
  import NeptuneSchema.*

  def exportRun(
      verified: VerifiedManifest,
      outDir: Path,
      options: ExportOptions = ExportOptions()
  ): Result[CsvExportManifest] =
    val csvDir = outDir.resolve(CsvDir)
    Files.createDirectories(csvDir)
    val runId = verified.manifest.runId
    val rejected = new java.util.concurrent.atomic.AtomicLong
    val files = Vector.newBuilder[CsvExportFile]

    def reject(code: String, f: RowFailure): Unit =
      rejected.incrementAndGet()
      rejects.record(StoreReject("neptune", s"${f.file}#${f.rowIndex}", code, f.reason, runId))

    def rejectRow(table: String, file: String, index: Long, code: String, why: String): Unit =
      reject(code, RowFailure(file, index, s"$table: $why"))

    /** Writes one CSV from a sequence of already-mapped rows. */
    def writeCsv(
        name: String,
        role: String,
        label: String,
        header: Vector[String],
        table: String,
        rows: Iterator[Vector[String]]
    ): Unit =
      val path = csvDir.resolve(name)
      val w = new BufferedWriter(
        new OutputStreamWriter(Files.newOutputStream(path), StandardCharsets.UTF_8)
      )
      var n = 0L
      try
        w.write(Csv.record(header))
        rows.foreach { r =>
          w.write(Csv.record(r))
          n += 1
        }
      finally w.close()
      files += CsvExportFile(
        name,
        role,
        label,
        n,
        Sha256.ofFile(path),
        table,
        verified.table(table).map(_.files.map(_.path)).getOrElse(Vector.empty)
      )

    def typed[A: ReadWriter: RecordContract](table: String)(
        f: (SourcedRow[A], A) => Option[Vector[String]]
    ): Iterator[Vector[String]] =
      TableRows.stream[A](verified, table).flatMap { sr =>
        sr.value match
          case Left(failure) =>
            reject("ROW_DECODE", failure)
            None
          case Right(a) => f(sr, a)
      }

    def s(o: Option[String]): String = o.getOrElse("")
    def oi(o: Option[Int]): String = o.fold("")(_.toString)
    def prov(p: Provenance): Vector[String] =
      Vector(p.sourceDataset.toString, p.snapshot, p.runId)

    val present = verified.manifest.tables.map(_.tableName).toSet

    if present("researcher") then
      writeCsv(
        "nodes-researcher.csv",
        "node",
        "Researcher",
        nodeHeader(ResearcherProps),
        "researcher",
        typed[CanonicalResearcher]("researcher") { (_, r) =>
          Some(
            Vector(r.researcherId, "Researcher", r.displayName, s(r.orcid), s(r.dblpPid)) ++
              Vector(s(r.openAlexAuthorId), s(r.identityVersion), r.identityConfidence.toString) ++
              prov(r.provenance)
          )
        }
      )

    // Researcher metrics overlay: one window only (see NodeWindow).
    var chosenWindow: Option[String] = None
    if present("researcher_collaboration") then
      val window: Option[(Int, Int)] = options.nodeWindow match
        case NodeWindow.Fixed(s0, e0) => Some((s0, e0))
        case NodeWindow.Widest =>
          val seen = scala.collection.mutable.Set.empty[(Int, Int)]
          TableRows.stream[ResearcherMetrics](verified, "researcher_collaboration").foreach { sr =>
            sr.value.foreach(m => seen += ((m.windowStartYear, m.windowEndYear)))
          }
          seen.toVector.sortBy((a, b) => (-(b - a), a)).headOption
      chosenWindow = window.map((a, b) => s"$a-$b")
      writeCsv(
        "nodes-researcher-metrics.csv",
        "node-overlay",
        "Researcher",
        nodeHeader(ResearcherMetricsProps),
        "researcher_collaboration",
        typed[ResearcherMetrics]("researcher_collaboration") { (_, m) =>
          if !window.contains((m.windowStartYear, m.windowEndYear)) then None
          else
            Some(
              Vector(
                m.researcherId,
                "Researcher",
                m.publicationCount.toString,
                m.distinctCoauthorCount.toString,
                m.totalCoauthorOccurrences.toString,
                m.averageCoauthorsPerPaper.toString,
                m.maximumCoauthorsOnOnePaper.toString,
                m.weightedDegree.toString,
                m.componentId,
                s(m.communityId),
                m.bridgeScore.fold("")(_.toString),
                oi(m.coreness),
                s"${m.windowStartYear}-${m.windowEndYear}",
                m.metricVersion
              )
            )
        }
      )

    if present("work") then
      writeCsv(
        "nodes-work.csv",
        "node",
        "Work",
        nodeHeader(WorkProps),
        "work",
        typed[WorkRecord]("work") { (_, w) =>
          Some(
            Vector(w.workId, "Work", oi(w.year), w.recordType, s(w.doi), s(w.arxivId)) ++
              prov(w.provenance)
          )
        }
      )
    if present("venue") then
      writeCsv(
        "nodes-venue.csv",
        "node",
        "Venue",
        nodeHeader(VenueProps),
        "venue",
        typed[Venue]("venue") { (_, v) =>
          Some(Vector(v.venueId, "Venue", v.name, s(v.venueType)) ++ prov(v.provenance))
        }
      )
    if present("topic") then
      writeCsv(
        "nodes-topic.csv",
        "node",
        "Topic",
        nodeHeader(TopicProps),
        "topic",
        typed[Topic]("topic") { (_, t) =>
          Some(Vector(t.topicId, "Topic", t.name, s(t.parentTopicId)) ++ prov(t.provenance))
        }
      )
    if present("institution") then
      writeCsv(
        "nodes-institution.csv",
        "node",
        "Institution",
        nodeHeader(InstitutionProps),
        "institution",
        typed[Institution]("institution") { (_, i) =>
          Some(
            Vector(i.institutionId, "Institution", i.name, s(i.rorId), s(i.countryCode)) ++
              prov(i.provenance)
          )
        }
      )

    if present("authored") then
      writeCsv(
        "relationships-authored.csv",
        "relationship",
        "AUTHORED",
        relHeader(AuthoredProps),
        "authored",
        typed[AuthorshipRecord]("authored") { (_, a) =>
          Some(
            Vector(
              a.authorshipId,
              a.researcherId,
              a.workId,
              EdgeType.AUTHORED.toString,
              a.position.toString,
              oi(a.year),
              a.matchConfidence.toString,
              s(a.matchDecisionId),
              a.mentionId
            ) ++ prov(a.provenance)
          )
        }
      )
    if present("has_topic") then
      writeCsv(
        "relationships-has_topic.csv",
        "relationship",
        "HAS_TOPIC",
        relHeader(HasTopicProps),
        "has_topic",
        typed[GraphEdge]("has_topic") { (sr, e) =>
          if e.edgeType != EdgeType.HAS_TOPIC then
            rejectRow(
              "has_topic",
              sr.file,
              sr.rowIndex,
              "UNEXPECTED_EDGE_TYPE",
              s"edge ${e.edgeId} has type ${e.edgeType}"
            )
            None
          else
            Some(
              Vector(
                e.edgeId,
                e.fromId,
                e.toId,
                e.edgeType.toString,
                e.policyVersion,
                e.sourceEvidenceIds.mkString(";")
              ) ++ prov(e.provenance)
            )
        }
      )
    if present("coauthored_with") then
      writeCsv(
        "relationships-coauthored_with.csv",
        "relationship",
        "COAUTHORED_WITH",
        relHeader(CoauthoredProps),
        "coauthored_with",
        typed[CoauthorEdge]("coauthored_with") { (sr, e) =>
          if !CanonicalPair.isCanonical(e.researcherA, e.researcherB) then
            rejectRow(
              "coauthored_with",
              sr.file,
              sr.rowIndex,
              "NOT_CANONICAL_PAIR",
              s"edge ${e.edgeId}: ${e.researcherA} is not before ${e.researcherB}"
            )
            None
          else
            Some(
              Vector(
                e.edgeId,
                e.researcherA,
                e.researcherB,
                EdgeType.COAUTHORED_WITH.toString,
                e.jointPublicationCount.toString,
                e.firstCollaborationYear.toString,
                e.lastCollaborationYear.toString,
                e.windowStartYear.toString,
                e.windowEndYear.toString,
                s(e.supportingWorkIdsRef),
                e.provenance.runId,
                e.policyVersion
              )
            )
        }
      )

    val skipped = verified.manifest.tables.flatMap(t =>
      NoCsv.get(t.tableName).map(SkippedTable(t.tableName, _))
    )
    val known = Set(
      "researcher",
      "researcher_collaboration",
      "work",
      "venue",
      "topic",
      "institution",
      "authored",
      "has_topic",
      "coauthored_with"
    ) ++ NoCsv.keySet
    val unknown = verified.manifest.tables
      .filterNot(t => known(t.tableName))
      .map(t => SkippedTable(t.tableName, "no Neptune mapping is defined for this table"))

    val manifest = CsvExportManifest(
      StorageSchemas.NeptuneCsvExport,
      runId,
      chosenWindow,
      files.result(),
      skipped ++ unknown,
      rejected.get,
      verified.manifest.provenance
    )
    Files.write(
      outDir.resolve(ManifestFile),
      write(manifest, indent = 2).getBytes(StandardCharsets.UTF_8)
    )
    Right(manifest)
