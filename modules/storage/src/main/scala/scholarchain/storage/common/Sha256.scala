package scholarchain.storage.common

import java.io.InputStream
import java.nio.file.{Files, Path}
import java.security.MessageDigest

/** SHA-256 helpers. The wire form is `sha256:<64 lower-case hex>` (core `LoadFile.checksum`). */
object Sha256:
  val Prefix = "sha256:"

  def hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  def of(bytes: Array[Byte]): String = Prefix + hex(bytes)

  def of(text: String): String = of(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))

  /** Streams the input; never holds the content in memory. */
  def of(in: InputStream): String =
    val md = MessageDigest.getInstance("SHA-256")
    val buf = new Array[Byte](1 << 16)
    var n = in.read(buf)
    while n >= 0 do
      if n > 0 then md.update(buf, 0, n)
      n = in.read(buf)
    Prefix + md.digest().map(b => f"${b & 0xff}%02x").mkString

  def ofFile(path: Path): String =
    val in = Files.newInputStream(path)
    try of(in)
    finally in.close()

  /** Base64 of the raw digest, the form S3 expects in `x-amz-checksum-sha256`. */
  def base64(prefixed: String): String =
    val h = prefixed.stripPrefix(Prefix)
    java.util.Base64.getEncoder
      .encodeToString(h.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray)

  def isWellFormed(s: String): Boolean = s != null && s.matches("sha256:[0-9a-f]{64}")
