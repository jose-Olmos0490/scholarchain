package scholarchain.hadoop.io

/** Output layout of the pipeline (Phase 4 work order B item 5).
  *
  * Every table lives at
  * {{{
  *   <outputRoot>/<dataset>/<table>/snapshot=<snapshot>/run_id=<runDigest>/
  * }}}
  * where `runDigest` is the 64-hex digest of the stable run ID `run:<digest>`. The `run:` prefix is
  * left out of the directory name because Hadoop `Path` rejects a `:` in a relative path segment.
  * Part files inside it are named `part-[mr]-NNNNN.gz`.
  */
object Layout:
  private val SafeSegment = "[A-Za-z0-9][A-Za-z0-9._+-]*".r
  private val RunDigest = "[0-9a-f]{64}".r

  /** True when `s` can be used as a snapshot, dataset or table directory segment. */
  def isSafeSegment(s: String): Boolean = s != null && SafeSegment.matches(s)

  /** The digest part of a stable run ID `run:<64 hex>`. */
  def runDigest(runId: String): String =
    val d = Option(runId).map(_.stripPrefix("run:")).getOrElse("")
    require(RunDigest.matches(d), s"not a stable run ID: '$runId'")
    d

  /** Relative directory of one table for one snapshot and run. */
  def tableDir(dataset: String, table: String, snapshot: String, runId: String): String =
    require(isSafeSegment(dataset), s"unsafe dataset segment '$dataset'")
    require(isSafeSegment(table), s"unsafe table segment '$table'")
    require(isSafeSegment(snapshot), s"unsafe snapshot label '$snapshot'")
    s"$dataset/$table/snapshot=$snapshot/run_id=${runDigest(runId)}"

  /** MultipleOutputs base path (the part-file prefix) inside a table directory. */
  def partBase(tableDir: String): String = s"$tableDir/part"

  /** Parsed location of a part file inside the layout. */
  final case class TableRef(dataset: String, table: String, snapshot: String, runDigest: String)

  /** Parses `.../<dataset>/<table>/snapshot=<s>/run_id=<d>/[year=<y>/]<file>` from a path string.
    */
  def parseTableRef(path: String): Option[TableRef] =
    val segs = path.split('/').toVector.filter(_.nonEmpty)
    val i = segs.lastIndexWhere(_.startsWith("run_id="))
    if i < 3 || !segs(i - 1).startsWith("snapshot=") then None
    else
      Some(
        TableRef(
          dataset = segs(i - 3),
          table = segs(i - 2),
          snapshot = segs(i - 1).stripPrefix("snapshot="),
          runDigest = segs(i).stripPrefix("run_id=")
        )
      )

  /** Name of the run-scoped success marker, written by the driver after the job succeeded. */
  val SuccessMarker = "_SUCCESS"

  /** Name of the counters file written next to each table's part files. */
  val CountersFile = "counters.json"
