package scholarchain.rag.ingest

import scholarchain.core.config.VectorIndexConfig
import scholarchain.core.model.*
import scholarchain.rag.chunk.*
import scholarchain.rag.common.*
import scholarchain.rag.render.*

/** One chunk ready for the storage writer: text, identity and the filterable fields. The storage
  * `RenderedDocument` is built from it field by field (`ordinal` is the chunk ordinal).
  */
final case class RenderedChunk(
    indexName: String,
    entityId: String,
    documentType: String,
    windowStartYear: Int,
    windowEndYear: Int,
    ordinal: Int,
    chunkId: String,
    text: String,
    textHash: String,
    topicIds: Vector[String],
    institutionIds: Vector[String],
    sourceDataset: String,
    communityId: Option[String],
    templateVersion: String,
    sourceVersion: String,
    runId: String,
    provenance: Provenance
)

final case class RenderReject(entityId: String, indexName: String, code: String, detail: String)

final case class RenderBatch(chunks: Vector[RenderedChunk], rejects: Vector[RenderReject])

/** Limits and caps of rendering. `maxTopicIds` and `maxInstitutionIds` are the storage metadata
  * caps: the highest-ranked IDs are kept (the rendered text still lists every ranked entity).
  */
final case class RenderSettings(
    chunking: ChunkingConfig,
    maxUntrustedCodePoints: Int,
    maxAbstractCodePoints: Int,
    maxTopicIds: Int,
    maxInstitutionIds: Int
)

object RenderSettings:
  def load(env: Map[String, String]): RResult[RenderSettings] =
    val r = new EnvReader(env)
    val untrusted = r.int("SCHOLARCHAIN_RAG_MAX_UNTRUSTED_CODE_POINTS", 2)
    val abs = r.int("SCHOLARCHAIN_RAG_MAX_ABSTRACT_CODE_POINTS", 2)
    val topics = r.int("SCHOLARCHAIN_VECTOR_INGEST_MAX_TOPIC_IDS", 1)
    val insts = r.int("SCHOLARCHAIN_VECTOR_INGEST_MAX_INSTITUTION_IDS", 1)
    val chunking = ChunkingConfig.load(env)
    chunking.left.foreach(p => r.check(Some(p.detail)))
    r.result(RenderSettings(chunking.toOption.get, untrusted, abs, topics, insts))

/** render -> chunk, per document kind. Everything is deterministic: the same input yields the same
  * chunks in the same order.
  */
final class RenderPipeline(indexes: VectorIndexConfig, s: RenderSettings):

  private def chunked(
      index: String,
      entityId: String,
      docType: String,
      ws: Int,
      we: Int,
      sourceVersion: String,
      rendered: RenderedText,
      topics: Vector[String],
      insts: Vector[String],
      community: Option[String],
      runId: String,
      prov: Provenance
  ): RResult[Vector[RenderedChunk]] =
    Chunker.chunk(s.chunking, entityId, sourceVersion, docType, rendered.text).map { cs =>
      cs.map(c =>
        RenderedChunk(
          index,
          entityId,
          docType,
          ws,
          we,
          c.ordinal,
          c.chunkId,
          c.text,
          c.textHash,
          topics,
          insts,
          prov.sourceDataset.toString,
          community,
          rendered.templateVersion,
          sourceVersion,
          runId,
          prov
        )
      )
    }

  private def topIds(xs: Vector[RankedEntity], cap: Int): Vector[String] =
    xs.sortBy(e => (-e.count, e.entityId)).map(_.entityId).distinct.take(cap)

  /** Researcher, community, topic and institution facts. PATH facts are refused: a path is
    * rendered only from its [[PathEvidence]].
    */
  def renderFact(f: SemanticFact): RResult[Vector[RenderedChunk]] =
    val (index, docType, rendered) = f.subjectType match
      case SemanticSubjectType.RESEARCHER =>
        (
          indexes.researchers,
          Templates.DocResearcher,
          Templates.researcherProfile(f, s.maxUntrustedCodePoints)
        )
      case SemanticSubjectType.COMMUNITY =>
        (
          indexes.communities,
          Templates.DocCommunity,
          Templates.communitySummary(f, s.maxUntrustedCodePoints)
        )
      case SemanticSubjectType.TOPIC | SemanticSubjectType.INSTITUTION =>
        (
          indexes.topicInstitutions,
          Templates.DocTopicInstitution,
          Templates.topicInstitution(f, s.maxUntrustedCodePoints)
        )
      case SemanticSubjectType.PATH =>
        (
          indexes.paths,
          Templates.DocPath,
          Left(RagProblem("PATH_FACT_NOT_RENDERABLE", "render paths from PathEvidence"))
        )
    rendered.flatMap { r =>
      chunked(
        index,
        f.subjectId,
        docType,
        f.windowStartYear,
        f.windowEndYear,
        s"${r.templateVersion}/${f.metricVersion}",
        r,
        topIds(f.topTopics, s.maxTopicIds),
        topIds(f.topInstitutions, s.maxInstitutionIds),
        Option.when(f.subjectType == SemanticSubjectType.COMMUNITY)(f.subjectId),
        f.runId,
        f.provenance
      )
    }

  /** A verified path. The document is filed under its START researcher (`entityId`), so a
    * metadata filter on `entityId` finds the paths that start there. The storage key ordinal of a
    * path chunk is `pathOrdinal * 1000 + chunkOrdinal` where `pathOrdinal` is the 21-bit prefix
    * of the path ID's digest: distinct paths of one researcher get distinct, stable ordinals (a
    * clash is caught by the storage collision rule, never silently merged).
    */
  def renderPath(p: PathEvidence): RResult[Vector[RenderedChunk]] =
    Templates.pathSummary(p).flatMap { r =>
      chunked(
        indexes.paths,
        p.sourceResearcherId,
        Templates.DocPath,
        p.windowStartYear,
        p.windowEndYear,
        s"${r.templateVersion}/${p.policyVersion}",
        r,
        Vector.empty,
        Vector.empty,
        None,
        p.runId,
        p.provenance
      ).map(_.map(c => c.copy(ordinal = PathOrdinals.of(p.pathId) * 1000 + c.ordinal)))
    }

  def renderPaper(in: PaperInput): RResult[Vector[RenderedChunk]] =
    val w = in.work
    Templates.paperAbstract(in, s.maxUntrustedCodePoints, s.maxAbstractCodePoints).flatMap { r =>
      w.year match
        case None =>
          Left(RagProblem("NO_YEAR", s"work ${w.workId} has no year, so it has no window"))
        case Some(y) =>
          chunked(
            indexes.abstracts,
            w.workId,
            Templates.DocPaper,
            y,
            y,
            s"${r.templateVersion}/${w.provenance.snapshot}",
            r,
            Vector.empty,
            Vector.empty,
            None,
            w.provenance.runId,
            w.provenance
          )
    }

  def renderAll(
      facts: Iterable[SemanticFact],
      paths: Iterable[PathEvidence],
      papers: Iterable[PaperInput]
  ): RenderBatch =
    val chunks = Vector.newBuilder[RenderedChunk]
    val rejects = Vector.newBuilder[RenderReject]
    def take(id: String, index: String, r: RResult[Vector[RenderedChunk]]): Unit = r match
      case Right(cs) => chunks ++= cs
      case Left(p) => rejects += RenderReject(id, index, p.code, p.detail)
    facts.foreach(f =>
      take(
        f.subjectId,
        f.subjectType match
          case SemanticSubjectType.RESEARCHER => indexes.researchers
          case SemanticSubjectType.COMMUNITY => indexes.communities
          case SemanticSubjectType.PATH => indexes.paths
          case _ => indexes.topicInstitutions
        ,
        renderFact(f)
      )
    )
    paths.foreach(p => take(p.sourceResearcherId, indexes.paths, renderPath(p)))
    papers.foreach(p => take(p.work.workId, indexes.abstracts, renderPaper(p)))
    RenderBatch(chunks.result(), rejects.result())

object PathOrdinals:
  /** 21 bits of the path ID digest: at most 2^21 * 1000 < Int.MaxValue. */
  def of(pathId: String): Int =
    Integer.parseInt(pathId.split(":").last.take(6), 16) & 0x1fffff

/** `--index <name>`, `--limit <n>`, `--dry-run`, plus the input directory, run ID and snapshot. */
final case class IngestionOptions(
    indexName: Option[String],
    limit: Option[Int],
    dryRun: Boolean,
    inputDir: Option[String] = None,
    runId: Option[String] = None,
    snapshot: Option[String] = None
)

object IngestionOptions:
  def parse(args: Seq[String]): RResult[IngestionOptions] =
    def go(rest: List[String], o: IngestionOptions): RResult[IngestionOptions] = rest match
      case Nil => Right(o)
      case "--dry-run" :: t => go(t, o.copy(dryRun = true))
      case "--index" :: v :: t if !v.startsWith("--") => go(t, o.copy(indexName = Some(v)))
      case "--input-dir" :: v :: t if !v.startsWith("--") => go(t, o.copy(inputDir = Some(v)))
      case "--run-id" :: v :: t if !v.startsWith("--") => go(t, o.copy(runId = Some(v)))
      case "--snapshot" :: v :: t if !v.startsWith("--") => go(t, o.copy(snapshot = Some(v)))
      case "--limit" :: v :: t =>
        v.toIntOption.filter(_ >= 1) match
          case Some(n) => go(t, o.copy(limit = Some(n)))
          case None => Left(RagProblem("BAD_ARGUMENT", s"--limit '$v' must be an integer >= 1"))
      case other :: _ =>
        Left(RagProblem("BAD_ARGUMENT", s"unknown or incomplete argument '$other'"))
    go(args.toList, IngestionOptions(None, None, false))

  /** Applies `--index` and `--limit`. The limit counts documents (entity + type + window), never
    * splitting one document's chunks.
    */
  def select(o: IngestionOptions, chunks: Vector[RenderedChunk]): Vector[RenderedChunk] =
    val byIndex = o.indexName.fold(chunks)(n => chunks.filter(_.indexName == n))
    o.limit match
      case None => byIndex
      case Some(n) =>
        val docs = byIndex
          .map(c => (c.indexName, c.entityId, c.documentType, c.windowStartYear, c.windowEndYear))
          .distinct
          .take(n)
          .toSet
        byIndex.filter(c =>
          docs((c.indexName, c.entityId, c.documentType, c.windowStartYear, c.windowEndYear))
        )

/** What `--dry-run` prints: counts per index and sample metadata. It calls nothing. */
object DryRun:
  def report(chunks: Vector[RenderedChunk], rejects: Vector[RenderReject], samples: Int): String =
    val sb = new StringBuilder
    sb ++= s"DRY RUN: no AWS calls were made\nchunks: ${chunks.size}, render rejects: ${rejects.size}\n"
    chunks.groupBy(_.indexName).toVector.sortBy(_._1).foreach { (i, cs) =>
      val docs =
        cs.map(c => (c.entityId, c.documentType, c.windowStartYear, c.windowEndYear)).distinct.size
      sb ++= s"index $i: documents=$docs chunks=${cs.size} templates=${cs.map(_.templateVersion).distinct.mkString(",")}\n"
    }
    rejects
      .groupBy(_.code)
      .toVector
      .sortBy(_._1)
      .foreach((c, rs) => sb ++= s"reject $c: ${rs.size}\n")
    chunks.take(samples).foreach { c =>
      sb ++= ujson
        .Obj(
          "indexName" -> c.indexName,
          "entityId" -> c.entityId,
          "documentType" -> c.documentType,
          "windowStartYear" -> c.windowStartYear,
          "windowEndYear" -> c.windowEndYear,
          "ordinal" -> c.ordinal,
          "topicIds" -> ujson.Arr.from(c.topicIds),
          "institutionIds" -> ujson.Arr.from(c.institutionIds),
          "sourceDataset" -> c.sourceDataset,
          "communityId" -> c.communityId.map(ujson.Str(_)).getOrElse(ujson.Null),
          "textHash" -> c.textHash,
          "chunkId" -> c.chunkId,
          "templateVersion" -> c.templateVersion,
          "textTokens" -> Chunker.tokenCount(c.text)
        )
        .render() + "\n"
    }
    sb.toString
