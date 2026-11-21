package scholarchain.core.ids

/** Kinds of ScholarChain stable identifier.
  *
  * Wire grammar (all lower-case ASCII):
  * {{{
  *   kind ":" digest                  for kinds without a namespace
  *   kind ":" namespace ":" digest    for kinds with a namespace
  *   digest = 64 lower-case hex characters (SHA-256)
  * }}}
  *
  * @param prefix
  *   the literal first segment
  * @param namespaces
  *   allowed namespace segments; empty means the kind has no namespace segment
  * @param versionedNamespacePrefix
  *   if set, a namespace of the form `<prefix>v<N>` (N >= 1) is also allowed
  */
enum IdKind(
    val prefix: String,
    val namespaces: Set[String],
    val versionedNamespacePrefix: Option[String]
):
  case Work extends IdKind("work", Set("dblp", "doi", "openalex", "arxiv"), None)
  case Researcher
      extends IdKind("researcher", Set("orcid", "dblp", "openalex"), Some("sc-researcher-"))
  case AuthorMention extends IdKind("mention", Set.empty, None)
  case Authorship extends IdKind("authorship", Set.empty, None)
  case PairCandidate extends IdKind("pair-candidate", Set.empty, None)
  case CoauthorEdge extends IdKind("coauthor-edge", Set.empty, None)
  case Metrics extends IdKind("metrics", Set.empty, None)
  case Venue extends IdKind("venue", Set("dblp", "openalex", "issn"), None)
  case Institution extends IdKind("institution", Set("ror", "openalex"), None)
  case Topic extends IdKind("topic", Set("openalex"), None)
  case MatchDecision extends IdKind("match", Set.empty, None)
  case GraphEdge extends IdKind("graph-edge", Set.empty, None)
  case Chunk extends IdKind("chunk", Set.empty, None)
  case Profile extends IdKind("profile", Set.empty, None)
  case Evidence extends IdKind("evidence", Set.empty, None)
  case Bundle extends IdKind("bundle", Set.empty, None)
  case Claim extends IdKind("claim", Set.empty, None)
  case Query extends IdKind("query", Set.empty, None)
  case Run extends IdKind("run", Set.empty, None)
  case Snapshot extends IdKind("snapshot", Set.empty, None)
  case MatchCandidate extends IdKind("match-candidate", Set.empty, None)
  case ExactIdOutcome extends IdKind("exact-id", Set.empty, None)

  def hasNamespace: Boolean = namespaces.nonEmpty || versionedNamespacePrefix.nonEmpty

  def allowsNamespace(ns: String): Boolean =
    namespaces.contains(ns) || versionedNamespacePrefix.exists { p =>
      ns.startsWith(p) && IdKind.VersionTag.matches(ns.substring(p.length))
    }

object IdKind:
  /** `v1`, `v2`, ... (no leading zeros). */
  val VersionTag = "v[1-9][0-9]*".r

  def byPrefix(prefix: String): Option[IdKind] = values.find(_.prefix == prefix)
