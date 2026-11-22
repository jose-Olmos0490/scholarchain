package scholarchain.storage

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scholarchain.core.model.*
import scholarchain.storage.common.*
import scholarchain.storage.graph.*
import scholarchain.storage.manifest.*
import upickle.default.*

/** Parquet to Neptune openCypher CSV: exact output for the fixture, headers and types, canonical
  * order, evidence pointers, windows, rejects.
  */
class NeptuneExportSpec extends StorageSuite:
  import Fixture.*

  private def lines(rows: Vector[String]): String = rows.mkString("", "\r\n", "\r\n")
  private val prov = (dataset: String, snap: String) => s"$dataset,$snap,$runId"
  private val derived = prov("SCHOLARCHAIN", Snapshot)
  private val dblp = prov("DBLP", Snapshot)
  private val openAlex = prov("OPENALEX", "api-2026-11-20")

  /** The expected CSV of the fixture, written out literally (not through the exporter's code). */
  private val expected: Vector[(String, String)] = Vector(
    "nodes-researcher.csv" -> lines(
      Vector(
        ":ID,:LABEL,displayName:String,orcid:String,dblpPid:String,openAlexAuthorId:String,identityVersion:String,identityConfidence:Double,sourceDataset:String,snapshot:String,runId:String",
        s"${resA.researcherId},Researcher,Henry Samueli,0000-0003-3175-3913,,,,1.0,$derived",
        s"${resB.researcherId},Researcher,Josiah Carberry,0000-0002-1825-0097,,,,1.0,$derived",
        s"${resC.researcherId},Researcher,Walden C. Rhines,,,A5110371381,,1.0,$derived"
      )
    ),
    "nodes-researcher-metrics.csv" -> {
      val comp = Vector(resA, resB, resC).map(_.researcherId).min
      lines(
        Vector(
          ":ID,:LABEL,P_r:Long,C_r:Long,T_r:Long,A_r:Double,M_r:Long,W_r:Double,componentId:String,communityId:String,bridgeScore:Double,coreness:Int,window:String,metricVersion:String",
          s"${resA.researcherId},Researcher,2,1,2,1.0,1,2.0,$comp,$comp,,1,2010-2013,$MetricVersion",
          s"${resB.researcherId},Researcher,3,2,3,1.0,1,3.0,$comp,$comp,,1,2010-2013,$MetricVersion",
          s"${resC.researcherId},Researcher,1,1,1,1.0,1,1.0,$comp,$comp,,1,2010-2013,$MetricVersion"
        )
      )
    },
    "nodes-work.csv" -> lines(
      Vector(
        ":ID,:LABEL,year:Int,recordType:String,doi:String,arxivId:String,sourceDataset:String,snapshot:String,runId:String",
        s"${w1.workId},Work,2010,inproceedings,,,$dblp",
        s"${w2.workId},Work,2012,inproceedings,,,$dblp",
        s"${w3.workId},Work,2013,inproceedings,,,$dblp"
      )
    ),
    "nodes-venue.csv" -> lines(
      Vector(
        ":ID,:LABEL,name:String,venueType:String,sourceDataset:String,snapshot:String,runId:String",
        s"${venue.venueId},Venue,DAC,conference,$dblp"
      )
    ),
    "nodes-topic.csv" -> lines(
      Vector(
        ":ID,:LABEL,name:String,parentTopicId:String,sourceDataset:String,snapshot:String,runId:String",
        s"${topic.topicId},Topic,Low-power high-performance VLSI design,,$openAlex"
      )
    ),
    "nodes-institution.csv" -> lines(
      Vector(
        ":ID,:LABEL,name:String,rorId:String,countryCode:String,sourceDataset:String,snapshot:String,runId:String",
        s"${institution.institutionId},Institution,Chinese University of Hong Kong,00t33hh48,HK,$openAlex"
      )
    ),
    "relationships-authored.csv" -> lines(
      ":ID,:START_ID,:END_ID,:TYPE,position:Int,year:Int,matchConfidence:Double,matchDecisionId:String,mentionId:String,sourceDataset:String,snapshot:String,runId:String" +:
        authorships.map { x =>
          s"${x.authorshipId},${x.researcherId},${x.workId},AUTHORED,${x.position},${x.year.get},1.0,,${x.mentionId},$dblp"
        }
    ),
    "relationships-has_topic.csv" -> lines(
      Vector(
        ":ID,:START_ID,:END_ID,:TYPE,policyVersion:String,sourceEvidenceIds:String,sourceDataset:String,snapshot:String,runId:String",
        s"${hasTopic.edgeId},${w1.workId},${topic.topicId},HAS_TOPIC,$TopicPolicy,${w1.workId},$derived"
      )
    ),
    "relationships-coauthored_with.csv" -> lines(
      ":ID,:START_ID,:END_ID,:TYPE,jointPublicationCount:Long,firstCollaborationYear:Int,lastCollaborationYear:Int,windowStartYear:Int,windowEndYear:Int,evidencePointer:String,runId:String,policyVersion:String" +:
        (edgesEarly ++ edgesAll).map { e =>
          s"${e.edgeId},${e.researcherA},${e.researcherB},COAUTHORED_WITH,${e.jointPublicationCount},${e.firstCollaborationYear},${e.lastCollaborationYear},${e.windowStartYear},${e.windowEndYear},coauthor-edge-support-v1:${e.edgeId},$runId,$CoauthorPolicy"
        }
    )
  )

  private def exportFixture(
      root: Path = FixturePaths.root,
      manifest: LoadManifest = manifestOf(FixturePaths.root, "neptune"),
      options: ExportOptions = ExportOptions(),
      rejects: RejectSink = new InMemoryRejectSink
  ): (Path, CsvExportManifest) =
    val v = ok(LoadManifests.verify(manifest, LoadTarget.NEPTUNE, new LocalFileStager(root)))
    val out = tempDir("export-")
    (out, ok(new NeptuneCsvExporter(rejects).exportRun(v, out, options)))

  test("exact CSV for the fixture: separate node and relationship files, headers with types") {
    val (out, manifest) = exportFixture()
    manifest.files.map(_.file) shouldBe expected.map(_._1)
    expected.foreach { (name, content) =>
      new String(Files.readAllBytes(out.resolve("csv").resolve(name)), UTF_8) shouldBe content
    }
  }

  test(
    "node IDs and relationship IDs are the Spark output IDs, and the pair is in canonical order"
  ) {
    val (out, _) = exportFixture()
    val rows = Csv
      .parse(
        new java.io.StringReader(
          Files.readString(out.resolve("csv/relationships-coauthored_with.csv"))
        )
      )
      .toVector
      .tail
    rows.map(_(0)) shouldBe (edgesEarly ++ edgesAll).map(_.edgeId)
    rows.foreach(r => (r(1) < r(2)) shouldBe true)
    val nodeIds = Csv
      .parse(new java.io.StringReader(Files.readString(out.resolve("csv/nodes-researcher.csv"))))
      .toVector
      .tail
      .map(_(0))
    nodeIds shouldBe researchers.map(_.researcherId)
  }

  test("the export manifest lists file, row count, SHA-256 and source Parquet files") {
    val (out, manifest) = exportFixture()
    val src = manifestOf(FixturePaths.root, "neptune")
    manifest.sourceRunId shouldBe runId
    manifest.provenance.runId shouldBe runId
    manifest.rejectedRows shouldBe 0
    manifest.nodeWindow shouldBe Some("2010-2013")
    manifest.files.foreach { f =>
      f.sha256 shouldBe Sha256.ofFile(out.resolve("csv").resolve(f.file))
      f.rowCount shouldBe (expected.find(_._1 == f.file).get._2.split("\r\n").length - 1).toLong
      f.sourceParquetFiles shouldBe src.tables
        .find(_.tableName == f.sourceTable)
        .get
        .files
        .map(_.path)
    }
    manifest.files.map(f => (f.file, f.role, f.label)) shouldBe Vector(
      ("nodes-researcher.csv", "node", "Researcher"),
      ("nodes-researcher-metrics.csv", "node-overlay", "Researcher"),
      ("nodes-work.csv", "node", "Work"),
      ("nodes-venue.csv", "node", "Venue"),
      ("nodes-topic.csv", "node", "Topic"),
      ("nodes-institution.csv", "node", "Institution"),
      ("relationships-authored.csv", "relationship", "AUTHORED"),
      ("relationships-has_topic.csv", "relationship", "HAS_TOPIC"),
      ("relationships-coauthored_with.csv", "relationship", "COAUTHORED_WITH")
    )
    // the manifest is written next to, not inside, the directory Neptune loads
    Files.isRegularFile(out.resolve("csv-export-manifest.json")) shouldBe true
    Files.list(out.resolve("csv")).count shouldBe manifest.files.size.toLong
  }

  test("a fixed node window puts that window's metrics on the researcher nodes") {
    val (out, manifest) = exportFixture(options = ExportOptions(NodeWindow.Fixed(2010, 2012)))
    manifest.nodeWindow shouldBe Some("2010-2012")
    val rows = Csv
      .parse(
        new java.io.StringReader(Files.readString(out.resolve("csv/nodes-researcher-metrics.csv")))
      )
      .toVector
      .tail
    rows.map(_(0)).toSet shouldBe Set(resA.researcherId, resB.researcherId)
    rows.foreach(_(12) shouldBe "2010-2012")
  }

  test("a table with no CSV mapping is reported as skipped, with the reason") {
    val m = manifestOf(FixturePaths.root, "neptune")
    val extra = m.copy(tables =
      m.tables ++ Vector(
        LoadTable(
          "components",
          "ComponentMembership",
          "component-membership-v1",
          "graph/metrics/type=components",
          "parquet",
          Vector("run_id"),
          0,
          Vector.empty
        ),
        LoadTable(
          "mystery",
          "Venue",
          "venue-v1",
          "graph/mystery",
          "parquet",
          Vector("run_id"),
          0,
          Vector.empty
        )
      )
    )
    val (_, manifest) = exportFixture(manifest = extra)
    manifest.skippedTables.map(_.table) shouldBe Vector("components", "mystery")
    manifest.skippedTables.head.reason should include("denormalized into researcher_collaboration")
    manifest.skippedTables.last.reason should include("no Neptune mapping")
  }

  test("a row that does not decode into its core record is rejected, recorded and counted") {
    val dir = fixtureCopy()
    val m = manifestOf(dir, "neptune")
    // swap the pair: not canonical, so the core contract rejects the row
    val rewritten = FixtureTamper.rewrite(dir, m, "coauthored_with", fileIndex = 1) { rows =>
      rows.map { r =>
        if r("edgeId").str == edgeBcAll.edgeId then
          val o = ujson.read(r.render()).obj
          val x = o("researcherA"); o("researcherA") = o("researcherB"); o("researcherB") = x
          ujson.Obj(o)
        else r
      }
    }
    val rejects = new InMemoryRejectSink
    val (out, manifest) = exportFixture(dir, rewritten, rejects = rejects)
    manifest.rejectedRows shouldBe 1
    rejects.all.map(r => (r.store, r.reasonCode)) shouldBe Vector(("neptune", "ROW_DECODE"))
    rejects.all.head.detail should include("researcherA")
    val csv = Files.readString(out.resolve("csv/relationships-coauthored_with.csv"))
    csv should not include edgeBcAll.edgeId
    csv should include(edgeAbAll.edgeId)
  }

  test(
    "a coauthor edge without an evidence pointer is exported empty, so the validator can stop the load"
  ) {
    val dir = fixtureCopy()
    val rewritten =
      FixtureTamper.rewrite(dir, manifestOf(dir, "neptune"), "coauthored_with", fileIndex = 1) {
        rows =>
          rows.map { r =>
            val o = ujson.read(r.render()).obj
            o("supportingWorkIdsRef") = ujson.Null
            ujson.Obj(o)
          }
      }
    val (out, _) = exportFixture(dir, rewritten)
    err(NeptuneCsvValidator.validate(out)).message should include("MISSING_EVIDENCE_POINTER")
  }

/** Rewrites one Parquet file of a copied fixture and returns the manifest that describes the copy. */
object FixtureTamper:
  def rewrite(root: Path, m: LoadManifest, table: String, fileIndex: Int)(
      f: Vector[ujson.Obj] => Vector[ujson.Obj]
  ): LoadManifest =
    val t = m.tables.find(_.tableName == table).get
    val file = t.files(fileIndex)
    val fixtureTable = Fixture.tableNamed(table)
    val rows = scholarchain.storage.parquet.ParquetRows
      .open(root.resolve(file.path))
      .map(r => ujson.Obj(r.value))
      .toVector
    val updated = f(rows)
    ParquetFixtureWriter.write(root.resolve(file.path), fixtureTable.schema, updated)
    val newFile = LoadFile(
      file.path,
      Files.size(root.resolve(file.path)),
      Sha256.ofFile(root.resolve(file.path))
    )
    val newTable = t.copy(
      files = t.files.updated(fileIndex, newFile),
      rowCount = t.rowCount - rows.size + updated.size
    )
    m.copy(tables = m.tables.map(x => if x.tableName == table then newTable else x))
