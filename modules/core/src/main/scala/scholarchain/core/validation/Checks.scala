package scholarchain.core.validation

import java.time.Instant
import scala.util.Try
import scholarchain.core.ids.{CanonicalPair, IdKind, StableId, StableIds}
import scholarchain.core.model.Provenance
import scholarchain.core.schema.SchemaVersions
import scholarchain.core.validation.ValidationError.*

/** Reusable field checks. Every function returns the violations it finds (empty when valid). */
private[core] object Checks:
  type Errors = Vector[ValidationError]
  val ok: Errors = Vector.empty

  def isBlank(s: String): Boolean = s == null || s.trim.isEmpty

  def nonBlank(path: String, value: String): Errors =
    if isBlank(value) then Vector(BlankField(path)) else ok

  def optNonBlank(path: String, value: Option[String]): Errors =
    if value == null then Vector(InvalidValue(path, "null option"))
    else value.toVector.flatMap(nonBlank(path, _))

  def notNull(path: String, value: AnyRef): Errors =
    if value == null then Vector(InvalidValue(path, "must not be null")) else ok

  def schemaVersion(path: String, recordType: String, found: String): Errors =
    val supported = SchemaVersions.supportedFor(recordType)
    if found != null && supported.contains(found) then ok
    else
      Vector(
        UnsupportedSchemaVersion(path, recordType, String.valueOf(found), supported.toVector.sorted)
      )

  def id(path: String, value: String, kinds: IdKind*): Errors =
    if value == null then Vector(MalformedStableId(path, "null", kinds.toVector, "null"))
    else
      val parsed =
        if kinds.isEmpty then StableId.parse(value) else StableId.parseAs(value, kinds.toSet)
      parsed.fold(r => Vector(MalformedStableId(path, value, kinds.toVector, r)), _ => ok)

  def optId(path: String, value: Option[String], kinds: IdKind*): Errors =
    if value == null then Vector(InvalidValue(path, "null option"))
    else value.toVector.flatMap(id(path, _, kinds*))

  def ids(path: String, values: Vector[String], kinds: IdKind*): Errors =
    if values == null then Vector(InvalidValue(path, "must not be null"))
    else values.zipWithIndex.flatMap((v, i) => id(s"$path[$i]", v, kinds*))

  def distinct(path: String, values: Vector[String]): Errors =
    if values != null && values.distinct.size != values.size then
      Vector(InvalidValue(path, "contains duplicates"))
    else ok

  def confidence(path: String, value: Double): Errors =
    if value.isNaN || value < 0.0 || value > 1.0 then Vector(ConfidenceOutOfRange(path, value))
    else ok

  def window(path: String, startYear: Int, endYear: Int): Errors =
    if startYear > endYear then Vector(InvalidWindow(path, startYear, endYear)) else ok

  def pair(path: String, a: String, b: String): Errors =
    if a == null || b == null then Vector(InvalidValue(path, "pair member is null"))
    else if a == b then Vector(SelfPair(path, a))
    else if !CanonicalPair.isCanonical(a, b) then Vector(PairNotCanonical(path, a, b))
    else ok

  def nonNegative(path: String, value: Long): Errors =
    if value < 0 then Vector(InvalidValue(path, s"must be >= 0, got $value")) else ok

  def finiteNonNegative(path: String, value: Double): Errors =
    if value.isNaN || value.isInfinite || value < 0 then
      Vector(InvalidValue(path, s"must be a finite number >= 0, got $value"))
    else ok

  def textHash(path: String, value: String): Errors =
    if StableIds.isTextHash(value) then ok
    else Vector(InvalidValue(path, s"'$value' is not sha256:<64 lower-case hex>"))

  def instant(path: String, value: String): Errors =
    if value != null && Try(Instant.parse(value)).isSuccess then ok
    else Vector(InvalidValue(path, s"'$value' is not an ISO-8601 instant"))

  def check(cond: Boolean, error: => ValidationError): Errors = if cond then ok else Vector(error)

  /** Recomputes a stable ID and compares. Formula failures are reported by the other checks, so
    * a `Left` here yields no extra error.
    */
  def matchesFormula(path: String, found: String, expected: Either[String, String]): Errors =
    expected match
      case Right(e) if e != found => Vector(StableIdMismatch(path, found, e))
      case _ => ok

  /** Like [[matchesFormula]], but used where the formula inputs are not checked elsewhere: a
    * `Left` (an input the formula rejects) is itself a violation.
    */
  def matchesFormulaStrict(path: String, found: String, expected: Either[String, String]): Errors =
    expected match
      case Right(e) if e != found => Vector(StableIdMismatch(path, found, e))
      case Right(_) => ok
      case Left(reason) => Vector(InvalidValue(path, s"cannot be recomputed: $reason"))

  def provenance(path: String, p: Provenance): Errors =
    if p == null then Vector(MissingProvenance(path))
    else
      def required(field: String, v: String): Errors =
        if isBlank(v) then Vector(IncompleteProvenance(path, field)) else ok
      schemaVersion(s"$path.schemaVersion", "Provenance", p.schemaVersion) ++
        (if p.sourceDataset == null then Vector(IncompleteProvenance(path, "sourceDataset"))
         else ok) ++
        required("sourceRecordId", p.sourceRecordId) ++
        required("snapshot", p.snapshot) ++
        (if isBlank(p.runId) then Vector(IncompleteProvenance(path, "runId"))
         else id(s"$path.runId", p.runId, IdKind.Run)) ++
        optNonBlank(s"$path.policyVersion", p.policyVersion) ++
        optNonBlank(s"$path.modelVersion", p.modelVersion) ++
        optNonBlank(s"$path.promptVersion", p.promptVersion) ++
        ids(s"$path.derivedFrom", p.derivedFrom)
