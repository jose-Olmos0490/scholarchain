package scholarchain.rag.common

import scholarchain.core.ids.StableIds

/** A refusal with a stable reason code and a readable detail. */
final case class RagProblem(code: String, detail: String):
  def message: String = s"$code: $detail"

type RResult[+A] = Either[RagProblem, A]

object Hashing:
  /** `sha256:<64 hex>` of the UTF-8 text (the core text-hash formula). */
  def textHash(text: String): String =
    StableIds.textHash(text).fold(m => throw new IllegalStateException(m), identity)

  def sha256Hex(text: String): String =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

/** Reads required settings from an explicit environment map. There is no default for any value: a
  * missing or invalid one is reported by name. (The shared core configuration does not define the
  * rag settings yet; follow-up for `domain-engineer`.)
  */
final class EnvReader(env: Map[String, String]):
  private val problems = Vector.newBuilder[String]
  private def raw(v: String): Option[String] = env.get(v).map(_.trim).filter(_.nonEmpty)

  def string(v: String): String = raw(v).getOrElse { problems += s"$v is required"; "" }

  def int(v: String, min: Int, max: Int = Int.MaxValue): Int =
    raw(v) match
      case None => problems += s"$v is required"; min
      case Some(s) =>
        s.toIntOption.filter(i => i >= min && i <= max) match
          case Some(i) => i
          case None => problems += s"$v='$s' must be an integer in [$min, $max]"; min

  def boolean(v: String): Boolean =
    raw(v) match
      case Some("true") => true
      case Some("false") => false
      case Some(s) => problems += s"$v='$s' must be true or false"; false
      case None => problems += s"$v is required"; false

  def check(extra: Option[String]): Unit = extra.foreach(problems += _)

  def result[A](build: => A): RResult[A] =
    val ps = problems.result()
    if ps.nonEmpty then Left(RagProblem("MISCONFIGURED", ps.mkString("; "))) else Right(build)
