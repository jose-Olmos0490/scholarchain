package scholarchain.er.text

import java.text.Normalizer
import java.util.Locale

/** Entity-resolution text normalizers (policy er-v1). Pure, deterministic, locale-independent.
  *
  * Used only for comparison features. Never used to build stable IDs and never written back into
  * a source record: raw values are kept unchanged in every view and decision.
  */
object TextNormalize:

  /** Letters that Unicode NFKD does not decompose into ASCII base letters, folded to their
    * conventional Latin transliteration so that `Ø`/`O`, `ß`/`ss` and `ł`/`l` compare equal.
    */
  private val ExtraFold: Map[Int, String] = Map(
    'ß'.toInt -> "ss",
    'ø'.toInt -> "o",
    'Ø'.toInt -> "o",
    'ł'.toInt -> "l",
    'Ł'.toInt -> "l",
    'đ'.toInt -> "d",
    'Đ'.toInt -> "d",
    'æ'.toInt -> "ae",
    'Æ'.toInt -> "ae",
    'œ'.toInt -> "oe",
    'Œ'.toInt -> "oe",
    'ı'.toInt -> "i",
    'þ'.toInt -> "th",
    'Þ'.toInt -> "th"
  )

  /** True for a code point that renders nothing visible: whitespace, a Unicode space separator
    * (including no-break spaces), an ISO control or an invisible format character (Cf).
    */
  def isInvisible(cp: Int): Boolean =
    Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.isISOControl(cp) ||
      Character.getType(cp) == Character.FORMAT

  /** Unicode folding. NFKD; combining marks (Mn, Mc, Me) and format characters (Cf) removed
    * without splitting the token; [[ExtraFold]] letters transliterated; lower-cased with
    * `Locale.ROOT`; every run of characters that is not a letter or digit replaced by one space;
    * trimmed. Letters of any script are kept. `null` folds to "".
    */
  def fold(s: String): String =
    if s == null then ""
    else
      val nfkd = Normalizer.normalize(s, Normalizer.Form.NFKD)
      val sb = new java.lang.StringBuilder(nfkd.length)
      var pendingSpace = false
      nfkd.codePoints.forEach { cp =>
        val t = Character.getType(cp)
        if t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK ||
          t == Character.ENCLOSING_MARK || t == Character.FORMAT
        then ()
        else
          val piece: String = ExtraFold.get(cp) match
            case Some(x) => x
            case None =>
              if Character.isLetterOrDigit(cp) then
                new String(Character.toChars(cp)).toLowerCase(Locale.ROOT)
              else ""
          if piece.isEmpty then pendingSpace = true
          else
            if pendingSpace && sb.length > 0 then sb.append(' ')
            pendingSpace = false
            sb.append(piece)
      }
      sb.toString

  /** Whitespace normalization: every run of invisible code points (see [[isInvisible]]) becomes
    * one ASCII space, Cf characters are dropped without splitting tokens, and the result is
    * trimmed. Case, punctuation and letters are kept.
    */
  def collapseWhitespace(s: String): String =
    if s == null then ""
    else
      val sb = new java.lang.StringBuilder(s.length)
      var pendingSpace = false
      s.codePoints.forEach { cp =>
        if Character.getType(cp) == Character.FORMAT then ()
        else if isInvisible(cp) then pendingSpace = true
        else
          if pendingSpace && sb.length > 0 then sb.append(' ')
          pendingSpace = false
          sb.appendCodePoint(cp)
      }
      sb.toString

  /** Punctuation removal on an already folded or raw string: every character that is neither a
    * letter, a digit nor whitespace becomes a space; whitespace is then collapsed.
    */
  def stripPunctuation(s: String): String =
    if s == null then ""
    else
      val cps = s.codePoints
        .map(cp => if Character.isLetterOrDigit(cp) || isInvisible(cp) then cp else ' '.toInt)
        .toArray
      collapseWhitespace(new String(cps, 0, cps.length))

  private val Tag = "<[^<>]{0,200}>".r
  private val Entity = "&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,6});".r
  private val NamedEntities = Map(
    "&amp;" -> "&",
    "&lt;" -> "<",
    "&gt;" -> ">",
    "&quot;" -> "\"",
    "&apos;" -> "'",
    "&nbsp;" -> " "
  )
  private val LatexCommand = "\\\\[a-zA-Z]+\\s*\\{([^{}]*)\\}".r
  private val LatexBareCommand = "\\\\[a-zA-Z]+".r

  private def safeCodePoint(cp: Int): String =
    if Character.isValidCodePoint(cp) && !(cp >= 0xd800 && cp <= 0xdfff) then
      new String(Character.toChars(cp))
    else " "

  /** Title markup removal: XML/HTML/MathML/JATS tags (`<i>`, `<sub>`, `<mml:math>`) removed
    * (a tag boundary separates nothing: `H<sub>2</sub>O` becomes `H2O`), character entities
    * decoded once, LaTeX commands with a braced argument replaced by the argument, remaining
    * bare commands, `$` and braces removed, and a single trailing period (dblp convention)
    * dropped. Whitespace collapsed. Does not fold case.
    */
  def stripTitleMarkup(s: String): String =
    if s == null then ""
    else
      val noTags = Tag.replaceAllIn(s, "")
      // One left-to-right pass: a decoded '&' never starts another entity.
      val decoded = Entity.replaceAllIn(
        noTags,
        m =>
          val body = m.group(1)
          val text =
            if body.startsWith("#x") || body.startsWith("#X") then
              safeCodePoint(Integer.parseInt(body.drop(2), 16))
            else if body.startsWith("#") then safeCodePoint(body.drop(1).toInt)
            else NamedEntities.getOrElse(s"&$body;", m.matched)
          java.util.regex.Matcher.quoteReplacement(text)
      )
      var latex = decoded
      var guard = 0
      while LatexCommand.findFirstIn(latex).isDefined && guard < 8 do
        latex = LatexCommand.replaceAllIn(
          latex,
          m => java.util.regex.Matcher.quoteReplacement(m.group(1))
        )
        guard += 1
      val bare = LatexBareCommand
        .replaceAllIn(latex, "")
        .replace("$", "")
        .replace("{", "")
        .replace("}", "")
      val collapsed = collapseWhitespace(bare)
      if collapsed.endsWith(".") && !collapsed.endsWith("..") then collapsed.dropRight(1).trim
      else collapsed

  /** Normalized title: markup stripped, then folded. */
  def normalizeTitle(s: String): String = fold(stripTitleMarkup(s))

  /** Tokens of a normalized title. */
  def titleTokens(s: String): Vector[String] =
    normalizeTitle(s).split(' ').toVector.filter(_.nonEmpty)

  private val HomonymSuffix = "\\s+[0-9]{4}$".r

  /** Removes the dblp homonym suffix (` 0001`) from a raw name, after whitespace collapsing. */
  def stripHomonymSuffix(raw: String): String =
    HomonymSuffix.replaceFirstIn(collapseWhitespace(raw), "")

  /** Lower-case surname particles kept with the surname (`van der Berg`, `de la Cruz`). */
  val SurnameParticles: Set[String] =
    Set(
      "van",
      "von",
      "der",
      "den",
      "de",
      "del",
      "della",
      "da",
      "di",
      "du",
      "la",
      "le",
      "ter",
      "ten",
      "dos",
      "das",
      "bin",
      "ibn",
      "al",
      "el",
      "st",
      "vander",
      "vanden"
    )

  /** Generational suffixes ignored in surname comparison. */
  val NameSuffixes: Set[String] = Set("jr", "sr", "ii", "iii", "iv")

  /** A person name split into given-name tokens and surname tokens, all folded.
    *
    * @param givenNames
    *   folded given-name tokens in order; a single letter is an initial
    * @param surname
    *   folded surname tokens including particles (`van der berg` is three tokens)
    */
  final case class PersonName(givenNames: Vector[String], surname: Vector[String]):
    /** Surname without particles: its last token (`berg`); "" when there is no surname. */
    def surnameKey: String = surname.lastOption.getOrElse("")

    /** First letter of every given-name token (`j r r`). */
    def initials: Vector[String] =
      givenNames.filter(_.nonEmpty).map(t => t.substring(0, t.offsetByCodePoints(0, 1)))

    /** `given surname` as one folded string. */
    def full: String = (givenNames ++ surname).mkString(" ")

    /** Comparison key `<surnameKey> <first initial>`; "" for an empty name. */
    def key: String =
      if surnameKey.isEmpty then "" else (surnameKey +: initials.take(1)).mkString(" ")

  /** Parses a raw person name.
    *
    * Name order: `Last, First` (one comma) is reordered to given + surname; otherwise the name
    * is read as `Given ... Surname`. The dblp homonym suffix and generational suffixes (`Jr.`)
    * are removed. Initials: `J.R.R. Tolkien` and `J. R. R. Tolkien` both give the given tokens
    * `j r r` (dotted initials are split before folding). Particles immediately before the last
    * token join the surname. A single-token name is a surname.
    */
  def parseName(raw: String): PersonName =
    val base = stripHomonymSuffix(if raw == null then "" else raw)
    // Split dotted initials ("J.R.R.") into separate tokens before folding removes the dots.
    val dotted = base.replaceAll("(\\p{L})\\.(?=\\p{L})", "$1. ")
    val commaIdx = dotted.indexOf(',')
    val (givenRaw, surnameRaw): (Option[String], Option[String]) =
      if commaIdx >= 0 && dotted.indexOf(',', commaIdx + 1) < 0 then
        val last = dotted.substring(0, commaIdx)
        val first = dotted.substring(commaIdx + 1)
        val firstTokens = fold(first).split(' ').filter(_.nonEmpty)
        // "Smith, Jr." is not name order: a suffix-only tail is not a given name.
        if firstTokens.nonEmpty && firstTokens.forall(NameSuffixes.contains) then (None, None)
        else (Some(first), Some(last))
      else (None, None)
    def tokens(s: String): Vector[String] =
      fold(s).split(' ').toVector.filter(t => t.nonEmpty && !NameSuffixes.contains(t))
    (givenRaw, surnameRaw) match
      case (Some(g), Some(s)) => PersonName(tokens(g), tokens(s))
      case _ =>
        val all = tokens(dotted)
        if all.isEmpty then PersonName(Vector.empty, Vector.empty)
        else if all.size == 1 then PersonName(Vector.empty, all)
        else
          var split = all.size - 1
          while split > 1 && SurnameParticles.contains(all(split - 1)) do split -= 1
          PersonName(all.take(split), all.drop(split))

  /** Normalized venue string: markup stripped, folded, year-like and ordinal tokens removed
    * (`DAC '26` and `DAC 2003` both give `dac`).
    */
  def normalizeVenue(s: String): String =
    fold(stripTitleMarkup(s))
      .split(' ')
      .filter(t => t.nonEmpty && !t.forall(Character.isDigit) && !t.matches("[0-9]+(st|nd|rd|th)"))
      .mkString(" ")

  /** Common words that carry no venue or affiliation identity. */
  val StopWords: Set[String] =
    Set(
      "of",
      "the",
      "and",
      "for",
      "on",
      "in",
      "at",
      "a",
      "an",
      "de",
      "la",
      "des",
      "und",
      "fur",
      "der",
      "di",
      "proceedings",
      "proc",
      "acm",
      "ieee",
      "international",
      "conference",
      "annual",
      "journal",
      "symposium",
      "workshop"
    )

  /** Acronym of a venue name: first letters of its non-stop-word tokens
    * (`Design Automation Conference` gives `dac`).
    */
  def venueAcronym(normalizedVenue: String): String =
    normalizedVenue
      .split(' ')
      .filter(t => t.nonEmpty && !AcronymSkip.contains(t))
      .map(_.take(1))
      .mkString

  private val AcronymSkip: Set[String] =
    Set(
      "of",
      "the",
      "and",
      "for",
      "on",
      "in",
      "at",
      "a",
      "an",
      "proceedings",
      "proc",
      "acm",
      "ieee",
      "international",
      "annual"
    )

  /** Affiliation tokens: folded, stop words removed. */
  def affiliationTokens(s: String): Set[String] =
    fold(s).split(' ').filter(t => t.nonEmpty && !StopWords.contains(t)).toSet
