package scholarchain.core.config

/** A typed configuration section. Modules load only the sections they need, so a Hadoop job is
  * not blocked by a missing model ID.
  */
trait ConfigSection[A]:
  def read(c: ConfigCursor): A

object ConfigSection:
  /** Reads the section; any missing key or invalid value yields [[ConfigError.Invalid]] listing
    * all of them.
    */
  def from[A](section: ConfigSection[A], cursor: ConfigCursor): Either[ConfigError, A] =
    val value = section.read(cursor)
    val (missing, invalid) = cursor.problems
    if missing.isEmpty && invalid.isEmpty then Right(value)
    else Left(ConfigError.Invalid(missing, invalid))

/** Configuration file format version (`scholarchain.config.schemaVersion`). */
object ConfigSchema:
  val Current = "config-v1"
  val Supported: Set[String] = Set(Current)

final case class ApplicationConfig(
    name: String,
    configSchemaVersion: String,
    awsRegion: String,
    awsAccountId: String
)

object ApplicationConfig extends ConfigSection[ApplicationConfig]:
  private val Region = "[a-z]{2}(-[a-z]+)+-[0-9]".r
  private val Account = "[0-9]{12}".r

  def read(c: ConfigCursor): ApplicationConfig =
    val schema = c.string("config.schemaVersion", "SCHOLARCHAIN_CONFIG_SCHEMA_VERSION")
    if schema.nonEmpty && !ConfigSchema.Supported.contains(schema) then
      c.fail(
        "config.schemaVersion",
        s"unsupported '$schema'; supported: ${ConfigSchema.Supported.mkString(", ")}"
      )
    val region = c.string("application.awsRegion", "AWS_REGION")
    if region.nonEmpty && !Region.matches(region) then
      c.fail("application.awsRegion", s"'$region' is not an AWS Region code")
    val account = c.string("application.awsAccountId", "SCHOLARCHAIN_AWS_ACCOUNT_ID")
    if account.nonEmpty && !Account.matches(account) then
      c.fail("application.awsAccountId", "must be 12 digits")
    ApplicationConfig(
      name = c.string("application.name", "SCHOLARCHAIN_APPLICATION_NAME"),
      configSchemaVersion = schema,
      awsRegion = region,
      awsAccountId = account
    )

final case class StorageConfig(
    rawBucket: String,
    curatedBucket: String,
    graphBucket: String,
    ragBucket: String,
    vectorBucket: String
)

object StorageConfig extends ConfigSection[StorageConfig]:
  def read(c: ConfigCursor): StorageConfig = StorageConfig(
    rawBucket = c.string("storage.rawBucket", "RAW_BUCKET"),
    curatedBucket = c.string("storage.curatedBucket", "CURATED_BUCKET"),
    graphBucket = c.string("storage.graphBucket", "GRAPH_BUCKET"),
    ragBucket = c.string("storage.ragBucket", "RAG_BUCKET"),
    vectorBucket = c.string("storage.vectorBucket", "VECTOR_BUCKET")
  )

final case class NeptuneConfig(endpoint: String, port: Int, queryTimeoutMs: Int)

object NeptuneConfig extends ConfigSection[NeptuneConfig]:
  def read(c: ConfigCursor): NeptuneConfig = NeptuneConfig(
    endpoint = c.string("neptune.endpoint", "NEPTUNE_ENDPOINT"),
    port = c.int("neptune.port", "NEPTUNE_PORT", min = 1, max = 65535),
    queryTimeoutMs = c.int("neptune.queryTimeoutMs", "NEPTUNE_QUERY_TIMEOUT_MS", min = 1)
  )

final case class VectorIndexConfig(
    researchers: String,
    communities: String,
    abstracts: String,
    paths: String,
    topicInstitutions: String
)

object VectorIndexConfig extends ConfigSection[VectorIndexConfig]:
  def read(c: ConfigCursor): VectorIndexConfig = VectorIndexConfig(
    researchers = c.string("vectorIndexes.researchers", "VECTOR_INDEX_RESEARCHERS"),
    communities = c.string("vectorIndexes.communities", "VECTOR_INDEX_COMMUNITIES"),
    abstracts = c.string("vectorIndexes.abstracts", "VECTOR_INDEX_ABSTRACTS"),
    paths = c.string("vectorIndexes.paths", "VECTOR_INDEX_PATHS"),
    topicInstitutions =
      c.string("vectorIndexes.topicInstitutions", "VECTOR_INDEX_TOPIC_INSTITUTIONS")
  )

/** The approved DBLP functional-run subset (manifest `datasets.dblp.subset_rule`): keep a record
  * iff its element name is in `recordTypes` and its integer `<year>` lies in `years` (inclusive).
  * At most `maxWorks` works enter the run.
  *
  * @param recordTypes
  *   DBLP record element names, each one declared by the DBLP DTD
  * @param years
  *   inclusive year window (`DBLP_SUBSET_START_YEAR`, `DBLP_SUBSET_END_YEAR`)
  * @param maxWorks
  *   cap on the number of works in the run, > 0 (`DBLP_SUBSET_MAX_WORKS`)
  */
final case class DblpSubsetConfig(recordTypes: Vector[String], years: YearWindow, maxWorks: Int)

/** Dataset run parameters. */
final case class DatasetConfig(dblpSubset: DblpSubsetConfig)

object DatasetConfig extends ConfigSection[DatasetConfig]:
  /** Record element names declared by the root element of the DBLP DTD
    * (`datasets/samples/dblp/dblp-2023-06-28.dtd`: `<!ELEMENT dblp (article|inproceedings|...)>`).
    * A source-format fact, not a tunable parameter: the subset may only name these.
    */
  val DblpRecordElements: Set[String] = Set(
    "article",
    "inproceedings",
    "proceedings",
    "book",
    "incollection",
    "phdthesis",
    "mastersthesis",
    "www",
    "person",
    "data"
  )

  /** Reads the DBLP subset year window. `None` when a bound is missing or invalid, or when
    * start > end; every such problem is recorded. A missing or invalid bound is reported only as
    * itself, never also as a window error against a placeholder.
    */
  def readSubsetYears(c: ConfigCursor): Option[YearWindow] =
    val start = c.intOption("datasets.dblpSubset.startYear", "DBLP_SUBSET_START_YEAR")
    val end = c.intOption("datasets.dblpSubset.endYear", "DBLP_SUBSET_END_YEAR")
    (start, end) match
      case (Some(s), Some(e)) =>
        YearWindow.of(s, e) match
          case Right(w) => Some(w)
          case Left(reason) =>
            c.fail("datasets.dblpSubset", reason)
            None
      case _ => None

  /** Reads `datasets.dblpSubset.recordTypes`. `None` when missing or invalid (recorded). */
  def readSubsetRecordTypes(c: ConfigCursor): Option[Vector[String]] =
    recordTypes(c, "datasets.dblpSubset.recordTypes", "DBLP_SUBSET_RECORD_TYPES")

  /** Reads a list of DBLP record element names; unknown names are recorded as invalid. */
  private[config] def recordTypes(
      c: ConfigCursor,
      key: String,
      envVar: String
  ): Option[Vector[String]] =
    c.stringList(key, envVar).flatMap { types =>
      val unknown = types.filterNot(DblpRecordElements.contains)
      if unknown.nonEmpty then
        c.fail(
          key,
          s"not DBLP record elements: ${unknown.mkString(", ")}; allowed: " +
            DblpRecordElements.toVector.sorted.mkString(", ")
        )
        None
      else Some(types)
    }

  def read(c: ConfigCursor): DatasetConfig =
    val recordTypes = readSubsetRecordTypes(c).getOrElse(Vector.empty)
    val years = readSubsetYears(c).getOrElse(YearWindow.Placeholder)
    val maxWorks = c.int("datasets.dblpSubset.maxWorks", "DBLP_SUBSET_MAX_WORKS", min = 1)
    DatasetConfig(DblpSubsetConfig(recordTypes, years, maxWorks))

/** Hadoop MapReduce ingestion parameters (`scholarchain.hadoop`).
  *
  * Defaults for `maxBlockSize` (200), `largeTeamThreshold` (50), `maxPairExpansionTeamSize`
  * (2000) and the two codecs live in `reference.conf` (human-approved, Phase 04 work order A).
  * `dblpRecordTypes`, `yearFrom` and `yearTo` have no value of their own: when unset, the
  * loader uses `datasets.dblpSubset.recordTypes/startYear/endYear` (the manifest subset rule),
  * and a problem with those is reported once, at the `datasets.dblpSubset` path. When set, they
  * may narrow but never widen the subset. `numReducers` and `dblpDtdPath` have no default.
  *
  * Validation:
  *   - `numReducers >= 1`, `maxBlockSize >= 2`, `largeTeamThreshold >= 1`,
  *     `maxPairExpansionTeamSize >= 2` and `maxPairExpansionTeamSize >= largeTeamThreshold`
  *   - each codec is one of [[HadoopConfig.SupportedCodecs]]
  *   - `dblpRecordTypes`: non-blank, no duplicates, DBLP DTD record elements, and a subset of
  *     `datasets.dblpSubset.recordTypes` when that key is valid
  *   - `yearFrom <= yearTo`, and the window lies inside the DBLP subset years when those are
  *     valid
  *   - `dblpDtdPath`: non-blank, ends in `.dtd`, not an `http(s)` URL (DTDs are never fetched
  *     from the network)
  */
final case class HadoopConfig(
    numReducers: Int,
    maxBlockSize: Int,
    largeTeamThreshold: Int,
    maxPairExpansionTeamSize: Int,
    intermediateCodec: String,
    outputCodec: String,
    dblpRecordTypes: Vector[String],
    yearFrom: Int,
    yearTo: Int,
    dblpDtdPath: String
):
  /** `yearFrom..yearTo` (valid after a successful load). */
  def years: YearWindow = YearWindow.of(yearFrom, yearTo).getOrElse(YearWindow.Placeholder)

object HadoopConfig extends ConfigSection[HadoopConfig]:
  /** Hadoop's built-in compression codec classes (`org.apache.hadoop.io.compress`). A format
    * fact of the pinned Hadoop line, not a tunable: the configured codecs must name one of them.
    */
  val SupportedCodecs: Set[String] = Set(
    "org.apache.hadoop.io.compress.DefaultCodec",
    "org.apache.hadoop.io.compress.GzipCodec",
    "org.apache.hadoop.io.compress.BZip2Codec",
    "org.apache.hadoop.io.compress.Lz4Codec",
    "org.apache.hadoop.io.compress.SnappyCodec",
    "org.apache.hadoop.io.compress.ZStandardCodec"
  )

  private def codec(c: ConfigCursor, key: String, envVar: String): String =
    val v = c.string(key, envVar)
    if v.nonEmpty && !SupportedCodecs.contains(v) then
      c.fail(
        key,
        s"'$v' is not a supported codec; supported: ${SupportedCodecs.toVector.sorted.mkString(", ")}"
      )
    v

  def read(c: ConfigCursor): HadoopConfig =
    val numReducers =
      c.int("hadoop.numReducers", "SCHOLARCHAIN_HADOOP_NUM_REDUCERS", min = 1)
    val maxBlockSize =
      c.int("hadoop.maxBlockSize", "SCHOLARCHAIN_HADOOP_MAX_BLOCK_SIZE", min = 2)
    val largeTeam =
      c.intOption("hadoop.largeTeamThreshold", "SCHOLARCHAIN_HADOOP_LARGE_TEAM_THRESHOLD", min = 1)
    val maxExpansion = c.intOption(
      "hadoop.maxPairExpansionTeamSize",
      "SCHOLARCHAIN_HADOOP_MAX_PAIR_EXPANSION_TEAM_SIZE",
      min = 2
    )
    for l <- largeTeam; m <- maxExpansion if m < l do
      c.fail(
        "hadoop.maxPairExpansionTeamSize",
        s"must be >= largeTeamThreshold ($l), got $m"
      )
    val intermediateCodec =
      codec(c, "hadoop.intermediateCodec", "SCHOLARCHAIN_HADOOP_INTERMEDIATE_CODEC")
    val outputCodec = codec(c, "hadoop.outputCodec", "SCHOLARCHAIN_HADOOP_OUTPUT_CODEC")

    // Record types: the hadoop key narrows the subset when set; otherwise the subset value is
    // read through the same reader as DatasetConfig, so a problem is reported once, at its path.
    val recordTypes =
      if c.has("hadoop.dblpRecordTypes") then
        DatasetConfig
          .recordTypes(c, "hadoop.dblpRecordTypes", "SCHOLARCHAIN_HADOOP_DBLP_RECORD_TYPES")
          .map { types =>
            c.probe(DatasetConfig.readSubsetRecordTypes).foreach { subset =>
              val outside = types.filterNot(subset.contains)
              if outside.nonEmpty then
                c.fail(
                  "hadoop.dblpRecordTypes",
                  s"not in the DBLP subset record types (${subset.mkString(",")}): " +
                    outside.mkString(", ")
                )
            }
            types
          }
      else DatasetConfig.readSubsetRecordTypes(c)

    // Years: likewise, the subset window unless a hadoop bound is set.
    val (yearFrom, yearTo) =
      if !c.has("hadoop.yearFrom") && !c.has("hadoop.yearTo") then
        val w = DatasetConfig.readSubsetYears(c)
        (w.map(_.startYear), w.map(_.endYear))
      else
        val from =
          if c.has("hadoop.yearFrom") then
            c.intOption("hadoop.yearFrom", "SCHOLARCHAIN_HADOOP_YEAR_FROM")
          else c.intOption("datasets.dblpSubset.startYear", "DBLP_SUBSET_START_YEAR")
        val to =
          if c.has("hadoop.yearTo") then c.intOption("hadoop.yearTo", "SCHOLARCHAIN_HADOOP_YEAR_TO")
          else c.intOption("datasets.dblpSubset.endYear", "DBLP_SUBSET_END_YEAR")
        val checked = for f <- from; t <- to yield YearWindow.of(f, t) match
          case Left(reason) =>
            c.fail("hadoop.yearFrom,yearTo", reason)
            None
          case Right(w) =>
            c.probe(DatasetConfig.readSubsetYears).foreach { subset =>
              if !subset.contains(w) then
                c.fail(
                  "hadoop.yearFrom,yearTo",
                  s"years ${w.render} lie outside the DBLP subset years ${subset.render}"
                )
            }
            Some(w)
        val w = checked.flatten
        (w.map(_.startYear), w.map(_.endYear))

    val dtd = c.string("hadoop.dblpDtdPath", "SCHOLARCHAIN_HADOOP_DBLP_DTD_PATH")
    if dtd.nonEmpty then
      val lower = dtd.toLowerCase(java.util.Locale.ROOT)
      if lower.startsWith("http://") || lower.startsWith("https://") then
        c.fail("hadoop.dblpDtdPath", "must be a file or object-store path, not an http(s) URL")
      else if !lower.endsWith(".dtd") then
        c.fail("hadoop.dblpDtdPath", s"'$dtd' does not name a .dtd file")

    HadoopConfig(
      numReducers = numReducers,
      maxBlockSize = maxBlockSize,
      largeTeamThreshold = largeTeam.getOrElse(0),
      maxPairExpansionTeamSize = maxExpansion.getOrElse(0),
      intermediateCodec = intermediateCodec,
      outputCodec = outputCodec,
      dblpRecordTypes = recordTypes.getOrElse(Vector.empty),
      yearFrom = yearFrom.getOrElse(0),
      yearTo = yearTo.getOrElse(0),
      dblpDtdPath = dtd
    )

/** Coauthorship-graph parameters.
  *
  * Analysis windows come in two kinds:
  *
  *   - `windows` (`graph.windows`): the fixed windows, each with start <= end, sorted by start
  *     year, non-overlapping, and each inside the DBLP subset years. Because of the last rule this
  *     section also reads `datasets.dblpSubset.startYear/endYear`; the containment check runs only
  *     when both the windows and the subset years are valid, so one bad value is reported once.
  *   - `allYearsWindow`: present iff `graph.includeAllYearsWindow = true`. It spans the DBLP
  *     subset years and is deliberately outside the non-overlap rule, so it coexists with the
  *     fixed windows. A fixed window equal to it is rejected, so `(windowStartYear,
  *     windowEndYear)` identifies exactly one analysis window in every output record.
  *
  * Window membership (frozen rule, [[windowsFor]]): a work with year `y` belongs to every
  * analysis window whose `[start, end]` contains `y`; a work with no year belongs only to the
  * all-years window (to none when it is disabled).
  *
  * @param representativeWorkLimit
  *   K, the maximum number of `CoauthorEdge.representativeWorkIds` (>= 1)
  */
final case class GraphConfig(
    windows: Vector[YearWindow],
    coauthorPolicyVersion: String,
    allYearsWindow: Option[YearWindow],
    representativeWorkLimit: Int
):
  /** The all-years window (if enabled) first, then the fixed windows in order. */
  def analysisWindows: Vector[YearWindow] = allYearsWindow.toVector ++ windows

  def isAllYearsWindow(w: YearWindow): Boolean = allYearsWindow.contains(w)

  /** The analysis windows a work with the given year belongs to (see the class comment). */
  def windowsFor(year: Option[Int]): Vector[YearWindow] = year match
    case None => allYearsWindow.toVector
    case Some(y) => analysisWindows.filter(_.containsYear(y))

object GraphConfig extends ConfigSection[GraphConfig]:
  def read(c: ConfigCursor): GraphConfig =
    val subset = DatasetConfig.readSubsetYears(c)
    val windows = c.yearWindowsOption("graph.windows", "SCHOLARCHAIN_GRAPH_WINDOWS")
    windows.foreach(ws =>
      YearWindow.seriesViolations(ws, subset).foreach(c.fail("graph.windows", _))
    )
    val policy =
      c.string("graph.coauthorPolicyVersion", "SCHOLARCHAIN_GRAPH_COAUTHOR_POLICY_VERSION")
    val includeAll = c.booleanOption(
      "graph.includeAllYearsWindow",
      "SCHOLARCHAIN_GRAPH_INCLUDE_ALL_YEARS_WINDOW"
    )
    val allYears = if includeAll.contains(true) then subset else None
    for all <- allYears; ws <- windows; w <- ws if w == all do
      c.fail(
        "graph.windows",
        s"window ${w.render} equals the all-years window; remove it or set " +
          "includeAllYearsWindow = false"
      )
    val k = c.int(
      "graph.representativeWorkLimit",
      "SCHOLARCHAIN_GRAPH_REPRESENTATIVE_WORK_LIMIT",
      min = 1
    )
    GraphConfig(
      windows = windows.getOrElse(Vector.empty),
      coauthorPolicyVersion = policy,
      allYearsWindow = allYears,
      representativeWorkLimit = k
    )

/** Spark analytics job I/O and execution parameters (`scholarchain.spark`).
  *
  * Locations are file paths or object-store URIs (for example `s3://...`); an `http(s)` URL is
  * rejected. `outputRoot` must differ from both input locations, and `checkpointDir` from
  * `outputRoot` (compared after removing trailing `/`).
  *
  * @param hadoopInputRoot
  *   root of the Hadoop export tables the job consumes
  * @param hadoopRunId
  *   optional `run:<64 hex>` selecting one Hadoop run below the root
  * @param matchDecisionsDir
  *   the entity-resolution run directory holding the match decisions
  * @param outputRoot
  *   root of all Spark outputs
  * @param shufflePartitions
  *   `spark.sql.shuffle.partitions` (>= 1)
  * @param checkpointDir
  *   checkpoint directory for iterative algorithms
  * @param checkpointInterval
  *   checkpoint every N iterations (>= 1)
  */
final case class SparkJobConfig(
    hadoopInputRoot: String,
    hadoopRunId: Option[String],
    matchDecisionsDir: String,
    outputRoot: String,
    shufflePartitions: Int,
    checkpointDir: String,
    checkpointInterval: Int
)

object SparkJobConfig extends ConfigSection[SparkJobConfig]:
  private def location(c: ConfigCursor, key: String, envVar: String): String =
    val v = c.string(key, envVar)
    val lower = v.toLowerCase(java.util.Locale.ROOT)
    if lower.startsWith("http://") || lower.startsWith("https://") then
      c.fail(key, "must be a file path or object-store URI, not an http(s) URL")
    v

  private def norm(s: String): String =
    var x = s
    while x.length > 1 && x.endsWith("/") do x = x.dropRight(1)
    x

  def read(c: ConfigCursor): SparkJobConfig =
    val input = location(c, "spark.hadoopInputRoot", "SCHOLARCHAIN_SPARK_HADOOP_INPUT_ROOT")
    val runId = c.optionalString("spark.hadoopRunId")
    runId.foreach { r =>
      if !scholarchain.core.ids.StableId.isValid(r, scholarchain.core.ids.IdKind.Run) then
        c.fail("spark.hadoopRunId", s"'$r' is not a run ID (run:<64 lower-case hex>)")
    }
    val decisions =
      location(c, "spark.matchDecisionsDir", "SCHOLARCHAIN_SPARK_MATCH_DECISIONS_DIR")
    val output = location(c, "spark.outputRoot", "SCHOLARCHAIN_SPARK_OUTPUT_ROOT")
    val partitions =
      c.int("spark.shufflePartitions", "SCHOLARCHAIN_SPARK_SHUFFLE_PARTITIONS", min = 1)
    val checkpoint = location(c, "spark.checkpointDir", "SCHOLARCHAIN_SPARK_CHECKPOINT_DIR")
    val interval =
      c.int("spark.checkpointInterval", "SCHOLARCHAIN_SPARK_CHECKPOINT_INTERVAL", min = 1)
    if output.nonEmpty then
      if input.nonEmpty && norm(output) == norm(input) then
        c.fail("spark.outputRoot", "must differ from spark.hadoopInputRoot")
      if decisions.nonEmpty && norm(output) == norm(decisions) then
        c.fail("spark.outputRoot", "must differ from spark.matchDecisionsDir")
      if checkpoint.nonEmpty && norm(checkpoint) == norm(output) then
        c.fail("spark.checkpointDir", "must differ from spark.outputRoot")
    SparkJobConfig(input, runId, decisions, output, partitions, checkpoint, interval)

/** Canonical-identity parameters for the Spark researcher builder (`graph.identity`).
  *
  * @param version
  *   identity version `v<N>` passed to `StableIds.researcherIdScholarChain`
  * @param maxClusterSize
  *   largest identity cluster that may be merged (>= 1); a larger cluster is not merged and is
  *   recorded as an `IdentityConflict` with reason `CLUSTER_TOO_LARGE`
  */
final case class IdentityConfig(version: String, maxClusterSize: Int)

object IdentityConfig extends ConfigSection[IdentityConfig]:
  def read(c: ConfigCursor): IdentityConfig =
    val version = c.string("graph.identity.version", "SCHOLARCHAIN_GRAPH_IDENTITY_VERSION")
    if version.nonEmpty && !scholarchain.core.ids.IdKind.VersionTag.matches(version) then
      c.fail("graph.identity.version", s"'$version' must match v<N> (v1, v2, ...)")
    IdentityConfig(
      version = version,
      maxClusterSize = c.int(
        "graph.identity.maxClusterSize",
        "SCHOLARCHAIN_GRAPH_IDENTITY_MAX_CLUSTER_SIZE",
        min = 1
      )
    )

/** Connected components by iterative min-label propagation (`graph.components`). */
final case class ComponentsConfig(maxIterations: Int)

object ComponentsConfig extends ConfigSection[ComponentsConfig]:
  def read(c: ConfigCursor): ComponentsConfig = ComponentsConfig(
    c.int(
      "graph.components.maxIterations",
      "SCHOLARCHAIN_GRAPH_COMPONENTS_MAX_ITERATIONS",
      min = 1
    )
  )

/** k-core decomposition by iterative peeling (`graph.kcore`). */
final case class KCoreConfig(maxIterations: Int)

object KCoreConfig extends ConfigSection[KCoreConfig]:
  def read(c: ConfigCursor): KCoreConfig = KCoreConfig(
    c.int("graph.kcore.maxIterations", "SCHOLARCHAIN_GRAPH_KCORE_MAX_ITERATIONS", min = 1)
  )

/** One configured benchmark path query, in the configured direction. */
final case class BenchmarkPair(sourceResearcherId: String, targetResearcherId: String)

/** Batch shortest-path parameters (`graph.paths`). Independent of the runtime bound
  * `retrieval.maxPathDepth`.
  *
  * @param maxDepth
  *   BFS depth bound (>= 1)
  * @param benchmarkPairs
  *   optional `source|target,source|target,...` researcher-ID pairs; absent or blank means none,
  *   in which case `samplePairCount` pairs are drawn with `seed`. Endpoints must be researcher
  *   stable IDs; self-pairs and repeated unordered pairs are rejected
  * @param samplePairCount
  *   number of pairs sampled from the largest component when no benchmark pair is given (>= 1)
  */
final case class PathConfig(
    maxDepth: Int,
    benchmarkPairs: Vector[BenchmarkPair],
    samplePairCount: Int,
    seed: Long
)

object PathConfig extends ConfigSection[PathConfig]:
  import scholarchain.core.ids.{CanonicalPair, IdKind, StableId}

  private val Key = "graph.paths.benchmarkPairs"

  /** Parses the benchmark-pair list; every problem is recorded at [[Key]]. */
  private def pairs(c: ConfigCursor): Vector[BenchmarkPair] =
    c.optionalString(Key) match
      case None => Vector.empty
      case Some(s) =>
        val parsed = s.split(",", -1).toVector.map(_.trim).map { p =>
          p.split("\\|", -1).toVector.map(_.trim) match
            case Vector(a, b) =>
              val bad = Vector(a, b).filterNot(StableId.isValid(_, IdKind.Researcher))
              if bad.nonEmpty then
                Left(s"'$p': not researcher stable IDs: ${bad.map(x => s"'$x'").mkString(", ")}")
              else if a == b then Left(s"'$p': source and target are the same researcher")
              else Right(BenchmarkPair(a, b))
            case _ => Left(s"'$p' is not source|target")
        }
        val errors = parsed.collect { case Left(e) => e }
        errors.foreach(c.fail(Key, _))
        val ok = parsed.collect { case Right(p) => p }
        val keys = ok.map(p => CanonicalPair.of(p.sourceResearcherId, p.targetResearcherId))
        if keys.distinct.size != keys.size then
          c.fail(Key, "contains the same unordered pair more than once")
        if errors.isEmpty then ok else Vector.empty

  def read(c: ConfigCursor): PathConfig =
    val depth = c.int("graph.paths.maxDepth", "SCHOLARCHAIN_GRAPH_PATHS_MAX_DEPTH", min = 1)
    val bench = pairs(c)
    val count = c.int(
      "graph.paths.samplePairCount",
      "SCHOLARCHAIN_GRAPH_PATHS_SAMPLE_PAIR_COUNT",
      min = 1
    )
    val seed = c.long("graph.paths.seed", "SCHOLARCHAIN_GRAPH_PATHS_SEED")
    PathConfig(depth, bench, count, seed)

/** Sampled distance estimate (mean distance, 90th-percentile effective diameter) parameters
  * (`graph.diameter`): `sampleSources` seeded BFS sources per component of at least
  * `minComponentSize` researchers. The result is an estimate and is labelled so.
  */
final case class DiameterConfig(sampleSources: Int, seed: Long, minComponentSize: Int)

object DiameterConfig extends ConfigSection[DiameterConfig]:
  def read(c: ConfigCursor): DiameterConfig = DiameterConfig(
    sampleSources = c.int(
      "graph.diameter.sampleSources",
      "SCHOLARCHAIN_GRAPH_DIAMETER_SAMPLE_SOURCES",
      min = 1
    ),
    seed = c.long("graph.diameter.seed", "SCHOLARCHAIN_GRAPH_DIAMETER_SEED"),
    minComponentSize = c.int(
      "graph.diameter.minComponentSize",
      "SCHOLARCHAIN_GRAPH_DIAMETER_MIN_COMPONENT_SIZE",
      min = 2
    )
  )

/** Bounds on the structured semantic-input fact records (`graph.semanticFacts`).
  *
  * @param topEntityLimit
  *   maximum entries in each top-topics/venues/institutions list (>= 1)
  * @param memberLimit
  *   maximum related researcher IDs listed on one fact (>= 1)
  */
final case class SemanticFactsConfig(topEntityLimit: Int, memberLimit: Int)

object SemanticFactsConfig extends ConfigSection[SemanticFactsConfig]:
  def read(c: ConfigCursor): SemanticFactsConfig = SemanticFactsConfig(
    topEntityLimit = c.int(
      "graph.semanticFacts.topEntityLimit",
      "SCHOLARCHAIN_GRAPH_SEMANTIC_TOP_ENTITY_LIMIT",
      min = 1
    ),
    memberLimit = c.int(
      "graph.semanticFacts.memberLimit",
      "SCHOLARCHAIN_GRAPH_SEMANTIC_MEMBER_LIMIT",
      min = 1
    )
  )

/** All graph-analytics algorithm parameters. Each part is also a section of its own, so a job
  * can load only what it uses. Label-propagation communities use `community.*`; approximate
  * betweenness uses `bridge.sampleSize` (k sampled sources) and `bridge.randomSeed`.
  */
final case class GraphAnalyticsConfig(
    identity: IdentityConfig,
    components: ComponentsConfig,
    kcore: KCoreConfig,
    paths: PathConfig,
    diameter: DiameterConfig,
    semanticFacts: SemanticFactsConfig
)

object GraphAnalyticsConfig extends ConfigSection[GraphAnalyticsConfig]:
  def read(c: ConfigCursor): GraphAnalyticsConfig = GraphAnalyticsConfig(
    identity = IdentityConfig.read(c),
    components = ComponentsConfig.read(c),
    kcore = KCoreConfig.read(c),
    paths = PathConfig.read(c),
    diameter = DiameterConfig.read(c),
    semanticFacts = SemanticFactsConfig.read(c)
  )

/** Entity-resolution thresholds. Required ordering:
  * `rejectThreshold <= llmBandMin <= llmBandMax <= acceptThreshold`.
  */
final case class EntityResolutionConfig(
    policyVersion: String,
    acceptThreshold: Double,
    rejectThreshold: Double,
    llmBandMin: Double,
    llmBandMax: Double
)

object EntityResolutionConfig extends ConfigSection[EntityResolutionConfig]:
  def read(c: ConfigCursor): EntityResolutionConfig =
    val cfg = EntityResolutionConfig(
      policyVersion = c.string("entityResolution.policyVersion", "SCHOLARCHAIN_ER_POLICY_VERSION"),
      acceptThreshold =
        c.unitInterval("entityResolution.acceptThreshold", "SCHOLARCHAIN_ER_ACCEPT_THRESHOLD"),
      rejectThreshold =
        c.unitInterval("entityResolution.rejectThreshold", "SCHOLARCHAIN_ER_REJECT_THRESHOLD"),
      llmBandMin = c.unitInterval("entityResolution.llmBandMin", "SCHOLARCHAIN_ER_LLM_BAND_MIN"),
      llmBandMax = c.unitInterval("entityResolution.llmBandMax", "SCHOLARCHAIN_ER_LLM_BAND_MAX")
    )
    val (missing, invalid) = c.problems
    val touched =
      (missing.map(_.path) ++ invalid.map(_.path)).exists(_.contains(".entityResolution."))
    if !touched && !(cfg.rejectThreshold <= cfg.llmBandMin && cfg.llmBandMin <= cfg.llmBandMax &&
        cfg.llmBandMax <= cfg.acceptThreshold)
    then
      c.fail(
        "entityResolution",
        "thresholds must satisfy rejectThreshold <= llmBandMin <= llmBandMax <= acceptThreshold"
      )
    cfg

/** Bridge-measure parameters (algorithm name, source-sample size, random seed). */
final case class BridgeConfig(algorithm: String, sampleSize: Int, randomSeed: Long)

object BridgeConfig extends ConfigSection[BridgeConfig]:
  def read(c: ConfigCursor): BridgeConfig = BridgeConfig(
    algorithm = c.string("bridge.algorithm", "SCHOLARCHAIN_BRIDGE_ALGORITHM"),
    sampleSize = c.int("bridge.sampleSize", "SCHOLARCHAIN_BRIDGE_SAMPLE_SIZE", min = 1),
    randomSeed = c.long("bridge.randomSeed", "SCHOLARCHAIN_BRIDGE_RANDOM_SEED")
  )

final case class CommunityConfig(algorithm: String, maxIterations: Int)

object CommunityConfig extends ConfigSection[CommunityConfig]:
  def read(c: ConfigCursor): CommunityConfig = CommunityConfig(
    algorithm = c.string("community.algorithm", "SCHOLARCHAIN_COMMUNITY_ALGORITHM"),
    maxIterations =
      c.int("community.maxIterations", "SCHOLARCHAIN_COMMUNITY_MAX_ITERATIONS", min = 1)
  )

final case class RetrievalConfig(
    graphLimit: Int,
    vectorLimit: Int,
    maxPathDepth: Int,
    contextTokenBudget: Int,
    fusionPolicyVersion: String
)

object RetrievalConfig extends ConfigSection[RetrievalConfig]:
  def read(c: ConfigCursor): RetrievalConfig = RetrievalConfig(
    graphLimit = c.int("retrieval.graphLimit", "SCHOLARCHAIN_RETRIEVAL_GRAPH_LIMIT", min = 1),
    vectorLimit = c.int("retrieval.vectorLimit", "SCHOLARCHAIN_RETRIEVAL_VECTOR_LIMIT", min = 0),
    maxPathDepth =
      c.int("retrieval.maxPathDepth", "SCHOLARCHAIN_RETRIEVAL_MAX_PATH_DEPTH", min = 1),
    contextTokenBudget = c.int(
      "retrieval.contextTokenBudget",
      "SCHOLARCHAIN_RETRIEVAL_CONTEXT_TOKEN_BUDGET",
      min = 1
    ),
    fusionPolicyVersion =
      c.string("retrieval.fusionPolicyVersion", "SCHOLARCHAIN_RETRIEVAL_FUSION_POLICY_VERSION")
  )

final case class ModelConfig(
    embeddingModelId: String,
    synthesisModelId: String,
    verificationModelId: String
)

object ModelConfig extends ConfigSection[ModelConfig]:
  def read(c: ConfigCursor): ModelConfig = ModelConfig(
    embeddingModelId = c.string("models.embeddingModelId", "EMBEDDING_MODEL_ID"),
    synthesisModelId = c.string("models.synthesisModelId", "SYNTHESIS_MODEL_ID"),
    verificationModelId = c.string("models.verificationModelId", "VERIFICATION_MODEL_ID")
  )

final case class OperationsConfig(
    synchronousDeadlineMs: Int,
    maxWorkflowRetries: Int,
    maxConcurrentLlmCalls: Int
)

object OperationsConfig extends ConfigSection[OperationsConfig]:
  def read(c: ConfigCursor): OperationsConfig = OperationsConfig(
    synchronousDeadlineMs = c.int(
      "operations.synchronousDeadlineMs",
      "SCHOLARCHAIN_OPS_SYNCHRONOUS_DEADLINE_MS",
      min = 1
    ),
    maxWorkflowRetries =
      c.int("operations.maxWorkflowRetries", "SCHOLARCHAIN_OPS_MAX_WORKFLOW_RETRIES", min = 0),
    maxConcurrentLlmCalls = c.int(
      "operations.maxConcurrentLlmCalls",
      "SCHOLARCHAIN_OPS_MAX_CONCURRENT_LLM_CALLS",
      min = 1
    )
  )

/** The complete ScholarChain configuration. `environment` is the selected environment name. */
final case class ScholarChainConfig(
    environment: String,
    application: ApplicationConfig,
    storage: StorageConfig,
    neptune: NeptuneConfig,
    vectorIndexes: VectorIndexConfig,
    datasets: DatasetConfig,
    hadoop: HadoopConfig,
    graph: GraphConfig,
    entityResolution: EntityResolutionConfig,
    bridge: BridgeConfig,
    community: CommunityConfig,
    retrieval: RetrievalConfig,
    models: ModelConfig,
    operations: OperationsConfig,
    spark: SparkJobConfig,
    analytics: GraphAnalyticsConfig,
    storageRuntime: StorageRuntimeConfig
)

object ScholarChainConfig:
  def section(environment: String): ConfigSection[ScholarChainConfig] =
    new ConfigSection[ScholarChainConfig]:
      def read(c: ConfigCursor): ScholarChainConfig = ScholarChainConfig(
        environment = environment,
        application = ApplicationConfig.read(c),
        storage = StorageConfig.read(c),
        neptune = NeptuneConfig.read(c),
        vectorIndexes = VectorIndexConfig.read(c),
        datasets = DatasetConfig.read(c),
        hadoop = HadoopConfig.read(c),
        graph = GraphConfig.read(c),
        entityResolution = EntityResolutionConfig.read(c),
        bridge = BridgeConfig.read(c),
        community = CommunityConfig.read(c),
        retrieval = RetrievalConfig.read(c),
        models = ModelConfig.read(c),
        operations = OperationsConfig.read(c),
        spark = SparkJobConfig.read(c),
        analytics = GraphAnalyticsConfig.read(c),
        storageRuntime = StorageRuntimeConfig.section(environment).read(c)
      )
