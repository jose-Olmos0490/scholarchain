package scholarchain.core

import com.typesafe.config.ConfigFactory
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.config.*

/** Phase 07 repair: storage runtime configuration (tables, prefixes, batches, retry, vector index
  * settings, loader role, session TTL, endpoint overrides). The process environment is never read.
  */
class StorageRuntimeConfigSpec extends AnyFunSuite with Matchers:

  private def env(name: String, vars: (String, String)*): Map[String, String] =
    Map(ConfigLoader.EnvironmentVariable -> name) ++ vars

  private def load(name: String, vars: (String, String)*): StorageRuntimeConfig =
    ConfigLoader
      .loadSection(StorageRuntimeConfig.section(name), env(name, vars*))
      .fold(e => fail(e.message), identity)

  private def rejected(vars: (String, String)*): Vector[InvalidSetting] =
    ConfigLoader.loadSection(StorageRuntimeConfig.section("dev"), env("dev", vars*)) match
      case Left(ConfigError.Invalid(missing, inv)) =>
        missing shouldBe empty
        inv
      case other => fail(s"expected ConfigError.Invalid, got $other")

  private def rejectedAt(path: String, vars: (String, String)*): Unit =
    rejected(vars*).map(_.path) should contain(s"scholarchain.$path")

  test("defaults: tables follow scholarchain-<env>-<table>; optional cache and ARN are absent") {
    val c = load("dev")
    c.dynamoTables shouldBe DynamoTablesConfig(
      "scholarchain-dev-run-ledger",
      "scholarchain-dev-entity-link-cache",
      "scholarchain-dev-query-session",
      "scholarchain-dev-evidence-bundle",
      "scholarchain-dev-artifact-registry",
      None
    )
    load("stage").dynamoTables.runLedger shouldBe "scholarchain-stage-run-ledger"
    c.neptuneLoader.roleArn shouldBe None
    c.neptuneLoader.requireRoleArn.isLeft shouldBe true
    c.endpoints shouldBe EndpointOverridesConfig(None, None, None, "SCHOLARCHAIN_AWS_PROFILE")
  }

  test("defaults: prefixes, batch sizes, retry, vector indexes and session TTL") {
    val c = load("dev")
    c.s3Prefixes shouldBe S3PrefixConfig("reports/", "logs/", "neptune-export/")
    c.batch shouldBe BatchConfig(500, 100, 25, LoaderParallelism.Medium)
    c.retry shouldBe RetryPolicyConfig(5, 200, 5000)
    val expected = VectorIndexSettings(1024, DistanceMetric.Cosine, VectorIndexType.Classic)
    c.vectorIndexSettings shouldBe VectorIndexSettingsConfig(
      expected,
      expected,
      expected,
      expected,
      expected
    )
    c.querySession shouldBe QuerySessionConfig(7)
    c.querySession.ttlSeconds shouldBe 604800L
  }

  test("environment overrides every storage setting") {
    val c = load(
      "dev",
      "SCHOLARCHAIN_DDB_TABLE_PREFIX" -> "acme",
      "SCHOLARCHAIN_DDB_TABLE_RUN_LEDGER" -> "t-ledger",
      "SCHOLARCHAIN_DDB_TABLE_ENTITY_LINK_CACHE" -> "t-links",
      "SCHOLARCHAIN_DDB_TABLE_QUERY_SESSION" -> "t-sessions",
      "SCHOLARCHAIN_DDB_TABLE_EVIDENCE_BUNDLE" -> "t-evidence",
      "SCHOLARCHAIN_DDB_TABLE_ARTIFACT_REGISTRY" -> "t-artifacts",
      "SCHOLARCHAIN_DDB_TABLE_RESEARCHER_METRIC_CACHE" -> "t-metrics",
      "SCHOLARCHAIN_S3_PREFIX_REPORTS" -> "r/x/",
      "SCHOLARCHAIN_S3_PREFIX_LOGS" -> "l/",
      "SCHOLARCHAIN_S3_PREFIX_NEPTUNE_EXPORT" -> "n/",
      "SCHOLARCHAIN_STORAGE_PUT_VECTORS_BATCH_SIZE" -> "10",
      "SCHOLARCHAIN_STORAGE_GET_VECTORS_BATCH_SIZE" -> "20",
      "SCHOLARCHAIN_STORAGE_DYNAMODB_BATCH_WRITE_SIZE" -> "5",
      "SCHOLARCHAIN_NEPTUNE_LOADER_PARALLELISM" -> "OVERSUBSCRIBE",
      "SCHOLARCHAIN_STORAGE_RETRY_MAX_ATTEMPTS" -> "3",
      "SCHOLARCHAIN_STORAGE_RETRY_BASE_DELAY_MS" -> "10",
      "SCHOLARCHAIN_STORAGE_RETRY_MAX_DELAY_MS" -> "10",
      "SCHOLARCHAIN_VECTOR_INDEX_PATHS_DIMENSION" -> "4096",
      "SCHOLARCHAIN_VECTOR_INDEX_PATHS_DISTANCE_METRIC" -> "euclidean",
      "SCHOLARCHAIN_VECTOR_INDEX_PATHS_TYPE" -> "ENHANCED",
      "SCHOLARCHAIN_QUERY_SESSION_TTL_DAYS" -> "1",
      "SCHOLARCHAIN_ENDPOINT_S3" -> "http://localhost:4566",
      "SCHOLARCHAIN_ENDPOINT_DYNAMODB" -> "http://localhost:8000",
      "SCHOLARCHAIN_ENDPOINT_S3_VECTORS" -> "https://vectors.example.invalid",
      "SCHOLARCHAIN_IT_AWS_PROFILE_VARIABLE" -> "MY_PROFILE",
      "NEPTUNE_LOADER_ROLE_ARN" -> "arn:aws:iam::123456789012:role/loader"
    )
    c.dynamoTables shouldBe DynamoTablesConfig(
      "t-ledger",
      "t-links",
      "t-sessions",
      "t-evidence",
      "t-artifacts",
      Some("t-metrics")
    )
    c.s3Prefixes shouldBe S3PrefixConfig("r/x/", "l/", "n/")
    c.batch shouldBe BatchConfig(10, 20, 5, LoaderParallelism.Oversubscribe)
    c.retry shouldBe RetryPolicyConfig(3, 10, 10)
    c.vectorIndexSettings.paths shouldBe
      VectorIndexSettings(4096, DistanceMetric.Euclidean, VectorIndexType.Enhanced)
    c.vectorIndexSettings.researchers.dimension shouldBe 1024
    c.querySession.ttlDays shouldBe 1
    c.endpoints shouldBe EndpointOverridesConfig(
      Some("http://localhost:4566"),
      Some("http://localhost:8000"),
      Some("https://vectors.example.invalid"),
      "MY_PROFILE"
    )
    c.neptuneLoader.requireRoleArn shouldBe Right("arn:aws:iam::123456789012:role/loader")
  }

  test("the table prefix changes the default table names") {
    load("dev", "SCHOLARCHAIN_DDB_TABLE_PREFIX" -> "acme").dynamoTables.querySession shouldBe
      "acme-dev-query-session"
  }

  test("reject: batch sizes above the documented service maximums, and below 1") {
    rejectedAt("batch.putVectors", "SCHOLARCHAIN_STORAGE_PUT_VECTORS_BATCH_SIZE" -> "501")
    rejectedAt("batch.getVectors", "SCHOLARCHAIN_STORAGE_GET_VECTORS_BATCH_SIZE" -> "101")
    rejectedAt("batch.dynamoBatchWrite", "SCHOLARCHAIN_STORAGE_DYNAMODB_BATCH_WRITE_SIZE" -> "26")
    rejectedAt("batch.putVectors", "SCHOLARCHAIN_STORAGE_PUT_VECTORS_BATCH_SIZE" -> "0")
    load(
      "dev",
      "SCHOLARCHAIN_STORAGE_PUT_VECTORS_BATCH_SIZE" -> "500"
    ).batch.putVectors shouldBe 500
  }

  test("reject: unknown Neptune loader parallelism") {
    rejectedAt(
      "batch.neptuneLoaderParallelism",
      "SCHOLARCHAIN_NEPTUNE_LOADER_PARALLELISM" -> "medium"
    )
  }

  test("reject: non-positive retry values and maxDelayMs below baseDelayMs") {
    rejectedAt("retry.maxAttempts", "SCHOLARCHAIN_STORAGE_RETRY_MAX_ATTEMPTS" -> "0")
    rejectedAt("retry.baseDelayMs", "SCHOLARCHAIN_STORAGE_RETRY_BASE_DELAY_MS" -> "-1")
    rejectedAt("retry.maxDelayMs", "SCHOLARCHAIN_STORAGE_RETRY_MAX_DELAY_MS" -> "0")
    rejectedAt("retry.maxDelayMs", "SCHOLARCHAIN_STORAGE_RETRY_MAX_DELAY_MS" -> "199")
  }

  test("reject: unknown index type, unknown distance metric, bad dimension") {
    rejectedAt(
      "vectorIndexSettings.abstracts.indexType",
      "SCHOLARCHAIN_VECTOR_INDEX_ABSTRACTS_TYPE" -> "TURBO"
    )
    rejectedAt(
      "vectorIndexSettings.communities.distanceMetric",
      "SCHOLARCHAIN_VECTOR_INDEX_COMMUNITIES_DISTANCE_METRIC" -> "dot"
    )
    rejectedAt(
      "vectorIndexSettings.researchers.dimension",
      "SCHOLARCHAIN_VECTOR_INDEX_RESEARCHERS_DIMENSION" -> "0"
    )
    rejectedAt(
      "vectorIndexSettings.researchers.dimension",
      "SCHOLARCHAIN_VECTOR_INDEX_RESEARCHERS_DIMENSION" -> "4097"
    )
  }

  test("reject: bad prefixes, table names, TTL, ARN, endpoint and profile variable name") {
    rejectedAt("s3Prefixes.reports", "SCHOLARCHAIN_S3_PREFIX_REPORTS" -> "/abs/")
    rejectedAt("s3Prefixes.logs", "SCHOLARCHAIN_S3_PREFIX_LOGS" -> "no-slash")
    rejectedAt("dynamodb.tables.runLedger", "SCHOLARCHAIN_DDB_TABLE_RUN_LEDGER" -> "a b")
    rejectedAt("querySession.ttlDays", "SCHOLARCHAIN_QUERY_SESSION_TTL_DAYS" -> "0")
    rejectedAt("neptune.loaderRoleArn", "NEPTUNE_LOADER_ROLE_ARN" -> "not-an-arn")
    rejectedAt("endpoints.s3", "SCHOLARCHAIN_ENDPOINT_S3" -> "localhost:4566")
    rejectedAt(
      "integration.awsProfileVariable",
      "SCHOLARCHAIN_IT_AWS_PROFILE_VARIABLE" -> "bad name"
    )
  }

  test("loader role ARN is environment-only: no shipped file contains an ARN or account ID") {
    val files = Seq(
      ConfigLoader.BaseResource,
      ConfigLoader.environmentResource("dev")
    )
    files.foreach { f =>
      val rendered = ConfigFactory
        .parseResources(getClass.getClassLoader, f)
        .root()
        .render()
      rendered should not include "arn:"
      rendered should not include "loaderRoleArn"
      "[0-9]{12}".r.findFirstIn(rendered) shouldBe None
    }
  }
