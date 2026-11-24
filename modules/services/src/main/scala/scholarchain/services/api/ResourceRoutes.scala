package scholarchain.services.api

import scholarchain.services.evidence.EvidenceService
import scholarchain.services.graph.GraphService
import scholarchain.services.security.Scopes

/** The routes of the graph and evidence read API. */
final class ResourceRoutes(evidence: EvidenceService, graph: GraphService):
  val routes: Vector[Route] = Vector(
    Route(
      "GET",
      "/v1/evidence/{evidenceId}",
      Some(Scopes.EvidenceRead),
      c => evidence.get(c.params("evidenceId")).map(Json.response(200, _))
    ),
    Route(
      "GET",
      "/v1/graph/nodes/{nodeId}",
      Some(Scopes.GraphRead),
      c => graph.node(c.params("nodeId")).map(Json.response(200, _))
    ),
    Route(
      "GET",
      "/v1/graph/nodes/{nodeId}/neighbors",
      Some(Scopes.GraphRead),
      c => graph.neighbors(c.params("nodeId"), c.request.query).map(Json.response(200, _))
    )
  )
