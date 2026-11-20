package scholarchain.core.validation

import scholarchain.core.ids.IdKind

/** Typed contract violations. `path` locates the offending field (for example
  * `authors[2].provenance`).
  */
enum ValidationError(val path: String):
  case MissingProvenance(override val path: String) extends ValidationError(path)
  case IncompleteProvenance(override val path: String, field: String) extends ValidationError(path)
  case ConfidenceOutOfRange(override val path: String, value: Double) extends ValidationError(path)
  case UnsupportedSchemaVersion(
      override val path: String,
      recordType: String,
      found: String,
      supported: Vector[String]
  ) extends ValidationError(path)
  case MalformedStableId(
      override val path: String,
      value: String,
      expected: Vector[IdKind],
      reason: String
  ) extends ValidationError(path)
  case StableIdMismatch(override val path: String, found: String, expected: String)
      extends ValidationError(path)
  case PairNotCanonical(override val path: String, first: String, second: String)
      extends ValidationError(path)
  case SelfPair(override val path: String, id: String) extends ValidationError(path)
  case InvalidWindow(override val path: String, startYear: Int, endYear: Int)
      extends ValidationError(path)
  case BlankField(override val path: String) extends ValidationError(path)
  case InvalidValue(override val path: String, reason: String) extends ValidationError(path)
  case DecodeFailure(override val path: String, reason: String) extends ValidationError(path)

  def message: String = this match
    case MissingProvenance(p) => s"$p: provenance is missing"
    case IncompleteProvenance(p, f) => s"$p: provenance field '$f' is blank"
    case ConfidenceOutOfRange(p, v) => s"$p: confidence $v is outside [0, 1]"
    case UnsupportedSchemaVersion(p, t, f, s) =>
      s"$p: unsupported $t schema version '$f'; supported: ${s.mkString(", ")}"
    case MalformedStableId(p, v, e, r) =>
      s"$p: malformed stable ID '$v' (expected ${e.map(_.prefix).mkString(" or ")}): $r"
    case StableIdMismatch(p, f, e) => s"$p: stable ID '$f' does not match its formula ('$e')"
    case PairNotCanonical(p, a, b) => s"$p: pair ('$a', '$b') is not in canonical order (a < b)"
    case SelfPair(p, id) => s"$p: self-pair '$id' is not allowed"
    case InvalidWindow(p, s, e) => s"$p: window start year $s exceeds end year $e"
    case BlankField(p) => s"$p: must not be blank"
    case InvalidValue(p, r) => s"$p: $r"
    case DecodeFailure(p, r) => s"$p: cannot decode: $r"
