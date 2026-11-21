package scholarchain.hadoop.io

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import scala.collection.immutable.TreeMap
import scholarchain.core.json.WireCodec
import scholarchain.core.model.{Provenance, RejectRecord, SourceType}
import scholarchain.core.schema.SchemaVersions
import scholarchain.core.validation.RecordContract
import upickle.default.ReadWriter

/** Helpers to build provenance, rejects and validated JSON lines with the core codecs. */
object Records:

  def provenance(
      dataset: SourceType,
      sourceRecordId: String,
      snapshot: String,
      runId: String,
      derivedFrom: Vector[String] = Vector.empty,
      policyVersion: Option[String] = None
  ): Provenance =
    Provenance(
      schemaVersion = SchemaVersions.Provenance,
      sourceDataset = dataset,
      sourceRecordId = sourceRecordId,
      snapshot = snapshot,
      runId = runId,
      policyVersion = policyVersion,
      modelVersion = None,
      promptVersion = None,
      derivedFrom = derivedFrom.distinct.sorted
    )

  /** A reject record; the excerpt is truncated to the wire bound. */
  def reject(
      dataset: SourceType,
      sourceRecordRef: String,
      snapshot: String,
      runId: String,
      stage: String,
      reasonCode: String,
      rawExcerpt: String
  ): RejectRecord =
    val ref = Option(sourceRecordRef).map(_.trim).filter(_.nonEmpty).getOrElse("<unknown>")
    RejectRecord(
      schemaVersion = SchemaVersions.RejectRecord,
      source = dataset,
      sourceRecordRef = ref,
      stage = stage,
      reasonCode = reasonCode,
      rawExcerpt = RejectRecord.truncateExcerpt(rawExcerpt),
      runId = runId,
      provenance = provenance(dataset, ref, snapshot, runId)
    )

  /** Validated JSON of a record, or the rendered validation errors. */
  def json[A: ReadWriter: RecordContract](a: A): Either[String, String] =
    WireCodec.encodeValidated(a).left.map(_.map(_.toString).mkString("; "))

  /** Sorted copy of a property map, so its JSON key order is deterministic. */
  def sorted(m: Map[String, String]): Map[String, String] = TreeMap.from(m)

  def sortedDoubles(m: Map[String, Double]): Map[String, Double] = TreeMap.from(m)

  private val EncodingDecl =
    """<\?xml[^>]*?encoding\s*=\s*["']([A-Za-z0-9._\-]+)["']""".r

  /** Charset named by an XML declaration in `head`, UTF-8 when absent or unknown. */
  def xmlEncoding(head: Array[Byte], length: Int): Charset =
    val text = new String(head, 0, length, StandardCharsets.ISO_8859_1)
    EncodingDecl
      .findFirstMatchIn(text)
      .flatMap(m => scala.util.Try(Charset.forName(m.group(1))).toOption)
      .getOrElse(StandardCharsets.UTF_8)
