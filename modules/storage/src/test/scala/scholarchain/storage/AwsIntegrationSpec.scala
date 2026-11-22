package scholarchain.storage

import java.nio.file.Files
import org.scalatest.Tag
import org.scalatest.exceptions.TestCanceledException
import scholarchain.core.config.LoaderParallelism
import scholarchain.storage.aws.*
import scholarchain.storage.common.*
import scholarchain.storage.dynamo.*
import scholarchain.storage.graph.*
import scholarchain.storage.loader.*
import scholarchain.storage.manifest.*
import scholarchain.storage.objects.*
import scholarchain.storage.vectors.*
import scholarchain.tags.AwsIntegration

/** Gate and wiring of the bounded AWS integration suites.
  *
  * A suite runs only when SCHOLARCHAIN_AWS_IT is `true` (the build also excludes the tag
  * `scholarchain.tags.AwsIntegration` otherwise), the storage configuration loads from the process
  * environment, and an explicit AWS profile is named in the variable the configuration names
  * (`integration.awsProfileVariable`, default SCHOLARCHAIN_AWS_PROFILE). The default credential
  * chain is never used. When any condition is missing the test is CANCELED with the reason; it is
  * never reported as passed.
  *
  * Every suite is bounded: a handful of requests per case, unique keys, no scans.
  */
object AwsIt:
  val EnableVariable = "SCHOLARCHAIN_AWS_IT"
  val AuxiliaryAreaVariable = "SCHOLARCHAIN_IT_AUX_AREA"

  def enabled: Boolean = sys.env.get(EnableVariable).contains("true")

  lazy val settings: Either[String, StorageSettings] =
    StorageSettings.load(sys.env).left.map(_.message)

  def profile: Option[String] =
    settings.toOption
      .flatMap(s => sys.env.get(s.runtime.endpoints.awsProfileVariable))
      .filter(_.trim.nonEmpty)

  /** The reason the suites cannot run now, or `None` when they can. */
  def skipReason: Option[String] =
    if !enabled then Some(s"$EnableVariable is not 'true'")
    else
      settings.fold(
        reason => Some(s"storage configuration does not load from the environment: $reason"),
        s =>
          if profile.isEmpty then
            Some(s"no explicit AWS profile: set ${s.runtime.endpoints.awsProfileVariable}")
          else None
      )

  def requirement(): Unit =
    skipReason.foreach(r => throw new TestCanceledException(r, 0))

  lazy val clients: AwsClients = AwsClients.forProfile(settings.toOption.get, profile.get)
  lazy val retrier: Retrier =
    new Retrier(settings.toOption.get.runtime.retry, AwsErrors.isTransient)

  def layout: ObjectLayout =
    val area = sys.env
      .get(AuxiliaryAreaVariable)
      .flatMap(n => ObjectArea.values.find(_.wire == n))
      .getOrElse(throw new TestCanceledException(s"$AuxiliaryAreaVariable names no object area", 0))
    val s = settings.toOption.get
    ObjectLayout
      .fromConfig(s.buckets, s.runtime.s3Prefixes, area)
      .fold(e => throw new TestCanceledException(e.message, 0), identity)

  def dynamo: DynamoPorts =
    val s = settings.toOption.get
    if s.runtime.dynamoTables.researcherMetricCache.isEmpty then
      throw new TestCanceledException("dynamodb.tables.researcherMetricCache is not configured", 0)
    DynamoPorts(
      new DynamoItemStore(clients.dynamo, retrier),
      s.runtime.dynamoTables,
      s.runtime.querySession.ttlSeconds
    )

trait AwsGate extends PortGate:
  override protected def contractTags: Seq[Tag] = Seq(AwsIntegration)
  override protected def preconditions(): Unit = AwsIt.requirement()

class S3ObjectStoreIT extends ObjectStoreContract with AwsGate:
  protected lazy val store: ObjectStore =
    new S3ObjectStore(AwsIt.clients.s3, AwsIt.layout, AwsIt.retrier)
  protected val keyPrefix: String = s"it-${java.util.UUID.randomUUID}/"

class RunLedgerIT extends RunLedgerContract with AwsGate:
  protected lazy val ledger: RunLedger = AwsIt.dynamo.runLedger

class EntityLinkCacheIT extends EntityLinkCacheContract with AwsGate:
  protected lazy val cache: EntityLinkCache = AwsIt.dynamo.entityLinkCache

class QuerySessionsIT extends QuerySessionsContract with AwsGate:
  protected lazy val sessions: QuerySessions = AwsIt.dynamo.querySessions

class EvidenceStoreIT extends EvidenceStoreContract with AwsGate:
  protected lazy val evidence: EvidenceStore = AwsIt.dynamo.evidence

class ArtifactRegistryIT extends ArtifactRegistryContract with AwsGate:
  protected lazy val registry: ArtifactRegistry = AwsIt.dynamo.artifacts

class MetricCacheIT extends MetricCacheContract with AwsGate:
  protected lazy val metricCache: ResearcherMetricCache = AwsIt.dynamo.metricCache.get

class S3VectorsIT extends VectorIndexContract with AwsGate:
  protected lazy val definition: IndexDefinition =
    IndexDefinition.fromSettings(AwsIt.settings.toOption.get).head
  protected lazy val index: VectorIndex =
    new S3VectorsIndex(
      AwsIt.clients.vectors,
      AwsIt.settings.toOption.get.buckets.vectorBucket,
      AwsIt.retrier,
      IndexDefinition.fromSettings(AwsIt.settings.toOption.get)
    )

/** Loads the fixture graph through the real S3 and Neptune adapters (bounded wait), then runs the
  * reader contract against the cluster.
  */
class NeptuneGraphIT extends GraphReaderContract with AwsGate:
  private def s = AwsIt.settings.toOption.get
  protected lazy val limits: GraphReaderLimits = GraphReaderLimits(s.graphLimit, s.maxPathDepth)
  protected lazy val reader: GraphReader =
    new NeptuneGraphReader(AwsIt.clients.neptune, limits, AwsIt.retrier)
  private var loaded = false

  protected def prepare(): Unit =
    if !loaded then
      val roleArn = s.runtime.neptuneLoader.requireRoleArn
        .fold(r => throw new TestCanceledException(r, 0), identity)
      val layout = AwsIt.layout
      val objects = new S3ObjectStore(AwsIt.clients.s3, layout, AwsIt.retrier)
      val loader = new NeptuneBulkLoader(
        AwsIt.clients.neptune,
        layout,
        roleArn,
        s.awsRegion,
        s.runtime.batch.neptuneLoaderParallelism,
        AwsIt.retrier
      )
      val workflow = new GraphLoadWorkflow(
        new LocalFileStager(FixturePaths.root),
        objects,
        loader,
        new InMemoryRejectSink,
        Files.createTempDirectory("neptune-it-")
      )
      val report = ok(workflow.run(manifestOf(FixturePaths.root, "neptune")))
      // bounded wait: 5 minutes at 5 second intervals
      val deadline = System.nanoTime + 5L * 60 * 1000 * 1000 * 1000
      var state = ok(loader.status(report.job))
      while (state == GraphLoadState.Queued || state == GraphLoadState.InProgress) && System.nanoTime < deadline
      do
        Thread.sleep(5000)
        state = ok(loader.status(report.job))
      state shouldBe GraphLoadState.Completed
      loaded = true

/** Not tagged: proves that the tagged suites exist and are skipped, not passed, without the gate. */
class AwsIntegrationGateSpec extends StorageSuite:
  private val suites: Vector[Class[?]] = Vector(
    classOf[S3ObjectStoreIT],
    classOf[RunLedgerIT],
    classOf[EntityLinkCacheIT],
    classOf[QuerySessionsIT],
    classOf[EvidenceStoreIT],
    classOf[ArtifactRegistryIT],
    classOf[MetricCacheIT],
    classOf[S3VectorsIT],
    classOf[NeptuneGraphIT]
  )

  test("the tag is the one the build excludes unless SCHOLARCHAIN_AWS_IT=true") {
    AwsIntegration.name shouldBe "scholarchain.tags.AwsIntegration"
  }

  test("every integration suite registers all of its tests under the AwsIntegration tag") {
    suites.foreach { c =>
      val suite = c.getDeclaredConstructor().newInstance().asInstanceOf[org.scalatest.Suite]
      suite.testNames should not be empty
      suite.testNames.foreach { n =>
        withClue(s"${c.getSimpleName}: $n") {
          suite.tags.get(n).map(_.contains(AwsIntegration.name)) shouldBe Some(true)
        }
      }
    }
  }

  test("without the gate a suite is canceled with its reason, never passed") {
    // this environment has no SCHOLARCHAIN_AWS_IT=true
    if !AwsIt.enabled then
      val e = intercept[TestCanceledException](AwsIt.requirement())
      e.getMessage should include("SCHOLARCHAIN_AWS_IT")
  }

  test("the gate demands storage configuration and an explicit profile, never the default chain") {
    AwsIt.skipReason.foreach(_ should not be empty)
    // forProfile refuses a blank profile
    intercept[IllegalArgumentException](AwsClients.forProfile(TestEnv.settings, "  "))
  }
