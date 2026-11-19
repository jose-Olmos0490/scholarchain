I created the build. `sbt clean`, `sbt Test/compile`, `sbt check` and `sbt packageJobs` all pass. One work-order constraint had to give way to another: the latest Scala 3.3.x LTS patch breaks the scala-library ceiling, so I pinned an earlier patch. The ratified constitution had no conflicts, so nothing went to factory-engineer.

**Scala version decision.** I pinned Scala **3.3.7**, not the newest 3.3.x (3.3.9), because the work order also says scala-library must be <= 2.13.17. From the Maven Central POMs:

| Scala | needs scala-library |
|---|---|
| 3.3.6, 3.3.7 | 2.13.16 |
| 3.3.8, 3.3.9 | 2.13.18 |

3.3.7 is the newest patch that meets the ceiling. When EMR moves to Scala 2.13.18 or later, the bump to 3.3.9 is a version-only change for dependency-updater.

**Version ledger.** Every version was resolved from Maven Central `maven-metadata.xml` and POMs. The Temurin tag came from the Docker Hub tags API. `cs` is not installed, so I did not use `cs complete-dep`.

| Item | Pinned | Notes |
|---|---|---|
| sbt | 1.13.1 | latest 1.x (2.1.0 exists, not used); in `project/build.properties` |
| sbt-assembly / sbt-native-packager / sbt-scalafmt | 2.5.0 / 1.13.0 / 2.6.2 | sbt 1.x artifacts; they load and run under sbt 1.13.1 |
| scalafmt core | 3.11.5 | in `.scalafmt.conf` |
| Scala 3 | 3.3.7 | see above |
| hadoop-client | 3.4.2 Provided | matches Spark's parent POM `hadoop.version` |
| spark-sql, spark-graphx | 4.1.1 Provided, `for3Use2_13` | Spark's parent POM confirms Scala 2.13.17, Java 17 |
| upickle | 4.4.3 | built against Scala 3.3.7 |
| scalatest | 3.2.20 Test | in every module |
| config | 1.4.10 | core |
| slf4j-api | 2.0.17 | same as Spark 4.1.1 |
| logback-classic | 1.5.38 Runtime | services only; built against slf4j 2.0.17 |
| cask | 0.11.3 | services |
| AWS SDK v2 (BOM) | 2.59.4 | all eight service artifacts exist at this version |
| Docker base | `eclipse-temurin:17.0.21_9-jre-noble` | |

- **Mirrored plugin versions:** sbt reads `build.properties` and `plugins.sbt` before it compiles `project/*.scala`, so it cannot take those versions from `Dependencies.scala`. The ledger mirrors them in a comment block, and any change has to update both places.
- **AWS BOM:** sbt 1.x cannot import a Maven BOM, so every awssdk artifact uses the BOM release version from one ledger value.
- **Project version:** I moved the project's own `0.1.0` into the ledger as `Dependencies.projectVersion`, outside the `Versions` object, so `build.sbt` has no version literals.

**Module graph** (matches CLAUDE.md, acyclic):
- core: no internal dependencies.
- hadoop, entityResolution, sparkAnalytics, storage, rag: depend on core only.
- services: depends on core, rag and storage.
- root aggregates all seven and publishes nothing.

AWS clients are placed like this:
- storage: s3, dynamodb, s3vectors, neptunedata, sqs.
- rag: bedrockruntime.
- services: secretsmanager, sfn.

**Environment variables.** The build reads only `SCHOLARCHAIN_AWS_IT`. When it is unset or anything other than `true`, ScalaTest gets `-l scholarchain.tags.AwsIntegration`. With `SCHOLARCHAIN_AWS_IT=true` the exclusion goes away. I checked both cases with `show core/Test/testOptions`.

**Test marker.**
- Before the first `check`, `.claude/.last-test-run` did not exist. After it: `2026-11-19 00:18:16`. After the final `check`: `2026-11-19 00:21:30`.
- Failure path: I made `core / Test / test` throw through a session-only `set` (no files written). `markTestRun` exited 1 and the marker kept its 00:18:16 timestamp.
- The marker is newer than every file the Stop hook checks.

**Jar contents** (`jar tf`):

| Check | hadoop jar | spark-analytics jar |
|---|---|---|
| scala-library (`scala/collection/immutable/List.class`, `library.properties`) | 1 / 1 | 0 / 0 |
| scala3-library (`scala/runtime/LazyVals.class`, `scala/util/boundary.class`) | 1 / 1 | 1 / 1 |
| `org/apache/hadoop/` entries | 0 | 0 |
| `org/apache/spark/` entries | 0 | 0 |
| `org/slf4j/` entries | 0 | 0 |
| logback / log4j entries | 0 | 0 |

- **Determinism:** the jar hashes were identical across two builds from clean (hadoop `2ba9d950…`, spark `846af6d8…`).
- **Merge strategy:** signatures, manifests and `module-info` are discarded, service files keep distinct lines, `reference.conf` is concatenated, and any other duplicate fails the build.
- **slf4j-api is left out of both jars**, though it stays a Compile dependency, because EMR supplies it. This avoids a clash with Hadoop's slf4j 1.7.36.
- **Docker:** `services/Docker/stage` produced a two-stage Dockerfile on the pinned base. It runs as `USER 10001:10001` and passes `-XX:InitialRAMPercentage=50.0` and `-XX:MaxRAMPercentage=75.0` (no `-Xmx`), and the `latest` tag is off. I did not run `dockerLocal`, which the verify list doesn't include.

**Evictions and conflicts.**
- **Fixed:** Spark brings scala-xml_2.13 and ScalaTest brings scala-xml_3, and sbt refuses two suffixes of one module. I excluded the `_2.13` copy from the Provided Spark artifacts.
- scala3-library 3.3.7 replaces 3.3.1 (from upickle-core/geny) and, in services, 3.3.4 (cask) and 3.1.3 (scalatest).
- slf4j-api 2.0.17 replaces 1.7.36 (AWS SDK, httpclient5) and 1.7.25 (Java-WebSocket). This is a known-compatible upgrade.
- In services only:
  - upickle 4.4.3 replaces 4.0.2 (cask)
  - sourcecode 0.4.2 replaces 0.3.0 and 0.4.0
  - jboss-logging 3.4.3 replaces 3.4.1 and 3.3.1
  - jboss-threads 3.5.0 replaces 2.3.6 (pulled in by undertow)
- sparkAnalytics' Compile/Test classpath ends up on scala-library 2.13.17 (from Spark), which matches EMR. The other modules use 2.13.16.

**Risks to watch.**
- `check` runs the tests twice: once for `test`, and again because `markTestRun` depends on `Test/test`. That costs time, not correctness.
- Spark tests run against scala-xml_3 instead of `_2.13`. Tests should set `spark.ui.enabled=false`.
- The Java 17 `--add-opens` flags apply only to sbt-forked hadoop and spark tests and local runs. On EMR, Spark adds them itself.
- Spark 2.13 macros (Encoders, TypeTags) are not available from Scala 3. The spark-engineer will need explicit encoders or a library for that.
- The hadoop jar carries Scala 2.13.16, which is fine unless a task classpath also loads EMR's Spark jars.
- `JavaAppPackaging` has no main class yet. The image builds but won't start until service-engineer adds a main, and no port is exposed, so that is left to configuration.
- sbt created `modules/*/target/` (disposable output). No source directories or files exist under `modules/`.

Your next step is human review of the files below; there is no other manual action. The test marker and phase evidence were not written by hand.

```text
STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Phase 2 rev 2, sbt multi-project build (docs/agent-runs/phase-02/prompt.md)
FILES CREATED: /home/olmos/scholarchain/build.sbt, /home/olmos/scholarchain/project/build.properties, /home/olmos/scholarchain/project/plugins.sbt, /home/olmos/scholarchain/project/Dependencies.scala, /home/olmos/scholarchain/.scalafmt.conf, /home/olmos/scholarchain/.gitignore
FILES MODIFIED: none
COMMANDS RUN: sbt projects; sbt fmt; sbt evicted (+ show classpaths); sbt clean; sbt Test/compile; sbt check (x2); sbt 'set core/Test/test := throw...' markTestRun (expected failure); sbt "show core/Test/testOptions" (with/without SCHOLARCHAIN_AWS_IT=true); sbt packageJobs (x2); sbt services/Docker/stage; jar tf on both assemblies
TEST RESULTS: sbt check exit 0, markTestRun touched marker 00:21:30; no test sources exist yet (0 tests); simulated failure exit 1, marker untouched
VALIDATION RESULTS: scalafmtCheckAll/scalafmtSbtCheck pass; jar-content checks pass (table above); identical jar hashes across clean rebuilds; Dockerfile non-root, pinned base, RAM percentage flags; no version literals in build.sbt
ASSUMPTIONS: Scala 3.3.7 chosen over 3.3.9 to meet the <= 2.13.17 ceiling; slf4j-api kept out of job jars because EMR supplies it; AWS clients placed by module role (above); Temurin noble image
RISKS: duplicate test run in check; scala-xml_3 standing in for _2.13 in Spark tests; no Spark encoder derivation from Scala 3; services image has no main class or exposed port yet; services-only evictions (cask/undertow)
MANUAL ACTION REQUIRED: none beyond human review of these files
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: sbt check and code-reviewer
```
