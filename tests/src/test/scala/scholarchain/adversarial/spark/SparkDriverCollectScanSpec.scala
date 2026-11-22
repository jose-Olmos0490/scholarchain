package scholarchain.adversarial.spark

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scala.jdk.CollectionConverters.*

/** Case 13: a source scan of modules/spark/src/main proving that no node, edge or path table is
  * brought to the driver without a stated bound.
  *
  * Rule. Every driver-materializing Spark action (`.collect()`, `.collectAsList(`,
  * `.collectAsMap(`, `.toLocalIterator(`, `.take(`, `.takeAsList(`, `.takeOrdered(`,
  * `.takeSample(`, `.head()`/`.head(n)`, `.first()`, `.tail(n)`, `.countByKey(`,
  * `.countByValue(`, `.show(`) must carry a bound comment: a comment line containing "bound"
  * (case-insensitive) on the action line, inside its statement, or within [[CommentReach]] lines
  * above the start of its statement (the nearest `val`/`var`/`def` line above the action).
  * `.head`/`.tail` without parentheses and `.collect {` are Scala collection operations and do not
  * match. Scala-collection uses of an ambiguous name (`.take(` on a Vector) are listed in
  * [[ScalaCollectionUses]] with the exact trimmed source line, so a new `.take(` on a DataFrame is
  * not silently allowed.
  *
  * The scanner is proven able to fail on planted violation strings before it is trusted.
  */
class SparkDriverCollectScanSpec extends AnyFunSuite:

  val CommentReach = 6

  private val ActionPattern =
    ("""\.(collect\(\)|collectAsList\(|collectAsMap\(|toLocalIterator\(|take\(|takeAsList\(|""" +
      """takeOrdered\(|takeSample\(|head\(|first\(\)|tail\(|countByKey\(|countByValue\(|show\()""").r

  private val StatementStart = """^\s*(private\s+|override\s+|lazy\s+|final\s+)*(val|var|def)\s""".r

  /** Exact trimmed source lines that use an ambiguous action name on a Scala collection. */
  val ScalaCollectionUses: Set[String] = Set(
    "(disjoint ++ rest).take(count).map((i, j) => (members(i), members(j)))"
  )

  final case class Finding(file: String, line: Int, text: String)

  private def isComment(t: String): Boolean =
    t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") || t.startsWith("*/")

  /** Returns (all action sites, unbounded action sites) of one source file. */
  def scan(file: String, lines: Vector[String]): (Vector[Finding], Vector[Finding]) =
    val sites = Vector.newBuilder[Finding]
    val bad = Vector.newBuilder[Finding]
    lines.zipWithIndex.foreach { (raw, i) =>
      val t = raw.trim
      val code = raw.indexOf("//") match
        case -1 => raw
        case k => raw.substring(0, k)
      if !isComment(t) && ActionPattern.findFirstIn(code).isDefined then
        val f = Finding(file, i + 1, t)
        if !ScalaCollectionUses.contains(t) then
          sites += f
          val start = (i to math.max(0, i - 80) by -1)
            .find(j => StatementStart.findFirstIn(lines(j)).isDefined)
            .getOrElse(i)
          val from = math.max(0, start - CommentReach)
          val bounded = (from to i).exists { j =>
            val l = lines(j)
            val c = l.indexOf("//") match
              case -1 => if isComment(l.trim) then l else ""
              case k => l.substring(k)
            c.toLowerCase.contains("bound")
          }
          if !bounded then bad += f
    }
    (sites.result(), bad.result())

  private lazy val root: File =
    Iterator
      .iterate(new File(".").getAbsoluteFile)(_.getParentFile)
      .takeWhile(_ != null)
      .find(d => new File(d, "modules/spark/src/main/scala").isDirectory)
      .getOrElse(fail("cannot find modules/spark/src/main/scala from the working directory"))

  private lazy val sources: Vector[File] =
    val dir = new File(root, "modules/spark/src/main")
    Files
      .walk(dir.toPath)
      .iterator()
      .asScala
      .map(_.toFile)
      .filter(f => f.isFile && f.getName.endsWith(".scala"))
      .toVector
      .sortBy(_.getPath)

  private def read(f: File): Vector[String] =
    Files.readAllLines(f.toPath, StandardCharsets.UTF_8).asScala.toVector

  // ------------------------------------------------------------------ the scanner can fail

  test("case 13: the scanner flags planted unbounded collects on node/edge/path tables") {
    val planted = Vector(
      "object Planted:",
      "  def leak(out: GraphOutputs): Unit =",
      "    val all = out.edges.collect()",
      "    val it = out.researchers.toLocalIterator()",
      "    val firstPath = paths.first()",
      "    val some = out.components.take(1000)",
      "    val h = nodes.head()",
      "    val l = out.edgeSupport.collectAsList()",
      "    out.metrics.show(20)"
    )
    val (sites, bad) = scan("Planted.scala", planted)
    assert(sites.size == 7)
    assert(bad.map(_.line) == Vector(3, 4, 5, 6, 7, 8, 9))
  }

  test("case 13: a bound comment satisfies the rule; Scala collection calls are not actions") {
    val ok = Vector(
      "object Fine:",
      "  def f(df: DataFrame, xs: Vector[Int]): Unit =",
      "    // Bounded collect: one row per configured window.",
      "    val perWindow = df",
      "      .groupBy(\"w\")",
      "      .count()",
      "      .collect()",
      "    val a = xs.head",
      "    val b = xs.tail",
      "    val c = xs.collect { case x if x > 0 => x }",
      "    // the word collect() in a comment is not code"
    )
    val (sites, bad) = scan("Fine.scala", ok)
    assert(sites.map(_.line) == Vector(7))
    assert(bad.isEmpty)
    // The same collect with its comment removed is flagged.
    val (_, bad2) = scan("Fine.scala", ok.patch(2, Nil, 1))
    assert(bad2.map(_.line) == Vector(6))
  }

  test("case 13: a comment far above the statement does not count") {
    val far = Vector("// Bounded: windows.") ++ Vector.fill(10)("val x = 1") ++
      Vector("val rows = edges", "  .collect()")
    val (_, bad) = scan("Far.scala", far)
    assert(bad.map(_.line) == Vector(13))
  }

  // ------------------------------------------------------------------ the real tree

  test("case 13: every driver action in modules/spark/src/main carries a bound comment") {
    assert(sources.size >= 20, s"expected the Spark sources, found ${sources.size} files")
    val results = sources.map(f => scan(root.toPath.relativize(f.toPath).toString, read(f)))
    val sites = results.flatMap(_._1)
    val bad = results.flatMap(_._2)
    // Guard against a vacuous pass: the tree is known to contain bounded collects.
    assert(sites.size >= 15, s"only ${sites.size} action sites found; the scan is not reading code")
    assert(
      bad.isEmpty,
      "unbounded driver actions:\n" + bad.map(f => s"${f.file}:${f.line}: ${f.text}").mkString("\n")
    )
  }

  test("case 13: every allow-listed Scala-collection line still exists verbatim") {
    val all = sources.flatMap(read).map(_.trim).toSet
    ScalaCollectionUses.foreach(l => assert(all.contains(l), s"stale allow-list entry: $l"))
  }

  test("case 13: no node, edge or path table is collected by name, bounded comment or not") {
    val Direct =
      """\b(edges|edgeSupport|nodes|researchers|authored|components|paths|evidence|labels|visited|directed|coreness|communities|bridges|crossEdges|metrics|researcherPapers)\s*\.(collect\(|collectAsList\(|toLocalIterator\(|take\(|head\(|first\(\))""".r
    val hits = sources.flatMap { f =>
      read(f).zipWithIndex.collect {
        case (l, i) if !isComment(l.trim) && Direct.findFirstIn(l).isDefined =>
          s"${f.getName}:${i + 1}: ${l.trim}"
      }
    }
    assert(hits.isEmpty, hits.mkString("\n"))
    // The pattern itself can fail.
    assert(Direct.findFirstIn("val all = edges.collect()").isDefined)
  }
