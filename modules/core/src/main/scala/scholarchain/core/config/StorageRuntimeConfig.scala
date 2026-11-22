package scholarchain.core.config

/** S3 Vectors index type. The limits differ by type (filterable metadata 2 KB on CLASSIC, 40 KB
  * on ENHANCED), so the configured type must match the deployed index.
  */
enum VectorIndexType(val wire: String):
  case Classic extends VectorIndexType("CLASSIC")
  case Enhanced extends VectorIndexType("ENHANCED")

object VectorIndexType:
  def parse(s: String): Option[VectorIndexType] = values.find(_.wire == s)

/** S3 Vectors distance metric (wire value, lower case). */
enum DistanceMetric(val wire: String):
  case Cosine extends DistanceMetric("cosine")
  case Euclidean extends DistanceMetric("euclidean")

object DistanceMetric:
  def parse(s: String): Option[DistanceMetric] = values.find(_.wire == s)

/** Neptune bulk-loader `parallelism` parameter values. */
enum LoaderParallelism(val wire: String):
  case Low extends LoaderParallelism("LOW")
  case Medium extends LoaderParallelism("MEDIUM")
  case High extends LoaderParallelism("HIGH")
  case Oversubscribe extends LoaderParallelism("OVERSUBSCRIBE")

object LoaderParallelism:
  def parse(s: String): Option[LoaderParallelism] = values.find(_.wire == s)

/** Documented service maximums that configuration is validated against. Source-format facts of the
  * AWS APIs, not tunables (S3 Vectors limits page, retrieved 2026-11-21: PutVectors 500,
  * GetVectors 100, dimension 1..4096; DynamoDB BatchWriteItem: 25 items).
  */
object ServiceLimits:
  val PutVectorsMaxBatch = 500
  val GetVectorsMaxBatch = 100
  val DynamoBatchWriteMaxItems = 25
  val VectorMaxDimension = 4096

/** DynamoDB table names. Each is `scholarchain.dynamodb.tables.<name>` (environment variable
  * `SCHOLARCHAIN_DDB_TABLE_<NAME>`); when unset it is `<tablePrefix>-<environment>-<table>`, for
  * example `scholarchain-dev-run-ledger`. `researcherMetricCache` has no default: it is present only
  * when explicitly configured.
  */
final case class DynamoTablesConfig(
    runLedger: String,
    entityLinkCache: String,
    querySession: String,
    evidenceBundle: String,
    artifactRegistry: String,
    researcherMetricCache: Option[String]
)

object DynamoTablesConfig:
  private val TableName = "[A-Za-z0-9_.-]{3,255}".r
  private val Prefix = "[a-z][a-z0-9-]*".r

  def section(environment: String): ConfigSection[DynamoTablesConfig] =
    new ConfigSection[DynamoTablesConfig]:
      def read(c: ConfigCursor): DynamoTablesConfig =
        val prefix = c.string("dynamodb.tablePrefix", "SCHOLARCHAIN_DDB_TABLE_PREFIX")
        if prefix.nonEmpty && !Prefix.matches(prefix) then
          c.fail("dynamodb.tablePrefix", s"'$prefix' must match [a-z][a-z0-9-]*")
        def table(key: String, suffix: String): String =
          val name =
            c.optionalString(s"dynamodb.tables.$key").getOrElse(s"$prefix-$environment-$suffix")
          if !TableName.matches(name) then
            c.fail(s"dynamodb.tables.$key", s"'$name' is not a valid DynamoDB table name")
          name
        val cache = c.optionalString("dynamodb.tables.researcherMetricCache")
        cache.foreach { n =>
          if !TableName.matches(n) then
            c.fail(
              "dynamodb.tables.researcherMetricCache",
              s"'$n' is not a valid DynamoDB table name"
            )
        }
        DynamoTablesConfig(
          runLedger = table("runLedger", "run-ledger"),
          entityLinkCache = table("entityLinkCache", "entity-link-cache"),
          querySession = table("querySession", "query-session"),
          evidenceBundle = table("evidenceBundle", "evidence-bundle"),
          artifactRegistry = table("artifactRegistry", "artifact-registry"),
          researcherMetricCache = cache
        )

/** Key prefixes inside the configured buckets. Each is relative, ends in `/`, has no leading `/`
  * and no empty segment.
  */
final case class S3PrefixConfig(reports: String, logs: String, neptuneExport: String)

object S3PrefixConfig extends ConfigSection[S3PrefixConfig]:
  private def prefix(c: ConfigCursor, key: String, envVar: String): String =
    val v = c.string(key, envVar)
    if v.nonEmpty && (v.startsWith("/") || !v.endsWith("/") || v.contains("//")) then
      c.fail(key, s"'$v' must be relative, end in '/', and have no empty segment")
    v

  def read(c: ConfigCursor): S3PrefixConfig = S3PrefixConfig(
    reports = prefix(c, "s3Prefixes.reports", "SCHOLARCHAIN_S3_PREFIX_REPORTS"),
    logs = prefix(c, "s3Prefixes.logs", "SCHOLARCHAIN_S3_PREFIX_LOGS"),
    neptuneExport = prefix(c, "s3Prefixes.neptuneExport", "SCHOLARCHAIN_S3_PREFIX_NEPTUNE_EXPORT")
  )

/** Batch sizes, each validated against the documented service maximum. */
final case class BatchConfig(
    putVectors: Int,
    getVectors: Int,
    dynamoBatchWrite: Int,
    neptuneLoaderParallelism: LoaderParallelism
)

object BatchConfig extends ConfigSection[BatchConfig]:
  def read(c: ConfigCursor): BatchConfig =
    val par = c.string("batch.neptuneLoaderParallelism", "SCHOLARCHAIN_NEPTUNE_LOADER_PARALLELISM")
    val parsed = LoaderParallelism.parse(par)
    if par.nonEmpty && parsed.isEmpty then
      c.fail(
        "batch.neptuneLoaderParallelism",
        s"'$par' is unknown; allowed: ${LoaderParallelism.values.map(_.wire).mkString(", ")}"
      )
    BatchConfig(
      putVectors = c.int(
        "batch.putVectors",
        "SCHOLARCHAIN_STORAGE_PUT_VECTORS_BATCH_SIZE",
        min = 1,
        max = ServiceLimits.PutVectorsMaxBatch
      ),
      getVectors = c.int(
        "batch.getVectors",
        "SCHOLARCHAIN_STORAGE_GET_VECTORS_BATCH_SIZE",
        min = 1,
        max = ServiceLimits.GetVectorsMaxBatch
      ),
      dynamoBatchWrite = c.int(
        "batch.dynamoBatchWrite",
        "SCHOLARCHAIN_STORAGE_DYNAMODB_BATCH_WRITE_SIZE",
        min = 1,
        max = ServiceLimits.DynamoBatchWriteMaxItems
      ),
      neptuneLoaderParallelism = parsed.getOrElse(LoaderParallelism.Medium)
    )

/** Retry policy for storage adapters: `maxAttempts >= 1`, delays `>= 1` ms, `maxDelayMs >=
  * baseDelayMs`.
  */
final case class RetryPolicyConfig(maxAttempts: Int, baseDelayMs: Int, maxDelayMs: Int)

object RetryPolicyConfig extends ConfigSection[RetryPolicyConfig]:
  def read(c: ConfigCursor): RetryPolicyConfig =
    val attempts = c.int("retry.maxAttempts", "SCHOLARCHAIN_STORAGE_RETRY_MAX_ATTEMPTS", min = 1)
    val base = c.intOption("retry.baseDelayMs", "SCHOLARCHAIN_STORAGE_RETRY_BASE_DELAY_MS", min = 1)
    val max = c.intOption("retry.maxDelayMs", "SCHOLARCHAIN_STORAGE_RETRY_MAX_DELAY_MS", min = 1)
    for b <- base; m <- max if m < b do
      c.fail("retry.maxDelayMs", s"must be >= baseDelayMs ($b), got $m")
    RetryPolicyConfig(attempts, base.getOrElse(0), max.getOrElse(0))

/** Settings of one S3 Vectors index. */
final case class VectorIndexSettings(
    dimension: Int,
    distanceMetric: DistanceMetric,
    indexType: VectorIndexType
)

/** Per-index vector settings for the five configured indexes. */
final case class VectorIndexSettingsConfig(
    researchers: VectorIndexSettings,
    communities: VectorIndexSettings,
    abstracts: VectorIndexSettings,
    paths: VectorIndexSettings,
    topicInstitutions: VectorIndexSettings
)

object VectorIndexSettingsConfig extends ConfigSection[VectorIndexSettingsConfig]:
  private def one(c: ConfigCursor, name: String, envName: String): VectorIndexSettings =
    val base = s"vectorIndexSettings.$name"
    val envBase = s"SCHOLARCHAIN_VECTOR_INDEX_$envName"
    val dim = c.int(
      s"$base.dimension",
      s"${envBase}_DIMENSION",
      min = 1,
      max = ServiceLimits.VectorMaxDimension
    )
    val metric = c.string(s"$base.distanceMetric", s"${envBase}_DISTANCE_METRIC")
    val parsedMetric = DistanceMetric.parse(metric)
    if metric.nonEmpty && parsedMetric.isEmpty then
      c.fail(
        s"$base.distanceMetric",
        s"'$metric' is unknown; allowed: ${DistanceMetric.values.map(_.wire).mkString(", ")}"
      )
    val tpe = c.string(s"$base.indexType", s"${envBase}_TYPE")
    val parsedType = VectorIndexType.parse(tpe)
    if tpe.nonEmpty && parsedType.isEmpty then
      c.fail(
        s"$base.indexType",
        s"'$tpe' is unknown; allowed: ${VectorIndexType.values.map(_.wire).mkString(", ")}"
      )
    VectorIndexSettings(
      dim,
      parsedMetric.getOrElse(DistanceMetric.Cosine),
      parsedType.getOrElse(VectorIndexType.Classic)
    )

  def read(c: ConfigCursor): VectorIndexSettingsConfig = VectorIndexSettingsConfig(
    researchers = one(c, "researchers", "RESEARCHERS"),
    communities = one(c, "communities", "COMMUNITIES"),
    abstracts = one(c, "abstracts", "ABSTRACTS"),
    paths = one(c, "paths", "PATHS"),
    topicInstitutions = one(c, "topicInstitutions", "TOPIC_INSTITUTIONS")
  )

/** Neptune bulk-loader IAM role ARN. Environment variable only (`NEPTUNE_LOADER_ROLE_ARN`), no
  * shipped value, required only by the live loader: loading the section never fails when it is
  * absent; the live loader calls [[requireRoleArn]].
  */
final case class NeptuneLoaderConfig(roleArn: Option[String]):
  def requireRoleArn: Either[String, String] =
    roleArn.toRight("NEPTUNE_LOADER_ROLE_ARN is required by the live Neptune loader")

object NeptuneLoaderConfig extends ConfigSection[NeptuneLoaderConfig]:
  private val RoleArn = "arn:aws[a-z-]*:iam::[0-9]{12}:role/[A-Za-z0-9+=,.@/_-]+".r

  def read(c: ConfigCursor): NeptuneLoaderConfig =
    val arn = c.optionalString("neptune.loaderRoleArn")
    arn.foreach { a =>
      if !RoleArn.matches(a) then c.fail("neptune.loaderRoleArn", "is not an IAM role ARN")
    }
    NeptuneLoaderConfig(arn)

/** QuerySession time-to-live in days (>= 1). */
final case class QuerySessionConfig(ttlDays: Int):
  def ttlSeconds: Long = ttlDays.toLong * 86400L

object QuerySessionConfig extends ConfigSection[QuerySessionConfig]:
  def read(c: ConfigCursor): QuerySessionConfig =
    QuerySessionConfig(
      c.int("querySession.ttlDays", "SCHOLARCHAIN_QUERY_SESSION_TTL_DAYS", min = 1)
    )

/** Optional per-service endpoint overrides (local and integration tests; absent means the AWS
  * default) and the NAME of the environment variable that selects the explicit AWS profile for the
  * integration suite (the profile value itself is never read here).
  */
final case class EndpointOverridesConfig(
    s3: Option[String],
    dynamodb: Option[String],
    s3Vectors: Option[String],
    awsProfileVariable: String
)

object EndpointOverridesConfig extends ConfigSection[EndpointOverridesConfig]:
  private val VarName = "[A-Z_][A-Z0-9_]*".r

  private def endpoint(c: ConfigCursor, key: String): Option[String] =
    val v = c.optionalString(key)
    v.foreach { e =>
      val l = e.toLowerCase(java.util.Locale.ROOT)
      if !(l.startsWith("http://") || l.startsWith("https://")) then
        c.fail(key, s"'$e' must be an http(s) URL")
    }
    v

  def read(c: ConfigCursor): EndpointOverridesConfig =
    val variable =
      c.string("integration.awsProfileVariable", "SCHOLARCHAIN_IT_AWS_PROFILE_VARIABLE")
    if variable.nonEmpty && !VarName.matches(variable) then
      c.fail("integration.awsProfileVariable", s"'$variable' is not an environment variable name")
    EndpointOverridesConfig(
      s3 = endpoint(c, "endpoints.s3"),
      dynamodb = endpoint(c, "endpoints.dynamodb"),
      s3Vectors = endpoint(c, "endpoints.s3Vectors"),
      awsProfileVariable = variable
    )

/** Everything the storage module needs beyond bucket, Neptune endpoint and index names. */
final case class StorageRuntimeConfig(
    dynamoTables: DynamoTablesConfig,
    s3Prefixes: S3PrefixConfig,
    batch: BatchConfig,
    retry: RetryPolicyConfig,
    vectorIndexSettings: VectorIndexSettingsConfig,
    neptuneLoader: NeptuneLoaderConfig,
    querySession: QuerySessionConfig,
    endpoints: EndpointOverridesConfig
)

object StorageRuntimeConfig:
  def section(environment: String): ConfigSection[StorageRuntimeConfig] =
    new ConfigSection[StorageRuntimeConfig]:
      def read(c: ConfigCursor): StorageRuntimeConfig = StorageRuntimeConfig(
        dynamoTables = DynamoTablesConfig.section(environment).read(c),
        s3Prefixes = S3PrefixConfig.read(c),
        batch = BatchConfig.read(c),
        retry = RetryPolicyConfig.read(c),
        vectorIndexSettings = VectorIndexSettingsConfig.read(c),
        neptuneLoader = NeptuneLoaderConfig.read(c),
        querySession = QuerySessionConfig.read(c),
        endpoints = EndpointOverridesConfig.read(c)
      )
