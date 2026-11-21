package scholarchain.core

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.config.*

/** `scholarchain.hadoop` section: defaults, subset fallback, overrides and validation. The
  * process environment is never read: every test passes its own map.
  */
class HadoopConfigSpec extends AnyFunSuite with Matchers:

  private def env(name: String, vars: (String, String)*): Map[String, String] =
    Map(ConfigLoader.EnvironmentVariable -> name) ++ vars

  /** The DBLP subset (2010-2012, article and inproceedings) plus the two keys with no default. */
  private val base: Map[String, String] = Map(
    "DBLP_SUBSET_RECORD_TYPES" -> "article,inproceedings",
    "DBLP_SUBSET_START_YEAR" -> "2010",
    "DBLP_SUBSET_END_YEAR" -> "2012",
    "SCHOLARCHAIN_HADOOP_NUM_REDUCERS" -> "4",
    "SCHOLARCHAIN_HADOOP_DBLP_DTD_PATH" -> "datasets/samples/dblp/dblp-2023-06-28.dtd"
  )

  private def load(vars: (String, String)*): Either[ConfigError, HadoopConfig] =
    ConfigLoader.loadSection(HadoopConfig, env("empty-test") ++ base ++ vars)

  private def loaded(vars: (String, String)*): HadoopConfig =
    load(vars*).fold(e => fail(e.message), identity)

  private def invalid(vars: (String, String)*): Vector[InvalidSetting] =
    load(vars*) match
      case Left(ConfigError.Invalid(missing, inv)) =>
        missing shouldBe empty
        inv
      case other => fail(s"expected ConfigError.Invalid, got $other")

  private def invalidPaths(vars: (String, String)*): Vector[String] = invalid(vars*).map(_.path)

  test("defaults: the human-approved values apply when no override is set") {
    loaded() shouldBe HadoopConfig(
      numReducers = 4,
      maxBlockSize = 200,
      largeTeamThreshold = 50,
      maxPairExpansionTeamSize = 2000,
      intermediateCodec = "org.apache.hadoop.io.compress.DefaultCodec",
      outputCodec = "org.apache.hadoop.io.compress.GzipCodec",
      dblpRecordTypes = Vector("article", "inproceedings"),
      yearFrom = 2010,
      yearTo = 2012,
      dblpDtdPath = "datasets/samples/dblp/dblp-2023-06-28.dtd"
    )
    loaded().years shouldBe YearWindow.of(2010, 2012).toOption.get
  }

  test("subset fallback: under dev, record types and years are the manifest subset rule") {
    val c = ConfigLoader
      .loadSection(
        HadoopConfig,
        env(
          "dev",
          "SCHOLARCHAIN_HADOOP_NUM_REDUCERS" -> "2",
          "SCHOLARCHAIN_HADOOP_DBLP_DTD_PATH" -> "x/dblp.dtd"
        )
      )
      .fold(e => fail(e.message), identity)
    c.dblpRecordTypes shouldBe Vector("article", "inproceedings")
    (c.yearFrom, c.yearTo) shouldBe (2000, 2025)
  }

  test("missing: numReducers, dblpDtdPath and the DBLP subset source keys are listed") {
    ConfigLoader.loadSection(HadoopConfig, env("empty-test")) shouldBe Left(
      ConfigError.Invalid(
        Vector(
          MissingKey("scholarchain.hadoop.numReducers", "SCHOLARCHAIN_HADOOP_NUM_REDUCERS"),
          MissingKey("scholarchain.datasets.dblpSubset.recordTypes", "DBLP_SUBSET_RECORD_TYPES"),
          MissingKey("scholarchain.datasets.dblpSubset.startYear", "DBLP_SUBSET_START_YEAR"),
          MissingKey("scholarchain.datasets.dblpSubset.endYear", "DBLP_SUBSET_END_YEAR"),
          MissingKey("scholarchain.hadoop.dblpDtdPath", "SCHOLARCHAIN_HADOOP_DBLP_DTD_PATH")
        ),
        Vector.empty
      )
    )
  }

  test("the section loads without bucket, model or threshold values") {
    load().isRight shouldBe true
  }

  test("overrides: every default can be replaced by its environment variable") {
    val c = loaded(
      "SCHOLARCHAIN_HADOOP_MAX_BLOCK_SIZE" -> "300",
      "SCHOLARCHAIN_HADOOP_LARGE_TEAM_THRESHOLD" -> "40",
      "SCHOLARCHAIN_HADOOP_MAX_PAIR_EXPANSION_TEAM_SIZE" -> "40",
      "SCHOLARCHAIN_HADOOP_INTERMEDIATE_CODEC" -> "org.apache.hadoop.io.compress.SnappyCodec",
      "SCHOLARCHAIN_HADOOP_OUTPUT_CODEC" -> "org.apache.hadoop.io.compress.BZip2Codec"
    )
    (c.maxBlockSize, c.largeTeamThreshold, c.maxPairExpansionTeamSize) shouldBe (300, 40, 40)
    c.intermediateCodec shouldBe "org.apache.hadoop.io.compress.SnappyCodec"
    c.outputCodec shouldBe "org.apache.hadoop.io.compress.BZip2Codec"
  }

  test("invalid: maxPairExpansionTeamSize below largeTeamThreshold") {
    invalid("SCHOLARCHAIN_HADOOP_MAX_PAIR_EXPANSION_TEAM_SIZE" -> "49") shouldBe Vector(
      InvalidSetting(
        "scholarchain.hadoop.maxPairExpansionTeamSize",
        "must be >= largeTeamThreshold (50), got 49"
      )
    )
    invalid(
      "SCHOLARCHAIN_HADOOP_LARGE_TEAM_THRESHOLD" -> "2500"
    ).map(_.path) shouldBe Vector("scholarchain.hadoop.maxPairExpansionTeamSize")
  }

  test("invalid: numeric lower bounds and non-integers") {
    invalidPaths("SCHOLARCHAIN_HADOOP_NUM_REDUCERS" -> "0") shouldBe
      Vector("scholarchain.hadoop.numReducers")
    invalidPaths("SCHOLARCHAIN_HADOOP_MAX_BLOCK_SIZE" -> "1") shouldBe
      Vector("scholarchain.hadoop.maxBlockSize")
    invalidPaths("SCHOLARCHAIN_HADOOP_LARGE_TEAM_THRESHOLD" -> "0") shouldBe
      Vector("scholarchain.hadoop.largeTeamThreshold")
    invalidPaths(
      "SCHOLARCHAIN_HADOOP_LARGE_TEAM_THRESHOLD" -> "1",
      "SCHOLARCHAIN_HADOOP_MAX_PAIR_EXPANSION_TEAM_SIZE" -> "1"
    ) shouldBe Vector("scholarchain.hadoop.maxPairExpansionTeamSize")
    invalid("SCHOLARCHAIN_HADOOP_NUM_REDUCERS" -> "many") shouldBe Vector(
      InvalidSetting("scholarchain.hadoop.numReducers", "'many' is not an integer")
    )
  }

  test("an invalid threshold is reported alone, without a spurious ordering error") {
    invalidPaths("SCHOLARCHAIN_HADOOP_LARGE_TEAM_THRESHOLD" -> "x") shouldBe
      Vector("scholarchain.hadoop.largeTeamThreshold")
  }

  test("invalid: codecs must be Hadoop built-in codec classes") {
    invalidPaths("SCHOLARCHAIN_HADOOP_OUTPUT_CODEC" -> "gzip") shouldBe
      Vector("scholarchain.hadoop.outputCodec")
    invalidPaths(
      "SCHOLARCHAIN_HADOOP_INTERMEDIATE_CODEC" -> "com.example.EvilCodec"
    ) shouldBe Vector("scholarchain.hadoop.intermediateCodec")
  }

  test("record types: an override may narrow the subset but not widen it") {
    loaded("SCHOLARCHAIN_HADOOP_DBLP_RECORD_TYPES" -> "article").dblpRecordTypes shouldBe
      Vector("article")
    invalid("SCHOLARCHAIN_HADOOP_DBLP_RECORD_TYPES" -> "article,book") shouldBe Vector(
      InvalidSetting(
        "scholarchain.hadoop.dblpRecordTypes",
        "not in the DBLP subset record types (article,inproceedings): book"
      )
    )
    invalid("SCHOLARCHAIN_HADOOP_DBLP_RECORD_TYPES" -> "paper").map(s =>
      (s.path, s.reason.takeWhile(_ != ';'))
    ) shouldBe Vector(("scholarchain.hadoop.dblpRecordTypes", "not DBLP record elements: paper"))
    invalidPaths("SCHOLARCHAIN_HADOOP_DBLP_RECORD_TYPES" -> "article,article") shouldBe
      Vector("scholarchain.hadoop.dblpRecordTypes")
  }

  test("years: an override may narrow the subset window but not widen or invert it") {
    val narrowed = loaded("SCHOLARCHAIN_HADOOP_YEAR_FROM" -> "2011")
    (narrowed.yearFrom, narrowed.yearTo) shouldBe (2011, 2012)
    invalid("SCHOLARCHAIN_HADOOP_YEAR_FROM" -> "2009") shouldBe Vector(
      InvalidSetting(
        "scholarchain.hadoop.yearFrom,yearTo",
        "years 2009-2012 lie outside the DBLP subset years 2010-2012"
      )
    )
    invalid(
      "SCHOLARCHAIN_HADOOP_YEAR_FROM" -> "2012",
      "SCHOLARCHAIN_HADOOP_YEAR_TO" -> "2011"
    ) shouldBe Vector(
      InvalidSetting(
        "scholarchain.hadoop.yearFrom,yearTo",
        "window start year 2012 exceeds end year 2011"
      )
    )
  }

  test("an inverted subset window is reported once, at the subset path") {
    invalid("DBLP_SUBSET_START_YEAR" -> "2013") shouldBe Vector(
      InvalidSetting(
        "scholarchain.datasets.dblpSubset",
        "window start year 2013 exceeds end year 2012"
      )
    )
  }

  test("dblpDtdPath: must name a .dtd file and must not be an http(s) URL") {
    loaded(
      "SCHOLARCHAIN_HADOOP_DBLP_DTD_PATH" -> "s3://bucket/dblp/dblp-2023-06-28.dtd"
    ).dblpDtdPath shouldBe "s3://bucket/dblp/dblp-2023-06-28.dtd"
    invalidPaths(
      "SCHOLARCHAIN_HADOOP_DBLP_DTD_PATH" -> "https://dblp.org/xml/dblp.dtd"
    ) shouldBe Vector("scholarchain.hadoop.dblpDtdPath")
    invalidPaths("SCHOLARCHAIN_HADOOP_DBLP_DTD_PATH" -> "dblp/dblp.xml") shouldBe
      Vector("scholarchain.hadoop.dblpDtdPath")
  }

  test("the full configuration carries the hadoop section") {
    val c = ConfigLoader
      .load(env("contract-test"))
      .fold(e => fail(e.message), identity)
    c.hadoop.numReducers shouldBe 2
    c.hadoop.dblpDtdPath shouldBe "file-fixtures/dblp.dtd"
    c.hadoop.dblpRecordTypes shouldBe c.datasets.dblpSubset.recordTypes
    c.hadoop.years shouldBe c.datasets.dblpSubset.years
  }
