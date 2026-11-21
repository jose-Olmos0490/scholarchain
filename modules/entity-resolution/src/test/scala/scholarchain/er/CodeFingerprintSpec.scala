package scholarchain.er

import java.nio.file.{Files, Path, Paths}
import java.util.jar.{JarEntry, JarOutputStream}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scholarchain.er.run.CodeFingerprint

/** Re-review finding N5: the gold-set evaluator is not decision code, so it is left out of the
  * code fingerprint (and therefore out of the run ID), while every decision package stays in it.
  */
class CodeFingerprintSpec extends AnyFunSuite with Matchers:

  private val decisionEntry = "scholarchain/er/policy/MatchPolicy$.class"
  private val evaluationEntry = "scholarchain/er/evaluation/Metrics$.class"
  private val resourceEntry = "scholarchain/er/policy/er-v1.json"

  private def classDir(files: Map[String, String]): Path =
    val root = Files.createTempDirectory("er-fingerprint-dir")
    files.foreach { (rel, body) =>
      val p = root.resolve(rel)
      Files.createDirectories(p.getParent)
      Files.writeString(p, body)
    }
    root

  private def jar(files: Map[String, String]): Path =
    val p = Files.createTempFile("er-fingerprint", ".jar")
    Using.resource(new JarOutputStream(Files.newOutputStream(p))) { out =>
      files.toVector.sortBy(_._1).foreach { (rel, body) =>
        out.putNextEntry(new JarEntry(rel))
        out.write(body.getBytes("UTF-8"))
        out.closeEntry()
      }
    }
    p

  private val base = Map(
    decisionEntry -> "decision-v1",
    resourceEntry -> "{\"policy\":1}",
    evaluationEntry -> "evaluator-v1"
  )

  private def fp(p: Path): String = TestData.ok(CodeFingerprint.ofClasspathEntry(p))

  for (kind, build) <- Seq[(String, Map[String, String] => Path)](
      "class directory" -> classDir,
      "jar" -> jar
    )
  do
    test(s"$kind: changing only evaluator classes does not change the fingerprint") {
      val before = fp(build(base))
      fp(build(base.updated(evaluationEntry, "evaluator-v2"))) shouldBe before
      fp(build(base + ("scholarchain/er/evaluation/New.class" -> "added"))) shouldBe before
      fp(build(base - evaluationEntry)) shouldBe before
    }

    test(s"$kind: changing decision code or a policy resource still changes the fingerprint") {
      val before = fp(build(base))
      fp(build(base.updated(decisionEntry, "decision-v2"))) should not be before
      fp(build(base.updated(resourceEntry, "{\"policy\":2}"))) should not be before
      fp(build(base + ("scholarchain/er/run/New.class" -> "added"))) should not be before
    }

  test("the whole-module fingerprint (evaluation ID) still changes when evaluator classes change") {
    val dir = classDir(base)
    val changed = classDir(base.updated(evaluationEntry, "evaluator-v2"))
    val whole =
      (p: Path) => TestData.ok(CodeFingerprint.ofClasspathEntry(p, CodeFingerprint.inModule))
    whole(changed) should not be whole(dir)
    whole(dir) should not be fp(dir)
    TestData.ok(CodeFingerprint.ofModule(classOf[scholarchain.er.policy.Policy])) should not be
      TestData.ok(CodeFingerprint.of(classOf[scholarchain.er.policy.Policy]))
  }

  test("a classpath entry holding only evaluator classes has nothing to fingerprint") {
    CodeFingerprint.ofClasspathEntry(classDir(Map(evaluationEntry -> "x"))).isLeft shouldBe true
  }

  test("only the evaluation package is excluded; a look-alike package name is not") {
    CodeFingerprint.ExcludedPrefixes shouldBe Vector("scholarchain/er/evaluation/")
    CodeFingerprint.included("scholarchain/er/evaluation/Metrics.class") shouldBe false
    CodeFingerprint.included("scholarchain/er/evaluationx/Metrics.class") shouldBe true
    CodeFingerprint.included("scholarchain/er/policy/MatchPolicy.class") shouldBe true
    CodeFingerprint.included("scholarchain/core/Foo.class") shouldBe false
  }

  test("the real module fingerprint is the same with the evaluator classes removed") {
    val real =
      Paths.get(
        classOf[scholarchain.er.policy.Policy].getProtectionDomain.getCodeSource.getLocation.toURI
      )
    // sbt runs tests against the class directory, which must hold evaluator classes for this
    // check to mean anything.
    Files.isDirectory(real.resolve("scholarchain/er/evaluation")) shouldBe true
    val copy = Files.createTempDirectory("er-fingerprint-real")
    Using.resource(Files.walk(real.resolve("scholarchain/er"))) { s =>
      s.iterator()
        .asScala
        .filter(Files.isRegularFile(_))
        .filterNot(p =>
          real.relativize(p).toString.replace('\\', '/').startsWith("scholarchain/er/evaluation/")
        )
        .foreach { p =>
          val target = copy.resolve(real.relativize(p).toString)
          Files.createDirectories(target.getParent)
          Files.copy(p, target)
        }
    }
    fp(copy) shouldBe TestData.ok(CodeFingerprint.of(classOf[scholarchain.er.policy.Policy]))
  }
