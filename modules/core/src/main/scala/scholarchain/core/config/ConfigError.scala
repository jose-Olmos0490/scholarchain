package scholarchain.core.config

/** A required configuration key that has no value in any layer.
  *
  * @param path
  *   full HOCON path, for example `scholarchain.storage.rawBucket`
  * @param environmentVariable
  *   the environment variable that can supply it
  */
final case class MissingKey(path: String, environmentVariable: String)

/** A configuration value that is present but unusable. */
final case class InvalidSetting(path: String, reason: String)

/** Typed configuration-loading failure. */
enum ConfigError:
  /** `SCHOLARCHAIN_ENV` is unset or blank. The environment name is always required, even though
    * its versioned file is optional, so that every loaded configuration names its environment.
    */
  case MissingEnvironmentName(variable: String)

  /** The environment name is not `[a-z][a-z0-9-]*`. */
  case InvalidEnvironmentName(name: String)

  /** A layer could not be parsed or resolved. */
  case ParseFailure(reason: String)

  /** One or more required keys are missing and/or values are invalid. Lists every problem. */
  case Invalid(missingKeys: Vector[MissingKey], invalidSettings: Vector[InvalidSetting])

  def message: String = this match
    case MissingEnvironmentName(v) => s"environment variable $v must name the environment"
    case InvalidEnvironmentName(n) => s"environment name '$n' must match [a-z][a-z0-9-]*"
    case ParseFailure(r) => s"configuration could not be parsed: $r"
    case Invalid(missing, invalid) =>
      val m = missing.map(k => s"missing ${k.path} (env ${k.environmentVariable})")
      val i = invalid.map(s => s"invalid ${s.path}: ${s.reason}")
      (m ++ i).mkString("configuration errors:\n  ", "\n  ", "")
