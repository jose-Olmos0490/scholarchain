package scholarchain.core.json

import scala.util.control.NonFatal
import scholarchain.core.schema.SchemaVersions
import scholarchain.core.validation.{RecordContract, ValidationError}
import scholarchain.core.validation.ValidationError.*
import upickle.default.*

/** Encoding and validated decoding of ScholarChain wire records (uPickle JSON).
  *
  * Decoding order:
  *   1. parse JSON; the value must be an object
  *   2. reject an unsupported or missing `schemaVersion` before reading fields, so a future
  *      version is reported as such rather than as a field error
  *   3. reject a missing or null `provenance` (top level and inside nested record arrays)
  *   4. decode fields
  *   5. run the record contract's validation
  */
object WireCodec:

  def encode[A: ReadWriter](a: A): String = write(a)

  /** Encodes only a record that passes validation. */
  def encodeValidated[A: ReadWriter: RecordContract](
      a: A
  ): Either[Vector[ValidationError], String] =
    validate(a).map(write(_))

  def validate[A](a: A)(using c: RecordContract[A]): Either[Vector[ValidationError], A] =
    val errors = c.violations(a, "")
    if errors.isEmpty then Right(a) else Left(errors)

  def decode[A: ReadWriter](json: String)(using
      c: RecordContract[A]
  ): Either[Vector[ValidationError], A] =
    val parsed =
      try Right(ujson.read(json))
      catch case NonFatal(e) => Left(Vector(DecodeFailure("", s"invalid JSON: ${e.getMessage}")))
    parsed.flatMap(decodeValue[A])

  def decodeValue[A: ReadWriter](
      value: ujson.Value
  )(using c: RecordContract[A]): Either[Vector[ValidationError], A] =
    value match
      case obj: ujson.Obj =>
        val pre = preChecks(obj, c)
        if pre.nonEmpty then Left(pre)
        else
          val decoded =
            try Right(read[A](obj))
            catch
              case NonFatal(e) =>
                Left(Vector(DecodeFailure("", Option(e.getMessage).getOrElse(e.toString))))
          decoded.flatMap(validate(_))
      case other =>
        Left(
          Vector(DecodeFailure("", s"expected a JSON object, got ${other.getClass.getSimpleName}"))
        )

  private def preChecks(obj: ujson.Obj, c: RecordContract[?]): Vector[ValidationError] =
    val supported = SchemaVersions.supportedFor(c.recordType)
    val schema = obj.value.get("schemaVersion") match
      case Some(ujson.Str(v)) if supported.contains(v) => Vector.empty
      case found =>
        val shown = found match
          case Some(ujson.Str(v)) => v
          case Some(v) => v.render()
          case None => "<missing>"
        Vector(
          UnsupportedSchemaVersion("schemaVersion", c.recordType, shown, supported.toVector.sorted)
        )

    def provenanceOf(o: ujson.Obj, path: String): Vector[ValidationError] =
      o.value.get("provenance") match
        case Some(_: ujson.Obj) => Vector.empty
        case _ => Vector(MissingProvenance(path))

    val top = if c.requiresProvenance then provenanceOf(obj, "provenance") else Vector.empty
    val nested = c.nestedRecordArrays.flatMap { field =>
      obj.value.get(field) match
        case Some(arr: ujson.Arr) =>
          arr.value.toVector.zipWithIndex.flatMap {
            case (o: ujson.Obj, i) => provenanceOf(o, s"$field[$i].provenance")
            case (_, i) => Vector(DecodeFailure(s"$field[$i]", "expected a JSON object"))
          }
        case _ => Vector.empty
    }
    schema ++ top ++ nested
