package scholarchain.storage.vectors

import java.nio.charset.StandardCharsets
import scholarchain.core.model.Provenance
import scholarchain.storage.common.*
import scholarchain.storage.objects.*
import upickle.default.*

/** An already-rendered document handed to ingestion. Rendering (templates, chunking) and embedding
  * belong to `rag-engineer`; storage only accepts the finished text.
  */
final case class RenderedDocument(
    indexName: String,
    entityId: String,
    documentType: String,
    windowStartYear: Int,
    windowEndYear: Int,
    ordinal: Int,
    text: String,
    topicIds: Vector[String],
    institutionIds: Vector[String],
    sourceDataset: String,
    communityId: Option[String],
    provenance: Provenance
)

/** The object written to ordinary S3: the full text plus provenance. */
final case class StoredDocument(
    schemaVersion: String,
    indexName: String,
    entityId: String,
    documentType: String,
    windowStartYear: Int,
    windowEndYear: Int,
    ordinal: Int,
    text: String,
    textHash: String,
    topicIds: Vector[String],
    institutionIds: Vector[String],
    sourceDataset: String,
    communityId: Option[String],
    provenance: Provenance
) derives ReadWriter

object StoredDocument:
  val Version = "rendered-document-v1"

  def from(d: RenderedDocument): StoredDocument =
    StoredDocument(
      Version,
      d.indexName,
      d.entityId,
      d.documentType,
      d.windowStartYear,
      d.windowEndYear,
      d.ordinal,
      d.text,
      Sha256.of(d.text),
      d.topicIds,
      d.institutionIds,
      d.sourceDataset,
      d.communityId,
      d.provenance
    )

enum DocumentWrite:
  /** The object did not exist and was written. */
  case Created

  /** An object with this content address already held this exact text; nothing was written. */
  case Existing

/** Content-addressed document objects in the RAG bucket:
  * `<indexName>/<documentType>/<entityId>/<hex(textHash)>.json`.
  *
  * The address is a function of the text, so a rewrite is either the same text (a skip) or a
  * different address. The first writer's provenance is kept when a later run re-renders identical
  * text; the vector metadata (`runId`) records the later run.
  */
final class DocumentStore(objects: ObjectStore, layout: ObjectLayout):

  private def segment(name: String, v: String): Result[String] =
    if v == null || v.trim.isEmpty then Left(StorageError.InvalidRecord(s"$name must not be blank"))
    else if v.contains('/') || v == "." || v == ".." || v.exists(_.isControl) then
      Left(StorageError.InvalidRecord(s"$name '$v' is not usable as an S3 key segment"))
    else Right(v)

  def ref(
      indexName: String,
      documentType: String,
      entityId: String,
      textHash: String
  ): Result[ObjectRef] =
    for
      i <- segment("indexName", indexName)
      d <- segment("documentType", documentType)
      e <- segment("entityId", entityId)
      _ <-
        if Sha256.isWellFormed(textHash) then Right(())
        else Left(StorageError.InvalidRecord(s"textHash '$textHash' is not sha256:<64 hex>"))
      r = ObjectRef(ObjectArea.Rag, s"$i/$d/$e/${textHash.stripPrefix(Sha256.Prefix)}.json")
      bytes = layout.fullKey(r).getBytes(StandardCharsets.UTF_8).length
      _ <-
        if bytes <= 1024 then Right(())
        else Left(StorageError.LimitExceeded("S3 object key bytes", 1024, bytes))
    yield r

  def uri(ref: ObjectRef): String = layout.s3Uri(ref)

  /** Parses an existing object and checks that its text hashes to `expectedHash`. */
  def read(ref: ObjectRef, expectedHash: String): Result[StoredDocument] =
    objects.getBytes(ref).flatMap {
      case None => Left(StorageError.NotFound(layout.s3Uri(ref)))
      case Some(bytes) =>
        val parsed =
          try Right(upickle.default.read[StoredDocument](new String(bytes, StandardCharsets.UTF_8)))
          catch
            case e: Exception =>
              Left(
                StorageError
                  .InvalidRecord(s"${layout.s3Uri(ref)} is not a document: ${e.getMessage}")
              )
        parsed.flatMap { d =>
          val actual = Sha256.of(d.text)
          if actual != expectedHash || d.textHash != expectedHash then
            Left(StorageError.ConflictingContent(layout.s3Uri(ref), actual, expectedHash))
          else Right(d)
        }
    }

  def write(doc: RenderedDocument): Result[(ObjectRef, DocumentWrite)] =
    if doc.text == null || doc.text.trim.isEmpty then
      Left(StorageError.InvalidRecord("document text must not be blank"))
    else
      val stored = StoredDocument.from(doc)
      ref(doc.indexName, doc.documentType, doc.entityId, stored.textHash).flatMap { r =>
        objects.head(r).flatMap {
          case Some(_) => read(r, stored.textHash).map(_ => (r, DocumentWrite.Existing))
          case None =>
            val bytes = writeJs(stored).render().getBytes(StandardCharsets.UTF_8)
            objects.putBytes(r, bytes, "application/json") match
              case Right(PutOutcome.Created) => Right((r, DocumentWrite.Created))
              case Right(PutOutcome.Skipped) => Right((r, DocumentWrite.Existing))
              case Left(_: StorageError.ConflictingContent) =>
                read(r, stored.textHash).map(_ => (r, DocumentWrite.Existing))
              case Left(e) => Left(e)
        }
      }

/** One line of the immutable ingestion manifest. */
final case class ManifestItem(
    indexName: String,
    entityId: String,
    documentType: String,
    windowStartYear: Int,
    windowEndYear: Int,
    ordinal: Int,
    textHash: String
) derives ReadWriter

/** The immutable work list of one run. The manifest names documents; their text, topics and
  * provenance are read from ordinary S3 at ingestion time.
  */
final case class IngestionManifest(
    schemaVersion: String,
    runId: String,
    items: Vector[ManifestItem]
) derives ReadWriter

object IngestionManifest:
  val Version = "vector-ingestion-manifest-v1"
  def of(runId: String, items: Vector[ManifestItem]): IngestionManifest =
    IngestionManifest(Version, runId, items)

/** Stores manifests in ordinary S3 at `ingestion-manifests/<runHex>.json`. Objects are immutable:
  * writing a different manifest for the same run is [[StorageError.ConflictingContent]].
  */
final class ManifestStore(objects: ObjectStore, layout: ObjectLayout):
  private def ref(runId: String): Result[ObjectRef] =
    ObjectKeys.runHex(runId).map(h => ObjectRef(ObjectArea.Rag, s"ingestion-manifests/$h.json"))

  /** Returns the object URI and the SHA-256 of the manifest bytes. */
  def write(m: IngestionManifest): Result[(String, String)] =
    for
      r <- ref(m.runId)
      bytes = writeJs(m).render().getBytes(StandardCharsets.UTF_8)
      _ <- objects.putBytes(r, bytes, "application/json")
    yield (layout.s3Uri(r), Sha256.of(bytes))

  def read(runId: String): Result[IngestionManifest] =
    ref(runId).flatMap { r =>
      objects.getBytes(r).flatMap {
        case None => Left(StorageError.NotFound(layout.s3Uri(r)))
        case Some(bytes) =>
          try
            Right(
              upickle.default.read[IngestionManifest](new String(bytes, StandardCharsets.UTF_8))
            )
          catch case e: Exception => Left(StorageError.InvalidManifest(e.getMessage))
      }
    }
