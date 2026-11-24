package scholarchain.storage

import scholarchain.storage.aws.NeptuneBulkLoader
import scholarchain.storage.common.*
import scholarchain.storage.graph.*
import scholarchain.storage.loader.*

/** The bounded Neptune bulk-load step of the loader entry point, against a fake `GraphLoader`. */
class NeptuneLoadStepSpec extends StorageSuite:
  private val run = Fixture.runId
  private val hex = Fixture.runHex
  private val RoleVar = "NEPTUNE_LOADER_ROLE_ARN"
  private val role = "arn:aws:iam::123456789012:role/test-neptune-loader"
  private val settingsMap: Map[String, String] = TestEnv.vars ++ Map(
    RoleVar -> role,
    NeptuneLoadConfig.TimeoutVar -> "60",
    NeptuneLoadConfig.PollVar -> "5"
  )

  /** Scripted loader: records every call; `states` is returned by successive status calls (the
    * last one repeats).
    */
  final private class FakeLoader(
      states: Vector[Result[GraphLoadState]],
      start: Result[GraphLoadJob] = Right(GraphLoadJob("load-1", Fixture.runId))
  ) extends GraphLoader:
    var starts = Vector.empty[GraphLoadRequest]
    var statuses = 0
    def startLoad(r: GraphLoadRequest): Result[GraphLoadJob] =
      starts :+= r
      start
    def status(j: GraphLoadJob): Result[GraphLoadState] =
      val i = math.min(statuses, states.size - 1)
      statuses += 1
      states(i)

  private def completed = new FakeLoader(Vector(Right(GraphLoadState.Completed)))

  /** A clock that only moves when the step sleeps. */
  final private class Clock extends Sleeper:
    var now = 1000L
    var sleeps = Vector.empty[Long]
    def sleep(ms: Long): Unit =
      sleeps :+= ms
      now += ms

  private class Ctx(loader: FakeLoader):
    val clock = new Clock
    var built = 0
    var closed = 0
    val lines = Vector.newBuilder[String]
    def go(args: Seq[String], vars: Map[String, String] = settingsMap): Int =
      LoadMain.execute(
        args.toVector,
        vars,
        lines += _,
        neptuneLoader = (_, _) =>
          built += 1
          (loader, () => closed += 1)
        ,
        sleeper = clock,
        nowMillis = () => clock.now
      )
    def text: String = lines.result().mkString("\n")

  private def neptuneArgs(extra: String*): Vector[String] =
    Vector("--run-id", run, "--targets", "neptune") ++ extra

  private val expectedSource = s"s3://test-rag/neptune-export/run_id=$hex/csv/"

  test("success: starts one load on neptune-export/run_id=<hex>/csv/ and polls until completed") {
    val loader = new FakeLoader(
      Vector(
        Right(GraphLoadState.Queued),
        Right(GraphLoadState.InProgress),
        Right(GraphLoadState.Completed)
      )
    )
    val c = new Ctx(loader)
    val code = c.go(neptuneArgs())
    withClue(c.text) {
      code shouldBe 0
      loader.starts.size shouldBe 1
      loader.starts.head.runId shouldBe run
      loader.starts.head.csvPrefix.key shouldBe s"run_id=$hex/csv/"
      loader.statuses shouldBe 3
      c.clock.sleeps shouldBe Vector(5000L, 5000L)
      c.text should include("NEPTUNE LOAD COMPLETED: loadId=load-1")
      c.built shouldBe 1
      c.closed shouldBe 1
    }
  }

  test("LOAD_FAILED: exit 1, the loader's reason and load ID are printed, no success claimed") {
    val loader = new FakeLoader(
      Vector(
        Right(GraphLoadState.Failed("LOAD_FAILED (failed feeds: s3://b/csv/x.csv LOAD_FAILED)"))
      )
    )
    val c = new Ctx(loader)
    val code = c.go(neptuneArgs())
    withClue(c.text) {
      code shouldBe 1
      c.text should include("NEPTUNE LOAD FAILED")
      c.text should include("loadId=load-1")
      c.text should include("failed feeds: s3://b/csv/x.csv")
      c.text should not include "COMPLETED"
      c.closed shouldBe 1
    }
  }

  test("a cancelled or otherwise non-complete terminal state is a failure") {
    val loader = new FakeLoader(Vector(Right(GraphLoadState.Failed("LOAD_CANCELLED_BY_USER"))))
    val c = new Ctx(loader)
    c.go(neptuneArgs()) shouldBe 1
    c.text should include("LOAD_CANCELLED_BY_USER")
  }

  test("timeout: exit 1, clear message, load not claimed, and no polling past the limit") {
    val loader = new FakeLoader(Vector(Right(GraphLoadState.InProgress)))
    val c = new Ctx(loader)
    val code = c.go(neptuneArgs())
    withClue(c.text) {
      code shouldBe 1
      c.text should include("NEPTUNE LOAD FAILED")
      c.text should include("exceeded its time limit of 60000 ms")
      c.text should include("not cancelled")
      c.text should not include "COMPLETED"
      // 60 s limit with a 5 s poll: 13 polls (t=0..60), 12 sleeps
      loader.statuses shouldBe 13
      c.clock.sleeps.size shouldBe 12
    }
  }

  test("timeout and poll interval can be given as flags and win over the environment") {
    val loader = new FakeLoader(Vector(Right(GraphLoadState.InProgress)))
    val c = new Ctx(loader)
    c.go(neptuneArgs("--neptune-timeout-seconds", "2", "--neptune-poll-seconds", "1")) shouldBe 1
    c.clock.sleeps shouldBe Vector(1000L, 1000L)
  }

  test("a failed status call fails the step and says the load may still be running") {
    val loader = new FakeLoader(Vector(Left(StorageError.Transient("status", 3, "boom"))))
    val c = new Ctx(loader)
    c.go(neptuneArgs()) shouldBe 1
    c.text should include("loadId=load-1")
    c.text should include("may still be running")
  }

  test("a refused start is a failure and nothing is polled") {
    val loader = new FakeLoader(
      Vector(Right(GraphLoadState.Completed)),
      Left(StorageError.Permanent("start", "denied"))
    )
    val c = new Ctx(loader)
    c.go(neptuneArgs()) shouldBe 1
    loader.statuses shouldBe 0
  }

  test("missing NEPTUNE_LOADER_ROLE_ARN fails fast with exit 2 before any client is built") {
    val loader = completed
    val c = new Ctx(loader)
    val code = c.go(neptuneArgs(), settingsMap.removed(RoleVar))
    code shouldBe 2
    c.text should include(RoleVar)
    c.built shouldBe 0
    loader.starts shouldBe empty
  }

  test("a malformed role ARN is refused") {
    val c = new Ctx(completed)
    c.go(neptuneArgs(), settingsMap + (RoleVar -> "not-an-arn")) shouldBe 2
    c.built shouldBe 0
  }

  test("missing timeout or poll interval is refused; there is no hardcoded default") {
    val c1 = new Ctx(completed)
    c1.go(neptuneArgs(), settingsMap.removed(NeptuneLoadConfig.TimeoutVar)) shouldBe 2
    c1.text should include(NeptuneLoadConfig.TimeoutVar)
    val c2 = new Ctx(completed)
    c2.go(neptuneArgs(), settingsMap.removed(NeptuneLoadConfig.PollVar)) shouldBe 2
    c2.text should include(NeptuneLoadConfig.PollVar)
    val c3 = new Ctx(completed)
    c3.go(neptuneArgs(), settingsMap + (NeptuneLoadConfig.PollVar -> "0")) shouldBe 2
  }

  test("dry run prints the exact planned request and makes zero loader calls") {
    val loader = completed
    val c = new Ctx(loader)
    val code = c.go(neptuneArgs("--dry-run"))
    withClue(c.text) {
      code shouldBe 0
      c.built shouldBe 0
      loader.starts shouldBe empty
      loader.statuses shouldBe 0
      c.clock.sleeps shouldBe empty
      c.text should include(s"source=$expectedSource")
      c.text should include("format=opencypher")
      c.text should include("s3BucketRegion=eu-west-1")
      c.text should include(s"iamRoleArn=$role")
      c.text should include("mode=NEW")
      c.text should include("failOnError=true")
      c.text should include("userProvidedEdgeIds=true")
      c.text should include("queueRequest=true")
      c.text should include("parallelism=")
      c.text should include("no Neptune call was made")
    }
  }

  test("dry run still validates: a missing role ARN fails it") {
    val c = new Ctx(completed)
    c.go(neptuneArgs("--dry-run"), settingsMap.removed(RoleVar)) shouldBe 2
  }

  test("the plan carries the source, role and limits from configuration") {
    val a = LoadArgs.parse(Seq("--run-id", run, "--targets", "neptune")).toOption.get
    val plan = NeptuneLoadConfig.resolve(a, settingsMap).toOption.get
    plan.bulk.source shouldBe expectedSource
    plan.bulk.roleArn shouldBe role
    plan.timeoutMillis shouldBe 60000L
    plan.pollMillis shouldBe 5000L
  }

  test("a bad run ID is refused at argument parsing, before any configuration is read") {
    val c = new Ctx(completed)
    c.go(Vector("--run-id", "run:XYZ", "--targets", "neptune")) shouldBe 2
    c.go(Vector("--run-id", "run:" + hex.toUpperCase, "--targets", "neptune")) shouldBe 2
    c.built shouldBe 0
  }

  // ---- target parsing ----

  test("--targets neptune alone needs neither --spark-output nor --snapshot") {
    val a = LoadArgs.parse(Seq("--run-id", run, "--targets", "neptune")).toOption.get
    a.targets shouldBe Vector(LoadStep.NeptuneLoad)
    LoadStep.NeptuneLoad.wire shouldBe "neptune"
  }

  test("neptune combined with existing targets parses, keeps order and still needs the inputs") {
    val a = LoadArgs
      .parse(
        Seq(
          "--spark-output",
          "/x",
          "--run-id",
          run,
          "--snapshot",
          "s",
          "--targets",
          "neptune-csv,neptune"
        )
      )
      .toOption
      .get
    a.targets shouldBe Vector(LoadStep.NeptuneCsv, LoadStep.NeptuneLoad)
    LoadArgs
      .parse(Seq("--run-id", run, "--targets", "neptune-csv,neptune"))
      .left
      .toOption
      .get should include("--spark-output")
  }

  test("neptune is never a default target") {
    val a = LoadArgs
      .parse(Seq("--spark-output", "/x", "--run-id", run, "--snapshot", "s"))
      .toOption
      .get
    a.targets should not contain LoadStep.NeptuneLoad
  }

  test("timeout flags must be positive integers") {
    LoadArgs
      .parse(neptuneArgs("--neptune-timeout-seconds", "0"))
      .left
      .toOption
      .get should include("--neptune-timeout-seconds")
    LoadArgs.parse(neptuneArgs("--neptune-poll-seconds", "x")).isLeft shouldBe true
  }

  test("combined with a staging step: a failing staging step means no Neptune load is started") {
    val loader = completed
    val c = new Ctx(loader)
    val missing = tempDir("empty-spark-")
    val code = c.go(
      Vector(
        "--spark-output",
        missing.toString,
        "--run-id",
        run,
        "--snapshot",
        Fixture.Snapshot,
        "--targets",
        "neptune-csv,neptune"
      )
    )
    code should not be 0
    c.built shouldBe 0
    loader.starts shouldBe empty
    c.text should include("NOT started")
  }

  // ---- adapter failure detail ----

  test("failure detail names the failed feeds and error log entries from the status payload") {
    val payload = ujson.read(
      """{"failedFeeds":[{"fullUri":"s3://b/k/nodes.csv","status":"LOAD_FAILED"}],
        |"errors":{"errorLogs":[{"errorCode":"PARSING_ERROR","errorMessage":"bad row",
        |"fileName":"s3://b/k/nodes.csv","recordNum":12}]}}""".stripMargin
    )
    val d = NeptuneBulkLoader.describeFailure(payload)
    d should include("failed feeds: s3://b/k/nodes.csv LOAD_FAILED")
    d should include("s3://b/k/nodes.csv record 12: PARSING_ERROR bad row")
    NeptuneBulkLoader.describeFailure(ujson.Obj()) shouldBe ""
  }
