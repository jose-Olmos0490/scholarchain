package scholarchain.services.ops

import scholarchain.services.config.OpsSettings
import scholarchain.services.error.{ApiError, ErrorCode}
import scholarchain.services.resilience.{BoundedExecutor, CircuitBreaker}

/** One readiness check: a cheap call through a port. `check` returns true when the dependency
  * answers. A probe never reports an error text.
  */
final case class ReadinessProbe(name: String, check: () => Boolean)

/** Liveness, readiness and metrics.
  *
  * Liveness (`/healthz`) says the process runs: it calls nothing and reports nothing else.
  * Readiness (`/readyz`) says the service can do its work: it asks each probe, within a time
  * limit, and reports only each probe's name and `ok` or `failing`. Neither exposes versions,
  * host names, endpoints, credentials or error messages.
  */
final class OpsService(
    probes: Vector[ReadinessProbe],
    probeExecutor: BoundedExecutor,
    settings: OpsSettings,
    metrics: Metrics,
    breakers: Seq[CircuitBreaker]
):
  def health: ujson.Obj = ujson.Obj("status" -> "ok")

  def ready: Either[ApiError, ujson.Obj] =
    val results = probes.map { p =>
      val ok = probeExecutor
        .callWithin(settings.readinessTimeoutMillis)(
          if p.check() then Right(()) else Left(ApiError.internal)
        )
        .isRight
      p.name -> ok
    }
    val checks =
      ujson.Obj.from(results.map((n, ok) => n -> ujson.Str(if ok then "ok" else "failing")))
    if results.forall(_._2) then Right(ujson.Obj("status" -> "ready", "checks" -> checks))
    else
      Left(
        ApiError(
          ErrorCode.Unavailable,
          "the service is not ready",
          retryable = true,
          reason = Some("NOT_READY"),
          retryAfterSeconds = None,
          details = Map("checks" -> checks)
        )
      )

  def metricsEnabled: Boolean = settings.metricsEnabled
  def metricsText: String = metrics.render(breakers)
