package scholarchain.storage

import scholarchain.storage.common.*
import scholarchain.storage.graph.*

/** Builds small in-memory graphs through the same CSV path as a real load. */
object TinyGraph:
  val Window = "2010-2013"
  val Evidence = "coauthor-edge-support-v1:"

  /** `researchers`: id, displayName, P_r, componentId, communityId, coreness (None = absent). */
  final case class R(
      id: String,
      publications: Long,
      component: String,
      community: Option[String] = None,
      coreness: Option[Int] = None,
      window: String = Window
  )

  /** A coauthor edge between two researchers with a joint publication count. */
  final case class E(a: String, b: String, count: Long, window: (Int, Int) = (2010, 2013))

  private def row(fields: String*): String = Csv.record(fields)

  def build(
      researchers: Vector[R],
      edges: Vector[E] = Vector.empty,
      works: Vector[(String, Vector[String], Vector[String])] =
        Vector.empty // work, authors, topics
  ): InMemoryGraph =
    val g = new InMemoryGraph
    val nodes =
      row(
        ":ID",
        ":LABEL",
        "displayName:String",
        "P_r:Long",
        "C_r:Long",
        "T_r:Long",
        "A_r:Double",
        "M_r:Long",
        "W_r:Double",
        "componentId:String",
        "communityId:String",
        "coreness:Int",
        "window:String",
        "metricVersion:String",
        "runId:String"
      ) + researchers.map { r =>
        row(
          r.id,
          "Researcher",
          s"Name ${r.id}",
          r.publications.toString,
          "0",
          "0",
          "0.0",
          "0",
          r.publications.toDouble.toString,
          r.component,
          r.community.getOrElse(""),
          r.coreness.fold("")(_.toString),
          r.window,
          "metrics-v1",
          "run:test"
        )
      }.mkString
    val workNodes =
      row(":ID", ":LABEL") + works.map((w, _, _) => row(w, "Work")).mkString
    val topicIds = works.flatMap(_._3).distinct
    val topicNodes = row(":ID", ":LABEL") + topicIds.map(t => row(t, "Topic")).mkString
    val coauthored =
      row(
        ":ID",
        ":START_ID",
        ":END_ID",
        ":TYPE",
        "jointPublicationCount:Long",
        "firstCollaborationYear:Int",
        "lastCollaborationYear:Int",
        "windowStartYear:Int",
        "windowEndYear:Int",
        "evidencePointer:String",
        "runId:String",
        "policyVersion:String"
      ) + edges.map { e =>
        val id = s"edge:${e.a}:${e.b}:${e.window._1}-${e.window._2}"
        row(
          id,
          e.a,
          e.b,
          "COAUTHORED_WITH",
          e.count.toString,
          "2010",
          "2012",
          e.window._1.toString,
          e.window._2.toString,
          s"$Evidence$id",
          "run:test",
          "p1"
        )
      }.mkString
    val authored =
      row(":ID", ":START_ID", ":END_ID", ":TYPE") + works.flatMap { (w, authors, _) =>
        authors.map(a => row(s"authored:$a:$w", a, w, "AUTHORED"))
      }.mkString
    val hasTopic =
      row(":ID", ":START_ID", ":END_ID", ":TYPE") + works.flatMap { (w, _, topics) =>
        topics.map(t => row(s"has_topic:$w:$t", w, t, "HAS_TOPIC"))
      }.mkString
    val files =
      Vector("nodes-r.csv" -> nodes, "nodes-w.csv" -> workNodes, "nodes-t.csv" -> topicNodes) ++
        Vector("rel-co.csv" -> coauthored, "rel-au.csv" -> authored, "rel-ht.csv" -> hasTopic)
    g.load(files) match
      case Right(_) => g
      case Left(e) => throw new IllegalStateException(e.message)

  def edgeId(a: String, b: String, window: (Int, Int) = (2010, 2013)): String =
    s"edge:$a:$b:${window._1}-${window._2}"
