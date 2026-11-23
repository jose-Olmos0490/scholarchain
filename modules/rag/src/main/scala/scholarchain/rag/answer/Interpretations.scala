package scholarchain.rag.answer

import scala.util.matching.Regex

/** One interpretation template. Its text is fixed, versioned code. The only variable content is
  * the five placeholders `{subject}`, `{object}`, `{value}`, `{field}` and `{window}`, and each is
  * filled from a verified assertion (see [[InterpretationTemplates.render]]).
  *
  * @param requires
  *   the kind and the fields of the verified assertion the template reads
  */
final case class InterpretationTemplate(
    id: String,
    kind: AssertionKind,
    fields: String => Boolean,
    text: String
)

/** The fixed set of interpretations a model may pick. The model chooses an ID only. A template
  * applies to a claim only when one of the claim's verified assertions has the required kind and
  * field; the text then carries that assertion's verified values and nothing else the model wrote.
  */
object InterpretationTemplates:
  /** The version of this set; recorded with the synthesis schema (its enum is [[ids]]). */
  val Version = "interpretation-templates-v1"

  import AssertionKind.*

  val all: Vector[InterpretationTemplate] = Vector(
    InterpretationTemplate(
      "BREADTH_IS_NOT_INTENSITY",
      METRIC,
      _ == "DISTINCT_COAUTHOR_COUNT",
      "Reading: the stored count of distinct coauthors of {subject} is {value} in window " +
        "{window}. It measures breadth; it does not say how strongly any pair worked together."
    ),
    InterpretationTemplate(
      "INTENSITY_IS_NOT_BREADTH",
      METRIC,
      _ == "WEIGHTED_DEGREE",
      "Reading: the stored weighted degree of {subject} is {value} in window {window}. It " +
        "measures the intensity of coauthorship; it is not the number of distinct coauthors."
    ),
    InterpretationTemplate(
      "TEAM_SIZE_IS_NOT_BREADTH",
      METRIC,
      f => f == "AVERAGE_COAUTHORS_PER_PAPER" || f == "MAXIMUM_COAUTHORS_ON_ONE_PAPER",
      "Reading: {field} of {subject} is {value} in window {window}. It describes team size on " +
        "single papers; it is not the number of distinct coauthors."
    ),
    InterpretationTemplate(
      "EDGE_COUNT_IS_PAIR_STRENGTH",
      COAUTHOR_EDGE,
      _ == "JOINT_PUBLICATION_COUNT",
      "Reading: {subject} and {object} have {value} joint publications in window {window}. The " +
        "stored count is the strength of this one coauthor edge."
    ),
    InterpretationTemplate(
      "BRIDGE_MEASURE_IS_NARROW",
      BRIDGE,
      _ => true,
      "Reading: {field} of {subject} is {value}. A stored bridge measure describes a position " +
        "between communities; it is not a general measure of importance."
    ),
    InterpretationTemplate(
      "IDENTITY_CONFIDENCE_IS_NOT_CERTAINTY",
      IDENTITY,
      _ == "CONFIDENCE",
      "Reading: the stored identity confidence for {subject} is {value}. A value below one " +
        "means that the match is not certain."
    ),
    InterpretationTemplate(
      "CONNECTIVITY_IS_STRUCTURAL",
      COMMUNITY,
      f => f == "COMMUNITY_SIZE" || f == "COMPONENT_SIZE",
      "Reading: {field} of {subject} is {value} in window {window}. A stored community or " +
        "component describes connection structure in the graph; it is not a topic or a field of " +
        "study."
    )
  )

  /** The IDs, sorted: the schema enum. */
  val ids: Vector[String] = all.map(_.id).sorted

  def byId(id: String): Option[InterpretationTemplate] = all.find(_.id == id)

  private val Placeholder: Regex = "\\{(subject|object|value|field|window)\\}".r

  /** The text of template `id` for the first verified assertion it fits, or the reason it does not
    * apply. Placeholders are filled in one pass, so a stored value that itself holds a
    * placeholder is never expanded.
    */
  def render(
      id: String,
      verified: Vector[VerifiedFact],
      label: String => String
  ): Either[String, String] =
    byId(id) match
      case None => Left(s"the interpretation template is unknown")
      case Some(t) =>
        verified.find(v => v.assertion.kind == t.kind && t.fields(v.assertion.field)) match
          case None =>
            Left(s"interpretation template $id does not fit any verified assertion of the claim")
          case Some(v) =>
            val filled = Map(
              "subject" -> label(v.assertion.subjectId),
              "object" -> v.assertion.objectId.filter(_.nonEmpty).fold("")(label),
              "value" -> ClaimRendering.valueText(v).getOrElse(""),
              "field" -> v.assertion.field,
              "window" -> v.window.fold("not stored")(w => s"${w.start}-${w.end}")
            )
            Right(
              Placeholder.replaceAllIn(t.text, m => Regex.quoteReplacement(filled(m.group(1))))
            )
