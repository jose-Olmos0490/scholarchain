package scholarchain.core.config

import com.typesafe.config.{
  Config,
  ConfigFactory,
  ConfigParseOptions,
  ConfigResolveOptions,
  ConfigUtil,
  ConfigValueFactory
}
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Loads ScholarChain configuration from three layers, highest precedence first:
  *
  *   1. environment variables, bound through `${?VAR}` substitutions in
  *      `scholarchain/env-overrides.conf`
  *   2. the versioned environment file `scholarchain/conf/<env>.conf`, if one exists; a missing
  *      file is an empty layer, so environment variables alone can configure any environment
  *   3. the base file `reference.conf` (non-secret structure only)
  *
  * The environment name comes from `SCHOLARCHAIN_ENV` and is always required: unset or blank is
  * [[ConfigError.MissingEnvironmentName]], a name outside `[a-z][a-z0-9-]*` is
  * [[ConfigError.InvalidEnvironmentName]]. All files are classpath resources of the core module.
  * Required keys have no default in any shipped layer; a missing key yields
  * [[ConfigError.Invalid]] listing every missing key with the environment variable that supplies
  * it.
  *
  * The process environment is passed in explicitly, so tests never read the real one. Only the
  * variables that `env-overrides.conf` binds, plus `SCHOLARCHAIN_ENV`, are taken from it; every
  * other variable (for example cloud credentials) is dropped before any parsing, and the returned
  * `Config` contains only the `scholarchain` tree. Environment files cannot bind variables: only
  * `env-overrides.conf` does.
  */
object ConfigLoader:
  val EnvironmentVariable = "SCHOLARCHAIN_ENV"
  val BaseResource = "reference.conf"
  val OverridesResource = "scholarchain/env-overrides.conf"
  def environmentResource(name: String): String = s"scholarchain/conf/$name.conf"

  private val EnvName = "[a-z][a-z0-9-]*".r
  private val OptionalSubstitution = """\$\{\?\s*([A-Za-z_][A-Za-z0-9_]*)\s*\}""".r

  /** Names of the environment variables bound by `env-overrides.conf` (every `${?VAR}`), plus
    * `SCHOLARCHAIN_ENV`. Only these are read from the environment map.
    */
  def boundVariables(
      classLoader: ClassLoader = getClass.getClassLoader
  ): Either[ConfigError, Set[String]] =
    try
      val urls = classLoader.getResources(OverridesResource).asScala.toVector
      if urls.isEmpty then Left(ConfigError.ParseFailure(s"missing resource $OverridesResource"))
      else
        val names = urls.flatMap { url =>
          val in = url.openStream()
          val text =
            try new String(in.readAllBytes(), StandardCharsets.UTF_8)
            finally in.close()
          val uncommented = text.linesIterator.map(_.replaceFirst("(#|//).*$", "")).mkString("\n")
          OptionalSubstitution.findAllMatchIn(uncommented).map(_.group(1))
        }
        Right(names.toSet + EnvironmentVariable)
    catch case NonFatal(e) => Left(ConfigError.ParseFailure(String.valueOf(e.getMessage)))

  /** Loads and merges the layers, without typing. Returns the environment name and the resolved
    * `scholarchain` tree.
    */
  def loadRaw(
      env: Map[String, String],
      classLoader: ClassLoader = getClass.getClassLoader
  ): Either[ConfigError, (String, Config)] =
    for
      name <- Option(env).flatMap(_.get(EnvironmentVariable)).map(_.trim).filter(_.nonEmpty) match
        case None => Left(ConfigError.MissingEnvironmentName(EnvironmentVariable))
        case Some(n) if !EnvName.matches(n) => Left(ConfigError.InvalidEnvironmentName(n))
        case Some(n) => Right(n)
      bound <- boundVariables(classLoader)
      merged <-
        try
          val required = ConfigParseOptions.defaults().setAllowMissing(false)
          val optional = ConfigParseOptions.defaults().setAllowMissing(true)
          val base = ConfigFactory.parseResources(classLoader, BaseResource, required)
          val overrides = ConfigFactory.parseResources(classLoader, OverridesResource, required)
          // A missing environment file parses as an empty layer.
          val envFile =
            ConfigFactory.parseResources(classLoader, environmentResource(name), optional)
          val source = env
            .filter((k, v) => k != null && v != null && bound.contains(k))
            .foldLeft(ConfigFactory.empty()) { case (acc, (k, v)) =>
              acc.withValue(ConfigUtil.joinPath(k), ConfigValueFactory.fromAnyRef(v))
            }
          // The filtered environment sits at the root as the lowest-precedence fallback, so
          // ${?VAR} resolves against it; noSystem() keeps the real process environment out.
          // (Typesafe Config cannot resolve merged values against a separate resolveWith source.)
          // withOnlyPath then drops the root-level variables from the result.
          Right(
            overrides
              .withFallback(envFile)
              .withFallback(base)
              .withFallback(source)
              .resolve(ConfigResolveOptions.noSystem())
              .withOnlyPath(ConfigCursor.Root)
          )
        catch case NonFatal(e) => Left(ConfigError.ParseFailure(String.valueOf(e.getMessage)))
    yield (name, merged)

  /** Loads one section. */
  def loadSection[A](
      section: ConfigSection[A],
      env: Map[String, String],
      classLoader: ClassLoader = getClass.getClassLoader
  ): Either[ConfigError, A] =
    loadRaw(env, classLoader).flatMap((_, config) =>
      ConfigSection.from(section, new ConfigCursor(config))
    )

  /** Loads the complete configuration. */
  def load(
      env: Map[String, String],
      classLoader: ClassLoader = getClass.getClassLoader
  ): Either[ConfigError, ScholarChainConfig] =
    loadRaw(env, classLoader).flatMap((name, config) =>
      ConfigSection.from(ScholarChainConfig.section(name), new ConfigCursor(config))
    )

  /** Loads the complete configuration from the process environment (filtered as above). */
  def loadFromProcessEnvironment(): Either[ConfigError, ScholarChainConfig] = load(sys.env)
