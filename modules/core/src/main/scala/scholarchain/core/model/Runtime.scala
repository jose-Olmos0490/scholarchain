package scholarchain.core.model

import upickle.default.ReadWriter

/** One item of evidence.
  *
  * @param referencedIds
  *   stable IDs of the stored records this evidence points at (ordered; for a path, the path ID
  *   inputs in hop order)
  * @param sourceUri
  *   locator of the authoritative stored copy
  * @param graphPath
  *   researcher IDs in hop order, for `GRAPH_PATH` evidence
  * @param score
  *   retrieval score, when the item came from ranked retrieval; never a graph fact
  * @param textHash
  *   `sha256:<hex>` of `text` (or of the stored text when `text` is not inlined)
  * @param text
  *   bounded excerpt; untrusted data, never instructions
  */
final case class EvidenceItem(
    schemaVersion: String,
    evidenceId: String,
    evidenceType: EvidenceType,
    referencedIds: Vector[String],
    workId: Option[String],
    sourceUri: String,
    chunkId: Option[String],
    graphPath: Option[Vector[String]],
    score: Option[Double],
    textHash: Option[String],
    text: Option[String],
    provenance: Provenance
) derives ReadWriter

/** Bounded evidence for one query. Deterministic graph facts and semantic context are kept in
  * separate sections.
  *
  * @param identityConfidence
  *   researcher ID to identity-resolution confidence in [0, 1]
  */
final case class EvidenceBundle(
    schemaVersion: String,
    bundleId: String,
    queryId: String,
    graphEvidence: Vector[EvidenceItem],
    semanticEvidence: Vector[EvidenceItem],
    identityConfidence: Map[String, Double],
    provenance: Provenance
) derives ReadWriter

/** Typed plan derived from a user question. Only allowlisted operations exist.
  *
  * The time window is either fully present or fully absent. Limits are requested values; the
  * runtime caps them with configured bounds.
  */
final case class QueryPlan(
    schemaVersion: String,
    queryId: String,
    operations: Vector[QueryOperation],
    researcherNames: Vector[String],
    researcherIds: Vector[String],
    windowStartYear: Option[Int],
    windowEndYear: Option[Int],
    topics: Vector[String],
    venues: Vector[String],
    institutions: Vector[String],
    rankingMetric: Option[CollaborationMetric],
    graphLimit: Int,
    vectorLimit: Int,
    maxPathDepth: Int,
    planVersion: String,
    provenance: Provenance
) derives ReadWriter

final case class AnswerClaim(
    schemaVersion: String,
    claimId: String,
    text: String,
    evidenceIds: Vector[String],
    verificationStatus: VerificationStatus,
    uncertainty: Option[String],
    provenance: Provenance
) derives ReadWriter

/** Final answer for one query. An abstaining answer must state its uncertainty. */
final case class AnswerResponse(
    schemaVersion: String,
    queryId: String,
    summary: String,
    claims: Vector[AnswerClaim],
    evidenceBundleId: String,
    abstained: Boolean,
    uncertainty: Option[String],
    provenance: Provenance
) derives ReadWriter

/** Audit record of one answered query (Phase 9). Stored by the storage layer in the QuerySession
  * table under sort key `AUDIT#<queryId>`.
  *
  * It holds the plan as JSON text, the IDs of everything the answer cited, the versions that
  * produced it, usage counters, the claim verification outcome and the SHA-256 of the answer text.
  * It deliberately has no field for model reasoning or raw prompts: hidden model reasoning is
  * never stored.
  *
  * @param planJson
  *   the validated [[QueryPlan]] as JSON text
  * @param verificationResult
  *   overall outcome of claim verification for the answer
  * @param answerHash
  *   `sha256:<64 lower-case hex>` of the answer text
  */
final case class QueryAuditRecord(
    schemaVersion: String,
    queryId: String,
    runId: String,
    planJson: String,
    researcherIds: Vector[String],
    evidenceIds: Vector[String],
    metricVersions: Vector[String],
    promptVersions: Vector[String],
    modelVersions: Vector[String],
    inputTokens: Long,
    outputTokens: Long,
    latencyMillis: Long,
    verificationResult: VerificationStatus,
    answerHash: String,
    provenance: Provenance
) derives ReadWriter

/** One input of a pipeline run.
  *
  * @param location
  *   locator of the staged input (object key, prefix or repository path)
  * @param checksum
  *   required `sha256:<64 lower-case hex>` of the input bytes. For a single object it is the
  *   SHA-256 of the object. For a multi-object location (a prefix) the producer computes it over a
  *   canonical listing (one `<key> <sha256>` line per object, keys sorted); that listing rule is
  *   the producer's, core only requires the digest. It ties the run ID to the data.
  */
final case class RunInput(
    sourceDataset: SourceType,
    snapshot: String,
    location: String,
    checksum: String
) derives ReadWriter

/** Immutable run manifest. The run ID is the hash of its canonical content.
  *
  * It has no time fields by design, so re-running identical inputs, code and parameters yields
  * the same run ID. `inputs` is a set: its order does not affect the run ID, and two inputs may
  * not name the same dataset and location. It is the root that every `Provenance.runId` points
  * at, and its `inputs` are its own provenance; it therefore carries no nested [[Provenance]].
  */
final case class RunManifest(
    schemaVersion: String,
    pipelineStage: String,
    codeVersion: String,
    inputs: Vector[RunInput],
    parameters: Map[String, String],
    policyVersions: Map[String, String]
) derives ReadWriter
