package scholarchain.storage

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scholarchain.core.model.*
import scholarchain.storage.common.*
import scholarchain.storage.graph.*
import scholarchain.storage.loader.StorageSchemas
import upickle.default.*

/** The pre-load validator, on hand-built CSV directories (independent of the exporter). */
class NeptuneValidatorSpec extends StorageSuite:

  // short, valid-looking IDs: the validator checks structure, not stable-ID syntax
  private val R1 = "researcher:orcid:" + ("1" * 64)
  private val R2 = "researcher:orcid:" + ("2" * 64)
  private val R3 = "researcher:orcid:" + ("3" * 64)

  private val nodeHeader = ":ID,:LABEL,name:String"
  private val relHeader = ":ID,:START_ID,:END_ID,:TYPE,evidencePointer:String"
  private def lines(rows: String*) = rows.mkString("", "\r\n", "\r\n")

  private val goodNodes =
    lines(nodeHeader, s"$R1,Researcher,One", s"$R2,Researcher,Two", s"$R3,Researcher,Three")
  private val goodRels = lines(
    relHeader,
    s"e1,$R1,$R2,COAUTHORED_WITH,coauthor-edge-support-v1:e1",
    s"e2,$R2,$R3,COAUTHORED_WITH,coauthor-edge-support-v1:e2"
  )

  /** Writes the files and an export manifest whose hashes and counts are right for them. */
  private def build(files: (String, String, String, String)*): Path =
    val out = tempDir("validator-")
    Files.createDirectories(out.resolve("csv"))
    val entries = files.map { (name, role, label, content) =>
      Files.write(out.resolve("csv").resolve(name), content.getBytes(UTF_8))
      CsvExportFile(
        name,
        role,
        label,
        (content.split("\r\n").length - 1).toLong,
        Sha256.of(content.getBytes(UTF_8)),
        "t",
        Vector.empty
      )
    }.toVector
    val m = CsvExportManifest(
      StorageSchemas.NeptuneCsvExport,
      Fixture.runId,
      None,
      entries,
      Vector.empty,
      0,
      Fixture.prov(SourceType.SCHOLARCHAIN, Fixture.runId)
    )
    Files.write(out.resolve(NeptuneCsvExporter.ManifestFile), write(m).getBytes(UTF_8))
    out

  private def failure(out: Path): String = err(NeptuneCsvValidator.validate(out)).message

  test("a clean export passes and reports node and relationship counts") {
    val s = ok(
      NeptuneCsvValidator.validate(
        build(
          ("n.csv", "node", "Researcher", goodNodes),
          ("r.csv", "relationship", "COAUTHORED_WITH", goodRels)
        )
      )
    )
    s shouldBe ValidationSummary(nodeIds = 3, relationships = 2, files = 2)
  }

  test("a relationship endpoint missing from the node files fails (start and end)") {
    val rels = lines(
      relHeader,
      s"e1,researcher:orcid:${"9" * 64},$R2,COAUTHORED_WITH,p1",
      s"e2,$R1,researcher:orcid:${"8" * 64},COAUTHORED_WITH,p2"
    )
    val m = failure(
      build(
        ("n.csv", "node", "Researcher", goodNodes),
        ("r.csv", "relationship", "COAUTHORED_WITH", rels)
      )
    )
    m should include("DANGLING_ENDPOINT x2")
    m should include(":START_ID")
    m should include(":END_ID")
  }

  test("a duplicate :ID inside one node file fails") {
    val nodes =
      lines(nodeHeader, s"$R1,Researcher,One", s"$R1,Researcher,Again", s"$R2,Researcher,Two")
    failure(build(("n.csv", "node", "Researcher", nodes))) should (include(
      "DUPLICATE_ID"
    ) and include(R1))
  }

  test("the same :ID in two node files fails; an overlay may repeat a node but must have one") {
    val other = lines(nodeHeader, s"$R1,Work,Clash")
    failure(
      build(("n.csv", "node", "Researcher", goodNodes), ("w.csv", "node", "Work", other))
    ) should
      include("is also in another node file")
    val overlay = lines(":ID,:LABEL,P_r:Long", s"$R1,Researcher,2")
    ok(
      NeptuneCsvValidator.validate(
        build(
          ("n.csv", "node", "Researcher", goodNodes),
          ("o.csv", "node-overlay", "Researcher", overlay)
        )
      )
    )
    val orphan = lines(":ID,:LABEL,P_r:Long", s"researcher:orcid:${"7" * 64},Researcher,2")
    failure(
      build(
        ("n.csv", "node", "Researcher", goodNodes),
        ("o.csv", "node-overlay", "Researcher", orphan)
      )
    ) should
      include("OVERLAY_WITHOUT_NODE")
  }

  test("a duplicate relationship :ID fails, in one file or across files") {
    val rels = lines(relHeader, s"e1,$R1,$R2,COAUTHORED_WITH,p", s"e1,$R2,$R3,COAUTHORED_WITH,p")
    failure(
      build(
        ("n.csv", "node", "Researcher", goodNodes),
        ("r.csv", "relationship", "COAUTHORED_WITH", rels)
      )
    ) should
      include("DUPLICATE_ID")
    val a = lines(relHeader, s"e1,$R1,$R2,COAUTHORED_WITH,p")
    val b = lines(relHeader, s"e1,$R2,$R3,COAUTHORED_WITH,p")
    failure(
      build(
        ("n.csv", "node", "Researcher", goodNodes),
        ("a.csv", "relationship", "COAUTHORED_WITH", a),
        ("b.csv", "relationship", "COAUTHORED_WITH", b)
      )
    ) should
      include("relationship e1 is in another file")
  }

  test("a COAUTHORED_WITH edge without an evidence pointer fails (empty value or missing column)") {
    val empty = lines(relHeader, s"e1,$R1,$R2,COAUTHORED_WITH,", s"e2,$R2,$R3,COAUTHORED_WITH,   ")
    failure(
      build(
        ("n.csv", "node", "Researcher", goodNodes),
        ("r.csv", "relationship", "COAUTHORED_WITH", empty)
      )
    ) should
      include("MISSING_EVIDENCE_POINTER x2")
    val noColumn = lines(":ID,:START_ID,:END_ID,:TYPE", s"e1,$R1,$R2,COAUTHORED_WITH")
    failure(
      build(
        ("n.csv", "node", "Researcher", goodNodes),
        ("r.csv", "relationship", "COAUTHORED_WITH", noColumn)
      )
    ) should
      include("no evidencePointer column")
  }

  test("a relationship of another type does not need an evidence pointer") {
    val rels = lines(":ID,:START_ID,:END_ID,:TYPE", s"a1,$R1,$R2,AUTHORED")
    ok(
      NeptuneCsvValidator.validate(
        build(
          ("n.csv", "node", "Researcher", goodNodes),
          ("r.csv", "relationship", "AUTHORED", rels)
        )
      )
    )
  }

  test("a COAUTHORED_WITH edge stored against canonical order fails") {
    val rels = lines(relHeader, s"e1,$R2,$R1,COAUTHORED_WITH,p")
    failure(
      build(
        ("n.csv", "node", "Researcher", goodNodes),
        ("r.csv", "relationship", "COAUTHORED_WITH", rels)
      )
    ) should
      include("NOT_CANONICAL_PAIR")
  }

  test("structural problems are named: field count, missing required headers, empty ID") {
    val ragged = lines(nodeHeader, s"$R1,Researcher")
    failure(build(("n.csv", "node", "Researcher", ragged))) should include("FIELD_COUNT")
    failure(build(("n.csv", "node", "Researcher", lines(":ID,name:String", "x,y")))) should
      include("MISSING_HEADER")
    failure(build(("n.csv", "node", "Researcher", lines(nodeHeader, ",Researcher,x")))) should
      include("EMPTY_ID")
  }

  test("a file changed after export fails its manifest checksum and row count") {
    val out = build(("n.csv", "node", "Researcher", goodNodes))
    Files.writeString(out.resolve("csv/n.csv"), goodNodes + s"${R3}x,Researcher,Extra\r\n")
    val m = failure(out)
    m should include("FILE_CHECKSUM")
    m should include("ROW_COUNT")
  }

  test("a missing file and a missing manifest are reported") {
    val out = build(("n.csv", "node", "Researcher", goodNodes))
    Files.delete(out.resolve("csv/n.csv"))
    failure(out) should include("MISSING_FILE")
    err(NeptuneCsvValidator.validate(tempDir())) shouldBe a[StorageError.MissingFile]
  }

  test("every problem is reported at once, not only the first") {
    val rels = lines(
      relHeader,
      s"e1,$R1,$R2,COAUTHORED_WITH,",
      s"e1,$R1,researcher:orcid:${"6" * 64},COAUTHORED_WITH,p"
    )
    val nodes =
      lines(nodeHeader, s"$R1,Researcher,One", s"$R1,Researcher,Dup", s"$R2,Researcher,Two")
    val m = failure(
      build(
        ("n.csv", "node", "Researcher", nodes),
        ("r.csv", "relationship", "COAUTHORED_WITH", rels)
      )
    )
    m should include("DUPLICATE_ID")
    m should include("DANGLING_ENDPOINT")
    m should include("MISSING_EVIDENCE_POINTER")
  }
