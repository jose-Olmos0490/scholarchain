package scholarchain.storage

import scholarchain.core.model.*
import scholarchain.storage.common.*
import scholarchain.storage.directory.*
import scholarchain.storage.dynamo.*
import scholarchain.storage.graph.*

/** Contract of [[ResearcherDirectory]]. The fake and the DynamoDB adapter (over the in-memory item
  * store, and over a real table behind the AWS gate) must all pass it.
  */
abstract class ResearcherDirectoryContract extends PortContract:
  /** The run every candidate of the suite belongs to. */
  protected def runId: String

  /** The largest limit the directory under test accepts. */
  protected def maxLimit: Int = 3
  protected def directory: ResearcherDirectory
  protected def seed(normalizedName: String, candidate: NameCandidate): Unit

  private def unique(prefix: String) = s"$prefix${RM.nextN()}"

  contract(
    "directory: candidates are ordered by confidence descending, then researcherId ascending"
  ) {
    val name = unique("wei zhang ")
    val ns = Vector(RM.nextN(), RM.nextN(), RM.nextN())
    seed(name, RM.candidate(ns(0), "Wei Zhang", 0.80, runId))
    seed(name, RM.candidate(ns(1), "Wei Zhang", 0.95, runId))
    seed(name, RM.candidate(ns(2), "Wei Zhang", 0.80, runId))
    val got = ok(directory.candidatesByName(name, 3))
    got.map(_.researcherId) shouldBe
      Vector(RM.researcherId(ns(1))) ++ Vector(ns(0), ns(2)).map(RM.researcherId).sorted
    got.map(_.confidence) shouldBe Vector(0.95, 0.80, 0.80)
  }

  contract("directory: confidences that differ in the last bit are still ordered exactly") {
    val name = unique("ada close ")
    val hi = 0.9
    val lo = Math.nextDown(hi)
    val a = RM.nextN()
    val b = RM.nextN()
    // the lower confidence gets the smaller researcherId, so only the confidence can put it second
    val (small, large) = (math.min(a, b), math.max(a, b))
    val (lowerId, higherId) =
      if RM.researcherId(small) < RM.researcherId(large) then (small, large) else (large, small)
    seed(name, RM.candidate(lowerId, "Ada", lo, runId))
    seed(name, RM.candidate(higherId, "Ada", hi, runId))
    ok(directory.candidatesByName(name, 2)).map(_.confidence) shouldBe Vector(hi, lo)
  }

  contract("directory: the limit bounds the result and is applied after ordering") {
    val name = unique("lin chen ")
    val ns = (1 to 3).map(_ => RM.nextN()).toVector
    ns.zip(Vector(0.5, 0.9, 0.7))
      .foreach((n, c) => seed(name, RM.candidate(n, "Lin Chen", c, runId)))
    ok(directory.candidatesByName(name, 2)).map(_.confidence) shouldBe Vector(0.9, 0.7)
    ok(directory.candidatesByName(name, 1)).map(_.confidence) shouldBe Vector(0.9)
  }

  contract("directory: a candidate is returned exactly as stored, with its audit fields") {
    val name = unique("sam low ")
    val n = RM.nextN()
    val c = RM
      .candidate(n, "Sam Low", 0.6, runId)
      .copy(
        sourceIds = Vector("dblp:Sam Low 0002", "openAlex:A1"),
        affiliations = Vector("i1", "i2"),
        coauthorSample = Vector("Bo Sample"),
        matchDecisionIds = Vector("d1", "d2")
      )
    seed(name, c)
    ok(directory.candidatesByName(name, 3)) shouldBe Vector(c)
  }

  contract("directory: the match is exact, an unknown or different name is an empty result") {
    val name = unique("exact name ")
    seed(name, RM.candidate(RM.nextN(), "Exact Name", 0.7, runId))
    ok(directory.candidatesByName(s"$name x", 3)) shouldBe empty
    ok(directory.candidatesByName(name.dropRight(1), 3)) shouldBe empty
    ok(directory.candidatesByName(name.toUpperCase, 3)) shouldBe empty
  }

  contract("directory: a blank name and a limit outside 1..max are refused, never clamped") {
    err(directory.candidatesByName("  ", 1)) shouldBe a[StorageError.InvalidRecord]
    err(directory.candidatesByName("x y", 0)) shouldBe a[StorageError.InvalidRecord]
    err(directory.candidatesByName("x y", maxLimit + 1)) shouldBe a[StorageError.LimitExceeded]
  }

/** Contract of [[EdgeSupportReader]]. */
abstract class EdgeSupportReaderContract extends PortContract:
  protected def runId: String
  protected def reader: EdgeSupportReader
  protected def seed(row: CoauthorEdgeSupport): Unit

  private def pairOf() = (RM.researcherId(RM.nextN()), RM.researcherId(RM.nextN()))

  contract("edge support: the distinct supporting works are returned sorted ascending") {
    val (x, y) = pairOf()
    val works = Vector.fill(3)(RM.workId(RM.nextN()))
    // seeded out of order, one of them twice
    (works.reverse :+ works.head).foreach(w => seed(RM.support(x, y, 2010, 2013, w, runId)))
    ok(reader.supportingWorkIds(RM.edgeView(x, y, 2010, 2013, 3, runId))) shouldBe works.sorted
  }

  contract("edge support: an edge without support rows has an empty, not a missing, result") {
    val (x, y) = pairOf()
    ok(reader.supportingWorkIds(RM.edgeView(x, y, 2010, 2013, 1, runId))) shouldBe Vector.empty
  }

  contract("edge support: rows of another window or another edge are not returned") {
    val (x, y) = pairOf()
    val z = RM.researcherId(RM.nextN())
    val mine = RM.workId(RM.nextN())
    seed(RM.support(x, y, 2010, 2013, mine, runId))
    seed(RM.support(x, y, 2010, 2012, RM.workId(RM.nextN()), runId))
    seed(RM.support(x, z, 2010, 2013, RM.workId(RM.nextN()), runId))
    ok(reader.supportingWorkIds(RM.edgeView(x, y, 2010, 2013, 1, runId))) shouldBe Vector(mine)
  }

  contract("edge support: rows of another run are not returned") {
    val (x, y) = pairOf()
    val mine = RM.workId(RM.nextN())
    seed(RM.support(x, y, 2010, 2013, mine, runId))
    val otherRun = Fresh.runId()
    ok(reader.supportingWorkIds(RM.edgeView(x, y, 2010, 2013, 1, otherRun))) shouldBe Vector.empty
  }

  contract("edge support: a pointer that is not the support reference of the edge is refused") {
    val (x, y) = pairOf()
    val view = RM.edgeView(x, y, 2010, 2013, 1, runId)
    err(reader.supportingWorkIds(view.copy(evidencePointer = "s3://bucket/key"))) shouldBe
      a[StorageError.InvalidRecord]
    val (p, q) = pairOf()
    val other = RM.edgeView(p, q, 2010, 2013, 1, runId)
    err(reader.supportingWorkIds(view.copy(evidencePointer = other.evidencePointer))) shouldBe
      a[StorageError.InvalidRecord]
  }

/** Contract of [[AnalyticsReader]]. `commitConnectivity` stands for the loader's final step: the
  * counts of a community exist only after it.
  */
abstract class AnalyticsReaderContract extends PortContract:
  protected def runId: String
  protected def maxLimit: Int = 3
  protected def reader: AnalyticsReader
  protected def seedComponent(m: ComponentMembership): Unit
  protected def seedCommunity(m: CommunityMembership): Unit
  protected def seedCross(e: CrossCommunityEdge): Unit
  protected def commitConnectivity(community: String, ws: Int, we: Int): Unit

  private def rid() = RM.researcherId(RM.nextN())

  contract("analytics: component and community membership round trip per researcher and window") {
    val r = rid()
    val c = RM.component(r, r, 1, 2010, 2013, runId)
    val m = RM.community(r, r, 1, 2010, 2013, runId)
    seedComponent(c)
    seedCommunity(m)
    ok(reader.componentOf(r, 2010, 2013)) shouldBe Some(c)
    ok(reader.communityOf(r, 2010, 2013)) shouldBe Some(m)
  }

  contract(
    "analytics: a record of another window, or none at all, is None (windows are not merged)"
  ) {
    val r = rid()
    seedComponent(RM.component(r, r, 1, 2010, 2012, runId))
    ok(reader.componentOf(r, 2010, 2013)) shouldBe None
    ok(reader.communityOf(r, 2010, 2012)) shouldBe None
    ok(reader.componentOf(rid(), 2010, 2012)) shouldBe None
  }

  contract("analytics: an inverted window is refused") {
    err(reader.componentOf(rid(), 2013, 2010)) shouldBe a[StorageError.InvalidRecord]
    err(reader.communityOf(rid(), 2013, 2010)) shouldBe a[StorageError.InvalidRecord]
  }

  /** Community `c` with `n` cross edges to `n` different communities, joint counts 1..n. */
  private def star(n: Int, ws: Int = 2010, we: Int = 2013): (String, Vector[CrossCommunityEdge]) =
    val c = rid()
    val edges = (1 to n).toVector.map { k =>
      val other = rid()
      RM.cross(c, other, c, other, k.toLong, ws, we, runId)
    }
    edges.foreach(seedCross)
    (c, edges)

  contract("analytics: cross-community edges are ordered by joint count descending, then edge ID") {
    val (c, edges) = star(3)
    val got = ok(reader.crossCommunityEdges(c, 2010, 2013, 3))
    got.map(_.jointPublicationCount) shouldBe Vector(3L, 2L, 1L)
    got.map(_.edgeId) shouldBe edges.sortBy(e => (-e.jointPublicationCount, e.edgeId)).map(_.edgeId)
    got shouldBe edges.sortBy(e => (-e.jointPublicationCount, e.edgeId))
  }

  contract("analytics: a cross-community edge is found from either of its communities") {
    val (c, edges) = star(1)
    val e = edges.head
    val other = if e.communityA == c then e.communityB else e.communityA
    ok(reader.crossCommunityEdges(other, 2010, 2013, 3)) shouldBe Vector(e)
  }

  contract("analytics: the edge limit truncates the list but never the stored counts") {
    val (c, edges) = star(3)
    ok(reader.crossCommunityEdges(c, 2010, 2013, 2)).map(_.jointPublicationCount) shouldBe
      Vector(3L, 2L)
    commitConnectivity(c, 2010, 2013)
    ok(reader.crossCommunityEdgeCount(c, 2010, 2013)) shouldBe edges.size.toLong
    ok(reader.communitiesConnected(c, 2010, 2013)) shouldBe edges.size.toLong
  }

  contract("analytics: two edges to one community count as two edges and one connected community") {
    val c = rid()
    val other = rid()
    seedCross(RM.cross(c, rid(), c, other, 2, 2010, 2013, runId))
    seedCross(RM.cross(c, rid(), c, other, 1, 2010, 2013, runId))
    commitConnectivity(c, 2010, 2013)
    ok(reader.crossCommunityEdgeCount(c, 2010, 2013)) shouldBe 2L
    ok(reader.communitiesConnected(c, 2010, 2013)) shouldBe 1L
  }

  contract("analytics: counts are per window") {
    val c = rid()
    seedCross(RM.cross(c, rid(), c, rid(), 1, 2010, 2013, runId))
    seedCross(RM.cross(c, rid(), c, rid(), 1, 2010, 2012, runId))
    seedCross(RM.cross(c, rid(), c, rid(), 1, 2010, 2012, runId))
    commitConnectivity(c, 2010, 2013)
    commitConnectivity(c, 2010, 2012)
    ok(reader.crossCommunityEdgeCount(c, 2010, 2013)) shouldBe 1L
    ok(reader.crossCommunityEdgeCount(c, 2010, 2012)) shouldBe 2L
  }

  contract("analytics: counts that were never stored are NotFound, not zero") {
    val (c, _) = star(2)
    err(reader.crossCommunityEdgeCount(c, 2010, 2013)) shouldBe a[StorageError.NotFound]
    err(reader.communitiesConnected(c, 2010, 2013)) shouldBe a[StorageError.NotFound]
    err(reader.crossCommunityEdgeCount(rid(), 2010, 2013)) shouldBe a[StorageError.NotFound]
  }

  contract("analytics: a stored community with no cross-community edge has a count of zero") {
    val c = rid()
    commitConnectivity(c, 2010, 2013)
    ok(reader.crossCommunityEdgeCount(c, 2010, 2013)) shouldBe 0L
    ok(reader.communitiesConnected(c, 2010, 2013)) shouldBe 0L
    ok(reader.crossCommunityEdges(c, 2010, 2013, 3)) shouldBe empty
  }

  contract("analytics: a limit outside 1..max is refused, never clamped") {
    err(reader.crossCommunityEdges(rid(), 2010, 2013, 0)) shouldBe a[StorageError.InvalidRecord]
    err(reader.crossCommunityEdges(rid(), 2010, 2013, maxLimit + 1)) shouldBe
      a[StorageError.LimitExceeded]
  }
