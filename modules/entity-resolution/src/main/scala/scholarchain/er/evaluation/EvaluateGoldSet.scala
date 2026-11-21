package scholarchain.er.evaluation

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.Locale
import scala.util.control.NonFatal
import scholarchain.er.adjudication.Canonical
import scholarchain.er.review.ReviewQueue
import scholarchain.er.run.CodeFingerprint

/** Gold-set evaluation of a local entity-resolution run (Phase 05 PART B).
  *
  * {{{
  * sbt "entityResolution/runMain scholarchain.er.evaluation.EvaluateGoldSet <gold-set.csv>
  *   [--run-dir <run_id=... dir>] [--review-key <key csv>] [--output-dir <dir>]
  *   [--compare-run-dir <run_id=... dir>] [--accept-grid 0.7,0.8,...] [--reject-grid 0.35,...]"
  * }}}
  *
  * The gold-set path is the required first argument; there is no default and nothing else in
  * the module knows where a gold set lives. Every input is only read. Outputs (overwritten, and
  * byte-identical for identical inputs: no wall-clock value is written) go to `--output-dir`:
  *   - `gold-eval-results.csv`: one row per gold pair (raw results)
  *   - `summary.json`: every metric, machine-readable
  *   - `summary.md`: the same metrics as tables
  *
  * Reporting only: no threshold, weight or rule is derived from the gold set, and the sensitivity
  * table is a counterfactual over recorded scores.
  */
object EvaluateGoldSet:
  val DefaultRunDir =
    "modules/entity-resolution/local-run/er-v1/run_id=7449422852604780fd2de54fcaa1ff18ebe1cf761c260d8cf4de08b368ce4e15"
  val DefaultReviewKey = "modules/entity-resolution/local-run/er-v1/review-queue-v1-key.csv"
  val DefaultOutputDir = "modules/entity-resolution/evaluation/er-v1"
  val DefaultAcceptGrid: Vector[Double] = Vector(0.70, 0.75, 0.80, 0.85, 0.90, 0.95)
  val DefaultRejectGrid: Vector[Double] = Vector(0.35, 0.45, 0.55, 0.65)
  val EvaluatorVersion = "er-gold-eval-v1"
  val ResultsFile = "gold-eval-results.csv"
  val SummaryJson = "summary.json"
  val SummaryMd = "summary.md"

  final case class Args(
      goldSet: Path,
      runDir: Path,
      reviewKey: Path,
      outputDir: Path,
      compareRunDir: Option[Path],
      acceptGrid: Vector[Double],
      rejectGrid: Vector[Double]
  )

  private def grid(s: String): Either[String, Vector[Double]] =
    val vs =
      s.split(',').toVector.map(_.trim).map(x => x.toDoubleOption.filter(d => d >= 0 && d <= 1))
    if vs.isEmpty || vs.exists(_.isEmpty) then
      Left(s"grid '$s' must be comma-separated values in [0, 1]")
    else Right(vs.flatten.distinct.sorted)

  def parseArgs(argv: Seq[String]): Either[String, Args] =
    argv.headOption match
      case None | Some("") =>
        Left(
          "usage: EvaluateGoldSet <gold-set.csv> [--run-dir d] [--review-key f] " +
            "[--output-dir d] [--compare-run-dir d] [--accept-grid list] [--reject-grid list]"
        )
      case Some(g) if g.startsWith("--") => Left("the first argument must be the gold-set CSV path")
      case Some(g) =>
        val rest = argv.tail
        val pairs = rest.grouped(2).toVector
        if rest.size % 2 != 0 || pairs.exists(p => !p.head.startsWith("--")) then
          Left("options must be --name value pairs")
        else
          val m = pairs.map(p => p(0).stripPrefix("--") -> p(1)).toMap
          val known =
            Set(
              "run-dir",
              "review-key",
              "output-dir",
              "compare-run-dir",
              "accept-grid",
              "reject-grid"
            )
          val unknown = m.keySet -- known
          if unknown.nonEmpty then
            Left(s"unknown option(s): ${unknown.toVector.sorted.mkString(", ")}")
          else
            for
              a <- m.get("accept-grid").map(grid).getOrElse(Right(DefaultAcceptGrid))
              r <- m.get("reject-grid").map(grid).getOrElse(Right(DefaultRejectGrid))
            yield Args(
              Paths.get(g),
              Paths.get(m.getOrElse("run-dir", DefaultRunDir)),
              Paths.get(m.getOrElse("review-key", DefaultReviewKey)),
              Paths.get(m.getOrElse("output-dir", DefaultOutputDir)),
              m.get("compare-run-dir").map(Paths.get(_)),
              a,
              r
            )

  def main(argv: Array[String]): Unit =
    run(argv.toSeq) match
      case Left(err) => throw new IllegalArgumentException(s"EvaluateGoldSet: $err")
      case Right(md) => println(md)

  /** Runs the evaluation and writes the outputs. Returns the Markdown summary. */
  def run(argv: Seq[String]): Either[String, String] =
    for
      args <- parseArgs(argv)
      _ <- guardOutput(args)
      goldAndSha <- GoldSet.load(args.goldSet)
      keyAndSha <- ReviewKey.load(args.reviewKey)
      run <- RunData.load(args.runDir)
      rows <- Evaluation.join(goldAndSha._1, keyAndSha._1, run)
      cmp <- args.compareRunDir match
        case None => Right(None)
        case Some(d) =>
          RunData
            .load(d)
            .flatMap(r => Evaluation.join(goldAndSha._1, keyAndSha._1, r).map(x => Some(r -> x)))
      fp <- CodeFingerprint.ofModule(classOf[GoldRow])
      out <- write(args, Report(args, goldAndSha._2, keyAndSha._2, run, rows, cmp, fp))
    yield out

  /** Refuses an output directory that would overwrite the gold set or write into a run directory. */
  private def guardOutput(a: Args): Either[String, Unit] =
    val out = a.outputDir.toAbsolutePath.normalize
    val gold = a.goldSet.toAbsolutePath.normalize
    val runs = (a.runDir +: a.compareRunDir.toVector).map(_.toAbsolutePath.normalize)
    if Vector(ResultsFile, SummaryJson, SummaryMd).exists(n => out.resolve(n) == gold) then
      Left("output would overwrite the gold set")
    else if runs.exists(r => out.startsWith(r)) then
      Left("output directory is inside a run directory")
    else Right(())

  private def write(a: Args, r: Report): Either[String, String] =
    try
      Files.createDirectories(a.outputDir)
      val md = r.markdown
      Files.writeString(a.outputDir.resolve(ResultsFile), r.resultsCsv, StandardCharsets.UTF_8)
      Files.writeString(
        a.outputDir.resolve(SummaryJson),
        r.json.render(2) + "\n",
        StandardCharsets.UTF_8
      )
      Files.writeString(a.outputDir.resolve(SummaryMd), md, StandardCharsets.UTF_8)
      Right(md)
    catch case NonFatal(e) => Left(s"cannot write evaluation output: ${e.getMessage}")

/** Everything one evaluation reports. Pure rendering over the joined rows. */
final case class Report(
    args: EvaluateGoldSet.Args,
    goldSha: String,
    keySha: String,
    run: RunData,
    rows: Vector[EvalRow],
    compare: Option[(RunData, Vector[EvalRow])],
    codeFingerprint: String
):
  import Report.*

  val evaluationId: String =
    "gold-eval:" + Canonical.hashFields(
      (Vector(
        EvaluateGoldSet.EvaluatorVersion,
        goldSha,
        keySha,
        run.inputSha256,
        compare.map(_._1.inputSha256).getOrElse("-"),
        codeFingerprint,
        args.acceptGrid.mkString(","),
        args.rejectGrid.mkString(",")
      ))*
    )

  val sensitivity: Vector[SensitivityRow] =
    Evaluation.sensitivity(rows, args.acceptGrid, args.rejectGrid, run)

  /** Whether the policy variant at the configured thresholds reproduces the recorded outcomes. */
  val sensitivityReproducesRun: Boolean =
    Evaluation.Kinds.forall { k =>
      sensitivity
        .find(s => s.variant == Evaluation.PolicyVariant && s.kind == k && s.configured)
        .forall(_.confusion == Evaluation.confusion(rows.filter(_.gold.kind == k)))
    }

  def resultsCsv: String =
    val header = Vector(
      "pair_id",
      "kind",
      "stratum_r1_key",
      "human_label",
      "included",
      "outcome_class",
      "candidate_id",
      "decision_id",
      "left_ref",
      "right_ref",
      "match_path",
      "decision",
      "score",
      "confidence",
      "reason_codes",
      "rules_fired",
      "features",
      "run_id",
      "policy_version",
      "policy_revision",
      "feature_version"
    )
    ReviewQueue.csv(
      header,
      rows.sortBy(_.gold.pairId).map { r =>
        val d = r.decision
        Vector(
          r.gold.pairId,
          r.gold.kind,
          r.key.stratum,
          r.gold.label.toString,
          r.included.toString,
          r.outcomeClass,
          d.candidateId,
          d.decisionId,
          d.leftRef,
          d.rightRef,
          d.path,
          d.decision,
          f6(d.score),
          f6(d.confidence),
          d.reasonCodes.mkString(";"),
          d.rulesFired.mkString(";"),
          featureText(d.features, all = true),
          d.runId,
          run.policyVersion,
          run.policyRevision,
          run.featureVersion
        )
      }
    )

  private def ratioJson(r: Ratio): ujson.Value =
    ujson.Obj(
      "numerator" -> r.num,
      "denominator" -> r.den,
      "value" -> r.value.map(v => ujson.Num(round6(v))).getOrElse(ujson.Null),
      "wilson95" -> r.wilson
        .map((lo, hi) => ujson.Arr(round6(lo), round6(hi)))
        .getOrElse(ujson.Null)
    )

  private def confusionJson(c: Confusion): ujson.Value =
    ujson.Obj(
      "matrix" -> ujson.Obj.from(
        Vector(GoldLabel.SAME, GoldLabel.DIFFERENT).map(g =>
          g.toString -> ujson.Obj.from(Outcomes.All.map(o => o -> ujson.Num(c.count(g, o))))
        )
      ),
      "included" -> c.included,
      "goldSame" -> c.goldSame,
      "goldDifferent" -> c.goldDifferent,
      "tp" -> c.tp,
      "fp" -> c.fp,
      "fn" -> c.fn,
      "tn" -> c.tn,
      "precision" -> ratioJson(c.precision),
      "recall" -> ratioJson(c.recall),
      "f1" -> c.f1.map(v => ujson.Num(round6(v))).getOrElse(ujson.Null),
      "specificity" -> ratioJson(c.specificity),
      "coverage" -> ratioJson(c.coverage),
      "unresolvedRate" -> ratioJson(c.unresolvedRate),
      "conflictRate" -> ratioJson(c.conflictRate),
      "decidedOnlyRecall" -> ratioJson(c.decidedRecall)
    )

  private def runJson(r: RunData): ujson.Value =
    ujson.Obj(
      "runId" -> r.runId,
      "runDir" -> r.dir.toString.replace('\\', '/'),
      "policyVersion" -> r.policyVersion,
      "policyRevision" -> r.policyRevision,
      "featureVersion" -> r.featureVersion,
      "codeVersion" -> r.codeVersion,
      "thresholds" -> ujson.Obj(
        "acceptThreshold" -> r.acceptThreshold,
        "rejectThreshold" -> r.rejectThreshold,
        "llmBandMin" -> r.llmBandMin,
        "llmBandMax" -> r.llmBandMax
      ),
      "adjudicator" -> r.adjudicator,
      "adjudicatorIsFake" -> r.adjudicatorIsFake,
      "adjudicationModelVersion" -> r.modelVersion,
      "adjudicationPromptVersion" -> r.promptVersion,
      "llmPairsInBand" -> r.llmPairsInBand,
      "llmDecided" -> r.llmDecided,
      "inputSha256" -> r.inputSha256
    )

  private def metricsJson(rs: Vector[EvalRow]): ujson.Value =
    ujson.Obj(
      "byKind" -> ujson.Obj.from(Evaluation.byKind(rs).map((k, c) => k -> confusionJson(c))),
      "byKindAndPath" -> ujson.Obj.from(Evaluation.Kinds.map { k =>
        k -> ujson.Obj.from(
          Evaluation.byKindAndPath(rs).collect { case (`k`, p, c) => p -> confusionJson(c) }
        )
      }),
      "excludedUnsureByKind" -> ujson.Obj.from(
        Evaluation.unsureByKind(rs).map((k, n) => k -> ujson.Num(n))
      )
    )

  private def errorJson(r: EvalRow): ujson.Value =
    ujson.Obj(
      "pairId" -> r.gold.pairId,
      "kind" -> r.gold.kind,
      "humanLabel" -> r.gold.label.toString,
      "humanNotes" -> r.gold.notes,
      "matchPath" -> r.decision.path,
      "decision" -> r.decision.decision,
      "score" -> round6(r.decision.score),
      "confidence" -> round6(r.decision.confidence),
      "reasonCodes" -> ujson.Arr.from(r.decision.reasonCodes),
      "rulesFired" -> ujson.Arr.from(r.decision.rulesFired),
      "features" -> ujson.Obj.from(r.decision.features.map((k, v) => k -> ujson.Num(round6(v))))
    )

  def json: ujson.Value =
    val fps = Evaluation.falsePositives(rows)
    val fns = Evaluation.falseNegatives(rows)
    ujson.Obj(
      "schemaVersion" -> EvaluateGoldSet.EvaluatorVersion,
      "evaluationId" -> evaluationId,
      "codeFingerprint" -> codeFingerprint,
      "goldSet" -> ujson.Obj(
        "path" -> args.goldSet.toString.replace('\\', '/'),
        "sha256" -> goldSha,
        "pairs" -> rows.size,
        "labels" -> ujson.Obj.from(
          GoldLabel.values.toVector.map(l => l.toString -> ujson.Num(rows.count(_.gold.label == l)))
        ),
        "labelsByKind" -> ujson.Obj.from(Evaluation.Kinds.map { k =>
          k -> ujson.Obj.from(
            GoldLabel.values.toVector.map(l =>
              l.toString -> ujson.Num(rows.count(r => r.gold.kind == k && r.gold.label == l))
            )
          )
        })
      ),
      "reviewKey" -> ujson
        .Obj("path" -> args.reviewKey.toString.replace('\\', '/'), "sha256" -> keySha),
      "run" -> runJson(run),
      "countingRules" -> ujson.Arr.from(CountingRules),
      "llmPath" -> llmNote(run),
      "metrics" -> metricsJson(rows),
      "errors" -> ujson.Obj(
        "falsePositivesTotal" -> fps.size,
        "falseNegativesTotal" -> fns.size,
        "falsePositives" -> ujson.Arr.from(fps.take(MaxErrors).map(errorJson)),
        "falseNegatives" -> ujson.Arr.from(fns.take(MaxErrors).map(errorJson))
      ),
      "thresholdSensitivity" -> ujson.Obj(
        "note" -> SensitivityNote,
        "acceptGrid" -> ujson.Arr.from(args.acceptGrid.map(ujson.Num(_))),
        "rejectGrid" -> ujson.Arr.from(args.rejectGrid.map(ujson.Num(_))),
        "policyVariantAtConfiguredThresholdsReproducesRun" -> sensitivityReproducesRun,
        "rows" -> ujson.Arr.from(sensitivity.map { s =>
          ujson.Obj(
            "variant" -> s.variant,
            "kind" -> s.kind,
            "accept" -> s.accept,
            "reject" -> s.reject,
            "configured" -> s.configured,
            "pairsRethresholded" -> s.rethresholded,
            "tp" -> s.confusion.tp,
            "fp" -> s.confusion.fp,
            "fn" -> s.confusion.fn,
            "tn" -> s.confusion.tn,
            "precision" -> ratioJson(s.confusion.precision),
            "recall" -> ratioJson(s.confusion.recall),
            "unresolvedRate" -> ratioJson(s.confusion.unresolvedRate)
          )
        })
      ),
      "comparison" -> compare
        .map((r, rs) => ujson.Obj("run" -> runJson(r), "metrics" -> metricsJson(rs)))
        .getOrElse(ujson.Null)
    )

  // ---------------------------------------------------------------- Markdown

  private def rate(r: Ratio): String =
    r.value match
      case None => s"n/a (0/0)"
      case Some(v) =>
        val ci = r.wilson.map((lo, hi) => s" [${f3(lo)}, ${f3(hi)}]").getOrElse("")
        s"${f4(v)} (${r.num}/${r.den})$ci"

  private def matrixMd(c: Confusion): Vector[String] =
    Vector(
      "| gold \\ system | ACCEPTED | REJECTED | UNRESOLVED | CONFLICT | total |",
      "|---|---:|---:|---:|---:|---:|"
    ) ++ Vector(GoldLabel.SAME, GoldLabel.DIFFERENT).map { g =>
      val cs = Outcomes.All.map(c.count(g, _))
      s"| $g | ${cs.mkString(" | ")} | ${cs.sum} |"
    }

  private def kindBlock(k: String, c: Confusion, unsure: Int): Vector[String] =
    Vector(s"### ${k.capitalize} resolution", "") ++ matrixMd(c) ++ Vector(
      "",
      s"- Included pairs: ${c.included} (SAME ${c.goldSame}, DIFFERENT ${c.goldDifferent}); UNSURE excluded: $unsure",
      s"- TP ${c.tp}, FP ${c.fp}, FN ${c.fn}, TN ${c.tn}",
      s"- Precision: ${rate(c.precision)}",
      s"- Recall: ${rate(c.recall)}",
      s"- F1: ${c.f1.map(f4).getOrElse("n/a")}",
      s"- Specificity (DIFFERENT kept apart): ${rate(c.specificity)}",
      s"- Coverage (ACCEPTED or REJECTED): ${rate(c.coverage)}",
      s"- Unresolved rate: ${rate(c.unresolvedRate)}",
      s"- Conflict rate: ${rate(c.conflictRate)}",
      s"- Decided-only recall: ${rate(c.decidedRecall)}",
      ""
    )

  private def pathTable(rs: Vector[EvalRow], r: RunData): Vector[String] =
    Vector(
      "| kind | match path | pairs | SAME | DIFF | TP | FP | FN | TN | precision | recall | unresolved | conflict |",
      "|---|---|---:|---:|---:|---:|---:|---:|---:|---|---|---:|---:|"
    ) ++ Evaluation.byKindAndPath(rs).map { (k, p, c) =>
      val label = if p == "LLM_ADJUDICATED" && r.adjudicatorIsFake then
        s"$p (fake adjudicator only; not model results)"
      else p
      def short(x: Ratio) = x.value.map(v => s"${f4(v)} (${x.num}/${x.den})").getOrElse("n/a")
      s"| $k | $label | ${c.included} | ${c.goldSame} | ${c.goldDifferent} | ${c.tp} | ${c.fp} | ${c.fn} | ${c.tn} | " +
        s"${short(c.precision)} | ${short(c.recall)} | ${c.count(GoldLabel.SAME, Outcomes.Unresolved) + c
            .count(GoldLabel.DIFFERENT, Outcomes.Unresolved)} | ${c.count(GoldLabel.SAME, Outcomes.Conflict) + c
            .count(GoldLabel.DIFFERENT, Outcomes.Conflict)} |"
    }

  private def errorMd(r: EvalRow): String =
    val rules = r.decision.rulesFired.filterNot(x =>
      x.startsWith("POLICY_REVISION:") || x.startsWith("FEATURE_VERSION:") || x.startsWith(
        "HADOOP_FEATURE_VERSION:"
      )
    )
    val notes = if r.gold.notes.isBlank then "" else s" Human note: \"${r.gold.notes}\"."
    s"- `${r.gold.pairId}` (${r.gold.kind}, gold ${r.gold.label}): ${r.decision.decision} via ${r.decision.path}, " +
      s"score ${f4(r.decision.score)}, reasons ${r.decision.reasonCodes.mkString(";")}, rules ${rules.mkString(";")}.$notes " +
      s"Features: ${featureText(r.decision.features, all = false)}"

  def markdown: String =
    val fps = Evaluation.falsePositives(rows)
    val fns = Evaluation.falseNegatives(rows)
    val unsure = Evaluation.unsureByKind(rows).toMap
    val labels =
      GoldLabel.values.toVector.map(l => s"$l ${rows.count(_.gold.label == l)}").mkString(", ")
    val lines = Vector.newBuilder[String]
    lines ++= Vector(
      "# Gold-set evaluation: entity resolution er-v1",
      "",
      "Generated by `scholarchain.er.evaluation.EvaluateGoldSet`. Do not edit by hand; re-run the evaluator.",
      "",
      "## Inputs and versions",
      "",
      s"- Evaluation ID: `$evaluationId` (evaluator `${EvaluateGoldSet.EvaluatorVersion}`)",
      s"- Code fingerprint: `$codeFingerprint`",
      s"- Gold set: `${args.goldSet.toString.replace('\\', '/')}` (`$goldSha`), ${rows.size} pairs: $labels",
      s"- Review key (pair_id to refs only): `${args.reviewKey.toString.replace('\\', '/')}` (`$keySha`)",
      s"- Scored run: `${run.runId}`",
      s"- Policy version `${run.policyVersion}`, revision `${run.policyRevision}`, features `${run.featureVersion}`",
      s"- Code version of the run: `${run.codeVersion}`",
      s"- Thresholds: accept ${run.acceptThreshold}, reject ${run.rejectThreshold}, LLM band [${run.llmBandMin}, ${run.llmBandMax})",
      s"- Adjudicator: `${run.adjudicator}`, model version `${run.modelVersion}`, prompt version `${run.promptVersion}`",
      s"- ${llmNote(run)}",
      "",
      "## Counting rules",
      ""
    )
    lines ++= CountingRules.map("- " + _)
    lines ++= Vector(
      "",
      "Rates show value (numerator/denominator) [Wilson 95% interval].",
      "",
      "## Limitation: class imbalance",
      "",
      s"- Negatives (DIFFERENT) are few: researcher ${Evaluation.byKind(rows).toMap.apply("researcher").goldDifferent}, " +
        s"work ${Evaluation.byKind(rows).toMap.apply("work").goldDifferent}. Precision is bounded by very few " +
        "possible false positives, so its interval is wide and a single error moves it a lot. " +
        "Treat precision as weak evidence; read the counts, not only the rate.",
      s"- The gold set is the ${rows.size}-pair review queue (the work order asked for 200), drawn from the r1 review queue strata, " +
        "not a random sample of all candidate pairs. Rates do not generalize to the full population.",
      "",
      "## Metrics by kind",
      ""
    )
    Evaluation.byKind(rows).foreach((k, c) => lines ++= kindBlock(k, c, unsure(k)))
    lines ++= Vector("## Metrics by match path", "")
    lines ++= pathTable(rows, run)
    lines ++= Vector(
      "",
      s"## Error analysis (up to $MaxErrors each)",
      "",
      s"False positives: ${fps.size} total.",
      ""
    )
    lines ++= (if fps.isEmpty then Vector("- none") else fps.take(MaxErrors).map(errorMd))
    lines ++= Vector("", s"False negatives: ${fns.size} total.", "")
    lines ++= (if fns.isEmpty then Vector("- none") else fns.take(MaxErrors).map(errorMd))
    lines ++= Vector(
      "",
      "## Threshold sensitivity (report only)",
      "",
      SensitivityNote,
      "",
      s"Policy variant at the configured thresholds reproduces the recorded run outcomes: $sensitivityReproducesRun.",
      "",
      "| variant | kind | accept | reject | configured | pairs re-thresholded | TP | FP | FN | TN | precision | recall | unresolved rate |",
      "|---|---|---:|---:|---|---:|---:|---:|---:|---:|---|---|---|"
    )
    lines ++= sensitivity.map { s =>
      val c = s.confusion
      def short(x: Ratio) = x.value.map(v => s"${f4(v)} (${x.num}/${x.den})").getOrElse("n/a")
      s"| ${s.variant} | ${s.kind} | ${f2(s.accept)} | ${f2(s.reject)} | ${
          if s.configured then "yes" else ""
        } | " +
        s"${s.rethresholded} | ${c.tp} | ${c.fp} | ${c.fn} | ${c.tn} | ${short(c.precision)} | ${short(c.recall)} | ${short(c.unresolvedRate)} |"
    }
    compare.foreach { (r, rs) =>
      lines ++= Vector(
        "",
        "## Comparison run (for reference only, not the scored run)",
        "",
        s"- Scored run `${run.runId}`: revision `${run.policyRevision}`, features `${run.featureVersion}`",
        s"- Comparison run `${r.runId}`: revision `${r.policyRevision}` (as recorded in its summary and " +
          s"manifest), features `${r.featureVersion}`, code `${r.codeVersion}`",
        "- Same gold labels and same pair set; decisions taken from this run's own output.",
        "",
        "| kind | run | TP | FP | FN | TN | precision | recall | F1 | coverage | unresolved rate | conflict rate |",
        "|---|---|---:|---:|---:|---:|---|---|---|---|---|---|"
      )
      for
        (label, data) <- Vector(
          s"scored: revision ${run.policyRevision}, ${run.featureVersion}" -> rows,
          s"comparison: revision ${r.policyRevision}, ${r.featureVersion}" -> rs
        )
        (k, c) <- Evaluation.byKind(data)
      do
        def short(x: Ratio) = x.value.map(v => s"${f4(v)} (${x.num}/${x.den})").getOrElse("n/a")
        lines += s"| $k | $label | ${c.tp} | ${c.fp} | ${c.fn} | ${c.tn} | ${short(c.precision)} | ${short(c.recall)} | " +
          s"${c.f1.map(f4).getOrElse("n/a")} | ${short(c.coverage)} | ${short(c.unresolvedRate)} | ${short(c.conflictRate)} |"
    }
    lines += ""
    lines.result().mkString("\n")

object Report:
  val MaxErrors = 10

  val CountingRules: Vector[String] = Vector(
    "Gold SAME is a positive, DIFFERENT a negative; UNSURE is excluded from every rate and counted separately.",
    "Only ACCEPTED is a predicted match (merge). REJECTED, UNRESOLVED and CONFLICT are all 'not merged'.",
    "TP = SAME and ACCEPTED; FP = DIFFERENT and ACCEPTED; FN = SAME and not ACCEPTED (UNRESOLVED and CONFLICT on a SAME pair cost recall); TN = DIFFERENT and not ACCEPTED.",
    "CONFLICT is never merged, so it is never a TP or FP.",
    "Coverage = (ACCEPTED + REJECTED) / included pairs. Unresolved rate = UNRESOLVED / included. Conflict rate = CONFLICT / included.",
    "Decided-only recall = TP / (SAME pairs that were ACCEPTED or REJECTED)."
  )

  val SensitivityNote: String =
    "Counterfactual over the run's recorded weighted scores; reporting only, the er-v1 thresholds " +
      "are not changed. `policy-score-path` re-thresholds only decisions the thresholds governed " +
      "(PROBABILISTIC or LLM_ADJUDICATED path decided by score; exact-ID, bibliographic, " +
      "name-gate and generic-title decisions keep their recorded outcome). `score-only` decides " +
      "every non-CONFLICT pair by score alone, ignoring all deterministic rules (CONFLICT stays " +
      "CONFLICT). In both, a score between reject and accept is UNRESOLVED: no adjudicator is run."

  def llmNote(r: RunData): String =
    if r.adjudicatorIsFake then
      s"LLM-adjudicated path: the run used only the deterministic FAKE adjudicator " +
        s"(`${r.modelVersion}`), never a model. Pairs in the LLM band: ${r.llmPairsInBand}; " +
        s"decided by the adjudicator: ${r.llmDecided}. Every LLM-path figure is fake-adjudicator " +
        "output or not applicable, never a model result."
    else
      s"LLM-adjudicated path: adjudicator `${r.adjudicator}`, model `${r.modelVersion}`. " +
        s"Pairs in band: ${r.llmPairsInBand}; decided: ${r.llmDecided}."

  def round6(d: Double): Double =
    BigDecimal(d).setScale(6, BigDecimal.RoundingMode.HALF_EVEN).toDouble
  def f2(d: Double): String = String.format(Locale.ROOT, "%.2f", d)
  def f3(d: Double): String = String.format(Locale.ROOT, "%.3f", d)
  def f4(d: Double): String = String.format(Locale.ROOT, "%.4f", d)
  def f6(d: Double): String = String.format(Locale.ROOT, "%.6f", d)

  def featureText(fs: Vector[(String, Double)], all: Boolean): String =
    fs.filter((k, _) => all || !k.startsWith("hadoop."))
      .map((k, v) => s"$k=${f4(v)}")
      .mkString("; ")
