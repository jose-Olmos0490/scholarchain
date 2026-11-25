package scholarchain.storage

import java.util.UUID
import org.scalatest.Tag
import scholarchain.core.config.{DistanceMetric, VectorIndexType}
import scholarchain.storage.common.*
import scholarchain.storage.vectors.*

/** Builders for vector records keyed by the frozen vector-key formula. */
object Vectors:
  val Model = "test-embedding-model-v1"
  val ModelVersion = "2026-01"
  val DocumentType = "researcher-profile"

  /** A unique text, its hash and the vector key built from a unique ordinal. */
  final case class Text(text: String, hash: String, key: String)

  def text(label: String = "profile"): Text =
    val t = s"$label ${UUID.randomUUID}"
    val ordinal = scala.util.Random.nextInt(Int.MaxValue)
    val key = VectorKeys
      .of(Fixture.resA.researcherId, DocumentType, 2010, 2013, ordinal)
      .fold(e => throw new IllegalStateException(e.message), identity)
    Text(t, Sha256.of(t), key)

  def meta(
      t: Text,
      scope: String = "scope",
      model: String = Model,
      entityId: String = Fixture.resA.researcherId,
      window: (Int, Int) = (2010, 2013)
  ): VectorMetadata =
    VectorMetadata(
      entityId = entityId,
      documentType = DocumentType,
      windowStartYear = window._1,
      windowEndYear = window._2,
      topicIds = Vector.empty,
      institutionIds = Vector.empty,
      sourceDataset = "SCHOLARCHAIN",
      communityId = Some(scope),
      textS3Uri = s"s3://test-rag/profiles/${t.key.takeRight(16)}.json",
      textHash = t.hash,
      embeddingModel = model,
      embeddingModelVersion = ModelVersion,
      runId = Fixture.runId,
      schemaVersion = MetadataSchema.Version
    )

  def record(t: Text, v: Vector[Float], scope: String = "scope"): VectorRecord =
    VectorRecord(t.key, v, meta(t, scope))

/** Reusable contract of [[VectorIndex]]; queries are scoped by a unique `communityId` so the same
  * cases run against a shared real index.
  */
abstract class VectorIndexContract extends StorageSuite with PortGate:
  protected def index: VectorIndex
  protected def definition: IndexDefinition

  private def contract(name: String)(body: => Unit): Unit =
    test(s"vector index contract: $name", contractTags*) {
      preconditions()
      body
    }

  /** Four meaningful components, padded with zeros to the index dimension. */
  private def vec(a: Float, b: Float, c: Float, d: Float): Vector[Float] =
    Vector(a, b, c, d) ++ Vector.fill(definition.dimension - 4)(0f)
  private def scopeFilter(scope: String) = VectorFilter.Eq("communityId", ujson.Str(scope))

  contract("describe returns the deployed definition; an unknown index is None") {
    val deployed = ok(index.describe(definition.name))
    withClue(s"deployed $deployed, configured $definition: ") {
      deployed.exists(definition.matches) shouldBe true
    }
    ok(index.describe("no-such-index-v9")) shouldBe None
  }

  contract("put then get returns metadata, and the data only when asked") {
    val t = Vectors.text()
    ok(index.put(definition.name, Vector(Vectors.record(t, vec(1, 0, 0, 0)))))
    val withoutData = ok(index.get(definition.name, Vector(t.key), returnData = false))
    withoutData.map(_.key) shouldBe Vector(t.key)
    withoutData.head.metadata shouldBe Vectors.meta(t)
    withoutData.head.embedding shouldBe None
    ok(index.get(definition.name, Vector(t.key), returnData = true)).head.embedding shouldBe
      Some(vec(1, 0, 0, 0))
    ok(index.get(definition.name, Vector(Vectors.text().key), returnData = false)) shouldBe empty
  }

  contract("the stored metadata keeps the pointer, the text hash and the embedding model") {
    val t = Vectors.text()
    ok(index.put(definition.name, Vector(Vectors.record(t, vec(0, 1, 0, 0)))))
    val m = ok(index.get(definition.name, Vector(t.key), returnData = false)).head.metadata
    (m.textS3Uri, m.textHash, m.embeddingModel, m.runId) shouldBe
      (Vectors.meta(t).textS3Uri, t.hash, Vectors.Model, Fixture.runId)
  }

  contract("query returns the nearest vectors first and applies the metadata filter") {
    val scope = s"scope-${UUID.randomUUID}"
    val near = Vectors.text("near")
    val mid = Vectors.text("mid")
    val far = Vectors.text("far")
    ok(
      index.put(
        definition.name,
        Vector(
          Vectors.record(far, vec(0, 0, 1, 0), scope),
          Vectors.record(near, vec(1, 0.01f, 0, 0), scope),
          Vectors.record(mid, vec(1, 1, 0, 0), scope)
        )
      )
    )
    val q = VectorQuery(vec(1, 0, 0, 0), topK = 2, filter = Some(scopeFilter(scope)))
    ok(index.query(definition.name, q)).map(_.key) shouldBe Vector(near.key, mid.key)
    ok(index.query(definition.name, q.copy(topK = 10)))
      .map(_.key) shouldBe Vector(near.key, mid.key, far.key)
    ok(index.query(definition.name, q)).head.metadata.map(_.textHash) shouldBe Some(near.hash)
    ok(index.query(definition.name, q.copy(returnMetadata = false))).head.metadata shouldBe None

    val onlyWindow = VectorFilter.And(
      Vector(
        scopeFilter(scope),
        VectorFilter.Gte("windowStartYear", 2010),
        VectorFilter.Lte("windowEndYear", 2013)
      )
    )
    ok(index.query(definition.name, q.copy(topK = 10, filter = Some(onlyWindow)))).size shouldBe 3
    val noMatch =
      VectorFilter.And(Vector(scopeFilter(scope), VectorFilter.Gte("windowStartYear", 2020)))
    ok(index.query(definition.name, q.copy(topK = 10, filter = Some(noMatch)))) shouldBe empty
    val inSet = VectorFilter.And(
      Vector(
        scopeFilter(scope),
        VectorFilter.In("documentType", Vector(ujson.Str("researcher-profile"), ujson.Str("other")))
      )
    )
    ok(index.query(definition.name, q.copy(topK = 10, filter = Some(inSet)))).size shouldBe 3
  }

  contract("a filter on a non-filterable key is refused before the request") {
    err(
      index.query(
        definition.name,
        VectorQuery(vec(1, 0, 0, 0), 1, Some(VectorFilter.Eq("textHash", ujson.Str("sha256:x"))))
      )
    ) shouldBe a[StorageError.InvalidRecord]
  }

  contract("documented limits are enforced client-side: batch sizes, topK, dimension") {
    val many =
      (1 to 501).map(i => Vectors.record(Vectors.text(s"bulk $i"), vec(1, 0, 0, 0))).toVector
    err(index.put(definition.name, many)) shouldBe
      StorageError.LimitExceeded("vectors per PutVectors call", 500, 501)
    err(index.put(definition.name, Vector.empty)) shouldBe a[StorageError.InvalidRecord]
    err(
      index.get(definition.name, (1 to 101).map(i => s"k$i").toVector, returnData = false)
    ) shouldBe
      StorageError.LimitExceeded("keys per GetVectors call", 100, 101)
    err(index.query(definition.name, VectorQuery(vec(1, 0, 0, 0), 10001, None))) shouldBe
      StorageError.LimitExceeded("topK", 10000, 10001)
    err(index.query(definition.name, VectorQuery(vec(1, 0, 0, 0), 0, None))) shouldBe a[
      StorageError.InvalidRecord
    ]
    err(index.query(definition.name, VectorQuery(Vector(1f, 0f), 1, None))) shouldBe a[
      StorageError.InvalidRecord
    ]
    err(index.put(definition.name, Vector(Vectors.record(Vectors.text(), Vector(1f, 0f))))) shouldBe
      a[StorageError.ValidationFailed]
  }

class InMemoryVectorIndexSpec extends VectorIndexContract:
  protected val definition: IndexDefinition = IndexDefinition.fromSettings(TestEnv.settings).head
  protected val index: VectorIndex = new InMemoryVectorIndex(Vector(definition))
