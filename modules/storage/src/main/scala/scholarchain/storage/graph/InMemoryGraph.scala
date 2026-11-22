package scholarchain.storage.graph

import java.io.StringReader
import scala.collection.mutable
import scholarchain.storage.common.*
import scholarchain.storage.objects.*

/** Local fake of a Neptune cluster: a property graph that applies bulk-load CSV with the loader's
  * documented merge semantics (repeated node ID: labels are added and only missing properties are
  * set; repeated relationship ID: kept as is). Vertices are applied before relationships.
  */
final class InMemoryGraph:
  final case class Node(id: String, labels: Set[String], props: Map[String, ujson.Value])
  final case class Rel(
      id: String,
      start: String,
      end: String,
      relType: String,
      props: Map[String, ujson.Value]
  )

  private val nodes = mutable.LinkedHashMap.empty[String, Node]
  private val rels = mutable.LinkedHashMap.empty[String, Rel]

  def node(id: String): Option[Node] = synchronized(nodes.get(id))
  def allRels: Vector[Rel] = synchronized(rels.values.toVector)

  /** Deterministic, comparable rendering of the whole state. */
  def state: Vector[String] = synchronized {
    nodes.values.toVector
      .sortBy(_.id)
      .map(n => s"N ${n.id} ${n.labels.toVector.sorted.mkString(";")} ${render(n.props)}") ++
      rels.values.toVector
        .sortBy(_.id)
        .map(r => s"R ${r.id} ${r.start} ${r.end} ${r.relType} ${render(r.props)}")
  }

  private def render(p: Map[String, ujson.Value]): String =
    p.toVector.sortBy(_._1).map((k, v) => s"$k=${v.render()}").mkString(",")

  private def convert(tpe: String, raw: String): ujson.Value = tpe match
    case "Int" | "Long" | "Short" | "Byte" => ujson.Num(raw.toLong.toDouble)
    case "Double" | "Float" => ujson.Num(raw.toDouble)
    case "Bool" | "Boolean" => ujson.Bool(raw == "true")
    case _ => ujson.Str(raw)

  /** Applies a set of CSV files (name, text). Fails, like a `failOnError` load, on a relationship
    * whose endpoint is missing.
    */
  def load(files: Vector[(String, String)]): Result[Unit] = synchronized {
    case class Parsed(name: String, header: Vector[String], rows: Vector[Vector[String]])
    val parsed = files.map { (name, text) =>
      val it = Csv.parse(new StringReader(text))
      val header = if it.hasNext then it.next() else Vector.empty
      Parsed(name, header, it.toVector)
    }
    val (nodeFiles, relFiles) = parsed.partition(_.header.contains(NeptuneSchema.Label))

    def props(header: Vector[String], row: Vector[String]): Map[String, ujson.Value] =
      header
        .zip(row)
        .collect {
          case (h, v) if v.nonEmpty && !h.startsWith(":") =>
            val i = h.lastIndexOf(':')
            h.take(i) -> convert(h.drop(i + 1), v)
        }
        .toMap

    nodeFiles.foreach { f =>
      val idI = f.header.indexOf(NeptuneSchema.Id)
      val lblI = f.header.indexOf(NeptuneSchema.Label)
      f.rows.foreach { row =>
        val p = props(f.header, row)
        val labels = row(lblI).split(";").filter(_.nonEmpty).toSet
        nodes.get(row(idI)) match
          case None => nodes(row(idI)) = Node(row(idI), labels, p)
          case Some(old) =>
            nodes(row(idI)) = Node(old.id, old.labels ++ labels, p ++ old.props)
      }
    }
    var failure: Option[StorageError] = None
    relFiles.foreach { f =>
      val idI = f.header.indexOf(NeptuneSchema.Id)
      val sI = f.header.indexOf(NeptuneSchema.StartId)
      val eI = f.header.indexOf(NeptuneSchema.EndId)
      val tI = f.header.indexOf(NeptuneSchema.Type)
      f.rows.foreach { row =>
        if failure.isEmpty then
          if !nodes.contains(row(sI)) || !nodes.contains(row(eI)) then
            failure = Some(
              StorageError.Permanent(
                "graph load",
                s"${f.name}: relationship ${row(idI)} has a missing endpoint"
              )
            )
          else
            val p = props(f.header, row)
            rels.get(row(idI)) match
              case None => rels(row(idI)) = Rel(row(idI), row(sI), row(eI), row(tI), p)
              case Some(old) => rels(row(idI)) = old.copy(props = p ++ old.props)
      }
    }
    failure.toLeft(())
  }

  // ----- read helpers used by the fake reader -----

  def relsOf(nodeId: String): Vector[Rel] = synchronized {
    rels.values
      .filter(r => r.relType == "COAUTHORED_WITH" && (r.start == nodeId || r.end == nodeId))
      .toVector
  }

/** Fake [[GraphLoader]]: reads the CSV objects under the request prefix from an [[ObjectStore]]
  * and applies them to an [[InMemoryGraph]].
  */
final class FakeGraphLoader(objects: ObjectStore, graph: InMemoryGraph) extends GraphLoader:
  private val jobs = mutable.LinkedHashMap.empty[String, GraphLoadState]

  def startLoad(request: GraphLoadRequest): Result[GraphLoadJob] =
    for
      listed <- objects.list(request.csvPrefix.area, request.csvPrefix.key)
      texts <- listed.foldLeft[Result[Vector[(String, String)]]](Right(Vector.empty)) { (acc, m) =>
        acc.flatMap { done =>
          objects.getBytes(m.ref).map { b =>
            done :+ (m.ref.key, new String(b.getOrElse(Array.emptyByteArray), "UTF-8"))
          }
        }
      }
    yield synchronized {
      val id = s"fake-load-${jobs.size + 1}"
      val state = graph.load(texts) match
        case Right(_) => GraphLoadState.Completed
        case Left(e) => GraphLoadState.Failed(e.message)
      jobs(id) = state
      GraphLoadJob(id, request.runId)
    }

  def status(job: GraphLoadJob): Result[GraphLoadState] =
    synchronized(jobs.get(job.jobId).toRight(StorageError.NotFound(job.jobId)))

/** Fake [[GraphReader]] with the same bounds and the same results the Cypher templates define. */
final class FakeGraphReader(graph: InMemoryGraph, limits: GraphReaderLimits) extends GraphReader:
  private val templates = new CypherTemplates(limits)

  private def str(p: Map[String, ujson.Value], k: String): Option[String] =
    p.get(k).collect { case ujson.Str(s) => s }
  private def lng(p: Map[String, ujson.Value], k: String): Option[Long] =
    p.get(k).collect { case ujson.Num(n) => n.toLong }
  private def dbl(p: Map[String, ujson.Value], k: String): Option[Double] =
    p.get(k).collect { case ujson.Num(n) => n }

  def getResearcher(researcherId: String): Result[Option[ResearcherView]] =
    Right(graph.node(researcherId).filter(_.labels.contains("Researcher")).map { n =>
      val p = n.props
      val metrics = str(p, "window").map { w =>
        ResearcherNodeMetrics(
          w,
          lng(p, "P_r").getOrElse(0L),
          lng(p, "C_r").getOrElse(0L),
          lng(p, "T_r").getOrElse(0L),
          dbl(p, "A_r").getOrElse(0.0),
          lng(p, "M_r").getOrElse(0L),
          dbl(p, "W_r").getOrElse(0.0),
          str(p, "componentId").getOrElse(""),
          str(p, "communityId"),
          dbl(p, "bridgeScore"),
          lng(p, "coreness").map(_.toInt),
          str(p, "metricVersion").getOrElse("")
        )
      }
      ResearcherView(
        n.id,
        str(p, "displayName").getOrElse(""),
        str(p, "orcid"),
        str(p, "runId").getOrElse(""),
        metrics
      )
    })

  private def edgeView(r: graph.Rel): CoauthorEdgeView =
    val p = r.props
    CoauthorEdgeView(
      r.id,
      r.start,
      r.end,
      lng(p, "jointPublicationCount").getOrElse(0L),
      lng(p, "firstCollaborationYear").getOrElse(0L).toInt,
      lng(p, "lastCollaborationYear").getOrElse(0L).toInt,
      lng(p, "windowStartYear").getOrElse(0L).toInt,
      lng(p, "windowEndYear").getOrElse(0L).toInt,
      str(p, "evidencePointer").getOrElse(""),
      str(p, "runId").getOrElse(""),
      str(p, "policyVersion").getOrElse("")
    )

  private def inWindow(r: graph.Rel, s: Int, e: Int): Boolean =
    lng(r.props, "windowStartYear").contains(s.toLong) && lng(r.props, "windowEndYear").contains(
      e.toLong
    )

  def directCoauthors(
      researcherId: String,
      windowStartYear: Int,
      windowEndYear: Int,
      limit: Int
  ): Result[Vector[CoauthorView]] =
    templates.directCoauthors(researcherId, windowStartYear, windowEndYear, limit).map { _ =>
      graph
        .relsOf(researcherId)
        .filter(inWindow(_, windowStartYear, windowEndYear))
        .map(r => CoauthorView(if r.start == researcherId then r.end else r.start, edgeView(r)))
        .sortBy(c => (-c.edge.jointPublicationCount, c.coauthorId))
        .take(limit)
    }

  def coauthorEdge(
      researcherA: String,
      researcherB: String,
      windowStartYear: Int,
      windowEndYear: Int
  ): Result[Option[CoauthorEdgeView]] =
    val found = graph
      .relsOf(researcherA)
      .filter(r =>
        (r.start == researcherB || r.end == researcherB) && inWindow(
          r,
          windowStartYear,
          windowEndYear
        )
      )
      .sortBy(r => (str(r.props, "policyVersion").getOrElse(""), r.id))
    found match
      case Vector() => Right(None)
      case Vector(one) => Right(Some(edgeView(one)))
      case _ =>
        Left(
          StorageError.Permanent(
            "coauthorEdge",
            s"more than one stored edge for $researcherA / $researcherB in the window"
          )
        )

  def shortestPath(
      source: String,
      target: String,
      windowStartYear: Int,
      windowEndYear: Int,
      maxDepth: Int
  ): Result[Option[PathView]] =
    templates.shortestPath(source, target, windowStartYear, windowEndYear, maxDepth).map { _ =>
      def neighbours(n: String): Vector[(String, graph.Rel)] =
        graph.relsOf(n).filter(inWindow(_, windowStartYear, windowEndYear)).map { r =>
          (if r.start == n then r.end else r.start, r)
        }
      def distances(from: String): Map[String, Int] =
        var dist = Map(from -> 0)
        var frontier = Vector(from)
        var d = 0
        while frontier.nonEmpty && d < maxDepth do
          d += 1
          val next = frontier.flatMap(neighbours(_).map(_._1)).distinct.filterNot(dist.contains)
          next.foreach(n => dist += n -> d)
          frontier = next
        dist
      val fromSource = distances(source)
      fromSource.get(target) match
        case None => None
        case Some(total) if source == target => Some(PathView(Vector(source), Vector.empty))
        case Some(total) =>
          val fromTarget = distances(target)
          var path = Vector(source)
          var edges = Vector.empty[String]
          var cur = source
          var d = 0
          while cur != target do
            val options = neighbours(cur)
              .filter((n, _) =>
                fromSource.get(n).contains(d + 1) && fromTarget.get(n).contains(total - d - 1)
              )
              .sortBy((n, r) => (n, r.id))
            val (n, r) = options.head
            path :+= n
            edges :+= r.id
            cur = n
            d += 1
          Some(PathView(path, edges))
    }
