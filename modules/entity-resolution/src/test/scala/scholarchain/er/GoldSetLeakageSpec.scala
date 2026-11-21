package scholarchain.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.Locale
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scholarchain.er.policy.Policy

/** Evaluation labels never leak into matching logic (Phase 05 PART B leakage rule).
  *
  * Only `scholarchain.er.evaluation` reads a gold set, and only from its command-line argument.
  * Matching, feature, threshold and policy code, their resources and the policy configuration
  * must not name a gold set, its location, or the evaluation package; and no compiled class
  * outside the evaluation package may link to it.
  */
class GoldSetLeakageSpec extends AnyFunSuite with Matchers:

  private val moduleDir: Path =
    Vector(Paths.get("modules/entity-resolution"), Paths.get("."))
      .map(_.toAbsolutePath.normalize)
      .find(p => Files.isDirectory(p.resolve("src/main/scala/scholarchain/er")))
      .getOrElse(fail("cannot locate the entity-resolution module sources"))

  private val scalaRoot = moduleDir.resolve("src/main/scala/scholarchain/er")

  /** Packages that make or tune match decisions. */
  private val matchingPackages =
    Vector("features", "policy", "ids", "text", "adjudication", "io", "run", "model")

  private def files(root: Path): Vector[Path] =
    if !Files.isDirectory(root) then Vector.empty
    else
      Using.resource(Files.walk(root))(_.iterator().asScala.filter(Files.isRegularFile(_)).toVector)

  private def offending(fs: Vector[Path], tokens: Vector[String]): Vector[String] =
    for
      f <- fs
      text = Files.readString(f, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT)
      t <- tokens
      if text.contains(t.toLowerCase(Locale.ROOT))
    yield s"${moduleDir.relativize(f)} contains '$t'"

  test("matching packages exist (the scan is not vacuous)") {
    for p <- matchingPackages do
      withClue(p) {
        files(scalaRoot.resolve(p)).exists(_.toString.endsWith(".scala")) shouldBe true
      }
  }

  test(
    "matching, feature, threshold and policy sources never reference a gold set or the evaluator"
  ) {
    val fs = matchingPackages.flatMap(p => files(scalaRoot.resolve(p)))
    offending(
      fs,
      Vector(
        "gold",
        "agent-runs",
        "human_label",
        "scholarchain.er.evaluation",
        "EvaluateGoldSet",
        "GoldLabel"
      )
    ) shouldBe empty
  }

  test("no main source outside the evaluation package imports the evaluation package") {
    val fs = files(scalaRoot).filterNot(_.startsWith(scalaRoot.resolve("evaluation")))
    offending(
      fs,
      Vector("scholarchain.er.evaluation", "EvaluateGoldSet", "GoldSet.")
    ) shouldBe empty
  }

  test("main resources and the policy configuration never name a gold-set file or its location") {
    offending(
      files(moduleDir.resolve("src/main/resources")),
      Vector("gold", "agent-runs", "human_label")
    ) shouldBe empty
    offending(
      files(moduleDir.resolve("config")),
      Vector("gold-set-v1", "agent-runs", "human_label", ".csv")
    ) shouldBe empty
  }

  test("no compiled class outside the evaluation package links to it") {
    val classesRoot = Paths.get(classOf[Policy].getProtectionDomain.getCodeSource.getLocation.toURI)
    val er = classesRoot.resolve("scholarchain/er")
    val classes = files(er)
      .filter(_.toString.endsWith(".class"))
      .filterNot(_.startsWith(er.resolve("evaluation")))
    classes should not be empty
    val needle = "scholarchain/er/evaluation".getBytes(StandardCharsets.UTF_8)
    val linked = classes.filter { c =>
      val b = Files.readAllBytes(c)
      b.indices.exists(i =>
        i + needle.length <= b.length && needle.indices.forall(j => b(i + j) == needle(j))
      )
    }
    linked.map(c => er.relativize(c).toString) shouldBe empty
  }
