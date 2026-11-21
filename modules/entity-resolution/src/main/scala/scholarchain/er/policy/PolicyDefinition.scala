package scholarchain.er.policy

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal
import scholarchain.core.config.{ConfigLoader, EntityResolutionConfig}
import scholarchain.er.adjudication.Canonical
import scholarchain.er.text.TextNormalize
import upickle.default.*

/** Versioned scoring-policy parameters (feature weights and feature constants). One JSON
  * resource per policy version, `scholarchain/er/policy/<policyVersion>.json`. The policy version
  * itself and the four decision thresholds come from configuration
  * ([[scholarchain.core.config.EntityResolutionConfig]]).
  *
  * @param policyRevision
  *   revision of the decision logic inside one policy version (`er-v1-r2`). The configured policy
  *   version selects the threshold set and weights; the revision changes whenever the decision
  *   logic changes without a threshold change. It is recorded on every decision (rule
  *   `POLICY_REVISION:<rev>`), in the run manifest (so it changes the run ID) and in the
  *   adjudication cache key. See `modules/entity-resolution/POLICY-CHANGELOG.md`.
  * @param featureVersion
  *   version of the feature definitions in [[scholarchain.er.features.Features]]
  * @param genericTitlesResource
  *   classpath resource listing generic titles (see [[GenericTitles]])
  * @param researcherWeights
  *   weight per researcher feature name; features absent from the map have weight 0
  * @param workWeights
  *   weight per work feature name
  * @param missingFeatureValue
  *   value in [0, 1] used for a weighted feature that is not available on a pair (neutral prior)
  * @param researcherYearScale
  *   years over which researcher year proximity falls from 1 to 0
  * @param workYearScale
  *   years over which work year proximity falls from 1 to 0
  * @param sourceReliability
  *   reliability in [0, 1] per `SourceType` name
  * @param genericTitles
  *   the normalized entries of `genericTitlesResource`; filled by [[Policy.loadParameters]], never
  *   read from the JSON resource
  */
final case class PolicyParameters(
    policyVersion: String,
    policyRevision: String,
    featureVersion: String,
    genericTitlesResource: String,
    researcherWeights: Map[String, Double],
    workWeights: Map[String, Double],
    missingFeatureValue: Double,
    researcherYearScale: Double,
    workYearScale: Double,
    sourceReliability: Map[String, Double],
    genericTitles: Set[String] = Set.empty
) derives ReadWriter:
  /** True when `title` does not identify a work by itself (see [[GenericTitles.isGeneric]]). */
  def isGenericTitle(title: String): Boolean = GenericTitles.isGeneric(title, genericTitles)

/** Generic-title list (`generic-titles-v<N>.txt`, format documented in the resource). */
object GenericTitles:
  private def isRoman(t: String): Boolean = t.nonEmpty && t.forall("ivxlcdm".contains(_))

  /** Parses the resource text: one normalized entry per line, `#` comments and blanks ignored.
    * Each entry is normalized again so a hand-edited line still compares correctly.
    */
  def parse(text: String): Set[String] =
    text.linesIterator
      .map(_.trim)
      .filter(l => l.nonEmpty && !l.startsWith("#"))
      .map(TextNormalize.normalizeTitle)
      .filter(_.nonEmpty)
      .toSet

  /** A title is generic when its normalized form is empty or equals an entry, or equals an
    * entry after numeric and roman-numeral tokens are removed (`Editorial 2018`, `Erratum II`).
    */
  def isGeneric(title: String, entries: Set[String]): Boolean =
    val tokens = TextNormalize.titleTokens(title)
    if tokens.isEmpty then true
    else
      val full = tokens.mkString(" ")
      val stripped = tokens.filterNot(t => t.forall(Character.isDigit) || isRoman(t)).mkString(" ")
      entries.contains(full) || (stripped.nonEmpty && entries.contains(stripped))

/** Decision thresholds, copied from configuration. Semantics (half-open, no overlap):
  *   - `score >= acceptThreshold` accepts
  *   - `score < rejectThreshold` rejects
  *   - `llmBandMin <= score < llmBandMax` is eligible for LLM adjudication
  *   - every other score is UNRESOLVED
  */
final case class Thresholds(
    acceptThreshold: Double,
    rejectThreshold: Double,
    llmBandMin: Double,
    llmBandMax: Double
):
  def inLlmBand(score: Double): Boolean = score >= llmBandMin && score < llmBandMax

object Thresholds:
  def from(c: EntityResolutionConfig): Thresholds =
    Thresholds(c.acceptThreshold, c.rejectThreshold, c.llmBandMin, c.llmBandMax)

/** The three gating cutoffs besides the four score thresholds (finding F7, Phase 05 repair-01).
  *
  * They are read only from the er-v1 module properties file
  * (`modules/entity-resolution/config/er-v1-thresholds.properties`), the same file that holds the
  * four er-v1 thresholds. The path is never built into the code: `ResolveSample` takes it from
  * the required `--er-policy-properties <path>` argument and passes it to [[Policy.load]]. There is
  * no default and no fallback: a missing file, a missing, blank, duplicated or non-numeric value,
  * NaN, an infinity, or a value outside [0, 1] stops the run (see [[DecisionCutoffs.parse]]).
  *
  * @param minSurnameSimilarity
  *   surname Jaro-Winkler below which two names are incompatible
  * @param minSharedIdTitleSimilarity
  *   title similarity below which two works that share an identifier are a CONFLICT
  * @param llmMinConfidence
  *   LLM ACCEPT or REJECT with a lower confidence is treated as an abstention
  * @param sourceName
  *   file name of the properties file the values came from (recorded in the run manifest)
  * @param sourceSha256
  *   `sha256:<hex>` of the properties file bytes (recorded in the run manifest, so any edit of the
  *   file gives a new run ID)
  */
final case class DecisionCutoffs(
    minSurnameSimilarity: Double,
    minSharedIdTitleSimilarity: Double,
    llmMinConfidence: Double,
    sourceName: String,
    sourceSha256: String
):
  /** Source of each cutoff, as recorded in the run manifest: `properties-file:<name>#<key>`. */
  def sources: Map[String, String] =
    DecisionCutoffs.Keys.map(k => k -> s"${DecisionCutoffs.SourcePrefix}$sourceName#$k").toMap

  def valueOf(key: String): Double = key match
    case "minSurnameSimilarity" => minSurnameSimilarity
    case "minSharedIdTitleSimilarity" => minSharedIdTitleSimilarity
    case "llmMinConfidence" => llmMinConfidence
    case other => throw new IllegalArgumentException(s"unknown cutoff $other")

object DecisionCutoffs:
  /** Property keys in the er-v1 properties file. */
  val Keys: Vector[String] =
    Vector("minSurnameSimilarity", "minSharedIdTitleSimilarity", "llmMinConfidence")

  val SourcePrefix = "properties-file:"

  /** Reads the cutoffs from the properties file at `path`. */
  def load(path: Path): Either[String, DecisionCutoffs] =
    val name = Option(path.getFileName).map(_.toString).getOrElse(path.toString)
    if !Files.isRegularFile(path) then Left(s"er policy properties file not found: $path")
    else
      try
        val bytes = Files.readAllBytes(path)
        val text = String(bytes, StandardCharsets.UTF_8)
        parse(text, name).left.map(e => s"er policy properties file $path: $e")
      catch
        case NonFatal(e) => Left(s"cannot read er policy properties file $path: ${e.getMessage}")

  /** Parses the properties text. Lines are `key=value`; blank lines and lines starting with `#`
    * or `!` are ignored; keys other than [[Keys]] are ignored (the file also holds the threshold
    * environment variables). Each cutoff key must appear exactly once with a finite value in
    * [0, 1]; every problem is reported, none is defaulted.
    */
  def parse(text: String, sourceName: String): Either[String, DecisionCutoffs] =
    val entries = text.linesIterator
      .map(_.trim)
      .filter(l => l.nonEmpty && !l.startsWith("#") && !l.startsWith("!"))
      .flatMap { l =>
        val i = l.indexOf('=')
        if i < 0 then None else Some(l.substring(0, i).trim -> l.substring(i + 1).trim)
      }
      .toVector
    val values = Keys.map { k =>
      entries.filter(_._1 == k).map(_._2) match
        case Vector() => k -> Left(s"$k is missing")
        case Vector(v) if v.isEmpty => k -> Left(s"$k is blank")
        case Vector(v) =>
          v.toDoubleOption match
            case None => k -> Left(s"$k is not a number: '$v'")
            case Some(d) if d.isNaN || d.isInfinite || d < 0.0 || d > 1.0 =>
              k -> Left(s"$k must be a finite number in [0, 1], got '$v'")
            case Some(d) => k -> Right(d)
        case vs => k -> Left(s"$k is set ${vs.size} times")
    }.toMap
    val errors = Keys.flatMap(k => values(k).left.toOption)
    if errors.nonEmpty then Left(errors.mkString("; "))
    else
      val v = values.view.mapValues(_.toOption.get).toMap
      Right(
        DecisionCutoffs(
          v("minSurnameSimilarity"),
          v("minSharedIdTitleSimilarity"),
          v("llmMinConfidence"),
          sourceName,
          "sha256:" + Canonical.sha256Hex(text)
        )
      )

/** A complete policy: parameters from the versioned resource, thresholds from configuration, and
  * the gating cutoffs from the er-v1 properties file.
  */
final case class Policy(
    parameters: PolicyParameters,
    thresholds: Thresholds,
    cutoffs: DecisionCutoffs
):
  def policyVersion: String = parameters.policyVersion
  def policyRevision: String = parameters.policyRevision

  /** Version string used in the adjudication cache key: policy version and revision. */
  def cacheVersion: String = s"$policyVersion+$policyRevision"

object Policy:
  /** Policy version implemented by this code. */
  val ImplementedVersion = "er-v1"

  /** Decision-logic revision implemented by this code (see POLICY-CHANGELOG.md). */
  val ImplementedRevision = "er-v1-r2"

  def resourcePath(policyVersion: String): String = s"scholarchain/er/policy/$policyVersion.json"

  /** Raw bytes of a classpath resource. */
  def resourceBytes(path: String): Either[String, Array[Byte]] =
    Option(getClass.getClassLoader.getResourceAsStream(path)) match
      case None => Left(s"missing resource $path")
      case Some(in) =>
        try Right(in.readAllBytes())
        catch case NonFatal(e) => Left(s"cannot read $path: ${e.getMessage}")
        finally in.close()

  /** `sha256:<hex>` of a classpath resource (recorded in the run manifest). */
  def resourceSha256(path: String): Either[String, String] =
    resourceBytes(path).map(b => "sha256:" + Canonical.sha256Hex(String(b, StandardCharsets.UTF_8)))

  /** Loads the parameters for `policyVersion` from the classpath and checks them. */
  def loadParameters(policyVersion: String): Either[String, PolicyParameters] =
    if policyVersion != ImplementedVersion then
      Left(s"policy version '$policyVersion' is not implemented (this build: $ImplementedVersion)")
    else
      val path = resourcePath(policyVersion)
      for
        bytes <- resourceBytes(path)
        _ <- refuseCutoffKeys(path, String(bytes, StandardCharsets.UTF_8))
        p <-
          try Right(read[PolicyParameters](String(bytes, StandardCharsets.UTF_8)))
          catch case NonFatal(e) => Left(s"cannot read $path: ${e.getMessage}")
        titles <- resourceBytes(p.genericTitlesResource)
          .map(b => GenericTitles.parse(String(b, StandardCharsets.UTF_8)))
        withTitles = p.copy(genericTitles = titles)
        _ <- validate(withTitles)
      yield withTitles

  /** The gating cutoffs live only in the er-v1 properties file. A policy resource that still
    * names one is refused, so no second, silently ignored copy can exist.
    */
  def refuseCutoffKeys(path: String, json: String): Either[String, Unit] =
    try
      val keys = ujson.read(json).obj.keySet
      val found = DecisionCutoffs.Keys.filter(keys.contains)
      if found.isEmpty then Right(())
      else
        Left(
          s"$path must not set ${found.mkString(", ")}: the cutoffs are read only from the er " +
            "policy properties file"
        )
    catch case NonFatal(e) => Left(s"cannot read $path: ${e.getMessage}")

  private def unit(name: String, v: Double): Option[String] =
    if v.isNaN || v < 0.0 || v > 1.0 then Some(s"$name must be in [0, 1], got $v") else None

  def validate(p: PolicyParameters): Either[String, Unit] =
    val problems =
      (p.researcherWeights ++ p.workWeights).toVector.flatMap((k, v) =>
        if v.isNaN || v < 0.0 then Some(s"weight $k must be >= 0") else None
      ) ++
        Vector(
          unit("missingFeatureValue", p.missingFeatureValue),
          if p.policyRevision == ImplementedRevision then None
          else Some(s"policyRevision must be $ImplementedRevision, got ${p.policyRevision}"),
          if p.genericTitles.nonEmpty then None else Some("generic-title list must not be empty"),
          if p.researcherYearScale > 0 then None else Some("researcherYearScale must be > 0"),
          if p.workYearScale > 0 then None else Some("workYearScale must be > 0"),
          if p.researcherWeights.values.sum > 0 then None
          else Some("researcherWeights must not all be 0"),
          if p.workWeights.values.sum > 0 then None else Some("workWeights must not all be 0")
        ).flatten ++
        p.sourceReliability.toVector.flatMap((k, v) => unit(s"sourceReliability.$k", v))
    if problems.isEmpty then Right(()) else Left(problems.mkString("; "))

  /** Always fails. A policy cannot be built from the core section alone: the gating cutoffs
    * have no default and are read only from the er policy properties file. Use the two-argument
    * `fromConfig(c, cutoffs)` or [[load]].
    */
  def fromConfig(c: EntityResolutionConfig): Either[String, Policy] =
    Left(
      s"policy ${c.policyVersion}: the gating cutoffs (${DecisionCutoffs.Keys.mkString(", ")}) " +
        "have no default; they must be read from the er policy properties file " +
        "(ResolveSample --er-policy-properties <path>, Policy.load(env, path))"
    )

  /** Builds the policy from the core section (policy version and thresholds) and cutoffs read
    * from the er policy properties file.
    */
  def fromConfig(c: EntityResolutionConfig, cutoffs: DecisionCutoffs): Either[String, Policy] =
    loadParameters(c.policyVersion).map(p => Policy(p, Thresholds.from(c), cutoffs))

  /** Loads the full policy: the core `entityResolution` section through the core configuration
    * loader, and the gating cutoffs from the properties file at `cutoffsFile`.
    */
  def load(env: Map[String, String], cutoffsFile: Path): Either[String, Policy] =
    for
      c <- ConfigLoader.loadSection(EntityResolutionConfig, env).left.map(_.toString)
      cut <- DecisionCutoffs.load(cutoffsFile)
      pol <- fromConfig(c, cut)
    yield pol
