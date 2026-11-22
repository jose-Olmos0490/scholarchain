package scholarchain.spark

/** Code-level versions of the Spark definitions (Hadoop/ER precedent: feature and policy versions
  * are code constants). Each is recorded in the run manifest's `policyVersions`, so changing one
  * changes the run ID.
  */
object Versions:
  /** Pipeline stage name in the run manifest. */
  val PipelineStage = "spark-graph-analytics"

  /** Code version used when `--code-version` is not given (EMR runs pass the Git SHA). */
  val DefaultCodeVersion = "scholarchain-spark-analytics-0.2.0-phase06"

  /** P_r, C_r, T_r, A_r, M_r, maximumTeamWorkIds, W_r as defined in modules/spark/JOBS.md. */
  val CollaborationMetrics = "sc-collaboration-metrics-v1"

  /** Connected components by min-label propagation; componentId = smallest researcher ID. */
  val Components = "sc-components-min-label-v1"

  /** Identity clustering rule (ACCEPTED links + DBLP author key, ratified constraints). */
  val IdentityRule = "sc-identity-cluster-rule-v1"

  /** HAS_TOPIC edges re-anchored from OpenAlex works to DBLP works via ACCEPTED work links. */
  val HasTopicPolicy = "sc-has-topic-via-accepted-work-link-v1"

  /** Window summary records (`WindowGraphSummary.metricVersion`). */
  val WindowSummary = "sc-window-summary-v1"

  /** Format of the companion run-summary JSON written next to the run manifest. */
  val RunSummaryFormat = "spark-run-summary-v1"

  /** Stage label on integrity reject records. */
  val RejectStage = "spark-graph-builder"

  /** The only supported `community.algorithm` value. */
  val CommunityAlgorithmName = "label-propagation"

  /** Synchronous, unweighted label propagation with self-vote, ties to the smallest label. */
  val Communities = "sc-communities-lpa-sync-v1"

  /** crossCommunityDegree, communitiesConnected (counts the own community) and the unweighted
    * participation coefficient; no betweenness.
    */
  val Bridges = "sc-bridge-measures-v1"

  /** Coreness by iterative peeling (min-degree jump per round). */
  val KCore = "sc-kcore-peeling-v1"

  /** Shortest paths: BFS from the target by frontier joins, then the lexicographically smallest
    * forward walk; hop evidence from the edge and support tables.
    */
  val Paths = "sc-shortest-path-bfs-lexmin-v1"

  /** Sampled-source BFS distance estimate (mean distance, p90 effective diameter). */
  val DistanceEstimate = "sc-distance-estimate-sampled-bfs-v1"

  /** Consecutive-window researcher comparison. */
  val Temporal = "sc-temporal-window-change-v1"

  /** Structured semantic-input fact records. */
  val SemanticFacts = "sc-semantic-facts-v1"
