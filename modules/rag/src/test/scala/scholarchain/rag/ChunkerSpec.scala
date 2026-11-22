package scholarchain.rag

import scholarchain.core.ids.StableIds
import scholarchain.rag.chunk.*
import RagFixtures.*

class ChunkerSpec extends RagSuite:
  private val cfg = ChunkingConfig(maxTokens = 20, chunkTokens = 10, overlapTokens = 3)
  private val entity = researcher(1)
  private def words(n: Int) = (1 to n).map(i => s"w$i").mkString(" ")
  private def run(text: String, c: ChunkingConfig = cfg) =
    good(Chunker.chunk(c, entity, "researcher-profile-tmpl-v1/m1", "researcher-profile", text))

  test("text within the token limit is one chunk, unchanged") {
    val t = "line one\nline two  with   spacing\n"
    val cs = run(t)
    cs.map(_.ordinal) shouldBe Vector(0)
    cs.head.text shouldBe t
  }

  test(
    "longer text is split into fixed-size windows with overlap; the last window ends at the last token"
  ) {
    val cs = run(words(25))
    cs.map(c => Chunker.tokenCount(c.text)) shouldBe Vector(10, 10, 10, 4)
    cs.map(_.text.split(" ").head) shouldBe Vector("w1", "w8", "w15", "w22")
    cs.map(_.text.split(" ").last) shouldBe Vector("w10", "w17", "w24", "w25")
    // consecutive chunks share exactly `overlapTokens` tokens
    cs.sliding(2).take(2).foreach { p =>
      p(0).text.split(" ").takeRight(3).toVector shouldBe p(1).text.split(" ").take(3).toVector
    }
    cs.map(_.ordinal) shouldBe Vector(0, 1, 2, 3)
  }

  test("an exactly-fitting text produces no empty or duplicate trailing chunk") {
    run(words(20)).size shouldBe 1
    val cs = run(words(24)) // windows 1-10, 8-17, 15-24
    cs.map(_.text.split(" ").last) shouldBe Vector("w10", "w17", "w24")
  }

  test("chunk text is an exact substring of the source (newlines inside a window are kept)") {
    val t = (1 to 30).map(i => s"w$i").mkString("\n")
    run(t).foreach(c => t should include(c.text))
    run(t).head.text should include("\n")
  }

  test(
    "chunkId = StableIds.chunkId(entityId, sourceVersion, documentType, ordinal, textHash) and is deterministic"
  ) {
    val cs = run(words(25))
    cs.foreach { c =>
      c.chunkId shouldBe ok(
        StableIds.chunkId(
          entity,
          "researcher-profile-tmpl-v1/m1",
          "researcher-profile",
          c.ordinal,
          c.textHash
        )
      )
    }
    run(words(25)) shouldBe cs
    cs.map(_.chunkId).distinct.size shouldBe cs.size
    // a different source version gives different IDs for the same text
    good(Chunker.chunk(cfg, entity, "other-version", "researcher-profile", words(25)))
      .map(_.chunkId) should not be cs.map(_.chunkId)
  }

  test("blank text and a bad entity ID are refused") {
    bad(Chunker.chunk(cfg, entity, "v", "t", "  \n ")).code shouldBe "EMPTY_TEXT"
    bad(Chunker.chunk(cfg, "not-an-id", "v", "t", "text")).code shouldBe "INVALID_CHUNK_ID"
  }

  test("chunking limits come from configuration and are validated") {
    val env = Map(
      "SCHOLARCHAIN_RAG_MAX_TOKENS" -> "400",
      "SCHOLARCHAIN_RAG_CHUNK_TOKENS" -> "200",
      "SCHOLARCHAIN_RAG_CHUNK_OVERLAP_TOKENS" -> "40"
    )
    good(ChunkingConfig.load(env)) shouldBe ChunkingConfig(400, 200, 40)
    bad(ChunkingConfig.load(Map.empty)).detail should include(
      "SCHOLARCHAIN_RAG_MAX_TOKENS is required"
    )
    bad(
      ChunkingConfig.load(env.updated("SCHOLARCHAIN_RAG_CHUNK_OVERLAP_TOKENS", "200"))
    ).detail should include("must be <")
    bad(
      ChunkingConfig.load(env.updated("SCHOLARCHAIN_RAG_CHUNK_TOKENS", "500"))
    ).detail should include("must be <=")
  }
