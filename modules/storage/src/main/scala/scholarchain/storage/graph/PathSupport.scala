package scholarchain.storage.graph

import scala.collection.mutable
import scholarchain.storage.common.*

/** Supporting works of one stored coauthor edge. The rows are the
  * `coauthor-edge-support-v1` records of the edge's run: one row per supporting work, so their
  * number equals the edge's `jointPublicationCount`.
  */
trait EdgeSupportReader:
  /** The distinct supporting work IDs of `edge`, sorted ascending, resolved from the edge's
    * `evidencePointer` and `runId`. An edge with no support rows is `Right(Vector.empty)`; a
    * pointer that is not a support reference is refused.
    */
  def supportingWorkIds(edge: CoauthorEdgeView): Result[Vector[String]]

/** One hop of a path with its stored edge and every supporting work. `fromResearcherId` and
  * `toResearcherId` give the direction of travel; the edge keeps its canonical direction.
  */
final case class SupportedHop(
    fromResearcherId: String,
    toResearcherId: String,
    edge: CoauthorEdgeView,
    supportingWorkIds: Vector[String]
)

final case class SupportedPath(researcherIds: Vector[String], hops: Vector[SupportedHop]):
  def length: Int = hops.size

/** Bounded paths with the supporting works of every hop: the one port a runtime agent needs to
  * cite a path. A hop whose support rows are fewer or more than the edge's
  * `jointPublicationCount` fails the whole call: evidence is never silently incomplete.
  */
trait PathReader:
  def paths(
      source: String,
      target: String,
      windowStartYear: Int,
      windowEndYear: Int,
      limits: PathQueryLimits
  ): Result[Vector[SupportedPath]]

/** [[PathReader]] composed from the topology port and the support port. */
final class SupportedPathReader(graph: GraphReader, support: EdgeSupportReader) extends PathReader:
  def paths(
      source: String,
      target: String,
      windowStartYear: Int,
      windowEndYear: Int,
      limits: PathQueryLimits
  ): Result[Vector[SupportedPath]] =
    graph.paths(source, target, windowStartYear, windowEndYear, limits).flatMap { found =>
      val out = Vector.newBuilder[SupportedPath]
      val it = found.iterator
      var failure: Option[StorageError] = None
      while failure.isEmpty && it.hasNext do
        supported(it.next()) match
          case Left(e) => failure = Some(e)
          case Right(p) => out += p
      failure.toLeft(out.result())
    }

  private def supported(p: EdgePath): Result[SupportedPath] =
    val hops = Vector.newBuilder[SupportedHop]
    var failure: Option[StorageError] = None
    p.edges.zipWithIndex.foreach { (edge, i) =>
      if failure.isEmpty then
        support.supportingWorkIds(edge) match
          case Left(e) => failure = Some(e)
          case Right(works) if works.size.toLong != edge.jointPublicationCount =>
            failure = Some(
              StorageError.InvalidRecord(
                s"edge ${edge.edgeId} has jointPublicationCount ${edge.jointPublicationCount} " +
                  s"but ${works.size} support row(s)"
              )
            )
          case Right(works) =>
            hops += SupportedHop(p.researcherIds(i), p.researcherIds(i + 1), edge, works)
    }
    failure.toLeft(SupportedPath(p.researcherIds, hops.result()))

/** In-memory [[EdgeSupportReader]], the local fake of the contract. The real adapter is
  * `scholarchain.storage.dynamo.DynamoEdgeSupportReader`, fed by `GraphReadModelLoader` from the
  * `coauthored_with_support` Parquet table; both pass `EdgeSupportReaderContract`.
  */
final class FakeEdgeSupportReader extends EdgeSupportReader:
  private val rows = mutable.LinkedHashMap.empty[(String, String), Set[String]]

  /** Records the supporting works of `pointer` in `runId`. */
  def put(pointer: String, runId: String, workIds: Set[String]): Unit = synchronized {
    rows((pointer, runId)) = workIds
  }

  def supportingWorkIds(edge: CoauthorEdgeView): Result[Vector[String]] =
    if edge.evidencePointer != scholarchain.core.model.CoauthorEdgeSupport.supportRef(edge.edgeId)
    then
      Left(
        StorageError.InvalidRecord(
          s"evidence pointer '${edge.evidencePointer}' is not the support reference of edge ${edge.edgeId}"
        )
      )
    else
      Right(
        synchronized(rows.getOrElse((edge.evidencePointer, edge.runId), Set.empty)).toVector.sorted
      )
