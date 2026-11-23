package scholarchain.rag.answer

import java.text.Normalizer
import scholarchain.rag.render.TextSanitizer

/** The one normal form of a text a person will read. A text is normalized (NFKC; then control,
  * format, zero-width and bidi characters are removed and white space is collapsed) before it is
  * shown, so that what is checked is exactly what is shown.
  *
  * The model's own text is never shown: only stored names and code-written sentences pass through
  * here.
  */
object ShownText:
  /** Normalized text of at most `maxCodePoints` code points. The result is stable: normalizing it
    * again returns it unchanged. A cut ends with three ASCII dots (inside the cap), because NFKC
    * would turn the single ellipsis character into them.
    */
  def normalize(raw: String, maxCodePoints: Int): String =
    require(maxCodePoints >= 4, "maxCodePoints must be >= 4")
    if raw == null then ""
    else
      // two passes: removing an invisible character can join a sequence that NFKC then folds
      val first = TextSanitizer.clean(Normalizer.normalize(raw, Normalizer.Form.NFKC), Int.MaxValue)
      val all = TextSanitizer.clean(Normalizer.normalize(first, Normalizer.Form.NFKC), Int.MaxValue)
      if all.codePointCount(0, all.length) <= maxCodePoints then all
      else all.substring(0, all.offsetByCodePoints(0, maxCodePoints - 3)).stripTrailing() + "..."

/** An assertion that the verifier accepted, with what the stores hold for it.
  *
  * @param value
  *   the stored value (a stored gap is [[FactValue.Gap]]); `None` for an assertion that has no
  *   stored value (a window, a statement backed by context only)
  * @param window
  *   the STORED window of the fact that supported the assertion, never the asserted one
  */
final case class VerifiedFact(
    assertion: Assertion,
    value: Option[FactValue],
    window: Option[Window]
)

/** Everything a person reads as a fact is written here, by code, from verified assertions: the
  * researcher names and IDs, the numbers, the relation (coauthor edge, path hop, community,
  * identity) and the window. The model chooses which assertions to present and in what order;
  * it supplies no word of the sentence.
  *
  * Rendering rules: a number is the stored value, written with up to six decimals, rounded half
  * up, trailing zeros removed (a whole number as is). A researcher is written as its ID, preceded
  * by the stored name (between the delimiter pair, as data) when the evidence holds one. A window is the window of
  * the stored fact.
  */
object ClaimRendering:
  private val NotStored = "not stored"

  /** The delimiter pair of stored text inside a code-written sentence. */
  val Open = '\u00AB'
  val Close = '\u00BB'

  /** Stored text between the delimiter pair. The pair is removed from the text first, so the
    * span between the first Open and the next Close is exactly the stored text, and nothing the
    * text holds (quotes, back ticks, braces) can end the span.
    */
  def quote(stored: String): String =
    s"$Open${stored.filterNot(c => c == Open || c == Close)}$Close"

  /** The stored value of a verified assertion as text; `None` when the fact has no value. */
  def valueText(v: VerifiedFact): Option[String] = v.value.map {
    case FactValue.Num(d) => ClaimVerificationAgent.show(d)
    case FactValue.Text(t) => quote(ShownText.normalize(t, 200))
    case FactValue.Gap => NotStored
  }

  /** Stored names by researcher ID, from the identity evidence of the bundle. */
  def namesOf(facts: Vector[ItemFacts]): Map[String, String] =
    facts
      .filter(_.graph)
      .flatMap(_.facts)
      .collect { case Fact(AssertionKind.IDENTITY, id, _, "NAME", FactValue.Text(n), _, _) =>
        id -> ShownText.normalize(n, 100)
      }
      .filter(_._2.nonEmpty)
      .groupBy(_._1)
      .collect { case (id, names) if names.map(_._2).distinct.size == 1 => id -> names.head._2 }

  def labeller(names: Map[String, String]): String => String = id =>
    val clean = ShownText.normalize(id, 200)
    names.get(id).fold(clean)(n => s"${quote(n)} ($clean)")

  def window(w: Option[Window]): String = w.fold("")(x => s" (window ${x.start}-${x.end})")

  /** The sentence for one verified assertion. */
  def sentence(v: VerifiedFact, label: String => String): String =
    val a = v.assertion
    val s = label(a.subjectId)
    val o = a.objectId.filter(_.nonEmpty).map(label).getOrElse("")
    val value = valueText(v).getOrElse("")
    val win = window(v.window)
    (a.kind, a.field) match
      case (AssertionKind.WINDOW, _) =>
        v.window.fold("The analysis window is not stored.")(w =>
          s"The analysis window is ${w.start}-${w.end}."
        )
      case (AssertionKind.PUBLICATION, "DESCRIBED_IN_CONTEXT") =>
        s"Retrieved context mentions $s; this is not a stored graph fact."
      case (AssertionKind.PUBLICATION, _) =>
        s"Work $s is a stored supporting work of edge $value$win."
      case (AssertionKind.COAUTHOR_EDGE, "COAUTHOR") =>
        s"$s and $o are coauthors in the stored graph (edge $value)$win."
      case (AssertionKind.PATH_HOP, "HOP") =>
        s"$s and $o are joined by a stored path hop (edge $value)$win."
      case (AssertionKind.PATH_HOP, "PATH_LENGTH") =>
        s"The stored shortest path between $s and $o has $value hops$win."
      case (AssertionKind.PATH_HOP, "PATH_WITHIN_HOPS") =>
        s"A stored path of at most $value hops joins $s and $o$win."
      case (_, "COMMUNITY_ID") => s"$s is in stored community $value$win."
      case (_, "COMPONENT_ID") => s"$s is in stored component $value$win."
      case (AssertionKind.IDENTITY, "NAME") => s"$s is stored under the name $value."
      case _ =>
        val who = if o.isEmpty then s else s"$s and $o"
        s"$who: ${a.field} = $value$win."

  /** A removed claim, described for a diagnostic: what was requested, labeled as not verified.
    * Nothing here is shown as a fact.
    */
  def requested(c: SynthClaim): String =
    val items = c.assertions.map { a =>
      val who = a.objectId.filter(_.nonEmpty).fold(a.subjectId)(o => s"${a.subjectId}/$o")
      val v = a.value match
        case AssertedValue.Num(d) => ClaimVerificationAgent.show(d)
        case AssertedValue.Text(t) => t
        case AssertedValue.Absent => "none"
      val w = a.window.fold("")(x => s" ${x.start}-${x.end}")
      s"${a.kind}/${a.field} $who = $v$w"
    }
    TextSanitizer.clean(s"not verified, requested: ${items.mkString("; ")}", 500)
