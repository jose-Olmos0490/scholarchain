package scholarchain.core.ids

/** A parsed stable identifier. See [[IdKind]] for the grammar. */
final case class StableId(kind: IdKind, namespace: Option[String], digest: String):
  def render: String = namespace match
    case Some(ns) => s"${kind.prefix}:$ns:$digest"
    case None => s"${kind.prefix}:$digest"

object StableId:
  private val Digest = "[0-9a-f]{64}".r

  /** Parses any stable ID. Fails on unknown kind, disallowed namespace or a bad digest. */
  def parse(value: String): Either[String, StableId] =
    if value == null then Left("stable ID is null")
    else
      value.split(":", -1).toList match
        case prefix :: digest :: Nil =>
          IdKind.byPrefix(prefix) match
            case None => Left(s"unknown stable-ID kind '$prefix' in '$value'")
            case Some(kind) if kind.hasNamespace =>
              Left(s"stable ID '$value' lacks the namespace required for kind '$prefix'")
            case Some(kind) =>
              if Digest.matches(digest) then Right(StableId(kind, None, digest))
              else Left(s"stable ID '$value' has a malformed SHA-256 digest")
        case prefix :: ns :: digest :: Nil =>
          IdKind.byPrefix(prefix) match
            case None => Left(s"unknown stable-ID kind '$prefix' in '$value'")
            case Some(kind) if !kind.allowsNamespace(ns) =>
              Left(s"stable ID '$value' has namespace '$ns', not allowed for kind '$prefix'")
            case Some(kind) =>
              if Digest.matches(digest) then Right(StableId(kind, Some(ns), digest))
              else Left(s"stable ID '$value' has a malformed SHA-256 digest")
        case _ => Left(s"stable ID '$value' does not match kind[:namespace]:sha256hex")

  /** Parses and requires one of the given kinds. */
  def parseAs(value: String, kinds: Set[IdKind]): Either[String, StableId] =
    parse(value).flatMap { id =>
      if kinds.contains(id.kind) then Right(id)
      else
        Left(
          s"stable ID '$value' is a ${id.kind.prefix} ID; expected ${kinds.map(_.prefix).toVector.sorted.mkString(" or ")}"
        )
    }

  def isValid(value: String, kind: IdKind): Boolean = parseAs(value, Set(kind)).isRight
