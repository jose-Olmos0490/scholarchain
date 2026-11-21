package scholarchain.er.model

import scholarchain.core.model.{MatchEntityType, SourceType}
import scholarchain.er.ids.ErIdType

/** Read-only comparison view of one record, assembled from Phase 4 tables. Views copy values;
  * nothing in the entity-resolution module writes back to a source record.
  */
sealed trait EntityView:
  /** Stable ID of the record (a work ID, author-mention ID or researcher ID). */
  def ref: String
  def source: SourceType

  /** Source-side record ID (dblp key, DOI, OpenAlex ID) from the record's provenance. */
  def sourceRecordId: String
  def snapshot: String
  def ids: Map[ErIdType, Set[String]]
  def kind: MatchEntityType

/** A work.
  *
  * @param authorNames
  *   raw author names in source order
  * @param venues
  *   raw venue strings (venue record name, container title, booktitle, event acronym)
  * @param references
  *   normalized cited-work identifiers, each prefixed with its type (`doi:…`, `openalex:W…`)
  */
final case class WorkView(
    ref: String,
    source: SourceType,
    sourceRecordId: String,
    snapshot: String,
    title: String,
    year: Option[Int],
    authorNames: Vector[String],
    venues: Vector[String],
    ids: Map[ErIdType, Set[String]],
    references: Set[String]
) extends EntityView:
  def kind: MatchEntityType = MatchEntityType.WORK

/** A researcher record: an author mention on one work, or a source researcher profile node.
  *
  * @param position
  *   1-based author position on `work`, for a mention
  * @param work
  *   the work the mention appears on; `None` for a researcher profile
  * @param coauthorNames
  *   raw names of the other authors on the mention's work
  * @param affiliations
  *   raw affiliation strings
  * @param workIds
  *   identifiers of works linked to a researcher profile (for example ORCID work DOIs)
  */
final case class ResearcherView(
    ref: String,
    source: SourceType,
    sourceRecordId: String,
    snapshot: String,
    name: String,
    position: Option[Int],
    teamSize: Option[Int],
    work: Option[WorkView],
    coauthorNames: Vector[String],
    affiliations: Vector[String],
    ids: Map[ErIdType, Set[String]],
    workIds: Map[ErIdType, Set[String]]
) extends EntityView:
  def kind: MatchEntityType = MatchEntityType.RESEARCHER
  def year: Option[Int] = work.flatMap(_.year)

  /** Identifiers of the works this record is attached to: its mention's work IDs plus any
    * profile work IDs.
    */
  def contextWorkIds: Map[ErIdType, Set[String]] =
    val fromWork = work.map(_.ids).getOrElse(Map.empty)
    (fromWork.keySet ++ workIds.keySet).iterator
      .map(t => t -> (fromWork.getOrElse(t, Set.empty) ++ workIds.getOrElse(t, Set.empty)))
      .toMap

/** Match path that produced a decision (evidence hierarchy level). */
enum MatchPath:
  case EXACT_ID, DETERMINISTIC_BIBLIOGRAPHIC, PROBABILISTIC, LLM_ADJUDICATED
