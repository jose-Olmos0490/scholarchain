package scholarchain.adversarial.spark

import org.apache.spark.sql.Row
import scholarchain.adversarial.spark.SparkAdv.*
import scholarchain.core.config.BenchmarkPair
import scholarchain.spark.AnalysisWindow
import scholarchain.spark.graph.{AnalyticsOutputs, GraphOutputs}

/** Independent, single-machine reference implementations of the documented definitions in
  * modules/spark/JOBS.md, for fixtures without identity merges and without oversize teams (every
  * researcher is the fallback ID of its DBLP key). The Spark outputs are compared with these
  * definitions row by row; every mismatch is reported as one message.
  *
  *   - windows: a dated work belongs to every window whose [start, end] contains its year; a
  *     year-less work only to the all-years window
  *   - edges: distinct canonical researchers of a dated work, pairwise; weight = distinct works
  *   - metrics P, C, T, M, maximumTeamWorkIds, W (n_p counts distinct researchers)
  *   - components: componentId = smallest member ID
  *   - communities: synchronous LPA, unweighted, own label + one vote per neighbour, most votes
  *     wins, ties to the smallest label, at most `maxIterations` iterations
  *   - coreness by peeling; bridge measures from the communities
  *   - shortest paths: lexicographically smallest node sequence among ALL shortest paths (found
  *     here by exhaustive enumeration, not by the production greedy walk)
  */
object Oracle:

  final case class Graph(
      nodes: Set[String],
      adj: Map[String, Set[String]],
      weight: Map[(String, String), Long],
      support: Map[(String, String), Vector[String]],
      years: Map[(String, String), (Int, Int)]
  ):
    def nbrs(n: String): Set[String] = adj.getOrElse(n, Set.empty)

  def inWin(w: AnalysisWindow, y: Option[Int]): Boolean =
    y match
      case Some(v) => v >= w.startYear && v <= w.endYear
      case None => w.allYears

  def windowWorks(works: Vector[Work], w: AnalysisWindow): Vector[Work] =
    works.filter(x => inWin(w, x.year))

  def graph(works: Vector[Work], w: AnalysisWindow): Graph =
    val ws = windowWorks(works, w)
    val nodes = ws.flatMap(_.authors.map(rid)).toSet
    val pairWorks = for
      x <- ws if x.year.isDefined && !x.oversize
      ids = x.authors.map(rid).distinct.sorted
      i <- ids.indices
      j <- (i + 1) until ids.size
    yield ((ids(i), ids(j)), x)
    val byPair = pairWorks.groupBy(_._1).map((k, v) => k -> v.map(_._2).distinctBy(_.workId))
    val adj = byPair.keys.toVector
      .flatMap((a, b) => Vector(a -> b, b -> a))
      .groupBy(_._1)
      .map((k, v) => k -> v.map(_._2).toSet)
    Graph(
      nodes,
      adj,
      byPair.map((k, v) => k -> v.size.toLong),
      byPair.map((k, v) => k -> v.map(_.workId).sorted),
      byPair.map((k, v) => k -> (v.flatMap(_.year).min, v.flatMap(_.year).max))
    )

  final case class Metric(
      p: Long,
      c: Long,
      t: Long,
      m: Long,
      maxTeam: Vector[String],
      w: Long
  )

  def metrics(works: Vector[Work], w: AnalysisWindow): Map[String, Metric] =
    val g = graph(works, w)
    val ws = windowWorks(works, w)
    g.nodes.map { r =>
      val mine = ws.filter(_.authors.map(rid).contains(r))
      val co = mine.map(x => x -> (x.authors.map(rid).distinct.size - 1).toLong)
      val m = co.map(_._2).max
      val wDeg = g.nbrs(r).toVector.map(n => g.weight(if r < n then (r, n) else (n, r))).sum
      r -> Metric(
        mine.size.toLong,
        g.nbrs(r).size.toLong,
        co.map(_._2).sum,
        m,
        co.filter(_._2 == m).map(_._1.workId).sorted,
        wDeg
      )
    }.toMap

  def components(g: Graph): Map[String, String] =
    val parent = scala.collection.mutable.Map.empty[String, String]
    g.nodes.foreach(n => parent(n) = n)
    def find(x: String): String = if parent(x) == x then x else find(parent(x))
    for (a, b) <- g.weight.keys do
      val (ra, rb) = (find(a), find(b))
      if ra != rb then parent(math.Ordering.String.max(ra, rb)) = math.Ordering.String.min(ra, rb)
    val roots = g.nodes.groupBy(find)
    roots.values.flatMap(ms => ms.map(_ -> ms.min)).toMap

  /** Synchronous LPA (the documented rule). Returns labels and whether it converged. */
  def lpa(g: Graph, maxIterations: Int): (Map[String, String], Boolean) =
    var labels = g.nodes.map(n => n -> n).toMap
    var i = 0
    var converged = false
    while !converged && i < maxIterations do
      i += 1
      val next = labels.map { (n, own) =>
        val votes =
          (own +: g.nbrs(n).toVector.map(labels)).groupBy(identity).map((l, v) => (l, v.size))
        val best = votes.values.max
        n -> votes.filter(_._2 == best).keys.min
      }
      converged = next == labels
      labels = next
    (labels, converged)

  def coreness(g: Graph): Map[String, Int] =
    val deg = scala.collection.mutable.Map.from(g.nodes.map(n => n -> g.nbrs(n).size))
    val alive = scala.collection.mutable.Set.from(g.nodes)
    val core = scala.collection.mutable.Map.empty[String, Int]
    var k = 0
    while alive.nonEmpty do
      val low = alive.filter(n => deg(n) <= k).toVector
      if low.isEmpty then k = alive.map(deg).min
      else
        low.foreach { n =>
          core(n) = k
          alive -= n
          g.nbrs(n).filter(alive.contains).foreach(m => deg(m) -= 1)
        }
    core.toMap

  final case class Bridge(degree: Long, cross: Long, connected: Long, pc: Double)

  def bridges(g: Graph, comm: Map[String, String]): Map[String, Bridge] =
    g.nodes.map { r =>
      val k = g.nbrs(r).size.toLong
      val krc = g.nbrs(r).toVector.groupBy(comm).map((c, v) => c -> v.size.toLong)
      val own = comm(r)
      val cross = krc.filter(_._1 != own).values.sum
      val connected = (krc.keySet + own).size.toLong
      val pc =
        if k == 0 then 0.0 else (k * k - krc.values.map(x => x * x).sum).toDouble / (k * k).toDouble
      r -> Bridge(k, cross, connected, pc)
    }.toMap

  def dist(g: Graph, from: String): Map[String, Int] =
    var seen = Map(from -> 0)
    var frontier = Vector(from)
    var d = 0
    while frontier.nonEmpty do
      d += 1
      val nxt = frontier.flatMap(g.nbrs).distinct.filterNot(seen.contains)
      seen = seen ++ nxt.map(_ -> d)
      frontier = nxt
    seen

  /** All shortest paths from s to t (node sequences, s first), by exhaustive DFS. */
  def allShortest(g: Graph, s: String, t: String): Vector[Vector[String]] =
    val dt = dist(g, t)
    dt.get(s) match
      case None => Vector.empty
      case Some(len) =>
        def go(cur: String, rem: Int): Vector[Vector[String]] =
          if rem == 0 then Vector(Vector(cur))
          else
            g.nbrs(cur)
              .toVector
              .filter(n => dt.get(n).contains(rem - 1))
              .flatMap(n => go(n, rem - 1).map(cur +: _))
        go(s, len)

  def lexMin(paths: Vector[Vector[String]]): Vector[String] =
    paths.minBy(_.mkString("\u0000"))

  enum PathExpect:
    case Found(nodes: Vector[String])
    case NotFoundWithinDepth
    case NoPath

  def path(g: Graph, s: String, t: String, maxDepth: Int): PathExpect =
    if !g.nodes.contains(s) || !g.nodes.contains(t) then PathExpect.NoPath
    else
      val all = allShortest(g, s, t)
      if all.isEmpty then PathExpect.NoPath
      else if all.head.size - 1 > maxDepth then PathExpect.NotFoundWithinDepth
      else PathExpect.Found(lexMin(all))

  // ------------------------------------------------------------------ comparison

  private def key(a: String, b: String): (String, String) = if a < b then (a, b) else (b, a)

  /** Compares PART A (and, when given, PART B) Spark outputs with the reference for `works` in
    * every window of `windows`. Returns every mismatch (empty = agreement).
    */
  def compare(
      works: Vector[Work],
      windows: Vector[AnalysisWindow],
      out: GraphOutputs,
      an: Option[AnalyticsOutputs],
      communityMaxIterations: Int,
      benchmark: Vector[BenchmarkPair] = Vector.empty,
      maxDepth: Int = 8,
      representativeLimit: Int = 3
  ): Vector[String] =
    val errs = Vector.newBuilder[String]
    def chk(cond: Boolean, msg: => String): Unit = if !cond then errs += msg
    windows.foreach { w =>
      val lbl = s"${w.startYear}-${w.endYear}"
      val g = graph(works, w)

      // edges
      val edges = inWindow(out.edges, w)
      val got = edges.map(r => (str(r, "researcherA"), str(r, "researcherB")) -> r).toMap
      chk(
        got.keySet == g.weight.keySet,
        s"$lbl edges: expected ${g.weight.size} pairs, got ${got.size}; " +
          s"missing=${(g.weight.keySet -- got.keySet).size} extra=${(got.keySet -- g.weight.keySet).size}"
      )
      chk(edges.size == got.size, s"$lbl edges: duplicate (researcherA, researcherB) rows")
      got.foreach { (k, r) =>
        chk(k._1 < k._2, s"$lbl edge $k: researcherA must be < researcherB")
        g.weight.get(k).foreach { wt =>
          chk(
            long(r, "jointPublicationCount") == wt,
            s"$lbl edge $k weight ${long(r, "jointPublicationCount")} != $wt"
          )
          val (fy, ly) = g.years(k)
          chk(long(r, "firstCollaborationYear") == fy, s"$lbl edge $k first year")
          chk(long(r, "lastCollaborationYear") == ly, s"$lbl edge $k last year")
          chk(
            strs(r, "representativeWorkIds") == g.support(k).take(representativeLimit),
            s"$lbl edge $k representativeWorkIds ${strs(r, "representativeWorkIds")}"
          )
        }
      }
      val support = inWindow(out.edgeSupport, w)
        .groupBy(r => (str(r, "researcherA"), str(r, "researcherB")))
        .map((k, v) => k -> v.map(str(_, "workId")).sorted)
      chk(
        support == g.support,
        s"$lbl edge support table differs from the distinct supporting works"
      )

      // metrics
      val em = metrics(works, w)
      val gm = inWindow(out.metrics, w).map(r => str(r, "researcherId") -> r).toMap
      chk(gm.keySet == em.keySet, s"$lbl metrics rows: expected ${em.size}, got ${gm.size}")
      val comp = components(g)
      val compSize = comp.values.groupBy(identity).map((k, v) => k -> v.size.toLong)
      gm.foreach { (id, r) =>
        em.get(id).foreach { e =>
          val obs = Metric(
            long(r, "publicationCount"),
            long(r, "distinctCoauthorCount"),
            long(r, "totalCoauthorOccurrences"),
            long(r, "maximumCoauthorsOnOnePaper"),
            strs(r, "maximumTeamWorkIds"),
            dbl(r, "weightedDegree").toLong
          )
          chk(obs == e, s"$lbl metrics $id: expected $e, got $obs")
          chk(
            math.abs(dbl(r, "averageCoauthorsPerPaper") - e.t.toDouble / e.p.toDouble) < 1e-12,
            s"$lbl metrics $id: A_r != T_r / P_r"
          )
          chk(str(r, "componentId") == comp(id), s"$lbl componentId of $id")
          chk(long(r, "componentSize") == compSize(comp(id)), s"$lbl componentSize of $id")
        }
      }
      val gc =
        inWindow(out.components, w).map(r => str(r, "researcherId") -> str(r, "componentId")).toMap
      chk(gc == comp, s"$lbl components table differs from the reference")

      an.foreach { a =>
        // communities
        val (lab, _) = lpa(g, communityMaxIterations)
        val labSize = lab.values.groupBy(identity).map((k, v) => k -> v.size.toLong)
        val cm = inWindow(a.communities, w).map(r => str(r, "researcherId") -> r).toMap
        chk(
          cm.map((k, r) => k -> str(r, "communityId")) == lab,
          s"$lbl communities differ from synchronous LPA (ties to the smallest label): " +
            s"${cm.count((k, r) => lab.get(k).forall(_ != str(r, "communityId")))} researchers differ"
        )
        cm.foreach { (k, r) =>
          lab
            .get(k)
            .foreach(l => chk(long(r, "communitySize") == labSize(l), s"$lbl communitySize of $k"))
        }
        // communities never cross components
        lab.foreach((n, l) =>
          chk(comp.get(n) == comp.get(l), s"$lbl community label of $n is outside its component")
        )
        // cross-community edges
        val expCross = g.weight.keySet.filter((x, y) => lab(x) != lab(y))
        val gotCross =
          inWindow(a.crossEdges, w).map(r => (str(r, "researcherA"), str(r, "researcherB"))).toSet
        chk(gotCross == expCross, s"$lbl cross-community edges differ")
        // bridges
        val eb = bridges(g, lab)
        val gb = inWindow(a.bridges, w).map(r => str(r, "researcherId") -> r).toMap
        chk(gb.keySet == eb.keySet, s"$lbl bridge rows")
        gb.foreach { (k, r) =>
          eb.get(k).foreach { e =>
            chk(long(r, "degree") == e.degree, s"$lbl bridge degree of $k")
            chk(long(r, "crossCommunityDegree") == e.cross, s"$lbl crossCommunityDegree of $k")
            chk(long(r, "communitiesConnected") == e.connected, s"$lbl communitiesConnected of $k")
            chk(
              math.abs(dbl(r, "participationCoefficient") - e.pc) < 1e-12,
              s"$lbl participationCoefficient of $k: ${dbl(r, "participationCoefficient")} != ${e.pc}"
            )
          }
        }
        // coreness
        val ec = coreness(g)
        val gk = inWindow(a.coreness, w).map(r => str(r, "researcherId") -> r).toMap
        chk(
          gk.map((k, r) => k -> long(r, "coreness").toInt) == ec,
          s"$lbl coreness differs from peeling: ${gk
              .count((k, r) => ec.get(k).forall(_ != long(r, "coreness").toInt))} differ"
        )
        gk.foreach((k, r) =>
          chk(long(r, "degree") == g.nbrs(k).size.toLong, s"$lbl coreness degree of $k")
        )
        // metrics carry communityId and coreness
        inWindow(a.metrics, w).foreach { r =>
          val id = str(r, "researcherId")
          chk(Option(str(r, "communityId")) == lab.get(id), s"$lbl metrics.communityId of $id")
          chk(
            Option(r.getAs[Any]("coreness")).map(_.asInstanceOf[Number].intValue) == ec.get(id),
            s"$lbl metrics.coreness of $id"
          )
        }
        // paths
        val paths = inWindow(a.paths, w)
        benchmark.foreach { b =>
          val rows = paths.filter(r =>
            str(r, "sourceResearcherId") == b.sourceResearcherId && str(
              r,
              "targetResearcherId"
            ) == b.targetResearcherId
          )
          chk(rows.size == 1, s"$lbl path ${b}: expected one row, got ${rows.size}")
          rows.headOption.foreach { r =>
            val st = str(r, "status")
            path(g, b.sourceResearcherId, b.targetResearcherId, maxDepth) match
              case PathExpect.NoPath =>
                chk(st == "NO_PATH", s"$lbl path $b: expected NO_PATH, got $st")
              case PathExpect.NotFoundWithinDepth =>
                chk(
                  st == "NOT_FOUND_WITHIN_DEPTH",
                  s"$lbl path $b: expected NOT_FOUND_WITHIN_DEPTH, got $st"
                )
              case PathExpect.Found(nodes) =>
                chk(st == "FOUND", s"$lbl path $b: expected FOUND, got $st")
                if st == "FOUND" then
                  chk(long(r, "length") == nodes.size - 1, s"$lbl path $b length")
                  val hops = r.getSeq[Row](r.fieldIndex("hops")).toVector
                  val seq = hops.headOption.map(h => str(h, "fromResearcherId")).toVector ++
                    hops.map(h => str(h, "toResearcherId"))
                  chk(seq == nodes, s"$lbl path $b: nodes $seq != lexicographic minimum $nodes")
                  hops.foreach { h =>
                    val k = key(str(h, "fromResearcherId"), str(h, "toResearcherId"))
                    chk(
                      g.weight.get(k).contains(long(h, "jointPublicationCount")),
                      s"$lbl hop $k weight"
                    )
                    chk(
                      strs(h, "supportingWorkIds") == g.support.getOrElse(k, Vector.empty),
                      s"$lbl hop $k support"
                    )
                    chk(
                      got.get(k).map(str(_, "edgeId")).contains(str(h, "edgeId")),
                      s"$lbl hop $k edgeId"
                    )
                  }
          }
        }
      }
    }
    errs.result()
