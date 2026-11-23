package scholarchain.rag.answer

import scholarchain.core.model.CollaborationMetric

/** What a synthesized claim asserts about stored data. Every kind except `PUBLICATION` with the
  * field `DESCRIBED_IN_CONTEXT` is checked against deterministic (graph or metric store) evidence
  * only.
  */
enum AssertionKind:
  case METRIC, COAUTHOR_EDGE, PATH_HOP, COMMUNITY, BRIDGE, IDENTITY, PUBLICATION, WINDOW

object AssertionKind:
  def parse(name: String): Option[AssertionKind] = values.find(_.toString == name)

  /** Kinds that only deterministic evidence can support. Semantic context never does. */
  val DeterministicOnly: Set[AssertionKind] = Set(
    METRIC,
    COAUTHOR_EDGE,
    PATH_HOP,
    COMMUNITY,
    BRIDGE,
    IDENTITY
  )

/** The fields an assertion may name, per kind. The synthesis schema's `field` enum is exactly
  * [[AssertionFields.all]], and the verifier refuses a (kind, field) pair outside this table.
  *
  * Rank fields are `RANK_BY_<METRIC>` (the place in the stored ranking, 1 first) and
  * `TOPIC_RANK_BY_<METRIC>` (the place in a ranking that was filtered by topic).
  */
object AssertionFields:
  import AssertionKind.*

  private val metricNames: Vector[String] = CollaborationMetric.values.toVector.map(_.toString)

  val RankPrefix = "RANK_BY_"
  val TopicRankPrefix = "TOPIC_RANK_BY_"

  val byKind: Map[AssertionKind, Set[String]] = Map(
    METRIC -> (Set(
      "PUBLICATION_COUNT",
      "DISTINCT_COAUTHOR_COUNT",
      "TOTAL_COAUTHOR_OCCURRENCES",
      "AVERAGE_COAUTHORS_PER_PAPER",
      "MAXIMUM_COAUTHORS_ON_ONE_PAPER",
      "WEIGHTED_DEGREE",
      "CORENESS",
      "COMPONENT_SIZE",
      "COMMUNITY_SIZE",
      "MAXIMUM_TEAM_WORK"
    ) ++ metricNames.map(RankPrefix + _) ++ metricNames.map(TopicRankPrefix + _)),
    COAUTHOR_EDGE -> Set(
      "COAUTHOR",
      "JOINT_PUBLICATION_COUNT",
      "FIRST_COLLABORATION_YEAR",
      "LAST_COLLABORATION_YEAR"
    ),
    PATH_HOP -> Set("HOP", "PATH_LENGTH", "PATH_WITHIN_HOPS", "HOP_JOINT_PUBLICATION_COUNT"),
    COMMUNITY -> Set("COMMUNITY_ID", "COMMUNITY_SIZE", "COMPONENT_ID", "COMPONENT_SIZE"),
    BRIDGE -> Set(
      "APPROX_BETWEENNESS",
      "CROSS_COMMUNITY_DEGREE",
      "PARTICIPATION_COEFFICIENT",
      "COMMUNITIES_CONNECTED",
      "CROSS_COMMUNITY_EDGE_COUNT"
    ),
    IDENTITY -> Set("CONFIDENCE", "SOURCE_ID", "NAME"),
    PUBLICATION -> Set("SUPPORTING_WORK", "DESCRIBED_IN_CONTEXT"),
    WINDOW -> Set("ANALYSIS_WINDOW")
  )

  /** Every field name, sorted: the schema enum. */
  val all: Vector[String] = byKind.values.flatten.toVector.distinct.sorted

  def allowed(kind: AssertionKind, field: String): Boolean =
    byKind.get(kind).exists(_.contains(field))

  /** Fields whose value is a whole number: compared exactly. */
  def isInteger(field: String): Boolean =
    field.startsWith(RankPrefix) || field.startsWith(TopicRankPrefix) || integerFields(field)

  private val integerFields = Set(
    "PUBLICATION_COUNT",
    "DISTINCT_COAUTHOR_COUNT",
    "TOTAL_COAUTHOR_OCCURRENCES",
    "MAXIMUM_COAUTHORS_ON_ONE_PAPER",
    "CORENESS",
    "COMPONENT_SIZE",
    "COMMUNITY_SIZE",
    "JOINT_PUBLICATION_COUNT",
    "FIRST_COLLABORATION_YEAR",
    "LAST_COLLABORATION_YEAR",
    "PATH_LENGTH",
    "PATH_WITHIN_HOPS",
    "HOP_JOINT_PUBLICATION_COUNT",
    "CROSS_COMMUNITY_DEGREE",
    "COMMUNITIES_CONNECTED",
    "CROSS_COMMUNITY_EDGE_COUNT"
  )

  /** The path fields that read every stored path of a pair rather than one stored fact. */
  val pathBounds: Set[String] = Set("PATH_LENGTH", "PATH_WITHIN_HOPS")

  /** Fields that name an existence: a null value is enough (the subject and object are the claim). */
  val existence: Set[String] = Set("COAUTHOR", "HOP")

  /** Fields that need `objectId`: the second researcher of an edge or hop. */
  val needsObject: Set[String] = Set(
    "COAUTHOR",
    "JOINT_PUBLICATION_COUNT",
    "FIRST_COLLABORATION_YEAR",
    "LAST_COLLABORATION_YEAR",
    "HOP",
    "PATH_LENGTH",
    "PATH_WITHIN_HOPS",
    "HOP_JOINT_PUBLICATION_COUNT"
  )

/** The numeric tolerance of a verification. Whole numbers, IDs and windows are exact. A stored
  * real number is rendered into evidence with six decimals, so an asserted real number matches when
  * it differs from the stored one by at most this absolute amount.
  */
object Tolerance:
  val Real: Double = 1e-6

final case class Window(start: Int, end: Int)

enum AssertedValue:
  case Num(value: Double)
  case Text(value: String)
  case Absent

final case class Assertion(
    kind: AssertionKind,
    subjectId: String,
    objectId: Option[String],
    field: String,
    value: AssertedValue,
    window: Option[Window]
)

/** One presentation of the model: which verified assertions to show, and optionally one
  * interpretation template. The model writes no text: a person reads only what code renders from
  * the assertions that verified, and from the template (see [[InterpretationTemplates]]).
  *
  * @param interpretation
  *   the ID of one template, or `None`
  */
final case class SynthClaim(
    evidenceIds: Vector[String],
    assertions: Vector[Assertion],
    interpretation: Option[String] = None
)

/** The validated output of the synthesis model: the claims to present, in order. It has no
  * summary, no uncertainty note and no claim text: code renders those.
  */
final case class SynthesizedAnswer(claims: Vector[SynthClaim])

object SynthesizedAnswer:
  private def strOpt(v: ujson.Value): Option[String] = v match
    case ujson.Str(s) => Some(s)
    case _ => None

  private def assertion(o: ujson.Value): Option[Assertion] =
    for
      kind <- strOpt(o("kind")).flatMap(AssertionKind.parse)
      subject <- strOpt(o("subjectId"))
      field <- strOpt(o("field"))
    yield Assertion(
      kind,
      subject,
      strOpt(o("objectId")),
      field,
      o("value") match
        case ujson.Num(n) => AssertedValue.Num(n)
        case ujson.Str(s) => AssertedValue.Text(s)
        case _ => AssertedValue.Absent
      ,
      o("window") match
        case w: ujson.Obj => Some(Window(w("start").num.toInt, w("end").num.toInt))
        case _ => None
    )

  /** Reads an object that already validated against the synthesis schema. `None` when a field
    * holds a value the schema allows but the vocabulary does not (an unknown kind or template).
    */
  def fromJson(o: ujson.Obj): Option[SynthesizedAnswer] =
    val claims = o("claims").arr.toVector.map { c =>
      val asserts = c("assertions").arr.toVector.map(assertion)
      val template = strOpt(c("interpretation"))
      Option.when(
        asserts.forall(_.isDefined) && template.forall(id =>
          InterpretationTemplates.byId(id).isDefined
        )
      )(
        SynthClaim(
          c("evidenceIds").arr.toVector.map(_.str),
          asserts.flatten,
          template
        )
      )
    }
    Option.when(claims.forall(_.isDefined))(SynthesizedAnswer(claims.flatten))
