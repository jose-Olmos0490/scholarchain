package scholarchain.rag.agents

import scholarchain.core.config.RetrievalConfig
import scholarchain.core.ids.StableIds
import scholarchain.core.model.Provenance
import scholarchain.core.validation.RecordContract
import scholarchain.rag.context.SemanticContextItem
import scholarchain.rag.embed.Embedder
import scholarchain.rag.render.TextSanitizer
import scholarchain.storage.objects.{ObjectArea, ObjectLayout, ObjectRef, ObjectStore}
import scholarchain.storage.vectors.*

object SemanticContext:
  /** The label of every item this stage returns. Semantic context is never a graph fact. */
  val Label = "SEMANTIC_CONTEXT"

/** A request for semantic context. `text` is untrusted: it is only embedded, never executed.
  * Filters use filterable metadata fields only.
  */
final case class SemanticQuery(
    text: String,
    windowStartYear: Option[Int],
    windowEndYear: Option[Int],
    entityIds: Vector[String],
    topicIds: Vector[String],
    limit: Int
)

/** One retrieved item. `provenance` is the stored document's own provenance. `label` is always
  * [[SemanticContext.Label]]; the item is not authoritative.
  */
final case class SemanticHit(
    item: SemanticContextItem,
    chunkId: String,
    provenance: Provenance,
    label: String = SemanticContext.Label
)

/** A hit that was not used, with the reason. A refused hit contributes nothing to the answer. */
final case class RefusedHit(vectorKey: String, reason: String)

final case class SemanticResult(hits: Vector[SemanticHit], refused: Vector[RefusedHit])

/** Semantic context from the vector index and the stored texts.
  *
  * The text of a hit is read from the object store through `textS3Uri` only after the URI is
  * resolved inside the configured layout: a URI outside the configured buckets, outside the RAG
  * area, or with an unsafe path is refused (retrieved metadata is untrusted). The stored text must
  * hash to the metadata's `textHash`. Excerpts are sanitized and capped. Similarity is returned as
  * `score = 1 - distance` and says nothing about any graph fact.
  */
final class SemanticContextAgent(
    index: VectorIndex,
    objects: ObjectStore,
    layout: ObjectLayout,
    embedder: Embedder,
    indexNames: Vector[String],
    retrieval: RetrievalConfig,
    maxExcerptCodePoints: Int,
    maxQueryCodePoints: Int
):
  private val documents = new DocumentStore(objects, layout)

  private def filterOf(q: SemanticQuery): Option[VectorFilter] =
    val parts = Vector(
      q.windowStartYear.map(y => VectorFilter.Eq("windowStartYear", ujson.Num(y))),
      q.windowEndYear.map(y => VectorFilter.Eq("windowEndYear", ujson.Num(y))),
      Option.when(q.entityIds.nonEmpty)(
        VectorFilter.In("entityId", q.entityIds.map(id => ujson.Str(id): ujson.Value))
      ),
      Option.when(q.topicIds.nonEmpty)(
        VectorFilter.In(
          "topicIds",
          q.topicIds.map(id => ujson.Arr(ujson.Str(id)): ujson.Value)
        )
      )
    ).flatten
    parts match
      case Vector() => None
      case Vector(one) => Some(one)
      case many => Some(VectorFilter.And(many))

  def retrieve(q: SemanticQuery): SResult[SemanticResult] =
    val limit = math.min(q.limit, retrieval.vectorLimit)
    if q.limit < 0 then Left(StageFailure.Rejected("LIMIT_INVALID", "limit must not be negative"))
    else if limit == 0 then Right(SemanticResult(Vector.empty, Vector.empty))
    else
      val text = TextSanitizer.clean(q.text, maxQueryCodePoints)
      if text.isEmpty then
        Left(StageFailure.Rejected("QUERY_EMPTY", "there is no text to search with"))
      else
        for
          vector <- embed(text)
          matches <- queryAll(vector, filterOf(q), limit)
        yield assemble(q, matches)

  private def embed(text: String): SResult[Vector[Float]] =
    try Right(embedder.embed(Seq(text)).head.toVector)
    catch
      case scala.util.control.NonFatal(_) =>
        Left(StageFailure.Failed("embedder", "the query could not be embedded"))

  private def queryAll(
      vector: Vector[Float],
      filter: Option[VectorFilter],
      limit: Int
  ): SResult[Vector[(String, VectorMatch)]] =
    indexNames
      .foldLeft[SResult[Vector[(String, VectorMatch)]]](Right(Vector.empty)) { (acc, name) =>
        acc.flatMap { done =>
          index
            .query(name, VectorQuery(vector, limit, filter))
            .left
            .map(StageFailure.fromStorage("vectorIndex.query", _))
            .map(ms => done ++ ms.map(name -> _))
        }
      }
      .map(
        _.filterNot((_, m) => m.distance.isNaN)
          .sortBy((n, m) => (m.distance, n, m.key))
          .take(limit)
      )

  private def assemble(q: SemanticQuery, matches: Vector[(String, VectorMatch)]): SemanticResult =
    val hits = Vector.newBuilder[SemanticHit]
    val refused = Vector.newBuilder[RefusedHit]
    matches.foreach { (indexName, m) =>
      use(q, indexName, m) match
        case Right(h) => hits += h
        case Left(why) => refused += RefusedHit(m.key, why)
    }
    SemanticResult(hits.result(), refused.result())

  private def use(
      q: SemanticQuery,
      indexName: String,
      m: VectorMatch
  ): Either[String, SemanticHit] =
    for
      meta <- m.metadata.toRight("no metadata")
      _ <- Either.cond(
        q.windowStartYear.forall(_ == meta.windowStartYear) &&
          q.windowEndYear.forall(_ == meta.windowEndYear) &&
          (q.entityIds.isEmpty || q.entityIds.contains(meta.entityId)),
        (),
        "the hit does not satisfy the request's filter"
      )
      ref <- ObjectRef
        .fromS3Uri(layout, meta.textS3Uri)
        .left
        .map(_ => "textS3Uri is outside the configured layout")
      _ <- Either.cond(ref.area == ObjectArea.Rag, (), "textS3Uri is not in the RAG area")
      doc <- documents
        .read(ref, meta.textHash)
        .left
        .map(e => s"stored text unusable (${e.getClass.getSimpleName.stripSuffix("$")})")
      _ <- Either.cond(
        doc.entityId == meta.entityId && doc.documentType == meta.documentType,
        (),
        "stored text belongs to another entity"
      )
      _ <- Either.cond(
        RecordContract[Provenance].violations(doc.provenance, "provenance").isEmpty,
        (),
        "stored text has no valid provenance"
      )
      chunkId <- StableIds.chunkId(
        meta.entityId,
        indexName,
        meta.documentType,
        doc.ordinal,
        meta.textHash
      )
      item <- SemanticContextItem
        .of(
          indexName,
          meta.entityId,
          meta.documentType,
          meta.windowStartYear,
          meta.windowEndYear,
          1.0 - m.distance,
          meta.textS3Uri,
          meta.textHash,
          doc.text,
          chunkId,
          doc.provenance.runId,
          maxExcerptCodePoints
        )
        .left
        .map(_.message)
    yield SemanticHit(item, chunkId, doc.provenance)
