package scholarchain.services.evidence

import scholarchain.core.ids.{IdKind, StableId}
import scholarchain.core.model.EvidenceItem
import scholarchain.rag.render.TextSanitizer
import scholarchain.services.config.EvidenceSettings
import scholarchain.services.error.ApiError
import scholarchain.services.resilience.Downstream
import scholarchain.storage.dynamo.EvidenceStore
import upickle.default.writeJs

/** `GET /v1/evidence/{evidenceId}`: one stored evidence item with its provenance. The excerpt is
  * untrusted data: it is cleaned, cut to a configured length and labeled as such.
  */
final class EvidenceService(
    store: EvidenceStore,
    downstream: Downstream,
    settings: EvidenceSettings
):
  def get(evidenceId: String): Either[ApiError, ujson.Obj] =
    if StableId.parseAs(evidenceId, Set(IdKind.Evidence)).isLeft then
      Left(ApiError.badRequest("INVALID_ID", "the evidence ID is not a stable evidence ID"))
    else
      downstream.read()(store.getItem(evidenceId)).flatMap {
        case None => Left(ApiError.notFound("the evidence item was not found"))
        case Some(item) => Right(view(item))
      }

  def view(i: EvidenceItem): ujson.Obj =
    val out = ujson.Obj(
      "evidenceId" -> i.evidenceId,
      "evidenceType" -> i.evidenceType.toString,
      "provenance" -> writeJs(i.provenance),
      "sourceRecord" -> ujson.Obj(
        "referencedIds" -> ujson.Arr.from(i.referencedIds.map(ujson.Str(_))),
        "workId" -> i.workId.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
        "sourceDataset" -> i.provenance.sourceDataset.toString,
        "sourceRecordId" -> i.provenance.sourceRecordId
      ),
      "sourceUri" -> i.sourceUri
    )
    i.graphPath.foreach(p => out("graphPath") = ujson.Arr.from(p.map(ujson.Str(_))))
    if i.chunkId.isDefined || i.textHash.isDefined then
      out("textPointer") = ujson.Obj(
        "chunkId" -> i.chunkId.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
        "textHash" -> i.textHash.fold[ujson.Value](ujson.Null)(ujson.Str(_))
      )
    i.text.foreach { t =>
      val cleaned = TextSanitizer.clean(t, settings.contentMaxChars)
      out("content") = ujson.Obj(
        "text" -> cleaned,
        "truncated" -> (cleaned != t),
        "trust" -> "untrusted-data"
      )
    }
    i.score.foreach(s => out("retrievalScore") = s)
    out
