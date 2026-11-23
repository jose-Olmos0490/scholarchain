package scholarchain.services.graph

import scholarchain.core.ids.{IdKind, StableId}
import scholarchain.services.config.{GraphApiSettings, RuntimeLimits}
import scholarchain.services.error.{ApiError, ErrorCode}
import scholarchain.services.query.StorageErrors
import scholarchain.services.resilience.Downstream
import scholarchain.storage.common.{Result, StorageError}
import scholarchain.storage.graph.{
  CoauthorEdgeView,
  GraphReader,
  PageRequest,
  ResearcherNodeMetrics,
  ResearcherView
}

/** A validated, bounded neighborhood request. Every bound is explicit. */
final case class NeighborQuery(
    nodeId: String,
    windowStartYear: Int,
    windowEndYear: Int,
    limit: Int,
    depth: Int,
    edgeTypes: Vector[String],
    timeoutMillis: Long,
    cursor: Option[String]
)

/** Graph exploration over the [[GraphReader]] port. Depth, edge types, result count and time are
  * bounded by configuration; a request above a bound is refused (422), never clamped, and a
  * missing window is refused (400). Neighborhoods are cursored.
  */
final class GraphService(
    reader: GraphReader,
    downstream: Downstream,
    limits: RuntimeLimits,
    settings: GraphApiSettings
):
  private val Allowed =
    Set(
      "window",
      "windowStartYear",
      "windowEndYear",
      "limit",
      "depth",
      "edgeTypes",
      "timeoutMs",
      "cursor"
    )

  /** The deepest neighborhood served: the smaller of the configured path depth and the port's
    * own reach.
    */
  val maxDepth: Int = math.min(limits.maxPathDepth, settings.neighborMaxDepth)

  def node(nodeId: String): Either[ApiError, ujson.Obj] =
    for
      _ <- researcherId(nodeId)
      found <- downstream.read()(reader.getResearcher(nodeId))
      view <- found.toRight(ApiError.notFound("the graph node was not found"))
    yield nodeView(view)

  def neighbors(nodeId: String, params: Map[String, Vector[String]]): Either[ApiError, ujson.Obj] =
    for
      q <- parse(nodeId, params)
      out <- fetch(q)
    yield out

  // ---- parameters ---------------------------------------------------------------------------

  private def researcherId(id: String): Either[ApiError, Unit] =
    if StableId.parseAs(id, Set(IdKind.Researcher)).isRight then Right(())
    else if StableId.parse(id).isRight then
      Left(
        ApiError.unprocessable(
          "UNSUPPORTED_NODE_TYPE",
          "only researcher nodes can be read through this API"
        )
      )
    else Left(ApiError.badRequest("INVALID_ID", "the node ID is not a stable ID"))

  private def single(
      params: Map[String, Vector[String]],
      k: String
  ): Either[ApiError, Option[String]] =
    params.get(k) match
      case None => Right(None)
      case Some(Vector(v)) => Right(Some(v))
      case Some(_) =>
        Left(ApiError.badRequest("DUPLICATE_PARAMETER", s"parameter '$k' may be given once"))

  private def intParam(
      params: Map[String, Vector[String]],
      k: String
  ): Either[ApiError, Option[Long]] =
    single(params, k).flatMap {
      case None => Right(None)
      case Some(v) =>
        v.toLongOption
          .map(n => Right(Some(n)))
          .getOrElse(
            Left(ApiError.badRequest("INVALID_PARAMETER", s"parameter '$k' must be an integer"))
          )
    }

  private def required(k: String, v: Option[Long]): Either[ApiError, Long] =
    v.toRight(ApiError.badRequest("MISSING_PARAMETER", s"parameter '$k' is required"))

  private def within(k: String, v: Long, min: Long, max: Long): Either[ApiError, Long] =
    if v < min || v > max then
      Left(
        ApiError.unprocessable(
          if k == "depth" then "DEPTH_OUT_OF_BOUNDS" else "LIMIT_OUT_OF_BOUNDS",
          s"parameter '$k' must be between $min and $max"
        )
      )
    else Right(v)

  def parse(nodeId: String, params: Map[String, Vector[String]]): Either[ApiError, NeighborQuery] =
    for
      _ <- researcherId(nodeId)
      _ <-
        if params.keySet.subsetOf(Allowed) then Right(())
        else Left(ApiError.badRequest("UNKNOWN_PARAMETER", "the request has an unknown parameter"))
      ws <- intParam(params, "windowStartYear").flatMap(required("windowStartYear", _))
      we <- intParam(params, "windowEndYear").flatMap(required("windowEndYear", _))
      _ <-
        if ws <= we then Right(())
        else
          Left(ApiError.unprocessable("WINDOW_INVALID", "the window start is after the window end"))
      limit <- intParam(params, "limit").flatMap(l =>
        within("limit", l.getOrElse(limits.pageSize.toLong), 1, limits.graphLimit)
      )
      depth <- intParam(params, "depth").flatMap(d => within("depth", d.getOrElse(1L), 1, maxDepth))
      timeout <- intParam(params, "timeoutMs").flatMap(t =>
        within("timeoutMs", t.getOrElse(limits.graphTimeoutMillis), 1, limits.graphTimeoutMillis)
      )
      types <- single(params, "edgeTypes").map(
        _.fold(settings.edgeTypes)(_.split(',').toVector.map(_.trim).filter(_.nonEmpty))
      )
      _ <-
        if types.nonEmpty && types.forall(settings.edgeTypes.contains) then Right(())
        else
          Left(
            ApiError.unprocessable(
              "UNSUPPORTED_EDGE_TYPE",
              s"edgeTypes must be among: ${settings.edgeTypes.mkString(", ")}"
            )
          )
      cursor <- single(params, "cursor").flatMap {
        case Some(c) if c.isEmpty || c.length > 512 =>
          Left(ApiError.badRequest("INVALID_CURSOR", "the cursor is not valid"))
        case other => Right(other)
      }
    yield NeighborQuery(
      nodeId,
      ws.toInt,
      we.toInt,
      limit.toInt,
      depth.toInt,
      types,
      timeout,
      cursor
    )

  // ---- reading ------------------------------------------------------------------------------

  private def mapStorage(e: StorageError): ApiError = e match
    case StorageError.InvalidRecord(r) if r.startsWith("cursor") =>
      ApiError.badRequest("INVALID_CURSOR", "the cursor is not valid for this request")
    case other => StorageErrors.toApi(other)

  private def fetch(q: NeighborQuery): Either[ApiError, ujson.Obj] =
    // one deadline covers the existence check and the page, so the whole request is bounded
    def page
        : Result[Option[scholarchain.storage.graph.Page[scholarchain.storage.graph.CoauthorView]]] =
      for
        node <- reader.getResearcher(q.nodeId)
        page <- node match
          case None => Right(None)
          case Some(_) =>
            reader
              .directCoauthorsPage(
                q.nodeId,
                q.windowStartYear,
                q.windowEndYear,
                PageRequest(q.limit, q.cursor)
              )
              .map(Some(_))
      yield page
    downstream
      .read(timeoutMillis = q.timeoutMillis, mapError = mapStorage)(page)
      .flatMap {
        case None => Left(ApiError.notFound("the graph node was not found"))
        case Some(p) =>
          Right(
            ujson.Obj(
              "nodeId" -> q.nodeId,
              "window" -> ujson.Obj("startYear" -> q.windowStartYear, "endYear" -> q.windowEndYear),
              "depth" -> q.depth,
              "edgeTypes" -> ujson.Arr.from(q.edgeTypes.map(ujson.Str(_))),
              "limit" -> q.limit,
              "items" -> ujson.Arr.from(
                p.items
                  .map(c => ujson.Obj("neighborId" -> c.coauthorId, "edge" -> edgeView(c.edge)))
              ),
              "nextCursor" -> p.nextCursor.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
              "truncated" -> p.nextCursor.isDefined
            )
          )
      }

  // ---- views --------------------------------------------------------------------------------

  private def edgeView(e: CoauthorEdgeView): ujson.Obj = ujson.Obj(
    "edgeId" -> e.edgeId,
    "edgeType" -> "COAUTHORED_WITH",
    "researcherA" -> e.researcherA,
    "researcherB" -> e.researcherB,
    "jointPublicationCount" -> e.jointPublicationCount.toDouble,
    "firstCollaborationYear" -> e.firstCollaborationYear,
    "lastCollaborationYear" -> e.lastCollaborationYear,
    "windowStartYear" -> e.windowStartYear,
    "windowEndYear" -> e.windowEndYear,
    "evidencePointer" -> e.evidencePointer,
    "provenance" -> ujson.Obj("runId" -> e.runId, "policyVersion" -> e.policyVersion)
  )

  private def metricsView(m: ResearcherNodeMetrics): ujson.Obj = ujson.Obj(
    "window" -> m.window,
    "publicationCount" -> m.publicationCount.toDouble,
    "distinctCoauthorCount" -> m.distinctCoauthorCount.toDouble,
    "totalCoauthorOccurrences" -> m.totalCoauthorOccurrences.toDouble,
    "averageCoauthorsPerPaper" -> m.averageCoauthorsPerPaper,
    "maximumCoauthorsOnOnePaper" -> m.maximumCoauthorsOnOnePaper.toDouble,
    "weightedDegree" -> m.weightedDegree,
    "componentId" -> m.componentId,
    "communityId" -> m.communityId.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
    "bridgeScore" -> m.bridgeScore.fold[ujson.Value](ujson.Null)(ujson.Num(_)),
    "coreness" -> m.coreness.fold[ujson.Value](ujson.Null)(ujson.Num(_)),
    "metricVersion" -> m.metricVersion
  )

  private def nodeView(v: ResearcherView): ujson.Obj =
    ujson.Obj(
      "nodeId" -> v.researcherId,
      "nodeType" -> "Researcher",
      "displayName" -> v.displayName,
      "orcid" -> v.orcid.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
      "metrics" -> v.metrics.fold[ujson.Value](ujson.Null)(metricsView),
      "provenance" -> ujson.Obj("runId" -> v.runId),
      "links" -> ujson.Obj("neighbors" -> s"/v1/graph/nodes/${v.researcherId}/neighbors")
    )
