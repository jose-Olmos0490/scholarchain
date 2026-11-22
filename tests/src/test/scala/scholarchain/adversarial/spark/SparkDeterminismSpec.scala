package scholarchain.adversarial.spark

import org.apache.spark.sql.DataFrame
import scholarchain.adversarial.spark.SparkAdv.*
import scholarchain.core.model.MatchOutcome
import scholarchain.spark.graph.{AnalyticsOutputs, GraphOutputs, WindowSummaries}

/** Case 12: two identical runs give identical output, and so does a third run over the same
  * records in reversed input order with a different shuffle partition count. Also: a seeded
  * random graph (with year-less and oversize works) agrees with the reference definitions.
  */
class SparkDeterminismSpec extends SparkSuite:

  /** A seeded random collaboration graph over `people` DBLP keys. */
  private def randomWorks(seed: Long, people: Int, count: Int): Vector[Work] =
    val rnd = new scala.util.Random(seed)
    (0 until count).toVector.map { i =>
      val size = Vector(2, 2, 2, 3, 3, 4)(rnd.nextInt(6))
      val authors = rnd.shuffle((0 until people).toVector).take(size).map(p => f"Rnd $p%02d")
      val year = if rnd.nextInt(20) == 0 then None else Some(2008 + rnd.nextInt(15))
      Work(f"RND-$i%03d", year, authors, oversize = i == 7)
    }

  private val random = randomWorks(20261121L, 45, 70)

  // Identity extras on keys disjoint from the random graph: one clean ORCID merge, one chain.
  private val orcid = orcidFrom("000000031234567")
  private val idA = Work("DET-A", Some(2012), Vector("Det Ann", "Det Bob"))
  private val idB = Work("DET-B", Some(2018), Vector("Det Ann", "Rnd 01"))
  private val chain = Work("DET-C", Some(2014), Vector("Det X", "Det Y"))
  private val oaAnn = OaMention(7001, 1, "Ann", Some("A7001"), Some(orcid))
  private val oaChain = OaMention(7002, 1, "X or Y", Some("A7002"), None)
  private val detTables = tables(
    random ++ Vector(idA, idB, chain),
    Vector(oaAnn, oaChain),
    Vector(
      decision(idA.mention(1), oaAnn.mentionId, MatchOutcome.ACCEPTED),
      decision(chain.mention(1), oaChain.mentionId, MatchOutcome.ACCEPTED),
      decision(chain.mention(2), oaChain.mentionId, MatchOutcome.ACCEPTED),
      decision(idB.mention(2), oaChain.mentionId, MatchOutcome.UNRESOLVED)
    )
  )

  private def tablesOf(out: GraphOutputs, an: AnalyticsOutputs): Vector[(String, DataFrame)] =
    Vector(
      "researchers" -> out.researchers,
      "works" -> out.works,
      "authored" -> out.authored,
      "edges" -> out.edges,
      "edgeSupport" -> out.edgeSupport,
      "wtInvariant" -> out.wtInvariant,
      "components" -> out.components,
      "rejects" -> out.rejects,
      "conflicts" -> out.identity.conflicts,
      "mentionIdentity" -> out.identity.mentionIdentity,
      "selfPairs" -> out.selfPairs,
      "metrics" -> an.metrics,
      "communities" -> an.communities,
      "crossEdges" -> an.crossEdges,
      "bridges" -> an.bridges,
      "coreness" -> an.coreness,
      "paths" -> an.paths,
      "semanticFacts" -> an.semanticFacts
    ) ++ an.windowChanges.toVector.map("windowChanges" -> _)

  final private case class RunSnap(
      tables: Vector[(String, Vector[String])],
      queries: Vector[scholarchain.spark.graph.Paths.Query],
      distances: Vector[scholarchain.spark.graph.Paths.DistanceEstimate],
      summaries: Vector[scholarchain.core.model.WindowGraphSummary]
  )

  private def snapshot(t: Tables): RunSnap =
    val out = build(t)
    val an = analytics(out)
    RunSnap(
      tablesOf(out, an).map((n, df) => n -> canonical(df)),
      an.queries,
      an.distances,
      WindowSummaries.compute(out, params(), 60, Some(WindowSummaries.PartB(an, 40, 200)))
    )

  private lazy val first = snapshot(detTables)
  private lazy val second = snapshot(detTables)
  private lazy val third =
    val before = spark.conf.get("spark.sql.shuffle.partitions")
    spark.conf.set("spark.sql.shuffle.partitions", "7")
    try snapshot(detTables.reversed)
    finally spark.conf.set("spark.sql.shuffle.partitions", before)

  private def diff(a: RunSnap, b: RunSnap): Vector[String] =
    val tb = b.tables.toMap
    a.tables.flatMap { (n, rows) =>
      val other = tb.getOrElse(n, Vector.empty)
      if rows == other then None
      else
        Some(
          s"$n: ${rows.size} vs ${other.size} rows; first difference: " +
            rows.diff(other).headOption.getOrElse("-").take(300)
        )
    } ++
      (if a.queries != b.queries then Vector("path queries differ") else Vector.empty) ++
      (if a.distances != b.distances then Vector("distance estimates differ") else Vector.empty) ++
      (if a.summaries != b.summaries then Vector("window summaries differ") else Vector.empty)

  test("case 12: the fixture is non-trivial (edges, a merge, a conflict, sampled paths)") {
    val t = first.tables.toMap
    assert(t("edges").size > 100)
    assert(t("conflicts").size == 1)
    assert(t("researchers").exists(_.contains(orcidRid(orcid))))
    assert(first.queries.nonEmpty && t("paths").size == first.queries.size)
    assert(t("semanticFacts").nonEmpty && t("communities").nonEmpty)
  }

  test("case 12: two identical runs produce identical output (every table, row by row)") {
    val d = diff(first, second)
    assert(d.isEmpty, d.mkString("\n"))
  }

  test("case 12: reversed input order and 7 shuffle partitions produce identical output") {
    val d = diff(first, third)
    assert(d.isEmpty, d.mkString("\n"))
  }

  test("case 12 (oracle): a seeded random graph agrees with the reference in every window") {
    val out = build(tables(random))
    val bench = Vector(
      scholarchain.core.config.BenchmarkPair(rid("Rnd 00"), rid("Rnd 44")),
      scholarchain.core.config.BenchmarkPair(rid("Rnd 13"), rid("Rnd 31")),
      scholarchain.core.config.BenchmarkPair(rid("Rnd 07"), rid("Rnd 22"))
    )
    val an = analytics(out, analyticsParams(benchmark = bench))
    val errs = Oracle.compare(random, ThreeWindows, out, Some(an), 40, bench)
    assert(errs.isEmpty, errs.mkString("\n"))
  }
