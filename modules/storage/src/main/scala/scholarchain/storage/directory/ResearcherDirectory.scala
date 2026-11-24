package scholarchain.storage.directory

import scala.collection.mutable
import scholarchain.storage.common.*
import upickle.default.ReadWriter

/** One canonical researcher that a normalized name may denote.
  *
  * @param confidence
  *   the stored `MatchDecision.confidence` of the accepted identity cluster, in [0, 1]. It is
  *   never recomputed or rescaled by the directory.
  * @param matchDecisionIds
  *   the decisions that formed the cluster, so the confidence can be audited
  */
final case class NameCandidate(
    researcherId: String,
    displayName: String,
    confidence: Double,
    sourceIds: Vector[String],
    affiliations: Vector[String],
    coauthorSample: Vector[String],
    matchDecisionIds: Vector[String],
    policyVersion: String,
    runId: String
) derives ReadWriter

/** Lookup of canonical researchers by normalized name, from the entity-resolution outputs.
  *
  * Candidates are ordered by `confidence DESC, researcherId ASC`. `limit` is bounded by the
  * configured graph limit and refused above it. The caller normalizes the name; the directory
  * matches it exactly and does not guess.
  */
trait ResearcherDirectory:
  def candidatesByName(normalizedName: String, limit: Int): Result[Vector[NameCandidate]]

/** In-memory [[ResearcherDirectory]], the local fake of the contract. The real adapter is
  * `scholarchain.storage.dynamo.DynamoResearcherDirectory`; both pass `ResearcherDirectoryContract`.
  */
final class FakeResearcherDirectory(maxLimit: Int) extends ResearcherDirectory:
  private val byName = mutable.LinkedHashMap.empty[String, Vector[NameCandidate]]

  def put(normalizedName: String, candidate: NameCandidate): Result[Unit] =
    if normalizedName == null || normalizedName.trim.isEmpty then
      Left(StorageError.InvalidRecord("normalizedName must not be blank"))
    else if candidate.confidence.isNaN || candidate.confidence < 0.0 || candidate.confidence > 1.0
    then Left(StorageError.InvalidRecord(s"confidence ${candidate.confidence} outside [0,1]"))
    else
      synchronized {
        byName(normalizedName) = byName
          .getOrElse(normalizedName, Vector.empty)
          .filterNot(
            _.researcherId == candidate.researcherId
          ) :+ candidate
      }
      Right(())

  def candidatesByName(normalizedName: String, limit: Int): Result[Vector[NameCandidate]] =
    if normalizedName == null || normalizedName.trim.isEmpty then
      Left(StorageError.InvalidRecord("normalizedName must not be blank"))
    else if limit < 1 then Left(StorageError.InvalidRecord(s"limit must be >= 1, got $limit"))
    else if limit > maxLimit then Left(StorageError.LimitExceeded("result limit", maxLimit, limit))
    else
      Right(
        synchronized(byName.getOrElse(normalizedName, Vector.empty))
          .sortBy(c => (-c.confidence, c.researcherId))
          .take(limit)
      )
