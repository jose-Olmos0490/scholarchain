package scholarchain.storage

import java.nio.file.{Files, Path}
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.io.LocalInputFile
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.core.validation.RecordContract
import scholarchain.storage.common.*
import scholarchain.storage.manifest.*
import scholarchain.storage.parquet.ParquetRows
import upickle.default.*

/** The streaming Parquet reader and load-manifest verification. */
class ParquetAndManifestSpec extends StorageSuite:

  private def verified(root: Path, name: String, target: LoadTarget) =
    LoadManifests.verify(manifestOf(root, name), target, new LocalFileStager(root))

  // ----- Parquet rows decode into exactly the fixture records -----

  private def decoded[A: ReadWriter: RecordContract](
      v: VerifiedManifest,
      table: String
  ): Vector[A] =
    TableRows
      .stream[A](v, table)
      .map(r => r.value.fold(f => fail(s"row ${f.rowIndex} of ${f.file}: ${f.reason}"), identity))
      .toVector

  test("every fixture table streams out as exactly the core records that were written") {
    val v = ok(verified(FixturePaths.root, "s3", LoadTarget.S3))
    decoded[CanonicalResearcher](v, "researcher") shouldBe Fixture.researchers
    decoded[WorkRecord](v, "work") shouldBe Fixture.works
    decoded[Venue](v, "venue") shouldBe Vector(Fixture.venue)
    decoded[Topic](v, "topic") shouldBe Vector(Fixture.topic)
    decoded[Institution](v, "institution") shouldBe Vector(Fixture.institution)
    decoded[AuthorshipRecord](v, "authored") shouldBe Fixture.authorships
    decoded[GraphEdge](v, "has_topic") shouldBe Vector(Fixture.hasTopic)
    // two window directories, read in manifest (path) order
    decoded[CoauthorEdge](v, "coauthored_with") shouldBe Fixture.edgesEarly ++ Fixture.edgesAll
    decoded[ResearcherMetrics](v, "researcher_collaboration") shouldBe
      Fixture.metricsEarly ++ Fixture.metricsAll
    decoded[SemanticFact](v, "semantic_fact") shouldBe Vector(Fixture.semanticFact)
  }

  test("round-trip serialization: every stored record survives encode and validated decode") {
    def rt[A: ReadWriter: RecordContract](a: A): Unit =
      WireCodec.decode[A](write(a)) shouldBe Right(a)
    Fixture.researchers.foreach(rt(_))
    Fixture.works.foreach(rt(_))
    rt(Fixture.venue); rt(Fixture.topic); rt(Fixture.institution)
    Fixture.authorships.foreach(rt(_))
    rt(Fixture.hasTopic)
    (Fixture.edgesEarly ++ Fixture.edgesAll).foreach(rt(_))
    (Fixture.metricsEarly ++ Fixture.metricsAll).foreach(rt(_))
    rt(Fixture.semanticFact)
    Vector("neptune", "dynamodb", "s3", "s3vectors-input").foreach { n =>
      val m = manifestOf(FixturePaths.root, n)
      WireCodec.decode[LoadManifest](write(m)) shouldBe Right(m)
    }
  }

  test(
    "the committed fixture holds the rows the generator defines, and its manifests describe its files"
  ) {
    // Parquet bytes differ between writer runs, so rows are compared, not bytes.
    Fixture.tables.foreach { t =>
      t.files.foreach { (rel, rows) =>
        val read = ParquetRows.open(FixturePaths.root.resolve(rel)).toVector
        read.map(r => ujson.read(r.render())) shouldBe rows
          .map(r => ujson.read(r.render()))
          .map(normalize)
      }
    }
    Vector(
      "neptune" -> Fixture.neptuneTables,
      "dynamodb" -> Fixture.dynamoTables,
      "s3" -> Fixture.s3Tables,
      "s3vectors-input" -> Fixture.s3VectorsInputTables
    ).foreach { (name, tables) =>
      val target = manifestOf(FixturePaths.root, name).target
      val expected = Fixture.manifest(FixturePaths.root, target, tables)
      manifestOf(FixturePaths.root, name) shouldBe expected
    }
  }

  /** Rows read back have every column; absent values are null, and integers are plain numbers. */
  private def normalize(v: ujson.Value): ujson.Value = v match
    case o: ujson.Obj => ujson.Obj.from(o.value.map((k, x) => k -> normalize(x)))
    case a: ujson.Arr => ujson.Arr.from(a.value.map(normalize))
    case other => other

  test("the reader streams across row groups, one row at a time") {
    val file = tempDir().resolve("many-groups.parquet")
    val t = Fixture.tableNamed("venue")
    val rows: Vector[ujson.Obj] = (1 to 400).map { i =>
      writeJs(Fixture.venue.copy(name = s"Venue $i")).asInstanceOf[ujson.Obj]
    }.toVector
    ParquetFixtureWriter.write(file, t.schema, rows, rowGroupBytes = Some(256L))
    val groups = ParquetFileReader.open(new LocalInputFile(file))
    val blocks = try groups.getFooter.getBlocks.size
    finally groups.close()
    blocks should be > 5

    ParquetRows.rowCount(file) shouldBe 400L
    val it = ParquetRows.open(file)
    try
      it.next()("name").str shouldBe "Venue 1"
      it.hasNext shouldBe true // the file is still open: rows are pulled, not preloaded
      it.map(_("name").str).toVector.last shouldBe "Venue 400"
    finally it.close()
  }

  test("an INT64 above 2^53 is refused instead of being rounded") {
    val file = tempDir().resolve("big.parquet")
    import PType.*
    ParquetFixtureWriter.write(
      file,
      Vector("n" -> I64),
      Vector(ujson.Obj("n" -> ujson.Num(math.pow(2, 60))))
    )
    val e = intercept[StorageException](ParquetRows.foreach(file)(_ => ()))
    e.error.message should include("2^53")
  }

  test("legacy and standard list encodings, maps and nulls decode to JSON") {
    val file = tempDir().resolve("shapes.parquet")
    import PType.*
    val fields = Vector(
      "s" -> Str,
      "l" -> StrList,
      "m" -> StrMap,
      "st" -> StructList(Vector("a" -> Str, "b" -> I32)),
      "absent" -> Str
    )
    ParquetFixtureWriter.write(
      file,
      fields,
      Vector(
        ujson.Obj(
          "s" -> "x",
          "l" -> ujson.Arr("p", "q"),
          "m" -> ujson.Obj("k" -> "v"),
          "st" -> ujson.Arr(ujson.Obj("a" -> "z", "b" -> 3)),
          "absent" -> ujson.Null
        )
      )
    )
    val row = ParquetRows.open(file).next()
    row("s") shouldBe ujson.Str("x")
    row("l") shouldBe ujson.Arr("p", "q")
    row("m") shouldBe ujson.Obj("k" -> "v")
    row("st") shouldBe ujson.Arr(ujson.Obj("a" -> "z", "b" -> 3))
    row("absent") shouldBe ujson.Null
  }

  // ----- manifest verification -----

  test("a verified manifest lists every file with a local path") {
    val v = ok(verified(FixturePaths.root, "neptune", LoadTarget.NEPTUNE))
    v.localFiles.size shouldBe v.manifest.tables.flatMap(_.files).size
    v.localFiles.values.foreach(p => Files.isRegularFile(p) shouldBe true)
  }

  test("a checksum mismatch fails the load") {
    val root = fixtureCopy()
    val m = manifestOf(root, "neptune")
    val victim = m.tables.find(_.tableName == "work").get.files.head
    val path = root.resolve(victim.path)
    val bytes = Files.readAllBytes(path)
    bytes(bytes.length / 2) = (bytes(bytes.length / 2) ^ 0x01).toByte // same size, one bit flipped
    Files.write(path, bytes)
    val e = err(verified(root, "neptune", LoadTarget.NEPTUNE))
    e shouldBe a[StorageError.ChecksumMismatch]
    e.asInstanceOf[StorageError.ChecksumMismatch].path shouldBe victim.path
    e.asInstanceOf[StorageError.ChecksumMismatch].expected shouldBe victim.checksum
  }

  test("a size mismatch fails the load") {
    val root = fixtureCopy()
    val m = manifestOf(root, "neptune")
    val victim = m.tables.head.files.head
    Files.write(root.resolve(victim.path), Array[Byte](1, 2, 3))
    err(verified(root, "neptune", LoadTarget.NEPTUNE)) shouldBe a[StorageError.SizeMismatch]
  }

  test("a missing file fails the load") {
    val root = fixtureCopy()
    val m = manifestOf(root, "neptune")
    Files.delete(root.resolve(m.tables.head.files.head.path))
    err(verified(root, "neptune", LoadTarget.NEPTUNE)) shouldBe a[StorageError.MissingFile]
  }

  test("a row count that disagrees with the files fails the load") {
    val root = fixtureCopy()
    val m = manifestOf(root, "neptune")
    val wrong =
      m.copy(tables = m.tables.map(t => if t.tableName == "venue" then t.copy(rowCount = 9) else t))
    val e = err(LoadManifests.verify(wrong, LoadTarget.NEPTUNE, new LocalFileStager(root)))
    e shouldBe StorageError.RowCountMismatch("venue", 9, 1)
  }

  test("a manifest for another target is refused") {
    err(verified(FixturePaths.root, "s3", LoadTarget.NEPTUNE)) shouldBe a[
      StorageError.InvalidManifest
    ]
  }

  test("a malformed or unsupported manifest is reported with a reason") {
    LoadManifests.parse("{ not json") shouldBe a[Left[?, ?]]
    val m = write(manifestOf(FixturePaths.root, "neptune"))
      .replace("load-manifest-v1", "load-manifest-v9")
    val e = err(LoadManifests.parse(m))
    e shouldBe a[StorageError.InvalidManifest]
    e.message should include("load-manifest-v9")
  }
