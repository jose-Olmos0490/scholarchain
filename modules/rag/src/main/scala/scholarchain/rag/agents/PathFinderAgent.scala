package scholarchain.rag.agents

import scholarchain.core.config.RetrievalConfig
import scholarchain.core.model.Provenance
import scholarchain.core.model.SourceType
import scholarchain.core.schema.SchemaVersions as SV
import scholarchain.storage.graph.*

/** One hop of a path: the stored edge and every supporting work.
  *
  * @param fromResearcherId
  *   the researcher the hop starts at in the direction of travel
  */
final case class PathHopReport(
    fromResearcherId: String,
    toResearcherId: String,
    edgeId: String,
    jointPublicationCount: Long,
    firstCollaborationYear: Int,
    lastCollaborationYear: Int,
    supportingWorkIds: Vector[String],
    evidencePointer: String,
    provenance: Provenance
)

final case class FoundPath(researcherIds: Vector[String], hops: Vector[PathHopReport]):
  def length: Int = hops.size

/** Paths between two researchers inside one window.
  *
  * An empty `paths` means that the store found no path within `depthLimit` hops; it is not a
  * statement about whether the researchers are connected at any depth.
  *
  * @param droppedPaths
  *   paths the store returned that were discarded because a hop had no supporting work
  * @param depthCapped
  *   the requested depth was above the configured depth and was lowered to it
  */
final case class PathReport(
    source: String,
    target: String,
    windowStartYear: Int,
    windowEndYear: Int,
    depthLimit: Int,
    resultLimit: Int,
    timeoutMillis: Long,
    paths: Vector[FoundPath],
    droppedPaths: Int,
    depthCapped: Boolean = false
)

/** Bounded paths with supporting works.
  *
  * The depth, the number of results and the time limit come from `retrieval.maxPathDepth`,
  * `retrieval.pathResultLimit` and `retrieval.pathTimeoutMillis`. A requested depth above the
  * configured one is capped. A store time-out is a retryable failure.
  */
final class PathFinderAgent(
    reader: PathReader,
    retrieval: RetrievalConfig,
    snapshot: String
):
  def find(
      source: String,
      target: String,
      windowStartYear: Int,
      windowEndYear: Int,
      requestedDepth: Option[Int] = None
  ): SResult[PathReport] =
    if source == target then
      Left(StageFailure.Rejected("SAME_ENDPOINTS", "source and target are the same researcher"))
    else if requestedDepth.exists(_ < 1) then
      Left(StageFailure.Rejected("LIMIT_INVALID", "path depth must be at least 1"))
    else
      val depth = math.min(requestedDepth.getOrElse(retrieval.maxPathDepth), retrieval.maxPathDepth)
      val limits =
        PathQueryLimits(depth, retrieval.pathResultLimit, retrieval.pathTimeoutMillis.toLong)
      reader
        .paths(source, target, windowStartYear, windowEndYear, limits)
        .left
        .map(StageFailure.fromStorage("pathReader.paths", _))
        .map { found =>
          val converted = found.map(convert)
          val kept =
            converted.filter(p => p.hops.nonEmpty && p.hops.forall(_.supportingWorkIds.nonEmpty))
          PathReport(
            source,
            target,
            windowStartYear,
            windowEndYear,
            depth,
            retrieval.pathResultLimit,
            retrieval.pathTimeoutMillis.toLong,
            kept,
            converted.size - kept.size,
            requestedDepth.exists(_ > depth)
          )
        }

  private def convert(p: SupportedPath): FoundPath =
    FoundPath(
      p.researcherIds,
      p.hops.map { h =>
        PathHopReport(
          h.fromResearcherId,
          h.toResearcherId,
          h.edge.edgeId,
          h.edge.jointPublicationCount,
          h.edge.firstCollaborationYear,
          h.edge.lastCollaborationYear,
          h.supportingWorkIds,
          h.edge.evidencePointer,
          Provenance(
            SV.Provenance,
            SourceType.SCHOLARCHAIN,
            h.edge.edgeId,
            snapshot,
            h.edge.runId,
            Some(h.edge.policyVersion),
            None,
            None,
            h.supportingWorkIds
          )
        )
      }
    )

/** One direct coauthor: the stored edge and every supporting work. */
final case class CoauthorReport(
    coauthorId: String,
    edgeId: String,
    jointPublicationCount: Long,
    firstCollaborationYear: Int,
    lastCollaborationYear: Int,
    supportingWorkIds: Vector[String],
    evidencePointer: String,
    provenance: Provenance
)

/** @param nextCursor
  *   opaque token for the next page; `None` on the last page
  */
final case class CoauthorPageReport(
    researcherId: String,
    windowStartYear: Int,
    windowEndYear: Int,
    coauthors: Vector[CoauthorReport],
    nextCursor: Option[String],
    pageSize: Int
)

/** A page of direct coauthors, ordered by `jointPublicationCount DESC, coauthorId ASC`, with the
  * supporting works of each edge. The page size is `retrieval.coauthorPageSize`.
  */
final class DirectCoauthorsAgent(
    graph: GraphReader,
    support: EdgeSupportReader,
    retrieval: RetrievalConfig,
    snapshot: String
):
  def page(
      researcherId: String,
      windowStartYear: Int,
      windowEndYear: Int,
      cursor: Option[String] = None
  ): SResult[CoauthorPageReport] =
    for
      p <- graph
        .directCoauthorsPage(
          researcherId,
          windowStartYear,
          windowEndYear,
          PageRequest(retrieval.coauthorPageSize, cursor)
        )
        .left
        .map(StageFailure.fromStorage("graphReader.directCoauthorsPage", _))
      rows <- p.items.foldLeft[SResult[Vector[CoauthorReport]]](Right(Vector.empty)) { (acc, c) =>
        acc.flatMap { done =>
          support
            .supportingWorkIds(c.edge)
            .left
            .map(StageFailure.fromStorage("edgeSupportReader.supportingWorkIds", _))
            .flatMap { works =>
              if works.size.toLong != c.edge.jointPublicationCount then
                Left(
                  StageFailure.Failed(
                    "edgeSupportReader.supportingWorkIds",
                    s"edge ${c.edge.edgeId} has ${works.size} supporting work(s) for jointPublicationCount ${c.edge.jointPublicationCount}"
                  )
                )
              else
                Right(
                  done :+ CoauthorReport(
                    c.coauthorId,
                    c.edge.edgeId,
                    c.edge.jointPublicationCount,
                    c.edge.firstCollaborationYear,
                    c.edge.lastCollaborationYear,
                    works,
                    c.edge.evidencePointer,
                    Provenance(
                      SV.Provenance,
                      SourceType.SCHOLARCHAIN,
                      c.edge.edgeId,
                      snapshot,
                      c.edge.runId,
                      Some(c.edge.policyVersion),
                      None,
                      None,
                      works
                    )
                  )
                )
            }
        }
      }
    yield CoauthorPageReport(
      researcherId,
      windowStartYear,
      windowEndYear,
      rows,
      p.nextCursor,
      retrieval.coauthorPageSize
    )
