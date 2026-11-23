package scholarchain.rag.llm

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scholarchain.rag.agents.{SResult, StageFailure}
import scholarchain.rag.common.Hashing

/** A versioned prompt. `sha256` is the digest of the file bytes and is checked against the
  * manifest, so a prompt cannot change without a new version.
  */
final case class Prompt(version: String, text: String, sha256: String)

/** The prompts and JSON schemas under `prompts/`, loaded through `prompts/manifest.json`. Every
  * file's SHA-256 must equal the manifest entry, and a file outside the root is refused.
  */
final class PromptRepository private (
    prompts: Map[String, Prompt],
    schemas: Map[String, JsonSchema]
):
  def prompt(version: String): SResult[Prompt] =
    prompts.get(version).toRight(StageFailure.NotFound(s"prompt version $version"))

  def schema(version: String): SResult[JsonSchema] =
    schemas.get(version).toRight(StageFailure.NotFound(s"schema version $version"))

  def promptVersions: Set[String] = prompts.keySet
  def schemaVersions: Set[String] = schemas.keySet

object PromptRepository:
  /** The nearest `prompts/` directory with a manifest, searching `start` and its parents. */
  def locate(start: Path): Option[Path] =
    Iterator
      .iterate(Option(start.toAbsolutePath.normalize))(_.flatMap(p => Option(p.getParent)))
      .takeWhile(_.isDefined)
      .flatten
      .map(_.resolve("prompts"))
      .find(p => Files.isRegularFile(p.resolve("manifest.json")))

  private def entries(m: ujson.Value, key: String): SResult[Vector[(String, String, String)]] =
    m.obj.get(key) match
      case Some(ujson.Arr(items)) =>
        items.toVector.foldLeft[SResult[Vector[(String, String, String)]]](Right(Vector.empty)) {
          (acc, e) =>
            acc.flatMap { done =>
              (for
                v <- e.obj.get("version").collect { case ujson.Str(s) => s }
                f <- e.obj.get("file").collect { case ujson.Str(s) => s }
                h <- e.obj.get("sha256").collect { case ujson.Str(s) => s }
              yield done :+ ((v, f, h))).toRight(
                StageFailure.Rejected("MANIFEST_INVALID", s"an entry of '$key' is incomplete")
              )
            }
        }
      case _ => Left(StageFailure.Rejected("MANIFEST_INVALID", s"'$key' is missing"))

  private def read(root: Path, file: String, sha: String): SResult[String] =
    val p = root.resolve(file).normalize
    if !p.startsWith(root) then
      Left(StageFailure.Rejected("MANIFEST_INVALID", s"$file is outside the prompts root"))
    else if !Files.isRegularFile(p) then Left(StageFailure.NotFound(s"prompt file $file"))
    else
      val bytes = Files.readAllBytes(p)
      val actual = Hashing.sha256Hex(new String(bytes, StandardCharsets.UTF_8))
      if actual != sha then
        Left(
          StageFailure.Rejected(
            "PROMPT_CHANGED",
            s"$file has sha256 $actual, the manifest says $sha: publish a new version instead"
          )
        )
      else Right(new String(bytes, StandardCharsets.UTF_8))

  def load(rootDir: Path): SResult[PromptRepository] =
    val root = rootDir.toAbsolutePath.normalize
    try
      val manifest = ujson.read(Files.readString(root.resolve("manifest.json")))
      for
        ps <- entries(manifest, "prompts")
        ss <- entries(manifest, "schemas")
        loadedPrompts <- ps.foldLeft[SResult[Map[String, Prompt]]](Right(Map.empty)) {
          case (acc, (v, f, h)) =>
            for
              m <- acc
              text <- read(root, f, h)
              _ <-
                if m.contains(v) then
                  Left(StageFailure.Rejected("MANIFEST_INVALID", s"duplicate prompt $v"))
                else Right(())
            yield m + (v -> Prompt(v, text, h))
        }
        loadedSchemas <- ss.foldLeft[SResult[Map[String, JsonSchema]]](Right(Map.empty)) {
          case (acc, (v, f, h)) =>
            for
              m <- acc
              text <- read(root, f, h)
              s <- JsonSchema.parse(v, text)
              _ <-
                if m.contains(v) then
                  Left(StageFailure.Rejected("MANIFEST_INVALID", s"duplicate schema $v"))
                else Right(())
            yield m + (v -> s)
        }
      yield new PromptRepository(loadedPrompts, loadedSchemas)
    catch
      case scala.util.control.NonFatal(_) =>
        Left(StageFailure.Rejected("MANIFEST_INVALID", "prompts/manifest.json cannot be read"))
