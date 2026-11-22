package scholarchain.storage.dynamo

import scholarchain.core.json.WireEnum
import scholarchain.core.model.*
import scholarchain.storage.common.*
import upickle.default.*

// ---------------------------------------------------------------------------
// Records owned by the storage module (the rest are core records)
// ---------------------------------------------------------------------------

enum LedgerState(val rank: Int):
  case STARTED extends LedgerState(0)
  case SUCCEEDED extends LedgerState(1)
  case FAILED extends LedgerState(1)

object LedgerState:
  given ReadWriter[LedgerState] = WireEnum.readWriter("LedgerState", values.toIndexedSeq)

/** One immutable event of one stage attempt of a run. A stage attempt normally has a STARTED
  * entry and then a SUCCEEDED or FAILED entry; entries are never rewritten.
  *
  * @param sequence
  *   position of the stage in the pipeline (0..99999), assigned by the caller
  * @param recordedAtEpochMillis
  *   supplied by the caller; the store never reads a clock for audit data
  */
final case class RunLedgerEntry(
    schemaVersion: String,
    runId: String,
    sequence: Int,
    stage: String,
    attempt: Int,
    state: LedgerState,
    recordedAtEpochMillis: Long,
    detail: String,
    provenance: Provenance
) derives ReadWriter

/** A cached entity-link adjudication, keyed by its inputs and the versions that produced it. */
final case class LinkAdjudication(
    schemaVersion: String,
    inputHash: String,
    promptVersion: String,
    modelVersion: String,
    policyVersion: String,
    outcome: MatchOutcome,
    confidence: Double,
    reasonCodes: Vector[String],
    provenance: Provenance
) derives ReadWriter

final case class QuerySessionRecord(
    schemaVersion: String,
    queryId: String,
    idempotencyKey: Option[String],
    requestFingerprint: String,
    createdAtEpochSeconds: Long,
    provenance: Provenance
) derives ReadWriter

final case class ArtifactVersion(
    schemaVersion: String,
    name: String,
    version: Long,
    location: String,
    sha256: String,
    contentSchemaVersion: String,
    runId: String,
    provenance: Provenance
) derives ReadWriter

enum CreateOutcome:
  case Created

  /** The key held identical content; nothing was written. */
  case Unchanged

// ---------------------------------------------------------------------------
// Ports, one per table
// ---------------------------------------------------------------------------

/** Table `runLedger`. Access: list every entry of a run in pipeline order.
  * Key: `pk = run#<runId>`, `sk = <sequence:5>#<stage>#<attempt:3>#<stateRank>-<state>`.
  */
trait RunLedger:
  /** Conditional create; an existing key is [[StorageError.AlreadyExists]]. */
  def record(entry: RunLedgerEntry): Result[Unit]
  def list(runId: String): Result[Vector[RunLedgerEntry]]

/** Table `entityLinkCache`. Access: get an adjudication by its four-part key.
  * Key: `pk = link#<inputHash>`, `sk = <promptVersion>#<modelVersion>#<policyVersion>`.
  */
trait EntityLinkCache:
  def put(adjudication: LinkAdjudication): Result[Unit]
  def get(
      inputHash: String,
      promptVersion: String,
      modelVersion: String,
      policyVersion: String
  ): Result[Option[LinkAdjudication]]

/** Table `querySession` (the only table with a TTL). Access: create once per queryId, get by
  * queryId, detect a repeated Idempotency-Key.
  * Keys: session `pk = query#<queryId>, sk = SESSION`; idempotency marker
  * `pk = idem#<key>, sk = QUERY` holding the queryId. Both are created in one transaction.
  */
trait QuerySessions:
  /** [[StorageError.AlreadyExists]] when the queryId exists;
    * [[StorageError.DuplicateIdempotencyKey]] when the Idempotency-Key was used.
    */
  def create(session: QuerySessionRecord): Result[Unit]
  def get(queryId: String): Result[Option[QuerySessionRecord]]

  /** The queryId that used `idempotencyKey`, if any. */
  def queryIdForIdempotencyKey(idempotencyKey: String): Result[Option[String]]

/** Evidence items are immutable and shared; a bundle only references them.
  * Keys: item `pk = evidence#<evidenceId>, sk = ITEM`; membership `pk = query#<queryId>,
  * sk = E#<evidenceId>`; bundle header `pk = query#<queryId>, sk = BUNDLE`.
  */
trait EvidenceStore:
  /** Stores the bundle header, each item and its membership. Items that already exist with
    * identical content are left alone; an existing item with different content is
    * [[StorageError.ConflictingContent]]. The header is a strict create.
    */
  def putBundle(bundle: EvidenceBundle): Result[Unit]
  def getItem(evidenceId: String): Result[Option[EvidenceItem]]

  /** Every evidence item of a query, graph then semantic section order within evidenceId order. */
  def listForQuery(queryId: String): Result[Vector[EvidenceItem]]

/** Table `artifactRegistry`. Access: latest version and a specific version of a named artifact.
  * Key: `pk = artifact#<name>`, `sk = v#<version:12>`; latest is a descending query, limit 1.
  */
trait ArtifactRegistry:
  /** Registers the next version (latest + 1) with a bounded optimistic retry. */
  def register(
      name: String,
      location: String,
      sha256: String,
      contentSchemaVersion: String,
      runId: String,
      provenance: Provenance
  ): Result[ArtifactVersion]
  def latest(name: String): Result[Option[ArtifactVersion]]
  def version(name: String, version: Long): Result[Option[ArtifactVersion]]

/** Table `researcherMetricCache` (optional table). Access: metrics by researcherId and window.
  * Key: `pk = researcher#<researcherId>`, `sk = w#<start:4>-<end:4>`.
  */
trait ResearcherMetricCache:
  /** Idempotent create: identical content is `Unchanged`, different content is
    * [[StorageError.ConflictingContent]]. Metrics rows are never overwritten.
    */
  def put(metrics: ResearcherMetrics): Result[CreateOutcome]
  def get(
      researcherId: String,
      windowStartYear: Int,
      windowEndYear: Int
  ): Result[Option[ResearcherMetrics]]
  def listWindows(researcherId: String): Result[Vector[ResearcherMetrics]]

// ---------------------------------------------------------------------------
// The conditional item seam the ports are written against
// ---------------------------------------------------------------------------

/** Minimal conditional item store: the only DynamoDB surface the ports need. Every item has string
  * attributes `pk` and `sk`. The SDK adapter and the in-memory fake implement exactly this.
  */
trait ItemStore:
  /** `attribute_not_exists(pk)` put. [[StorageError.AlreadyExists]] if the key exists. */
  def putIfAbsent(table: String, item: ujson.Obj): Result[Unit]

  /** All items or none, each with `attribute_not_exists(pk)`; max 100 items per DynamoDB
    * transaction. [[StorageError.AlreadyExists]] names the first conflicting key.
    */
  def putAllIfAbsent(table: String, items: Vector[ujson.Obj]): Result[Unit]

  def get(table: String, pk: String, sk: String): Result[Option[ujson.Obj]]

  /** Items of one partition, ordered by `sk`. */
  def query(
      table: String,
      pk: String,
      skPrefix: Option[String],
      ascending: Boolean,
      limit: Option[Int]
  ): Result[Vector[ujson.Obj]]
