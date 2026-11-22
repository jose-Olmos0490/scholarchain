package scholarchain.core

import scholarchain.core.model.*
import scholarchain.core.validation.RecordContract
import scholarchain.core.validation.RecordContract.given
import upickle.default.ReadWriter

/** One frozen wire example: a record value and its golden file under
  * `src/test/resources/golden/v1/`.
  */
final case class GoldenCase[A](file: String, value: A)(using
    val rw: ReadWriter[A],
    val contract: RecordContract[A]
):
  def recordType: String = contract.recordType

object GoldenCases:
  import Fixtures.*

  val all: Vector[GoldenCase[?]] = Vector(
    GoldenCase("provenance.json", dblpProv),
    GoldenCase("work-record.json", work),
    GoldenCase("author-mention.json", samueliMention),
    GoldenCase("canonical-researcher.json", researcher),
    GoldenCase("authorship-record.json", authorship),
    GoldenCase("publication-team.json", team),
    GoldenCase("coauthor-pair-candidate.json", pairCandidate),
    GoldenCase("coauthor-edge.json", coauthorEdge),
    GoldenCase("researcher-metrics.json", metrics),
    GoldenCase("venue.json", venue),
    GoldenCase("institution.json", institution),
    GoldenCase("topic.json", topic),
    GoldenCase("match-decision.json", matchDecision),
    GoldenCase("graph-node.json", graphNode),
    GoldenCase("graph-edge.json", graphEdge),
    GoldenCase("evidence-item.json", edgeEvidence),
    GoldenCase("evidence-bundle.json", bundle),
    GoldenCase("query-plan.json", queryPlan),
    GoldenCase("answer-claim.json", claim),
    GoldenCase("answer-response.json", answer),
    GoldenCase("run-manifest.json", runManifest),
    GoldenCase("author-paper-record.json", authorPaper),
    GoldenCase("match-candidate.json", matchCandidate),
    GoldenCase("exact-identifier-outcome.json", exactIdOutcome),
    GoldenCase("reject-record.json", reject),
    // Phase 6 Spark analytics records.
    GoldenCase("coauthor-edge-support.json", edgeSupport),
    GoldenCase("component-membership.json", componentMembership),
    GoldenCase("community-membership.json", communityMembership),
    GoldenCase("cross-community-edge.json", crossCommunityEdge),
    GoldenCase("bridge-measures.json", bridgeMeasures),
    GoldenCase("coreness.json", coreness),
    GoldenCase("path-evidence.json", pathEvidence),
    GoldenCase("semantic-fact.json", semanticFact),
    GoldenCase("load-manifest.json", loadManifest),
    GoldenCase("identity-conflict.json", identityConflict),
    GoldenCase("window-graph-summary.json", windowGraphSummary),
    GoldenCase("researcher-window-change.json", researcherWindowChange)
  )
