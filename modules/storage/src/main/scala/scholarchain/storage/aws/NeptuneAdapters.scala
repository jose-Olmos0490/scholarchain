package scholarchain.storage.aws

import scala.jdk.CollectionConverters.*
import scholarchain.core.config.LoaderParallelism
import scholarchain.storage.common.*
import scholarchain.storage.graph.*
import scholarchain.storage.objects.ObjectLayout
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model.*

/** Starts and polls Neptune bulk-load jobs (`format=opencypher`) through the neptunedata API.
  *
  * Parameters follow the Neptune Loader reference
  * (https://docs.aws.amazon.com/neptune/latest/userguide/load-api-reference-load.html):
  * `mode=NEW` (the IDs in the files are stable, so reloading a source is a merge, and a dropped
  * graph can be reloaded), `userProvidedEdgeIds=true` (relationships carry their `:ID`),
  * `failOnError=true`, `queueRequest=true`, `parallelism` from configuration. The loader runs with
  * the IAM role `roleArn`, which only the live loader needs.
  *
  * Starting a job is not exercised by `sbt check`; it is covered by the tagged integration suite.
  */
final class NeptuneBulkLoader(
    client: NeptunedataClient,
    layout: ObjectLayout,
    roleArn: String,
    region: String,
    parallelism: LoaderParallelism,
    retrier: Retrier
) extends GraphLoader:

  def startLoad(request: GraphLoadRequest): Result[GraphLoadJob] =
    retrier.run("Neptune StartLoaderJob") {
      val r = client.startLoaderJob(
        StartLoaderJobRequest
          .builder()
          .source(layout.s3Uri(request.csvPrefix))
          .format("opencypher")
          .iamRoleArn(roleArn)
          .s3BucketRegion(region)
          .mode("NEW")
          .failOnError(true)
          .parallelism(parallelism.wire)
          .userProvidedEdgeIds(true)
          .queueRequest(true)
          .build()
      )
      val id = Option(r.payload())
        .flatMap(p => Option(p.get("loadId")))
        .getOrElse(
          throw new StorageException(
            StorageError.Permanent("Neptune StartLoaderJob", "response has no loadId")
          )
        )
      GraphLoadJob(id, request.runId)
    }

  def status(job: GraphLoadJob): Result[GraphLoadState] =
    retrier.run("Neptune GetLoaderJobStatus") {
      val r =
        client.getLoaderJobStatus(GetLoaderJobStatusRequest.builder().loadId(job.jobId).build())
      val payload = Docs.toJson(r.payload())
      val status = payload match
        case o: ujson.Obj =>
          o.value.get("overallStatus").flatMap(_.objOpt).flatMap(_.get("status")).collect {
            case ujson.Str(s) => s
          }
        case _ => None
      status match
        case Some("LOAD_COMPLETED") => GraphLoadState.Completed
        case Some("LOAD_NOT_STARTED") | Some("LOAD_IN_QUEUE") => GraphLoadState.Queued
        case Some("LOAD_IN_PROGRESS") => GraphLoadState.InProgress
        case Some(other) => GraphLoadState.Failed(other)
        case None => GraphLoadState.Failed("no overallStatus in the loader response")
    }

/** Executes the named templates of [[CypherTemplates]] with bound parameters through the
  * neptunedata `ExecuteOpenCypherQuery` API. Caller values are sent only in the `parameters`
  * JSON document. Runtime credentials are read-only by IAM policy (Terraform), and no template
  * contains a write clause.
  */
final class NeptuneGraphReader(
    client: NeptunedataClient,
    limits: GraphReaderLimits,
    retrier: Retrier
) extends GraphReader:
  private val templates = new CypherTemplates(limits)

  private def run(q: GraphQuery): Result[Vector[ujson.Obj]] =
    retrier.run(s"Neptune openCypher ${q.name}") {
      val r = client.executeOpenCypherQuery(
        ExecuteOpenCypherQueryRequest
          .builder()
          .openCypherQuery(q.text)
          .parameters(ujson.write(ujson.Obj.from(q.parameters)))
          .build()
      )
      Docs.toJson(r.results()) match
        case ujson.Arr(rows) => rows.collect { case o: ujson.Obj => o }.toVector
        case ujson.Null => Vector.empty
        case other =>
          throw new StorageException(
            StorageError.InvalidRecord(
              s"unexpected openCypher result shape: ${other.render().take(200)}"
            )
          )
    }

  private def s(o: ujson.Obj, k: String): String =
    o.value.get(k).collect { case ujson.Str(x) => x }.getOrElse("")
  private def os(o: ujson.Obj, k: String): Option[String] =
    o.value.get(k).collect { case ujson.Str(x) => x }
  private def l(o: ujson.Obj, k: String): Long =
    o.value.get(k).collect { case ujson.Num(x) => x.toLong }.getOrElse(0L)
  private def d(o: ujson.Obj, k: String): Double =
    o.value.get(k).collect { case ujson.Num(x) => x }.getOrElse(0.0)
  private def od(o: ujson.Obj, k: String): Option[Double] =
    o.value.get(k).collect { case ujson.Num(x) => x }

  private def edge(o: ujson.Obj): CoauthorEdgeView = CoauthorEdgeView(
    s(o, "edgeId"),
    s(o, "researcherA"),
    s(o, "researcherB"),
    l(o, "jointPublicationCount"),
    l(o, "firstCollaborationYear").toInt,
    l(o, "lastCollaborationYear").toInt,
    l(o, "windowStartYear").toInt,
    l(o, "windowEndYear").toInt,
    s(o, "evidencePointer"),
    s(o, "runId"),
    s(o, "policyVersion")
  )

  def getResearcher(researcherId: String): Result[Option[ResearcherView]] =
    run(templates.getResearcher(researcherId)).map(_.headOption.map { o =>
      val metrics = os(o, "window").map { w =>
        ResearcherNodeMetrics(
          w,
          l(o, "P_r"),
          l(o, "C_r"),
          l(o, "T_r"),
          d(o, "A_r"),
          l(o, "M_r"),
          d(o, "W_r"),
          s(o, "componentId"),
          os(o, "communityId"),
          od(o, "bridgeScore"),
          o.value.get("coreness").collect { case ujson.Num(x) => x.toInt },
          s(o, "metricVersion")
        )
      }
      ResearcherView(
        s(o, "researcherId"),
        s(o, "displayName"),
        os(o, "orcid"),
        s(o, "runId"),
        metrics
      )
    })

  def directCoauthors(
      researcherId: String,
      windowStartYear: Int,
      windowEndYear: Int,
      limit: Int
  ): Result[Vector[CoauthorView]] =
    templates
      .directCoauthors(researcherId, windowStartYear, windowEndYear, limit)
      .flatMap(run)
      .map(_.map(o => CoauthorView(s(o, "coauthorId"), edge(o))))

  def coauthorEdge(
      researcherA: String,
      researcherB: String,
      windowStartYear: Int,
      windowEndYear: Int
  ): Result[Option[CoauthorEdgeView]] =
    run(templates.coauthorEdge(researcherA, researcherB, windowStartYear, windowEndYear)).flatMap {
      case Vector() => Right(None)
      case Vector(one) => Right(Some(edge(one)))
      case _ =>
        Left(
          StorageError.Permanent(
            "coauthorEdge",
            s"more than one stored edge for $researcherA / $researcherB in the window"
          )
        )
    }

  def shortestPath(
      source: String,
      target: String,
      windowStartYear: Int,
      windowEndYear: Int,
      maxDepth: Int
  ): Result[Option[PathView]] =
    templates
      .shortestPath(source, target, windowStartYear, windowEndYear, maxDepth)
      .flatMap(run)
      .map { rows =>
        // Candidates are ordered by length; keep the shortest and break ties lexicographically, the
        // rule Spark applies. The tie-break is exact only when the shortest candidates fit within
        // `maxLimit` (the candidate limit sent to the query).
        val paths = rows.map { o =>
          PathView(
            o("researcherIds").arr.map(_.str).toVector,
            o("edgeIds").arr.map(_.str).toVector
          )
        }
        if paths.isEmpty then None
        else
          val shortest = paths.map(_.length).min
          Some(paths.filter(_.length == shortest).minBy(_.researcherIds.mkString("\u0000")))
      }
