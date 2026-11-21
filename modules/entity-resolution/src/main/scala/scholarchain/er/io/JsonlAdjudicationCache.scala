package scholarchain.er.io

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scholarchain.er.adjudication.{AdjudicationCache, CachedAdjudication}
import upickle.default.{read, write}

/** Adjudication cache persisted as JSON Lines, one [[CachedAdjudication]] per line. Loaded once;
  * [[flush]] rewrites the file sorted by cache key, so its content does not depend on call
  * order. Local to one run; not shared between processes.
  */
final class JsonlAdjudicationCache(path: Path) extends AdjudicationCache:
  private val entries: scala.collection.mutable.Map[String, CachedAdjudication] =
    scala.collection.mutable.Map.from(
      if Files.isRegularFile(path) then
        Files
          .readAllLines(path, StandardCharsets.UTF_8)
          .asScala
          .filter(_.trim.nonEmpty)
          .map(l => read[CachedAdjudication](l))
          .map(e => e.cacheKey -> e)
      else Nil
    )

  def get(cacheKey: String): Option[CachedAdjudication] = entries.get(cacheKey)

  def put(entry: CachedAdjudication): Unit =
    if !entries.contains(entry.cacheKey) then entries.update(entry.cacheKey, entry)

  def size: Int = entries.size

  def flush(): Unit =
    Option(path.getParent).foreach(Files.createDirectories(_))
    val text = entries.values.toVector.sortBy(_.cacheKey).map(write(_)).mkString("", "\n", "\n")
    Files.write(path, (if entries.isEmpty then "" else text).getBytes(StandardCharsets.UTF_8))
