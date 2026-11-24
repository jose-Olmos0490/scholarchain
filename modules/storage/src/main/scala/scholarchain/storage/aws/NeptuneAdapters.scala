package scholarchain.storage.aws

import scala.jdk.CollectionConverters.*
import scholarchain.core.config.LoaderParallelism
import scholarchain.core.model.CollaborationMetric
import scholarchain.storage.common.*
import scholarchain.storage.graph.*
import scholarchain.storage.objects.ObjectLayout
import software.amazon.awssdk.core.exception.{
  ApiCallAttemptTimeoutException,
  ApiCallTimeoutException
}
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model.*

/** The exact parameters of one `StartLoaderJob` call, as plain values. Built without a client, so a
  * dry run can print what a live run would send.
  */
final case class BulkLoadPlan(
    source: String,
    format: String,
    roleArn: String,
    region: String,
    mode: String,
    failOnError: Boolean,
    parallelism: String,
    userProvidedEdgeIds: Boolean,
    queueRequest: Boolean
):
  def lines: Vector[String] = Vector(
    s"source=$source",
    s"format=$format",
    s"iamRoleArn=$roleArn",
    s"s3BucketRegion=$region",
    s"mode=$mode",
    s"failOnError=$failOnError",
    s"parallelism=$parallelism",
    s"userProvidedEdgeIds=$userProvidedEdgeIds",
    s"queueRequest=$queueRequest"
  )

object NeptuneBulkLoader:
  def plan(
      layout: ObjectLayout,
      roleArn: String,
      region: String,
      parallelism: LoaderParallelism,
      request: GraphLoadRequest
  ): BulkLoadPlan = BulkLoadPlan(
    source = layout.s3Uri(request.csvPrefix),
    format = "opencypher",
    roleArn = roleArn,
    region = region,
    mode = "NEW",
    failOnError = true,
    parallelism = parallelism.wire,
    userProvidedEdgeIds = true,
    queueRequest = true
  )

  /** At most this many error log entries are fetched for a failed load. */
  val ErrorsPerPage = 10

  /** A one-line summary of the per-feed failures and error log entries in a loader status payload
    * requested with `details=true` and `errors=true`. Empty when the payload names none.
    */
  def describeFailure(payload: ujson.Value): String =
    def str(o: ujson.Value, k: String): String = o match
      case x: ujson.Obj =>
        x.value.get(k).map { case ujson.Str(v) => v; case other => other.render() }.getOrElse("")
      case _ => ""
    def arr(o: ujson.Value, k: String): Vector[ujson.Value] = o match
      case x: ujson.Obj =>
        x.value.get(k).collect { case ujson.Arr(v) => v.toVector }.getOrElse(Vector.empty)
      case _ => Vector.empty
    val feeds =
      arr(payload, "failedFeeds").map(f => s"${str(f, "fullUri")} ${str(f, "status")}".trim)
    val errors = payload match
      case x: ujson.Obj =>
        x.value.get("errors").map(e => arr(e, "errorLogs")).getOrElse(Vector.empty).map { e =>
          s"${str(e, "fileName")} record ${str(e, "recordNum")}: ${str(e, "errorCode")} ${str(e, "errorMessage")}".trim
        }
      case _ => Vector.empty
    Vector(
      if feeds.nonEmpty then Some(s"failed feeds: ${feeds.take(ErrorsPerPage).mkString("; ")}")
      else None,
      if errors.nonEmpty then Some(s"errors: ${errors.take(ErrorsPerPage).mkString("; ")}")
      else None
    ).flatten.mkString(" | ")

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

  def plan(request: GraphLoadRequest): BulkLoadPlan =
    NeptuneBulkLoader.plan(layout, roleArn, region, parallelism, request)

  def startLoad(request: GraphLoadRequest): Result[GraphLoadJob] =
    val p = plan(request)
    retrier.run("Neptune StartLoaderJob") {
      val r = client.startLoaderJob(
        StartLoaderJobRequest
          .builder()
          .source(p.source)
          .format(p.format)
          .iamRoleArn(p.roleArn)
          .s3BucketRegion(p.region)
          .mode(p.mode)
          .failOnError(p.failOnError)
          .parallelism(p.parallelism)
          .userProvidedEdgeIds(p.userProvidedEdgeIds)
          .queueRequest(p.queueRequest)
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
        case Some(other) => GraphLoadState.Failed(failureReason(job, other))
        case None => GraphLoadState.Failed("no overallStatus in the loader response")
    }

  /** The status plus, when the service names them, the failed feeds and first error log entries. A
    * failure of this extra call never hides the status: the bare status is returned.
    */
  private def failureReason(job: GraphLoadJob, status: String): String =
    val detail = retrier
      .run("Neptune GetLoaderJobStatus errors") {
        val r = client.getLoaderJobStatus(
          GetLoaderJobStatusRequest
            .builder()
            .loadId(job.jobId)
            .details(true)
            .errors(true)
            .page(1)
            .errorsPerPage(NeptuneBulkLoader.ErrorsPerPage)
            .build()
        )
        NeptuneBulkLoader.describeFailure(Docs.toJson(r.payload()))
      }
      .getOrElse("")
    if detail.isEmpty then status else s"$status ($detail)"

/** Runs [[GraphQuery]] values through the neptunedata `ExecuteOpenCypherQuery` API and maps the
  * rows. Caller values are sent only in the `parameters` JSON document. A query that runs out of
  * time (the service's `TimeLimitExceededException`, or a client-side call timeout) becomes
  * [[StorageError.Timeout]] at once and is not retried inside the adapter: repeating a query that
  * already ran out of time is the caller's decision. For queries without their own limit the
  * reported `timeoutMillis` is the configured ceiling `limits.maxTimeoutMillis`.
  */
final private[aws] class NeptuneQueryRunner(
    client: NeptunedataClient,
    limits: GraphReaderLimits,
    retrier: Retrier
):
  def run(q: GraphQuery): Result[Vector[ujson.Obj]] =
    val operation = s"Neptune openCypher ${q.name}"
    retrier.run(operation) {
      val r =
        try
          client.executeOpenCypherQuery(
            ExecuteOpenCypherQueryRequest
              .builder()
              .openCypherQuery(q.text)
              .parameters(ujson.write(ujson.Obj.from(q.parameters)))
              .build()
          )
        catch
          case _: TimeLimitExceededException | _: ClientTimeoutException |
              _: ApiCallTimeoutException | _: ApiCallAttemptTimeoutException =>
            throw new StorageException(
              StorageError.Timeout(operation, q.timeoutMillis.getOrElse(limits.maxTimeoutMillis))
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

/** Row mapping shared by the Neptune read adapters. */
private[aws] object NeptuneRows:
  def s(o: ujson.Obj, k: String): String =
    o.value.get(k).collect { case ujson.Str(x) => x }.getOrElse("")
  def os(o: ujson.Obj, k: String): Option[String] =
    o.value.get(k).collect { case ujson.Str(x) => x }
  def l(o: ujson.Obj, k: String): Long =
    o.value.get(k).collect { case ujson.Num(x) => x.toLong }.getOrElse(0L)
  def d(o: ujson.Obj, k: String): Double =
    o.value.get(k).collect { case ujson.Num(x) => x }.getOrElse(0.0)
  def od(o: ujson.Obj, k: String): Option[Double] =
    o.value.get(k).collect { case ujson.Num(x) => x }

  def edge(o: ujson.Obj): CoauthorEdgeView = CoauthorEdgeView(
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

  def nodeMetrics(o: ujson.Obj): Option[ResearcherNodeMetrics] = os(o, "window").map { w =>
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

/** Executes the named templates of [[CypherTemplates]] through [[NeptuneQueryRunner]]. Runtime
  * access is read-only by IAM policy (Terraform), and no template contains a write clause.
  */
final class NeptuneGraphReader(
    client: NeptunedataClient,
    limits: GraphReaderLimits,
    retrier: Retrier
) extends GraphReader:
  import NeptuneRows.*
  private val templates = new CypherTemplates(limits)
  private val runner = new NeptuneQueryRunner(client, limits, retrier)
  private def run(q: GraphQuery): Result[Vector[ujson.Obj]] = runner.run(q)

  def getResearcher(researcherId: String): Result[Option[ResearcherView]] =
    run(templates.getResearcher(researcherId)).map(_.headOption.map { o =>
      ResearcherView(
        s(o, "researcherId"),
        s(o, "displayName"),
        os(o, "orcid"),
        s(o, "runId"),
        nodeMetrics(o)
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

  def directCoauthorsPage(
      researcherId: String,
      windowStartYear: Int,
      windowEndYear: Int,
      page: PageRequest
  ): Result[Page[CoauthorView]] =
    templates
      .directCoauthorsPage(researcherId, windowStartYear, windowEndYear, page)
      .flatMap(run)
      .map { rows =>
        PageCursor.page(
          PageCursor.scope(researcherId, windowStartYear, windowEndYear),
          page.limit,
          rows.map(o => CoauthorView(s(o, "coauthorId"), edge(o)))
        )
      }

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

  def paths(
      source: String,
      target: String,
      windowStartYear: Int,
      windowEndYear: Int,
      q: PathQueryLimits
  ): Result[Vector[EdgePath]] =
    templates.paths(source, target, windowStartYear, windowEndYear, q).flatMap(run).map { rows =>
      rows
        .map { o =>
          EdgePath(
            o("researcherIds").arr.map(_.str).toVector,
            o("hops").arr.map(h => edge(h.obj)).toVector
          )
        }
        // the query already excludes repeated researchers; this keeps a path whose shape ever
        // differs from that out of the evidence
        .filter(p => p.researcherIds.distinct.size == p.researcherIds.size)
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

/** [[MetricReader]] over the metrics stored on Researcher nodes. */
final class NeptuneMetricReader(
    client: NeptunedataClient,
    limits: GraphReaderLimits,
    retrier: Retrier
) extends MetricReader:
  import NeptuneRows.*
  private val templates = new CypherTemplates(limits)
  private val runner = new NeptuneQueryRunner(client, limits, retrier)

  def loadedWindows: Result[Vector[(Int, Int)]] =
    runner
      .run(templates.loadedWindows())
      .map(_.flatMap(o => MetricRanking.parseWindow(s(o, "window"))))

  def topK(
      metric: CollaborationMetric,
      windowStartYear: Int,
      windowEndYear: Int,
      filter: ResearcherFilter,
      limit: Int
  ): Result[Vector[RankedResearcher]] =
    for
      q <- templates.topK(metric, windowStartYear, windowEndYear, filter, limit)
      rows <- runner.run(q)
      ranked = rows.flatMap { o =>
        nodeMetrics(o).map(m =>
          RankedResearcher(s(o, "researcherId"), s(o, "displayName"), d(o, "metricValue"), m)
        )
      }
      out <-
        if ranked.nonEmpty then Right(ranked)
        else
          loadedWindows.flatMap { loaded =>
            if loaded.contains((windowStartYear, windowEndYear)) then Right(ranked)
            else Left(MetricRanking.windowNotLoaded(windowStartYear, windowEndYear, loaded))
          }
    yield out
