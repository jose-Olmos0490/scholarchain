// ScholarChain multi-project build.
// Owner: build-engineer. Every library version comes from project/Dependencies.scala.
// The only environment variable read by this build is SCHOLARCHAIN_AWS_IT.

import Dependencies.Versions

ThisBuild / organization := "io.scholarchain"
ThisBuild / version := Dependencies.projectVersion
ThisBuild / scalaVersion := Versions.scala3
ThisBuild / scalacOptions ++= Seq(
  "-release",
  Dependencies.javaRelease,
  "-encoding",
  "utf8",
  "-deprecation",
  "-feature",
  "-unchecked"
)
ThisBuild / javacOptions ++= Seq("--release", Dependencies.javaRelease)

// ---------------------------------------------------------------------------
// Environment input surface
// ---------------------------------------------------------------------------
// SCHOLARCHAIN_AWS_IT=true enables tests tagged scholarchain.tags.AwsIntegration.
// Any other value, or absence, excludes them.
lazy val awsIntegrationEnabled: Boolean =
  sys.env.get("SCHOLARCHAIN_AWS_IT").contains("true")

lazy val awsIntegrationTag = "scholarchain.tags.AwsIntegration"

// ---------------------------------------------------------------------------
// Shared settings
// ---------------------------------------------------------------------------
lazy val commonSettings = Seq(
  libraryDependencies ++= Dependencies.common,
  Test / testOptions ++= {
    if (awsIntegrationEnabled) Nil
    else Seq(Tests.Argument(TestFrameworks.ScalaTest, "-l", awsIntegrationTag))
  },
  publish / skip := true
)

// Spark and Hadoop on Java 17 need these module openings at test and local-run
// time (mirrors Spark's launcher JavaModuleOptions). EMR applies them on-cluster.
lazy val java17ClusterOpens = Seq(
  "-XX:+IgnoreUnrecognizedVMOptions",
  "--add-opens=java.base/java.lang=ALL-UNNAMED",
  "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
  "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
  "--add-opens=java.base/java.io=ALL-UNNAMED",
  "--add-opens=java.base/java.net=ALL-UNNAMED",
  "--add-opens=java.base/java.nio=ALL-UNNAMED",
  "--add-opens=java.base/java.util=ALL-UNNAMED",
  "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
  "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
  "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED",
  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
  "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
  "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
  "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
  "-Djdk.reflect.useDirectMethodHandle=false",
  "-Dio.netty.tryReflectionSetAccessible=true"
)

// Settings for modules whose jobs run on EMR with Provided Hadoop/Spark.
lazy val clusterJobSettings = Seq(
  Test / fork := true,
  Test / javaOptions ++= java17ClusterOpens,
  Test / parallelExecution := false,
  // Keep Provided cluster libraries on the classpath for local bounded runs.
  Compile / run := Defaults
    .runTask(Compile / fullClasspath, Compile / run / mainClass, Compile / run / runner)
    .evaluated,
  run / fork := true,
  run / javaOptions ++= java17ClusterOpens
)

// Deterministic merge strategy: discard signatures and module descriptors,
// merge service registrations and HOCON reference files, and fail on any other
// conflicting duplicate instead of silently picking one.
lazy val jobMergeStrategy: String => sbtassembly.MergeStrategy = {
  case "module-info.class" => MergeStrategy.discard
  case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
  case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
  case PathList("META-INF", "INDEX.LIST") => MergeStrategy.discard
  case PathList("META-INF", "services", _*) => MergeStrategy.filterDistinctLines
  case PathList("META-INF", f)
      if f.endsWith(".SF") || f.endsWith(".DSA") || f.endsWith(".RSA") || f.endsWith(".EC") =>
    MergeStrategy.discard
  case "reference.conf" => MergeStrategy.concat
  case _ => MergeStrategy.deduplicate
}

// Fat-jar settings for EMR jobs. Provided Hadoop/Spark never enter the jar.
// slf4j-api is supplied by the cluster, so it is left out to avoid binding
// conflicts; no logging backend is a dependency of these modules at all.
def jobAssemblySettings(bundleScala2Library: Boolean) = Seq(
  assembly / assemblyJarName := s"scholarchain-${name.value}-assembly-${version.value}.jar",
  assembly / assemblyMergeStrategy := jobMergeStrategy,
  assembly / assemblyExcludedJars := {
    val cp = (assembly / fullClasspath).value
    cp.filter { entry =>
      val n = entry.data.getName
      n.startsWith("slf4j-api-") || (!bundleScala2Library && n.startsWith("scala-library-"))
    }
  }
)

// ---------------------------------------------------------------------------
// Projects (directories and edges fixed by CLAUDE.md "Module dependency boundaries")
// ---------------------------------------------------------------------------
lazy val core = (project in file("modules/core"))
  .settings(commonSettings)
  .settings(
    name := "core",
    libraryDependencies ++= Dependencies.core
  )

lazy val hadoop = (project in file("modules/hadoop"))
  .dependsOn(core)
  .settings(commonSettings, clusterJobSettings)
  .settings(
    name := "hadoop",
    libraryDependencies ++= Dependencies.hadoopJobs
  )
  // Plain Hadoop task JVMs have no Scala runtime: bundle scala-library and scala3-library.
  .settings(jobAssemblySettings(bundleScala2Library = true))

lazy val entityResolution = (project in file("modules/entity-resolution"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "entity-resolution",
    libraryDependencies ++= Dependencies.entityResolution
  )

lazy val sparkAnalytics = (project in file("modules/spark"))
  .dependsOn(core)
  .settings(commonSettings, clusterJobSettings)
  .settings(
    name := "spark-analytics",
    libraryDependencies ++= Dependencies.sparkAnalytics
  )
  // EMR Spark supplies scala-library 2.13.17; bundle only scala3-library.
  .settings(jobAssemblySettings(bundleScala2Library = false))

lazy val storage = (project in file("modules/storage"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "storage",
    libraryDependencies ++= Dependencies.storage
  )

lazy val rag = (project in file("modules/rag"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "rag",
    libraryDependencies ++= Dependencies.rag
  )

lazy val services = (project in file("modules/services"))
  .dependsOn(core, rag, storage)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(commonSettings)
  .settings(
    name := "services",
    libraryDependencies ++= Dependencies.services,
    // Container image: pinned Temurin 17 JRE, non-root user, percentage-based heap.
    Docker / packageName := "scholarchain-services",
    dockerBaseImage := s"eclipse-temurin:${Versions.temurinJreTag}",
    dockerUpdateLatest := false,
    Docker / daemonUser := "scholarchain",
    Docker / daemonUserUid := Some("10001"),
    Docker / daemonGroup := "scholarchain",
    Docker / daemonGroupGid := Some("10001"),
    Universal / javaOptions ++= Seq(
      "-J-XX:InitialRAMPercentage=50.0",
      "-J-XX:MaxRAMPercentage=75.0",
      "-J-XX:+ExitOnOutOfMemoryError"
    )
  )

// ---------------------------------------------------------------------------
// Root: aggregates everything, publishes nothing, owns the test marker.
// ---------------------------------------------------------------------------
lazy val markTestRun =
  taskKey[Unit]("Run all tests; only if they pass, touch .claude/.last-test-run")

lazy val root = (project in file("."))
  .aggregate(core, hadoop, entityResolution, sparkAnalytics, storage, rag, services)
  .settings(
    name := "scholarchain",
    publish / skip := true,
    markTestRun := {
      // Depends on Test/test across every aggregated project. If any test
      // fails, this task fails before the marker is touched.
      (Test / test).all(ScopeFilter(inAggregates(LocalRootProject))).value
      val marker = (LocalRootProject / baseDirectory).value / ".claude" / ".last-test-run"
      IO.createDirectory(marker.getParentFile)
      IO.touch(marker, setModified = true)
      streams.value.log.info(s"markTestRun: tests passed; touched $marker")
    }
  )

addCommandAlias("fmt", "; scalafmtAll; scalafmtSbt")
addCommandAlias(
  "check",
  "; scalafmtCheckAll; scalafmtSbtCheck; Test/compile; test; markTestRun"
)
addCommandAlias("packageJobs", "; hadoop/assembly; sparkAnalytics/assembly")
addCommandAlias("dockerLocal", "; services/Docker/publishLocal")
