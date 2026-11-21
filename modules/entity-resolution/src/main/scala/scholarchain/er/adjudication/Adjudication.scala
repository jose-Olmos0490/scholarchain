package scholarchain.er.adjudication

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.util.control.NonFatal

/** LLM adjudication outcome. ABSTAIN is always allowed and always safe. */
enum AdjudicationOutcome:
  case ACCEPT, REJECT, ABSTAIN

/** A schema-validated adjudication (`er-adjudication-response-v1`). */
final case class AdjudicationResponse(
    outcome: AdjudicationOutcome,
    candidateIds: Vector[String],
    citedFields: Vector[String],
    confidence: Double,
    reasonCodes: Vector[String]
)

/** Input to one adjudication: the candidate IDs and a read-only copy of the compared fields.
  *
  * The request is pure data. It is rendered to canonical JSON (sorted keys, compact) for the
  * model and for the cache key; the model never receives a handle on a source record.
  *
  * @param candidateId
  *   the match-candidate ID (or the exact-identifier outcome ID for an exact-ID-only pair)
  * @param left
  *   compared fields of the left record; must contain `ref`
  * @param right
  *   compared fields of the right record; must contain `ref`
  */
final case class AdjudicationRequest(
    candidateId: String,
    entityType: String,
    left: ujson.Obj,
    right: ujson.Obj,
    features: Map[String, Double],
    score: Double
):
  private def ref(o: ujson.Obj): String = o.value.get("ref").map(_.str).getOrElse("")
  def leftRef: String = ref(left)
  def rightRef: String = ref(right)

  /** The only identifiers an adjudication may name. */
  def allowedIds: Set[String] = Set(candidateId, leftRef, rightRef).filter(_.nonEmpty)

  /** The only field names an adjudication may cite. */
  def allowedFields: Set[String] =
    left.value.keySet.map("left." + _).toSet ++ right.value.keySet.map("right." + _).toSet ++
      features.keySet.map("features." + _) + "score"

  def toJson: ujson.Value = ujson.Obj(
    "candidateId" -> candidateId,
    "entityType" -> entityType,
    "left" -> left,
    "right" -> right,
    "features" -> ujson.Obj.from(features.toSeq.sortBy(_._1).map((k, v) => k -> ujson.Num(v))),
    "score" -> score,
    "allowedCandidateIds" -> ujson.Arr.from(allowedIds.toVector.sorted.map(ujson.Str(_))),
    "allowedCitedFields" -> ujson.Arr.from(allowedFields.toVector.sorted.map(ujson.Str(_)))
  )

  /** Canonical JSON: every object's keys sorted, compact. */
  def canonicalJson: String = Canonical.render(toJson)

  /** `sha256:<hex>` of the canonical JSON. */
  def inputHash: String = "sha256:" + Canonical.sha256Hex(canonicalJson)

object Canonical:
  def canon(v: ujson.Value): ujson.Value = v match
    case o: ujson.Obj => ujson.Obj.from(o.value.toSeq.sortBy(_._1).map((k, x) => k -> canon(x)))
    case a: ujson.Arr => ujson.Arr.from(a.value.map(canon))
    case other => other

  def render(v: ujson.Value): String = canon(v).render()

  def sha256Hex(s: String): String =
    val d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))
    d.map(b => f"${b & 0xff}%02x").mkString

  private def field(s: String): String = s"${s.getBytes(StandardCharsets.UTF_8).length}:$s"

  /** sha256 over length-prefixed fields (no field can run into the next). */
  def hashFields(fields: String*): String = sha256Hex(fields.map(field).mkString)

/** Strict parser for `er-adjudication-response-v1`. The JSON Schema document is the classpath
  * resource [[SchemaResource]].
  *
  * Rules: the whole output must be one JSON object (surrounding whitespace allowed, nothing
  * else); no object anywhere in the output may repeat a key, even with different escaping
  * (`{"outcome":"REJECT",...,"outcome":"ACCEPT"}` is rejected with `DUPLICATE_KEY`: a JSON
  * reader keeps only one of the values, so such an output has no single meaning; finding F1);
  * exactly the keys `outcome`, `candidateIds`, `citedFields`, `confidence`,
  * `reasonCodes`; `outcome` one of ACCEPT, REJECT, ABSTAIN; `candidateIds` a non-empty array of
  * distinct strings, each one of the request's allowed IDs (an ID the model invented is
  * rejected); `citedFields` an array of distinct strings, each one of the request's allowed field
  * names, non-empty for ACCEPT and REJECT; `confidence` a finite number in [0, 1];
  * `reasonCodes` a non-empty array of `[A-Z][A-Z0-9_]{0,63}` strings.
  */
object AdjudicationSchema:
  val Version = "er-adjudication-response-v1"
  val SchemaResource = "scholarchain/er/schemas/er-adjudication-response-v1.schema.json"
  val Keys: Set[String] = Set("outcome", "candidateIds", "citedFields", "confidence", "reasonCodes")
  private val ReasonCode = "[A-Z][A-Z0-9_]{0,63}".r

  /** Error prefix for an output naming an identifier absent from the request. */
  val FabricatedIdError = "FABRICATED_ID"

  /** Error prefix for an output in which one object repeats a key. */
  val DuplicateKeyError = "DUPLICATE_KEY"

  /** Keys repeated inside any one object of `json`, which must already be valid JSON. Keys are
    * compared after JSON string unescaping (`"outcome"` and `"\u006futcome"` are one key).
    * Iterative (an explicit stack), so deeply nested input cannot overflow the call stack.
    */
  def duplicateKeys(json: String): Vector[String] =
    final class Frame(val isObject: Boolean):
      val keys = scala.collection.mutable.HashSet.empty[String]
      var expectKey: Boolean = isObject
    val stack = scala.collection.mutable.Stack.empty[Frame]
    val dups = Vector.newBuilder[String]
    var i = 0
    val n = json.length
    while i < n do
      json.charAt(i) match
        case '{' =>
          stack.push(Frame(true)); i += 1
        case '[' =>
          stack.push(Frame(false)); i += 1
        case '}' | ']' =>
          if stack.nonEmpty then stack.pop()
          i += 1
        case ',' =>
          if stack.nonEmpty && stack.top.isObject then stack.top.expectKey = true
          i += 1
        case '"' =>
          var j = i + 1
          while j < n && json.charAt(j) != '"' do j += (if json.charAt(j) == '\\' then 2 else 1)
          val token = json.substring(i, math.min(n, j + 1))
          if stack.nonEmpty && stack.top.isObject && stack.top.expectKey then
            val key =
              try ujson.read(token).str
              catch case NonFatal(_) => token
            if !stack.top.keys.add(key) then dups += key
            stack.top.expectKey = false
          i = j + 1
        case _ => i += 1
    dups.result().distinct

  def parse(
      raw: String,
      request: AdjudicationRequest
  ): Either[Vector[String], AdjudicationResponse] =
    val json =
      try Right(ujson.read(if raw == null then "" else raw.trim))
      catch case NonFatal(e) => Left(Vector(s"NOT_JSON: ${e.getClass.getSimpleName}"))
    json.flatMap {
      case o: ujson.Obj =>
        val dups = duplicateKeys(raw.trim)
        if dups.nonEmpty then Left(dups.sorted.map(k => s"$DuplicateKeyError: $k"))
        else fromObject(o, request)
      case _ => Left(Vector("NOT_AN_OBJECT"))
    }

  private def strings(o: ujson.Obj, key: String): Either[String, Vector[String]] =
    o.value.get(key) match
      case Some(a: ujson.Arr) =>
        val xs = a.value.toVector
        if xs.forall(_.isInstanceOf[ujson.Str]) then
          val v = xs.map(_.str)
          if v.distinct.size == v.size then Right(v) else Left(s"DUPLICATE_ENTRIES: $key")
        else Left(s"NOT_A_STRING_ARRAY: $key")
      case _ => Left(s"NOT_A_STRING_ARRAY: $key")

  private def fromObject(
      o: ujson.Obj,
      req: AdjudicationRequest
  ): Either[Vector[String], AdjudicationResponse] =
    val keys = o.value.keySet.toSet
    val keyErrors =
      (keys -- Keys).toVector.sorted.map(k => s"UNKNOWN_KEY: $k") ++
        (Keys -- keys).toVector.sorted.map(k => s"MISSING_KEY: $k")
    if keyErrors.nonEmpty then Left(keyErrors)
    else
      val outcome = o.value("outcome") match
        case ujson.Str(s) =>
          AdjudicationOutcome.values.find(_.toString == s).toRight(s"INVALID_OUTCOME: $s")
        case _ => Left("INVALID_OUTCOME")
      val ids = strings(o, "candidateIds").flatMap { v =>
        val unknown = v.filterNot(req.allowedIds.contains)
        if v.isEmpty then Left("EMPTY: candidateIds")
        else if unknown.nonEmpty then Left(s"$FabricatedIdError: ${unknown.mkString(",")}")
        else Right(v)
      }
      val cited = strings(o, "citedFields").flatMap { v =>
        val unknown = v.filterNot(req.allowedFields.contains)
        if unknown.nonEmpty then Left(s"UNKNOWN_CITED_FIELD: ${unknown.mkString(",")}")
        else Right(v)
      }
      val confidence = o.value("confidence") match
        case ujson.Num(d) if !d.isNaN && !d.isInfinite && d >= 0.0 && d <= 1.0 => Right(d)
        case _ => Left("INVALID_CONFIDENCE")
      val reasons = strings(o, "reasonCodes").flatMap { v =>
        if v.isEmpty then Left("EMPTY: reasonCodes")
        else if v.exists(r => !ReasonCode.matches(r)) then Left("INVALID_REASON_CODE")
        else Right(v)
      }
      val errors = Vector(outcome, ids, cited, confidence, reasons).collect { case Left(e) => e }
      if errors.nonEmpty then Left(errors)
      else
        val r = AdjudicationResponse(
          outcome.toOption.get,
          ids.toOption.get,
          cited.toOption.get,
          confidence.toOption.get,
          reasons.toOption.get
        )
        if r.outcome != AdjudicationOutcome.ABSTAIN && r.citedFields.isEmpty then
          Left(Vector("EMPTY: citedFields (required for ACCEPT and REJECT)"))
        else Right(r)

/** A versioned prompt loaded from the classpath. The first line of the resource must be
  * `prompt-version: <version>`; the text after it is the system prompt.
  */
final case class Prompt(version: String, text: String):
  def sha256: String = "sha256:" + Canonical.sha256Hex(text)

object Prompt:
  val AdjudicationResource = "scholarchain/er/prompts/er-adjudication-prompt-v1.txt"
  val AdjudicationVersion = "er-adj-prompt-v1"

  def load(resource: String): Either[String, Prompt] =
    Option(getClass.getClassLoader.getResourceAsStream(resource)) match
      case None => Left(s"missing prompt resource $resource")
      case Some(in) =>
        try
          val text = new String(in.readAllBytes(), StandardCharsets.UTF_8)
          val (first, rest) = text.span(_ != '\n')
          val Header = "prompt-version: (\\S+)".r
          first.trim match
            case Header(v) => Right(Prompt(v, rest.drop(1)))
            case _ => Left(s"$resource must start with 'prompt-version: <version>'")
        finally in.close()

  /** The adjudication prompt of this build; fails if the resource version is not
    * [[AdjudicationVersion]].
    */
  def adjudication: Either[String, Prompt] =
    load(AdjudicationResource).flatMap(p =>
      if p.version == AdjudicationVersion then Right(p)
      else Left(s"prompt version ${p.version} != $AdjudicationVersion")
    )

/** Port to an LLM. Implementations receive the prompt and the request JSON only; they return
  * the model's raw text or a transport error. The output is validated by
  * [[AdjudicationSchema.parse]] before any use.
  */
trait Adjudicator:
  /** Model ID and version, recorded on every decision this adjudicator contributes to. */
  def modelVersion: String
  def adjudicate(prompt: Prompt, request: AdjudicationRequest): Either[String, String]

/** Cache key components: `hash(inputHash, promptVersion, promptSha256, modelVersion,
  * policyVersion)`. The prompt text hash is part of the key (finding F13): editing the prompt
  * without bumping its version string never reuses an answer given to the old text.
  *
  * @param policyVersion
  *   the policy version and decision-logic revision ([[scholarchain.er.policy.Policy.cacheVersion]])
  */
final case class AdjudicationKey(
    inputHash: String,
    promptVersion: String,
    promptSha256: String,
    modelVersion: String,
    policyVersion: String
):
  def cacheKey: String =
    "sha256:" + Canonical.hashFields(
      inputHash,
      promptVersion,
      promptSha256,
      modelVersion,
      policyVersion
    )

/** One cached raw model output with its key components. */
final case class CachedAdjudication(
    cacheKey: String,
    inputHash: String,
    promptVersion: String,
    modelVersion: String,
    policyVersion: String,
    candidateId: String,
    rawOutput: String,
    promptSha256: String = ""
) derives upickle.default.ReadWriter

trait AdjudicationCache:
  def get(cacheKey: String): Option[CachedAdjudication]
  def put(entry: CachedAdjudication): Unit

/** In-memory cache, local to one run (not shared between tasks). */
final class InMemoryAdjudicationCache extends AdjudicationCache:
  private val entries = scala.collection.mutable.LinkedHashMap.empty[String, CachedAdjudication]
  def get(cacheKey: String): Option[CachedAdjudication] = entries.get(cacheKey)
  def put(entry: CachedAdjudication): Unit = entries.getOrElseUpdate(entry.cacheKey, entry)
  def all: Vector[CachedAdjudication] = entries.values.toVector

/** Result of one adjudication attempt through [[AdjudicationService]]. */
final case class AdjudicationResult(
    key: AdjudicationKey,
    modelVersion: String,
    promptVersion: String,
    cacheHit: Boolean,
    rawOutput: Option[String],
    parsed: Either[Vector[String], AdjudicationResponse]
)

/** Cache-first adjudication. A transport error is not cached (a later run may retry); every
  * model output, valid or not, is cached so a re-run never re-asks the same question.
  */
final class AdjudicationService(
    adjudicator: Adjudicator,
    cache: AdjudicationCache,
    prompt: Prompt,
    policyVersion: String
):
  def modelVersion: String = adjudicator.modelVersion
  def promptVersion: String = prompt.version
  def promptSha256: String = prompt.sha256

  def adjudicate(request: AdjudicationRequest): AdjudicationResult =
    val key =
      AdjudicationKey(request.inputHash, prompt.version, prompt.sha256, modelVersion, policyVersion)
    cache.get(key.cacheKey) match
      case Some(hit) =>
        AdjudicationResult(
          key,
          modelVersion,
          prompt.version,
          cacheHit = true,
          Some(hit.rawOutput),
          AdjudicationSchema.parse(hit.rawOutput, request)
        )
      case None =>
        adjudicator.adjudicate(prompt, request) match
          case Left(err) =>
            AdjudicationResult(
              key,
              modelVersion,
              prompt.version,
              cacheHit = false,
              None,
              Left(Vector(s"TRANSPORT_ERROR: $err"))
            )
          case Right(raw) =>
            cache.put(
              CachedAdjudication(
                key.cacheKey,
                key.inputHash,
                key.promptVersion,
                key.modelVersion,
                key.policyVersion,
                request.candidateId,
                raw,
                key.promptSha256
              )
            )
            AdjudicationResult(
              key,
              modelVersion,
              prompt.version,
              cacheHit = false,
              Some(raw),
              AdjudicationSchema.parse(raw, request)
            )

/** Deterministic stand-in for the LLM, for tests and local runs. It is NOT a model: decisions
  * it contributes to are labelled with [[FakeAdjudicator.ModelVersion]] and must never be
  * reported as model results.
  *
  * Rule (on the request features only): ACCEPT (confidence 0.8) when the primary similarity
  * (`nameSimilarity` for researchers, `titleSimilarity` for works) is >= 0.9 and
  * `coauthorOverlap` is >= 0.3; REJECT (confidence 0.8) when the primary similarity is < 0.7;
  * otherwise ABSTAIN (confidence 0).
  */
final class FakeAdjudicator extends Adjudicator:
  def modelVersion: String = FakeAdjudicator.ModelVersion

  def adjudicate(prompt: Prompt, request: AdjudicationRequest): Either[String, String] =
    val primaryName =
      if request.entityType == "RESEARCHER" then "nameSimilarity" else "titleSimilarity"
    val primary = request.features.get(primaryName)
    val overlap = request.features.get("coauthorOverlap")
    val ids = Vector(request.leftRef, request.rightRef)
    def out(o: AdjudicationOutcome, conf: Double, cited: Vector[String], reason: String) =
      ujson
        .Obj(
          "outcome" -> o.toString,
          "candidateIds" -> ujson.Arr.from(ids.map(ujson.Str(_))),
          "citedFields" -> ujson.Arr.from(cited.map(ujson.Str(_))),
          "confidence" -> conf,
          "reasonCodes" -> ujson.Arr(reason)
        )
        .render()
    val result = (primary, overlap) match
      case (Some(p), Some(c)) if p >= 0.9 && c >= 0.3 =>
        out(
          AdjudicationOutcome.ACCEPT,
          0.8,
          Vector(s"features.$primaryName", "features.coauthorOverlap"),
          "FAKE_PRIMARY_AND_COAUTHORS_AGREE"
        )
      case (Some(p), _) if p < 0.7 =>
        out(
          AdjudicationOutcome.REJECT,
          0.8,
          Vector(s"features.$primaryName"),
          "FAKE_PRIMARY_SIMILARITY_LOW"
        )
      case _ =>
        out(AdjudicationOutcome.ABSTAIN, 0.0, Vector.empty, "FAKE_INSUFFICIENT_EVIDENCE")
    Right(result)

object FakeAdjudicator:
  val ModelVersion = "fake-deterministic-adjudicator-v1"
