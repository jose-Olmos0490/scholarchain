package scholarchain.rag.chunk

import scholarchain.core.ids.StableIds
import scholarchain.rag.common.*

/** Token limits. A "token" is a whitespace-delimited word: a cheap, deterministic stand-in for the
  * model tokenizer, so `maxTokens` must be configured with margin below the model's own limit.
  *
  * @param maxTokens
  *   text up to this many tokens is one chunk
  * @param chunkTokens
  *   size of each chunk of longer text (<= maxTokens)
  * @param overlapTokens
  *   tokens shared by consecutive chunks (< chunkTokens)
  */
final case class ChunkingConfig(maxTokens: Int, chunkTokens: Int, overlapTokens: Int)

object ChunkingConfig:
  def load(env: Map[String, String]): RResult[ChunkingConfig] =
    val r = new EnvReader(env)
    val max = r.int("SCHOLARCHAIN_RAG_MAX_TOKENS", 1)
    val size = r.int("SCHOLARCHAIN_RAG_CHUNK_TOKENS", 1)
    val overlap = r.int("SCHOLARCHAIN_RAG_CHUNK_OVERLAP_TOKENS", 0)
    if size > max then
      r.check(
        Some(s"SCHOLARCHAIN_RAG_CHUNK_TOKENS ($size) must be <= SCHOLARCHAIN_RAG_MAX_TOKENS ($max)")
      )
    if overlap >= size then
      r.check(
        Some(
          s"SCHOLARCHAIN_RAG_CHUNK_OVERLAP_TOKENS ($overlap) must be < SCHOLARCHAIN_RAG_CHUNK_TOKENS ($size)"
        )
      )
    r.result(ChunkingConfig(max, size, overlap))

final case class Chunk(ordinal: Int, text: String, textHash: String, chunkId: String)

/** Fixed-size chunking with overlap. Text within `maxTokens` is one chunk (ordinal 0). Longer text
  * is cut into windows of `chunkTokens` tokens that start every `chunkTokens - overlapTokens`
  * tokens; chunk text is the exact substring of the source from the first to the last token of
  * the window, so no character is altered. The last window ends at the last token.
  *
  * `chunkId = StableIds.chunkId(entityId, sourceVersion, documentType, ordinal, textHash)`.
  */
object Chunker:
  private val Token = "\\S+".r

  def tokenCount(text: String): Int = Token.findAllIn(text).size

  def chunk(
      cfg: ChunkingConfig,
      entityId: String,
      sourceVersion: String,
      documentType: String,
      text: String
  ): RResult[Vector[Chunk]] =
    val spans = Token.findAllMatchIn(text).map(m => (m.start, m.end)).toVector
    if spans.isEmpty then Left(RagProblem("EMPTY_TEXT", s"$entityId/$documentType has no text"))
    else
      val pieces: Vector[String] =
        if spans.size <= cfg.maxTokens then Vector(text)
        else
          val step = cfg.chunkTokens - cfg.overlapTokens
          val starts = Iterator.iterate(0)(_ + step).takeWhile(_ < spans.size).toVector
          val kept = starts.takeWhile(s => s == 0 || (s - step) + cfg.chunkTokens < spans.size)
          kept.map { s =>
            val e = math.min(s + cfg.chunkTokens, spans.size) - 1
            text.substring(spans(s)._1, spans(e)._2)
          }
      pieces.zipWithIndex.foldLeft[RResult[Vector[Chunk]]](Right(Vector.empty)) {
        case (acc, (piece, ordinal)) =>
          acc.flatMap { done =>
            val h = Hashing.textHash(piece)
            StableIds
              .chunkId(entityId, sourceVersion, documentType, ordinal, h)
              .left
              .map(m => RagProblem("INVALID_CHUNK_ID", m))
              .map(id => done :+ Chunk(ordinal, piece, h, id))
          }
      }
