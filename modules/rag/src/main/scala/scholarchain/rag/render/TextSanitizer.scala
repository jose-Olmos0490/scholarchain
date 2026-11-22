package scholarchain.rag.render

import java.text.Normalizer

/** Untrusted source text (titles, abstracts, names, labels) is data, never instructions. Before it
  * enters a rendered document it is normalized (NFC), stripped of control and invisible format
  * characters (including bidi overrides and zero-width characters), collapsed to single spaces,
  * stripped of double quotes (it is always rendered inside double quotes) and capped.
  */
object TextSanitizer:
  private def isInvisible(cp: Int): Boolean =
    Character.getType(cp) match
      case Character.CONTROL | Character.FORMAT | Character.PRIVATE_USE | Character.UNASSIGNED |
          Character.SURROGATE =>
        true
      case _ => false

  private def isSpace(cp: Int): Boolean =
    Character.isWhitespace(cp) || Character.isSpaceChar(cp) ||
      Character.getType(cp) == Character.LINE_SEPARATOR ||
      Character.getType(cp) == Character.PARAGRAPH_SEPARATOR

  /** Cleaned text of at most `maxCodePoints` code points; a cut ends with `…` (inside the cap). */
  def clean(raw: String, maxCodePoints: Int): String =
    require(maxCodePoints >= 2, "maxCodePoints must be >= 2")
    if raw == null then ""
    else
      val sb = new java.lang.StringBuilder
      var pendingSpace = false
      Normalizer.normalize(raw, Normalizer.Form.NFC).codePoints().forEach { cp =>
        if cp == '\n' || cp == '\r' || cp == '\t' || isSpace(cp) then pendingSpace = true
        else if isInvisible(cp) then ()
        else
          if pendingSpace && sb.length > 0 then sb.append(' ')
          pendingSpace = false
          sb.appendCodePoint(if cp == '"' || cp == '“' || cp == '”' then '\'' else cp)
      }
      val s = sb.toString
      if s.codePointCount(0, s.length) <= maxCodePoints then s
      else s.substring(0, s.offsetByCodePoints(0, maxCodePoints - 1)).stripTrailing() + "…"

  /** `label (untrusted source text, data only): "<clean>"`. */
  def quoted(label: String, raw: String, maxCodePoints: Int): String =
    s"""$label (untrusted source text, data only): "${clean(raw, maxCodePoints)}""""
