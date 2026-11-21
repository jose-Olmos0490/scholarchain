package scholarchain.hadoop.sources

import scala.collection.immutable.TreeMap
import scala.util.Try
import scholarchain.core.ids.*
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions
import scholarchain.hadoop.io.Records
import scholarchain.hadoop.text.Normalize

/** Crossref REST API works items (JSON Lines, one item per line).
  *
  * Tables (dataset `crossref`): `work` (DOI-namespace work ID), `author_mention`, `work_node`,
  * `mention_node`, `venue` (ISSN namespace, when a valid ISSN exists), `graph_edge`
  * (PUBLISHED_IN). Name-only affiliations have no stable institution ID, so they stay on the
  * mention node (`affiliation.<j>`). A present `abstract` is recorded as `abstractTextRef`
  * (`<input>#<doi>/abstract`); its text is never copied.
  */
object CrossrefNormalizer:

  private def year(item: ujson.Value): Option[Int] =
    Seq("issued", "published", "published-print", "published-online").iterator
      .flatMap(k => Json.obj(item, k))
      .flatMap(d => Json.arr(d, "date-parts").headOption)
      .flatMap {
        case a: ujson.Arr =>
          a.value.headOption.collect { case ujson.Num(n) if n.isWhole => n.toInt }
        case _ => None
      }
      .nextOption()

  def works(line: String, ctx: SourceContext): Vector[Emitted] =
    val out = new EmitBuffer
    Try(ujson.read(line)).toEither match
      case Left(e) => out.reject("MALFORMED_JSON", ctx.inputLocation, s"${e.getMessage} | $line")
      case Right(item) =>
        Json.str(item, "DOI").flatMap(d => ExternalIds.normalizeDoi(d).toOption) match
          case None => out.reject("MISSING_KEY", ctx.inputLocation, line)
          case Some(doi) =>
            val title = Json.strs(item, "title").headOption
            val authors = Json.arr(item, "author")
            def name(a: ujson.Value): Option[String] =
              val givenName = Json.str(a, "given")
              val family = Json.str(a, "family")
              (givenName, family) match
                case (Some(g), Some(f)) => Some(s"${g.trim} ${f.trim}")
                case (None, Some(f)) => Some(f.trim)
                case (Some(g), None) => Some(g.trim)
                case _ => Json.str(a, "name").map(_.trim)
            if title.isEmpty then out.reject("MISSING_TITLE", doi, line)
            else if authors.exists(a => name(a).isEmpty) then
              out.reject("MISSING_AUTHOR_NAME", doi, line)
            else
              val workId = StableIds.workId(WorkNamespace.Doi, doi).toOption.get
              val prov = Records.provenance(SourceType.CROSSREF, doi, ctx.snapshot, ctx.runId)
              val mentions = authors.zipWithIndex.map { (a, i) =>
                val position = i + 1
                val n = name(a).get
                val folded = Normalize.personName(n)
                val mentionId = StableIds.mentionId(workId, position).toOption.get
                val props = TreeMap.newBuilder[String, String]
                props += "doi" -> doi
                Json.str(a, "sequence").foreach(s => props += "sequence" -> s)
                Json.str(a, "given").foreach(s => props += "given" -> s)
                Json.str(a, "family").foreach(s => props += "family" -> s)
                Json.str(a, "ORCID").foreach(s => props += "orcidRaw" -> s)
                Json
                  .bool(a, "authenticated-orcid")
                  .foreach(b => props += "authenticatedOrcid" -> b.toString)
                Json
                  .arr(a, "affiliation")
                  .flatMap(Json.str(_, "name"))
                  .zipWithIndex
                  .foreach((s, j) => props += s"affiliation.$j" -> s)
                out.row(
                  "mention_node",
                  mentionId,
                  GraphNode(
                    SchemaVersions.GraphNode,
                    mentionId,
                    NodeType.AuthorMention,
                    n,
                    props.result(),
                    prov
                  )
                )
                AuthorMention(
                  schemaVersion = SchemaVersions.AuthorMention,
                  mentionId = mentionId,
                  workId = workId,
                  position = position,
                  rawName = n,
                  normalizedName = if folded.nonEmpty then folded else Normalize.stripInvisible(n),
                  sourceAuthorKey = None,
                  orcid = Json.str(a, "ORCID").flatMap(o => ExternalIds.normalizeOrcid(o).toOption),
                  provenance = prov
                )
              }
              val issns = Json
                .strs(item, "ISSN")
                .flatMap(i => ExternalIds.normalizeIssn(i).toOption)
                .distinct
                .sorted
              val venueId =
                issns.headOption.flatMap(i => StableIds.venueId(VenueNamespace.Issn, i).toOption)
              val abstractRef =
                if Json.nonEmpty(item, "abstract") then Some(s"${ctx.inputLocation}#$doi/abstract")
                else None
              val normalizedTitle = Normalize.title(title.get)
              out.row(
                "work",
                workId,
                WorkRecord(
                  schemaVersion = SchemaVersions.WorkRecord,
                  workId = workId,
                  source = SourceType.CROSSREF,
                  sourceRecordId = doi,
                  recordType = Json.str(item, "type").getOrElse("unknown"),
                  title = Normalize.collapseWhitespace(title.get),
                  normalizedTitle = if normalizedTitle.nonEmpty then normalizedTitle
                  else Normalize.fallbackLower(title.get),
                  year = year(item),
                  venueId = venueId,
                  doi = Some(doi),
                  arxivId = None,
                  authors = mentions,
                  abstractTextRef = abstractRef,
                  provenance = prov
                )
              )
              mentions.foreach(m => out.row("author_mention", m.mentionId, m))
              val containerName = Json.strs(item, "container-title").headOption
              venueId.foreach { vid =>
                out.row(
                  "venue",
                  vid,
                  Venue(
                    SchemaVersions.Venue,
                    vid,
                    containerName.getOrElse(issns.head),
                    Some("journal"),
                    None,
                    issns,
                    Records.provenance(SourceType.CROSSREF, issns.head, ctx.snapshot, ctx.runId)
                  )
                )
                SourceEdges
                  .edge(
                    EdgeType.PUBLISHED_IN,
                    workId,
                    vid,
                    Vector(workId),
                    Map("assertedBy" -> "crossref-issn"),
                    prov
                  )
                  .foreach(e => out.row("graph_edge", e.edgeId, e))
              }
              val props = TreeMap.newBuilder[String, String]
              props += "doi" -> doi
              Seq("type", "publisher", "member", "prefix", "URL", "page", "volume", "issue")
                .foreach(k => Json.obj(item, k).flatMap(Json.scalar).foreach(s => props += k -> s))
              Json
                .strs(item, "container-title")
                .zipWithIndex
                .foreach((s, j) => props += s"containerTitle.$j" -> s)
              Json.strs(item, "ISBN").zipWithIndex.foreach((s, j) => props += s"isbn.$j" -> s)
              issns.zipWithIndex.foreach((s, j) => props += s"issn.$j" -> s)
              Json
                .strs(item, "alternative-id")
                .zipWithIndex
                .foreach((s, j) => props += s"alternativeId.$j" -> s)
              Json.obj(item, "event").foreach { ev =>
                Seq("name", "acronym", "location", "number").foreach(k =>
                  Json.obj(ev, k).flatMap(Json.scalar).foreach(s => props += s"event.$k" -> s)
                )
              }
              Json
                .arr(item, "reference")
                .flatMap(Json.str(_, "DOI"))
                .zipWithIndex
                .foreach((s, j) => props += s"referenceDoi.$j" -> s)
              abstractRef.foreach(r => props += "abstractTextRef" -> r)
              out.row(
                "work_node",
                workId,
                GraphNode(
                  SchemaVersions.GraphNode,
                  workId,
                  NodeType.Work,
                  Normalize.collapseWhitespace(title.get),
                  props.result(),
                  prov
                )
              )
    out.result()
