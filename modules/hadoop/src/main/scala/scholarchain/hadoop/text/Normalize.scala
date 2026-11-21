package scholarchain.hadoop.text

import java.text.Normalizer
import java.util.Locale

/** Deterministic, locale-independent text normalization used for titles, names and keys.
  *
  * Folding: Unicode NFKD, combining marks and invisible format characters (category Cf, e.g. soft
  * hyphen, zero-width space) removed without splitting the token, lower-cased with `Locale.ROOT`, every run of
  * characters that is not a letter or digit replaced by one space, trimmed. Letters of any script
  * are kept (a CJK or Cyrillic name keeps its letters).
  */
object Normalize:

  /** Collapses every run of whitespace (including newlines) to one space and trims. */
  def collapseWhitespace(s: String): String =
    if s == null then "" else s.trim.replaceAll("\\s+", " ")

  /** True for a code point that renders nothing visible: whitespace (`Character.isWhitespace`),
    * any Unicode space separator (Zs/Zl/Zp, including no-break spaces), an ISO control character
    * (Cc) or an invisible format character (Cf: soft hyphen, ZWSP, ZWNJ, ZWJ, word joiner, BOM,
    * bidi controls, tag characters).
    */
  def isInvisible(cp: Int): Boolean =
    Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.isISOControl(cp) ||
      Character.getType(cp) == Character.FORMAT

  /** True when `s` is null, empty, or made only of [[isInvisible]] code points. This is the one
    * blankness test for required text fields (author names, titles, source string fields);
    * `String.trim` is not used for it because it keeps Cf characters and no-break spaces.
    */
  def isBlank(s: String): Boolean =
    s == null || s.codePoints.allMatch(cp => isInvisible(cp))

  /** `s` with Cf characters removed and invisible code points stripped from both ends (inner
    * whitespace kept). Fallback for a non-blank value whose folded form is empty (for example a
    * name made only of punctuation), so the fallback never keeps invisible edges.
    */
  def stripInvisible(s: String): String =
    if s == null then ""
    else
      val cps = s.codePoints.filter(cp => Character.getType(cp) != Character.FORMAT).toArray
      var from = 0
      var to = cps.length
      while from < to && isInvisible(cps(from)) do from += 1
      while to > from && isInvisible(cps(to - 1)) do to -= 1
      new String(cps, from, to - from)

  /** Spacing form of `s`, with the same invisible-character rule as [[fold]] but no other change:
    * Cf characters are removed without separating tokens, every run of other [[isInvisible]] code
    * points (whitespace, Zs/Zl/Zp including no-break spaces, Cc) becomes one ASCII space, and the
    * result has no leading or trailing space. Letters, digits, punctuation, marks and case are kept
    * exactly (no NFKC, no case folding).
    */
  def spacingNormalized(s: String): String =
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

  /** Identity key of a raw source value (for example a dblp author name used as a source author
    * key): [[spacingNormalized]], then Unicode NFC. Two raw values that differ only by Cf
    * characters, by the kind or length of invisible spacing, or by canonically equivalent
    * spellings get the same key; case, compatibility forms (ligatures, full-width letters) and
    * the dblp homonym suffix stay significant. Used only for in-run comparisons (team
    * de-duplication, the `sourceAuthorKeyEqual` feature); never part of a stable-ID formula and
    * never stored in place of the raw value.
    */
  def identityKey(s: String): String =
    Normalizer.normalize(spacingNormalized(s), Normalizer.Form.NFC)

  /** Fallback normalized form for a non-blank value whose [[fold]] is empty: [[stripInvisible]],
    * lower-cased with `Locale.ROOT`.
    */
  def fallbackLower(s: String): String = stripInvisible(s).toLowerCase(Locale.ROOT)

  /** Folded form: see the object doc. */
  def fold(s: String): String =
    if s == null then ""
    else
      val decomposed = Normalizer.normalize(s, Normalizer.Form.NFKD)
      val sb = new StringBuilder(decomposed.length)
      var i = 0
      var pendingSpace = false
      while i < decomposed.length do
        val cp = decomposed.codePointAt(i)
        val t = Character.getType(cp)
        val isMark =
          t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK ||
            t == Character.ENCLOSING_MARK
        // Invisible format characters (Unicode category Cf: soft hyphen U+00AD, zero-width space
        // U+200B, ZWNJ/ZWJ, word joiner, BOM...) are dropped without separating tokens.
        if isMark || t == Character.FORMAT then ()
        else if Character.isLetterOrDigit(cp) then
          if pendingSpace && sb.nonEmpty then sb.append(' ')
          pendingSpace = false
          sb.append(new String(Character.toChars(cp)).toLowerCase(Locale.ROOT))
        else pendingSpace = true
        i += Character.charCount(cp)
      sb.toString

  /** Normalized work title (folded). */
  def title(raw: String): String = fold(raw)

  // Applied to the spacingNormalized form, where every separator is exactly one ASCII space.
  private val DblpHomonymSuffix = " [0-9]{4}$".r

  /** Strips the dblp homonym suffix (`Manish Singh 0001` -> `Manish Singh`) from the
    * [[spacingNormalized]] form of `name`. Detection therefore ignores Cf characters anywhere
    * (`Wang 0001` + ZWSP, `Wang 00` + soft hyphen + `01`) and accepts any invisible separator
    * before the number (NBSP, ideographic space, tab), exactly as [[fold]] would see the name.
    */
  def stripHomonymSuffix(name: String): String =
    DblpHomonymSuffix.replaceFirstIn(spacingNormalized(name), "")

  /** Normalized person name: homonym suffix removed, then folded. */
  def personName(raw: String): String = fold(stripHomonymSuffix(raw))

  /** Surname and first initial of a person name, from its normalized form. The surname is the
    * last token; the initial is the first character of the first token. A single-token name has
    * itself as surname and its own first character as initial. `None` for an empty name.
    */
  def surnameAndInitial(raw: String): Option[(String, String)] =
    val tokens = personName(raw).split(' ').toVector.filter(_.nonEmpty)
    if tokens.isEmpty then None
    else
      val surname = tokens.last
      val first = tokens.head
      Some((surname, first.substring(0, first.offsetByCodePoints(0, 1))))

  /** Surname only (last normalized token). */
  def surname(raw: String): Option[String] = surnameAndInitial(raw).map(_._1)

  /** Tokens of a normalized title. */
  def titleTokens(raw: String): Vector[String] =
    title(raw).split(' ').toVector.filter(_.nonEmpty)
