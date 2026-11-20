package scholarchain.core.ids

/** Canonical ordering for undirected pairs (coauthor pairs, undirected graph edges).
  *
  * Rule: the pair `{x, y}` is stored as `(a, b)` with `a < b` under `String.compareTo`, i.e.
  * ordinal UTF-16 code-unit order. Stable IDs are ASCII, so this equals byte order and is
  * locale-independent. A pair whose members are equal (a self-pair) is rejected.
  */
final case class CanonicalPair private (a: String, b: String)

object CanonicalPair:

  /** Orders any two distinct members canonically. */
  def of(x: String, y: String): Either[String, CanonicalPair] =
    if x == null || y == null then Left("pair member must not be null")
    else
      val c = x.compareTo(y)
      if c == 0 then Left(s"self-pair rejected: '$x'")
      else if c < 0 then Right(new CanonicalPair(x, y))
      else Right(new CanonicalPair(y, x))

  /** True when `(a, b)` is already in canonical order (`a < b`, hence also not a self-pair). */
  def isCanonical(a: String, b: String): Boolean = a != null && b != null && a.compareTo(b) < 0
