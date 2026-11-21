package scholarchain.er.features

import scala.collection.immutable.TreeMap
import scholarchain.core.model.SourceType
import scholarchain.er.ids.{ErIdType, ExactIdRules, IdComparison}
import scholarchain.er.model.{ResearcherView, WorkView}
import scholarchain.er.policy.{GenericTitles, PolicyParameters}
import scholarchain.er.text.TextNormalize
import scholarchain.er.text.TextNormalize.PersonName

/** String and set similarity primitives. All pure; all return a value in [0, 1]. */
object Similarity:

  /** Jaccard index |A ∩ B| / |A ∪ B|; 0 when both are empty. Range [0, 1]. */
  def jaccard[A](a: Set[A], b: Set[A]): Double =
    val union = (a union b).size
    if union == 0 then 0.0 else (a intersect b).size.toDouble / union

  /** Jaro similarity over code points. Range [0, 1]; 1 for two equal strings (including two
    * empty strings), 0 when exactly one is empty.
    */
  def jaro(s1: String, s2: String): Double =
    val a = s1.codePoints.toArray
    val b = s2.codePoints.toArray
    if a.isEmpty && b.isEmpty then 1.0
    else if a.isEmpty || b.isEmpty then 0.0
    else
      val window = math.max(0, math.max(a.length, b.length) / 2 - 1)
      val aMatched = Array.fill(a.length)(false)
      val bMatched = Array.fill(b.length)(false)
      var matches = 0
      for i <- a.indices do
        val lo = math.max(0, i - window)
        val hi = math.min(b.length - 1, i + window)
        var j = lo
        var found = false
        while !found && j <= hi do
          if !bMatched(j) && a(i) == b(j) then
            aMatched(i) = true
            bMatched(j) = true
            matches += 1
            found = true
          j += 1
      if matches == 0 then 0.0
      else
        var k = 0
        var transpositions = 0
        for i <- a.indices if aMatched(i) do
          while !bMatched(k) do k += 1
          if a(i) != b(k) then transpositions += 1
          k += 1
        val m = matches.toDouble
        (m / a.length + m / b.length + (m - transpositions / 2.0) / m) / 3.0

  /** Jaro-Winkler similarity with prefix scale 0.1 and a common prefix of at most 4 code
    * points. Range [0, 1].
    */
  def jaroWinkler(s1: String, s2: String): Double =
    val j = jaro(s1, s2)
    val a = s1.codePoints.toArray
    val b = s2.codePoints.toArray
    var prefix = 0
    while prefix < 4 && prefix < a.length && prefix < b.length && a(prefix) == b(prefix) do
      prefix += 1
    math.min(1.0, j + prefix * 0.1 * (1.0 - j))

  /** 1 - Levenshtein distance / max length, over code points. Range [0, 1]; 1 for two empty
    * strings.
    */
  def levenshteinRatio(s1: String, s2: String): Double =
    val a = s1.codePoints.toArray
    val b = s2.codePoints.toArray
    if a.isEmpty && b.isEmpty then 1.0
    else
      var prev = Array.tabulate(b.length + 1)(identity)
      var cur = new Array[Int](b.length + 1)
      for i <- 1 to a.length do
        cur(0) = i
        for j <- 1 to b.length do
          val cost = if a(i - 1) == b(j - 1) then 0 else 1
          cur(j) = math.min(math.min(cur(j - 1) + 1, prev(j) + 1), prev(j - 1) + cost)
        val t = prev
        prev = cur
        cur = t
      1.0 - prev(b.length).toDouble / math.max(a.length, b.length)

/** Feature functions (feature version `er-features-v2`; changes from v1 are listed in
  * `modules/entity-resolution/POLICY-CHANGELOG.md`). Every function is pure and documents
  * its range. A function returning `Option` returns `None` when the evidence is not available on
  * one of the two sides; an unavailable feature is not the same as a 0 score.
  */
object Features:

  /** Names of all features, for documentation and tests. */
  val ResearcherFeatureNames: Vector[String] = Vector(
    "nameSimilarity",
    "surnameSimilarity",
    "initialsCompatibility",
    "authorOrder",
    "coauthorOverlap",
    "affiliationCompatibility",
    "externalIdAgreement",
    "titleSimilarity",
    "yearDistance",
    "venueCompatibility",
    "citationNeighborhood",
    "sourceReliability",
    "sameWorkContext",
    "sameWorkBibliographic"
  )

  val WorkFeatureNames: Vector[String] = Vector(
    "titleSimilarity",
    "yearDistance",
    "venueCompatibility",
    "coauthorOverlap",
    "authorOrder",
    "externalIdAgreement",
    "citationNeighborhood",
    "sourceReliability",
    "exactBibliographic",
    "titlePrefix",
    "genericTitle"
  )

  /** Surname similarity: Jaro-Winkler of the particle-free surname keys. Range [0, 1]; 0 when
    * either name has no surname.
    */
  def surnameSimilarity(a: PersonName, b: PersonName): Double =
    if a.surnameKey.isEmpty || b.surnameKey.isEmpty then 0.0
    else
      math.max(
        Similarity.jaroWinkler(a.surnameKey, b.surnameKey),
        Similarity.jaroWinkler(a.surname.mkString(" "), b.surname.mkString(" "))
      )

  /** Researcher-name similarity. Range [0, 1].
    *
    * `0.6 * surnameSimilarity + 0.4 * given`, where `given` is the Jaro-Winkler of the first
    * given-name tokens when both are full names (two or more letters), 1 or 0 for equal or
    * different first initials when at least one side has only an initial, and 0.5 when either
    * side has no given name. 0 when either side has no surname.
    */
  def nameSimilarity(a: PersonName, b: PersonName): Double =
    if a.surnameKey.isEmpty || b.surnameKey.isEmpty then 0.0
    else
      val givenSim =
        (a.givenNames.headOption, b.givenNames.headOption) match
          case (Some(x), Some(y))
              if x.codePointCount(0, x.length) > 1 &&
                y.codePointCount(0, y.length) > 1 =>
            Similarity.jaroWinkler(x, y)
          case (Some(_), Some(_)) => if a.initials.head == b.initials.head then 1.0 else 0.0
          case _ => 0.5
      0.6 * surnameSimilarity(a, b) + 0.4 * givenSim

  /** Initials compatibility. Values {0, 0.5, 1}.
    *
    * 0.5 when either side has no given-name initials (unknown). Otherwise 1 when the shorter
    * initial sequence is a subsequence of the longer one (`J.` vs `J. R.`, `R.` vs `John R.`),
    * else 0.
    */
  def initialsCompatibility(a: PersonName, b: PersonName): Double =
    val x = a.initials
    val y = b.initials
    if x.isEmpty || y.isEmpty then 0.5
    else
      val (short, long) = if x.size <= y.size then (x, y) else (y, x)
      def isSubsequence(s: Vector[String], l: Vector[String]): Boolean =
        var i = 0
        l.foreach(c => if i < s.size && s(i) == c then i += 1)
        i == s.size
      if isSubsequence(short, long) then 1.0 else 0.0

  /** Author order of one mention on the same publication seen in two sources. Range [0, 1]:
    * `1 - |posA - posB| / max(teamA, teamB)`, floored at 0; 1 for the same position. `None`
    * when a position or team size is missing.
    */
  def mentionAuthorOrder(
      posA: Option[Int],
      teamA: Option[Int],
      posB: Option[Int],
      teamB: Option[Int]
  ): Option[Double] =
    for
      pa <- posA
      pb <- posB
      ta <- teamA
      tb <- teamB
    yield math.max(0.0, 1.0 - math.abs(pa - pb).toDouble / math.max(1, math.max(ta, tb)))

  private def compatibleNames(a: PersonName, b: PersonName): Boolean =
    a.surnameKey.nonEmpty && a.surnameKey == b.surnameKey &&
      (a.initials.isEmpty || b.initials.isEmpty || a.initials.head == b.initials.head)

  /** Author-sequence compatibility of two works. Range [0, 1]: the fraction of aligned positions
    * (up to the shorter list) whose names are compatible (same surname key, same or missing
    * first initial), multiplied by `min(n, m) / max(n, m)`. `None` when either list is empty.
    */
  def authorSequenceCompatibility(a: Vector[String], b: Vector[String]): Option[Double] =
    if a.isEmpty || b.isEmpty then None
    else
      val pa = a.map(TextNormalize.parseName)
      val pb = b.map(TextNormalize.parseName)
      val n = math.min(pa.size, pb.size)
      val aligned = (0 until n).count(i => compatibleNames(pa(i), pb(i))).toDouble / n
      Some(aligned * n / math.max(pa.size, pb.size))

  /** Comparison key of a raw person name: `<surname key> <first initial>`. */
  def nameKey(raw: String): String = TextNormalize.parseName(raw).key

  /** Whether two parsed person names can denote one person in a coauthor list (feature version
    * er-features-v2). Requires equal non-empty surname keys, and compatible given names:
    *   - either side has no given name: compatible (surname-only spelling);
    *   - the given names written together are equal (`Zhi-Quan` vs `Zhiquan`);
    *   - either first given token is a single letter: the first initials must be equal;
    *   - otherwise the first given tokens must be equal. `Li` vs `Lei` and `Yang` vs `Yu` are
    *     different people even though their `<surname> <initial>` keys collide (defect D2).
    */
  def coauthorNamesCompatible(a: PersonName, b: PersonName): Boolean =
    if a.surnameKey.isEmpty || a.surnameKey != b.surnameKey then false
    else if a.givenNames.isEmpty || b.givenNames.isEmpty then true
    else if a.givenNames.mkString == b.givenNames.mkString then true
    else
      val (x, y) = (a.givenNames.head, b.givenNames.head)
      if x.codePointCount(0, x.length) == 1 || y.codePointCount(0, y.length) == 1 then
        a.initials.head == b.initials.head
      else x == y

  /** Coauthor (or author-list) overlap. Range [0, 1]. `None` when either list has no parseable
    * name.
    *
    * er-features-v2: names on each side are parsed and de-duplicated by their folded full form;
    * a one-to-one matching pairs names that are [[coauthorNamesCompatible]] (greedy, in sorted
    * order on both sides, so the result is deterministic); the value is the matching Jaccard
    * `m / (|A| + |B| - m)`. In v1 the value was the Jaccard of `<surname> <initial>` keys, which
    * counted different people with colliding keys as shared coauthors (defect D2).
    */
  def coauthorOverlap(a: Vector[String], b: Vector[String]): Option[Double] =
    def names(v: Vector[String]): Vector[PersonName] =
      v.map(TextNormalize.parseName)
        .filter(_.surnameKey.nonEmpty)
        .groupBy(_.full)
        .toVector
        .sortBy(_._1)
        .map(_._2.head)
    val na = names(a)
    val nb = names(b)
    if na.isEmpty || nb.isEmpty then None
    else
      val used = scala.collection.mutable.BitSet.empty
      var matched = 0
      na.foreach { x =>
        nb.indices.find(j => !used(j) && coauthorNamesCompatible(x, nb(j))).foreach { j =>
          used += j
          matched += 1
        }
      }
      Some(matched.toDouble / (na.size + nb.size - matched))

  /** Affiliation compatibility. Range [0, 1]: the best Jaccard of affiliation tokens (folded,
    * stop words removed) over all pairs of affiliation strings. `None` when either side has no
    * affiliation.
    */
  def affiliationCompatibility(a: Vector[String], b: Vector[String]): Option[Double] =
    val ta = a.map(TextNormalize.affiliationTokens).filter(_.nonEmpty)
    val tb = b.map(TextNormalize.affiliationTokens).filter(_.nonEmpty)
    if ta.isEmpty || tb.isEmpty then None
    else Some((for x <- ta; y <- tb yield Similarity.jaccard(x, y)).max)

  /** External-identifier agreement. Range [-1, 1]: +1 when at least one comparable identifier
    * type agrees and none disagrees, -1 when any disagrees, 0 when nothing is comparable.
    */
  def externalIdAgreement(
      a: Map[ErIdType, Set[String]],
      b: Map[ErIdType, Set[String]]
  ): Double = ExactIdRules.compare(a, b).agreementValue

  /** Title similarity. Range [0, 1]: `0.5 * token Jaccard + 0.5 * Levenshtein ratio` of the
    * normalized titles (markup stripped, folded). `None` when either normalized title is empty.
    */
  def titleSimilarity(a: String, b: String): Option[Double] =
    val na = TextNormalize.normalizeTitle(a)
    val nb = TextNormalize.normalizeTitle(b)
    if na.isEmpty || nb.isEmpty then None
    else
      val j = Similarity.jaccard(na.split(' ').toSet, nb.split(' ').toSet)
      Some(0.5 * j + 0.5 * Similarity.levenshteinRatio(na, nb))

  /** Year distance as a proximity. Range [0, 1]: `max(0, 1 - |yA - yB| / scale)`; 1 for the
    * same year. `None` when either year is missing. `scale` must be > 0.
    */
  def yearDistance(a: Option[Int], b: Option[Int], scale: Double): Option[Double] =
    for
      x <- a
      y <- b
    yield math.max(0.0, 1.0 - math.abs(x - y).toDouble / scale)

  /** Venue compatibility. Range [0, 1]: the best score over all pairs of venue strings, where a
    * pair scores 1 when the normalized names are equal or one is the acronym of the other
    * (`DAC` vs `Design Automation Conference`), and otherwise the Jaccard of their tokens.
    * `None` when either side has no venue.
    */
  def venueCompatibility(a: Vector[String], b: Vector[String]): Option[Double] =
    val na = a.map(TextNormalize.normalizeVenue).filter(_.nonEmpty).distinct
    val nb = b.map(TextNormalize.normalizeVenue).filter(_.nonEmpty).distinct
    if na.isEmpty || nb.isEmpty then None
    else
      // `short` names the venue by the acronym of `long` (at least two letters).
      def acronymOf(short: String, long: String): Boolean =
        val acronym = TextNormalize.venueAcronym(long)
        acronym.length >= 2 && long.contains(' ') && short.split(' ').contains(acronym)
      def pair(x: String, y: String): Double =
        if x == y || acronymOf(x, y) || acronymOf(y, x) then 1.0
        else Similarity.jaccard(x.split(' ').toSet, y.split(' ').toSet)
      Some((for x <- na; y <- nb yield pair(x, y)).max)

  /** Title token prefix. Values {0, 1}: 1 when both normalized titles are non-empty and the
    * shorter one's tokens are a prefix of the longer one's (a title stored without its subtitle,
    * for example Crossref `ftd` vs `ftd: an exact frequency to time domain conversion`), else 0.
    */
  def titleTokenPrefix(a: String, b: String): Double =
    val ta = TextNormalize.titleTokens(a)
    val tb = TextNormalize.titleTokens(b)
    if ta.isEmpty || tb.isEmpty then 0.0
    else
      val (short, long) = if ta.size <= tb.size then (ta, tb) else (tb, ta)
      if long.take(short.size) == short then 1.0 else 0.0

  /** Citation-neighborhood evidence. Range [0, 1]: Jaccard of the typed cited-work identifier
    * sets. `None` when either side has no references (only some sources provide them).
    */
  def citationNeighborhood(a: Set[String], b: Set[String]): Option[Double] =
    if a.isEmpty || b.isEmpty then None else Some(Similarity.jaccard(a, b))

  /** Source reliability. Range [0, 1]: the lower of the two sources' reliabilities from the
    * policy table; a source missing from the table counts as 0.5.
    */
  def sourceReliability(a: SourceType, b: SourceType, table: Map[String, Double]): Double =
    math.min(table.getOrElse(a.toString, 0.5), table.getOrElse(b.toString, 0.5))

  /** Exact normalized bibliographic agreement of two works (evidence level 2). Values {0, 1}: 1
    * when the normalized titles are equal and non-empty, neither title is generic (see
    * [[scholarchain.er.policy.GenericTitles]]; finding F4), both years are present and equal, and
    * the author sequences are fully compatible (same length, every position compatible).
    */
  def exactBibliographic(a: WorkView, b: WorkView, genericTitles: Set[String]): Double =
    val ta = TextNormalize.normalizeTitle(a.title)
    val sameTitle = ta.nonEmpty && ta == TextNormalize.normalizeTitle(b.title)
    val generic = GenericTitles.isGeneric(a.title, genericTitles) ||
      GenericTitles.isGeneric(b.title, genericTitles)
    val sameYear = a.year.isDefined && a.year == b.year
    val seq = a.authorNames.size == b.authorNames.size &&
      authorSequenceCompatibility(a.authorNames, b.authorNames).contains(1.0)
    if sameTitle && !generic && sameYear && seq then 1.0 else 0.0

  /** Whether two researcher records are attached to the same work. Values {0, 1}.
    *
    * For two author mentions: the same work record, or work identifiers that agree without
    * contradiction ([[ExactIdRules.compare]]). When a researcher profile is involved (no mention
    * work): its listed work identifiers share a value with the other side's ([[ExactIdRules.shareAny]]).
    */
  def sameWorkContext(a: ResearcherView, b: ResearcherView): Double =
    (a.work, b.work) match
      case (Some(wa), Some(wb)) =>
        val c: IdComparison = ExactIdRules.compare(wa.ids, wb.ids)
        if wa.ref == wb.ref || (c.hasAgreement && !c.hasDisagreement) then 1.0 else 0.0
      case _ => if ExactIdRules.shareAny(a.contextWorkIds, b.contextWorkIds) then 1.0 else 0.0

  /** Features of a researcher pair, available features only, keyed by name (sorted). */
  def researcher(
      a: ResearcherView,
      b: ResearcherView,
      p: PolicyParameters
  ): TreeMap[String, Double] =
    val na = TextNormalize.parseName(a.name)
    val nb = TextNormalize.parseName(b.name)
    val sameWorkValue = sameWorkContext(a, b)
    // Same paper recognised from bibliographic fields alone (no shared identifier): used only to
    // keep two author slots of one paper apart, never to accept (finding F3).
    val sameWorkBiblio =
      (for
        wa <- a.work
        wb <- b.work
      yield exactBibliographic(wa, wb, p.genericTitles)).getOrElse(0.0)
    val order =
      if sameWorkValue == 1.0 then
        mentionAuthorOrder(a.position, a.teamSize, b.position, b.teamSize)
      else None
    val opt: Seq[(String, Option[Double])] = Seq(
      "authorOrder" -> order,
      "coauthorOverlap" -> coauthorOverlap(a.coauthorNames, b.coauthorNames),
      "affiliationCompatibility" -> affiliationCompatibility(a.affiliations, b.affiliations),
      // A generic title ("Editorial") says nothing about the person (finding F4).
      "titleSimilarity" -> (for
        wa <- a.work
        wb <- b.work
        if !p.isGenericTitle(wa.title) && !p.isGenericTitle(wb.title)
        s <- titleSimilarity(wa.title, wb.title)
      yield s),
      "yearDistance" -> yearDistance(a.year, b.year, p.researcherYearScale),
      "venueCompatibility" -> (for
        wa <- a.work
        wb <- b.work
        s <- venueCompatibility(wa.venues, wb.venues)
      yield s),
      "citationNeighborhood" -> (for
        wa <- a.work
        wb <- b.work
        s <- citationNeighborhood(wa.references, wb.references)
      yield s)
    )
    TreeMap.from(
      Seq(
        "nameSimilarity" -> nameSimilarity(na, nb),
        "surnameSimilarity" -> surnameSimilarity(na, nb),
        "initialsCompatibility" -> initialsCompatibility(na, nb),
        "externalIdAgreement" -> externalIdAgreement(a.ids, b.ids),
        "sourceReliability" -> sourceReliability(a.source, b.source, p.sourceReliability),
        "sameWorkContext" -> sameWorkValue,
        "sameWorkBibliographic" -> sameWorkBiblio
      ) ++ opt.collect { case (k, Some(v)) => k -> v }
    )

  /** Features of a work pair, available features only, keyed by name (sorted). */
  def work(a: WorkView, b: WorkView, p: PolicyParameters): TreeMap[String, Double] =
    val opt: Seq[(String, Option[Double])] = Seq(
      "titleSimilarity" -> titleSimilarity(a.title, b.title),
      "yearDistance" -> yearDistance(a.year, b.year, p.workYearScale),
      "venueCompatibility" -> venueCompatibility(a.venues, b.venues),
      "coauthorOverlap" -> coauthorOverlap(a.authorNames, b.authorNames),
      "authorOrder" -> authorSequenceCompatibility(a.authorNames, b.authorNames),
      "citationNeighborhood" -> citationNeighborhood(a.references, b.references)
    )
    TreeMap.from(
      Seq(
        "externalIdAgreement" -> externalIdAgreement(a.ids, b.ids),
        "sourceReliability" -> sourceReliability(a.source, b.source, p.sourceReliability),
        "exactBibliographic" -> exactBibliographic(a, b, p.genericTitles),
        "titlePrefix" -> titleTokenPrefix(a.title, b.title),
        "genericTitle" -> (if p.isGenericTitle(a.title) || p.isGenericTitle(b.title) then 1.0
                           else 0.0)
      ) ++ opt.collect { case (k, Some(v)) => k -> v }
    )

  /** Weighted score in [0, 1]: `sum(w_f * v_f) / sum(w_f)` over the weighted features, where an
    * unavailable feature contributes `missingFeatureValue`. Unweighted features (for example
    * `externalIdAgreement`, enforced by the exact rules instead) do not contribute.
    */
  def score(features: Map[String, Double], weights: Map[String, Double], missing: Double): Double =
    val total = weights.values.sum
    if total <= 0 then 0.0
    else
      val s = weights.toVector
        .sortBy(_._1)
        .map { (k, w) =>
          w * math.min(1.0, math.max(0.0, features.getOrElse(k, missing)))
        }
        .sum
      math.min(1.0, math.max(0.0, s / total))
