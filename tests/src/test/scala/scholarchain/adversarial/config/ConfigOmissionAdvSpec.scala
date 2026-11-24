package scholarchain.adversarial.config

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.config.*

/** Configuration omissions and hostile environment values, driven through the public loader with
  * an explicit environment map (the process environment is never read).
  */
class ConfigOmissionAdvSpec extends AnyFunSuite:

  private def invalid(env: Map[String, String]): ConfigError.Invalid =
    ConfigLoader.load(env) match
      case Left(e: ConfigError.Invalid) => e
      case other => fail(s"expected ConfigError.Invalid, got $other")

  test("a null environment map is a MissingEnvironmentName, not an exception") {
    val r = ConfigLoader.load(null)
    assert(r == Left(ConfigError.MissingEnvironmentName(ConfigLoader.EnvironmentVariable)))
  }

  test("an empty environment is MissingEnvironmentName") {
    assert(
      ConfigLoader.load(Map.empty) ==
        Left(ConfigError.MissingEnvironmentName(ConfigLoader.EnvironmentVariable))
    )
  }

  test("hostile environment names are rejected before any resource lookup") {
    val hostile =
      Seq("../etc/passwd", "DEV", "dev.conf", "1dev", "dev name", "-dev", "dev/../x")
    hostile.foreach { n =>
      val r = ConfigLoader.load(Map(ConfigLoader.EnvironmentVariable -> n))
      r match
        case Left(ConfigError.InvalidEnvironmentName(_)) => succeed
        case Left(ConfigError.MissingEnvironmentName(_)) if n.trim.isEmpty => succeed
        case other => fail(s"name '$n' was not rejected as invalid: $other")
    }
  }

  test("surrounding whitespace on the environment name is trimmed, not smuggled into a path") {
    val e = ConfigLoader.loadRaw(Map(ConfigLoader.EnvironmentVariable -> " dev\n"))
    assert(e.map(_._1) == Right("dev"))
  }

  test("an unknown but well-formed environment counts as an empty file, and lists missing keys") {
    val e = invalid(Map(ConfigLoader.EnvironmentVariable -> "no-such-env"))
    assert(e.missingKeys.nonEmpty)
    assert(e.invalidSettings.isEmpty)
    val bound = ConfigLoader.boundVariables().toOption.get
    // Every missing key names the variable that can supply it, and that variable is really bound.
    e.missingKeys.foreach { k =>
      assert(k.environmentVariable.nonEmpty, k)
      assert(bound.contains(k.environmentVariable), k)
      assert(k.path.startsWith("scholarchain."), k)
    }
    // No duplicate reports.
    assert(e.missingKeys.map(_.path).distinct.size == e.missingKeys.size)
  }

  test("a blank or whitespace-only value is treated as missing, never as a value") {
    val base = Map(ConfigLoader.EnvironmentVariable -> "no-such-env")
    val blank = invalid(base + ("RAW_BUCKET" -> "   "))
    assert(blank.missingKeys.exists(_.path == "scholarchain.storage.rawBucket"))
    val set = invalid(base + ("RAW_BUCKET" -> "bucket-a"))
    assert(!set.missingKeys.exists(_.path == "scholarchain.storage.rawBucket"))
  }

  test("an unparseable numeric value is reported as invalid, not thrown") {
    val env = Map(ConfigLoader.EnvironmentVariable -> "no-such-env", "NEPTUNE_PORT" -> "12ab")
    val e = invalid(env)
    assert(e.invalidSettings.exists(_.path == "scholarchain.neptune.port"), e.invalidSettings)
    assert(!e.missingKeys.exists(_.path == "scholarchain.neptune.port"))
  }

  test("an out-of-range Neptune port is rejected") {
    Seq("0", "-1", "65536", "99999999999").foreach { p =>
      val env = Map(ConfigLoader.EnvironmentVariable -> "no-such-env", "NEPTUNE_PORT" -> p)
      val e = invalid(env)
      assert(
        e.invalidSettings.exists(_.path == "scholarchain.neptune.port"),
        s"port $p was accepted; invalid=${e.invalidSettings}"
      )
    }
  }

  test("unbound variables, including credentials, never reach the loader or an error message") {
    val secret = "AKIA-SECRET-VALUE-9f3c"
    val env = Map(
      ConfigLoader.EnvironmentVariable -> "no-such-env",
      "AWS_SECRET_ACCESS_KEY" -> secret,
      "AWS_SESSION_TOKEN" -> secret,
      "NEPTUNE_PORT" -> "not-a-port"
    )
    val msg = ConfigLoader.load(env).left.toOption.get.message
    assert(!msg.contains(secret), msg)
    val raw = ConfigLoader.loadRaw(env).toOption.get._2
    assert(!raw.toString.contains(secret))
  }

  test("a section loads from only its own variables; other sections' omissions do not leak in") {
    val env = Map(
      ConfigLoader.EnvironmentVariable -> "no-such-env",
      "NEPTUNE_ENDPOINT" -> "n.example.invalid",
      "NEPTUNE_PORT" -> "8182",
      "NEPTUNE_QUERY_TIMEOUT_MS" -> "1500"
    )
    assert(
      ConfigLoader.loadSection(NeptuneConfig, env) ==
        Right(NeptuneConfig("n.example.invalid", 8182, 1500))
    )
    // The same section with one variable absent names exactly that variable.
    val e = ConfigLoader.loadSection(NeptuneConfig, env - "NEPTUNE_ENDPOINT")
    e match
      case Left(ConfigError.Invalid(m, i)) =>
        assert(m.map(_.environmentVariable) == Vector("NEPTUNE_ENDPOINT"))
        assert(i.isEmpty)
      case other => fail(s"unexpected $other")
  }

  test("a cyclic or self-referential substitution in a value is data, not resolved") {
    val env = Map(
      ConfigLoader.EnvironmentVariable -> "no-such-env",
      "RAW_BUCKET" -> "${scholarchain.storage.rawBucket}",
      "CURATED_BUCKET" -> "${?HOME}"
    )
    // Must not throw. If it loads the key, the literal text is the value.
    ConfigLoader.loadRaw(env) match
      case Right((_, c)) =>
        if c.hasPath("scholarchain.storage.curatedBucket") then
          assert(c.getString("scholarchain.storage.curatedBucket") == "${?HOME}")
      case Left(_) => succeed
  }
