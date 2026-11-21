package scholarchain.hadoop.sources

import scholarchain.core.validation.RecordContract
import scholarchain.hadoop.io.Records
import scholarchain.hadoop.text.Normalize
import upickle.default.ReadWriter

/** Output of a source normalizer for one input record: validated table rows and rejects. */
enum Emitted:
  /** A validated JSON row for `table` (of the input's dataset and snapshot), keyed by `id`. */
  case Row(table: String, id: String, json: String)

  /** An input (or a derived row) that could not be accepted. */
  case Reject(reasonCode: String, ref: String, detail: String)

/** Collects [[Emitted]] values; a row that fails its core contract becomes a `SCHEMA_FAILURE`
  * reject instead of being written.
  */
final class EmitBuffer:
  private val b = Vector.newBuilder[Emitted]

  def row[A: ReadWriter: RecordContract](table: String, id: String, a: A): Unit =
    Records.json(a) match
      case Right(json) => b += Emitted.Row(table, id, json)
      case Left(errors) => b += Emitted.Reject("SCHEMA_FAILURE", id, errors)

  def reject(code: String, ref: String, detail: String): Unit =
    b += Emitted.Reject(code, ref, detail)

  def result(): Vector[Emitted] = b.result()

/** Small, total accessors over `ujson.Value`. */
object Json:
  def obj(v: ujson.Value, key: String): Option[ujson.Value] = v match
    case o: ujson.Obj => o.value.get(key).filterNot(_ == ujson.Null)
    case _ => None

  /** A string value; `None` when absent, not a string, or blank per `Normalize.isBlank`
    * (whitespace, no-break spaces, controls and Cf characters only).
    */
  def str(v: ujson.Value, key: String): Option[String] = obj(v, key).collect {
    case ujson.Str(s) if !Normalize.isBlank(s) => s
  }

  def int(v: ujson.Value, key: String): Option[Int] = obj(v, key).collect {
    case ujson.Num(n) if n.isWhole && n >= Int.MinValue && n <= Int.MaxValue => n.toInt
  }

  def num(v: ujson.Value, key: String): Option[Double] = obj(v, key).collect {
    case ujson.Num(n) if !n.isNaN && !n.isInfinite => n
  }

  def bool(v: ujson.Value, key: String): Option[Boolean] = obj(v, key).collect {
    case ujson.Bool(b) => b
  }

  def arr(v: ujson.Value, key: String): Vector[ujson.Value] = obj(v, key) match
    case Some(a: ujson.Arr) => a.value.toVector
    case _ => Vector.empty

  /** The non-blank (per `Normalize.isBlank`) string elements of an array value. */
  def strs(v: ujson.Value, key: String): Vector[String] = arr(v, key).collect {
    case ujson.Str(s) if !Normalize.isBlank(s) => s
  }

  /** The first candidate, in order, that `normalize` accepts. Used wherever a field has fallback
    * sources, so a present but invalid first candidate never hides a valid later one.
    */
  def firstValid[A](candidates: Option[String]*)(normalize: String => Option[A]): Option[A] =
    candidates.iterator.flatten.flatMap(normalize).nextOption()

  /** True when a value carries content under the one blankness rule (`Normalize.isBlank`): a
    * string that is not blank, any number or boolean, an array with at least one present element,
    * or an object with at least one entry whose key is not blank and whose value is present (so
    * an abstract inverted index whose only words are invisible is absent). `null` is absent.
    */
  def present(v: ujson.Value): Boolean = v match
    case ujson.Str(s) => !Normalize.isBlank(s)
    case o: ujson.Obj => o.value.exists((k, x) => !Normalize.isBlank(k) && present(x))
    case a: ujson.Arr => a.value.exists(present)
    case ujson.Null => false
    case _ => true

  /** True when the key is present with a [[present]] value. */
  def nonEmpty(v: ujson.Value, key: String): Boolean = obj(v, key).exists(present)

  /** Scalar rendered as a property string (numbers without a trailing `.0` when whole). A blank
    * string (per `Normalize.isBlank`) is `None`, the same rule as [[str]], so no property path
    * stores an invisible-only value.
    */
  def scalar(v: ujson.Value): Option[String] = v match
    case ujson.Str(s) if !Normalize.isBlank(s) => Some(s)
    case ujson.Num(n) =>
      Some(if n.isWhole && math.abs(n) < 1e15 then n.toLong.toString else n.toString)
    case ujson.Bool(b) => Some(b.toString)
    case _ => None
