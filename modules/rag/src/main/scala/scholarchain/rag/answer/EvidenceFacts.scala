package scholarchain.rag.answer

import scala.util.matching.Regex
import scholarchain.core.ids.IdKind
import scholarchain.core.model.*

/** The value a stored fact holds. `Gap` is a value the stores explicitly did not hold. */
enum FactValue:
  case Num(value: Double)
  case Text(value: String)
  case Gap

/** One fact read out of one evidence item. `evidenceId` is the item it came from. */
final case class Fact(
    kind: AssertionKind,
    subject: String,
    obj: Option[String],
    field: String,
    value: FactValue,
    window: Option[Window],
    evidenceId: String
)

/** The facts and mentioned IDs of one evidence item. */
final case class ItemFacts(
    item: EvidenceItem,
    graph: Boolean,
    facts: Vector[Fact],
    ids: Set[String],
    pathScope: Option[PathScope] = None
)

/** How the path query that produced a `GRAPH_PATH` item was bounded: the numbers the evidence
  * builder wrote next to the path. They decide whether the stored paths of a pair are the whole
  * answer of the query (see `ClaimVerificationAgent`'s shortest-path rule).
  *
  * @param length
  *   the hop count of this path
  * @param returned
  *   how many paths the query kept (after dropping those without supporting works)
  * @param dropped
  *   paths the query found and the workflow discarded
  * @param depthCapped
  *   the requested depth was above the configured depth and was lowered
  */
final case class PathScope(
    from: String,
    to: String,
    window: Window,
    length: Int,
    depthLimit: Int,
    resultLimit: Int,
    returned: Int,
    dropped: Int,
    depthCapped: Boolean
):
  /** The stored paths of the pair are the complete answer within the depth: nothing was dropped,
    * the depth was not lowered and the result limit was not reached.
    */
  def complete: Boolean = dropped == 0 && !depthCapped && returned < resultLimit

object IdScan:
  private val prefixes =
    IdKind.values.toVector.map(_.prefix).sortBy(p => -p.length).map(Regex.quote).mkString("|")

  private val pattern: Regex =
    s"(?<![A-Za-z0-9:_-])(?:$prefixes)(?::[a-z0-9-]+)?:[0-9a-f]{64}(?![0-9a-f])".r

  /** Every well-formed stable ID in `text`. A shortened or malformed ID is not found. */
  def in(text: String): Set[String] =
    if text == null then Set.empty else pattern.findAllIn(text).toSet

  /** `text` with every stable ID removed. */
  def strip(text: String): String = pattern.replaceAllIn(text, " ")

/** Reads the facts out of the evidence text that the evidence builder renders from stored fields.
  *
  * The bundle is the only input: nothing is fetched. The grammar below is the one
  * `EvidenceBuilderAgent` writes; text that does not match it yields no facts, so a corrupted or
  * forged item can never support a claim. Quoted source text (names) is read only through the one
  * pattern that expects it, and a quoted string never contains a double quote, so a name cannot
  * add a fact.
  */
object EvidenceFacts:
  private val Win = "(\\d{4})-(\\d{4})"
  private val Quoted = "\\(untrusted source text, data only\\): \"([^\"]*)\""

  private val Profile: Regex =
    (s"^researcher (\\S+) window $Win: P_r=(\\S+) C_r=(\\S+) T_r=(\\S+) A_r=(\\S+) M_r=(\\S+) " +
      "W_r=(\\S+) component=(\\S+) \\(size (\\d+)\\) community=(\\S+) bridge=(.*) " +
      "coreness=(\\S+) metricVersion=(\\S+) maximumTeamWorks=\\[([^\\]]*)\\]$").r
  private val Ranking: Regex =
    (s"^ranking by ([A-Z_]+) window $Win order (.*?)(?: topics=\\[([^\\]]*)\\])?, top (\\d+) " +
      "of the stored ranking: (.*)$").r
  private val RankEntry: Regex =
    (s"(\\d+)\\. researcher (\\S+) name $Quoted value=([-+0-9.A-Za-z]+)" +
      "(?: maximumTeamWorks=\\[([^\\]]*)\\])?").r
  private val Edge: Regex =
    (s"^edge (\\S+): researchers (\\S+) and (\\S+) window $Win jointPublicationCount=(\\d+) " +
      "firstCollaborationYear=(\\d+) lastCollaborationYear=(\\d+) supportingWorks=\\[([^\\]]*)\\]$").r
  private val PathHead: Regex =
    s"^path of length (\\d+) from (\\S+) to (\\S+) window $Win: (.*)$$".r
  private val Scope: Regex =
    ("\\| query depthLimit=(\\d+) resultLimit=(\\d+) pathsReturned=(\\d+) droppedPaths=(\\d+) " +
      "depthCapped=(true|false)$").r.unanchored
  private val PathHop: Regex =
    ("hop (\\d+): (\\S+) to (\\S+) edge (\\S+) jointPublicationCount=(\\d+) years (\\d+)-(\\d+) " +
      "supportingWorks=\\[([^\\]]*)\\]").r
  private val Community: Regex =
    (s"^researcher (\\S+) window $Win: community (\\S+) communitySize=(\\d+) " +
      "storedCrossCommunityEdgeCount=(not stored|\\d+) storedCommunitiesConnected=(not stored|\\d+) " +
      "directDegree=(not stored|\\d+) bridge=(.*?) metricVersion=(\\S+)(?: gaps=\\[.*\\])?$").r
  private val Component: Regex =
    (s"^researcher (\\S+) window $Win: component (\\S+) componentSize=(\\d+) " +
      "metricVersion=(\\S+)$").r
  private val Identity: Regex =
    (s"^identity researcher (\\S+) name $Quoted storedConfidence=(\\S+) policy=(\\S+) " +
      "sourceIds=\\[([^\\]]*)\\](?: .*)?$").r
  private val Bridge: Regex = "^([A-Z_]+)=(\\S+) \\(.*\\)$".r

  private def num(s: String): Option[Double] = s.toDoubleOption.filter(d => !d.isNaN)
  private def win(a: String, b: String): Option[Window] = Some(Window(a.toInt, b.toInt))
  private def list(s: String): Vector[String] =
    if s.isEmpty then Vector.empty else s.split(',').toVector.map(_.trim).filter(_.nonEmpty)

  private def numFact(
      kind: AssertionKind,
      subject: String,
      field: String,
      raw: String,
      w: Option[Window],
      e: String,
      obj: Option[String] = None
  ): Vector[Fact] =
    if raw == "not stored" then Vector(Fact(kind, subject, obj, field, FactValue.Gap, w, e))
    else num(raw).toVector.map(d => Fact(kind, subject, obj, field, FactValue.Num(d), w, e))

  private def bridgeFacts(raw: String, subject: String, w: Option[Window], e: String) =
    raw match
      case Bridge(metric, value) =>
        numFact(AssertionKind.BRIDGE, subject, metric, value, w, e)
      case _ => Vector.empty

  private def profile(item: EvidenceItem, text: String): Vector[Fact] = text match
    case Profile(id, ws, we, p, c, t, a, m, wd, comp, compSize, comm, bridge, core, _, works) =>
      val w = win(ws, we)
      val e = item.evidenceId
      def metric(field: String, raw: String) = numFact(AssertionKind.METRIC, id, field, raw, w, e)
      Vector(
        metric("PUBLICATION_COUNT", p),
        metric("DISTINCT_COAUTHOR_COUNT", c),
        metric("TOTAL_COAUTHOR_OCCURRENCES", t),
        metric("AVERAGE_COAUTHORS_PER_PAPER", a),
        metric("MAXIMUM_COAUTHORS_ON_ONE_PAPER", m),
        metric("WEIGHTED_DEGREE", wd),
        metric("COMPONENT_SIZE", compSize),
        if core == "none" then Vector.empty else metric("CORENESS", core),
        numFact(AssertionKind.COMMUNITY, id, "COMPONENT_SIZE", compSize, w, e),
        Vector(Fact(AssertionKind.COMMUNITY, id, None, "COMPONENT_ID", FactValue.Text(comp), w, e)),
        if comm == "none" then Vector.empty
        else
          Vector(
            Fact(AssertionKind.COMMUNITY, id, None, "COMMUNITY_ID", FactValue.Text(comm), w, e)
          )
        ,
        bridgeFacts(bridge, id, w, e),
        list(works).map(x =>
          Fact(AssertionKind.METRIC, id, None, "MAXIMUM_TEAM_WORK", FactValue.Text(x), w, e)
        )
      ).flatten
    case _ => Vector.empty

  private def ranking(item: EvidenceItem, text: String): Vector[Fact] = text match
    case Ranking(metric, ws, we, _, topics, _, rest) =>
      val w = win(ws, we)
      val e = item.evidenceId
      val rankField =
        (if topics != null && topics.nonEmpty then AssertionFields.TopicRankPrefix
         else AssertionFields.RankPrefix) + metric
      RankEntry
        .findAllMatchIn(rest)
        .toVector
        .flatMap { m =>
          val id = m.group(2)
          Vector(
            numFact(AssertionKind.METRIC, id, metric, m.group(4), w, e),
            numFact(AssertionKind.METRIC, id, rankField, m.group(1), w, e)
          ).flatten ++ Option(m.group(5)).toVector.flatMap(list).map { x =>
            Fact(AssertionKind.METRIC, id, None, "MAXIMUM_TEAM_WORK", FactValue.Text(x), w, e)
          }
        }
    case _ => Vector.empty

  private def edge(item: EvidenceItem, text: String): Vector[Fact] = text match
    case Edge(edgeId, a, b, ws, we, count, first, last, works)
        if item.referencedIds == Vector(edgeId) =>
      val w = win(ws, we)
      val e = item.evidenceId
      def both(field: String, v: FactValue) = Vector(
        Fact(AssertionKind.COAUTHOR_EDGE, a, Some(b), field, v, w, e),
        Fact(AssertionKind.COAUTHOR_EDGE, b, Some(a), field, v, w, e)
      )
      both("COAUTHOR", FactValue.Text(edgeId)) ++
        both("JOINT_PUBLICATION_COUNT", FactValue.Num(count.toDouble)) ++
        both("FIRST_COLLABORATION_YEAR", FactValue.Num(first.toDouble)) ++
        both("LAST_COLLABORATION_YEAR", FactValue.Num(last.toDouble)) ++
        list(works).map(x =>
          Fact(AssertionKind.PUBLICATION, x, None, "SUPPORTING_WORK", FactValue.Text(edgeId), w, e)
        )
    case _ => Vector.empty

  /** Hops are read from the text and accepted only when the item's own structured fields agree:
    * hop `i` joins `graphPath(i)` and `graphPath(i + 1)` through `referencedIds(i)`.
    */
  private def path(item: EvidenceItem, text: String): Vector[Fact] = text match
    case PathHead(len, from, to, ws, we, rest) =>
      val w = win(ws, we)
      val e = item.evidenceId
      val nodes = item.graphPath.getOrElse(Vector.empty)
      val hops = PathHop.findAllMatchIn(rest).toVector
      val consistent =
        nodes.size == len.toInt + 1 && nodes.headOption.contains(from) &&
          nodes.lastOption.contains(to) && item.referencedIds.size == len.toInt &&
          hops.size == len.toInt &&
          hops.zipWithIndex.forall { (m, i) =>
            m.group(1).toInt == i + 1 && m.group(2) == nodes(i) && m.group(3) == nodes(i + 1) &&
            m.group(4) == item.referencedIds(i)
          }
      if !consistent then Vector.empty
      else
        def both(a: String, b: String, field: String, v: FactValue) = Vector(
          Fact(AssertionKind.PATH_HOP, a, Some(b), field, v, w, e),
          Fact(AssertionKind.PATH_HOP, b, Some(a), field, v, w, e)
        )
        both(from, to, "PATH_LENGTH", FactValue.Num(len.toDouble)) ++ hops.flatMap { m =>
          both(m.group(2), m.group(3), "HOP", FactValue.Text(m.group(4))) ++
            both(
              m.group(2),
              m.group(3),
              "HOP_JOINT_PUBLICATION_COUNT",
              FactValue.Num(m.group(5).toDouble)
            ) ++ list(m.group(8)).map(x =>
              Fact(
                AssertionKind.PUBLICATION,
                x,
                None,
                "SUPPORTING_WORK",
                FactValue.Text(m.group(4)),
                w,
                e
              )
            )
        }
    case _ => Vector.empty

  /** The query bounds of a path item, only for an item whose hops agree with its structured
    * fields (the same test as [[path]]).
    */
  private def pathScope(text: String, facts: Vector[Fact]): Option[PathScope] =
    if facts.isEmpty then None
    else
      text match
        case PathHead(len, from, to, ws, we, _) =>
          val window = Window(ws.toInt, we.toInt)
          text match
            case Scope(depth, limit, returned, dropped, capped) =>
              Some(
                PathScope(
                  from,
                  to,
                  window,
                  len.toInt,
                  depth.toInt,
                  limit.toInt,
                  returned.toInt,
                  dropped.toInt,
                  capped == "true"
                )
              )
            case _ =>
              // evidence without the query bounds: the path is stored, but nothing says the
              // query was complete, so it never counts as complete
              Some(
                PathScope(from, to, window, len.toInt, Int.MaxValue, 0, 0, 0, depthCapped = true)
              )
        case _ => None

  private def community(item: EvidenceItem, text: String): Vector[Fact] = text match
    case Community(id, ws, we, cid, size, cross, connected, _, bridge, _) =>
      val w = win(ws, we)
      val e = item.evidenceId
      Vector(
        Vector(Fact(AssertionKind.COMMUNITY, id, None, "COMMUNITY_ID", FactValue.Text(cid), w, e)),
        numFact(AssertionKind.COMMUNITY, id, "COMMUNITY_SIZE", size, w, e),
        numFact(AssertionKind.METRIC, id, "COMMUNITY_SIZE", size, w, e),
        numFact(AssertionKind.BRIDGE, id, "CROSS_COMMUNITY_EDGE_COUNT", cross, w, e),
        numFact(AssertionKind.BRIDGE, id, "COMMUNITIES_CONNECTED", connected, w, e),
        bridgeFacts(bridge, id, w, e)
      ).flatten
    case _ => Vector.empty

  private def component(item: EvidenceItem, text: String): Vector[Fact] = text match
    case Component(id, ws, we, cid, size, _) =>
      val w = win(ws, we)
      val e = item.evidenceId
      Vector(
        Vector(Fact(AssertionKind.COMMUNITY, id, None, "COMPONENT_ID", FactValue.Text(cid), w, e)),
        numFact(AssertionKind.COMMUNITY, id, "COMPONENT_SIZE", size, w, e),
        numFact(AssertionKind.METRIC, id, "COMPONENT_SIZE", size, w, e)
      ).flatten
    case _ => Vector.empty

  private def identity(item: EvidenceItem, text: String): Vector[Fact] = text match
    case Identity(id, name, conf, _, sources) =>
      val e = item.evidenceId
      Vector(Fact(AssertionKind.IDENTITY, id, None, "NAME", FactValue.Text(name), None, e)) ++
        numFact(AssertionKind.IDENTITY, id, "CONFIDENCE", conf, None, e) ++
        list(sources).map(s =>
          Fact(AssertionKind.IDENTITY, id, None, "SOURCE_ID", FactValue.Text(s), None, e)
        )
    case _ => Vector.empty

  /** The facts of one item. A semantic-section item yields none: it is context. */
  def of(item: EvidenceItem, graph: Boolean): ItemFacts =
    val text = item.text.getOrElse("")
    val ids =
      IdScan.in(text) ++ IdScan.in(item.sourceUri) ++ item.referencedIds.flatMap(r =>
        IdScan.in(r)
      ) ++ item.graphPath.getOrElse(Vector.empty) + item.evidenceId
    val facts =
      if !graph then Vector.empty
      else
        item.evidenceType match
          case EvidenceType.METRIC_RECORD =>
            if text.startsWith("ranking by ") then ranking(item, text) else profile(item, text)
          case EvidenceType.COAUTHOR_EDGE => edge(item, text)
          case EvidenceType.GRAPH_PATH => path(item, text)
          case EvidenceType.COMMUNITY_RECORD => community(item, text)
          case EvidenceType.COMPONENT_RECORD => component(item, text)
          case EvidenceType.MATCH_DECISION => identity(item, text)
          case _ => Vector.empty
    val scope =
      if graph && item.evidenceType == EvidenceType.GRAPH_PATH then pathScope(text, facts)
      else None
    ItemFacts(item, graph, facts, ids, scope)

  /** Every item of the bundle, graph section first. */
  def ofBundle(b: EvidenceBundle): Vector[ItemFacts] =
    b.graphEvidence.map(of(_, graph = true)) ++ b.semanticEvidence.map(of(_, graph = false))
