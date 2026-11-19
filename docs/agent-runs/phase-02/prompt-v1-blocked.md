Use the build-engineer agent to create the ScholarChain sbt multi-project build and repository skeleton from scratch. No application logic, domain records, or behavior tests.

TARGET RUNTIME (human-architect decision)
- Scala 3, latest 3.3.x LTS patch. Verify its required scala-library 2.13 version is <= 2.13.17.
- Cluster: Amazon EMR release emr-spark-8.1.0 (Spark 4.1.1, Scala 2.13.17, Hadoop 3.4.2, Java 17). Both MapReduce and Spark jobs run on it.
- Java 17 everywhere: compiler -release 17, containers, EMR.

PROJECTS (sbt id -> directory)
- core -> modules/core
- hadoop -> modules/hadoop
- entityResolution -> modules/entity-resolution
- sparkAnalytics -> modules/spark
- storage -> modules/storage
- rag -> modules/rag
- services -> modules/services
- repoTests -> tests  (test-engineer territory: contracts, integration, end-to-end, adversarial)
- root aggregates all, publishes nothing.

DEPENDENCY GRAPH (acyclic; no other internal edges)
- core: none
- hadoop, entityResolution, sparkAnalytics, storage, rag -> core
- services -> core, rag, storage, entityResolution
- repoTests -> all modules (Test scope)

VERSION LEDGER
- Pin sbt to the latest 1.x release in project/build.properties (not 2.x; the assembly, native-packager and scalafmt plugins must be confirmed compatible). Create project/build.properties BEFORE running any sbt command.
- project/Versions.scala is the single ledger for every library version. build.sbt contains no version string literals.
- sbt (project/build.properties) and plugin versions (project/plugins.sbt) cannot be read from Versions.scala by sbt; mirror them in a commented ledger block in Versions.scala and explain this in the report.
- No latest, SNAPSHOT, ranges, or floating versions. Resolve exact versions via `cs complete-dep` or Maven Central; report the source of each.

LIBRARIES TO DECLARE NOW (so later phases don't BLOCK on build changes)
- JSON: uPickle. Tests: ScalaTest, Test scope only. Config: Typesafe Config (HOCON) in core.
- Logging: slf4j-api Compile; logback-classic Runtime scope ONLY in services. Never bundle a logging backend in hadoop or sparkAnalytics.
- hadoop: hadoop-client 3.4.2, Provided.
- sparkAnalytics: spark-sql and spark-graphx 4.1.1, Provided, .cross(CrossVersion.for3Use2_13).
- AWS SDK for Java v2 via its BOM version: s3, dynamodb, s3vectors, neptunedata, bedrockruntime, sqs, sfn, secretsmanager, placed only in the modules that need them. Verify each artifact exists at the pinned version.
- HTTP server for services: cask (com.lihaoyi).
- Report evictions and version conflicts.

PACKAGING
- sbt-assembly fat jars for hadoop and sparkAnalytics with a deterministic merge strategy.
  - hadoop jar MUST include scala-library and scala3-library.
  - sparkAnalytics jar MUST include scala3-library and MUST NOT include scala-library.
  - Neither jar may contain Hadoop or Spark classes. Prove all of this with `jar tf` output.
- sbt-native-packager Docker image for services only: eclipse-temurin 17 JRE pinned to an exact tag, non-root user, -XX:MaxRAMPercentage instead of fixed -Xmx.
- sbt-scalafmt; .scalafmt.conf pins its version and sets runner.dialect = scala3.

TEST MARKER AND ALIASES
- markTestRun depends on Test/test and touches .claude/.last-test-run only after tests pass. Read .claude/hooks/verify-tests-ran.sh and confirm it checks this exact path. Do not edit the hook.
- Tests tagged scholarchain.tags.AwsIntegration are excluded unless SCHOLARCHAIN_AWS_IT=true.
- fmt = scalafmtAll; scalafmtSbt
- check = scalafmtCheckAll; scalafmtSbtCheck; Test/compile; test; markTestRun
- packageJobs = hadoop/assembly; sparkAnalytics/assembly
- dockerLocal = services/Docker/publishLocal

CONSTRAINTS
- The build reads only documented env vars (SCHOLARCHAIN_AWS_IT). No secrets, buckets, endpoints, account IDs, or deployment steps.
- No compose file in this phase.
- Do not modify CLAUDE.md, docs/agents.md, or .claude/**. If they conflict with this order, return BLOCKED-ON factory-engineer.

CREATE
build.sbt, project/build.properties, project/plugins.sbt, project/Versions.scala, .scalafmt.conf, .gitignore (target/, .bsp/, .metals/, .idea/, .env*, *.pem, .terraform/, *.tfstate*, datasets/raw/, .claude/.last-test-run, local experiment outputs). Empty src/main/scala and src/test/scala roots with .gitkeep for every module, tests/{contracts,integration,end-to-end,adversarial}, and the top-level dirs in docs/agents.md (datasets/, prompts/, infra/terraform/, scripts/, load-tests/, experiments/, reports/).

VERIFY from clean: sbt clean; sbt Test/compile; sbt check; sbt packageJobs. Paste real output tails. Show the .claude/.last-test-run timestamp before and after check.

REPORT using the standard contract (STATUS ... NEXT GATE), plus: full version ledger with sources, module dependency graph, env-var surface, packaging choices with jar-content evidence, evictions, and Scala 3 / Spark / Hadoop compatibility risks.
