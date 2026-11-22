package scholarchain.core.config

import com.typesafe.config.{Config, ConfigException}
import scala.collection.mutable.ListBuffer
import scala.util.Try

/** Reads typed values below `scholarchain.` and accumulates every missing key and invalid value,
  * so that one load reports all problems at once.
  *
  * A cursor is local to one load call; it is not shared between threads or tasks. When any
  * problem is recorded the values returned are placeholders and the section result is discarded
  * by [[ConfigSection.from]].
  */
final class ConfigCursor private[config] (config: Config):
  private val missing = ListBuffer.empty[MissingKey]
  private val invalid = ListBuffer.empty[InvalidSetting]

  private def full(key: String): String = s"${ConfigCursor.Root}.$key"

  /** Problems recorded so far, de-duplicated in first-seen order (two sections may read the same
    * key, for example the graph section re-reads the DBLP subset years to check its windows).
    */
  private[config] def problems: (Vector[MissingKey], Vector[InvalidSetting]) =
    (missing.toVector.distinct, invalid.toVector.distinct)

  def fail(key: String, reason: String): Unit = invalid += InvalidSetting(full(key), reason)

  /** Whether `key` is present with a non-blank value. Records nothing. A present value of the
    * wrong type counts as present, so that the reader reports it.
    */
  def has(key: String): Boolean =
    val path = full(key)
    try config.hasPath(path) && config.getString(path).trim.nonEmpty
    catch case _: ConfigException.WrongType => true

  /** Runs `f` on a fresh cursor over the same configuration and discards whatever problems it
    * records. For cross-checks against another section's keys whose absence or invalidity is
    * that section's to report, not the caller's.
    */
  def probe[A](f: ConfigCursor => A): A = f(new ConfigCursor(config))

  /** Raw string; absent, null or blank counts as missing. */
  private def raw(key: String, envVar: String): Option[String] =
    val path = full(key)
    val read: Either[String, Option[String]] =
      try Right(if config.hasPath(path) then Some(config.getString(path)) else None)
      catch case _: ConfigException.WrongType => Left("expected a scalar value")
    read match
      case Left(reason) =>
        fail(key, reason)
        None
      case Right(value) =>
        value.map(_.trim).filter(_.nonEmpty) match
          case some @ Some(_) => some
          case None =>
            missing += MissingKey(path, envVar)
            None

  def string(key: String, envVar: String): String = raw(key, envVar).getOrElse("")

  /** An optional value: absent, null or blank is `None` and records nothing. A non-scalar value
    * is recorded as invalid.
    */
  def optionalString(key: String): Option[String] =
    val path = full(key)
    try
      if config.hasPath(path) then Option(config.getString(path)).map(_.trim).filter(_.nonEmpty)
      else None
    catch
      case _: ConfigException.WrongType =>
        fail(key, "expected a scalar value")
        None

  /** Exactly `true` or `false` (lower case). `None` when missing or invalid (recorded). */
  def booleanOption(key: String, envVar: String): Option[Boolean] =
    raw(key, envVar).flatMap {
      case "true" => Some(true)
      case "false" => Some(false)
      case other =>
        fail(key, s"'$other' is not a boolean (true or false)")
        None
    }

  def int(key: String, envVar: String, min: Int = Int.MinValue, max: Int = Int.MaxValue): Int =
    intOption(key, envVar, min, max).getOrElse(0)

  /** Like [[int]], but `None` when the value is missing or invalid (the problem is still
    * recorded), so dependent checks can skip instead of using a placeholder.
    */
  def intOption(
      key: String,
      envVar: String,
      min: Int = Int.MinValue,
      max: Int = Int.MaxValue
  ): Option[Int] =
    raw(key, envVar)
      .flatMap { s =>
        Try(s.toInt).toOption match
          case Some(v) if v >= min && v <= max => Some(v)
          case Some(v) => fail(key, s"$v is outside [$min, $max]"); None
          case None => fail(key, s"'$s' is not an integer"); None
      }

  def long(key: String, envVar: String): Long =
    raw(key, envVar)
      .flatMap { s =>
        val v = Try(s.toLong).toOption
        if v.isEmpty then fail(key, s"'$s' is not an integer")
        v
      }
      .getOrElse(0L)

  /** A number in [0, 1]. */
  def unitInterval(key: String, envVar: String): Double =
    raw(key, envVar)
      .flatMap { s =>
        Try(s.toDouble).toOption match
          case Some(v) if v >= 0.0 && v <= 1.0 => Some(v)
          case Some(v) => fail(key, s"$v is outside [0, 1]"); None
          case None => fail(key, s"'$s' is not a number"); None
      }
      .getOrElse(0.0)

  /** Comma-separated `YYYY-YYYY` windows, each with start <= end, for example
    * `2000-2005,2006-2010`.
    */
  def yearWindows(key: String, envVar: String): Vector[YearWindow] =
    yearWindowsOption(key, envVar).getOrElse(Vector.empty)

  /** Like [[yearWindows]], but `None` when the value is missing or any window is malformed (the
    * problem is still recorded), so series checks run only on a fully parsed list.
    */
  def yearWindowsOption(key: String, envVar: String): Option[Vector[YearWindow]] =
    raw(key, envVar).flatMap { s =>
      val parts = s.split(",", -1).toVector.map(_.trim)
      val parsed = parts.map { p =>
        ConfigCursor.WindowPattern.findFirstMatchIn(p).filter(_.matched == p) match
          case Some(m) => YearWindow.of(m.group(1).toInt, m.group(2).toInt)
          case None => Left(s"'$p' is not YYYY-YYYY")
      }
      val errors = parsed.collect { case Left(e) => e }
      errors.foreach(fail(key, _))
      if errors.isEmpty then Some(parsed.collect { case Right(w) => w }) else None
    }

  /** Comma-separated list of non-blank tokens, trimmed, in the given order. Blank tokens and
    * duplicates are invalid. `None` when missing or invalid (the problem is recorded).
    */
  def stringList(key: String, envVar: String): Option[Vector[String]] =
    raw(key, envVar).flatMap { s =>
      val parts = s.split(",", -1).toVector.map(_.trim)
      if parts.exists(_.isEmpty) then
        fail(key, s"'$s' contains a blank entry")
        None
      else if parts.distinct.size != parts.size then
        fail(key, s"'$s' contains duplicates")
        None
      else Some(parts)
    }

object ConfigCursor:
  val Root = "scholarchain"
  private val WindowPattern = "([0-9]{4})-([0-9]{4})".r

/** A year window with `startYear <= endYear` (both inclusive). */
final case class YearWindow private (startYear: Int, endYear: Int):
  def contains(other: YearWindow): Boolean =
    startYear <= other.startYear && other.endYear <= endYear

  /** Whether `year` lies in `[startYear, endYear]`. */
  def containsYear(year: Int): Boolean = startYear <= year && year <= endYear

  /** `YYYY-YYYY`, the configuration syntax. */
  def render: String = s"$startYear-$endYear"

object YearWindow:
  /** Value returned alongside a recorded problem; never reaches a caller (the section fails). */
  private[config] val Placeholder: YearWindow = new YearWindow(0, 0)

  def of(startYear: Int, endYear: Int): Either[String, YearWindow] =
    if startYear > endYear then Left(s"window start year $startYear exceeds end year $endYear")
    else Right(new YearWindow(startYear, endYear))

  /** Rules for a series of analysis windows, each already satisfying start <= end:
    *
    *   - sorted by start year (ascending)
    *   - non-overlapping: each window ends before the next one starts
    *   - each window lies inside `within` (the DBLP subset years), when given
    *
    * Returns every violation, in order; empty means valid. Gaps between windows are allowed.
    */
  def seriesViolations(windows: Vector[YearWindow], within: Option[YearWindow]): Vector[String] =
    val order = windows
      .zip(windows.drop(1))
      .flatMap { (a, b) =>
        if b.startYear < a.startYear then
          Vector(s"windows must be sorted by start year: ${a.render} precedes ${b.render}")
        else if b.startYear <= a.endYear then Vector(s"windows ${a.render} and ${b.render} overlap")
        else Vector.empty
      }
    val outside = within.toVector.flatMap { range =>
      windows
        .filterNot(range.contains)
        .map(w => s"window ${w.render} lies outside the DBLP subset years ${range.render}")
    }
    order ++ outside
