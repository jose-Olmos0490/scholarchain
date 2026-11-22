package scholarchain.rag

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.config.{DistanceMetric, VectorIndexConfig}
import scholarchain.core.ids.*
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions as SV
import scholarchain.rag.chunk.ChunkingConfig
import scholarchain.rag.common.*
import scholarchain.rag.embed.EmbeddingModelSpec
import scholarchain.rag.ingest.*

trait RagSuite extends AnyFunSuite with Matchers:
  def ok[A](e: Either[String, A]): A = e.fold(m => fail(m), identity)
  def good[A](r: RResult[A]): A = r.fold(p => fail(p.message), identity)
  def bad[A](r: RResult[A]): RagProblem = r match
    case Left(p) => p
    case Right(v) => fail(s"expected a refusal, got $v")

/** Deterministic IDs, rows and settings shared by the rag specs. */
object RagFixtures:
  private def ok[A](e: Either[String, A]): A =
    e.fold(m => throw new IllegalStateException(m), identity)

  val runId: String = ok(
    StableIds.runId(
      RunManifest(
        SV.RunManifest,
        "spark-graph",
        "rag-fixture",
        Vector(RunInput(SourceType.DBLP, "2026-11-01", "fixture/dblp.xml", "sha256:" + "ab" * 32)),
        Map("fixture" -> "rag-v1"),
        Map("coauthorPolicy" -> "fixture-policy-v1")
      )
    )
  )
  val snapshot = "2026-11-01"
  val metricVersion = "fixture-metrics-v1"
  val policyVersion = "fixture-coauthor-policy-v1"

  def prov(source: String, dataset: SourceType = SourceType.SCHOLARCHAIN): Provenance =
    Provenance(SV.Provenance, dataset, source, snapshot, runId, None, None, None, Vector.empty)

  def researcher(n: Int): String =
    ok(StableIds.researcherIdFromExternal(ResearcherNamespace.OpenAlex, s"A${5000000000L + n}"))
  def topic(n: Int): String = ok(StableIds.topicId(TopicNamespace.OpenAlex, s"T$n"))
  def institution(n: Int): String =
    ok(StableIds.institutionId(InstitutionNamespace.OpenAlex, s"I$n"))
  def work(n: Int): String = ok(StableIds.workId(WorkNamespace.OpenAlex, s"W${2000000000L + n}"))
  def venue(n: Int): String = ok(StableIds.venueId(VenueNamespace.OpenAlex, s"S$n"))

  val indexNames: VectorIndexConfig = VectorIndexConfig(
    "researcher-profile-v1",
    "collaboration-community-v1",
    "paper-abstract-v1",
    "collaboration-path-v1",
    "topic-institution-summary-v1"
  )

  val chunking: ChunkingConfig =
    ChunkingConfig(maxTokens = 200, chunkTokens = 100, overlapTokens = 20)
  val renderSettings: RenderSettings = RenderSettings(chunking, 200, 2000, 3, 3)

  val modelSpec: EmbeddingModelSpec =
    EmbeddingModelSpec("test-embedding-model", "2026-01", 256, normalize = true)

  def fact(
      subjectType: SemanticSubjectType,
      subjectId: String,
      window: (Int, Int),
      name: Option[String] = None,
      metrics: Vector[MetricValue] = Vector(
        MetricValue(CollaborationMetric.PUBLICATION_COUNT, 12),
        MetricValue(CollaborationMetric.AVERAGE_COAUTHORS_PER_PAPER, 3.25),
        MetricValue(CollaborationMetric.DISTINCT_COAUTHOR_COUNT, 7)
      ),
      topics: Vector[RankedEntity] = Vector.empty,
      institutions: Vector[RankedEntity] = Vector.empty,
      related: Vector[String] = Vector.empty,
      works: Vector[RepresentativeWork] = Vector.empty
  ): SemanticFact =
    SemanticFact(
      SV.SemanticFact,
      ok(
        StableIds.semanticFactId(subjectType, subjectId, window._1, window._2, metricVersion, runId)
      ),
      subjectType,
      subjectId,
      name,
      window._1,
      window._2,
      metrics,
      topics,
      Vector.empty,
      institutions,
      related,
      works,
      metricVersion,
      runId,
      prov(subjectId)
    )

  def path(source: Int, target: Int, via: Vector[Int], window: (Int, Int)): PathEvidence =
    val nodes = (source +: via :+ target).map(researcher)
    val hops = nodes
      .sliding(2)
      .zipWithIndex
      .map { (p, i) =>
        PathHop(
          p(0),
          p(1),
          ok(StableIds.coauthorEdgeId(p(0), p(1), window._1, window._2, policyVersion)),
          2,
          Vector(work(source * 100 + i * 2 + 1), work(source * 100 + i * 2))
        )
      }
      .toVector
    PathEvidence(
      SV.PathEvidence,
      ok(StableIds.pathId(nodes.head, nodes.last, window._1, window._2, policyVersion, runId)),
      nodes.head,
      nodes.last,
      window._1,
      window._2,
      PathSelection.BENCHMARK,
      PathStatus.FOUND,
      4,
      Some(hops.size),
      hops,
      policyVersion,
      runId,
      prov(nodes.head)
    )

  def workRecord(n: Int, title: String, year: Option[Int]): WorkRecord =
    WorkRecord(
      SV.WorkRecord,
      work(n),
      SourceType.DBLP,
      s"conf/fixture/$n",
      "article",
      title,
      title.toLowerCase,
      year,
      Some(venue(1)),
      None,
      None,
      Vector.empty,
      None,
      prov(s"conf/fixture/$n", SourceType.DBLP)
    )
