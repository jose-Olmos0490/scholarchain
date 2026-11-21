package scholarchain.hadoop.io

import org.apache.hadoop.conf.Configuration
import scholarchain.core.model.SourceType
import upickle.default.{ReadWriter, read, write}

/** Kind of a raw input file, decided by the driver from its dataset directory and name. */
enum InputKind derives ReadWriter:
  case DblpXml, OpenAlexWorks, OpenAlexAuthors, CrossrefWorks, OrcidRecord

/** One raw input file of the run.
  *
  * @param path
  *   fully qualified path string (as Hadoop lists it)
  * @param relative
  *   path relative to the input root (the run-manifest `location`)
  * @param checksum
  *   `sha256:<hex>` of the file bytes
  */
final case class InputFile(
    path: String,
    relative: String,
    dataset: SourceType,
    kind: InputKind,
    snapshot: String,
    checksum: String
) derives ReadWriter

/** Job configuration keys shared by the driver and the tasks. Tasks read every parameter from the
  * job `Configuration` (set once by the driver); they never read the process environment.
  */
object PipelineConf:
  val RunId = "scholarchain.run.id"
  val SeedSnapshot = "scholarchain.run.seedSnapshot"
  val Inputs = "scholarchain.run.inputs"
  val MaxBlockSize = "scholarchain.hadoop.maxBlockSize"
  val LargeTeamThreshold = "scholarchain.hadoop.largeTeamThreshold"
  val MaxPairExpansionTeamSize = "scholarchain.hadoop.maxPairExpansionTeamSize"
  val DblpRecordTypes = "scholarchain.hadoop.dblpRecordTypes"
  val YearFrom = "scholarchain.hadoop.yearFrom"
  val YearTo = "scholarchain.hadoop.yearTo"
  val DtdCacheName = "scholarchain.hadoop.dtdCacheName"
  val DtdUri = "scholarchain.hadoop.dtdUri"
  val CapBucketLimit = "scholarchain.hadoop.capBucketLimit"
  val JobSlug = "scholarchain.job.slug"

  /** Name of the DTD inside the distributed cache (the URI fragment / symlink name). */
  val DefaultDtdCacheName = "scholarchain-dblp.dtd"

  def setInputs(conf: Configuration, inputs: Seq[InputFile]): Unit =
    conf.set(Inputs, write(inputs.toVector))

  def inputs(conf: Configuration): Vector[InputFile] =
    Option(conf.get(Inputs)).map(read[Vector[InputFile]](_)).getOrElse(Vector.empty)

  def required(conf: Configuration, key: String): String =
    val v = conf.get(key)
    if v == null || v.trim.isEmpty then
      throw new IllegalStateException(s"job configuration key '$key' is not set by the driver")
    v

  def requiredInt(conf: Configuration, key: String): Int = required(conf, key).trim.toInt

  /** Finds the raw input that a split path belongs to. Paths are compared after URI
    * normalization, so `file:/x` and `file:///x` match.
    */
  def inputFor(conf: Configuration, splitPath: String): Option[InputFile] =
    val wanted = normalizePath(splitPath)
    inputs(conf).find(i => normalizePath(i.path) == wanted)

  def normalizePath(p: String): String =
    val u = new org.apache.hadoop.fs.Path(p).toUri
    val scheme = Option(u.getScheme).getOrElse("file")
    val auth = Option(u.getAuthority).getOrElse("")
    s"$scheme://$auth${u.getPath}"
