package scholarchain.er.evaluation

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal
import scholarchain.er.review.ReviewQueue

/** One row of the sealed review-queue key: joins a `pair_id` to the pair's candidate and refs.
  * Only the join columns are used; the key's own (r1) decision columns are ignored.
  */
final case class KeyRow(
    pairId: String,
    kind: String,
    stratum: String,
    candidateId: String,
    leftRef: String,
    rightRef: String
)

object ReviewKey:
  private val Required =
    Vector("pair_id", "kind", "stratum", "candidate_id", "left_ref", "right_ref")

  def parse(text: String): Either[String, Vector[KeyRow]] =
    val rows = ReviewQueue.parseCsv(text)
    if rows.isEmpty then Left("review key is empty")
    else
      val header = rows.head
      val missing = Required.filterNot(header.contains)
      if missing.nonEmpty then Left(s"review key lacks column(s): ${missing.mkString(", ")}")
      else
        val ix = header.zipWithIndex.toMap
        val bad = rows.tail.indexWhere(_.size != header.size)
        if bad >= 0 then Left(s"review key line ${bad + 2} has the wrong number of fields")
        else
          val out = rows.tail.map { r =>
            def c(n: String) = r(ix(n))
            KeyRow(
              c("pair_id"),
              c("kind"),
              c("stratum"),
              c("candidate_id"),
              c("left_ref"),
              c(
                "right_ref"
              )
            )
          }
          val dups = out.groupBy(_.pairId).collect { case (k, v) if v.size > 1 => k }
          if dups.nonEmpty then Left(s"review key repeats pair_id(s): ${dups.mkString(", ")}")
          else Right(out)

  def load(path: Path): Either[String, (Vector[KeyRow], String)] =
    try
      if !Files.isRegularFile(path) then Left(s"review key not found: $path")
      else
        val bytes = Files.readAllBytes(path)
        parse(new String(bytes, StandardCharsets.UTF_8)).map(_ -> Files256.ofBytes(bytes))
    catch case NonFatal(e) => Left(s"cannot read review key $path: ${e.getMessage}")

/** One decision of a local entity-resolution run (`match-decision-v1` plus its index row). */
final case class RunDecision(
    decisionId: String,
    candidateId: String,
    kind: String,
    leftRef: String,
    rightRef: String,
    path: String,
    decision: String,
    score: Double,
    confidence: Double,
    reasonCodes: Vector[String],
    rulesFired: Vector[String],
    features: Vector[(String, Double)],
    runId: String
)

/** Metadata and decisions of one run directory `.../run_id=<hex>/` (read only). */
final case class RunData(
    dir: Path,
    runId: String,
    policyVersion: String,
    policyRevision: String,
    featureVersion: String,
    codeVersion: String,
    acceptThreshold: Double,
    rejectThreshold: Double,
    llmBandMin: Double,
    llmBandMax: Double,
    adjudicator: String,
    adjudicatorIsFake: Boolean,
    modelVersion: String,
    promptVersion: String,
    llmPairsInBand: Int,
    llmDecided: Int,
    inputSha256: String,
    byCandidate: Map[String, RunDecision]
)

object RunData:
  val Paths: Set[String] =
    Set("EXACT_ID", "DETERMINISTIC_BIBLIOGRAPHIC", "PROBABILISTIC", "LLM_ADJUDICATED")
  private val DecisionFiles = Vector("accepted", "rejected", "unresolved", "conflict")
  private val Unrecorded = "unrecorded"

  private def str(o: ujson.Value, k: String): Option[String] =
    o.obj.get(k).flatMap(v => v.strOpt)

  def load(dir: Path): Either[String, RunData] =
    try
      val summaryPath = dir.resolve("summary.json")
      val manifestPath = dir.resolve("run-manifest.json")
      val indexPath = dir.resolve("decision-index.csv")
      val missing = Vector(summaryPath, manifestPath, indexPath).filterNot(Files.isRegularFile(_))
      if missing.nonEmpty then Left(s"run directory lacks ${missing.mkString(", ")}")
      else
        val summary = ujson.read(Files.readString(summaryPath, StandardCharsets.UTF_8))
        val manifest = ujson.read(Files.readString(manifestPath, StandardCharsets.UTF_8))
        val th = summary("thresholds")
        val json = DecisionFiles.flatMap { n =>
          val p = dir.resolve("decisions").resolve(s"$n.jsonl")
          if !Files.isRegularFile(p) then Vector.empty
          else
            Files
              .readAllLines(p, StandardCharsets.UTF_8)
              .toArray(Array.empty[String])
              .toVector
              .filter(_.trim.nonEmpty)
              .map(ujson.read(_))
        }
        val byId = json.map(j => j("decisionId").str -> j).toMap
        val rows = ReviewQueue.parseCsv(Files.readString(indexPath, StandardCharsets.UTF_8))
        val header = rows.head
        val ix = header.zipWithIndex.toMap
        val decisions = rows.tail.map { r =>
          def c(n: String) = r(ix(n))
          val id = c("decision_id")
          byId.get(id) match
            case None => Left(s"decision $id is in decision-index.csv but in no decisions file")
            case Some(j) if j("decision").str != c("decision") =>
              Left(s"decision $id: index and decisions file disagree on the outcome")
            case Some(j) =>
              val feats = j("features").obj.toVector.map((k, v) => k -> v.num).sortBy(_._1)
              val score = feats.find(_._1 == "score").map(_._2).getOrElse(c("score").toDouble)
              val path = c("match_path")
              if !Paths.contains(path) then Left(s"decision $id: unknown match path $path")
              else if !Outcomes.All.contains(c("decision")) then
                Left(s"decision $id: unknown outcome ${c("decision")}")
              else
                Right(
                  RunDecision(
                    id,
                    c("candidate_id"),
                    c("kind"),
                    c("left_ref"),
                    c("right_ref"),
                    path,
                    c("decision"),
                    score,
                    j("confidence").num,
                    j("reasonCodes").arr.map(_.str).toVector,
                    j("rulesFired").arr.map(_.str).toVector,
                    feats,
                    j("runId").str
                  )
                )
        }
        decisions.collectFirst { case Left(e) => e } match
          case Some(e) => Left(e)
          case None =>
            val ds = decisions.collect { case Right(d) => d }
            val dupCandidates = ds.groupBy(_.candidateId).collect { case (k, v) if v.size > 1 => k }
            if dupCandidates.nonEmpty then
              Left(s"run has several decisions for candidate(s) ${dupCandidates.mkString(", ")}")
            else
              val pv = manifest.obj.get("policyVersions")
              val llm = summary.obj.get("llmBand")
              val inputSha = Files256.ofBytes(
                (Vector(summaryPath, manifestPath, indexPath) ++ DecisionFiles
                  .map(n => dir.resolve("decisions").resolve(s"$n.jsonl"))
                  .filter(Files.isRegularFile(_)))
                  .flatMap(p =>
                    (p.getFileName.toString + ":" + Files256.ofFile(p) + "\n")
                      .getBytes(StandardCharsets.UTF_8)
                  )
                  .toArray
              )
              Right(
                RunData(
                  dir,
                  summary("runId").str,
                  summary("policyVersion").str,
                  str(summary, "policyRevision")
                    .orElse(pv.flatMap(str(_, "entityResolutionPolicyRevision")))
                    .getOrElse(Unrecorded),
                  str(summary, "featureVersion").getOrElse(Unrecorded),
                  str(manifest, "codeVersion").getOrElse(Unrecorded),
                  th("acceptThreshold").num,
                  th("rejectThreshold").num,
                  th("llmBandMin").num,
                  th("llmBandMax").num,
                  str(summary, "adjudicator").getOrElse(Unrecorded),
                  summary.obj
                    .get("adjudicatorIsFake")
                    .flatMap(_.boolOpt)
                    .getOrElse(str(summary, "adjudicator").contains("fake")),
                  str(summary, "adjudicationModelVersion").getOrElse(Unrecorded),
                  str(summary, "adjudicationPromptVersion").getOrElse(Unrecorded),
                  llm.flatMap(_.obj.get("pairsInBand")).flatMap(_.numOpt).map(_.toInt).getOrElse(0),
                  llm
                    .flatMap(_.obj.get("decidedByLlm"))
                    .flatMap(_.numOpt)
                    .map(_.toInt)
                    .getOrElse(0),
                  inputSha,
                  ds.map(d => d.candidateId -> d).toMap
                )
              )
    catch case NonFatal(e) => Left(s"cannot read run directory $dir: ${e.getMessage}")
