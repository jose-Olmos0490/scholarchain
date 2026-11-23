package scholarchain.storage.dynamo

import java.nio.charset.StandardCharsets
import scholarchain.core.ids.{IdKind, StableId}
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.core.config.DynamoTablesConfig
import scholarchain.core.validation.RecordContract
import scholarchain.storage.common.*
import upickle.default.*

object DynamoSchemas:
  val RunLedgerEntry = "run-ledger-entry-v1"
  val LinkAdjudication = "link-adjudication-v1"
  val QuerySession = "query-session-v1"
  val ArtifactVersion = "artifact-version-v1"
  val BundleHeader = "evidence-bundle-header-v1"

/** Source of "now" for the one table that needs it (QuerySession TTL). */
trait Clock:
  def epochSeconds: Long

object Clock:
  val System: Clock = new Clock:
    def epochSeconds: Long = java.lang.System.currentTimeMillis() / 1000

private[storage] object ItemCodec:
  val MaxPartitionKeyBytes = 2048 // DynamoDB BatchWriteItem / key limits page
  val MaxSortKeyBytes = 1024

  def key(pk: String, sk: String): Result[Unit] =
    def bytes(s: String) = s.getBytes(StandardCharsets.UTF_8).length
    if bytes(pk) > MaxPartitionKeyBytes then
      Left(StorageError.LimitExceeded("partition key bytes", MaxPartitionKeyBytes, bytes(pk)))
    else if bytes(sk) > MaxSortKeyBytes then
      Left(StorageError.LimitExceeded("sort key bytes", MaxSortKeyBytes, bytes(sk)))
    else Right(())

  /** A key component: non-blank and free of the `#` separator. */
  def part(name: String, value: String): Result[String] =
    if value == null || value.trim.isEmpty then
      Left(StorageError.InvalidRecord(s"$name must not be blank"))
    else if value.contains('#') then
      Left(StorageError.InvalidRecord(s"$name must not contain '#': $value"))
    else Right(value)

  def item[A: ReadWriter](
      pk: String,
      sk: String,
      kind: String,
      record: A,
      expiresAtEpochSeconds: Option[Long] = None
  ): ujson.Obj =
    val rec = writeJs(record)
    val o = ujson.Obj(
      "pk" -> pk,
      "sk" -> sk,
      "kind" -> kind,
      "contentHash" -> Sha256.of(ujson.write(rec)),
      "record" -> rec
    )
    expiresAtEpochSeconds.foreach(t => o("expiresAt") = ujson.Num(t.toDouble))
    o

  def hashOf(item: ujson.Obj): String = item("contentHash").str
  def keyOf(item: ujson.Obj): String = s"${item("pk").str}|${item("sk").str}"

  def decode[A: ReadWriter](item: ujson.Obj): Result[A] =
    try Right(read[A](item("record")))
    catch
      case e: Exception =>
        Left(
          StorageError.InvalidRecord(s"stored item ${keyOf(item)} does not decode: ${e.getMessage}")
        )

  /** Create; an existing key with identical content is `Unchanged`, with different content a
    * conflict. Used where reloads must be idempotent.
    */
  def createOrSame(store: ItemStore, table: String, item: ujson.Obj): Result[CreateOutcome] =
    store.putIfAbsent(table, item) match
      case Right(_) => Right(CreateOutcome.Created)
      case Left(_: StorageError.AlreadyExists) =>
        store.get(table, item("pk").str, item("sk").str).flatMap {
          case Some(existing) if hashOf(existing) == hashOf(item) => Right(CreateOutcome.Unchanged)
          case Some(existing) =>
            Left(StorageError.ConflictingContent(keyOf(item), hashOf(existing), hashOf(item)))
          case None => Left(StorageError.Permanent("createOrSame", s"${keyOf(item)} vanished"))
        }
      case Left(other) => Left(other)

  def contract[A: RecordContract](a: A): Result[A] =
    WireCodec
      .validate(a)
      .left
      .map(es => StorageError.InvalidRecord(es.map(_.toString).mkString("; ")))

final class DynamoRunLedger(items: ItemStore, table: String) extends RunLedger:
  private val StagePattern = "[a-z][a-z0-9_-]{0,63}".r

  def record(e: RunLedgerEntry): Result[Unit] =
    for
      _ <-
        if e.schemaVersion == DynamoSchemas.RunLedgerEntry then Right(())
        else Left(StorageError.InvalidRecord(s"schemaVersion ${e.schemaVersion}"))
      run <- ItemCodec.part("runId", e.runId)
      _ <-
        if StagePattern.matches(e.stage) then Right(())
        else
          Left(StorageError.InvalidRecord(s"stage '${e.stage}' must match [a-z][a-z0-9_-]{0,63}"))
      _ <-
        if e.sequence >= 0 && e.sequence <= 99999 then Right(())
        else Left(StorageError.InvalidRecord(s"sequence ${e.sequence} outside 0..99999"))
      _ <-
        if e.attempt >= 1 && e.attempt <= 999 then Right(())
        else Left(StorageError.InvalidRecord(s"attempt ${e.attempt} outside 1..999"))
      pk = s"run#$run"
      sk = f"${e.sequence}%05d#${e.stage}#${e.attempt}%03d#${e.state.rank}-${e.state}"
      _ <- ItemCodec.key(pk, sk)
      _ <- items.putIfAbsent(table, ItemCodec.item(pk, sk, "RunLedgerEntry", e))
    yield ()

  def list(runId: String): Result[Vector[RunLedgerEntry]] =
    ItemCodec.part("runId", runId).flatMap { run =>
      items
        .query(table, s"run#$run", None, ascending = true, None)
        .flatMap(rows => traverse(rows)(ItemCodec.decode[RunLedgerEntry]))
    }

final class DynamoEntityLinkCache(items: ItemStore, table: String) extends EntityLinkCache:
  private def keys(h: String, p: String, m: String, v: String): Result[(String, String)] =
    for
      hh <- ItemCodec.part("inputHash", h)
      pp <- ItemCodec.part("promptVersion", p)
      mm <- ItemCodec.part("modelVersion", m)
      vv <- ItemCodec.part("policyVersion", v)
    yield (s"link#$hh", s"$pp#$mm#$vv")

  def put(a: LinkAdjudication): Result[Unit] =
    for
      _ <-
        if a.confidence >= 0.0 && a.confidence <= 1.0 then Right(())
        else Left(StorageError.InvalidRecord(s"confidence ${a.confidence} outside [0,1]"))
      _ <-
        if Sha256.isWellFormed(a.inputHash) then Right(())
        else Left(StorageError.InvalidRecord(s"inputHash '${a.inputHash}' is not sha256:<hex>"))
      k <- keys(a.inputHash, a.promptVersion, a.modelVersion, a.policyVersion)
      _ <- ItemCodec.key(k._1, k._2)
      _ <- items.putIfAbsent(table, ItemCodec.item(k._1, k._2, "LinkAdjudication", a))
    yield ()

  def get(h: String, p: String, m: String, v: String): Result[Option[LinkAdjudication]] =
    keys(h, p, m, v).flatMap { (pk, sk) =>
      items.get(table, pk, sk).flatMap {
        case None => Right(None)
        case Some(i) => ItemCodec.decode[LinkAdjudication](i).map(Some(_))
      }
    }

/** @param ttlSeconds
  *   lifetime of a session and its idempotency marker (`querySession.ttlDays` as seconds)
  */
final class DynamoQuerySessions(items: ItemStore, table: String, ttlSeconds: Long)
    extends QuerySessions:
  private val Session = "SESSION"
  private val IdemSk = "QUERY"

  def create(s: QuerySessionRecord): Result[Unit] =
    for
      q <- ItemCodec.part("queryId", s.queryId)
      expires = s.createdAtEpochSeconds + ttlSeconds
      _ <- ItemCodec.key(s"query#$q", Session)
      sessionItem = ItemCodec.item(s"query#$q", Session, "QuerySession", s, Some(expires))
      idem <- s.idempotencyKey match
        case None => Right(Vector.empty[ujson.Obj])
        case Some(k) =>
          ItemCodec
            .part("idempotencyKey", k)
            .flatMap(kk => ItemCodec.key(s"idem#$kk", IdemSk).map(_ => kk))
            .map { kk =>
              Vector(
                ujson.Obj(
                  "pk" -> s"idem#$kk",
                  "sk" -> IdemSk,
                  "kind" -> "IdempotencyMarker",
                  "contentHash" -> Sha256.of(s.queryId),
                  "record" -> ujson.Obj("queryId" -> s.queryId),
                  "expiresAt" -> ujson.Num(expires.toDouble)
                )
              )
            }
      // the idempotency marker goes first so an exact replay reports the repeated key
      _ <- items.putAllIfAbsent(table, idem :+ sessionItem).left.flatMap {
        case StorageError.AlreadyExists(key) if key.startsWith("idem#") =>
          queryIdForIdempotencyKey(key.stripPrefix("idem#").takeWhile(_ != '|')).flatMap {
            existing =>
              Left(
                StorageError.DuplicateIdempotencyKey(
                  s.idempotencyKey.getOrElse(""),
                  existing.getOrElse("")
                )
              )
          }
        case StorageError.AlreadyExists(_) => Left(StorageError.AlreadyExists(s.queryId))
        case other => Left(other)
      }
    yield ()

  def get(queryId: String): Result[Option[QuerySessionRecord]] =
    ItemCodec.part("queryId", queryId).flatMap { q =>
      items.get(table, s"query#$q", Session).flatMap {
        case None => Right(None)
        case Some(i) => ItemCodec.decode[QuerySessionRecord](i).map(Some(_))
      }
    }

  def queryIdForIdempotencyKey(key: String): Result[Option[String]] =
    ItemCodec.part("idempotencyKey", key).flatMap { k =>
      items.get(table, s"idem#$k", IdemSk).map(_.map(_("record")("queryId").str))
    }

/** Maximum DynamoDB item size, 400 KB: "the maximum item size in DynamoDB is 400 KB, which
  * includes both attribute name binary length (UTF-8 length) and attribute value lengths"
  * (https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Constraints.html).
  */
private[storage] object DynamoItemLimits:
  val MaxItemBytes: Long = 400L * 1024

final class DynamoQueryAuditLog(items: ItemStore, table: String) extends QueryAuditLog:
  private def keys(queryId: String): Result[(String, String)] =
    ItemCodec.part("queryId", queryId).map(q => (s"query#$q", s"AUDIT#$q"))

  def append(record: QueryAuditRecord): Result[Unit] =
    for
      valid <- ItemCodec.contract(record)
      k <- keys(valid.queryId)
      _ <- ItemCodec.key(k._1, k._2)
      // no expiresAt: audit records are kept
      item = ItemCodec.item(k._1, k._2, "QueryAudit", valid)
      bytes = ujson.write(item).getBytes(StandardCharsets.UTF_8).length.toLong
      _ <-
        if bytes > DynamoItemLimits.MaxItemBytes then
          Left(StorageError.LimitExceeded("audit item bytes", DynamoItemLimits.MaxItemBytes, bytes))
        else Right(())
      _ <- items.putIfAbsent(table, item).left.map {
        case _: StorageError.AlreadyExists => StorageError.AlreadyExists(k._2)
        case other => other
      }
    yield ()

  def get(queryId: String): Result[Option[QueryAuditRecord]] =
    keys(queryId).flatMap { (pk, sk) =>
      items.get(table, pk, sk).flatMap {
        case None => Right(None)
        case Some(i) => ItemCodec.decode[QueryAuditRecord](i).map(Some(_))
      }
    }

final class DynamoEvidenceStore(items: ItemStore, table: String) extends EvidenceStore:
  private val EvidenceSk = "ITEM"

  private case class Membership(evidenceId: String, section: String) derives ReadWriter
  private case class Header(
      schemaVersion: String,
      bundleId: String,
      queryId: String,
      graphEvidenceIds: Vector[String],
      semanticEvidenceIds: Vector[String],
      identityConfidence: Map[String, Double],
      provenance: Provenance
  ) derives ReadWriter

  def putBundle(b: EvidenceBundle): Result[Unit] =
    for
      _ <- ItemCodec.contract(b)
      q <- ItemCodec.part("queryId", b.queryId)
      graph = b.graphEvidence.map(_.evidenceId)
      semantic = b.semanticEvidence.map(_.evidenceId)
      all = (b.graphEvidence.map(e => ("graph", e)) ++ b.semanticEvidence.map(e => ("semantic", e)))
      _ <- traverse(all) { (section, e) =>
        for
          _ <- ItemCodec.part("evidenceId", e.evidenceId)
          _ <- ItemCodec.key(s"evidence#${e.evidenceId}", EvidenceSk)
          _ <- ItemCodec.key(s"query#$q", s"E#${e.evidenceId}")
          _ <- ItemCodec.createOrSame(
            items,
            table,
            ItemCodec.item(s"evidence#${e.evidenceId}", EvidenceSk, "EvidenceItem", e)
          )
          _ <- ItemCodec.createOrSame(
            items,
            table,
            ItemCodec.item(
              s"query#$q",
              s"E#${e.evidenceId}",
              "EvidenceMembership",
              Membership(e.evidenceId, section)
            )
          )
        yield ()
      }
      // header last: a retry after a partial failure re-creates items as Unchanged, then writes it
      header = Header(
        DynamoSchemas.BundleHeader,
        b.bundleId,
        b.queryId,
        graph,
        semantic,
        b.identityConfidence,
        b.provenance
      )
      _ <- items.putIfAbsent(
        table,
        ItemCodec.item(s"query#$q", "BUNDLE", "EvidenceBundleHeader", header)
      )
    yield ()

  def getItem(evidenceId: String): Result[Option[EvidenceItem]] =
    ItemCodec.part("evidenceId", evidenceId).flatMap { id =>
      items.get(table, s"evidence#$id", EvidenceSk).flatMap {
        case None => Right(None)
        case Some(i) => ItemCodec.decode[EvidenceItem](i).map(Some(_))
      }
    }

  def listForQuery(queryId: String): Result[Vector[EvidenceItem]] =
    ItemCodec.part("queryId", queryId).flatMap { q =>
      items.query(table, s"query#$q", Some("E#"), ascending = true, None).flatMap { rows =>
        traverse(rows)(r => ItemCodec.decode[Membership](r)).flatMap { ms =>
          traverse(ms) { m =>
            getItem(m.evidenceId).flatMap(
              _.toRight(StorageError.NotFound(s"evidence ${m.evidenceId} of query $queryId"))
            )
          }
        }
      }
    }

final class DynamoArtifactRegistry(items: ItemStore, table: String, maxAttempts: Int = 5)
    extends ArtifactRegistry:
  private def pk(name: String): Result[String] =
    ItemCodec.part("artifact name", name).map(n => s"artifact#$n")
  private def sk(version: Long): String = f"v#$version%012d"

  def register(
      name: String,
      location: String,
      sha256: String,
      contentSchemaVersion: String,
      runId: String,
      provenance: Provenance
  ): Result[ArtifactVersion] =
    pk(name).flatMap(p => ItemCodec.key(p, sk(1)).map(_ => p)).flatMap { p =>
      var attempt = 0
      var result: Option[Result[ArtifactVersion]] = None
      while result.isEmpty do
        attempt += 1
        latest(name) match
          case Left(e) => result = Some(Left(e))
          case Right(current) =>
            val v = ArtifactVersion(
              DynamoSchemas.ArtifactVersion,
              name,
              current.map(_.version + 1).getOrElse(1L),
              location,
              sha256,
              contentSchemaVersion,
              runId,
              provenance
            )
            items.putIfAbsent(table, ItemCodec.item(p, sk(v.version), "ArtifactVersion", v)) match
              case Right(_) => result = Some(Right(v))
              case Left(_: StorageError.AlreadyExists) if attempt < maxAttempts => ()
              case Left(_: StorageError.AlreadyExists) =>
                result = Some(
                  Left(
                    StorageError.Permanent("register", s"$name: version race lost $attempt times")
                  )
                )
              case Left(e) => result = Some(Left(e))
      result.get
    }

  def latest(name: String): Result[Option[ArtifactVersion]] =
    pk(name).flatMap { p =>
      items.query(table, p, Some("v#"), ascending = false, Some(1)).flatMap {
        case Vector() => Right(None)
        case rows => ItemCodec.decode[ArtifactVersion](rows.head).map(Some(_))
      }
    }

  def version(name: String, version: Long): Result[Option[ArtifactVersion]] =
    if version < 1 then Left(StorageError.InvalidRecord(s"version must be >= 1, got $version"))
    else
      pk(name).flatMap { p =>
        items.get(table, p, sk(version)).flatMap {
          case None => Right(None)
          case Some(i) => ItemCodec.decode[ArtifactVersion](i).map(Some(_))
        }
      }

final class DynamoResearcherMetricCache(items: ItemStore, table: String)
    extends ResearcherMetricCache:
  private def pk(researcherId: String): Result[String] =
    for
      r <- ItemCodec.part("researcherId", researcherId)
      _ <-
        if StableId.isValid(r, IdKind.Researcher) then Right(())
        else Left(StorageError.InvalidRecord(s"'$r' is not a researcher ID"))
    yield s"researcher#$r"
  private def sk(start: Int, end: Int): String = f"w#$start%04d-$end%04d"

  def put(m: ResearcherMetrics): Result[CreateOutcome] =
    for
      valid <- ItemCodec.contract(m)
      p <- pk(valid.researcherId)
      s = sk(valid.windowStartYear, valid.windowEndYear)
      _ <- ItemCodec.key(p, s)
      outcome <- ItemCodec.createOrSame(
        items,
        table,
        ItemCodec.item(p, s, "ResearcherMetrics", valid)
      )
    yield outcome

  def get(researcherId: String, start: Int, end: Int): Result[Option[ResearcherMetrics]] =
    pk(researcherId).flatMap { p =>
      items.get(table, p, sk(start, end)).flatMap {
        case None => Right(None)
        case Some(i) => ItemCodec.decode[ResearcherMetrics](i).map(Some(_))
      }
    }

  def listWindows(researcherId: String): Result[Vector[ResearcherMetrics]] =
    pk(researcherId).flatMap { p =>
      items
        .query(table, p, Some("w#"), ascending = true, None)
        .flatMap(rows => traverse(rows)(ItemCodec.decode[ResearcherMetrics]))
    }

/** The ports wired to one [[ItemStore]] and the configured table names. */
final case class DynamoPorts(
    runLedger: RunLedger,
    entityLinkCache: EntityLinkCache,
    querySessions: QuerySessions,
    evidence: EvidenceStore,
    artifacts: ArtifactRegistry,
    metricCache: Option[ResearcherMetricCache],
    queryAudit: QueryAuditLog
)

object DynamoPorts:
  def apply(
      items: ItemStore,
      tables: DynamoTablesConfig,
      querySessionTtlSeconds: Long
  ): DynamoPorts =
    DynamoPorts(
      new DynamoRunLedger(items, tables.runLedger),
      new DynamoEntityLinkCache(items, tables.entityLinkCache),
      new DynamoQuerySessions(items, tables.querySession, querySessionTtlSeconds),
      new DynamoEvidenceStore(items, tables.evidenceBundle),
      new DynamoArtifactRegistry(items, tables.artifactRegistry),
      tables.researcherMetricCache.map(new DynamoResearcherMetricCache(items, _)),
      new DynamoQueryAuditLog(items, tables.querySession)
    )

/** Sequence-with-short-circuit over [[Result]]. */
private[dynamo] def traverse[A, B](xs: Iterable[A])(f: A => Result[B]): Result[Vector[B]] =
  val out = Vector.newBuilder[B]
  val it = xs.iterator
  var failure: Option[StorageError] = None
  while failure.isEmpty && it.hasNext do
    f(it.next()) match
      case Left(e) => failure = Some(e)
      case Right(b) => out += b
  failure.toLeft(out.result())
