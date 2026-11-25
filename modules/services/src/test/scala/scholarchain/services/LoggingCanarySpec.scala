package scholarchain.services

import ch.qos.logback.classic.{BasicConfigurator, Level, Logger, LoggerContext}
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.{ILoggingEvent, LoggingEvent}
import ch.qos.logback.core.OutputStreamAppender
import java.net.{URL, URLClassLoader}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters.*

/** INC-20261125-debug-credential-logging. These tests read the PRODUCTION `logback.xml` explicitly:
  * on the test classpath `logback-test.xml` would otherwise take precedence over it.
  */
class LoggingCanarySpec extends AnyFunSuite with Matchers:
  import LoggingCanaryProbe.*

  private def resources(name: String): Vector[URL] =
    getClass.getClassLoader.getResources(name).asScala.toVector

  /** The one `logback.xml` on the classpath: the file that ships in the services jar. */
  private lazy val production: URL =
    resources("logback.xml") match
      case Vector(only) => only
      case found => fail(s"expected exactly one logback.xml on the classpath, found $found")

  private lazy val productionText: String =
    val in = production.openStream()
    try new String(in.readAllBytes(), UTF_8)
    finally in.close()

  private lazy val classpath: String =
    Iterator
      .iterate(getClass.getClassLoader)(_.getParent)
      .takeWhile(_ != null)
      .flatMap {
        case l: URLClassLoader => l.getURLs.toVector
        case _ => Vector.empty
      }
      .filter(_.getProtocol == "file")
      .map(u => Paths.get(u.toURI).toString)
      .toVector
      .distinct
      .mkString(java.io.File.pathSeparator)

  /** Runs the probe in a new JVM under `config` and returns everything it wrote. The child gets an
    * empty environment, so no real credential, proxy or JVM option can reach it, and the SDK's
    * shared config and credential files are pointed at nothing.
    */
  private def runUnder(config: URL): String =
    val dir = Files.createTempDirectory("logging-canary")
    val output = dir.resolve("output.log")
    try
      val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString
      val builder = new ProcessBuilder(
        java,
        s"-Dlogback.configurationFile=$config",
        s"-Duser.home=$dir",
        "-cp",
        classpath,
        "scholarchain.services.LoggingCanaryProbe"
      )
      builder.environment().clear()
      builder.environment().put("AWS_EC2_METADATA_DISABLED", "true")
      builder.environment().put("AWS_CONFIG_FILE", dir.resolve("absent-config").toString)
      builder
        .environment()
        .put("AWS_SHARED_CREDENTIALS_FILE", dir.resolve("absent-shared").toString)
      builder.redirectErrorStream(true).redirectOutput(output.toFile)
      val process = builder.start()
      if !process.waitFor(90, TimeUnit.SECONDS) then
        process.destroyForcibly()
        fail("the logging probe did not finish in time")
      val text = Files.readString(output, UTF_8)
      withClue(text.takeRight(4000))(process.exitValue() shouldBe 0)
      text should include(Success)
      text
    finally
      Files.deleteIfExists(output)
      deleteTree(dir)

  private def deleteTree(root: Path): Unit =
    val walk = Files.walk(root)
    try walk.iterator().asScala.toVector.reverse.foreach(Files.delete)
    finally walk.close()

  private def variant(text: String): URL =
    val file = Files.createTempFile("logback-variant", ".xml")
    file.toFile.deleteOnExit()
    Files.writeString(file, text, UTF_8)
    file.toUri.toURL

  private val forbidden = Vector(
    SecretValue,
    SessionToken,
    AccessKey,
    SecretKey,
    "Authorization",
    "AWS4-HMAC-SHA256",
    "X-Amz-Security-Token"
  )

  private def leaked(output: String): Vector[String] =
    forbidden.filter(f => output.toLowerCase.contains(f.toLowerCase))

  test("under the production configuration signed SDK calls leave no credential or secret") {
    val output = runUnder(production)
    leaked(output) shouldBe empty
    output.linesIterator
      .filter(l => l.contains(" DEBUG ") || l.contains(" TRACE "))
      .toVector shouldBe
      empty
  }

  test("under the production configuration the application lines keep their shape and trace ID") {
    val lines = runUnder(production).linesIterator.toVector
    val prefix = """\d\d:\d\d:\d\d\.\d{3} \[main\] INFO """
    val access = (prefix + "scholarchain\\.services\\.access -- request method=GET " +
      "route=/v1/probe status=200 requestId=request-canary-probe traceId=" + TraceId +
      " latencyMs=1 principal=- error=-").r
    val stage = (prefix + "scholarchain\\.services\\.workflow -- stage traceId=" + TraceId +
      " stage=probe latencyMs=1 outcome=OK").r
    lines.count(access.matches) shouldBe 1
    lines.count(stage.matches) shouldBe 1
  }

  test("control: with the levels at DEBUG the same capture does see the canaries") {
    val everything = productionText.replaceAll("""level="[A-Z]+"""", """level="DEBUG"""")
    everything should not be productionText
    val output = runUnder(variant(everything))
    leaked(output) should contain allOf (
      SecretValue,
      SessionToken,
      AccessKey,
      "Authorization",
      "AWS4-HMAC-SHA256",
      "X-Amz-Security-Token"
    )
    output should include(" DEBUG ")
    output should include("org.apache.hc.client5.http.wire")
    output should include("org.apache.hc.client5.http.headers")
    output should include("software.amazon.awssdk.request")
  }

  test("lowering only the root level to DEBUG still withholds credentials and secrets") {
    val rootOnly =
      productionText.replace("""<root level="INFO">""", """<root level="DEBUG">""")
    rootOnly should not be productionText
    leaked(runUnder(variant(rootOnly))) shouldBe empty
  }

  private def configured(configure: LoggerContext => Unit): LoggerContext =
    val context = new LoggerContext()
    configure(context)
    context

  private def productionContext(): LoggerContext =
    configured { context =>
      val joran = new JoranConfigurator()
      joran.setContext(context)
      joran.doConfigure(production)
    }

  test("the production file sets the root to INFO and the HTTP and SDK loggers to WARN") {
    val context = productionContext()
    try
      context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel shouldBe Level.INFO
      Vector(
        "software.amazon.awssdk",
        "software.amazon.awssdk.request",
        "org.apache.hc",
        "org.apache.hc.client5.http.wire",
        "org.apache.hc.client5.http.headers",
        "org.apache.http",
        "org.apache.http.wire",
        "org.apache.http.headers",
        "io.netty",
        "io.undertow",
        "org.xnio"
      ).foreach(name => withClue(name)(context.getLogger(name).getLevel shouldBe Level.WARN))
    finally context.stop()
  }

  test("the production file substitutes no variable, so no setting can lower a level") {
    productionText.replaceAll("(?s)<!--.*?-->", "") should not include "${"
  }

  test("no other file that Logback would prefer ships next to the production file") {
    resources("logback.scmo") shouldBe empty
    resources("logback-test.scmo") shouldBe empty
    resources("logback-test.xml").filter(_.toString.contains("/classes/")) shouldBe empty
  }

  test("a production line is byte for byte what Logback's unconfigured default writes") {
    def encoded(context: LoggerContext, event: ILoggingEvent): String =
      context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).iteratorForAppenders().next() match
        case a: OutputStreamAppender[ILoggingEvent] @unchecked =>
          new String(a.getEncoder.encode(event), UTF_8)
        case other => fail(s"unexpected appender $other")

    val shipped = productionContext()
    val default = configured { context =>
      val basic = new BasicConfigurator()
      basic.setContext(context)
      basic.configure(context)
    }
    try
      val logger = shipped.getLogger("scholarchain.services.access")
      val event = new LoggingEvent(
        classOf[Logger].getName,
        logger,
        Level.INFO,
        "request traceId={}",
        null,
        Array[AnyRef](TraceId)
      )
      encoded(shipped, event) shouldBe encoded(default, event)
    finally
      shipped.stop()
      default.stop()
  }
