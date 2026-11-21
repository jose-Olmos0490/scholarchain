package scholarchain.er.ids

import java.net.URI
import java.util.Locale
import scholarchain.core.ids.ExternalIds
import scala.util.Try

/** Identifier types used by the exact-identifier rules (evidence level 1).
  *
  * @param researcher
  *   true for researcher identifiers, false for work identifiers
  * @param disagreementIsEvidence
  *   whether two different values of this type on the two sides are evidence that the records
  *   differ. False for repository URLs: one work may link several repositories.
  * @param agreementIsIdentity
  *   whether a shared value of this type proves that the two records are one entity. False for
  *   repository URLs: several works (a paper, its extended version, a dataset paper) can link one
  *   repository (policy er-v1-r2, finding F10).
  */
enum ErIdType(
    val researcher: Boolean,
    val disagreementIsEvidence: Boolean,
    val agreementIsIdentity: Boolean
):
  case DOI extends ErIdType(false, true, true)
  case ARXIV extends ErIdType(false, true, true)
  case OPENALEX_WORK extends ErIdType(false, true, true)
  case SEMANTIC_SCHOLAR_PAPER extends ErIdType(false, true, true)
  case REPOSITORY_URL extends ErIdType(false, false, false)
  case DBLP_KEY extends ErIdType(false, true, true)
  case ORCID extends ErIdType(true, true, true)
  case OPENALEX_AUTHOR extends ErIdType(true, true, true)
  case SEMANTIC_SCHOLAR_AUTHOR extends ErIdType(true, true, true)

  /** dblp person key: the dblp author name string including any homonym suffix. Only a key
    * carrying a homonym suffix (`Wei Zhang 0001`) is an identifier: dblp has disambiguated that
    * profile. An unsuffixed key (`Wei Zhang`) can be an undisambiguated profile holding several
    * people, so it is ignored by [[ExactIdRules.compare]] (policy er-v1-r2, finding F10).
    */
  case DBLP_AUTHOR_KEY extends ErIdType(true, true, true)

/** Result of comparing the identifiers of two records. */
final case class IdComparison(agree: Vector[ErIdType], disagree: Vector[ErIdType]):
  def hasAgreement: Boolean = agree.nonEmpty
  def hasDisagreement: Boolean = disagree.nonEmpty

  /** Agreement on one type and disagreement on another: never merged. */
  def isConflict: Boolean = hasAgreement && hasDisagreement

  /** Feature value in [-1, 1]: +1 only agreement, -1 any disagreement, 0 nothing comparable. */
  def agreementValue: Double =
    if hasDisagreement then -1.0 else if hasAgreement then 1.0 else 0.0

/** Exact identifier normalization and comparison. Every normalizer is pure and returns the
  * normalized value or a rejection reason; nothing throws.
  */
object ExactIdRules:

  /** Removes invisible format characters (Cf, e.g. a zero-width space) anywhere and invisible
    * code points (no-break spaces, controls) at both ends. A DOI copied with a stray zero-width
    * character must equal the clean DOI (Phase 4 known risk 1).
    */
  private def cleanInvisible(raw: String): String =
    val cps = raw.codePoints.filter(cp => Character.getType(cp) != Character.FORMAT).toArray
    var from = 0
    var to = cps.length
    def invisible(cp: Int) =
      Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.isISOControl(cp)
    while from < to && invisible(cps(from)) do from += 1
    while to > from && invisible(cps(to - 1)) do to -= 1
    new String(cps, from, to - from)

  /** DOI. Invisible characters cleaned (see [[cleanInvisible]]); `doi:`, `https://doi.org/`,
    * `http://dx.doi.org/` and similar prefixes removed and the value lower-cased by the frozen core
    * normalizer `ExternalIds.normalizeDoi`. A trailing period or comma (sentence punctuation in
    * a copied DOI) is removed first.
    */
  def normalizeDoi(raw: String): Either[String, String] =
    if raw == null then Left("DOI must not be null")
    else
      val cleaned = cleanInvisible(raw).stripSuffix(".").stripSuffix(",")
      ExternalIds.normalizeDoi(cleaned)

  /** arXiv ID without version (core `ExternalIds.normalizeArxiv`), invisible characters cleaned;
    * also accepts the `https://arxiv.org/pdf/<id>` form.
    */
  def normalizeArxiv(raw: String): Either[String, String] =
    if raw == null then Left("arXiv ID must not be null")
    else
      val c = cleanInvisible(raw)
      val abs = c
        .replaceFirst("(?i)^https?://(www\\.)?arxiv\\.org/pdf/", "arxiv:")
        .replaceFirst("(?i)\\.pdf$", "")
        .replaceFirst("(?i)^https?://(www\\.)?arxiv\\.org/abs/", "arxiv:")
      ExternalIds.normalizeArxiv(abs)

  /** ORCID iD (core `ExternalIds.normalizeOrcid`, with checksum), invisible characters cleaned. */
  def normalizeOrcid(raw: String): Either[String, String] =
    if raw == null then Left("ORCID iD must not be null")
    else ExternalIds.normalizeOrcid(cleanInvisible(raw))

  /** OpenAlex work (`W…`) or author (`A…`) ID (core `ExternalIds.normalizeOpenAlex`). */
  def normalizeOpenAlex(raw: String, entityLetter: Char): Either[String, String] =
    if raw == null then Left("OpenAlex ID must not be null")
    else
      ExternalIds.normalizeOpenAlex(
        cleanInvisible(raw).replaceFirst("(?i)^https?://api\\.openalex\\.org/(works|authors)/", ""),
        entityLetter
      )

  private val S2PaperHex = "[0-9a-f]{40}".r
  private val S2Corpus = "(?i)corpus[ _-]?id[:/ ]?([0-9]+)".r
  private val Digits = "[0-9]+".r

  /** Semantic Scholar paper ID. Forms accepted:
    *   - the 40-hex paper SHA, bare or at the end of a `semanticscholar.org/paper/...` URL,
    *     normalized to lower-case hex;
    *   - a corpus ID (`CorpusId:123`, `CorpusID 123`, `api.semanticscholar.org/CorpusID:123`),
    *     normalized to `corpusid:<digits>`.
    */
  def normalizeSemanticScholarPaper(raw: String): Either[String, String] =
    if raw == null then Left("Semantic Scholar paper ID must not be null")
    else
      val c = cleanInvisible(raw).trim
      val tail = c.replaceFirst("(?i)^https?://(www\\.|api\\.)?semanticscholar\\.org/", "")
      val segments = tail.split('/').filter(_.nonEmpty)
      val hex = segments.lastOption.getOrElse("").toLowerCase(Locale.ROOT)
      // A URL on another host, or a path that is not /paper/..., is not a paper ID.
      val pathOk = segments.length == 1 ||
        segments.headOption.exists(_.equalsIgnoreCase("paper"))
      tail match
        case _ if tail.contains("://") => Left(s"'$raw' is not a Semantic Scholar URL")
        case S2Corpus(n) => Right(s"corpusid:${n.dropWhile(_ == '0').ifEmpty("0")}")
        case _ if pathOk && S2PaperHex.matches(hex) => Right(hex)
        case _ => Left(s"malformed Semantic Scholar paper ID '$raw'")

  /** Semantic Scholar author ID: digits, bare or at the end of a
    * `semanticscholar.org/author/<name>/<digits>` URL. Leading zeros removed.
    */
  def normalizeSemanticScholarAuthor(raw: String): Either[String, String] =
    if raw == null then Left("Semantic Scholar author ID must not be null")
    else
      val c = cleanInvisible(raw).trim
      val tail = c.replaceFirst("(?i)^https?://(www\\.|api\\.)?semanticscholar\\.org/", "")
      val last = tail.split('/').filter(_.nonEmpty).lastOption.getOrElse("")
      if !tail.contains("://") && Digits.matches(last) &&
        (tail == last || tail.toLowerCase(Locale.ROOT).startsWith("author/"))
      then Right(last.dropWhile(_ == '0').ifEmpty("0"))
      else Left(s"malformed Semantic Scholar author ID '$raw'")

  extension (s: String) private def ifEmpty(d: String): String = if s.isEmpty then d else s

  /** Code hosts whose `<owner>/<repo>` URLs identify a stable repository. Paths are compared
    * case-insensitively (all four hosts treat owner and repository names case-insensitively).
    */
  val RepositoryHosts: Set[String] =
    Set("github.com", "gitlab.com", "bitbucket.org", "codeberg.org")

  /** Stable repository URL, normalized to `https://<host>/<owner>/<repo>`: scheme forced to
    * https, `www.` removed, host lower-cased, query and fragment dropped, `.git` suffix and
    * trailing slash removed, path reduced to its first two segments and lower-cased. Only
    * [[RepositoryHosts]] are accepted; any other URL is not a repository identifier.
    */
  def normalizeRepositoryUrl(raw: String): Either[String, String] =
    if raw == null then Left("repository URL must not be null")
    else
      val c = cleanInvisible(raw).trim
      val withScheme =
        if c.matches("(?i)^[a-z][a-z0-9+.-]*://.*") then c
        else if c.startsWith("git@") then "https://" + c.stripPrefix("git@").replaceFirst(":", "/")
        else "https://" + c
      Try(new URI(withScheme)).toOption.flatMap(u => Option(u.getHost).map(h => (u, h))) match
        case None => Left(s"malformed repository URL '$raw'")
        case Some((u, h)) =>
          val host = h.toLowerCase(Locale.ROOT).stripPrefix("www.")
          val segments = Option(u.getPath).getOrElse("").split('/').filter(_.nonEmpty).toVector
          if !RepositoryHosts.contains(host) then Left(s"'$raw' is not on a known repository host")
          else if segments.size < 2 then Left(s"repository URL '$raw' has no <owner>/<repo>")
          else
            val owner = segments(0).toLowerCase(Locale.ROOT)
            val repo = segments(1).toLowerCase(Locale.ROOT).stripSuffix(".git")
            if owner.isEmpty || repo.isEmpty then Left(s"malformed repository URL '$raw'")
            else Right(s"https://$host/$owner/$repo")

  /** dblp record key (core `ExternalIds.normalizeDblpKey`). */
  def normalizeDblpKey(raw: String): Either[String, String] =
    if raw == null then Left("dblp key must not be null")
    else ExternalIds.normalizeDblpKey(cleanInvisible(raw))

  /** dblp person key: the author name as dblp writes it, whitespace collapsed, Cf removed and
    * NFC-composed. Case and the homonym suffix are significant (`Wei Wang 0001` and
    * `Wei Wang 0002` are different dblp persons).
    */
  def normalizeDblpAuthorKey(raw: String): Either[String, String] =
    if raw == null then Left("dblp author key must not be null")
    else
      val v = java.text.Normalizer
        .normalize(
          scholarchain.er.text.TextNormalize.collapseWhitespace(raw),
          java.text.Normalizer.Form.NFC
        )
      if v.isEmpty then Left("dblp author key is blank") else Right(v)

  /** Normalizes `raw` as an identifier of type `t`. */
  def normalize(t: ErIdType, raw: String): Either[String, String] = t match
    case ErIdType.DOI => normalizeDoi(raw)
    case ErIdType.ARXIV => normalizeArxiv(raw)
    case ErIdType.OPENALEX_WORK => normalizeOpenAlex(raw, 'W')
    case ErIdType.SEMANTIC_SCHOLAR_PAPER => normalizeSemanticScholarPaper(raw)
    case ErIdType.REPOSITORY_URL => normalizeRepositoryUrl(raw)
    case ErIdType.DBLP_KEY => normalizeDblpKey(raw)
    case ErIdType.ORCID => normalizeOrcid(raw)
    case ErIdType.OPENALEX_AUTHOR => normalizeOpenAlex(raw, 'A')
    case ErIdType.SEMANTIC_SCHOLAR_AUTHOR => normalizeSemanticScholarAuthor(raw)
    case ErIdType.DBLP_AUTHOR_KEY => normalizeDblpAuthorKey(raw)

  /** Classifies a work link (for example a dblp `<ee>` or an OpenAlex `ids.*` value) as an
    * identifier: DOI resolver URLs, arXiv URLs, Semantic Scholar paper URLs, OpenAlex work URLs
    * and repository URLs. `None` for any other link.
    */
  def classifyWorkLink(raw: String): Option[(ErIdType, String)] =
    if raw == null then None
    else
      val l = raw.trim.toLowerCase(Locale.ROOT)
      val t: Option[ErIdType] =
        if l.matches("^https?://(dx\\.)?doi\\.org/.*") || l.startsWith("doi:") then
          Some(ErIdType.DOI)
        else if l.matches("^https?://(www\\.)?arxiv\\.org/(abs|pdf)/.*") then Some(ErIdType.ARXIV)
        else if l.matches("^https?://(www\\.|api\\.)?semanticscholar\\.org/(paper/|corpusid).*")
        then Some(ErIdType.SEMANTIC_SCHOLAR_PAPER)
        else if l.matches("^https?://openalex\\.org/w[0-9]+$") then Some(ErIdType.OPENALEX_WORK)
        else if RepositoryHosts.exists(h =>
            l.matches(s"^https?://(www\\.)?${h.replace(".", "\\.")}/.*")
          )
        then Some(ErIdType.REPOSITORY_URL)
        else None
      t.flatMap(tt => normalize(tt, raw).toOption.map(v => (tt, v)))

  private val DblpHomonymSuffix = ".*\\S\\s[0-9]{4}$".r

  /** True when a normalized dblp person key carries a homonym suffix (`Wei Zhang 0001`). */
  def isDisambiguatedDblpAuthorKey(key: String): Boolean =
    key != null && DblpHomonymSuffix.matches(key)

  /** Values of type `t` that may take part in exact-identifier comparison. Unsuffixed dblp person
    * keys are dropped (see [[ErIdType.DBLP_AUTHOR_KEY]]); every other value is kept.
    */
  def identifyingValues(t: ErIdType, values: Set[String]): Set[String] = t match
    case ErIdType.DBLP_AUTHOR_KEY => values.filter(isDisambiguatedDblpAuthorKey)
    case _ => values

  /** Compares two identifier sets, type by type, in [[ErIdType]] declaration order (policy
    * er-v1-r2). A type is compared only when both sides carry at least one identifying value
    * ([[identifyingValues]]).
    *
    *   - Agreement: the sets share a value and the type's `agreementIsIdentity` is true.
    *   - Disagreement (when the type's `disagreementIsEvidence` is true):
    *     - researcher types: the sets are not equal. One person has one iD of each type, so
    *       `{A, C}` vs `{B, C}`, and also `{A}` vs `{A, C}`, carry an identifier the other side
    *       contradicts (finding F2);
    *     - work types: each side has a value the other lacks (neither set contains the other).
    *       A record that knows one more DOI than the other is not a contradiction; `{a, b}` vs
    *       `{b, c}` is.
    *
    * A type can therefore both agree and disagree, which makes the comparison a conflict
    * ([[IdComparison.isConflict]]): partially overlapping identifier sets are never merged.
    */
  def compare(
      left: Map[ErIdType, Set[String]],
      right: Map[ErIdType, Set[String]]
  ): IdComparison =
    val results = ErIdType.values.toVector.flatMap { t =>
      val a = left.get(t).map(identifyingValues(t, _)).getOrElse(Set.empty)
      val b = right.get(t).map(identifyingValues(t, _)).getOrElse(Set.empty)
      if a.isEmpty || b.isEmpty then None
      else
        val shared = (a intersect b).nonEmpty
        val contradicts =
          t.disagreementIsEvidence &&
            (if t.researcher then a != b else !(a subsetOf b) && !(b subsetOf a))
        Some((t, shared && t.agreementIsIdentity, contradicts))
    }
    IdComparison(
      results.collect { case (t, true, _) => t },
      results.collect { case (t, _, true) => t }
    )

  /** Whether two identifier sets share any value of a type that identifies the entity. Used for
    * "attached to the same work" context (a researcher profile lists many work DOIs; one shared
    * DOI places a mention on one of them), never for identity of the records themselves.
    */
  def shareAny(
      left: Map[ErIdType, Set[String]],
      right: Map[ErIdType, Set[String]]
  ): Boolean =
    ErIdType.values.exists { t =>
      t.agreementIsIdentity &&
      (left.getOrElse(t, Set.empty) intersect right.getOrElse(t, Set.empty)).nonEmpty
    }
