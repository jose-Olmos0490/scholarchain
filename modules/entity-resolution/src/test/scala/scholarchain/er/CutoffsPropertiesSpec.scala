package scholarchain.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scholarchain.core.config.{ConfigLoader, EntityResolutionConfig}
import scholarchain.core.ids.StableIds
import scholarchain.core.model.*
import scholarchain.er.TestData.*
import scholarchain.er.ids.ErIdType
import scholarchain.er.io.Phase4Input
import scholarchain.er.model.*
import scholarchain.er.policy.*
import scholarchain.er.run.ResolveSample

/** Feature tests for Phase 05 repair-01 step 2b (human decisions 2 and 3):
  *   - F7: `minSurnameSimilarity`, `minSharedIdTitleSimilarity` and `llmMinConfidence` are read
  *     only from the er-v1 module properties file, whose path is supplied explicitly; a missing
  *     or invalid value fails loudly; nothing falls back to a built-in or resource value.
  *   - the run manifest records each cutoff value and its source, and they are run-ID inputs.
  *   - policy version stays `er-v1`; revision `er-v1-r2` is recorded on every decision.
  */
class CutoffsPropertiesSpec extends AnyFunSuite with Matchers:

  private val realText: String =
    String(Files.readAllBytes(erPropertiesFile), StandardCharsets.UTF_8)

  /** The real file's text with one `key=value` line replaced (or removed when `value` is None).
    */
  private def edited(key: String, value: Option[String]): String =
    realText.linesIterator
      .flatMap { l =>
        if l.startsWith(s"$key=") then value.map(v => s"$key=$v") else Some(l)
      }
      .mkString("", "\n", "\n")

  private def writeTemp(text: String): Path =
    val dir = Files.createTempDirectory("er-props")
    val f = dir.resolve("er-v1-thresholds.properties")
    Files.write(f, text.getBytes(StandardCharsets.UTF_8))
    f

  private val env = Map(
    "SCHOLARCHAIN_ENV" -> "er-test",
    "SCHOLARCHAIN_LLM_PROVIDER" -> "bedrock",
    "SYNTHESIS_MODEL_ID" -> "test-synthesis-model",
    "VERIFICATION_MODEL_ID" -> "test-verification-model",
    "SCHOLARCHAIN_ER_POLICY_VERSION" -> "er-v1",
    "SCHOLARCHAIN_ER_ACCEPT_THRESHOLD" -> "0.999",
    "SCHOLARCHAIN_ER_REJECT_THRESHOLD" -> "0.01",
    "SCHOLARCHAIN_ER_LLM_BAND_MIN" -> "0.02",
    "SCHOLARCHAIN_ER_LLM_BAND_MAX" -> "0.998"
  )

  // --- read from the properties file ------------------------------------------------------------

  test("F7: the real er-v1 properties file holds the human-set cutoffs and the code reads them") {
    realText.linesIterator.toVector should contain allOf (
      "minSurnameSimilarity=0.85",
      "minSharedIdTitleSimilarity=0.5",
      "llmMinConfidence=0.7"
    )
    val c = ok(DecisionCutoffs.load(erPropertiesFile))
    c.minSurnameSimilarity shouldBe 0.85
    c.minSharedIdTitleSimilarity shouldBe 0.5
    c.llmMinConfidence shouldBe 0.7
    c.sourceName shouldBe "er-v1-thresholds.properties"
    c.sources("llmMinConfidence") shouldBe
      "properties-file:er-v1-thresholds.properties#llmMinConfidence"
    c.sourceSha256 shouldBe "sha256:" + scholarchain.er.adjudication.Canonical.sha256Hex(realText)
    val p = ok(Policy.load(env, erPropertiesFile))
    p.cutoffs shouldBe c
  }

  test("F7: an edited value in the properties file changes the cutoff and the decision") {
    val strictFile = writeTemp(edited("llmMinConfidence", Some("0.95")))
    def decideWith(file: Path) =
      val pol = ok(Policy.load(env, file))
      ok(
        MatchPolicy.decide(
          borderlineResearcherPair,
          DecisionContext(
            pol,
            runId,
            decidedAt,
            Some(service(ScriptedAdjudicator(r => Right(response("ACCEPT", Seq(r.leftRef))))))
          )
        )
      ).decision
    ok(Policy.load(env, strictFile)).cutoffs.llmMinConfidence shouldBe 0.95
    decideWith(erPropertiesFile).decision shouldBe MatchOutcome.ACCEPTED // 0.9 >= 0.7
    val strict = decideWith(strictFile)
    strict.decision shouldBe MatchOutcome.UNRESOLVED
    strict.reasonCodes.head shouldBe "LLM_LOW_CONFIDENCE"
  }

  test("F7: an edited minSurnameSimilarity in the file changes name compatibility") {
    val f = Map("surnameSimilarity" -> 0.9, "initialsCompatibility" -> 1.0)
    MatchPolicy.namesCompatible(f, ok(DecisionCutoffs.load(erPropertiesFile))) shouldBe true
    val strict = ok(DecisionCutoffs.load(writeTemp(edited("minSurnameSimilarity", Some("0.95")))))
    MatchPolicy.namesCompatible(f, strict) shouldBe false
  }

  // --- missing or invalid values fail loudly ----------------------------------------------------

  test("F7: a missing cutoff in the properties file fails the load and names the key") {
    for k <- DecisionCutoffs.Keys do
      val r = DecisionCutoffs.load(writeTemp(edited(k, None)))
      withClue(k) {
        r.isLeft shouldBe true
        r.left.toOption.get should include(s"$k is missing")
      }
      Policy.load(env, writeTemp(edited(k, None))).isLeft shouldBe true
  }

  test("F7: blank, NaN, infinite, non-numeric and out-of-range cutoffs fail the load") {
    for
      k <- DecisionCutoffs.Keys
      bad <- Seq("", " ", "NaN", "Infinity", "-Infinity", "abc", "1.5", "-0.1", "0.7.0")
    do
      val r = DecisionCutoffs.load(writeTemp(edited(k, Some(bad))))
      withClue(s"$k=<$bad>: ") {
        r.isLeft shouldBe true
        r.left.toOption.get should include(k)
      }
  }

  test("F7: a cutoff set twice fails the load (no last-value-wins)") {
    val r = DecisionCutoffs.load(writeTemp(realText + "llmMinConfidence=0.1\n"))
    r.isLeft shouldBe true
    r.left.toOption.get should include("llmMinConfidence is set 2 times")
  }

  test("F7: a missing properties file fails the policy load") {
    val missing = Files.createTempDirectory("er-props").resolve("absent.properties")
    val r = Policy.load(env, missing)
    r.isLeft shouldBe true
    r.left.toOption.get should include("not found")
  }

  // --- no hardcoded or resource fallback --------------------------------------------------------

  test("F7: no fallback: a policy cannot be built from configuration without the file") {
    val cfg = ok(ConfigLoader.loadSection(EntityResolutionConfig, env).left.map(_.toString))
    val r = Policy.fromConfig(cfg)
    r.isLeft shouldBe true
    r.left.toOption.get should include("properties file")
  }

  test("F7: the policy resource no longer carries cutoffs, and refuses them if re-added") {
    val json = String(
      ok(Policy.resourceBytes(Policy.resourcePath("er-v1"))),
      StandardCharsets.UTF_8
    )
    val keys = ujson.read(json).obj.keySet
    DecisionCutoffs.Keys.foreach(k => keys should not contain k)
    Policy.refuseCutoffKeys("er-v1.json", json) shouldBe Right(())
    val withCutoff = ujson.read(json)
    withCutoff.obj("llmMinConfidence") = 0.7
    val r = Policy.refuseCutoffKeys("er-v1.json", withCutoff.render())
    r.isLeft shouldBe true
    r.left.toOption.get should include("llmMinConfidence")
  }

  // --- run manifest and run ID ------------------------------------------------------------------

  private val fixture: Path =
    Paths.get(getClass.getClassLoader.getResource("phase4-fixture").toURI)

  test(
    "F7: the run manifest records each cutoff value and source; the file content is a run-ID input"
  ) {
    val files = ok(Phase4Input.discover(fixture, None))
    def manifestWith(file: Path) =
      ok(ResolveSample.manifest(files, ok(Policy.load(env, file)), "off", None, "sha256:aa"))
    val m = manifestWith(erPropertiesFile)
    m.parameters("entityResolution.minSurnameSimilarity") shouldBe "0.85"
    m.parameters("entityResolution.minSharedIdTitleSimilarity") shouldBe "0.5"
    m.parameters("entityResolution.llmMinConfidence") shouldBe "0.7"
    for k <- DecisionCutoffs.Keys do
      m.parameters(s"entityResolution.$k.source") shouldBe
        s"properties-file:er-v1-thresholds.properties#$k"
    m.parameters("entityResolution.cutoffsSourceSha256") shouldBe
      ok(DecisionCutoffs.load(erPropertiesFile)).sourceSha256
    val base = ok(StableIds.runId(m))
    // A changed value gives a new run ID ...
    ok(
      StableIds.runId(manifestWith(writeTemp(edited("minSharedIdTitleSimilarity", Some("0.6")))))
    ) should not be base
    // ... and so does any other edit of the file (its SHA-256 is recorded).
    ok(StableIds.runId(manifestWith(writeTemp(realText + "# comment\n")))) should not be base
    // The same bytes at another path give the same run ID.
    ok(StableIds.runId(manifestWith(writeTemp(realText)))) shouldBe base
  }

  private def runArgs(out: Path, props: Option[Path]): Seq[String] =
    Seq(
      "--hadoop-root",
      fixture.toString,
      "--output-root",
      out.toString,
      "--adjudicator",
      "fake",
      "--decided-at",
      "2026-11-20T00:00:00Z"
    ) ++ props.toSeq.flatMap(p => Seq("--er-policy-properties", p.toString))

  test("F7: ResolveSample requires --er-policy-properties and fails on an invalid file") {
    val out = Files.createTempDirectory("er-run")
    val noArg = ResolveSample.run(runArgs(out, None), env)
    noArg.isLeft shouldBe true
    noArg.left.toOption.get should include("--er-policy-properties")
    val bad =
      ResolveSample.run(runArgs(out, Some(writeTemp(edited("llmMinConfidence", Some(""))))), env)
    bad.isLeft shouldBe true
    bad.left.toOption.get should include("llmMinConfidence is blank")
    Using.resource(Files.list(out))(_.iterator().asScala.toVector) shouldBe empty
  }

  // --- revision on every decision ---------------------------------------------------------------

  private val doi = "10.1109/test.2005.1"

  private def conflictPair: PairInput =
    val authors = Vector("Feng Guo", "Ann Other")
    val w1 = work(
      oaWorkId("W7223249703"),
      SourceType.OPENALEX,
      authors = authors,
      ids = Map(ErIdType.DOI -> Set(doi))
    )
    val w2 = work(
      doiWorkId(doi),
      SourceType.CROSSREF,
      authors = authors,
      ids = Map(ErIdType.DOI -> Set(doi))
    )
    pair(
      mention(w1, 1, "Feng Guo", Map(ErIdType.ORCID -> Set("0000-0002-1825-0097"))),
      mention(w2, 1, "Feng Guo", Map(ErIdType.ORCID -> Set("0000-0001-5109-3700")))
    )

  test(
    "revision er-v1-r2 is recorded on every decision of all four outcomes; policyVersion stays er-v1"
  ) {
    val llm = Some(service(ScriptedAdjudicator(r => Right(response("ACCEPT", Seq(r.leftRef))))))
    val cases: Seq[(MatchOutcome, PairInput, DecisionContext)] = Seq(
      (MatchOutcome.ACCEPTED, borderlineResearcherPair, ctx(Thresholds(0.0, 0.0, 0.0, 0.0))),
      (MatchOutcome.ACCEPTED, borderlineResearcherPair, ctx(Thresholds(1.0, 0.0, 0.0, 1.0), llm)),
      (MatchOutcome.REJECTED, borderlineResearcherPair, ctx(Thresholds(1.0, 1.0, 1.0, 1.0))),
      (MatchOutcome.UNRESOLVED, borderlineResearcherPair, ctx(Thresholds(0.99, 0.01, 0.02, 0.03))),
      (MatchOutcome.CONFLICT, conflictPair, ctx())
    )
    for (expected, p, c) <- cases do
      val d = ok(MatchPolicy.decide(p, c)).decision
      withClue(s"$expected ${d.reasonCodes} ${d.rulesFired}: ") {
        d.decision shouldBe expected
        d.rulesFired should contain(s"POLICY_REVISION:${Policy.ImplementedRevision}")
        d.rulesFired.count(_.startsWith("POLICY_REVISION:")) shouldBe 1
        d.policyVersion shouldBe "er-v1"
        d.provenance.policyVersion shouldBe Some("er-v1")
        d.provenance.runId shouldBe c.runId
      }
    Policy.ImplementedRevision shouldBe "er-v1-r2"
  }
