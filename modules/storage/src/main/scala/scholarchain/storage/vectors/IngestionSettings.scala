package scholarchain.storage.vectors

import scholarchain.core.config.RetryPolicyConfig
import scholarchain.storage.StorageSettings
import scholarchain.storage.common.*

/** Everything the ingestion path reads from configuration. Batch sizes and the retry policy come
  * from the storage runtime configuration (`batch.putVectors`, `batch.getVectors`, `retry.*`); the
  * remaining values are read here from environment variables because the shared core
  * configuration does not define them yet (follow-up for `domain-engineer`). None has a built-in
  * default: a missing value is a [[StorageError.Misconfigured]] naming the variable.
  *
  * @param rateLimitPerSecond
  *   PutVectors requests per second, per index name
  */
final case class IngestionSettings(
    putBatchSize: Int,
    getBatchSize: Int,
    retry: RetryPolicyConfig,
    rateLimitPerSecond: Map[String, Int],
    maxTopicIds: Int,
    maxInstitutionIds: Int,
    embeddingModel: String,
    embeddingModelVersion: String,
    ledgerSequence: Int
):
  def metadataPolicy: MetadataPolicy =
    MetadataPolicy(maxTopicIds, maxInstitutionIds, VectorServiceLimits.IngestFilterableBudgetBytes)

object IngestionSettings:
  val RateLimitDefaultVar = "SCHOLARCHAIN_VECTOR_INGEST_RATE_LIMIT_PER_SECOND"
  val MaxTopicIdsVar = "SCHOLARCHAIN_VECTOR_INGEST_MAX_TOPIC_IDS"
  val MaxInstitutionIdsVar = "SCHOLARCHAIN_VECTOR_INGEST_MAX_INSTITUTION_IDS"
  val LedgerSequenceVar = "SCHOLARCHAIN_VECTOR_INGEST_LEDGER_SEQUENCE"
  val ModelVersionVar = "EMBEDDING_MODEL_VERSION"

  /** Optional per-index override variable: `SCHOLARCHAIN_VECTOR_INGEST_RATE_LIMIT_<SUFFIX>`. */
  private def overrides(s: StorageSettings): Vector[(String, String)] =
    val n = s.vectorIndexNames
    Vector(
      n.researchers -> "RESEARCHERS",
      n.communities -> "COMMUNITIES",
      n.abstracts -> "ABSTRACTS",
      n.paths -> "PATHS",
      n.topicInstitutions -> "TOPIC_INSTITUTIONS"
    ).map((name, suffix) => name -> s"SCHOLARCHAIN_VECTOR_INGEST_RATE_LIMIT_$suffix")

  def load(storage: StorageSettings, env: Map[String, String]): Result[IngestionSettings] =
    val problems = Vector.newBuilder[String]
    def raw(v: String): Option[String] = env.get(v).map(_.trim).filter(_.nonEmpty)
    def int(v: String, min: Int, max: Int): Option[Int] =
      raw(v) match
        case None => problems += s"$v is required"; None
        case Some(s) =>
          s.toIntOption.filter(i => i >= min && i <= max) match
            case None => problems += s"$v='$s' must be an integer in [$min, $max]"; None
            case ok => ok
    val default = int(RateLimitDefaultVar, 1, 1000000)
    val perIndex = overrides(storage).flatMap { (name, v) =>
      raw(v) match
        case None => default.map(name -> _)
        case Some(s) =>
          s.toIntOption.filter(_ >= 1) match
            case Some(i) => Some(name -> i)
            case None => problems += s"$v='$s' must be an integer >= 1"; None
    }.toMap
    val topics = int(MaxTopicIdsVar, 1, 1000)
    val insts = int(MaxInstitutionIdsVar, 1, 1000)
    val seq = int(LedgerSequenceVar, 0, 99999)
    val version = raw(ModelVersionVar)
    if version.isEmpty then problems += s"$ModelVersionVar is required"
    if storage.embeddingModelId.trim.isEmpty then problems += "EMBEDDING_MODEL_ID is required"
    val batch = storage.runtime.batch
    val bad = problems.result()
    if bad.nonEmpty then Left(StorageError.Misconfigured(bad.mkString("; ")))
    else
      Right(
        IngestionSettings(
          putBatchSize = batch.putVectors,
          getBatchSize = batch.getVectors,
          retry = storage.runtime.retry,
          rateLimitPerSecond = perIndex,
          maxTopicIds = topics.get,
          maxInstitutionIds = insts.get,
          embeddingModel = storage.embeddingModelId,
          embeddingModelVersion = version.get,
          ledgerSequence = seq.get
        )
      )
