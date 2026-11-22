package scholarchain.storage

import java.nio.file.{Files, Path, Paths}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.model.LoadManifest
import scholarchain.storage.common.*
import scholarchain.storage.manifest.LoadManifests

/** Hooks of the reusable contract suites: the tags their tests carry and the preconditions that
  * cancel them. Fakes use the defaults; the AWS suites override both.
  */
trait PortGate:
  protected def contractTags: Seq[org.scalatest.Tag] = Nil
  protected def preconditions(): Unit = ()

/** Shared helpers for the storage specs. */
trait StorageSuite extends AnyFunSuite with Matchers:
  def ok[A](r: Result[A]): A = r.fold(e => fail(s"unexpected failure: ${e.message}"), identity)

  def err[A](r: Result[A]): StorageError = r match
    case Left(e) => e
    case Right(v) => fail(s"expected a failure, got $v")

  def tempDir(prefix: String = "storage-test-"): Path =
    val d = Files.createTempDirectory(prefix)
    sys.addShutdownHook {
      if Files.exists(d) then
        import scala.jdk.CollectionConverters.*
        Files.walk(d).iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists(_))
    }
    d

  /** Copies the committed fixture into a fresh temp directory so a test may damage it. */
  def fixtureCopy(): Path =
    val target = tempDir("fixture-")
    import scala.jdk.CollectionConverters.*
    val src = FixturePaths.root
    Files.walk(src).iterator().asScala.foreach { p =>
      val rel = src.relativize(p)
      val dest = target.resolve(rel.toString)
      if Files.isDirectory(p) then Files.createDirectories(dest)
      else Files.copy(p, dest)
    }
    target

  def manifestOf(root: Path, name: String): LoadManifest =
    ok(LoadManifests.read(root.resolve("load-manifests").resolve(s"$name.json")))

object FixturePaths:
  /** The committed fixture directory (a classpath directory of the test resources). */
  lazy val root: Path =
    Paths
      .get(getClass.getResource("/fixture/v1/load-manifests/neptune.json").toURI)
      .getParent
      .getParent

/** The environment of a storage process in tests; every value is a test value. */
object TestEnv:
  val vars: Map[String, String] = Map(
    "SCHOLARCHAIN_ENV" -> "dev",
    "AWS_REGION" -> "eu-west-1",
    "RAW_BUCKET" -> "test-raw",
    "CURATED_BUCKET" -> "test-curated",
    "GRAPH_BUCKET" -> "test-graph",
    "RAG_BUCKET" -> "test-rag",
    "VECTOR_BUCKET" -> "test-vectors",
    "NEPTUNE_ENDPOINT" -> "neptune.test.invalid",
    "NEPTUNE_PORT" -> "8182",
    "NEPTUNE_QUERY_TIMEOUT_MS" -> "20000",
    "VECTOR_INDEX_RESEARCHERS" -> "researcher-profile-v1",
    "VECTOR_INDEX_COMMUNITIES" -> "collaboration-community-v1",
    "VECTOR_INDEX_ABSTRACTS" -> "paper-abstract-v1",
    "VECTOR_INDEX_PATHS" -> "collaboration-path-v1",
    "VECTOR_INDEX_TOPIC_INSTITUTIONS" -> "topic-institution-summary-v1",
    "SCHOLARCHAIN_RETRIEVAL_GRAPH_LIMIT" -> "25",
    "SCHOLARCHAIN_RETRIEVAL_MAX_PATH_DEPTH" -> "4",
    "EMBEDDING_MODEL_ID" -> "test-embedding-model-v1",
    // small vectors and small batches so the tests can see batching; one ENHANCED, one euclidean
    "SCHOLARCHAIN_VECTOR_INDEX_RESEARCHERS_DIMENSION" -> "4",
    "SCHOLARCHAIN_VECTOR_INDEX_COMMUNITIES_DIMENSION" -> "4",
    "SCHOLARCHAIN_VECTOR_INDEX_COMMUNITIES_TYPE" -> "ENHANCED",
    "SCHOLARCHAIN_VECTOR_INDEX_ABSTRACTS_DIMENSION" -> "4",
    "SCHOLARCHAIN_VECTOR_INDEX_ABSTRACTS_DISTANCE_METRIC" -> "euclidean",
    "SCHOLARCHAIN_VECTOR_INDEX_PATHS_DIMENSION" -> "4",
    "SCHOLARCHAIN_VECTOR_INDEX_TOPIC_INSTITUTIONS_DIMENSION" -> "4",
    "SCHOLARCHAIN_STORAGE_PUT_VECTORS_BATCH_SIZE" -> "3",
    "SCHOLARCHAIN_STORAGE_GET_VECTORS_BATCH_SIZE" -> "2"
  )

  lazy val settings: StorageSettings =
    StorageSettings.load(vars).fold(e => throw new IllegalStateException(e.message), identity)

/** A sleeper that records the delays instead of waiting. */
final class RecordingSleeper extends Sleeper:
  val delays = scala.collection.mutable.ArrayBuffer.empty[Long]
  def sleep(millis: Long): Unit = delays += millis

/** Deterministic jitter: always the upper bound, so tests see the exact bound. */
object MaxJitter extends Jitter:
  def upTo(boundInclusive: Long): Long = boundInclusive
