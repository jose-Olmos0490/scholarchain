package scholarchain.storage

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import org.scalatest.Tag
import scholarchain.core.config.S3PrefixConfig
import scholarchain.core.model.*
import scholarchain.storage.common.*
import scholarchain.storage.loader.*
import scholarchain.storage.manifest.*
import scholarchain.storage.objects.*
import upickle.default.*

/** The reusable contract of [[ObjectStore]]. Run against the in-memory fake by default; the tagged
  * `S3ObjectStoreIT` runs the same cases against the AWS adapter.
  */
abstract class ObjectStoreContract extends StorageSuite with PortGate:
  protected def store: ObjectStore
  protected def keyPrefix: String

  private def contract(name: String)(body: => Unit): Unit =
    test(s"object store contract: $name", contractTags*) {
      preconditions()
      body
    }

  private def ref(name: String) = ObjectRef(ObjectArea.Reports, s"$keyPrefix$name")
  private def bytes(s: String) = s.getBytes(UTF_8)

  contract("a new key is created and reads back with its SHA-256") {
    val r = ref("a/one.txt")
    ok(store.putBytes(r, bytes("hello"), "text/plain")) shouldBe PutOutcome.Created
    new String(ok(store.getBytes(r)).get, UTF_8) shouldBe "hello"
    val meta = ok(store.head(r)).get
    meta.sha256 shouldBe Sha256.of("hello")
    meta.sizeBytes shouldBe 5L
  }

  contract("same key and same content is a skip") {
    val r = ref("a/same.txt")
    ok(store.putBytes(r, bytes("x"), "text/plain")) shouldBe PutOutcome.Created
    ok(store.putBytes(r, bytes("x"), "text/plain")) shouldBe PutOutcome.Skipped
  }

  contract("same key and different content fails loudly and keeps the original") {
    val r = ref("a/conflict.txt")
    ok(store.putBytes(r, bytes("original"), "text/plain"))
    val e = err(store.putBytes(r, bytes("changed"), "text/plain"))
    e shouldBe a[StorageError.ConflictingContent]
    e.asInstanceOf[StorageError.ConflictingContent].existing shouldBe Sha256.of("original")
    e.asInstanceOf[StorageError.ConflictingContent].incoming shouldBe Sha256.of("changed")
    new String(ok(store.getBytes(r)).get, UTF_8) shouldBe "original"
  }

  contract("a file is stored with its verified hash; a wrong hash is refused") {
    val f = Files.createTempFile("obj-", ".bin")
    Files.write(f, bytes("file content"))
    val r = ref("b/file.bin")
    ok(store.putFile(r, f, Sha256.of("file content"), 12)) shouldBe PutOutcome.Created
    ok(store.putFile(r, f, Sha256.of("file content"), 12)) shouldBe PutOutcome.Skipped
    val bad = ref("b/bad.bin")
    store.putFile(bad, f, Sha256.of("something else"), 12).isLeft shouldBe true
    ok(store.head(bad)) shouldBe None
  }

  contract("list returns only keys under the prefix, sorted") {
    ok(store.putBytes(ref("c/2.txt"), bytes("2"), "text/plain"))
    ok(store.putBytes(ref("c/1.txt"), bytes("1"), "text/plain"))
    ok(store.putBytes(ref("d/3.txt"), bytes("3"), "text/plain"))
    ok(store.list(ObjectArea.Reports, s"${keyPrefix}c/")).map(_.ref.key) shouldBe
      Vector(s"${keyPrefix}c/1.txt", s"${keyPrefix}c/2.txt")
  }

  contract("download copies the object; a missing object is None") {
    val r = ref("e/dl.txt")
    ok(store.putBytes(r, bytes("download me"), "text/plain"))
    val target = Files.createTempFile("dl-", ".txt")
    ok(store.downloadTo(r, target)).get.sha256 shouldBe Sha256.of("download me")
    Files.readString(target) shouldBe "download me"
    ok(store.downloadTo(ref("e/missing.txt"), target)) shouldBe None
    ok(store.head(ref("e/missing.txt"))) shouldBe None
    ok(store.getBytes(ref("e/missing.txt"))) shouldBe None
  }

class InMemoryObjectStoreSpec extends ObjectStoreContract:
  protected val store = new InMemoryObjectStore
  protected def keyPrefix = ""

/** The S3 load: layout, run manifest, idempotency, conflicts, and the verified-input gate. */
class S3LoaderSpec extends StorageSuite:
  private val layout = ok(
    ObjectLayout.fromConfig(
      TestEnv.settings.buckets,
      TestEnv.settings.runtime.s3Prefixes,
      ObjectArea.Graph
    )
  )
  private val root = FixturePaths.root
  private def manifest = manifestOf(root, "s3")
  private def verified(store: ObjectStore = new InMemoryObjectStore) =
    ok(LoadManifests.verify(manifest, LoadTarget.S3, new LocalFileStager(root)))

  test(
    "layout: four buckets from configuration; reports, logs and neptune export use configured prefixes"
  ) {
    layout.bucket(ObjectArea.Raw) shouldBe "test-raw"
    layout.bucket(ObjectArea.Curated) shouldBe "test-curated"
    layout.bucket(ObjectArea.Graph) shouldBe "test-graph"
    layout.bucket(ObjectArea.Rag) shouldBe "test-rag"
    layout.bucket(ObjectArea.Reports) shouldBe "test-graph"
    layout.fullKey(ObjectRef(ObjectArea.Reports, "x")) shouldBe "reports/x"
    layout.fullKey(ObjectRef(ObjectArea.Logs, "x")) shouldBe "logs/x"
    layout.fullKey(ObjectRef(ObjectArea.NeptuneExport, "x")) shouldBe "neptune-export/x"
    layout.fullKey(ObjectRef(ObjectArea.Graph, "x")) shouldBe "x"
    layout.s3Uri(ObjectRef(ObjectArea.Rag, "k")) shouldBe "s3://test-rag/k"
  }

  test("layout: the bucket of the auxiliary areas is an explicit choice among the four buckets") {
    val bad = ObjectLayout.fromConfig(
      TestEnv.settings.buckets,
      TestEnv.settings.runtime.s3Prefixes,
      ObjectArea.Reports
    )
    err(bad) shouldBe a[StorageError.Misconfigured]
  }

  test(
    "every file lands under run_id=<hex>/<table>/..., semantic facts in the rag area, the rest in graph"
  ) {
    val store = new InMemoryObjectStore
    val report = ok(new S3Loader(store, new InMemoryRejectSink).load(verified()))
    val hex = Fixture.runHex
    val keys = store.snapshot.map((r, _) => (r.area, r.key))
    keys.foreach((_, k) => k should startWith(s"run_id=$hex/"))
    keys.count(_._1 == ObjectArea.Rag) shouldBe 1
    keys.filter(_._1 == ObjectArea.Rag).head._2 should include("/semantic_fact/window=2010-2013/")
    keys.filter(_._1 == ObjectArea.Graph).map(_._2) should contain(
      s"run_id=$hex/coauthored_with/window=2010-2012/run_id=$hex/part-00000.snappy.parquet"
    )
    report.created shouldBe manifest.tables.flatMap(_.files).size
    report.skipped shouldBe 0
  }

  test("the run manifest lists key, sha256, size, schema version and source run for every object") {
    val store = new InMemoryObjectStore
    val report = ok(new S3Loader(store, new InMemoryRejectSink).load(verified()))
    val bytes = ok(store.getBytes(ObjectRef(ObjectArea.Reports, report.manifestKey))).get
    val run = read[S3RunManifest](new String(bytes, UTF_8))
    run.schemaVersion shouldBe "s3-run-manifest-v1"
    run.sourceRunId shouldBe Fixture.runId
    run.provenance.runId shouldBe Fixture.runId
    val files = manifest.tables.flatMap(t => t.files.map(f => (t, f)))
    run.objects.size shouldBe files.size
    files.foreach { (t, f) =>
      val entry = run.objects
        .find(o => o.key.endsWith(f.path.stripPrefix(t.location)) && o.tableName == t.tableName)
        .get
      entry.sha256 shouldBe f.checksum
      entry.sizeBytes shouldBe f.sizeBytes
      entry.schemaVersion shouldBe t.recordSchemaVersion
      // the stored object really has that hash
      ok(
        store.head(ObjectRef(ObjectArea.values.find(_.wire == entry.area).get, entry.key))
      ).get.sha256 shouldBe f.checksum
    }
    run.objects.map(o => (o.area, o.key)) shouldBe run.objects.map(o => (o.area, o.key)).sorted
  }

  test("idempotent reload: loading the same run twice leaves identical store state") {
    val store = new InMemoryObjectStore
    val loader = new S3Loader(store, new InMemoryRejectSink)
    val first = ok(loader.load(verified()))
    val before = store.snapshot
    val second = ok(loader.load(verified()))
    store.snapshot shouldBe before
    second.created shouldBe 0
    second.skipped shouldBe first.created
    second.manifestOutcome shouldBe PutOutcome.Skipped
  }

  test("a key that already holds different content fails the load and is recorded") {
    val store = new InMemoryObjectStore
    val rejects = new InMemoryRejectSink
    val m = manifest
    val t = m.tables.find(_.tableName == "venue").get
    val f = t.files.head
    val rel = f.path.stripPrefix(t.location + "/")
    val key = ObjectKeys.tableFile(Fixture.runHex, "venue", rel)
    ok(store.putBytes(ObjectRef(ObjectArea.Graph, key), "tampered".getBytes(UTF_8), "x"))
    val e = err(new S3Loader(store, rejects).load(verified()))
    e shouldBe a[StorageError.ConflictingContent]
    rejects.all.map(_.reasonCode) shouldBe Vector("KEY_CONFLICT")
    rejects.all.head.key shouldBe key
    // the run manifest was not written
    ok(store.list(ObjectArea.Reports, ObjectKeys.runDir(Fixture.runHex))) shouldBe empty
  }

  test("a checksum mismatch stops the load before anything is written") {
    val dir = fixtureCopy()
    val m = manifestOf(dir, "s3")
    val victim = m.tables.head.files.head
    val p = dir.resolve(victim.path)
    val b = Files.readAllBytes(p)
    b(10) = (b(10) ^ 0x01).toByte
    Files.write(p, b)
    val store = new InMemoryObjectStore
    val v = LoadManifests.verify(m, LoadTarget.S3, new LocalFileStager(dir))
    err(v) shouldBe a[StorageError.ChecksumMismatch]
    store.snapshot shouldBe empty
  }

  test("a run ID that is not run:<hex> cannot name a key") {
    err(ObjectKeys.runHex("run:abc").map(identity)) shouldBe a[StorageError.InvalidRecord]
    ObjectKeys.runHex(Fixture.runId) shouldBe Right(Fixture.runHex)
  }
