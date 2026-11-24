package scholarchain.storage

import java.util.concurrent.atomic.AtomicInteger
import scholarchain.core.ids.*
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions as SV
import scholarchain.storage.common.*
import scholarchain.storage.directory.NameCandidate
import scholarchain.storage.graph.CoauthorEdgeView

/** Valid records for the read-model tests. Every ID comes from the frozen `StableIds` formulas, so
  * the core record contracts accept them. IDs are drawn from one counter, so contracts that share a
  * real table never collide.
  */
object RM:
  private def ok[A](e: Either[String, A]): A =
    e.fold(m => throw new IllegalStateException(m), identity)

  private val counter = new AtomicInteger(1000 + scala.util.Random.nextInt(1000000))
  def nextN(): Int = counter.incrementAndGet()

  val Policy = "rm-policy-v1"
  val MetricVersion = "rm-metrics-v1"

  def researcherId(n: Int): String =
    ok(StableIds.researcherIdFromExternal(ResearcherNamespace.OpenAlex, s"A$n"))

  def workId(n: Int): String = ok(StableIds.workId(WorkNamespace.Dblp, s"conf/rm/Work$n"))

  /** Canonical order of two researcher IDs. */
  def pair(x: String, y: String): (String, String) =
    val p = ok(CanonicalPair.of(x, y))
    (p.a, p.b)

  def edgeId(x: String, y: String, ws: Int, we: Int): String =
    val (a, b) = pair(x, y)
    ok(StableIds.coauthorEdgeId(a, b, ws, we, Policy))

  def prov(run: String, source: String): Provenance =
    Provenance(
      SV.Provenance,
      SourceType.SCHOLARCHAIN,
      source,
      "2026-11-01",
      run,
      None,
      None,
      None,
      Vector.empty
    )

  def support(
      x: String,
      y: String,
      ws: Int,
      we: Int,
      work: String,
      run: String
  ): CoauthorEdgeSupport =
    val (a, b) = pair(x, y)
    val id = edgeId(x, y, ws, we)
    CoauthorEdgeSupport(
      SV.CoauthorEdgeSupport,
      id,
      a,
      b,
      ws,
      we,
      work,
      Some(ws),
      Policy,
      run,
      prov(run, id)
    )

  def edgeView(x: String, y: String, ws: Int, we: Int, joint: Long, run: String): CoauthorEdgeView =
    val (a, b) = pair(x, y)
    val id = edgeId(x, y, ws, we)
    CoauthorEdgeView(
      id,
      a,
      b,
      joint,
      ws,
      we,
      ws,
      we,
      CoauthorEdgeSupport.supportRef(id),
      run,
      Policy
    )

  def component(
      r: String,
      c: String,
      size: Long,
      ws: Int,
      we: Int,
      run: String
  ): ComponentMembership =
    ComponentMembership(
      SV.ComponentMembership,
      r,
      c,
      size,
      ws,
      we,
      MetricVersion,
      run,
      prov(run, r)
    )

  def community(
      r: String,
      c: String,
      size: Long,
      ws: Int,
      we: Int,
      run: String
  ): CommunityMembership =
    CommunityMembership(
      SV.CommunityMembership,
      r,
      c,
      size,
      ws,
      we,
      MetricVersion,
      run,
      prov(run, r)
    )

  def cross(
      x: String,
      y: String,
      communityOfX: String,
      communityOfY: String,
      joint: Long,
      ws: Int,
      we: Int,
      run: String
  ): CrossCommunityEdge =
    val (a, b) = pair(x, y)
    val (ca, cb) = if a == x then (communityOfX, communityOfY) else (communityOfY, communityOfX)
    val id = edgeId(x, y, ws, we)
    CrossCommunityEdge(
      SV.CrossCommunityEdge,
      id,
      a,
      b,
      ca,
      cb,
      ws,
      we,
      joint,
      Policy,
      MetricVersion,
      run,
      prov(run, id)
    )

  /** A canonical researcher with an OpenAlex identity. */
  def researcher(
      n: Int,
      displayName: String,
      confidence: Double,
      run: String,
      dblpKeys: Vector[String] = Vector.empty,
      decisions: Vector[String] = Vector.empty
  ): CanonicalResearcher =
    val id = researcherId(n)
    CanonicalResearcher(
      SV.CanonicalResearcher,
      id,
      displayName,
      None,
      None,
      Some(s"A$n"),
      dblpKeys,
      None,
      Vector(Fixture.institution.institutionId),
      confidence,
      prov(run, id).copy(policyVersion = Some(Policy), derivedFrom = decisions)
    )

  def candidate(n: Int, name: String, confidence: Double, run: String): NameCandidate =
    NameCandidate(
      researcherId(n),
      name,
      confidence,
      Vector(s"openAlex:A$n"),
      Vector("inst-1"),
      Vector("Ada Example"),
      Vector(s"decision-$n"),
      Policy,
      run
    )
