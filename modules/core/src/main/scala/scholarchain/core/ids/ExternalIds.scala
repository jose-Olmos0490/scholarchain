package scholarchain.core.ids

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.util.Locale
import scholarchain.core.model.ExactIdType

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

  private val DoiResolverPrefixes = Seq(
    "https://doi.org/",
    "http://doi.org/",
    "https://dx.doi.org/",
    "http://dx.doi.org/"
  )

  /** Strict, single-pass RFC 3986 percent-decoding as UTF-8. Every `%` must start a `%HH` escape
    * of two hex digits, and the decoded bytes must be well-formed UTF-8; otherwise `None`. `+` is
    * left as is (it is not a space outside form encoding). Never throws.
    */
  private[ids] def percentDecodeStrict(s: String): Option[String] =
    if s.indexOf('%') < 0 then Some(s)
    else
      val out = new ByteArrayOutputStream(s.length)
      val n = s.length
      var i = 0
      var ok = true
      while ok && i < n do
        if s.charAt(i) == '%' then
          val hi = if i + 1 < n then Character.digit(s.charAt(i + 1), 16) else -1
          val lo = if i + 2 < n then Character.digit(s.charAt(i + 2), 16) else -1
          if hi < 0 || lo < 0 then ok = false
          else
            out.write((hi << 4) | lo)
            i += 3
        else
          // Copy the literal run up to the next '%' as UTF-8 (keeps surrogate pairs together).
          val next = s.indexOf('%', i)
          val end = if next < 0 then n else next
          val bytes = s.substring(i, end).getBytes(StandardCharsets.UTF_8)
          out.write(bytes, 0, bytes.length)
          i = end
      if !ok then None
      else
        val decoder = StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
        try Some(decoder.decode(ByteBuffer.wrap(out.toByteArray)).toString)
        catch case _: CharacterCodingException => None

  /** DOI: strips resolver or `doi:` prefixes, lower-cases, requires `10.<registrant>/<suffix>`.
    *
    * Percent-encoding (DOI Handbook: a DOI in URL form is percent-encoded and denotes the
    * same DOI as its decoded form). Rules:
    *   - Decoding applies ONLY to the resolver URL forms (`http(s)://doi.org/`,
    *     `http(s)://dx.doi.org/`). A bare DOI or a `doi:` DOI is not a URL, and `%` is a legal
    *     character in a DOI suffix, so those forms are never decoded.
    *   - Decoding is a single strict pass, UTF-8, before lower-casing (so `%3A` and `%3a` agree).
    *     `%25` decodes to a literal `%` and is not decoded again.
    *   - `%2F` decodes to `/`, as doi.org does; `10.1000%2Fabc` and `10.1000/abc` are one DOI.
    *   - A malformed escape (`%` not followed by two hex digits) or bytes that are not well-formed
    *     UTF-8 make the URL invalid: the result is `Left("malformed DOI ...")`. Nothing throws.
    *
    * Non-graphic characters (see `isNonGraphic`) are rejected in every form (URL, `doi:` and
    * bare) after decoding and trimming. A bare DOI whose only control characters are leading or
    * trailing has them removed by `trim` and is accepted. Every accepted value `v` is a fixed
    * point: `normalizeDoi(v) == Right(v)`.
    */
  def normalizeDoi(raw: String): Either[String, String] =
    if raw == null then nullInput("DOI")
    else
      val trimmed = raw.trim
      val decoded: Option[String] =
        DoiResolverPrefixes.find(p => trimmed.regionMatches(true, 0, p, 0, p.length)) match
          case Some(p) => percentDecodeStrict(trimmed.substring(p.length))
          case None => Some(stripPrefixIgnoreCase(trimmed, Seq("doi:")))
      decoded match
        case None => Left(s"malformed DOI '$raw' (invalid percent-encoding)")
        case Some(d) =>
          firstNonGraphic(d) match
            case Some(cp) =>
              Left(f"malformed DOI '${escapeNonGraphic(raw)}' (non-graphic character U+$cp%04X)")
            case None =>
              val v = d.toLowerCase(Locale.ROOT)
              if DoiPattern.matches(v) then Right(v) else Left(s"malformed DOI '$raw'")

  /** The first code point of `s` that may not appear in a DOI, if any. Rejected: ISO control
    * characters (C0 U+0000-U+001F, DEL U+007F, C1 U+0080-U+009F), Unicode separators (general
    * categories Zs, Zl, Zp, for example U+00A0 or U+2028), and unpaired surrogates. A DOI name is
    * made of graphic characters only; these code points are invisible, are partly removed by
    * `String.trim` (which would make normalization non-idempotent), or cannot be encoded as UTF-8
    * for hashing. Format characters (Cf, for example ZWJ) and unassigned code points are not
    * rejected: Cf is legitimate inside some scripts, and the set of unassigned code points depends
    * on the JDK's Unicode version, which would make normalization JDK-dependent.
    */
  private def isNonGraphic(cp: Int): Boolean =
    Character.isISOControl(cp) || (cp >= 0xd800 && cp <= 0xdfff) || {
      val t = Character.getType(cp)
      t == Character.SPACE_SEPARATOR || t == Character.LINE_SEPARATOR ||
      t == Character.PARAGRAPH_SEPARATOR
    }

  private def firstNonGraphic(s: String): Option[Int] =
    s.codePoints().toArray.find(isNonGraphic)

  /** Makes non-graphic characters visible in a rejection message (`\\uXXXX`). */
  private def escapeNonGraphic(s: String): String =
    s.codePoints()
      .toArray
      .map(cp => if isNonGraphic(cp) then f"\\u$cp%04X" else new String(Character.toChars(cp)))
      .mkString

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

  /** Normalizes `raw` with the normalizer that defines the normalized form of `idType`. */
  def normalizeExactId(idType: ExactIdType, raw: String): Either[String, String] =
    if idType == null then Left("idType must not be null")
    else
      idType match
        case ExactIdType.DOI => normalizeDoi(raw)
        case ExactIdType.ARXIV => normalizeArxiv(raw)
        case ExactIdType.DBLP_KEY => normalizeDblpKey(raw)
        case ExactIdType.OPENALEX_WORK => normalizeOpenAlex(raw, 'W')
        case ExactIdType.ORCID => normalizeOrcid(raw)
        case ExactIdType.DBLP_PID => normalizeDblpPid(raw)
        case ExactIdType.OPENALEX_AUTHOR => normalizeOpenAlex(raw, 'A')
