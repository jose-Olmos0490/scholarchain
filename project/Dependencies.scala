import sbt._

/** ScholarChain single dependency ledger.
  *
  * Ownership split (docs/agents.md, "Documented shared-file control"):
  *   - dependency-updater changes only the version string values in `Versions`.
  *   - build-engineer owns the structure: which modules exist, their scopes, and
  *     how build.sbt consumes them.
  *
  * Every value is an exact release. No ranges, SNAPSHOTs, `latest`, or `+`.
  *
  * Plugin and launcher ledger (MIRROR ONLY, not read by sbt)
  * ---------------------------------------------------------
  * sbt reads its own version from project/build.properties and loads plugins
  * from project/plugins.sbt before it compiles this file, so neither can take a
  * value from here. The authoritative literals live in those two files. This
  * block mirrors them so that the full ledger can be reviewed in one place, and
  * any change must update both:
  *
  *   sbt                  1.13.1   project/build.properties
  *   sbt-assembly         2.5.0    project/plugins.sbt
  *   sbt-native-packager  1.13.0   project/plugins.sbt
  *   sbt-scalafmt         2.6.2    project/plugins.sbt
  *   scalafmt (core)      3.11.5   .scalafmt.conf `version`
  *   Docker base image    eclipse-temurin:17.0.21_9-jre-noble (Versions.temurinJreTag)
  */
object Dependencies {

  // ScholarChain's own artifact version. Build structure (build-engineer),
  // not a third-party dependency version, so it sits outside `Versions`.
  val projectVersion = "0.1.0"

  // Java release targeted by scalac and javac. A build-wide platform decision
  // (Java 17 everywhere: compiler target, EMR runtime, container JRE), owned by
  // build-engineer, so it sits outside `Versions` and is not a bump target.
  val javaRelease = "17"

  object Versions {
    // Scala 3 LTS. 3.3.7 is the newest 3.3.x whose scala-library (2.13.16)
    // stays <= the EMR emr-spark-8.1.0 runtime scala-library 2.13.17.
    // 3.3.8 and 3.3.9 require scala-library 2.13.18.
    val scala3 = "3.3.7"

    // EMR emr-spark-8.1.0 supplied (Provided).
    val hadoop = "3.4.2"
    val spark = "4.1.1"

    // Project-wide libraries.
    val upickle = "4.4.3"
    val scalatest = "3.2.20"
    val typesafeConfig = "1.4.10"
    val slf4j = "2.0.17"
    val logback = "1.5.38"
    val cask = "0.11.3"

    // AWS SDK for Java v2. Every awssdk artifact uses the BOM release version.
    val awsSdkBom = "2.59.4"

    // Container runtime for services (sbt-native-packager dockerBaseImage).
    val temurinJreTag = "17.0.21_9-jre-noble"
  }

  // JSON (the one project-wide JSON library).
  val upickle = "com.lihaoyi" %% "upickle" % Versions.upickle

  // Configuration (HOCON).
  val typesafeConfig = "com.typesafe" % "config" % Versions.typesafeConfig

  // Logging API (Compile). The implementation is Runtime-scoped in services only.
  val slf4jApi = "org.slf4j" % "slf4j-api" % Versions.slf4j
  val logbackClassic = "ch.qos.logback" % "logback-classic" % Versions.logback % Runtime

  // Test framework (the one project-wide test framework). Test scope only.
  val scalatest = "org.scalatest" %% "scalatest" % Versions.scalatest % Test

  // Hadoop MapReduce API, supplied by EMR.
  val hadoopClient = "org.apache.hadoop" % "hadoop-client" % Versions.hadoop % Provided

  // Spark, supplied by EMR. Spark publishes Scala 2.13 artifacts only.
  // scala-xml_2.13 is excluded because ScalaTest brings scala-xml_3 and sbt
  // rejects two cross-version suffixes of one module. Both are scala-xml 2.x;
  // on EMR the cluster's own copy is used.
  val sparkSql =
    ("org.apache.spark" %% "spark-sql" % Versions.spark % Provided)
      .cross(CrossVersion.for3Use2_13)
      .exclude("org.scala-lang.modules", "scala-xml_2.13")
  val sparkGraphx =
    ("org.apache.spark" %% "spark-graphx" % Versions.spark % Provided)
      .cross(CrossVersion.for3Use2_13)
      .exclude("org.scala-lang.modules", "scala-xml_2.13")

  // HTTP server for services.
  val cask = "com.lihaoyi" %% "cask" % Versions.cask

  // AWS SDK for Java v2 service clients, aligned to the BOM release.
  object Aws {
    private def sdk(artifact: String): ModuleID =
      "software.amazon.awssdk" % artifact % Versions.awsSdkBom

    val s3 = sdk("s3")
    val dynamodb = sdk("dynamodb")
    val s3vectors = sdk("s3vectors")
    val neptunedata = sdk("neptunedata")
    val bedrockruntime = sdk("bedrockruntime")
    val sqs = sdk("sqs")
    val sfn = sdk("sfn")
    val secretsmanager = sdk("secretsmanager")
  }

  // Per-module dependency sets consumed by build.sbt.
  val common: Seq[ModuleID] = Seq(scalatest)

  val core: Seq[ModuleID] = Seq(upickle, typesafeConfig, slf4jApi)

  val hadoopJobs: Seq[ModuleID] = Seq(hadoopClient)

  // Model invocation for LLM match adjudication.
  val entityResolution: Seq[ModuleID] = Seq(Aws.bedrockruntime)

  val sparkAnalytics: Seq[ModuleID] = Seq(sparkSql, sparkGraphx)

  // S3, Neptune, DynamoDB, S3 Vectors adapters and the SQS embedding work queue.
  val storage: Seq[ModuleID] =
    Seq(Aws.s3, Aws.dynamodb, Aws.s3vectors, Aws.neptunedata, Aws.sqs)

  // Model invocation for runtime agents and embeddings.
  val rag: Seq[ModuleID] = Seq(Aws.bedrockruntime)

  // HTTP transport, runtime secrets lookup, Step Functions run control,
  // and the only logging backend in the build.
  val services: Seq[ModuleID] =
    Seq(cask, Aws.secretsmanager, Aws.sfn, logbackClassic)
}
