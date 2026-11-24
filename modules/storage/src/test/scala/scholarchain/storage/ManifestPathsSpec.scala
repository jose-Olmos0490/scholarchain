package scholarchain.storage

import scholarchain.core.model.LoadTable
import scholarchain.storage.common.*
import scholarchain.storage.loader.*
import scholarchain.storage.objects.InMemoryObjectStore

/** `S3Loader.relativePath`: relative and scheme-qualified locations and files, in every mix. */
class ManifestPathsSpec extends StorageSuite:
  private val loader = new S3Loader(new InMemoryObjectStore, new InMemoryRejectSink)
  private def table(location: String) =
    LoadTable("t", "R", "v1", location, "parquet", Vector.empty, 1L, Vector.empty)
  private def rel(location: String, file: String) =
    loader.relativePath(table(location), file)

  private val part = "run_id=ab/part-0.parquet"

  test("relative location and relative file") {
    rel("out/t", s"out/t/$part") shouldBe Right(part)
  }

  test("qualified location and qualified file") {
    rel("s3://b/p/t", s"s3://b/p/t/$part") shouldBe Right(part)
    rel("file:/tmp/out/t", s"file:/tmp/out/t/$part") shouldBe Right(part)
  }

  test("qualified location and relative file (rooted at the bucket)") {
    rel("s3://b/p/t", s"p/t/$part") shouldBe Right(part)
  }

  test("relative location and qualified file") {
    rel("p/t", s"s3://b/p/t/$part") shouldBe Right(part)
  }

  test("trailing and duplicate slashes on either side are ignored") {
    rel("s3://b/p/t/", s"s3://b/p/t/$part") shouldBe Right(part)
    rel("s3://b/p/t//", s"s3://b/p//t/$part") shouldBe Right(part)
    rel("p/t", s"p/t//$part/") shouldBe Right(part)
  }

  test("s3, s3a and s3n are the same scheme") {
    rel("s3a://b/p/t", s"s3://b/p/t/$part") shouldBe Right(part)
    rel("s3://b/p/t", s"s3a://b/p/t/$part") shouldBe Right(part)
    rel("s3n://b/p/t", s"s3a://b/p/t/$part") shouldBe Right(part)
  }

  test("file:, file:/ and file:/// name the same local path") {
    rel("file:///tmp/t", s"file:/tmp/t/$part") shouldBe Right(part)
  }

  test("a file outside the location fails clearly") {
    val e = err(rel("s3://b/p/t", "s3://b/p/other/x.parquet"))
    e shouldBe a[StorageError.InvalidManifest]
    e.message should include("not under its location")
    err(rel("p/t", "p/other/x.parquet")) shouldBe a[StorageError.InvalidManifest]
    err(rel("s3://b/p/t", "q/x.parquet")) shouldBe a[StorageError.InvalidManifest]
  }

  test("a different bucket or scheme fails clearly, never silently passes") {
    err(rel("s3://b/p/t", s"s3://other/p/t/$part")).message should include("differs")
    err(rel("s3://b/p/t", s"file:/p/t/$part")).message should include("differs")
  }

  test("a string prefix that is not a path prefix does not match") {
    err(rel("s3://b/p/run_id=ab", "s3://b/p/run_id=abc/x.parquet")) shouldBe a[
      StorageError.InvalidManifest
    ]
    err(rel("p/run_id=ab", "p/run_id=abc/x.parquet")) shouldBe a[StorageError.InvalidManifest]
    rel("p/run_id=abc", "p/run_id=abc/x.parquet") shouldBe Right("x.parquet")
  }

  test("the location itself, or an empty location, is not a file below it") {
    err(rel("s3://b/p/t", "s3://b/p/t/")) shouldBe a[StorageError.InvalidManifest]
    err(rel("s3://b/p/t", "s3://b/p/t")) shouldBe a[StorageError.InvalidManifest]
    err(rel("", "x/y")) shouldBe a[StorageError.InvalidManifest]
  }

  test("dot segments are refused so a path cannot climb out of the location") {
    err(rel("s3://b/p/t", "s3://b/p/t/../u/x.parquet")) shouldBe a[StorageError.InvalidManifest]
    err(rel("p/t", "p/t/./x")) shouldBe a[StorageError.InvalidManifest]
  }
