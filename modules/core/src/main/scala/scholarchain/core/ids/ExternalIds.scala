package scholarchain.core.ids

import java.util.Locale

/** Normalization and validation of external identifiers used as stable-ID inputs.
  *
  * Each function returns the normalized value or a reason for rejection. Normalization happens
  * before hashing, so that `https://doi.org/10.1145/X` and `10.1145/x` yield the same work ID. A
  * `null` input is rejected with a `Left`; no function throws.
  */
object ExternalIds:

  private def stripPrefixIgnoreCase(value: String, prefixes: Seq[String]): String =
    prefixes
      .find(p => value.regionMatches(true, 0, p, 0, p.length))
      .map(p => value.substring(p.length))
      .getOrElse(value)

  private def nullInput(what: String): Left[String, String] = Left(s"$what must not be null")

  private val DoiPattern = "10\\.[0-9]{4,9}/\\S+".r

  /** DOI: strips resolver or `doi:` prefixes, lower-cases, requires `10.<registrant>/<suffix>`.
    */
  def normalizeDoi(raw: String): Either[String, String] =
    if raw == null then nullInput("DOI")
    else
      val v = stripPrefixIgnoreCase(
        raw.trim,
        Seq(
          "https://doi.org/",
          "http://doi.org/",
          "https://dx.doi.org/",
          "http://dx.doi.org/",
          "doi:"
        )
      ).toLowerCase(Locale.ROOT)
      if DoiPattern.matches(v) then Right(v) else Left(s"malformed DOI '$raw'")

  private val OrcidPattern = "[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9X]".r

  /** ORCID iD: strips the orcid.org resolver, requires the 16-character hyphenated form and a
    * valid ISO 7064 MOD 11-2 check character.
    */
  def normalizeOrcid(raw: String): Either[String, String] =
    if raw == null then nullInput("ORCID iD")
    else
      val v = stripPrefixIgnoreCase(raw.trim, Seq("https://orcid.org/", "http://orcid.org/"))
        .toUpperCase(Locale.ROOT)
      if !OrcidPattern.matches(v) then Left(s"malformed ORCID iD '$raw'")
      else
        val digits = v.filter(_ != '-')
        val total = digits.init.foldLeft(0)((acc, c) => (acc + (c - '0')) * 2)
        val result = (12 - total % 11) % 11
        val expected = if result == 10 then 'X' else ('0' + result).toChar
        if digits.last == expected then Right(v)
        else Left(s"ORCID iD '$raw' fails its checksum")

  /** OpenAlex ID: strips the openalex.org resolver, requires `<entityLetter><digits>`. Entity
    * letters: W work, A author, I institution, S source, T topic.
    */
  def normalizeOpenAlex(raw: String, entityLetter: Char): Either[String, String] =
    if raw == null then nullInput("OpenAlex ID")
    else
      val v =
        stripPrefixIgnoreCase(raw.trim, Seq("https://openalex.org/", "http://openalex.org/"))
          .toUpperCase(Locale.ROOT)
      val pattern = s"$entityLetter[1-9][0-9]*".r
      if pattern.matches(v) then Right(v)
      else Left(s"malformed OpenAlex ID '$raw' (expected entity '$entityLetter')")

  private val DblpKeyPattern = "[a-z]+(/[^\\s/]+)+".r

  /** dblp record key such as `conf/dac/NewtonRMSB00`. Case-sensitive; only trimmed. */
  def normalizeDblpKey(raw: String): Either[String, String] =
    if raw == null then nullInput("dblp key")
    else
      val v = raw.trim
      if DblpKeyPattern.matches(v) then Right(v) else Left(s"malformed dblp key '$raw'")

  private val DblpPidNumeric = "[0-9]+/[0-9]+(-[0-9]+)?".r
  private val DblpPidLegacy = "[a-z]/[A-Za-z][A-Za-z0-9=_.'-]*".r
  private val DblpPidUrlPrefixes = Seq("https://dblp.org/pid/", "http://dblp.org/pid/")

  /** dblp person identifier (pid), returned in its bare form, for example `71/4882` or
    * `71/4882-1`. Case-sensitive; no case folding.
    *
    * Evidence and assumptions. The committed DTD (`dblp-2023-06-28.dtd`) and the DBLP sample
    * define no pid syntax; the only committed reference is the URL template
    * `https://dblp.org/pid/<pid>.xml` (`datasets/schemas/dblp.md`). The accepted forms below are an
    * ASSUMPTION from dblp's public pages, not verified against a committed sample:
    *   - numeric: `<digits>/<digits>`, with an optional homonym suffix `-<digits>`
    *   - legacy name-based: `<lower-case letter>/<name>`, name = a letter followed by letters,
    *     digits or `= _ . ' -`
    *
    * Accepted spellings, all normalized to the bare pid: the bare pid; the person page URL
    * `https://dblp.org/pid/<pid>` with an optional `.xml` or `.html` suffix (cited template); and
    * the person `www` record key `homepages/<pid>` (assumption).
    */
  def normalizeDblpPid(raw: String): Either[String, String] =
    if raw == null then nullInput("dblp pid")
    else
      val trimmed = raw.trim
      val v = DblpPidUrlPrefixes.find(p => trimmed.regionMatches(true, 0, p, 0, p.length)) match
        case Some(p) => trimmed.substring(p.length).stripSuffix(".xml").stripSuffix(".html")
        case None => trimmed.stripPrefix("homepages/")
      if DblpPidNumeric.matches(v) || DblpPidLegacy.matches(v) then Right(v)
      else Left(s"malformed dblp pid '$raw'")

  private val DblpVenuePattern = "[a-z]+/[^\\s/]+".r

  /** dblp venue stream key such as `conf/dac` or `journals/cacm`. */
  def normalizeDblpVenueKey(raw: String): Either[String, String] =
    if raw == null then nullInput("dblp venue key")
    else
      val v = raw.trim
      if DblpVenuePattern.matches(v) then Right(v) else Left(s"malformed dblp venue key '$raw'")

  private val ArxivNew = "[0-9]{4}\\.[0-9]{4,5}".r
  private val ArxivOld = "[a-z-]+(\\.[A-Z]{2})?/[0-9]{7}".r

  /** arXiv identifier, new (`2101.00001`) or old (`cs.DC/0101001`) style. The version suffix
    * (`v2`) is removed: a work ID identifies the work, not one revision.
    */
  def normalizeArxiv(raw: String): Either[String, String] =
    if raw == null then nullInput("arXiv ID")
    else
      val noPrefix = stripPrefixIgnoreCase(
        raw.trim,
        Seq("https://arxiv.org/abs/", "http://arxiv.org/abs/", "arxiv:")
      )
      val v = noPrefix.replaceFirst("v[0-9]+$", "")
      if ArxivNew.matches(v) || ArxivOld.matches(v) then Right(v)
      else Left(s"malformed arXiv ID '$raw'")

  private val RorPattern = "0[0-9a-hjkmnp-tv-z]{6}[0-9]{2}".r

  /** ROR ID body (`03yrm5c26`), with or without the ror.org resolver. */
  def normalizeRor(raw: String): Either[String, String] =
    if raw == null then nullInput("ROR ID")
    else
      val v = stripPrefixIgnoreCase(raw.trim, Seq("https://ror.org/", "http://ror.org/"))
        .toLowerCase(Locale.ROOT)
      if RorPattern.matches(v) then Right(v) else Left(s"malformed ROR ID '$raw'")

  private val IssnPattern = "[0-9]{4}-[0-9]{3}[0-9X]".r

  /** ISSN `NNNN-NNNC` with a valid MOD 11 check character. */
  def normalizeIssn(raw: String): Either[String, String] =
    if raw == null then nullInput("ISSN")
    else
      val v = raw.trim.toUpperCase(Locale.ROOT)
      if !IssnPattern.matches(v) then Left(s"malformed ISSN '$raw'")
      else
        val digits = v.filter(_ != '-')
        val sum = digits.init.zipWithIndex.map((c, i) => (c - '0') * (8 - i)).sum
        val check = (11 - sum % 11) % 11
        val expected = if check == 10 then 'X' else ('0' + check).toChar
        if digits.last == expected then Right(v) else Left(s"ISSN '$raw' fails its checksum")
