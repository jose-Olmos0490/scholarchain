package scholarchain.rag.context

import scholarchain.core.ids.StableIds
import scholarchain.core.model.EvidenceType
import scholarchain.rag.common.*
import scholarchain.rag.render.TextSanitizer
import upickle.default.*

/** One retrieved piece of semantic context handed to the synthesis agent.
  *
  * Semantic context is never authoritative: it carries no collaboration-metric fields (no counts,
  * paths, components, communities or bridge scores), so those can only come from graph or metric
  * stores. `excerpt` is untrusted data, sanitized and capped, never an instruction. `score` is the
  * vector distance/similarity reported by the index and says nothing about any graph fact.
  */
final case class SemanticContextItem(
    evidenceId: String,
    indexName: String,
    entityId: String,
    documentType: String,
    windowStartYear: Int,
    windowEndYear: Int,
    score: Double,
    textS3Uri: String,
    textHash: String,
    excerpt: String,
    authoritative: Boolean = false
) derives ReadWriter:
  require(!authoritative, "semantic context is never authoritative")

object SemanticContextItem:
  val SchemaVersion = "semantic-context-item-v1"

  /** The evidence type of a semantic document type. */
  def evidenceType(documentType: String): EvidenceType = documentType match
    case "researcher-profile" => EvidenceType.RESEARCHER_PROFILE
    case "collaboration-community" => EvidenceType.COMMUNITY_SUMMARY
    case "collaboration-path" => EvidenceType.PATH_SUMMARY
    case "topic-institution-summary" => EvidenceType.TOPIC_INSTITUTION_SUMMARY
    case _ => EvidenceType.TEXT_CHUNK

  /** Builds an item from a retrieval hit. `chunkId` is the stored chunk's ID; the evidence ID is
    * `StableIds.evidenceId(<semantic evidence type>, [chunkId], runId)`.
    */
  def of(
      indexName: String,
      entityId: String,
      documentType: String,
      windowStartYear: Int,
      windowEndYear: Int,
      score: Double,
      textS3Uri: String,
      textHash: String,
      text: String,
      chunkId: String,
      runId: String,
      maxExcerptCodePoints: Int
  ): RResult[SemanticContextItem] =
    StableIds
      .evidenceId(evidenceType(documentType), Seq(chunkId), runId)
      .left
      .map(m => RagProblem("INVALID_EVIDENCE_ID", m))
      .map { eid =>
        SemanticContextItem(
          eid,
          indexName,
          entityId,
          documentType,
          windowStartYear,
          windowEndYear,
          score,
          textS3Uri,
          textHash,
          TextSanitizer.clean(text, maxExcerptCodePoints)
        )
      }
