package scholarchain.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.er.evaluation.*
import scholarchain.er.review.ReviewQueue

/** Feature tests for the gold-set evaluator (Phase 05 PART B). All inputs are synthetic. */
class EvaluateGoldSetSpec extends AnyFunSuite with Matchers:

  private val GoldHeader =
    "pair_id,kind,source_dataset,source_record_id,target_dataset,target_id,left_name_or_title," +
      "right_name_or_title,left_ids,right_ids,left_coauthors,right_coauthors,left_affiliation," +
      "right_affiliation,left_year,right_year,left_venue,right_venue,human_label,notes"

  private def goldLine(id: String, kind: String, label: String, notes: String = ""): String =
    Vector(
      id,
      kind,
      "DBLP",
      "a",
      "OPENALEX",
      "b",
      "\"Doe, J.\"",
      "J Doe",
      "",
      "",
      "",
      "",
      "",
      "",
      "2000",
      "2000",
      "DAC",
      "DAC",
      label,
      notes
    ).mkString(",")

  private def gold(lines: String*): String = (GoldHeader +: lines).mkString("\r\n") + "\r\n"

  // ------------------------------------------------------------------ gold-set parsing

  test("gold set: SAME, DIFFERENT and UNSURE parse; quoted commas and notes survive") {
    val g = GoldSet.parse(
      gold(
        goldLine("p2", "work", "DIFFERENT", "\"Different DOI, clearly\""),
        goldLine("p1", "researcher", "SAME"),
        goldLine("p3", "researcher", "UNSURE")
      )
    )
    g.map(_.map(r => (r.pairId, r.kind, r.label, r.notes))) shouldBe Right(
      Vector(
        ("p1", "researcher", GoldLabel.SAME, ""),
        ("p2", "work", GoldLabel.DIFFERENT, "Different DOI, clearly"),
        ("p3", "researcher", GoldLabel.UNSURE, "")
      )
    )
  }

  test("gold set: any label outside SAME/DIFFERENT/UNSURE is an error, never a guess") {
    for bad <- Vector("", "same", "Same", "SAME ", "MAYBE", "YES", "0") do
      withClue(s"label '$bad': ") {
        GoldSet.parse(gold(goldLine("p1", "researcher", bad))).isLeft shouldBe true
      }
  }

  test("gold set: duplicate pair_id, unknown kind, missing column and ragged rows are errors") {
    GoldSet
      .parse(gold(goldLine("p1", "work", "SAME"), goldLine("p1", "work", "SAME")))
      .isLeft shouldBe true
    GoldSet.parse(gold(goldLine("p1", "venue", "SAME"))).isLeft shouldBe true
    GoldSet.parse("pair_id,kind\r\np1,work\r\n").isLeft shouldBe true
    GoldSet.parse(gold(goldLine("p1", "work", "SAME") + ",extra")).isLeft shouldBe true
    GoldSet.parse(GoldHeader + "\r\n").isLeft shouldBe true
  }

  // ------------------------------------------------------------------ metrics

  test("Wilson interval matches the closed form and is undefined for n = 0") {
    val (lo, hi) = Ratio(8, 10).wilson.get
    lo shouldBe 0.4901625 +- 1e-6
    hi shouldBe 0.9433178 +- 1e-6
    Ratio(0, 0).wilson shouldBe None
    Ratio(0, 0).value shouldBe None
    Ratio(5, 5).wilson.get._2 shouldBe 1.0
    Ratio(0, 5).wilson.get._1 shouldBe 0.0
  }

  test("counting: only ACCEPTED merges; UNRESOLVED/CONFLICT on SAME are FN; UNSURE excluded") {
    val c = Confusion.of(
      Vector(
        GoldLabel.SAME -> "ACCEPTED",
        GoldLabel.SAME -> "ACCEPTED",
        GoldLabel.SAME -> "UNRESOLVED",
        GoldLabel.SAME -> "CONFLICT",
        GoldLabel.SAME -> "REJECTED",
        GoldLabel.DIFFERENT -> "ACCEPTED",
        GoldLabel.DIFFERENT -> "CONFLICT",
        GoldLabel.DIFFERENT -> "REJECTED",
        GoldLabel.UNSURE -> "ACCEPTED",
        GoldLabel.UNSURE -> "REJECTED"
      )
    )
    (c.tp, c.fp, c.fn, c.tn) shouldBe (2, 1, 3, 2)
    c.included shouldBe 8
    c.precision shouldBe Ratio(2, 3)
    c.recall shouldBe Ratio(2, 5)
    c.f1.get shouldBe (2 * (2.0 / 3) * 0.4 / (2.0 / 3 + 0.4)) +- 1e-12
    c.coverage shouldBe Ratio(5, 8)
    c.unresolvedRate shouldBe Ratio(1, 8)
    c.conflictRate shouldBe Ratio(2, 8)
    c.decidedRecall shouldBe Ratio(2, 3)
    c.specificity shouldBe Ratio(2, 3)
  }

  test("F1 is undefined when precision has no denominator") {
    val c = Confusion.of(Vector(GoldLabel.SAME -> "UNRESOLVED", GoldLabel.DIFFERENT -> "REJECTED"))
    c.precision.value shouldBe None
    c.f1 shouldBe None
  }

  // ------------------------------------------------------------------ synthetic run fixture

  final private case class D(
      cand: String,
      kind: String,
      path: String,
      decision: String,
      score: Double,
      reasons: Vector[String],
      rules: Vector[String]
  )

  private val decisions = Vector(
    D(
      "c1",
      "researcher",
      "EXACT_ID",
      "ACCEPTED",
      0.97,
      Vector("EXACT_ID_MATCH"),
      Vector("PATH:EXACT_ID")
    ),
    D(
      "c2",
      "researcher",
      "PROBABILISTIC",
      "ACCEPTED",
      0.82,
      Vector("SCORE_ACCEPT"),
      Vector("SCORE_AT_OR_ABOVE_ACCEPT")
    ),
    D(
      "c3",
      "researcher",
      "PROBABILISTIC",
      "UNRESOLVED",
      0.55,
      Vector("SCORE_UNRESOLVED"),
      Vector("SCORE_IN_UNRESOLVED_BAND")
    ),
    D(
      "c4",
      "researcher",
      "DETERMINISTIC_BIBLIOGRAPHIC",
      "ACCEPTED",
      0.30,
      Vector("SAME_WORK_SAME_POSITION"),
      Vector("SAME_WORK_SAME_POSITION")
    ),
    D(
      "c5",
      "researcher",
      "EXACT_ID",
      "CONFLICT",
      0.95,
      Vector("EXACT_ID_DISAGREES_WITH_EVIDENCE"),
      Vector("SCORE_AT_OR_ABOVE_ACCEPT")
    ),
    D("c6", "work", "EXACT_ID", "ACCEPTED", 0.99, Vector("EXACT_ID_MATCH"), Vector()),
    D("c7", "work", "EXACT_ID", "REJECTED", 0.20, Vector("EXACT_ID_DISAGREE"), Vector()),
    D(
      "c8",
      "researcher",
      "PROBABILISTIC",
      "ACCEPTED",
      0.85,
      Vector("SCORE_ACCEPT"),
      Vector("SCORE_AT_OR_ABOVE_ACCEPT")
    )
  )

  // pair -> (candidate, kind, label)
  private val labels = Vector(
    ("p1", "c1", "researcher", "SAME"),
    ("p2", "c2", "researcher", "DIFFERENT"),
    ("p3", "c3", "researcher", "SAME"),
    ("p4", "c4", "researcher", "SAME"),
    ("p5", "c5", "researcher", "DIFFERENT"),
    ("p6", "c6", "work", "SAME"),
    ("p7", "c7", "work", "DIFFERENT"),
    ("p8", "c8", "researcher", "UNSURE")
  )

  private def decisionJson(d: D): String =
    ujson
      .Obj(
        "schemaVersion" -> "match-decision-v1",
        "decisionId" -> s"match:${d.cand}",
        "sourceId" -> s"l-${d.cand}",
        "targetId" -> s"r-${d.cand}",
        "features" -> ujson.Obj("score" -> d.score, "nameSimilarity" -> 1.0, "hadoop.x" -> 0.0),
        "rulesFired" -> ujson.Arr
          .from(("POLICY_REVISION:er-v1-r2" +: d.rules) :+ s"PATH:${d.path}"),
        "decision" -> d.decision,
        "confidence" -> 1.0,
        "reasonCodes" -> ujson.Arr.from(d.reasons),
        "policyVersion" -> "er-v1",
        "runId" -> "run:test"
      )
      .render()

  private def writeRun(dir: Path, ds: Vector[D] = decisions, fake: Boolean = true): Unit =
    Files.createDirectories(dir.resolve("decisions"))
    Files.writeString(
      dir.resolve("summary.json"),
      ujson
        .Obj(
          "runId" -> "run:test",
          "policyVersion" -> "er-v1",
          "policyRevision" -> "er-v1-r2",
          "featureVersion" -> "er-features-v2",
          "thresholds" -> ujson.Obj(
            "acceptThreshold" -> 0.8,
            "rejectThreshold" -> 0.45,
            "llmBandMin" -> 0.6,
            "llmBandMax" -> 0.8
          ),
          "adjudicator" -> (if fake then "fake" else "bedrock"),
          "adjudicationModelVersion" -> (if fake then "fake-deterministic-adjudicator-v1"
                                         else "m-1"),
          "adjudicationPromptVersion" -> "er-adj-prompt-v1",
          "adjudicatorIsFake" -> fake,
          "llmBand" -> ujson.Obj("pairsInBand" -> 0, "adjudicated" -> 0, "decidedByLlm" -> 0)
        )
        .render(2)
    )
    Files.writeString(dir.resolve("run-manifest.json"), """{"codeVersion":"test-code"}""")
    val index = ReviewQueue.csv(
      Vector(
        "decision_id",
        "candidate_id",
        "kind",
        "left_ref",
        "right_ref",
        "match_path",
        "decision",
        "score",
        "confidence",
        "reason_codes"
      ),
      ds.map(d =>
        Vector(
          s"match:${d.cand}",
          d.cand,
          d.kind,
          s"l-${d.cand}",
          s"r-${d.cand}",
          d.path,
          d.decision,
          f"${d.score}%.6f",
          "1.000000",
          d.reasons.mkString(";")
        )
      )
    )
    Files.writeString(dir.resolve("decision-index.csv"), index)
    for o <- Vector("ACCEPTED", "REJECTED", "UNRESOLVED", "CONFLICT") do
      Files.writeString(
        dir.resolve("decisions").resolve(s"${o.toLowerCase}.jsonl"),
        ds.filter(_.decision == o).map(decisionJson).map(_ + "\n").mkString
      )

  private def keyCsv(rows: Vector[(String, String, String, String)]): String =
    ReviewQueue.csv(
      Vector(
        "pair_id",
        "kind",
        "stratum",
        "candidate_id",
        "left_ref",
        "right_ref",
        "decision_id",
        "decision"
      ),
      // The key's own decision column is deliberately wrong: the evaluator must ignore it.
      rows.map((p, c, k, _) => Vector(p, k, "S", c, s"r-$c", s"l-$c", s"match:$c", "REJECTED"))
    )

  private def setup(): (Path, Path, Path, Path) =
    val root = Files.createTempDirectory("er-gold-eval")
    val g = root.resolve("gold.csv")
    Files.writeString(
      g,
      gold(
        labels.map((p, _, k, l) =>
          goldLine(p, k, l, if l == "DIFFERENT" then "\"note, with comma\"" else "")
        )*
      ),
      StandardCharsets.UTF_8
    )
    val key = root.resolve("key.csv")
    Files.writeString(key, keyCsv(labels))
    val run = root.resolve("run_id=test")
    writeRun(run)
    (g, key, run, root.resolve("out"))

  private def argv(g: Path, key: Path, run: Path, out: Path, extra: String*): Seq[String] =
    Seq(
      g.toString,
      "--run-dir",
      run.toString,
      "--review-key",
      key.toString,
      "--output-dir",
      out.toString
    ) ++ extra

  // ------------------------------------------------------------------ end to end

  test(
    "end to end: metrics per kind follow the counting rules; decisions come from the run, not the key"
  ) {
    val (g, key, run, out) = setup()
    val goldBefore = Files.readAllBytes(g)
    EvaluateGoldSet.run(argv(g, key, run, out)).isRight shouldBe true
    Files.readAllBytes(g) shouldBe goldBefore

    val s = ujson.read(Files.readString(out.resolve("summary.json")))
    val r = s("metrics")("byKind")("researcher")
    // p1 TP, p2 FP, p3 FN (UNRESOLVED), p4 TP, p5 TN (CONFLICT), p8 UNSURE excluded
    (r("tp").num, r("fp").num, r("fn").num, r("tn").num) shouldBe (2.0, 1.0, 1.0, 1.0)
    r("included").num shouldBe 5
    r("precision")("numerator").num shouldBe 2
    r("precision")("denominator").num shouldBe 3
    r("unresolvedRate")("numerator").num shouldBe 1
    r("conflictRate")("numerator").num shouldBe 1
    r("coverage")("numerator").num shouldBe 3
    r("matrix")("DIFFERENT")("CONFLICT").num shouldBe 1
    s("metrics")("excludedUnsureByKind")("researcher").num shouldBe 1
    s("metrics")("excludedUnsureByKind")("work").num shouldBe 0
    val w = s("metrics")("byKind")("work")
    (w("tp").num, w("fp").num, w("fn").num, w("tn").num) shouldBe (1.0, 0.0, 0.0, 1.0)

    val byPath = s("metrics")("byKindAndPath")("researcher")
    byPath("PROBABILISTIC")("fp").num shouldBe 1
    byPath("DETERMINISTIC_BIBLIOGRAPHIC")("tp").num shouldBe 1
    byPath("LLM_ADJUDICATED")("included").num shouldBe 0

    s("errors")("falsePositivesTotal").num shouldBe 1
    s("errors")("falsePositives")(0)("pairId").str shouldBe "p2"
    s("errors")("falsePositives")(0)("humanNotes").str shouldBe "note, with comma"
    s("errors")("falseNegatives")(0)("pairId").str shouldBe "p3"

    val rows = ReviewQueue.parseCsv(Files.readString(out.resolve("gold-eval-results.csv")))
    val h = rows.head
    val cls = rows.tail.map(x => x(h.indexOf("pair_id")) -> x(h.indexOf("outcome_class"))).toMap
    cls shouldBe Map(
      "p1" -> "TP",
      "p2" -> "FP",
      "p3" -> "FN",
      "p4" -> "TP",
      "p5" -> "TN",
      "p6" -> "TP",
      "p7" -> "TN",
      "p8" -> "EXCLUDED_UNSURE"
    )
  }

  test("end to end: output is byte-identical across two runs of the same inputs") {
    val (g, key, run, out) = setup()
    EvaluateGoldSet.run(argv(g, key, run, out)).isRight shouldBe true
    val first = Vector("gold-eval-results.csv", "summary.json", "summary.md").map(n =>
      Files.readAllBytes(out.resolve(n)).toVector
    )
    EvaluateGoldSet.run(argv(g, key, run, out)).isRight shouldBe true
    Vector("gold-eval-results.csv", "summary.json", "summary.md").map(n =>
      Files.readAllBytes(out.resolve(n)).toVector
    ) shouldBe first
  }

  test("LLM path is labeled as fake-adjudicator output, never as model results") {
    val (g, key, run, out) = setup()
    val md = EvaluateGoldSet.run(argv(g, key, run, out)).toOption.get
    md should include("FAKE adjudicator")
    md should include("LLM_ADJUDICATED (fake adjudicator only; not model results)")
    ujson.read(Files.readString(out.resolve("summary.json")))("llmPath").str should include(
      "never a model result"
    )
  }

  test("join: unknown pair, kind mismatch and ref mismatch are errors, not silent drops") {
    val (g, key, run, out) = setup()
    Files.writeString(key, keyCsv(labels.filterNot(_._1 == "p3")))
    EvaluateGoldSet.run(argv(g, key, run, out)).left.toOption.get should include("p3")

    Files.writeString(
      key,
      keyCsv(labels.map(t => if t._1 == "p6" then t.copy(_3 = "researcher") else t))
    )
    EvaluateGoldSet.run(argv(g, key, run, out)).isLeft shouldBe true

    Files.writeString(
      key,
      ReviewQueue.csv(
        Vector("pair_id", "kind", "stratum", "candidate_id", "left_ref", "right_ref"),
        labels.map((p, c, k, _) =>
          Vector(p, k, "S", c, s"l-$c", if p == "p1" then "other" else s"r-$c")
        )
      )
    )
    EvaluateGoldSet.run(argv(g, key, run, out)).left.toOption.get should include("refs")
  }

  test("arguments: the gold-set path is required and has no default") {
    EvaluateGoldSet.parseArgs(Seq.empty).isLeft shouldBe true
    EvaluateGoldSet.parseArgs(Seq("--run-dir", "x")).isLeft shouldBe true
    EvaluateGoldSet.parseArgs(Seq("g.csv", "--bogus", "1")).isLeft shouldBe true
    EvaluateGoldSet.parseArgs(Seq("g.csv", "--accept-grid", "0.7,abc")).isLeft shouldBe true
    EvaluateGoldSet.parseArgs(Seq("g.csv")).map(_.goldSet.toString) shouldBe Right("g.csv")
  }

  test("output: refuses to write into a run directory") {
    val (g, key, run, _) = setup()
    EvaluateGoldSet.run(argv(g, key, run, run.resolve("eval"))).isLeft shouldBe true
  }

  // ------------------------------------------------------------------ sensitivity

  test(
    "sensitivity: policy variant re-thresholds only score-governed decisions; configured row reproduces the run"
  ) {
    val (g, key, run, out) = setup()
    EvaluateGoldSet
      .run(argv(g, key, run, out, "--accept-grid", "0.5,0.8,0.9", "--reject-grid", "0.45"))
      .isRight shouldBe true
    val rows = ujson.read(Files.readString(out.resolve("summary.json")))("thresholdSensitivity")
    rows("policyVariantAtConfiguredThresholdsReproducesRun").bool shouldBe true
    def row(v: String, a: Double) =
      rows("rows").arr
        .find(x => x("variant").str == v && x("kind").str == "researcher" && x("accept").num == a)
        .get

    // accept 0.9: c2 (0.82, DIFFERENT) is no longer accepted -> FP gone; c4 keeps its deterministic ACCEPTED.
    val p9 = row("policy-score-path", 0.9)
    (p9("tp").num, p9("fp").num) shouldBe (2.0, 0.0)
    // c2, c3 and c8 are score-governed researcher rows (c8 is UNSURE: re-thresholded, not scored)
    p9("pairsRethresholded").num shouldBe 3
    // accept 0.5: c3 (0.55, SAME) becomes ACCEPTED.
    val p5 = row("policy-score-path", 0.5)
    (p5("tp").num, p5("fn").num) shouldBe (3.0, 0.0)

    // score-only at 0.9: c4 (score 0.30, SAME) is REJECTED by score, c5 stays CONFLICT.
    val s9 = row("score-only", 0.9)
    (s9("tp").num, s9("fp").num, s9("fn").num, s9("tn").num) shouldBe (1.0, 0.0, 2.0, 2.0)
  }

  test(
    "comparison run: metrics side by side, labeled scored vs comparison; missing revision is 'unrecorded'"
  ) {
    val (g, key, run, out) = setup()
    val other = run.getParent.resolve("run_id=other")
    writeRun(other, decisions.map(d => if d.cand == "c3" then d.copy(decision = "ACCEPTED") else d))
    val sp = other.resolve("summary.json")
    val js = ujson.read(Files.readString(sp))
    js.obj.remove("policyRevision")
    Files.writeString(sp, js.render())
    val md =
      EvaluateGoldSet.run(argv(g, key, run, out, "--compare-run-dir", other.toString)).toOption.get
    md should include("| researcher | scored: revision er-v1-r2, er-features-v2 | 2 | 1 | 1 | 1 |")
    md should include(
      "| researcher | comparison: revision unrecorded, er-features-v2 | 3 | 1 | 0 | 1 |"
    )
    val s = ujson.read(Files.readString(out.resolve("summary.json")))
    s("comparison")("metrics")("byKind")("researcher")("tp").num shouldBe 3
    s("metrics")("byKind")("researcher")("tp").num shouldBe 2
  }
