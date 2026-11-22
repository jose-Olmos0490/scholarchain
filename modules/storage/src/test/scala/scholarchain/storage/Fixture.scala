package scholarchain.storage

import java.nio.file.{Files, Path, Paths}
import scholarchain.core.ids.*
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions as SV
import scholarchain.storage.common.Sha256
import upickle.default.*

/** One Parquet table of the fixture: its schema, and its files with their rows. */
final case class FixtureTable(
    name: String,
    recordType: String,
    schemaVersion: String,
    location: String,
    partitionColumns: Vector[String],
    schema: Vector[(String, PType)],
    files: Vector[(String, Vector[ujson.Obj])]
):
  def rowCount: Long = files.map(_._2.size.toLong).sum

/** A small, hand-checkable Phase 6 style run:
  *
  * {{{
  *   researchers  A (Henry Samueli, ORCID), B (Josiah Carberry, ORCID), C (Walden C. Rhines, OpenAlex)
  *   works        W1 (2010: A, B)   W2 (2012: A, B)   W3 (2013: B, C)
  *   edges        window 2010-2013: A-B (2 joint, 2010..2012), B-C (1, 2013)
  *                window 2010-2012: A-B (2 joint, 2010..2012)
  *   metrics      window 2010-2013 for A, B, C; window 2010-2012 for A, B
  * }}}
  *
  * Every ID comes from the Phase 3 `StableIds` functions; every record is checked against its
  * core contract by the tests that decode it.
  */
object Fixture:
  private def ok(e: Either[String, String]): String =
    e.fold(m => throw new IllegalStateException(m), identity)

  val Snapshot = "2026-11-01"
  val MetricVersion = "fixture-metrics-v1"
  val CoauthorPolicy = "fixture-coauthor-policy-v1"
  val TopicPolicy = "fixture-topic-policy-v1"

  val runManifest: RunManifest = RunManifest(
    schemaVersion = SV.RunManifest,
    pipelineStage = "spark-graph",
    codeVersion = "storage-fixture",
    inputs = Vector(
      RunInput(
        SourceType.DBLP,
        Snapshot,
        "fixture/dblp-excerpt.xml",
        "sha256:b5d5c46dbe60284c46ddc50174ea2106fd6f55f4623611c310dc8e0b177428a1"
      )
    ),
    parameters = Map("fixture" -> "storage-v1"),
    policyVersions = Map("coauthorPolicy" -> CoauthorPolicy)
  )
  val runId: String = ok(StableIds.runId(runManifest))
  val runHex: String = runId.stripPrefix("run:")

  def prov(
      dataset: SourceType,
      source: String,
      policy: Option[String] = None,
      derivedFrom: Vector[String] = Vector.empty
  ): Provenance =
    Provenance(SV.Provenance, dataset, source, Snapshot, runId, policy, None, None, derivedFrom)

  private val derived = (source: String) =>
    prov(SourceType.SCHOLARCHAIN, source, Some(CoauthorPolicy))

  // ----- entities -----
  val topic: Topic = Topic(
    SV.Topic,
    ok(StableIds.topicId(TopicNamespace.OpenAlex, "T10363")),
    "Low-power high-performance VLSI design",
    None,
    Provenance(
      SV.Provenance,
      SourceType.OPENALEX,
      "https://openalex.org/T10363",
      "api-2026-11-20",
      runId,
      None,
      None,
      None,
      Vector.empty
    )
  )
  val institution: Institution = Institution(
    SV.Institution,
    ok(StableIds.institutionId(InstitutionNamespace.OpenAlex, "I177725633")),
    "Chinese University of Hong Kong",
    Some("00t33hh48"),
    Some("HK"),
    Provenance(
      SV.Provenance,
      SourceType.OPENALEX,
      "https://openalex.org/I177725633",
      "api-2026-11-20",
      runId,
      None,
      None,
      None,
      Vector.empty
    )
  )
  val venue: Venue = Venue(
    SV.Venue,
    ok(StableIds.venueId(VenueNamespace.Dblp, "conf/dac")),
    "DAC",
    Some("conference"),
    Some("conf/dac"),
    Vector("0738-100X"),
    prov(SourceType.DBLP, "conf/dac")
  )

  private def researcher(
      name: String,
      orcid: Option[String],
      openAlex: Option[String]
  ): CanonicalResearcher =
    val id = orcid
      .map(o => ok(StableIds.researcherIdFromExternal(ResearcherNamespace.Orcid, o)))
      .getOrElse(ok(StableIds.researcherIdFromExternal(ResearcherNamespace.OpenAlex, openAlex.get)))
    CanonicalResearcher(
      SV.CanonicalResearcher,
      id,
      name,
      orcid,
      None,
      openAlex,
      Vector(name),
      None,
      Vector(institution.institutionId),
      1.0,
      derived(id)
    )

  val resA: CanonicalResearcher = researcher("Henry Samueli", Some("0000-0003-3175-3913"), None)
  val resB: CanonicalResearcher = researcher("Josiah Carberry", Some("0000-0002-1825-0097"), None)
  val resC: CanonicalResearcher = researcher("Walden C. Rhines", None, Some("A5110371381"))
  val researchers: Vector[CanonicalResearcher] = Vector(resA, resB, resC)

  private def work(key: String, title: String, year: Int): WorkRecord =
    val id = ok(StableIds.workId(WorkNamespace.Dblp, key))
    WorkRecord(
      SV.WorkRecord,
      id,
      SourceType.DBLP,
      key,
      "inproceedings",
      title,
      title.toLowerCase,
      Some(year),
      Some(venue.venueId),
      None,
      None,
      Vector.empty,
      None,
      prov(SourceType.DBLP, key)
    )

  val w1: WorkRecord = work("conf/dac/ExampleAB01", "Fixture paper one", 2010)
  val w2: WorkRecord = work("conf/dac/ExampleAB02", "Fixture paper two", 2012)
  val w3: WorkRecord = work("conf/dac/ExampleBC03", "Fixture paper three", 2013)
  val works: Vector[WorkRecord] = Vector(w1, w2, w3)

  private def authored(w: WorkRecord, r: CanonicalResearcher, position: Int): AuthorshipRecord =
    val mention = ok(StableIds.mentionId(w.workId, position))
    AuthorshipRecord(
      SV.AuthorshipRecord,
      ok(StableIds.authorshipId(w.workId, mention, r.researcherId)),
      r.researcherId,
      w.workId,
      mention,
      position,
      w.year,
      1.0,
      None,
      prov(SourceType.DBLP, w.sourceRecordId)
    )

  val authorships: Vector[AuthorshipRecord] = Vector(
    authored(w1, resA, 1),
    authored(w1, resB, 2),
    authored(w2, resA, 1),
    authored(w2, resB, 2),
    authored(w3, resB, 1),
    authored(w3, resC, 2)
  )

  val hasTopic: GraphEdge = GraphEdge(
    SV.GraphEdge,
    ok(
      StableIds.graphEdgeId(
        EdgeType.HAS_TOPIC,
        w1.workId,
        topic.topicId,
        TopicPolicy,
        Vector(w1.workId)
      )
    ),
    w1.workId,
    EdgeType.HAS_TOPIC,
    topic.topicId,
    Map.empty,
    TopicPolicy,
    Vector(w1.workId),
    prov(SourceType.SCHOLARCHAIN, w1.workId, Some(TopicPolicy), Vector(w1.workId))
  )

  // ----- graph -----
  def pair(x: CanonicalResearcher, y: CanonicalResearcher): (String, String) =
    val p = CanonicalPair
      .of(x.researcherId, y.researcherId)
      .fold(m => throw new IllegalStateException(m), identity)
    (p.a, p.b)

  def edge(
      x: CanonicalResearcher,
      y: CanonicalResearcher,
      ws: Int,
      we: Int,
      joint: Long,
      first: Int,
      last: Int,
      works: Vector[WorkRecord]
  ): CoauthorEdge =
    val (ra, rb) = pair(x, y)
    val id = ok(StableIds.coauthorEdgeId(ra, rb, ws, we, CoauthorPolicy))
    CoauthorEdge(
      SV.CoauthorEdge,
      id,
      ra,
      rb,
      ws,
      we,
      joint,
      first,
      last,
      works.map(_.workId),
      Some(CoauthorEdgeSupport.supportRef(id)),
      CoauthorPolicy,
      prov(SourceType.SCHOLARCHAIN, ra, Some(CoauthorPolicy), Vector(rb))
    )

  val edgeAbAll: CoauthorEdge = edge(resA, resB, 2010, 2013, 2, 2010, 2012, Vector(w1, w2))
  val edgeBcAll: CoauthorEdge = edge(resB, resC, 2010, 2013, 1, 2013, 2013, Vector(w3))
  val edgeAbEarly: CoauthorEdge = edge(resA, resB, 2010, 2012, 2, 2010, 2012, Vector(w1, w2))

  /** Spark writes one directory per window, so the fixture has two files. */
  val edgesAll: Vector[CoauthorEdge] = Vector(edgeAbAll, edgeBcAll)
  val edgesEarly: Vector[CoauthorEdge] = Vector(edgeAbEarly)

  private val smallestOf = (ids: Vector[String]) => ids.min

  private def metrics(
      r: CanonicalResearcher,
      ws: Int,
      we: Int,
      p: Long,
      coauthors: Long,
      total: Long,
      weighted: Double,
      component: Vector[CanonicalResearcher],
      maxWork: WorkRecord
  ): ResearcherMetrics =
    val comp = smallestOf(component.map(_.researcherId))
    ResearcherMetrics(
      SV.ResearcherMetrics,
      ok(StableIds.metricsId(r.researcherId, ws, we, MetricVersion, runId)),
      r.researcherId,
      ws,
      we,
      p,
      coauthors,
      total,
      1.0,
      1L,
      Vector(maxWork.workId),
      weighted,
      comp,
      component.size.toLong,
      Some(comp),
      None,
      None,
      Some(1),
      MetricVersion,
      prov(SourceType.SCHOLARCHAIN, r.researcherId, Some(CoauthorPolicy))
    )

  val metricsAll: Vector[ResearcherMetrics] = Vector(
    metrics(resA, 2010, 2013, 2, 1, 2, 2.0, Vector(resA, resB, resC), w1),
    metrics(resB, 2010, 2013, 3, 2, 3, 3.0, Vector(resA, resB, resC), w1),
    metrics(resC, 2010, 2013, 1, 1, 1, 1.0, Vector(resA, resB, resC), w3)
  )
  val metricsEarly: Vector[ResearcherMetrics] = Vector(
    metrics(resA, 2010, 2012, 2, 1, 2, 2.0, Vector(resA, resB), w1),
    metrics(resB, 2010, 2012, 2, 1, 2, 2.0, Vector(resA, resB), w1)
  )

  val semanticFact: SemanticFact = SemanticFact(
    SV.SemanticFact,
    ok(
      StableIds.semanticFactId(
        SemanticSubjectType.RESEARCHER,
        resA.researcherId,
        2010,
        2013,
        MetricVersion,
        runId
      )
    ),
    SemanticSubjectType.RESEARCHER,
    resA.researcherId,
    Some(resA.displayName),
    2010,
    2013,
    Vector(MetricValue(CollaborationMetric.PUBLICATION_COUNT, 2.0)),
    Vector.empty,
    Vector.empty,
    Vector.empty,
    Vector(resB.researcherId),
    Vector(RepresentativeWork(w1.workId, w1.title, w1.year)),
    MetricVersion,
    runId,
    prov(SourceType.SCHOLARCHAIN, resA.researcherId, Some(CoauthorPolicy))
  )

  // ----- Parquet schemas -----
  import PType.*
  private val provenanceType = Struct(
    Vector(
      "schemaVersion" -> Str,
      "sourceDataset" -> Str,
      "sourceRecordId" -> Str,
      "snapshot" -> Str,
      "runId" -> Str,
      "policyVersion" -> Str,
      "modelVersion" -> Str,
      "promptVersion" -> Str,
      "derivedFrom" -> StrList
    )
  )
  private def cols(c: (String, PType)*): Vector[(String, PType)] =
    c.toVector :+ ("provenance" -> provenanceType)

  private val mentionFields: Vector[(String, PType)] = Vector(
    "schemaVersion" -> Str,
    "mentionId" -> Str,
    "workId" -> Str,
    "position" -> I32,
    "rawName" -> Str,
    "normalizedName" -> Str,
    "sourceAuthorKey" -> Str,
    "orcid" -> Str,
    "provenance" -> provenanceType
  )

  private def js[A: ReadWriter](a: A): ujson.Obj = writeJs(a).obj

  private def table[A: ReadWriter](
      name: String,
      recordType: String,
      schemaVersion: String,
      location: String,
      partitions: Vector[String],
      schema: Vector[(String, PType)],
      files: Vector[(String, Vector[A])]
  ): FixtureTable =
    FixtureTable(
      name,
      recordType,
      schemaVersion,
      location,
      partitions,
      schema,
      files.map((f, rs) => (f, rs.map(js(_))))
    )

  private def runDir(location: String) = s"$location/run_id=$runHex"

  val tables: Vector[FixtureTable] =
    val loc = (t: String) => s"graph/$t"
    Vector(
      table(
        "researcher",
        "CanonicalResearcher",
        SV.CanonicalResearcher,
        loc("nodes/type=researcher"),
        Vector("run_id"),
        cols(
          "schemaVersion" -> Str,
          "researcherId" -> Str,
          "displayName" -> Str,
          "orcid" -> Str,
          "dblpPid" -> Str,
          "openAlexAuthorId" -> Str,
          "dblpAuthorKeys" -> StrList,
          "identityVersion" -> Str,
          "affiliationInstitutionIds" -> StrList,
          "identityConfidence" -> Dbl
        ),
        Vector(
          s"${runDir(loc("nodes/type=researcher"))}/part-00000.snappy.parquet" -> researchers
        )
      ),
      table(
        "work",
        "WorkRecord",
        SV.WorkRecord,
        loc("nodes/type=work"),
        Vector("run_id"),
        cols(
          "schemaVersion" -> Str,
          "workId" -> Str,
          "source" -> Str,
          "sourceRecordId" -> Str,
          "recordType" -> Str,
          "title" -> Str,
          "normalizedTitle" -> Str,
          "year" -> I32,
          "venueId" -> Str,
          "doi" -> Str,
          "arxivId" -> Str,
          "authors" -> StructList(mentionFields),
          "abstractTextRef" -> Str
        ),
        Vector(s"${runDir(loc("nodes/type=work"))}/part-00000.snappy.parquet" -> works)
      ),
      table(
        "venue",
        "Venue",
        SV.Venue,
        loc("nodes/type=venue"),
        Vector("run_id"),
        cols(
          "schemaVersion" -> Str,
          "venueId" -> Str,
          "name" -> Str,
          "venueType" -> Str,
          "sourceVenueKey" -> Str,
          "issns" -> StrList
        ),
        Vector(s"${runDir(loc("nodes/type=venue"))}/part-00000.snappy.parquet" -> Vector(venue))
      ),
      table(
        "topic",
        "Topic",
        SV.Topic,
        loc("nodes/type=topic"),
        Vector("run_id"),
        cols(
          "schemaVersion" -> Str,
          "topicId" -> Str,
          "name" -> Str,
          "parentTopicId" -> Str
        ),
        Vector(s"${runDir(loc("nodes/type=topic"))}/part-00000.snappy.parquet" -> Vector(topic))
      ),
      table(
        "institution",
        "Institution",
        SV.Institution,
        loc("nodes/type=institution"),
        Vector("run_id"),
        cols(
          "schemaVersion" -> Str,
          "institutionId" -> Str,
          "name" -> Str,
          "rorId" -> Str,
          "countryCode" -> Str
        ),
        Vector(
          s"${runDir(loc("nodes/type=institution"))}/part-00000.snappy.parquet" -> Vector(
            institution
          )
        )
      ),
      table(
        "authored",
        "AuthorshipRecord",
        SV.AuthorshipRecord,
        loc("edges/type=authored"),
        Vector("run_id"),
        cols(
          "schemaVersion" -> Str,
          "authorshipId" -> Str,
          "researcherId" -> Str,
          "workId" -> Str,
          "mentionId" -> Str,
          "position" -> I32,
          "year" -> I32,
          "matchConfidence" -> Dbl,
          "matchDecisionId" -> Str
        ),
        Vector(
          s"${runDir(loc("edges/type=authored"))}/part-00000.snappy.parquet" -> authorships.take(3),
          s"${runDir(loc("edges/type=authored"))}/part-00001.snappy.parquet" -> authorships.drop(3)
        )
      ),
      table(
        "has_topic",
        "GraphEdge",
        SV.GraphEdge,
        loc("edges/type=has_topic"),
        Vector("run_id"),
        cols(
          "schemaVersion" -> Str,
          "edgeId" -> Str,
          "fromId" -> Str,
          "edgeType" -> Str,
          "toId" -> Str,
          "properties" -> StrMap,
          "policyVersion" -> Str,
          "sourceEvidenceIds" -> StrList
        ),
        Vector(
          s"${runDir(loc("edges/type=has_topic"))}/part-00000.snappy.parquet" -> Vector(hasTopic)
        )
      ),
      table(
        "coauthored_with",
        "CoauthorEdge",
        SV.CoauthorEdge,
        loc("edges/type=coauthored_with"),
        Vector("window", "run_id"),
        cols(
          "schemaVersion" -> Str,
          "edgeId" -> Str,
          "researcherA" -> Str,
          "researcherB" -> Str,
          "windowStartYear" -> I32,
          "windowEndYear" -> I32,
          "jointPublicationCount" -> I64,
          "firstCollaborationYear" -> I32,
          "lastCollaborationYear" -> I32,
          "representativeWorkIds" -> StrList,
          "supportingWorkIdsRef" -> Str,
          "policyVersion" -> Str
        ),
        Vector(
          s"${loc("edges/type=coauthored_with")}/window=2010-2012/run_id=$runHex/part-00000.snappy.parquet" -> edgesEarly,
          s"${loc("edges/type=coauthored_with")}/window=2010-2013/run_id=$runHex/part-00000.snappy.parquet" -> edgesAll
        )
      ),
      table(
        "researcher_collaboration",
        "ResearcherMetrics",
        SV.ResearcherMetrics,
        loc("metrics/type=researcher_collaboration"),
        Vector("window", "run_id"),
        cols(
          "schemaVersion" -> Str,
          "metricsId" -> Str,
          "researcherId" -> Str,
          "windowStartYear" -> I32,
          "windowEndYear" -> I32,
          "publicationCount" -> I64,
          "distinctCoauthorCount" -> I64,
          "totalCoauthorOccurrences" -> I64,
          "averageCoauthorsPerPaper" -> Dbl,
          "maximumCoauthorsOnOnePaper" -> I64,
          "maximumTeamWorkIds" -> StrList,
          "weightedDegree" -> Dbl,
          "componentId" -> Str,
          "componentSize" -> I64,
          "communityId" -> Str,
          "bridgeMetric" -> Str,
          "bridgeScore" -> Dbl,
          "coreness" -> I32,
          "metricVersion" -> Str
        ),
        Vector(
          s"${loc("metrics/type=researcher_collaboration")}/window=2010-2012/run_id=$runHex/part-00000.snappy.parquet" -> metricsEarly,
          s"${loc("metrics/type=researcher_collaboration")}/window=2010-2013/run_id=$runHex/part-00000.snappy.parquet" -> metricsAll
        )
      ),
      table(
        "semantic_fact",
        "SemanticFact",
        SV.SemanticFact,
        loc("semantic/type=semantic_fact"),
        Vector("window", "run_id"),
        cols(
          "schemaVersion" -> Str,
          "factId" -> Str,
          "subjectType" -> Str,
          "subjectId" -> Str,
          "displayName" -> Str,
          "windowStartYear" -> I32,
          "windowEndYear" -> I32,
          "metrics" -> StructList(Vector("metric" -> Str, "value" -> Dbl)),
          "topTopics" -> StructList(
            Vector("entityId" -> Str, "label" -> Str, "count" -> I64)
          ),
          "topVenues" -> StructList(Vector("entityId" -> Str, "label" -> Str, "count" -> I64)),
          "topInstitutions" -> StructList(
            Vector("entityId" -> Str, "label" -> Str, "count" -> I64)
          ),
          "relatedResearcherIds" -> StrList,
          "representativeWorks" -> StructList(
            Vector("workId" -> Str, "title" -> Str, "year" -> I32)
          ),
          "metricVersion" -> Str,
          "runId" -> Str
        ),
        Vector(
          s"${loc("semantic/type=semantic_fact")}/window=2010-2013/run_id=$runHex/part-00000.snappy.parquet" -> Vector(
            semanticFact
          )
        )
      )
    )

  def tableNamed(n: String): FixtureTable = tables.find(_.name == n).get

  val neptuneTables: Vector[String] = Vector(
    "researcher",
    "work",
    "venue",
    "topic",
    "institution",
    "authored",
    "has_topic",
    "coauthored_with",
    "researcher_collaboration"
  )
  val dynamoTables: Vector[String] = Vector("researcher_collaboration", "coauthored_with")
  val s3Tables: Vector[String] = tables.map(_.name)
  val s3VectorsInputTables: Vector[String] = Vector("semantic_fact")

  /** Writes every Parquet file and the four load manifests under `root`. */
  def generate(root: Path): Unit =
    tables.foreach { t =>
      t.files.foreach((rel, rows) => ParquetFixtureWriter.write(root.resolve(rel), t.schema, rows))
    }
    val dir = root.resolve("load-manifests")
    Files.createDirectories(dir)
    Vector(
      LoadTarget.NEPTUNE -> ("neptune", neptuneTables),
      LoadTarget.DYNAMODB -> ("dynamodb", dynamoTables),
      LoadTarget.S3 -> ("s3", s3Tables),
      LoadTarget.S3VECTORS_INPUT -> ("s3vectors-input", s3VectorsInputTables)
    ).foreach { case (target, (file, names)) =>
      val json = write(manifest(root, target, names), indent = 2)
      Files.write(dir.resolve(s"$file.json"), (json + "\n").getBytes("UTF-8"))
    }

  def manifest(root: Path, target: LoadTarget, names: Vector[String]): LoadManifest =
    LoadManifest(
      SV.LoadManifest,
      target,
      runId,
      names.map { n =>
        val t = tableNamed(n)
        LoadTable(
          t.name,
          t.recordType,
          t.schemaVersion,
          t.location,
          "parquet",
          t.partitionColumns,
          t.rowCount,
          t.files
            .map(_._1)
            .sorted
            .map { rel =>
              val p = root.resolve(rel)
              LoadFile(rel, Files.size(p), Sha256.ofFile(p))
            }
        )
      },
      Provenance(
        SV.Provenance,
        SourceType.SCHOLARCHAIN,
        runId,
        Snapshot,
        runId,
        None,
        None,
        None,
        Vector.empty
      )
    )

object FixtureGenerator:
  /** `sbt "storage/Test/runMain scholarchain.storage.FixtureGenerator <dir>"` rewrites the committed
    * fixture. Rerunning produces identical files.
    */
  def main(args: Array[String]): Unit =
    val dir = Paths.get(args.headOption.getOrElse("modules/storage/src/test/resources/fixture/v1"))
    Fixture.generate(dir)
    println(s"fixture written to ${dir.toAbsolutePath}")
