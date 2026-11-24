package scholarchain.storage.directory

import java.text.Normalizer
import java.util.Locale
import scholarchain.core.model.CanonicalResearcher

/** The name form the researcher directory is keyed by.
  *
  * `fold` is the same fold the runtime resolver applies to the name a person typed (NFKD, combining
  * marks and invisible format characters dropped, letters and digits lower-cased, every other run of
  * characters one space). It is duplicated here because `storage` cannot depend on `rag`; a test
  * pins both to the same cases, and a single shared implementation belongs in `core`.
  *
  * DBLP disambiguates homonyms by appending a four-digit number to the name ("Wei Wang 0001"), and
  * the canonical researcher's `displayName` is a DBLP author key. A researcher is therefore indexed
  * under the folded name with the suffix (what the key literally says) and without it (what a person
  * types). The directory never guesses beyond that: every other variant is an exact miss.
  */
object NameFold:
  private val HomonymSuffix = "\\s+[0-9]{4}$".r

  def fold(raw: String): String =
    if raw == null then ""
    else
      val d = Normalizer.normalize(raw, Normalizer.Form.NFKD)
      val sb = new StringBuilder
      var pendingSpace = false
      var i = 0
      while i < d.length do
        val cp = d.codePointAt(i)
        val t = Character.getType(cp)
        val mark =
          t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK ||
            t == Character.ENCLOSING_MARK
        if mark || t == Character.FORMAT then ()
        else if Character.isLetterOrDigit(cp) then
          if pendingSpace && sb.nonEmpty then sb.append(' ')
          pendingSpace = false
          sb.append(new String(Character.toChars(cp)).toLowerCase(Locale.ROOT))
        else pendingSpace = true
        i += Character.charCount(cp)
      sb.toString

  /** The folded names a canonical researcher is indexed under: its display name and each DBLP
    * author key, each with and without the homonym suffix; distinct, non-blank, sorted.
    */
  def namesOf(r: CanonicalResearcher): Vector[String] =
    (r.displayName +: r.dblpAuthorKeys)
      .flatMap { raw =>
        Vector(fold(raw), fold(HomonymSuffix.replaceFirstIn(raw.trim, "")))
      }
      .filter(_.nonEmpty)
      .distinct
      .sorted
