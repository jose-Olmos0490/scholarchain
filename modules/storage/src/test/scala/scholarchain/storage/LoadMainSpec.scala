package scholarchain.storage

import java.nio.file.{Files, Path}
import scholarchain.core.model.{LoadManifest, LoadTarget}
import scholarchain.storage.common.*
import scholarchain.storage.dynamo.*
import scholarchain.storage.loader.*
import scholarchain.storage.manifest.LoadManifests
import scholarchain.storage.objects.*
import upickle.default.write

/** The loader entry point: argument parsing, the dry run, and idempotent reloads on fakes. */
class LoadMainSpec extends StorageSuite:
  private val run = Fixture.runId
  private val hex = Fixture.runHex
  private val snapshot = Fixture.Snapshot

  /** The committed fixture laid out the way Spark lays out its output: `load-manifests/target=<t>/
    * run_id=<hex>/load-manifest.json` next to the table directories.
    */
  private def sparkOutput(): Path =
    val root = tempDir("load-main-")
    Fixture.generate(root)
    def put(target: LoadTarget, names: Vector[String]): Unit =
      val m: LoadManifest = Fixture.manifest(root, target, names)
      val p = SparkOutputLayout.manifestPath(root, target, hex)
      Files.createDirectories(p.getParent)
      Files.write(p, write(m).getBytes("UTF-8"))
    put(LoadTarget.DYNAMODB, Vector("researcher", "researcher_collaboration"))
    put(LoadTarget.NEPTUNE, Fixture.neptuneTables)
    put(LoadTarget.S3, Fixture.s3Tables)
    root

  private def args(root: Path, extra: String*): Vector[String] =
    Vector("--spark-output", root.toString, "--run-id", run, "--snapshot", snapshot) ++ extra

  // ---- argument parsing ----

  test("all flags parse; targets default to the three steps") {
    val a = LoadArgs.parse(args(Path.of("/x"))).toOption.get
    a.runId shouldBe run
    a.snapshot shouldBe snapshot
    a.dryRun shouldBe false
    a.targets shouldBe Vector(LoadStep.Dynamo, LoadStep.S3, LoadStep.NeptuneCsv)
    a.workDir shouldBe None
    val b = LoadArgs
      .parse(args(Path.of("/x"), "--dry-run", "--targets", "s3,dynamo,s3", "--work-dir=/w"))
      .toOption
      .get
    b.dryRun shouldBe true
    b.targets shouldBe Vector(LoadStep.S3, LoadStep.Dynamo)
    b.workDir shouldBe Some(Path.of("/w"))
  }

  test("missing, malformed and unknown arguments are refused with a reason") {
    def bad(a: Seq[String]) = LoadArgs.parse(a).left.toOption.get
    bad(Seq("--run-id", run, "--snapshot", "s")) should include("--spark-output")
    bad(Seq("--spark-output", "/x", "--snapshot", "s")) should include("--run-id")
    bad(Seq("--spark-output", "/x", "--run-id", run)) should include("--snapshot")
    bad(Seq("--spark-output", "/x", "--run-id", "run:abc", "--snapshot", "s")) should include(
      "run ID"
    )
    bad(args(Path.of("/x"), "--targets", "dynamo,neptunes")) should include("unknown target")
    bad(args(Path.of("/x"), "--targets", "dynamo,")) should include("empty")
    bad(args(Path.of("/x"), "--bogus")) should include("unknown argument")
    bad(args(Path.of("/x"), "--dry-run", "--dry-run")) should include("twice")
    bad(Seq("--spark-output")) should include("needs a value")
    bad(Seq("--spark-output", "/x", "--spark-output", "/y")) should include("twice")
  }

  // ---- dry run ----

  test("dry run reads only local files with no configuration and prints counts, keys and samples") {
    val root = sparkOutput()
    val lines = Vector.newBuilder[String]
    val code = LoadMain.execute(args(root, "--dry-run"), Map.empty, lines += _)
    val text = lines.result().mkString("\n")
    withClue(text) {
      code shouldBe 0
      text should include("mode=DRY-RUN")
      text should include(s"rows=${Fixture.tableNamed("researcher").rowCount}")
      text should include("ResearcherDirectoryEntry")
      text should include("pk=")
      text should include("sample ")
      text should include("S3 objects that would be written")
      text should include("run_id=" + hex)
      text should include("no AWS client was built")
      text should include("no Neptune load started")
    }
  }

  test("dry run refuses a manifest of another snapshot or run, and a missing manifest") {
    val root = sparkOutput()
    def go(a: Vector[String]): (Int, String) =
      val out = Vector.newBuilder[String]
      val c = LoadMain.execute(a, Map.empty, out += _)
      (c, out.result().mkString("\n"))
    val (c1, t1) = go(
      Vector(
        "--spark-output",
        root.toString,
        "--run-id",
        run,
        "--snapshot",
        "1999-01-01",
        "--dry-run"
      )
    )
    c1 shouldBe 1
    t1 should include("snapshot")
    val other = "run:" + "0" * 64
    val (c2, t2) = go(
      Vector(
        "--spark-output",
        root.toString,
        "--run-id",
        other,
        "--snapshot",
        snapshot,
        "--dry-run"
      )
    )
    c2 shouldBe 1
    t2 should include("LOAD FAILED")
    val (c3, _) = go(args(tempDir(), "--dry-run"))
    c3 shouldBe 1
  }

  test("a live run without configuration stops before building any client") {
    val root = sparkOutput()
    val out = Vector.newBuilder[String]
    LoadMain.execute(args(root), Map.empty, out += _) shouldBe 2
    out.result().mkString("\n") should include("configuration")
  }

  // ---- idempotent reload on fakes ----

  final private class Rig(root: Path, val items: ItemStore = new InMemoryItemStore):
    val objects = new InMemoryObjectStore
    val ledger = new DynamoRunLedger(items, "ledger-table")
    val rejects = new InMemoryRejectSink
    var sink: RejectSink = rejects
    def go(targets: Vector[LoadStep] = LoadStep.all): (Result[LoadOutcome], Vector[String]) =
      val out = Vector.newBuilder[String]
      val runner = new LoadRunner(
        objects,
        items,
        "read-model-table",
        Some(new DynamoResearcherMetricCache(items, "metrics-table")),
        Some(ledger),
        25,
        sink,
        tempDir("load-work-"),
        7,
        () => 1000L,
        out += _
      )
      val a = LoadArgs(root, run, snapshot, false, targets, None)
      (runner.run(a), out.result())

  test("a reload of the same run creates nothing new and records no second ledger entry") {
    val rig = new Rig(sparkOutput())
    val (first, firstOut) = rig.go()
    ok(first).steps shouldBe Vector("dynamo", "s3", "neptune-csv")
    firstOut.exists(_.matches(".*created=[1-9].*")) shouldBe true
    val ledger1 = ok(rig.ledger.list(run))
    ledger1.map(_.stage).sorted shouldBe Vector("load-dynamo", "load-neptune-csv", "load-s3")
    ledger1.foreach { e =>
      e.state shouldBe LedgerState.SUCCEEDED
      e.attempt shouldBe 1
      e.sequence shouldBe 7
      e.provenance.snapshot shouldBe snapshot
    }
    val objects1 = rig.objects.snapshot

    val (second, secondOut) = rig.go()
    ok(second).rejected shouldBe 0L
    withClue(secondOut.mkString("\n")) {
      secondOut.exists(_.matches(".*created=[1-9].*")) shouldBe false
      secondOut.exists(_.contains("unchanged=")) shouldBe true
      secondOut.exists(_.contains("already recorded")) shouldBe true
    }
    ok(rig.ledger.list(run)) shouldBe ledger1
    rig.objects.snapshot shouldBe objects1
  }

  test("S3 objects land under the run_id prefix; Neptune CSV lands in the export area") {
    val rig = new Rig(sparkOutput())
    ok(rig.go(Vector(LoadStep.S3, LoadStep.NeptuneCsv))._1)
    val snap = rig.objects.snapshot
    snap
      .filter(_._1.area == ObjectArea.Graph)
      .map(_._1.key)
      .foreach(_ should startWith(s"run_id=$hex/"))
    snap.exists(_._1.area == ObjectArea.NeptuneExport) shouldBe true
    snap.exists(r =>
      r._1.area == ObjectArea.Reports && r._1.key.endsWith("s3-run-manifest.json")
    ) shouldBe true
  }

  test("a damaged input fails the step, writes nothing, and records a FAILED ledger attempt") {
    val root = sparkOutput()
    val m = ok(LoadManifests.read(SparkOutputLayout.manifestPath(root, LoadTarget.S3, hex)))
    val victim = root.resolve(m.tables.head.files.head.path)
    Files.write(victim, "damaged".getBytes("UTF-8"))
    val rig = new Rig(root)
    val (r, _) = rig.go(Vector(LoadStep.S3))
    err(r)
    rig.objects.snapshot shouldBe empty
    val entries = ok(rig.ledger.list(run))
    entries.map(e => (e.stage, e.state, e.attempt)) shouldBe Vector(
      ("load-s3", LedgerState.FAILED, 1)
    )
  }

  test("the stager refuses remote paths and parent segments") {
    val st = new SparkOutputStager(tempDir())
    err(st.stage("s3://bucket/x.parquet")).message should include("copy it down")
    err(st.stage("graph/../../secret/x.parquet")).message should include("..")
  }

  // ---- conflicts, ledger states, exit codes ----

  /** Behaves like an item store in which one researcher entry already exists with other content.
    * Counts every write that would replace it.
    */
  final private class ConflictStore(inner: ItemStore) extends ItemStore:
    private var victim: Option[(String, String)] = None
    var replaced = 0
    private def isVictim(i: ujson.Obj): Boolean =
      victim.contains((i("pk").str, i("sk").str))
    def putIfAbsent(table: String, item: ujson.Obj): Result[Unit] =
      if victim.isEmpty && item.obj.get("kind").exists(_.str == "ResearcherDirectoryEntry") then
        victim = Some((item("pk").str, item("sk").str))
      if isVictim(item) then Left(StorageError.AlreadyExists("victim"))
      else inner.putIfAbsent(table, item)
    def putAllIfAbsent(table: String, items: Vector[ujson.Obj]): Result[Unit] =
      inner.putAllIfAbsent(table, items)
    def get(table: String, pk: String, sk: String): Result[Option[ujson.Obj]] =
      if victim.contains((pk, sk)) then
        Right(Some(ujson.Obj("pk" -> pk, "sk" -> sk, "contentHash" -> "sha256:other")))
      else inner.get(table, pk, sk)
    def replaceIfHash(table: String, item: ujson.Obj, expectedHash: String): Result[Boolean] =
      if isVictim(item) then replaced += 1
      inner.replaceIfHash(table, item, expectedHash)
    def query(
        table: String,
        pk: String,
        skPrefix: Option[String],
        ascending: Boolean,
        limit: Option[Int]
    ): Result[Vector[ujson.Obj]] = inner.query(table, pk, skPrefix, ascending, limit)

  test("an existing item with different content is rejected, kept, and LoadMain exits 3") {
    val root = sparkOutput()
    val store = new ConflictStore(new InMemoryItemStore)
    val out = Vector.newBuilder[String]
    val code = LoadMain.execute(
      args(root, "--dry-run", "--targets", "dynamo"),
      Map.empty,
      out += _,
      () => store
    )
    withClue(out.result().mkString("\n")) {
      code shouldBe 3
      store.replaced shouldBe 0
      out.result().exists(_.contains("CONTENT_CONFLICT")) shouldBe true
    }
    // the stored item still has the other content
    val rig = new Rig(root, store)
    val (r, _) = rig.go(Vector(LoadStep.Dynamo))
    ok(r).rejected should be > 0L
    LoadMain.finish(r, rig.rejects, _ => ()) shouldBe 3
  }

  test("a load with rejects is recorded COMPLETED_WITH_REJECTS, never SUCCEEDED, on every rerun") {
    val rig = new Rig(sparkOutput(), new ConflictStore(new InMemoryItemStore))
    val (first, _) = rig.go(Vector(LoadStep.Dynamo))
    ok(first).rejected should be > 0L
    val e1 = ok(rig.ledger.list(run))
    e1.map(e => (e.stage, e.state, e.attempt)) shouldBe Vector(
      ("load-dynamo", LedgerState.COMPLETED_WITH_REJECTS, 1)
    )
    ujson.read(e1.head.detail)("rejects").num should be > 0.0
    val (second, out2) = rig.go(Vector(LoadStep.Dynamo))
    ok(second).rejected should be > 0L
    out2.exists(_.contains("already recorded")) shouldBe false
    ok(rig.ledger.list(run)).map(e => (e.state, e.attempt)) shouldBe Vector(
      (LedgerState.COMPLETED_WITH_REJECTS, 1),
      (LedgerState.COMPLETED_WITH_REJECTS, 2)
    )
    ok(rig.ledger.list(run)).exists(_.state == LedgerState.SUCCEEDED) shouldBe false
  }

  test("the ledger attempt increments after a FAILED attempt") {
    val root = sparkOutput()
    val m = ok(LoadManifests.read(SparkOutputLayout.manifestPath(root, LoadTarget.S3, hex)))
    val victim = root.resolve(m.tables.head.files.head.path)
    val good = Files.readAllBytes(victim)
    Files.write(victim, "damaged".getBytes("UTF-8"))
    val rig = new Rig(root)
    err(rig.go(Vector(LoadStep.S3))._1)
    Files.write(victim, good)
    ok(rig.go(Vector(LoadStep.S3))._1)
    ok(rig.ledger.list(run)).map(e => (e.state, e.attempt)) shouldBe Vector(
      (LedgerState.FAILED, 1),
      (LedgerState.SUCCEEDED, 2)
    )
  }

  test("only a missing metric cache is skippable; other Misconfigured errors fail the step") {
    val root = sparkOutput()
    val rig = new Rig(root)
    val noCache = new LoadRunner(
      rig.objects,
      rig.items,
      "rm",
      None,
      None,
      25,
      rig.rejects,
      tempDir("w-"),
      0,
      () => 0L,
      _ => ()
    )
    ok(noCache.run(LoadArgs(root, run, snapshot, false, Vector(LoadStep.Dynamo), None)))
    // a remote path inside the manifest raises Misconfigured in the stager; it must fail the step
    val st = new SparkOutputStager(root)
    err(st.stage("s3://b/k.parquet")) shouldBe a[StorageError.Misconfigured]
    val dir = tempDir("misconf-")
    val failing = new LoadRunner(
      rig.objects,
      rig.items,
      "rm",
      Some(new DynamoResearcherMetricCache(rig.items, "mc")),
      None,
      25,
      rig.rejects,
      dir,
      0,
      () => 0L,
      _ => ()
    )
    val bad = ok(LoadManifests.read(SparkOutputLayout.manifestPath(root, LoadTarget.DYNAMODB, hex)))
    // point the manifest's files at a remote path
    val remote = bad.copy(tables =
      bad.tables.map(t => t.copy(files = t.files.map(f => f.copy(path = "s3://b/" + f.path))))
    )
    Files.write(
      SparkOutputLayout.manifestPath(root, LoadTarget.DYNAMODB, hex),
      write(remote).getBytes("UTF-8")
    )
    err(failing.run(LoadArgs(root, run, snapshot, false, Vector(LoadStep.Dynamo), None)))
  }

  test("bad directories, paths and work dirs map to exit codes 1 and 2 with a message") {
    def go(a: Vector[String]): (Int, String) =
      val out = Vector.newBuilder[String]
      val c = LoadMain.execute(a, Map.empty, out += _)
      (c, out.result().mkString("\n"))
    val file = Files.createTempFile("not-a-dir", ".txt")
    val (c1, t1) = go(args(file, "--dry-run"))
    c1 shouldBe 2
    t1 should include("not a directory")
    val (c2, t2) =
      go(Vector("--spark-output", "bad\u0000path", "--run-id", run, "--snapshot", snapshot))
    c2 shouldBe 2
    t2 should include("not a valid path")
    // a work dir under a regular file cannot be created
    val (c3, t3) = go(args(sparkOutput(), "--dry-run", "--work-dir", file.resolve("sub").toString))
    c3 shouldBe 2
    t3 should include("work directory")
  }

  test("the stager refuses a symlink and a file outside the Spark output") {
    val root = tempDir("stage-root-")
    val outside = tempDir("stage-outside-")
    val secret = Files.write(outside.resolve("x.parquet"), "x".getBytes("UTF-8"))
    val st = new SparkOutputStager(root)
    err(st.stage(secret.toString)) shouldBe a[StorageError.MissingFile]
    Files.createDirectories(root.resolve("nodes"))
    Files.createSymbolicLink(root.resolve("nodes/x.parquet"), secret)
    err(st.stage("graph/nodes/x.parquet")) shouldBe a[StorageError.MissingFile]
    Files.write(root.resolve("nodes/y.parquet"), "y".getBytes("UTF-8"))
    ok(st.stage("/emr/graph/nodes/y.parquet")).local shouldBe root
      .resolve("nodes/y.parquet")
      .toRealPath()
  }

  test("a dry run counts items written through putAllIfAbsent") {
    val inner = new InMemoryItemStore
    val s = new SamplingItemStore(inner, 2)
    ok(s.putAllIfAbsent("t", Vector(ujson.Obj("pk" -> "a", "sk" -> "1", "kind" -> "K"))))
    s.kindCounts shouldBe Map("K" -> 1L)
    s.sampleLines.size shouldBe 1
  }
