package scholarchain.rag.agents

import scholarchain.core.model.{CollaborationMetric, QueryOperation}
import scholarchain.core.model.CollaborationMetric.*
import scholarchain.core.model.QueryOperation.*

/** What one allowlisted operation needs and which fixed storage operations serve it.
  *
  * User text never becomes query code. A plan names operations from the core enum, and each
  * operation maps here to named storage templates (the IDs storage publishes, for example
  * `topK/WEIGHTED_DEGREE` or `paths/4`) and port operations. Nothing in a plan, a question or a
  * model output can add to this table.
  *
  * @param agent
  *   the stage that serves the operation (a modifier has none: it narrows another operation)
  */
final case class OperationSpec(
    operation: QueryOperation,
    agent: Option[String],
    minResearchers: Int,
    maxResearchers: Int,
    needsMetric: Boolean,
    needs: Option[String],
    portOperations: Vector[String]
)

/** One step of an execution plan: a stage, and the template IDs and port operations it may use. */
final case class PlannedStep(
    operation: QueryOperation,
    agent: String,
    templateIds: Vector[String],
    portOperations: Vector[String]
)

object OperationAllowlist:
  /** Measures that describe how a researcher bridges communities. Storage does not rank by them
    * today; the refusal surfaces as `Unsupported`.
    */
  val bridgeMetrics: Set[CollaborationMetric] =
    Set(
      APPROX_BETWEENNESS,
      CROSS_COMMUNITY_DEGREE,
      PARTICIPATION_COEFFICIENT,
      COMMUNITIES_CONNECTED
    )

  val table: Map[QueryOperation, OperationSpec] =
    def s(
        op: QueryOperation,
        agent: Option[String],
        min: Int,
        max: Int,
        metric: Boolean,
        needs: Option[String],
        ports: String*
    ) = op -> OperationSpec(op, agent, min, max, metric, needs, ports.toVector)
    Map(
      s(
        RESEARCHER_RANKING,
        Some("CollaborationMetricsAgent"),
        0,
        0,
        true,
        None,
        "MetricReader.topK"
      ),
      s(
        MAXIMUM_TEAM_SIZE,
        Some("CollaborationMetricsAgent"),
        0,
        0,
        false,
        None,
        "MetricReader.topK",
        "ResearcherMetricCache.get"
      ),
      s(
        DIRECT_COAUTHORS,
        Some("DirectCoauthorsAgent"),
        1,
        10,
        false,
        None,
        "GraphReader.directCoauthorsPage",
        "EdgeSupportReader.supportingWorkIds"
      ),
      s(
        SHORTEST_PATH,
        Some("PathFinderAgent"),
        2,
        2,
        false,
        None,
        "PathReader.paths"
      ),
      s(
        COMPONENT_LOOKUP,
        Some("CommunityAnalystAgent"),
        1,
        10,
        false,
        None,
        "AnalyticsReader.componentOf"
      ),
      s(
        COMMUNITY_COMPARISON,
        Some("CommunityAnalystAgent"),
        1,
        10,
        false,
        None,
        "AnalyticsReader.communityOf",
        "AnalyticsReader.crossCommunityEdgeCount",
        "AnalyticsReader.communitiesConnected",
        "AnalyticsReader.crossCommunityEdges",
        "ResearcherMetricCache.get"
      ),
      s(BRIDGE_RANKING, Some("CollaborationMetricsAgent"), 0, 0, true, None, "MetricReader.topK"),
      s(
        TEMPORAL_COMPARISON,
        Some("CollaborationMetricsAgent"),
        1,
        10,
        false,
        None,
        "ResearcherMetricCache.listWindows"
      ),
      s(TOPIC_FILTER, None, 0, 0, false, Some("topics")),
      s(VENUE_FILTER, None, 0, 0, false, Some("venues")),
      s(INSTITUTION_FILTER, None, 0, 0, false, Some("institutions")),
      s(
        SEMANTIC_EXPLANATION,
        Some("SemanticContextAgent"),
        0,
        0,
        false,
        None,
        "VectorIndex.query",
        "ObjectStore.getBytes"
      )
    )

  def spec(op: QueryOperation): OperationSpec = table(op)

  /** The operation named exactly `name`, or none. Case and spelling must match. */
  def parseOperation(name: String): Option[QueryOperation] =
    QueryOperation.values.find(_.toString == name)

  def parseMetric(name: String): Option[CollaborationMetric] =
    CollaborationMetric.values.find(_.toString == name)

  def isModifier(op: QueryOperation): Boolean = spec(op).agent.isEmpty

  /** Storage template IDs for a ranking by `metric`: `topK/<METRIC>`, and the topic variant when
    * the plan has a topic filter.
    */
  def rankingTemplates(metric: CollaborationMetric, withTopics: Boolean): Vector[String] =
    Vector(s"topK/$metric") ++ Option.when(withTopics)(s"topK/$metric/topics")

  /** The storage template IDs one operation may run. `paths/<d>` is the depth-`d` template with
    * `d` the planned depth (already capped by configuration).
    */
  def templateIds(
      op: QueryOperation,
      metric: Option[CollaborationMetric],
      hasTopics: Boolean,
      maxPathDepth: Int
  ): Vector[String] = op match
    case RESEARCHER_RANKING | BRIDGE_RANKING =>
      metric.toVector.flatMap(rankingTemplates(_, hasTopics))
    case MAXIMUM_TEAM_SIZE => rankingTemplates(MAXIMUM_COAUTHORS_ON_ONE_PAPER, hasTopics)
    case DIRECT_COAUTHORS => Vector("directCoauthorsPage/first", "directCoauthorsPage/after")
    case SHORTEST_PATH => Vector(s"paths/$maxPathDepth")
    case _ => Vector.empty

  /** The steps of a validated plan, in the order of its operations. Modifiers are not steps. */
  def steps(
      operations: Vector[QueryOperation],
      metric: Option[CollaborationMetric],
      hasTopics: Boolean,
      maxPathDepth: Int
  ): Vector[PlannedStep] =
    operations.distinct.flatMap { op =>
      val sp = spec(op)
      sp.agent.map(a =>
        PlannedStep(
          op,
          a,
          templateIds(op, effectiveMetric(op, metric), hasTopics, maxPathDepth),
          sp.portOperations
        )
      )
    }

  /** MAXIMUM_TEAM_SIZE always ranks by M_r. */
  def effectiveMetric(
      op: QueryOperation,
      requested: Option[CollaborationMetric]
  ): Option[CollaborationMetric] =
    if op == MAXIMUM_TEAM_SIZE then Some(MAXIMUM_COAUTHORS_ON_ONE_PAPER) else requested
