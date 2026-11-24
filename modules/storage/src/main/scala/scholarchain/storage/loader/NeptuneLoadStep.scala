package scholarchain.storage.loader

import scholarchain.storage.aws.{BulkLoadPlan, NeptuneBulkLoader}
import scholarchain.storage.common.*
import scholarchain.storage.graph.*
import scholarchain.storage.objects.*

/** Everything the Neptune bulk-load step needs, resolved from configuration before any call.
  *
  * @param timeoutMillis
  *   how long the step waits for a terminal state after the load was accepted
  * @param pollMillis
  *   pause between two status calls
  */
final case class NeptuneLoadPlan(
    runId: String,
    request: GraphLoadRequest,
    bulk: BulkLoadPlan,
    timeoutMillis: Long,
    pollMillis: Long
)

final case class NeptuneLoadReport(loadId: String, polls: Int, elapsedMillis: Long)

/** Reads the step's configuration. Nothing here calls AWS. */
object NeptuneLoadConfig:
  /** Seconds to wait for the load to finish. Required: there is deliberately no default. */
  val TimeoutVar = "SCHOLARCHAIN_NEPTUNE_LOAD_TIMEOUT_SECONDS"

  /** Seconds between two status polls. Required. */
  val PollVar = "SCHOLARCHAIN_NEPTUNE_LOAD_POLL_SECONDS"

  /** Builds the plan from the parsed arguments and the environment. The command-line flags win
    * over the variables. Fails (with every problem named) when the role ARN, the timeout or the poll
    * interval is missing, or the configuration is incomplete.
    */
  def resolve(a: LoadArgs, env: Map[String, String]): Either[String, NeptuneLoadPlan] =
    def seconds(flag: Option[Int], variable: String): Either[String, Long] = flag match
      case Some(n) => Right(n.toLong * 1000L)
      case None =>
        env.get(variable).map(_.trim).filter(_.nonEmpty) match
          case None => Left(s"$variable (or the matching flag) is required")
          case Some(v) =>
            v.toIntOption
              .filter(_ >= 1)
              .map(_.toLong * 1000L)
              .toRight(s"$variable='$v' must be an integer >= 1")
    for
      settings <- scholarchain.storage.StorageSettings
        .load(env)
        .left
        .map(e => s"configuration: ${e.message}")
      role <- settings.runtime.neptuneLoader.requireRoleArn
      layout <- ObjectLayout
        .fromConfig(settings.buckets, settings.runtime.s3Prefixes, ObjectArea.Rag)
        .left
        .map(_.message)
      hex <- ObjectKeys.runHex(a.runId).left.map(_.message)
      timeout <- seconds(a.neptuneTimeoutSeconds, TimeoutVar)
      poll <- seconds(a.neptunePollSeconds, PollVar)
    yield
      val prefix = ObjectRef(
        ObjectArea.NeptuneExport,
        ObjectKeys.runDir(hex) + scholarchain.storage.graph.NeptuneCsvExporter.CsvDir + "/"
      )
      val request = GraphLoadRequest(a.runId, prefix)
      NeptuneLoadPlan(
        a.runId,
        request,
        NeptuneBulkLoader.plan(
          layout,
          role,
          settings.awsRegion,
          settings.runtime.batch.neptuneLoaderParallelism,
          request
        ),
        timeout,
        poll
      )

/** Starts one bulk load through a [[GraphLoader]] and polls it to a terminal state.
  *
  * Success only on `Completed`. A `Failed` state (the adapter maps every other loader status,
  * including cancellations, to it) or a failed status call is an error that names the load ID, so
  * the operator can look at the job; a timeout is an error too, and says the load may still be
  * running inside Neptune (this step never cancels it).
  *
  * The clock and the sleeper are injected, so tests need no real time.
  */
final class NeptuneLoadStep(
    loader: GraphLoader,
    sleeper: Sleeper,
    nowMillis: () => Long,
    out: String => Unit
):
  def run(plan: NeptuneLoadPlan): Result[NeptuneLoadReport] =
    loader.startLoad(plan.request).flatMap { job =>
      out(s"   load accepted: loadId=${job.jobId}")
      val started = nowMillis()
      var polls = 0
      var result: Option[Result[NeptuneLoadReport]] = None
      while result.isEmpty do
        polls += 1
        val elapsed = nowMillis() - started
        loader.status(job) match
          case Left(e) =>
            result = Some(
              Left(
                StorageError.Permanent(
                  "Neptune load status",
                  s"loadId=${job.jobId}: ${e.message}; the load may still be running"
                )
              )
            )
          case Right(GraphLoadState.Completed) =>
            result = Some(Right(NeptuneLoadReport(job.jobId, polls, elapsed)))
          case Right(GraphLoadState.Failed(reason)) =>
            result = Some(
              Left(StorageError.Permanent("Neptune bulk load", s"loadId=${job.jobId}: $reason"))
            )
          case Right(state) =>
            if elapsed >= plan.timeoutMillis then
              result = Some(
                Left(
                  StorageError.Timeout(
                    s"Neptune bulk load loadId=${job.jobId} (last state $state; not cancelled, " +
                      "it may still be running)",
                    plan.timeoutMillis
                  )
                )
              )
            else
              out(s"   load $state after ${elapsed / 1000}s")
              sleeper.sleep(plan.pollMillis)
      result.get
    }
