package scholarchain.storage.vectors

import scholarchain.storage.common.*

/** The vector key formula (frozen, `vector-key-v1`):
  *
  * {{{
  *   vectorKey = lowerHex(SHA-256(utf8(entityId | documentType | windowStartYear | windowEndYear | ordinal)))
  * }}}
  *
  * The five fields are joined with the single character `|`. The text, its hash and the run are
  * deliberately NOT part of the key, so one logical unit (entity, document type, window, ordinal)
  * keeps one key when its text or embedding model changes. Because `|` is the separator, a
  * component containing `|` (or a control character) is refused rather than escaped: the encoding
  * stays injective and trivially reproducible outside Scala.
  */
object VectorKeys:
  val FormulaVersion = "vector-key-v1"

  private def component(name: String, v: String): Result[String] =
    if v == null || v.trim.isEmpty then Left(StorageError.InvalidRecord(s"$name must not be blank"))
    else if v.exists(c => c == '|' || c.isControl) then
      Left(StorageError.InvalidRecord(s"$name must not contain '|' or control characters"))
    else Right(v)

  def of(
      entityId: String,
      documentType: String,
      windowStartYear: Int,
      windowEndYear: Int,
      ordinal: Int
  ): Result[String] =
    for
      e <- component("entityId", entityId)
      d <- component("documentType", documentType)
      _ <-
        if windowStartYear <= windowEndYear then Right(())
        else Left(StorageError.InvalidRecord(s"window $windowStartYear-$windowEndYear is inverted"))
      _ <-
        if ordinal >= 0 then Right(())
        else Left(StorageError.InvalidRecord(s"ordinal $ordinal < 0"))
    yield Sha256.hex(
      s"$e|$d|$windowStartYear|$windowEndYear|$ordinal".getBytes(
        java.nio.charset.StandardCharsets.UTF_8
      )
    )
