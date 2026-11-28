package scholarchain.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.zip.GZIPOutputStream
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.core.validation.RecordContract.given
import scholarchain.er.io.{Phase4Input, ViewBuilder}
import scholarchain.er.review.ReviewQueue
import scholarchain.er.run.ResolveSample

/** End-to-end run over a copy of real Phase 4 output (see phase4-fixture/PROVENANCE.txt). */
class ResolveSampleSpec extends AnyFunSuite with Matchers:

  private val fixture: Path =
    Paths.get(getClass.getClassLoader.getResource("phase4-fixture").toURI)

  private val env = Map(
    "SCHOLARCHAIN_ENV" -> "er-test",
    "SCHOLARCHAIN_LLM_PROVIDER" -> "bedrock",
    "SYNTHESIS_MODEL_ID" -> "test-synthesis-model",
    "VERIFICATION_MODEL_ID" -> "test-verification-model",
    "SCHOLARCHAIN_ER_POLICY_VERSION" -> "er-v1",
    "SCHOLARCHAIN_ER_ACCEPT_THRESHOLD" -> "0.80",
    "SCHOLARCHAIN_ER_REJECT_THRESHOLD" -> "0.45",
    "SCHOLARCHAIN_ER_LLM_BAND_MIN" -> "0.60",
    "SCHOLARCHAIN_ER_LLM_BAND_MAX" -> "0.80"
  )

  private def runInto(out: Path, extra: Seq[String] = Seq.empty, e: Map[String, String] = env) =
    ResolveSample.run(
      Seq(
        "--hadoop-root",
        fixture.toString,
        "--output-root",
        out.toString,
        "--adjudicator",
        "fake",
        "--decided-at",
        "2026-11-20T00:00:00Z",
        "--er-policy-properties",
        TestData.erPropertiesFile.toString,
        "--review-queue",
        out.resolve("queue.csv").toString,
        "--review-key",
        out.resolve("sealed/key.csv").toString,
        "--queue-likely-match",
        "50",
        "--queue-likely-non-match",
        "50",
        "--queue-borderline",
        "100"
      ) ++ extra,
      e
    )

  private def runDir(out: Path): Path =
    Using.resource(Files.list(out))(
      _.iterator().asScala.find(_.getFileName.toString.startsWith("run_id=")).get
    )

  private def summaryOf(r: Either[String, String]): ujson.Value =
    val text: String = TestData.ok(r)
    ujson.read(text)

  private def read(p: Path) = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  private def fixtureChecksums: Map[String, String] =
    Using.resource(Files.walk(fixture))(
      _.iterator().asScala
        .filter(Files.isRegularFile(_))
        .map(p => p.toString -> Phase4Input.checksum(p))
        .toMap
    )

  test("decides every pair, writes the four outcome files with contract-valid decisions") {
    val before = fixtureChecksums
    val out = Files.createTempDirectory("er-run")
    val summary = summaryOf(runInto(out))
    val dir = runDir(out)
    val decisions = MatchOutcome.values.toVector.flatMap { o =>
      val f = dir.resolve("decisions").resolve(o.toString.toLowerCase + ".jsonl")
      Files.exists(f) shouldBe true
      read(f).linesIterator
        .filter(_.nonEmpty)
        .map { l =>
          val d = WireCodec.decode[MatchDecision](l)
          d.isRight shouldBe true
          d.toOption.get.decision shouldBe o
          d.toOption.get
        }
        .toVector
    }
    decisions.size shouldBe summary("pairs")("total").num.toInt
    decisions.map(_.decisionId).distinct.size shouldBe decisions.size
    decisions.foreach { d =>
      d.sourceId.compareTo(d.targetId) should be < 0
      d.runId shouldBe summary("runId").str
      d.policyVersion shouldBe "er-v1"
      d.decidedAt shouldBe "2026-11-20T00:00:00Z"
    }
    decisions.count(_.decision == MatchOutcome.CONFLICT) should be > 0
    decisions.count(_.decision == MatchOutcome.ACCEPTED) should be > 0
    decisions.count(_.decision == MatchOutcome.REJECTED) should be > 0
    summary("adjudicatorIsFake").bool shouldBe true
    Files.exists(dir.resolve("run-manifest.json")) shouldBe true
    Files.exists(dir.resolve("decision-index.csv")) shouldBe true
    fixtureChecksums shouldBe before // Phase 4 inputs are only read
  }

  test("the work-order example: same DOI, different ORCIDs is a CONFLICT in the sample") {
    val out = Files.createTempDirectory("er-run")
    TestData.ok(runInto(out))
    val conflicts = read(runDir(out).resolve("decisions/conflict.jsonl")).linesIterator
      .filter(_.nonEmpty)
      .map(l => WireCodec.decode[MatchDecision](l).toOption.get)
      .toVector
    conflicts.exists(d =>
      d.rulesFired.contains("EXACT_ID_DISAGREE:ORCID") && d.features("sameWorkContext") == 1.0
    ) shouldBe true
  }

  test("a wide unresolved band yields UNRESOLVED decisions from the same sample") {
    val out = Files.createTempDirectory("er-run")
    val wide = env ++ Map(
      "SCHOLARCHAIN_ER_ACCEPT_THRESHOLD" -> "0.99",
      "SCHOLARCHAIN_ER_REJECT_THRESHOLD" -> "0.10",
      "SCHOLARCHAIN_ER_LLM_BAND_MIN" -> "0.10",
      "SCHOLARCHAIN_ER_LLM_BAND_MAX" -> "0.20"
    )
    val summary = summaryOf(runInto(out, e = wide))
    summary("decisionsByKindAndOutcome").obj.keySet.exists(_.endsWith("/UNRESOLVED")) shouldBe true
  }

  test("outputs are deterministic for identical inputs, configuration and decided-at") {
    val a = Files.createTempDirectory("er-run-a")
    val b = Files.createTempDirectory("er-run-b")
    TestData.ok(runInto(a))
    TestData.ok(runInto(b))
    runDir(a).getFileName shouldBe runDir(b).getFileName
    for f <- Seq(
        "decisions/accepted.jsonl",
        "decisions/conflict.jsonl",
        "decision-index.csv",
        "run-manifest.json"
      )
    do read(runDir(a).resolve(f)) shouldBe read(runDir(b).resolve(f))
    read(a.resolve("queue.csv")) shouldBe read(b.resolve("queue.csv"))
    read(a.resolve("sealed/key.csv")) shouldBe read(b.resolve("sealed/key.csv"))
  }

  test("a threshold change gives a different run ID") {
    val a = Files.createTempDirectory("er-run-a")
    val b = Files.createTempDirectory("er-run-b")
    TestData.ok(runInto(a))
    TestData.ok(runInto(b, e = env + ("SCHOLARCHAIN_ER_ACCEPT_THRESHOLD" -> "0.85")))
    runDir(a).getFileName should not be runDir(b).getFileName
  }

  test("review queue is blind: fixed columns, no score or decision, empty label and notes") {
    val out = Files.createTempDirectory("er-run")
    val summary = summaryOf(runInto(out))
    val rows = ReviewQueue.parseCsv(read(out.resolve("queue.csv")))
    rows.head shouldBe ReviewQueue.QueueColumns
    rows.head.exists(c =>
      c.contains("score") || c.contains("decision") || c.contains("stratum")
    ) shouldBe
      false
    val body = rows.tail
    body.size shouldBe summary("reviewQueue")("selected").num.toInt
    body.foreach { r =>
      r.size shouldBe ReviewQueue.QueueColumns.size
      r(18) shouldBe ""
      r(19) shouldBe ""
      Set("researcher", "work") should contain(r(1))
    }
    body.map(_.head).distinct.size shouldBe body.size
    val key = ReviewQueue.parseCsv(read(out.resolve("sealed/key.csv")))
    key.head shouldBe ReviewQueue.KeyColumns
    key.tail.map(_.head) shouldBe body.map(_.head)
    // No queue cell repeats a system-only value from the key (score, decision ID, reason codes).
    val systemValues = key.tail.flatMap(k => Seq(k(6), k(9), k(11))).filter(_.nonEmpty).toSet
    body.flatten.exists(systemValues.contains) shouldBe false
  }

  test("review queue: every available pair is used once when fewer than requested") {
    val out = Files.createTempDirectory("er-run")
    val summary = summaryOf(runInto(out))
    val q = summary("reviewQueue")
    q("requested").num.toInt shouldBe 200
    q("selected").num.toInt shouldBe q("available").num.toInt
    q("shortfall").num.toInt shouldBe (200 - q("available").num.toInt)
    q("selectedByKind").obj.keySet shouldBe Set("researcher", "work")
  }

  test("argument errors are reported, and bedrock is refused without a configuration key") {
    ResolveSample.run(Seq("--hadoop-root"), env).isLeft shouldBe true
    ResolveSample
      .run(
        Seq("--hadoop-root", fixture.toString, "--output-root", "x", "--adjudicator", "maybe"),
        env
      )
      .isLeft shouldBe true
    val bedrock = ResolveSample.run(
      Seq(
        "--hadoop-root",
        fixture.toString,
        "--output-root",
        Files.createTempDirectory("er").toString,
        "--adjudicator",
        "bedrock",
        "--decided-at",
        "2026-11-20T00:00:00Z",
        "--er-policy-properties",
        TestData.erPropertiesFile.toString
      ),
      env
    )
    bedrock.isLeft shouldBe true
    bedrock.left.toOption.get should include("domain-engineer")
  }

  test("F8: --decided-at is required (no wall-clock default)") {
    val out = Files.createTempDirectory("er-run")
    val r = ResolveSample.run(
      Seq(
        "--hadoop-root",
        fixture.toString,
        "--output-root",
        out.toString,
        "--adjudicator",
        "off"
      ),
      env
    )
    r.isLeft shouldBe true
    r.left.toOption.get should include("--decided-at")
    Using.resource(Files.list(out))(_.iterator().asScala.toVector) shouldBe empty
  }

  /** Every score-path pair falls in the LLM band, so the fake adjudicator answers them. */
  private val allBand = env ++ Map(
    "SCHOLARCHAIN_ER_ACCEPT_THRESHOLD" -> "0.999",
    "SCHOLARCHAIN_ER_REJECT_THRESHOLD" -> "0.0",
    "SCHOLARCHAIN_ER_LLM_BAND_MIN" -> "0.0",
    "SCHOLARCHAIN_ER_LLM_BAND_MAX" -> "0.999"
  )

  test("F8/F11: a warm-cache re-run reproduces the cold run byte for byte") {
    val out = Files.createTempDirectory("er-run")
    val cold = summaryOf(runInto(out, e = allBand))
    cold("llmBand")("adjudicated").num.toInt should be > 0
    val dir = runDir(out)
    def snapshot = Using.resource(Files.walk(dir))(
      _.iterator().asScala
        .filter(Files.isRegularFile(_))
        .map(p => dir.relativize(p).toString -> read(p))
        .toMap
    )
    val before = snapshot
    Files.size(out.resolve("adjudication-cache.jsonl")) should be > 0L
    // The second run answers every band pair from the cache. The write-once commit would refuse
    // the run if any output byte differed from the cold run.
    val warm = runInto(out, e = allBand)
    warm.isRight shouldBe true
    ujson.read(warm.toOption.get) shouldBe cold
    snapshot shouldBe before
    // A cold run elsewhere gives the same decisions.
    val other = Files.createTempDirectory("er-run")
    TestData.ok(runInto(other, e = allBand))
    for f <- MatchOutcome.values.map(o => s"decisions/${o.toString.toLowerCase}.jsonl") do
      read(runDir(other).resolve(f)) shouldBe before(f)
  }

  test("F9: an existing run directory is never overwritten with different content") {
    val out = Files.createTempDirectory("er-run")
    TestData.ok(runInto(out))
    val f = runDir(out).resolve("decisions/accepted.jsonl")
    Files.write(f, "tampered\n".getBytes(StandardCharsets.UTF_8))
    val again = runInto(out)
    again.isLeft shouldBe true
    again.left.toOption.get should include("refusing to overwrite")
    read(f) shouldBe "tampered\n"
  }

  test("F9: the run ID covers code, policy resources, revision and cutoffs") {
    val files = TestData.ok(Phase4Input.discover(fixture, None))
    val policy = TestData.ok(scholarchain.er.policy.Policy.load(env, TestData.erPropertiesFile))
    def runIdWith(fp: String) =
      val m = TestData.ok(ResolveSample.manifest(files, policy, "off", None, fp))
      TestData.ok(scholarchain.core.ids.StableIds.runId(m))
    runIdWith("sha256:aa") should not be runIdWith("sha256:bb")
    val m = TestData.ok(ResolveSample.manifest(files, policy, "off", None, "sha256:aa"))
    m.codeVersion should endWith("@sha256:aa")
    m.policyVersions("entityResolutionPolicyRevision") shouldBe "er-v1-r2"
    m.policyVersions("entityResolutionPolicySha256") shouldBe TestData.ok(
      scholarchain.er.policy.Policy.resourceSha256("scholarchain/er/policy/er-v1.json")
    )
    m.policyVersions.keySet should contain("genericTitlesSha256")
    m.parameters(
      "entityResolution.llmMinConfidence"
    ) shouldBe policy.cutoffs.llmMinConfidence.toString
    m.parameters("entityResolution.llmMinConfidence.source") shouldBe
      "properties-file:er-v1-thresholds.properties#llmMinConfidence"
    val fp =
      TestData.ok(scholarchain.er.run.CodeFingerprint.of(classOf[scholarchain.er.policy.Policy]))
    fp should startWith("sha256:")
    val e1 = Vector("scholarchain/er/policy/er-v1.json" -> "a".getBytes)
    scholarchain.er.run.CodeFingerprint.ofEntries(e1) should not be
      scholarchain.er.run.CodeFingerprint
        .ofEntries(Vector("scholarchain/er/policy/er-v1.json" -> "b".getBytes))
  }

  test("missing threshold configuration stops the run before any output") {
    val out = Files.createTempDirectory("er-run")
    runInto(out, e = env - "SCHOLARCHAIN_ER_LLM_BAND_MAX").isLeft shouldBe true
    Using.resource(Files.list(out))(_.iterator().asScala.toVector) shouldBe empty
  }

  // --- Reader ------------------------------------------------------------------------------

  test("reader: loads every table, plain and gzip part files, and validates rows") {
    val data = TestData.ok(Phase4Input.load(fixture, None))
    data.works.size shouldBe 69
    data.candidates.size shouldBe 91
    data.exactOutcomes.size shouldBe 20
    data.invalidRows shouldBe empty

    val root = Files.createTempDirectory("p4")
    val run = "0" * 64
    def put(rel: String, lines: Seq[String], gzip: Boolean): Unit =
      val p = root.resolve(rel)
      Files.createDirectories(p.getParent)
      val bytes = (lines.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8)
      if gzip then Using.resource(new GZIPOutputStream(Files.newOutputStream(p)))(_.write(bytes))
      else Files.write(p, bytes)
    val cands = data.candidates.take(2).map(WireCodec.encode(_))
    put(s"scholarchain/match_candidate/snapshot=s/run_id=$run/part-r-00000", cands.take(1), false)
    put(s"scholarchain/match_candidate/snapshot=s/run_id=$run/part-r-00001.gz", cands.drop(1), true)
    val small = TestData.ok(Phase4Input.load(root, None))
    small.candidates.size shouldBe 2
    small.invalidRows shouldBe empty
  }

  test("F12: a row failing its contract stops the load instead of being dropped") {
    val data = TestData.ok(Phase4Input.load(fixture, None))
    val run = "0" * 64
    def rootWith(table: String, bad: String): Path =
      val root = Files.createTempDirectory("p4")
      def put(rel: String, lines: Seq[String]): Unit =
        val p = root.resolve(rel)
        Files.createDirectories(p.getParent)
        Files.write(p, (lines.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8))
      put(
        s"scholarchain/match_candidate/snapshot=s/run_id=$run/part-r-00000",
        data.candidates.take(1).map(WireCodec.encode(_))
      )
      put(s"$table/snapshot=s/run_id=$run/part-r-00000", Seq(bad))
      root
    val badWork = Phase4Input.load(rootWith("dblp/work", "{\"not\":\"a work\"}"), None)
    badWork.isLeft shouldBe true
    badWork.left.toOption.get should include("dblp/work=1")
    // An exact-identifier CONFLICT row with a broken contract must not silently disappear.
    val conflict = data.exactOutcomes.head.copy(outcome = ExactIdOutcome.CONFLICT)
    val broken = WireCodec.encode(conflict).replace(conflict.outcomeId, "not-an-id")
    val badOutcome =
      Phase4Input.load(rootWith("scholarchain/exact_identifier_outcome", broken), None)
    badOutcome.isLeft shouldBe true
    badOutcome.left.toOption.get should include("scholarchain/exact_identifier_outcome=1")
  }

  test("reader: several Phase 4 runs need an explicit run ID") {
    val root = Files.createTempDirectory("p4")
    for r <- Seq("a" * 64, "b" * 64) do
      val p = root.resolve(s"scholarchain/match_candidate/snapshot=s/run_id=$r/part-r-00000")
      Files.createDirectories(p.getParent)
      Files.write(p, Array.emptyByteArray)
    Phase4Input.load(root, None).isLeft shouldBe true
    Phase4Input.load(root, Some("a" * 64)).isRight shouldBe true
  }

  test("views: work identifiers come from the record and its node links") {
    val data = TestData.ok(Phase4Input.load(fixture, None))
    val views = ViewBuilder.views(data)
    val dblp = views.values
      .collect { case w: scholarchain.er.model.WorkView => w }
      .find(_.sourceRecordId == "conf/dac/SinghNLS05")
      .get
    dblp.ids(scholarchain.er.ids.ErIdType.DOI) shouldBe Set("10.1145/1065579.1065662")
    dblp.ids(scholarchain.er.ids.ErIdType.DBLP_KEY) shouldBe Set("conf/dac/SinghNLS05")
    dblp.venues should contain("DAC")
    val (pairs, stats) = ViewBuilder.pairs(data, views)
    stats.unknownRefs shouldBe 0
    pairs.map(p => (p.kind, p.left.ref, p.right.ref)).distinct.size shouldBe pairs.size
    pairs.foreach(p => p.left.ref.compareTo(p.right.ref) should be < 0)
  }
