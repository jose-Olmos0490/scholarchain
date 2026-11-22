package scholarchain.rag.embed

import scala.collection.mutable
import scholarchain.core.config.{VectorIndexConfig, VectorIndexSettingsConfig}
import scholarchain.rag.common.*

/** The embedding model an index is bound to. `modelId` and `version` identify the model, `dimension`
  * is the vector length, `normalize` says whether vectors are L2-normalized.
  */
final case class EmbeddingModelSpec(
    modelId: String,
    version: String,
    dimension: Int,
    normalize: Boolean
):
  require(modelId.trim.nonEmpty && version.trim.nonEmpty && dimension >= 1)
  def fingerprint: String =
    Hashing.sha256Hex(s"$modelId|$version|$dimension|$normalize")

object EmbeddingModelSpec:
  val ModelIdVar = "EMBEDDING_MODEL_ID"
  val VersionVar = "EMBEDDING_MODEL_VERSION"
  val NormalizeVar = "SCHOLARCHAIN_EMBEDDING_NORMALIZE"

  /** The model comes from the environment; the dimension of each index comes from the vector index
    * settings of the core configuration. Nothing is defaulted.
    */
  def load(env: Map[String, String], dimension: Int): RResult[EmbeddingModelSpec] =
    val r = new EnvReader(env)
    val id = r.string(ModelIdVar)
    val v = r.string(VersionVar)
    val n = r.boolean(NormalizeVar)
    r.result(EmbeddingModelSpec(id, v, dimension, n))

  /** One spec per configured index name. */
  def perIndex(
      names: VectorIndexConfig,
      settings: VectorIndexSettingsConfig,
      env: Map[String, String]
  ): RResult[Map[String, EmbeddingModelSpec]] =
    val pairs = Vector(
      names.researchers -> settings.researchers.dimension,
      names.communities -> settings.communities.dimension,
      names.abstracts -> settings.abstracts.dimension,
      names.paths -> settings.paths.dimension,
      names.topicInstitutions -> settings.topicInstitutions.dimension
    )
    pairs.foldLeft[RResult[Map[String, EmbeddingModelSpec]]](Right(Map.empty)) {
      case (acc, (name, dim)) => acc.flatMap(m => load(env, dim).map(s => m + (name -> s)))
    }

/** Where an index's model binding is recorded. The durable implementation (DynamoDB ArtifactRegistry)
  * belongs to the storage module; tests use [[InMemoryIndexBindings]].
  */
trait IndexBindings:
  def bound(indexName: String): Option[EmbeddingModelSpec]
  def bind(indexName: String, spec: EmbeddingModelSpec): Unit

final class InMemoryIndexBindings extends IndexBindings:
  private val m = mutable.Map.empty[String, EmbeddingModelSpec]
  def bound(indexName: String): Option[EmbeddingModelSpec] = synchronized(m.get(indexName))
  def bind(indexName: String, spec: EmbeddingModelSpec): Unit = synchronized { m(indexName) = spec }

/** An index is bound to one model and dimension. A different model, version, dimension or
  * normalization means a new index version (`...-v2`), never an overwrite of `...-v1`.
  */
object IndexBinder:
  private val Versioned = "(.*-v)([1-9][0-9]*)".r

  def nextIndexName(indexName: String): RResult[String] = indexName match
    case Versioned(prefix, n) => Right(s"$prefix${n.toInt + 1}")
    case _ =>
      Left(RagProblem("UNVERSIONED_INDEX", s"index name '$indexName' does not end in -v<N>"))

  /** Binds an unbound index; accepts the identical spec; refuses anything else. */
  def ensure(
      bindings: IndexBindings,
      indexName: String,
      spec: EmbeddingModelSpec
  ): RResult[Unit] =
    bindings.bound(indexName) match
      case None => Right(bindings.bind(indexName, spec))
      case Some(b) if b == spec => Right(())
      case Some(b) =>
        val next = nextIndexName(indexName).getOrElse("a new index version")
        Left(
          RagProblem(
            "INDEX_MODEL_MISMATCH",
            s"index $indexName is bound to $b; configured model is $spec. " +
              s"Create index $next for the new model; never overwrite $indexName."
          )
        )
