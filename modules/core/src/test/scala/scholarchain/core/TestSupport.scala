package scholarchain.core

import java.nio.charset.StandardCharsets
import scholarchain.core.json.WireCodec
import scholarchain.core.validation.{RecordContract, ValidationError}
import upickle.default.{ReadWriter, writeJs}

object TestSupport:
  def goldenText(file: String): String =
    val in = getClass.getResourceAsStream(s"/golden/v1/$file")
    require(in != null, s"missing golden file $file")
    try new String(in.readAllBytes(), StandardCharsets.UTF_8)
    finally in.close()

  def goldenJson(file: String): ujson.Value = ujson.read(goldenText(file))

  def encodeJson[A](c: GoldenCase[A]): ujson.Value = writeJs(c.value)(using c.rw)

  def decodeAs[A](c: GoldenCase[A], json: String): Either[Vector[ValidationError], A] =
    given ReadWriter[A] = c.rw
    given RecordContract[A] = c.contract
    WireCodec.decode[A](json)

  def violations[A](c: GoldenCase[A]): Vector[ValidationError] =
    c.contract.violations(c.value, "")

  def errorsOf[A](a: A)(using c: RecordContract[A]): Vector[ValidationError] =
    c.violations(a, "")

  /** Returns a copy of the JSON object with `f` applied to its top-level map. */
  def editObj(json: ujson.Value)(f: collection.mutable.Map[String, ujson.Value] => Unit): String =
    val copy = ujson.read(json.render())
    f(copy.obj)
    copy.render()
