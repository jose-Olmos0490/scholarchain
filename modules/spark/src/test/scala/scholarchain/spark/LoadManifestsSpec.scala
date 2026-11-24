package scholarchain.spark

import java.nio.file.{Files, Paths}
import org.apache.hadoop.conf.Configuration
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.schema.SchemaVersions
import scholarchain.spark.run.{LoadManifests, WrittenTable}

/** Load-manifest locations are qualified through the same Hadoop FileSystem as file paths. */
class LoadManifestsSpec extends AnyFunSuite:
  test(
    "relative table location is qualified (file: scheme, absolute) and prefixes its file paths"
  ) {
    val runHex = "abc123"
    val base = Paths.get("target", s"lm-spec-${System.nanoTime()}")
    val part = base.resolve("coreness").resolve(s"run_id=$runHex")
    Files.createDirectories(part)
    Files.write(part.resolve("part-0.parquet"), Array[Byte](1, 2, 3))
    val relative = base.resolve("coreness").toString
    assert(!Paths.get(relative).isAbsolute)

    val t = WrittenTable(
      "coreness",
      relative,
      "CorenessRecord",
      SchemaVersions.CorenessRecord,
      windowed = false,
      Vector.empty,
      1L,
      1,
      None,
      Vector.empty
    )
    val conf = new Configuration()
    val ms = LoadManifests.manifests(Vector(t), "run:" + runHex, "snap", conf)
    val tables = ms.flatMap(_.tables).filter(_.tableName == "coreness")
    assert(tables.nonEmpty)
    tables.foreach { lt =>
      assert(lt.location.startsWith("file:/"), lt.location)
      assert(lt.location.stripPrefix("file:").startsWith("/"))
      assert(lt.files.size == 1)
      lt.files.foreach(f =>
        assert(f.path.startsWith(lt.location + "/"), s"${f.path} vs ${lt.location}")
      )
    }
    assert(LoadManifests.qualify(tables.head.location, conf) == tables.head.location)
  }
