# Phase 11 — BLOCKED-ON build-engineer: final report (saved verbatim by human; orchestrator write was blocked because this path has no owner in docs/agents.md)

STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Wire adversarialTests to storage, rag and services; add Test-scoped HTTP client or test libraries if needed; verify the graph is acyclic; run adversarialTests/Test/compile and sbt check.
FILES CREATED: none
FILES MODIFIED: /home/olmos/scholarchain/build.sbt (one line)
EXACT DIFF:
-  .dependsOn(core, hadoop, entityResolution, sparkAnalytics)
+  .dependsOn(core, hadoop, entityResolution, sparkAnalytics, storage, rag, services)

VERSION LEDGER CHANGES: none. /home/olmos/scholarchain/project/Dependencies.scala is untouched.
- No Test-scoped HTTP client is needed. The existing services tests use the JDK `java.net.http.HttpClient`, and cask 0.11.3 is already a Compile dependency of services. adversarialTests inherits it transitively through the services dependency. I added no `requests` library, to avoid a new dependency with no demonstrated use.
- scalatest is already in `Dependencies.common`, so it is on the classpath through commonSettings.
- If test-engineer later needs a library that is not on the classpath, re-issue the request naming it.

test->test: NOT added.
- It is not justified yet. No test exists that needs the module fakes.
- Fakes such as `FakeEmbedder`, `FakeAdjudicator` and the services `TestKit` live in each module's own src/test tree.
- Both rag and storage define a `scholarchain.tags` package in their test trees. Pulling those trees onto one classpath risks duplicate classes.
- If specific fakes are needed, name them. Better still, ask the module owners to move them to a shared location.

MODULE GRAPH (acyclic, edges are `dependsOn`):
- core: no internal dependencies.
- hadoop, entityResolution, sparkAnalytics and storage each depend on core.
- rag depends on core and storage.
- services depends on core, rag and storage.
- adversarialTests (test-only, a leaf, not published, no assembly) depends on core, hadoop, entityResolution, sparkAnalytics, storage, rag and services.
- Production dependsOn lines are unchanged.
- Nothing depends on services or adversarialTests, and sbt loaded the build without a cyclic reference error.

COMMANDS RUN:
- `sbt scalafmtSbtCheck`: success.
- `sbt adversarialTests/Test/compile`: success. It compiled 9 Scala sources to tests/target/scala-3.3.7/test-classes.
- `sbt check`: success, total time 2325 s.

TEST RESULTS: sbt check ended with "All tests passed." The final per-project summaries showed 75, 66 and 363 tests succeeded, with 0 failed and 9 ignored in the 363-test project. markTestRun touched /home/olmos/scholarchain/.claude/.last-test-run. I saw only the last three summaries, because the tail cut off the earlier ones.

VALIDATION RESULTS: Format check and compile passed as part of sbt check.

ASSUMPTIONS: Services tests need only the JDK HTTP client. adversarialTests needs no new Compile-scope dependencies.

RISKS:
- adversarialTests now carries the transitive Compile classpath of storage, rag and services. That includes the AWS SDK clients, parquet/hadoop-client-runtime, cask and logback (Runtime). The logback test logging is noisy; the DEBUG Spark and Netty output in the check run is existing behavior.
- hadoop-client-api and hadoop-client-runtime (shaded, from storage) now sit beside the Provided hadoop-client and spark-sql. sbt check still passed, but classpath conflicts could appear in future Spark or Hadoop tests.
- A full sbt check now takes about 39 minutes, mostly in the forked Spark test JVM.

MANUAL ACTION REQUIRED: none. The orchestrator can re-issue the Phase 10/10b work order to test-engineer.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: sbt check and code-reviewer
