package scholarchain.er.run

import java.nio.file.{Files, Path, Paths}
import java.util.jar.JarFile
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal
import scholarchain.er.adjudication.Canonical

/** Content fingerprint of the entity-resolution code (finding F9).
  *
  * `sha256:<hex>` over every class file and resource under `scholarchain/er/` in the classpath
  * entry (directory or jar) that holds the given class, except those under [[ExcludedPrefixes]]:
  * entries sorted by path, each contributing its path and the SHA-256 of its bytes. Any change to
  * the module's decision code, its policy JSON, generic-title list, prompt or schema changes the
  * fingerprint and therefore the run ID. Independent of file timestamps and of where the build
  * sits on disk.
  *
  * The evaluator package (`evaluation/` under [[Prefix]]) is excluded (re-review finding N5): it
  * only reads finished runs and takes no part in any decision, so a change to it must not change
  * the run ID of a decision run. Without the exclusion, adding the evaluator after the r2 run made
  * the committed r2 run directory unreproducible. The evaluator's own evaluation ID uses
  * [[ofModule]], which still covers every entry under [[Prefix]] including the evaluator, so an
  * evaluator change still changes the evaluation ID.
  */
object CodeFingerprint:
  val Prefix = "scholarchain/er/"

  /** Packages under [[Prefix]] that are not decision code and are left out of the fingerprint. */
  val ExcludedPrefixes: Vector[String] = Vector(Prefix + "evaluation/")

  /** True when the classpath entry name `path` is in the decision-code fingerprint. */
  def included(path: String): Boolean =
    path.startsWith(Prefix) && !ExcludedPrefixes.exists(path.startsWith)

  /** True for every entry of the module, the evaluator included ([[ofModule]]). */
  def inModule(path: String): Boolean = path.startsWith(Prefix)

  private def sha(bytes: Array[Byte]): String =
    val d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    d.map(b => f"${b & 0xff}%02x").mkString

  /** Fingerprint of (relative path, bytes) entries. */
  def ofEntries(entries: Vector[(String, Array[Byte])]): String =
    "sha256:" + Canonical.hashFields(
      entries.sortBy(_._1).flatMap((p, b) => Seq(p, sha(b)))*
    )

  private def fromDirectory(
      root: Path,
      keep: String => Boolean
  ): Vector[(String, Array[Byte])] =
    val base = root.resolve(Prefix)
    if !Files.isDirectory(base) then Vector.empty
    else
      Using.resource(Files.walk(base))(
        _.iterator().asScala
          .filter(Files.isRegularFile(_))
          .map(p => root.relativize(p).toString.replace('\\', '/') -> p)
          .filter((rel, _) => keep(rel))
          .map((rel, p) => rel -> Files.readAllBytes(p))
          .toVector
      )

  private def fromJar(path: Path, keep: String => Boolean): Vector[(String, Array[Byte])] =
    Using.resource(new JarFile(path.toFile)) { jar =>
      jar
        .entries()
        .asScala
        .filter(e => !e.isDirectory && keep(e.getName))
        .map(e => e.getName -> Using.resource(jar.getInputStream(e))(_.readAllBytes()))
        .toVector
    }

  /** Decision-code fingerprint of a classpath entry: a class directory or a jar. */
  def ofClasspathEntry(path: Path): Either[String, String] = ofClasspathEntry(path, included)

  /** Fingerprint of the entries of a classpath entry that `keep` selects. */
  def ofClasspathEntry(path: Path, keep: String => Boolean): Either[String, String] =
    try
      val entries =
        if Files.isDirectory(path) then fromDirectory(path, keep) else fromJar(path, keep)
      if entries.isEmpty then Left(s"no $Prefix entries found in $path")
      else Right(ofEntries(entries))
    catch case NonFatal(e) => Left(s"cannot fingerprint code: ${e.getMessage}")

  /** Decision-code fingerprint (evaluator excluded) of the classpath entry holding `anchor`. Feeds
    * the run ID of a decision run.
    */
  def of(anchor: Class[?]): Either[String, String] = locate(anchor, included)

  /** Fingerprint of the whole module, the evaluator included, of the classpath entry holding
    * `anchor`. Feeds the evaluation ID computed by the evaluator.
    */
  def ofModule(anchor: Class[?]): Either[String, String] = locate(anchor, inModule)

  private def locate(anchor: Class[?], keep: String => Boolean): Either[String, String] =
    try
      val location = Option(anchor.getProtectionDomain.getCodeSource).map(_.getLocation)
      location match
        case None => Left(s"cannot locate the code of ${anchor.getName} for the run fingerprint")
        case Some(url) => ofClasspathEntry(Paths.get(url.toURI), keep)
    catch case NonFatal(e) => Left(s"cannot fingerprint code: ${e.getMessage}")
