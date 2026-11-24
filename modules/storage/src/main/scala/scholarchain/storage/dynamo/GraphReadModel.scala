package scholarchain.storage.dynamo

import java.nio.charset.StandardCharsets
import scholarchain.core.model.*
import scholarchain.storage.common.*
import scholarchain.storage.directory.*
import scholarchain.storage.graph.*
import upickle.default.ReadWriter

// ---------------------------------------------------------------------------
// The graph read model: one DynamoDB table holding the records the runtime reads by key and that
// no other store holds. Neptune keeps topology, ordinary S3 keeps the Parquet outputs; this table
// is the key-addressed index of four of them, loaded from the Spark load manifest, never edited.
//
//   researcher directory   pk = dir#<runId>#<foldedName>             sk = c#<inv(confidence)>#<researcherId>
//   edge support           pk = esup#<runId>#<edgeId>                 sk = w#<workId>
//   component membership   pk = comp#<runId>#<researcherId>           sk = w#<start:4>-<end:4>
//   community membership   pk = comm#<runId>#<researcherId>           sk = w#<start:4>-<end:4>
//   cross-community edge   pk = xedge#<runId>#<communityId>#<window>  sk = e#<inv(jointCount)>#<edgeId>
//                          (one item under each endpoint community)
//   community link         pk = xconn#<runId>#<communityId>#<window>  sk = o#<otherCommunityId>
//   connectivity marker    pk = xconn#<runId>#<communityId>#<window>  sk = M
//
// `inv(x)` is `Long.MaxValue - x` in 19 zero-padded digits (a double is first mapped to its IEEE
// bit pattern, which orders like the value for non-negative numbers), so an ascending query is the
// documented order: confidence DESC / jointPublicationCount DESC, then ID ASC. Items are only ever
// created with `attribute_not_exists(pk)`; no TTL attribute is set (nothing here is disposable).
// ---------------------------------------------------------------------------

object ReadModelSchemas:
  val DirectoryEntry = "researcher-directory-entry-v1"
  val CommunityLink = "community-link-v1"
  val ConnectivityMarker = "community-connectivity-marker-v1"

/** One directory row: the candidate exactly as returned, the folded name it is filed under and the
  * provenance of the canonical researcher record it was built from.
  */
final case class DirectoryEntry(
    schemaVersion: String,
    normalizedName: String,
    candidate: NameCandidate,
    provenance: Provenance
) derives ReadWriter

/** Two communities joined by at least one stored cross-community edge, filed under `communityId`.
  * The number of these rows of a community is its stored `communitiesConnected`.
  */
final case class CommunityLink(
    schemaVersion: String,
    communityId: String,
    otherCommunityId: String,
    windowStartYear: Int,
    windowEndYear: Int,
    metricVersion: String,
    runId: String,
    provenance: Provenance
) derives ReadWriter

/** Written once per community and window after every cross-community edge of the run was stored
  * without a reject. Its presence is what makes the counts of that community "stored"; without it
  * the counts are [[StorageError.NotFound]] rather than a guessed zero.
  */
final case class ConnectivityMarker(
    schemaVersion: String,
    communityId: String,
    windowStartYear: Int,
    windowEndYear: Int,
    communitySize: Long,
    metricVersion: String,
    runId: String,
    provenance: Provenance
) derives ReadWriter

private[storage] object ReadModelKeys:
  private val MinYear = 0
  private val MaxYear = 9999

  def part(name: String, v: String): Result[String] = ItemCodec.part(name, v)

  def window(start: Int, end: Int): Result[String] =
    if start > end then
      Left(StorageError.InvalidRecord(s"window start $start is after window end $end"))
    else if start < MinYear || end > MaxYear then
      Left(StorageError.InvalidRecord(s"window $start-$end outside $MinYear..$MaxYear"))
    else Right(f"$start%04d-$end%04d")

  def invertedLong(n: Long): Result[String] =
    if n < 0 then Left(StorageError.InvalidRecord(s"count $n is negative"))
    else Right(f"${Long.MaxValue - n}%019d")

  def invertedDouble(d: Double): Result[String] =
    if d.isNaN || d < 0.0 || d > 1.0 then
      Left(StorageError.InvalidRecord(s"confidence $d outside [0,1]"))
    else
      val bits = java.lang.Double.doubleToLongBits(if d == 0.0 then 0.0 else d)
      Right(f"${Long.MaxValue - bits}%019d")

  def sized(item: ujson.Obj): Result[ujson.Obj] =
    val bytes = ujson.write(item).getBytes(StandardCharsets.UTF_8).length.toLong
    if bytes > DynamoItemLimits.MaxItemBytes then
      Left(
        StorageError.LimitExceeded("read model item bytes", DynamoItemLimits.MaxItemBytes, bytes)
      )
    else Right(item)

  def make[A: ReadWriter](pk: String, sk: String, kind: String, record: A): Result[ujson.Obj] =
    ItemCodec.key(pk, sk).flatMap(_ => sized(ItemCodec.item(pk, sk, kind, record)))

  def directoryPk(run: String, name: String) = s"dir#$run#$name"
  def supportPk(run: String, edgeId: String) = s"esup#$run#$edgeId"
  def componentPk(run: String, researcherId: String) = s"comp#$run#$researcherId"
  def communityPk(run: String, researcherId: String) = s"comm#$run#$researcherId"
  def crossPk(run: String, community: String, window: String) = s"xedge#$run#$community#$window"
  def linkPk(run: String, community: String, window: String) = s"xconn#$run#$community#$window"
  def windowSk(window: String) = s"w#$window"
  val MarkerSk = "M"

/** Builds the items of the read model from decoded Spark records. Used by the loader; every
  * builder validates the record against its core contract and that it belongs to `run`.
  */
private[storage] object ReadModelItems:
  import ReadModelKeys.*

  private def sameRun(what: String, recordRun: String, run: String): Result[Unit] =
    if recordRun == run then Right(())
    else Left(StorageError.InvalidRecord(s"$what belongs to $recordRun, the manifest is $run"))

  def candidateOf(r: CanonicalResearcher): Result[NameCandidate] =
    r.provenance.policyVersion.orElse(r.identityVersion) match
      case None =>
        Left(StorageError.InvalidRecord(s"researcher ${r.researcherId} names no policy version"))
      case Some(policy) =>
        val sourceIds = (r.orcid.map(o => s"orcid:$o") ++ r.dblpPid.map(p => s"dblpPid:$p") ++
          r.openAlexAuthorId.map(a => s"openAlex:$a") ++
          r.dblpAuthorKeys.map(k => s"dblp:$k")).toVector.distinct.sorted
        Right(
          NameCandidate(
            researcherId = r.researcherId,
            displayName = r.displayName,
            confidence = r.identityConfidence,
            sourceIds = sourceIds,
            affiliations = r.affiliationInstitutionIds.distinct.sorted,
            // the canonical researcher record carries no coauthors; none are invented
            coauthorSample = Vector.empty,
            matchDecisionIds = r.provenance.derivedFrom.distinct.sorted,
            policyVersion = policy,
            runId = r.provenance.runId
          )
        )

  def directory(r: CanonicalResearcher, run: String): Result[Vector[ujson.Obj]] =
    for
      v <- ItemCodec.contract(r)
      _ <- sameRun(s"researcher ${v.researcherId}", v.provenance.runId, run)
      runPart <- part("runId", run)
      id <- part("researcherId", v.researcherId)
      c <- candidateOf(v)
      names = NameFold.namesOf(v)
      _ <-
        if names.nonEmpty then Right(())
        else Left(StorageError.InvalidRecord(s"researcher $id has no indexable name"))
      conf <- invertedDouble(c.confidence)
      items <- traverse(names) { n =>
        make(
          directoryPk(runPart, n),
          s"c#$conf#$id",
          "ResearcherDirectoryEntry",
          DirectoryEntry(ReadModelSchemas.DirectoryEntry, n, c, v.provenance)
        )
      }
    yield items

  def support(s: CoauthorEdgeSupport, run: String): Result[ujson.Obj] =
    for
      v <- ItemCodec.contract(s)
      _ <- sameRun(s"support row of ${v.edgeId}", v.runId, run)
      r <- part("runId", run)
      e <- part("edgeId", v.edgeId)
      w <- part("workId", v.workId)
      item <- make(supportPk(r, e), s"w#$w", "CoauthorEdgeSupport", v)
    yield item

  def component(m: ComponentMembership, run: String): Result[ujson.Obj] =
    for
      v <- ItemCodec.contract(m)
      _ <- sameRun(s"component row of ${v.researcherId}", v.runId, run)
      r <- part("runId", run)
      id <- part("researcherId", v.researcherId)
      w <- window(v.windowStartYear, v.windowEndYear)
      item <- make(componentPk(r, id), windowSk(w), "ComponentMembership", v)
    yield item

  def community(m: CommunityMembership, run: String): Result[ujson.Obj] =
    for
      v <- ItemCodec.contract(m)
      _ <- sameRun(s"community row of ${v.researcherId}", v.runId, run)
      r <- part("runId", run)
      id <- part("researcherId", v.researcherId)
      w <- window(v.windowStartYear, v.windowEndYear)
      item <- make(communityPk(r, id), windowSk(w), "CommunityMembership", v)
    yield item

  /** The edge under each endpoint community, then the link in each direction. */
  def crossEdge(e: CrossCommunityEdge, run: String, base: Provenance): Result[Vector[ujson.Obj]] =
    for
      v <- ItemCodec.contract(e)
      _ <- sameRun(s"cross-community edge ${v.edgeId}", v.runId, run)
      _ <-
        if v.communityA != v.communityB then Right(())
        else Left(StorageError.InvalidRecord(s"edge ${v.edgeId} does not leave its community"))
      r <- part("runId", run)
      a <- part("communityA", v.communityA)
      b <- part("communityB", v.communityB)
      id <- part("edgeId", v.edgeId)
      w <- window(v.windowStartYear, v.windowEndYear)
      inv <- invertedLong(v.jointPublicationCount)
      under <- traverse(Vector(a, b))(c =>
        make(crossPk(r, c, w), s"e#$inv#$id", "CrossCommunityEdge", v)
      )
      links <- traverse(Vector((a, b), (b, a))) { (from, to) =>
        make(
          linkPk(r, from, w),
          s"o#$to",
          "CommunityLink",
          CommunityLink(
            ReadModelSchemas.CommunityLink,
            from,
            to,
            v.windowStartYear,
            v.windowEndYear,
            v.metricVersion,
            run,
            base
          )
        )
      }
    yield under ++ links

  def marker(m: CommunityMembership, run: String, base: Provenance): Result[ujson.Obj] =
    for
      v <- ItemCodec.contract(m)
      _ <- sameRun(s"community row of ${v.researcherId}", v.runId, run)
      r <- part("runId", run)
      c <- part("communityId", v.communityId)
      w <- window(v.windowStartYear, v.windowEndYear)
      item <- make(
        linkPk(r, c, w),
        MarkerSk,
        "CommunityConnectivityMarker",
        ConnectivityMarker(
          ReadModelSchemas.ConnectivityMarker,
          c,
          v.windowStartYear,
          v.windowEndYear,
          v.communitySize,
          v.metricVersion,
          run,
          base
        )
      )
    yield item

// ---------------------------------------------------------------------------
// Adapters
// ---------------------------------------------------------------------------

/** [[ResearcherDirectory]] over the read model table, for one loaded run.
  *
  * @param runId
  *   the run whose directory is read (`run:<64 hex>`); never mixed with another run
  * @param maxLimit
  *   `retrieval.graphLimit`; a larger request is refused, not clamped
  */
final class DynamoResearcherDirectory(
    items: ItemStore,
    table: String,
    runId: String,
    maxLimit: Int
) extends ResearcherDirectory:
  import ReadModelKeys.*

  def candidatesByName(normalizedName: String, limit: Int): Result[Vector[NameCandidate]] =
    if normalizedName == null || normalizedName.trim.isEmpty then
      Left(StorageError.InvalidRecord("normalizedName must not be blank"))
    else if limit < 1 then Left(StorageError.InvalidRecord(s"limit must be >= 1, got $limit"))
    else if limit > maxLimit then Left(StorageError.LimitExceeded("result limit", maxLimit, limit))
    else
      part("runId", runId).flatMap { run =>
        val pk = directoryPk(run, normalizedName)
        if ItemCodec.key(pk, "c#").isLeft then Right(Vector.empty) // too long to be stored
        else
          items.query(table, pk, Some("c#"), ascending = true, Some(limit)).flatMap { rows =>
            traverse(rows) { row =>
              ItemCodec.decode[DirectoryEntry](row).flatMap { e =>
                if e.candidate.runId != run || e.normalizedName != normalizedName then
                  Left(
                    StorageError.InvalidRecord(
                      s"stored item ${ItemCodec.keyOf(row)} does not belong to run $run and name"
                    )
                  )
                else Right(e.candidate)
              }
            }
          }
      }

/** [[EdgeSupportReader]] over the read model table.
  *
  * @param maxRowsPerEdge
  *   the most support rows read for one edge. An edge with more is refused with
  *   [[StorageError.LimitExceeded]]: a truncated list would break the path's evidence invariant
  *   (rows == jointPublicationCount), so it is never returned partially.
  */
final class DynamoEdgeSupportReader(items: ItemStore, table: String, maxRowsPerEdge: Int)
    extends EdgeSupportReader:
  import ReadModelKeys.*

  def supportingWorkIds(edge: CoauthorEdgeView): Result[Vector[String]] =
    if edge.evidencePointer != CoauthorEdgeSupport.supportRef(edge.edgeId) then
      Left(
        StorageError.InvalidRecord(
          s"evidence pointer '${edge.evidencePointer}' is not the support reference of edge ${edge.edgeId}"
        )
      )
    else
      for
        run <- part("runId", edge.runId)
        id <- part("edgeId", edge.edgeId)
        rows <- items.query(
          table,
          supportPk(run, id),
          Some("w#"),
          ascending = true,
          Some(maxRowsPerEdge + 1)
        )
        _ <-
          if rows.size <= maxRowsPerEdge then Right(())
          else
            Left(StorageError.LimitExceeded("support rows of one edge", maxRowsPerEdge, rows.size))
        support <- traverse(rows)(r => ItemCodec.decode[CoauthorEdgeSupport](r))
        _ <- traverse(support) { s =>
          if s.edgeId == id && s.runId == run then Right(())
          else
            Left(StorageError.InvalidRecord(s"support row ${s.workId} does not belong to edge $id"))
        }
      yield support.map(_.workId).distinct.sorted

/** [[AnalyticsReader]] over the read model table, for one loaded run.
  *
  * Counts are the exact number of stored rows (`Select = COUNT`), never derived from a limited edge
  * list, and exist only for a community whose connectivity marker was stored.
  */
final class DynamoAnalyticsReader(
    items: ItemStore,
    table: String,
    runId: String,
    limits: GraphReaderLimits
) extends AnalyticsReader:
  import ReadModelKeys.*

  private val templates = new CypherTemplates(limits)

  private def membership[A: ReadWriter](
      pkOf: (String, String) => String,
      researcherId: String,
      ws: Int,
      we: Int
  )(check: (A, String) => Boolean): Result[Option[A]] =
    for
      _ <- templates.checkWindow(ws, we)
      run <- part("runId", runId)
      id <- part("researcherId", researcherId)
      w <- window(ws, we)
      row <- items.get(table, pkOf(run, id), windowSk(w))
      out <- row match
        case None => Right(None)
        case Some(i) =>
          ItemCodec.decode[A](i).flatMap { a =>
            if check(a, run) then Right(Some(a))
            else Left(StorageError.InvalidRecord(s"stored item ${ItemCodec.keyOf(i)} is misfiled"))
          }
    yield out

  def componentOf(r: String, ws: Int, we: Int): Result[Option[ComponentMembership]] =
    membership[ComponentMembership](componentPk, r, ws, we)((m, run) =>
      m.runId == run && m.researcherId == r && m.windowStartYear == ws && m.windowEndYear == we
    )

  def communityOf(r: String, ws: Int, we: Int): Result[Option[CommunityMembership]] =
    membership[CommunityMembership](communityPk, r, ws, we)((m, run) =>
      m.runId == run && m.researcherId == r && m.windowStartYear == ws && m.windowEndYear == we
    )

  def crossCommunityEdges(
      communityId: String,
      ws: Int,
      we: Int,
      limit: Int
  ): Result[Vector[CrossCommunityEdge]] =
    for
      l <- templates.boundLimit(limit)
      _ <- templates.checkWindow(ws, we)
      run <- part("runId", runId)
      c <- part("communityId", communityId)
      w <- window(ws, we)
      rows <- items.query(table, crossPk(run, c, w), Some("e#"), ascending = true, Some(l))
      edges <- traverse(rows)(r => ItemCodec.decode[CrossCommunityEdge](r))
      _ <- traverse(edges) { e =>
        if e.runId == run && e.windowStartYear == ws && e.windowEndYear == we &&
          (e.communityA == c || e.communityB == c)
        then Right(())
        else Left(StorageError.InvalidRecord(s"cross-community edge ${e.edgeId} is misfiled"))
      }
    yield edges

  /** The marker proves the counts were stored; absent, the community has no stored counts. */
  private def storedCounts(
      communityId: String,
      ws: Int,
      we: Int
  )(count: (String, String) => Result[Long]): Result[Long] =
    for
      _ <- templates.checkWindow(ws, we)
      run <- part("runId", runId)
      c <- part("communityId", communityId)
      w <- window(ws, we)
      marker <- items.get(table, linkPk(run, c, w), MarkerSk)
      _ <- marker match
        case None => Left(StorageError.NotFound(s"community connectivity $communityId $ws-$we"))
        case Some(m) =>
          ItemCodec.decode[ConnectivityMarker](m).flatMap { mk =>
            if mk.runId == run && mk.communityId == c then Right(())
            else Left(StorageError.InvalidRecord(s"stored item ${ItemCodec.keyOf(m)} is misfiled"))
          }
      n <- count(run, w)
    yield n

  def crossCommunityEdgeCount(communityId: String, ws: Int, we: Int): Result[Long] =
    storedCounts(communityId, ws, we)((run, w) =>
      items.count(table, crossPk(run, communityId, w), Some("e#"))
    )

  def communitiesConnected(communityId: String, ws: Int, we: Int): Result[Long] =
    storedCounts(communityId, ws, we)((run, w) =>
      items.count(table, linkPk(run, communityId, w), Some("o#"))
    )
