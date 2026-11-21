package scholarchain.hadoop.sources

import scala.collection.immutable.TreeMap
import scala.util.Try
import scholarchain.core.ids.*
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions
import scholarchain.hadoop.io.Records
import scholarchain.hadoop.text.Normalize

/** Identity of the run and input a normalizer writes provenance for. */
final case class SourceContext(snapshot: String, runId: String, inputLocation: String)

/** Policy version of source-asserted graph edges (HAS_TOPIC, AFFILIATED_WITH, PUBLISHED_IN) that
  * MapReduce copies from a source without any inference. A code-level format version, like a
  * schema version; not a tunable policy.
  */
object SourceEdges:
  val PolicyVersion = "hadoop-source-asserted-edges-v1"

  def edge(
      edgeType: EdgeType,
      from: String,
      to: String,
      evidence: Vector[String],
      properties: Map[String, String],
      prov: Provenance
  ): Option[GraphEdge] =
    StableIds.graphEdgeId(edgeType, from, to, PolicyVersion, evidence).toOption.map { id =>
      GraphEdge(
        schemaVersion = SchemaVersions.GraphEdge,
        edgeId = id,
        fromId = from,
        edgeType = edgeType,
        toId = to,
        properties = Records.sorted(properties),
        policyVersion = PolicyVersion,
        sourceEvidenceIds = evidence.distinct.sorted,
        provenance = prov
      )
    }

/** OpenAlex works and authors (snapshot JSON Lines and API-linked JSON Lines).
  *
  * Tables (dataset `openalex`): `work`, `author_mention`, `work_node`, `mention_node`,
  * `researcher_node`, `institution`, `topic`, `venue`, `graph_edge`. Every OpenAlex ID (work,
  * author, institution, topic, source) and every `ids` entry is kept, as a stable ID or as a node
  * property; affiliations become Institution rows and AFFILIATED_WITH edges, raw affiliation
  * strings stay on the mention node, topics become Topic rows and HAS_TOPIC edges. A non-empty
  * `abstract_inverted_index` is recorded as `abstractTextRef` (`<input>#<W id>/abstract_inverted_index`);
  * abstract text is never copied.
  */
object OpenAlexNormalizer:

  private def parse(line: String): Either[String, ujson.Value] =
    Try(ujson.read(line)).toEither.left.map(e => Option(e.getMessage).getOrElse(e.toString))

  private def openAlexId(raw: Option[String], letter: Char): Option[String] =
    raw.flatMap(r => ExternalIds.normalizeOpenAlex(r, letter).toOption)

  private def institutions(
      out: EmitBuffer,
      authorship: ujson.Value,
      ctx: SourceContext
  ): Vector[String] =
    Json.arr(authorship, "institutions").flatMap { inst =>
      openAlexId(Json.str(inst, "id"), 'I').flatMap { iid =>
        val name = Json.str(inst, "display_name").getOrElse(iid)
        StableIds.institutionId(InstitutionNamespace.OpenAlex, iid).toOption.map { id =>
          out.row(
            "institution",
            id,
            Institution(
              schemaVersion = SchemaVersions.Institution,
              institutionId = id,
              name = name,
              rorId = Json.str(inst, "ror").flatMap(r => ExternalIds.normalizeRor(r).toOption),
              countryCode = Json.str(inst, "country_code").filter("[A-Z]{2}".r.matches),
              provenance = Records.provenance(SourceType.OPENALEX, iid, ctx.snapshot, ctx.runId)
            )
          )
          id
        }
      }
    }

  def works(line: String, ctx: SourceContext): Vector[Emitted] =
    val out = new EmitBuffer
    parse(line) match
      case Left(err) => out.reject("MALFORMED_JSON", s"${ctx.inputLocation}", s"$err | $line")
      case Right(w) =>
        openAlexId(Json.str(w, "id"), 'W') match
          case None => out.reject("MISSING_KEY", ctx.inputLocation, line)
          case Some(oid) =>
            val title = Json.str(w, "title").orElse(Json.str(w, "display_name"))
            if title.isEmpty then out.reject("MISSING_TITLE", oid, line)
            else work(out, w, oid, title.get, ctx)
    out.result()

  private def authorName(a: ujson.Value): Option[String] =
    Json
      .str(a, "raw_author_name")
      .orElse(Json.obj(a, "author").flatMap(Json.str(_, "display_name")))

  private def work(
      out: EmitBuffer,
      w: ujson.Value,
      oid: String,
      title: String,
      ctx: SourceContext
  ): Unit =
    val unnamed = Json.arr(w, "authorships").indexWhere(a => authorName(a).isEmpty)
    if unnamed >= 0 then
      out.reject("MISSING_AUTHOR_NAME", oid, s"authorship ${unnamed + 1} has no name")
    else workWithNamedAuthors(out, w, oid, title, ctx)

  private def workWithNamedAuthors(
      out: EmitBuffer,
      w: ujson.Value,
      oid: String,
      title: String,
      ctx: SourceContext
  ): Unit =
    val workId = StableIds.workId(WorkNamespace.OpenAlex, oid).toOption.get
    val prov = Records.provenance(SourceType.OPENALEX, oid, ctx.snapshot, ctx.runId)
    val ids = Json.obj(w, "ids")
    // First candidate that normalizes: an invalid `doi` never hides a valid `ids.doi`.
    val doi = Json.firstValid(Json.str(w, "doi"), ids.flatMap(Json.str(_, "doi")))(d =>
      ExternalIds.normalizeDoi(d).toOption
    )
    val arxiv =
      ids.flatMap(Json.str(_, "arxiv")).flatMap(a => ExternalIds.normalizeArxiv(a).toOption)
    val source = Json.obj(w, "primary_location").flatMap(Json.obj(_, "source"))
    val venueOid = openAlexId(source.flatMap(Json.str(_, "id")), 'S')
    val venueId = venueOid.flatMap(s => StableIds.venueId(VenueNamespace.OpenAlex, s).toOption)
    val mentions = Json.arr(w, "authorships").zipWithIndex.flatMap { (a, i) =>
      val position = i + 1
      val author = Json.obj(a, "author")
      val rawName = authorName(a)
      rawName.map { name =>
        val folded = Normalize.personName(name)
        val mentionId = StableIds.mentionId(workId, position).toOption.get
        val authorOid = openAlexId(author.flatMap(Json.str(_, "id")), 'A')
        val orcid = Json.firstValid(author.flatMap(Json.str(_, "orcid")), Json.str(a, "raw_orcid"))(
          o => ExternalIds.normalizeOrcid(o).toOption
        )
        val m = AuthorMention(
          schemaVersion = SchemaVersions.AuthorMention,
          mentionId = mentionId,
          workId = workId,
          position = position,
          rawName = name,
          normalizedName = if folded.nonEmpty then folded else Normalize.stripInvisible(name),
          sourceAuthorKey = authorOid,
          orcid = orcid,
          provenance = prov
        )
        val instIds = institutions(out, a, ctx)
        instIds.foreach { iid =>
          SourceEdges
            .edge(
              EdgeType.AFFILIATED_WITH,
              mentionId,
              iid,
              Vector(workId),
              Map("assertedBy" -> "openalex-authorship"),
              prov
            )
            .foreach(e => out.row("graph_edge", e.edgeId, e))
        }
        val props = TreeMap.newBuilder[String, String]
        props += "openalexWorkId" -> oid
        authorOid.foreach(x => props += "openalexAuthorId" -> x)
        Json.str(a, "author_position").foreach(x => props += "authorPosition" -> x)
        Json.bool(a, "is_corresponding").foreach(x => props += "isCorresponding" -> x.toString)
        author.flatMap(Json.str(_, "orcid")).foreach(x => props += "orcidRaw" -> x)
        Json.str(a, "raw_orcid").foreach(x => props += "rawOrcid" -> x)
        Json
          .strs(a, "raw_affiliation_strings")
          .zipWithIndex
          .foreach((s, j) => props += s"rawAffiliation.$j" -> s)
        Json.strs(a, "countries").zipWithIndex.foreach((s, j) => props += s"country.$j" -> s)
        instIds.zipWithIndex.foreach((s, j) => props += s"institutionId.$j" -> s)
        out.row(
          "mention_node",
          mentionId,
          GraphNode(
            SchemaVersions.GraphNode,
            mentionId,
            NodeType.AuthorMention,
            name,
            props.result(),
            prov
          )
        )
        m
      }
    }
    locally {
      val abstractRef =
        if Json.nonEmpty(w, "abstract_inverted_index") then
          Some(s"${ctx.inputLocation}#$oid/abstract_inverted_index")
        else None
      val normalizedTitle = Normalize.title(title)
      val record = WorkRecord(
        schemaVersion = SchemaVersions.WorkRecord,
        workId = workId,
        source = SourceType.OPENALEX,
        sourceRecordId = oid,
        recordType = Json.str(w, "type").getOrElse("unknown"),
        title = Normalize.collapseWhitespace(title),
        normalizedTitle =
          if normalizedTitle.nonEmpty then normalizedTitle else Normalize.fallbackLower(title),
        year = Json.int(w, "publication_year"),
        venueId = venueId,
        doi = doi,
        arxivId = arxiv,
        authors = mentions,
        abstractTextRef = abstractRef,
        provenance = prov
      )
      out.row("work", workId, record)
      mentions.foreach(m => out.row("author_mention", m.mentionId, m))
      // Venue and PUBLISHED_IN.
      for
        s <- source
        soid <- venueOid
        vid <- venueId
      do
        out.row(
          "venue",
          vid,
          Venue(
            schemaVersion = SchemaVersions.Venue,
            venueId = vid,
            name = Json.str(s, "display_name").getOrElse(soid),
            venueType = Json.str(s, "type"),
            sourceVenueKey = Some(soid),
            issns = Json
              .strs(s, "issn")
              .flatMap(i => ExternalIds.normalizeIssn(i).toOption)
              .distinct
              .sorted,
            provenance = Records.provenance(SourceType.OPENALEX, soid, ctx.snapshot, ctx.runId)
          )
        )
        SourceEdges
          .edge(
            EdgeType.PUBLISHED_IN,
            workId,
            vid,
            Vector(workId),
            Map("assertedBy" -> "openalex-primary-location"),
            prov
          )
          .foreach(e => out.row("graph_edge", e.edgeId, e))
      // Topics and HAS_TOPIC.
      val primaryTopic =
        Json.obj(w, "primary_topic").flatMap(t => openAlexId(Json.str(t, "id"), 'T'))
      Json.arr(w, "topics").foreach { t =>
        openAlexId(Json.str(t, "id"), 'T').foreach { toid =>
          StableIds.topicId(TopicNamespace.OpenAlex, toid).toOption.foreach { tid =>
            out.row(
              "topic",
              tid,
              Topic(
                SchemaVersions.Topic,
                tid,
                Json.str(t, "display_name").getOrElse(toid),
                None,
                Records.provenance(SourceType.OPENALEX, toid, ctx.snapshot, ctx.runId)
              )
            )
            val props = TreeMap.newBuilder[String, String]
            props += "assertedBy" -> "openalex-topics"
            props += "primary" -> (primaryTopic.contains(toid)).toString
            Json.obj(t, "score").flatMap(Json.scalar).foreach(s => props += "score" -> s)
            Seq("subfield", "field", "domain").foreach { level =>
              Json.obj(t, level).flatMap(Json.str(_, "id")).foreach(x => props += s"$level.id" -> x)
              Json
                .obj(t, level)
                .flatMap(Json.str(_, "display_name"))
                .foreach(x => props += s"$level.name" -> x)
            }
            SourceEdges
              .edge(EdgeType.HAS_TOPIC, workId, tid, Vector(workId), props.result(), prov)
              .foreach(e => out.row("graph_edge", e.edgeId, e))
          }
        }
      }
      // Work node: every source-specific identifier and descriptive field without a WorkRecord slot.
      val props = TreeMap.newBuilder[String, String]
      props += "openalexWorkId" -> oid
      ids.foreach {
        case o: ujson.Obj =>
          o.value.foreach((k, v) => Json.scalar(v).foreach(s => props += s"ids.$k" -> s))
        case _ => ()
      }
      Seq(
        "type",
        "language",
        "publication_date",
        "is_retracted",
        "is_xpac",
        "is_paratext",
        "updated_date"
      )
        .foreach(k => Json.obj(w, k).flatMap(Json.scalar).foreach(s => props += k -> s))
      Json
        .obj(w, "primary_location")
        .flatMap(Json.str(_, "landing_page_url"))
        .foreach(x => props += "landingPageUrl" -> x)
      Json
        .obj(w, "primary_location")
        .flatMap(Json.str(_, "raw_source_name"))
        .foreach(x => props += "rawSourceName" -> x)
      Json.strs(w, "indexed_in").zipWithIndex.foreach((s, j) => props += s"indexedIn.$j" -> s)
      Json
        .strs(w, "referenced_works")
        .zipWithIndex
        .foreach((s, j) => props += s"referencedWork.$j" -> s)
      abstractRef.foreach(r => props += "abstractTextRef" -> r)
      out.row(
        "work_node",
        workId,
        GraphNode(
          SchemaVersions.GraphNode,
          workId,
          NodeType.Work,
          Normalize.collapseWhitespace(title),
          props.result(),
          prov
        )
      )
    }

  /** OpenAlex author profiles: `researcher_node` (keyed by the OpenAlex-namespace researcher ID of
    * the source profile; not a resolved identity), institutions and AFFILIATED_WITH edges.
    */
  def authors(line: String, ctx: SourceContext): Vector[Emitted] =
    val out = new EmitBuffer
    parse(line) match
      case Left(err) => out.reject("MALFORMED_JSON", ctx.inputLocation, s"$err | $line")
      case Right(a) =>
        openAlexId(Json.str(a, "id"), 'A') match
          case None => out.reject("MISSING_KEY", ctx.inputLocation, line)
          case Some(aid) =>
            val nodeId =
              StableIds.researcherIdFromExternal(ResearcherNamespace.OpenAlex, aid).toOption.get
            val prov = Records.provenance(SourceType.OPENALEX, aid, ctx.snapshot, ctx.runId)
            val name = Json.str(a, "display_name").getOrElse(aid)
            val props = TreeMap.newBuilder[String, String]
            props += "openalexAuthorId" -> aid
            Json.str(a, "orcid").foreach { o =>
              props += "orcidRaw" -> o
              ExternalIds.normalizeOrcid(o).foreach(n => props += "orcid" -> n)
            }
            Json.obj(a, "ids").foreach {
              case o: ujson.Obj =>
                o.value.foreach((k, v) => Json.scalar(v).foreach(s => props += s"ids.$k" -> s))
              case _ => ()
            }
            Json
              .strs(a, "display_name_alternatives")
              .zipWithIndex
              .foreach((s, j) => props += s"nameAlternative.$j" -> s)
            Json.obj(a, "works_count").flatMap(Json.scalar).foreach(s => props += "worksCount" -> s)
            val lastKnown = Json
              .arr(a, "last_known_institutions")
              .map(i => ujson.Obj("institutions" -> ujson.Arr(i)))
            val affiliations = Json
              .arr(a, "affiliations")
              .flatMap(af => Json.obj(af, "institution"))
              .map(i => ujson.Obj("institutions" -> ujson.Arr(i)))
            val instIds =
              (lastKnown ++ affiliations).flatMap(institutions(out, _, ctx)).distinct.sorted
            instIds.zipWithIndex.foreach((s, j) => props += s"institutionId.$j" -> s)
            instIds.foreach { iid =>
              SourceEdges
                .edge(
                  EdgeType.AFFILIATED_WITH,
                  nodeId,
                  iid,
                  Vector(nodeId),
                  Map("assertedBy" -> "openalex-author-profile"),
                  prov
                )
                .foreach(e => out.row("graph_edge", e.edgeId, e))
            }
            out.row(
              "researcher_node",
              nodeId,
              GraphNode(
                SchemaVersions.GraphNode,
                nodeId,
                NodeType.Researcher,
                name,
                props.result(),
                prov
              )
            )
    out.result()
