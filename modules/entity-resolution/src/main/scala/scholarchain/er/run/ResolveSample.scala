package scholarchain.er.run

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import scala.util.Try
import scholarchain.core.ids.StableIds
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions
import scholarchain.core.validation.RecordContract.given
import scholarchain.er.adjudication.*
import scholarchain.er.io.*
import scholarchain.er.model.MatchPath
import scholarchain.er.policy.*
import scholarchain.er.review.*

/** Local bounded entity-resolution run over Phase 4 output.
  *
  * {{{
  * SCHOLARCHAIN_ENV=dev SCHOLARCHAIN_ER_POLICY_VERSION=er-v1 SCHOLARCHAIN_ER_ACCEPT_THRESHOLD=…
  * SCHOLARCHAIN_ER_REJECT_THRESHOLD=… SCHOLARCHAIN_ER_LLM_BAND_MIN=… SCHOLARCHAIN_ER_LLM_BAND_MAX=…
  * sbt "entityResolution/runMain scholarchain.er.run.ResolveSample
  *   --hadoop-root <phase4 output root> [--hadoop-run-id <hex>]
  *   --output-root <dir> --adjudicator off|fake|bedrock --decided-at <ISO instant>
  *   --er-policy-properties modules/entity-resolution/config/er-v1-thresholds.properties
  *   [--review-queue <csv> --review-key <csv> --queue-likely-match <n>
  *    --queue-likely-non-match <n> --queue-borderline <n>]"
  * }}}
  *
  * Thresholds and the policy version come only from configuration (core `ConfigLoader`,
  * section `entityResolution`). The three gating cutoffs come only from the properties file named
  * by the required `--er-policy-properties` argument ([[DecisionCutoffs.load]]); there is no
  * default path and no fallback value. `--adjudicator fake` uses [[FakeAdjudicator]] (not a model).
  * `--adjudicator bedrock` is refused: configuration has no adjudication model-ID key yet.
  *
  * `--decided-at` is required: it is the `decidedAt` of every decision, and a wall-clock default
  * would make the output differ between two runs of the same inputs (finding F8).
  *
  * The run ID covers the Phase 4 input checksums, the thresholds and cutoffs, the adjudicator
  * mode, the policy version and revision, the SHA-256 of the policy, generic-title and prompt
  * resources, and a fingerprint of this module's compiled classes and resources, the evaluator
  * package excluded ([[CodeFingerprint]]), so any change to policy content or decision code gives
  * a new run directory (finding F9). Outputs are write-once: if a file to be written already
  * exists with different bytes, the run fails before writing anything; identical bytes are left
  * as they are.
  *
  * Outputs under `<output-root>/run_id=<hex>/`: `decisions/{accepted,rejected,unresolved,
  * conflict}.jsonl` (MatchDecision, `match-decision-v1`), `decision-index.csv`,
  * `run-manifest.json`, `summary.json`; the adjudication cache is
  * `<output-root>/adjudication-cache.jsonl`. Phase 4 inputs are only read.
  */
object ResolveSample:
  val PipelineStage = "entity-resolution"
  val CodeVersion = "scholarchain-entity-resolution-0.1.0-phase05-r2"

  final case class Args(
      hadoopRoot: Path,
      hadoopRunId: Option[String],
      outputRoot: Path,
      adjudicator: String,
      decidedAt: String,
      policyProperties: Path,
      reviewQueue: Option[Path],
      reviewKey: Option[Path],
      quotas: Option[ReviewQueue.Quotas]
  )

  def parseArgs(args: Seq[String]): Either[String, Args] =
    val pairs = args.grouped(2).toVector
    if args.size % 2 != 0 || pairs.exists(p => !p.head.startsWith("--")) then
      Left("arguments must be --name value pairs")
    else
      val m = pairs.map(p => p(0).stripPrefix("--") -> p(1)).toMap
      val known = Set(
        "hadoop-root",
        "hadoop-run-id",
        "output-root",
        "adjudicator",
        "decided-at",
        "er-policy-properties",
        "review-queue",
        "review-key",
        "queue-likely-match",
        "queue-likely-non-match",
        "queue-borderline"
      )
      val unknown = m.keySet -- known
      def int(k: String) = m.get(k).flatMap(v => Try(v.toInt).toOption).filter(_ >= 0)
      val quotaKeys = Vector("queue-likely-match", "queue-likely-non-match", "queue-borderline")
      if unknown.nonEmpty then Left(s"unknown arguments: ${unknown.toVector.sorted.mkString(", ")}")
      else if !m.contains("hadoop-root") || !m.contains("output-root") then
        Left("--hadoop-root and --output-root are required")
      else if !m.get("adjudicator").exists(Set("off", "fake", "bedrock")) then
        Left("--adjudicator must be off, fake or bedrock")
      else if !m.contains("decided-at") then
        Left("--decided-at <ISO-8601 instant> is required (decisions must be reproducible)")
      else if Try(Instant.parse(m("decided-at"))).isFailure then
        Left("--decided-at must be an ISO-8601 instant")
      else if !m.get("er-policy-properties").exists(_.trim.nonEmpty) then
        Left(
          "--er-policy-properties <path> is required (the er policy properties file holding " +
            DecisionCutoffs.Keys.mkString(", ") + "; there is no default)"
        )
      else if m.contains("review-queue") != m.contains("review-key") then
        Left("--review-queue and --review-key go together")
      else if m.contains("review-queue") && quotaKeys.exists(k => int(k).isEmpty) then
        Left(s"--review-queue needs ${quotaKeys.map("--" + _).mkString(", ")} (integers >= 0)")
      else
        Right(
          Args(
            Paths.get(m("hadoop-root")),
            m.get("hadoop-run-id"),
            Paths.get(m("output-root")),
            m("adjudicator"),
            m("decided-at"),
            Paths.get(m("er-policy-properties")),
            m.get("review-queue").map(Paths.get(_)),
            m.get("review-key").map(Paths.get(_)),
            if m.contains("review-queue") then
              Some(
                ReviewQueue.Quotas(
                  int("queue-likely-match").get,
                  int("queue-likely-non-match").get,
                  int("queue-borderline").get
                )
              )
            else None
          )
        )

  private def datasetOf(dir: String): SourceType = dir match
    case "dblp" => SourceType.DBLP
    case "openalex" => SourceType.OPENALEX
    case "crossref" => SourceType.CROSSREF
    case "orcid" => SourceType.ORCID
    case "semantic_scholar" => SourceType.SEMANTIC_SCHOLAR
    case _ => SourceType.SCHOLARCHAIN

  /** The run manifest. Inputs are the Phase 4 part files with their checksums; parameters are
    * the configured thresholds, the gating cutoffs with their source, and the adjudicator mode;
    * policy versions include the revision and the resource hashes; the code version carries the
    * module fingerprint. Any change to one of them gives a new run ID.
    */
  def manifest(
      files: Vector[TableFile],
      policy: Policy,
      adjudicatorMode: String,
      service: Option[AdjudicationService],
      codeFingerprint: String
  ): Either[String, RunManifest] =
    val th = policy.thresholds
    val cut = policy.cutoffs
    for
      policySha <- Policy.resourceSha256(Policy.resourcePath(policy.policyVersion))
      titlesSha <- Policy.resourceSha256(policy.parameters.genericTitlesResource)
    yield RunManifest(
      schemaVersion = SchemaVersions.RunManifest,
      pipelineStage = PipelineStage,
      codeVersion = s"$CodeVersion@$codeFingerprint",
      inputs = files.map(f =>
        RunInput(datasetOf(f.dataset), f.snapshot, f.relative, Phase4Input.checksum(f.path))
      ),
      parameters = Map(
        "entityResolution.acceptThreshold" -> th.acceptThreshold.toString,
        "entityResolution.rejectThreshold" -> th.rejectThreshold.toString,
        "entityResolution.llmBandMin" -> th.llmBandMin.toString,
        "entityResolution.llmBandMax" -> th.llmBandMax.toString,
        "entityResolution.cutoffsSourceSha256" -> cut.sourceSha256,
        "adjudicator" -> adjudicatorMode,
        "hadoopRunId" -> files.headOption.map(_.runIdHex).getOrElse("")
      ) ++ DecisionCutoffs.Keys.flatMap(k =>
        Seq(
          s"entityResolution.$k" -> cut.valueOf(k).toString,
          s"entityResolution.$k.source" -> cut.sources(k)
        )
      ),
      policyVersions = Map(
        "entityResolutionPolicyVersion" -> policy.policyVersion,
        "entityResolutionPolicyRevision" -> policy.policyRevision,
        "entityResolutionPolicySha256" -> policySha,
        "genericTitlesResource" -> policy.parameters.genericTitlesResource,
        "genericTitlesSha256" -> titlesSha,
        "erFeatureVersion" -> policy.parameters.featureVersion,
        "adjudicationResponseSchema" -> AdjudicationSchema.Version
      ) ++ service.toVector.flatMap(s =>
        Seq(
          "adjudicationPromptVersion" -> s.promptVersion,
          "adjudicationPromptSha256" -> s.promptSha256,
          "adjudicationModelVersion" -> s.modelVersion
        )
      )
    )

  def main(argv: Array[String]): Unit =
    run(argv.toSeq, sys.env) match
      case Left(err) =>
        System.err.println(s"ResolveSample: $err")
        sys.exit(1)
      case Right(summary) => println(summary)

  def run(argv: Seq[String], env: Map[String, String]): Either[String, String] =
    for
      args <- parseArgs(argv)
      policy <- Policy.load(env, args.policyProperties)
      data <- Phase4Input.load(args.hadoopRoot, args.hadoopRunId)
      cache = JsonlAdjudicationCache(args.outputRoot.resolve("adjudication-cache.jsonl"))
      service <- args.adjudicator match
        case "off" => Right(None)
        case "fake" =>
          Prompt.adjudication.map(p =>
            Some(AdjudicationService(FakeAdjudicator(), cache, p, policy.cacheVersion))
          )
        case _ =>
          Left(
            "--adjudicator bedrock is not available: the configuration contract has no key for " +
              "the adjudication model ID or an enable flag (owner: domain-engineer)"
          )
      fingerprint <- CodeFingerprint.of(classOf[Policy])
      m <- manifest(data.files, policy, args.adjudicator, service, fingerprint)
      runId <- StableIds.runId(m)
      summary <- execute(args, policy, data, service, m, runId)
    yield
      cache.flush()
      summary

  /** Writes every output, write-once: when any target exists with different bytes, nothing is
    * written and the run fails (a run ID never silently replaces earlier decisions). Targets
    * whose bytes are already identical are left untouched.
    */
  def commit(outputs: Vector[(Path, String)]): Either[String, Unit] =
    val bytes = outputs.map((p, t) => (p, t.getBytes(StandardCharsets.UTF_8)))
    val clashes = bytes.collect {
      case (p, b) if Files.exists(p) && !java.util.Arrays.equals(Files.readAllBytes(p), b) => p
    }
    if clashes.nonEmpty then
      Left(
        "refusing to overwrite existing output with different content: " +
          clashes.map(_.toString).mkString(", ") +
          " (the same run ID must reproduce the same bytes; choose a new --output-root)"
      )
    else
      bytes.foreach { (p, b) =>
        if !Files.exists(p) then
          Option(p.getParent).foreach(Files.createDirectories(_))
          Files.write(p, b)
      }
      Right(())

  private def execute(
      args: Args,
      policy: Policy,
      data: Phase4Data,
      service: Option[AdjudicationService],
      m: RunManifest,
      runId: String
  ): Either[String, String] =
    val decidedAt = args.decidedAt
    val outputs = Vector.newBuilder[(Path, String)]
    def write(path: Path, text: String): Unit = outputs += (path -> text)
    val views = ViewBuilder.views(data)
    val (pairs, stats) = ViewBuilder.pairs(data, views)
    val ctx = DecisionContext(policy, runId, decidedAt, service)
    val decided = pairs.map(p => MatchPolicy.decide(p, ctx).map(r => (p, r)))
    decided.collectFirst { case Left(e) => e } match
      case Some(err) => Left(err)
      case None =>
        val results = decided.collect { case Right(x) => x }
        val runDir = args.outputRoot.resolve("run_id=" + runId.stripPrefix("run:"))
        for o <- MatchOutcome.values do
          val lines = results
            .filter(_._2.decision.decision == o)
            .map((_, r) => WireCodec.encodeValidated(r.decision))
          lines
            .collectFirst { case Left(e) => e }
            .foreach(e => throw new IllegalStateException(s"invalid decision: $e"))
          write(
            runDir.resolve("decisions").resolve(o.toString.toLowerCase + ".jsonl"),
            lines.collect { case Right(s) => s + "\n" }.mkString
          )
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
          results.map((p, r) =>
            Vector(
              r.decision.decisionId,
              p.candidateId,
              ReviewQueue.kindLabel(p.kind),
              p.left.ref,
              p.right.ref,
              r.path.toString,
              r.decision.decision.toString,
              f"${r.score}%.6f",
              f"${r.decision.confidence}%.6f",
              r.decision.reasonCodes.mkString(";")
            )
          )
        )
        write(runDir.resolve("decision-index.csv"), index)
        StableIds
          .canonicalManifestJson(m)
          .foreach(j =>
            write(runDir.resolve("run-manifest.json"), ujson.read(j).render(indent = 2) + "\n")
          )
        val queueSummary: ujson.Value = (args.reviewQueue, args.reviewKey, args.quotas) match
          case (Some(q), Some(k), Some(quotas)) =>
            val entries = results.map((p, r) =>
              QueueEntry(ReviewQueue.pairId(p), ReviewQueue.stratum(p, r, policy.thresholds), p, r)
            )
            val chosen = ReviewQueue.select(entries, quotas)
            write(q, ReviewQueue.csv(ReviewQueue.QueueColumns, chosen.map(ReviewQueue.queueRow)))
            write(k, ReviewQueue.csv(ReviewQueue.KeyColumns, chosen.map(ReviewQueue.keyRow)))
            ujson.Obj(
              "queuePath" -> q.toString,
              "keyPath" -> k.toString,
              "requested" -> quotas.total,
              "available" -> entries.size,
              "selected" -> chosen.size,
              "shortfall" -> math.max(0, quotas.total - chosen.size),
              "selectedByStratum" -> counts(chosen.map(_.stratum.toString)),
              "selectedByKind" -> counts(chosen.map(e => ReviewQueue.kindLabel(e.pair.kind)))
            )
          case _ => ujson.Null
        val summary = ujson.Obj(
          "runId" -> runId,
          "policyVersion" -> policy.policyVersion,
          "policyRevision" -> policy.policyRevision,
          "featureVersion" -> policy.parameters.featureVersion,
          "thresholds" -> ujson.Obj(
            "acceptThreshold" -> policy.thresholds.acceptThreshold,
            "rejectThreshold" -> policy.thresholds.rejectThreshold,
            "llmBandMin" -> policy.thresholds.llmBandMin,
            "llmBandMax" -> policy.thresholds.llmBandMax
          ),
          "cutoffs" -> ujson.Obj(
            "minSurnameSimilarity" -> policy.cutoffs.minSurnameSimilarity,
            "minSharedIdTitleSimilarity" -> policy.cutoffs.minSharedIdTitleSimilarity,
            "llmMinConfidence" -> policy.cutoffs.llmMinConfidence,
            "sources" -> ujson.Obj.from(
              policy.cutoffs.sources.toSeq.sorted.map((k, v) => k -> ujson.Str(v))
            ),
            "sourceSha256" -> policy.cutoffs.sourceSha256,
            "propertiesFile" -> args.policyProperties.toString
          ),
          "adjudicator" -> args.adjudicator,
          "adjudicationModelVersion" -> service
            .map(s => ujson.Str(s.modelVersion))
            .getOrElse(ujson.Null),
          "adjudicationPromptVersion" ->
            service.map(s => ujson.Str(s.promptVersion)).getOrElse(ujson.Null),
          "adjudicatorIsFake" -> (args.adjudicator == "fake"),
          "inputs" -> ujson.Obj(
            "files" -> data.files.size,
            "works" -> data.works.size,
            "matchCandidates" -> data.candidates.size,
            "exactIdentifierOutcomes" -> data.exactOutcomes.size,
            "researcherNodes" -> data.researcherNodes.size,
            "invalidRows" -> ujson.Obj.from(
              data.invalidRows.toSeq.sorted.map((k, v) => k -> ujson.Num(v))
            )
          ),
          "pairs" -> ujson.Obj(
            "total" -> pairs.size,
            "fromMatchCandidates" -> stats.fromCandidates,
            "fromExactOutcomesOnly" -> stats.fromExactOutcomesOnly,
            "unknownRefs" -> stats.unknownRefs,
            "skippedSameWorkMentions" -> stats.skippedSameWork
          ),
          "decisionsByKindAndOutcome" -> counts(
            results.map((p, r) => s"${ReviewQueue.kindLabel(p.kind)}/${r.decision.decision}")
          ),
          "decisionsByKindAndPath" -> counts(
            results.map((p, r) => s"${ReviewQueue.kindLabel(p.kind)}/${r.path}")
          ),
          "unresolvedRateByKind" -> ujson.Obj.from(
            results.groupBy(x => ReviewQueue.kindLabel(x._1.kind)).toSeq.sortBy(_._1).map {
              (k, xs) =>
                k -> ujson.Num(
                  xs.count(_._2.decision.decision == MatchOutcome.UNRESOLVED).toDouble / xs.size
                )
            }
          ),
          "llmBand" -> ujson.Obj(
            "pairsInBand" -> results.count(_._2.decision.rulesFired.contains("SCORE_IN_LLM_BAND")),
            // Cache hits are deliberately not reported here: they depend on cache state, and
            // summary.json must be identical for a warm and a cold cache (finding F8).
            "adjudicated" -> results.count(_._2.adjudication.isDefined),
            "decidedByLlm" -> results.count((_, r) =>
              r.path == MatchPath.LLM_ADJUDICATED && r.decision.decision != MatchOutcome.UNRESOLVED
            )
          ),
          "reviewQueue" -> queueSummary,
          "outputDir" -> runDir.toString
        )
        val text = summary.render(indent = 2)
        write(runDir.resolve("summary.json"), text + "\n")
        commit(outputs.result()).map(_ => text)

  private def counts(xs: Vector[String]): ujson.Obj =
    ujson.Obj.from(xs.groupBy(identity).toSeq.sortBy(_._1).map((k, v) => k -> ujson.Num(v.size)))
