package scholarchain.services.entrypoint

import org.slf4j.LoggerFactory
import scholarchain.services.config.{
  EntrypointSettings,
  GraphEvidenceApiSettings,
  RuntimeLimits,
  ServiceSettings
}
import scholarchain.services.http.SystemServiceClock
import scholarchain.services.runtime.{ResourceApiPorts, RuntimeEnvironment, ServiceRuntime}
import scholarchain.services.security.Authenticator
import scholarchain.storage.aws.NeptuneGraphReader
import scholarchain.storage.graphReaderLimits

/** Everything the graph and evidence API root needs, as values. */
final case class GraphEvidenceApiInputs(
    settings: ServiceSettings,
    entry: GraphEvidenceApiSettings,
    limits: RuntimeLimits,
    ports: ResourceApiPorts,
    authenticator: Authenticator,
    env: RuntimeEnvironment = RuntimeEnvironment()
)

/** The graph and evidence API composition root: `GET /v1/graph/nodes/...`, `GET /v1/evidence/...`,
  * `/healthz`, `/readyz`. It needs the graph reader, the evidence store and the API keys. It has no
  * model, no vector index, no job store and no queue, and it does not call the query API.
  */
object GraphEvidenceApiRoot:
  def start(in: GraphEvidenceApiInputs): RunningHttp =
    RunningHttp.start(
      ServiceRuntime.resourceApi(in.settings, in.limits, in.ports, in.authenticator, in.env),
      in.settings.http.maxBodyBytes,
      in.entry.bind
    )

/** The live graph and evidence API: Neptune (read-only) and DynamoDB. */
object GraphEvidenceApiMain:
  private val log = LoggerFactory.getLogger("scholarchain.services.graph-evidence-api")

  def main(args: Array[String]): Unit =
    open(sys.env) match
      case Left(problems) =>
        problems.foreach(p => log.error("configuration: {}", p))
        sys.exit(2)
      case Right((in, stores)) =>
        val running = GraphEvidenceApiRoot.start(in)
        log.info("graph-evidence-api listening on port {}", Int.box(running.port))
        sys.addShutdownHook {
          running.stop()
          stores.close()
        }
        new java.util.concurrent.CountDownLatch(1).await()

  def open(
      env: Map[String, String]
  ): Either[Vector[String], (GraphEvidenceApiInputs, LiveStores)] =
    for
      svc <- ServiceSettings.load(requireServing = false)
      entry <- EntrypointSettings.graphEvidenceApi()
      core <- CoreConfigs.resource(env).left.map(e => Vector(e.message))
      stores <- LiveStores.open(core.storage).left.map(Vector(_))
      auth <- LiveAuth.open(svc, core.storage.awsRegion, SystemServiceClock) match
        case Left(e) =>
          stores.close()
          Left(Vector(e))
        case Right(a) => Right(a)
    yield
      val graph =
        new NeptuneGraphReader(
          stores.clients.neptune,
          core.storage.graphReaderLimits,
          stores.retrier
        )
      (
        GraphEvidenceApiInputs(
          svc,
          entry,
          core.limits,
          ResourceApiPorts(
            stores.dynamo.evidence,
            graph,
            Vector(stores.dynamoProbe, stores.neptuneProbe, auth._2)
          ),
          auth._1
        ),
        stores
      )
