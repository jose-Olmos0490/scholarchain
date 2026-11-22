package scholarchain.spark.run

import java.security.MessageDigest
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import scholarchain.core.json.WireCodec
import scholarchain.core.model.{
  LoadFile,
  LoadManifest,
  LoadTable,
  LoadTarget,
  Provenance,
  SourceType
}
import scholarchain.core.schema.SchemaVersions

/** One load manifest per target (`load-manifest-v1`): which tables of this run a store loads,
  * with their locations, record types, schema versions, partition columns, row counts and every
  * Parquet file with its size and SHA-256. No store-specific format is described or produced
  * (Neptune CSV, DynamoDB items, vectors belong to the storage loaders, Phase 7/8).
  *
  * Only tables whose rows are core records are listed (`wt_invariant` is a Spark-internal check
  * table and is not loaded anywhere).
  */
object LoadManifests:
  /** Table names per target. S3 receives every core-record table. */
  val Neptune: Vector[String] = Vector(
    "researcher",
    "work",
    "venue",
    "topic",
    "institution",
    "authored",
    "has_topic",
    "coauthored_with",
    "researcher_collaboration",
    "components",
    "community_membership",
    "cross_community_edge",
    "bridge_measures",
    "coreness"
  )
  val DynamoDb: Vector[String] = Vector(
    "researcher",
    "coauthored_with",
    "coauthored_with_support",
    "researcher_collaboration",
    "components",
    "community_membership",
    "bridge_measures",
    "coreness",
    "path_evidence",
    "window_summary",
    "researcher_window_change"
  )
  val S3VectorsInput: Vector[String] = Vector("semantic_fact")

  /** Tables of each target, in target order. */
  def selection(tables: Vector[WrittenTable]): Vector[(LoadTarget, Vector[WrittenTable])] =
    val core = tables.filter(t => SchemaVersions.supportedFor(t.recordType).nonEmpty)
    def pick(names: Vector[String]) = names.flatMap(n => core.find(_.name == n))
    Vector(
      LoadTarget.NEPTUNE -> pick(Neptune),
      LoadTarget.DYNAMODB -> pick(DynamoDb),
      LoadTarget.S3VECTORS_INPUT -> pick(S3VectorsInput),
      LoadTarget.S3 -> core
    )

  /** `sha256:<hex>` of a file, streamed on the driver (bounded run; see JOBS.md risks). */
  def sha256(path: Path, conf: Configuration): String =
    val in = path.getFileSystem(conf).open(path)
    try
      val md = MessageDigest.getInstance("SHA-256")
      val buf = new Array[Byte](1 << 16)
      var n = in.read(buf)
      while n >= 0 do
        if n > 0 then md.update(buf, 0, n)
        n = in.read(buf)
      "sha256:" + md.digest().map(b => f"${b & 0xff}%02x").mkString
    finally in.close()

  /** The Parquet files of this run of one table, sorted by path. */
  def files(t: WrittenTable, runHex: String, conf: Configuration): Vector[LoadFile] =
    val root = new Path(t.path)
    val fs = root.getFileSystem(conf)
    if !fs.exists(root) then Vector.empty
    else
      val it = fs.listFiles(root, true)
      val b = Vector.newBuilder[LoadFile]
      while it.hasNext do
        val st = it.next()
        val p = st.getPath
        if p.getName.endsWith(".parquet") && p.getParent.getName == s"run_id=$runHex" then
          b += LoadFile(p.toString, st.getLen, sha256(p, conf))
      b.result().sortBy(_.path)

  def manifests(
      tables: Vector[WrittenTable],
      runId: String,
      snapshot: String,
      conf: Configuration
  ): Vector[LoadManifest] =
    val runHex = runId.stripPrefix("run:")
    val fileCache = scala.collection.mutable.Map.empty[String, Vector[LoadFile]]
    selection(tables).map { (target, ts) =>
      LoadManifest(
        schemaVersion = SchemaVersions.LoadManifest,
        target = target,
        runId = runId,
        tables = ts.map(t =>
          LoadTable(
            tableName = t.name,
            recordType = t.recordType,
            recordSchemaVersion = t.schemaVersion,
            location = t.path,
            format = "parquet",
            partitionColumns = t.partitionColumns,
            rowCount = t.rowCount,
            files = fileCache.getOrElseUpdate(t.name, files(t, runHex, conf))
          )
        ),
        provenance = Provenance(
          schemaVersion = SchemaVersions.Provenance,
          sourceDataset = SourceType.SCHOLARCHAIN,
          sourceRecordId = runId,
          snapshot = snapshot,
          runId = runId,
          policyVersion = None,
          modelVersion = None,
          promptVersion = None,
          derivedFrom = Vector.empty
        )
      )
    }

  def json(m: LoadManifest): String =
    WireCodec.encodeValidated(m).fold(e => sys.error(s"invalid load manifest: $e"), identity)

  /** `graph/load-manifests/target=<t>/run_id=<hex>/load-manifest.json`. */
  def relativePath(target: LoadTarget, runHex: String): String =
    val t = target match
      case LoadTarget.NEPTUNE => "neptune"
      case LoadTarget.DYNAMODB => "dynamodb"
      case LoadTarget.S3VECTORS_INPUT => "s3vectors-input"
      case LoadTarget.S3 => "s3"
    s"graph/load-manifests/target=$t/run_id=$runHex/load-manifest.json"
