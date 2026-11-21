package scholarchain.hadoop

import java.io.{File, FileOutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.compress.bzip2.CBZip2OutputStream
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl
import org.apache.hadoop.mapreduce.{Job, TaskAttemptID}
import org.scalatest.funsuite.AnyFunSuite
import scala.jdk.CollectionConverters.*
import scholarchain.hadoop.dblp.DblpInputFormat
import scholarchain.hadoop.io.PipelineConf

/** The DBLP reader never splits a record across mappers: for every forced split size, the union
  * of the records read from all splits equals the single-split result, with no record lost or
  * read twice.
  */
class DblpInputFormatSpec extends AnyFunSuite:
  import HadoopTestSupport.*

  /** Reads every record of `file` with the given max split size; returns (splits, records). */
  private def readAll(file: File, maxSplit: Long): (Int, Vector[String]) =
    val conf = new Configuration()
    conf.set(PipelineConf.DblpRecordTypes, "article,inproceedings")
    conf.setLong("mapreduce.input.fileinputformat.split.maxsize", maxSplit)
    conf.setLong("mapreduce.input.fileinputformat.split.minsize", 1)
    val job = Job.getInstance(conf)
    FileInputFormat.addInputPath(job, new Path(file.getAbsolutePath))
    val format = new DblpInputFormat
    val splits = format.getSplits(job).asScala.toVector
    val records = splits.flatMap { split =>
      val ctx = new TaskAttemptContextImpl(job.getConfiguration, new TaskAttemptID())
      val reader = format.createRecordReader(split, ctx)
      reader.initialize(split, ctx)
      val out = Vector.newBuilder[String]
      while reader.nextKeyValue() do
        val v = reader.getCurrentValue
        out += new String(v.getBytes, 0, v.getLength, StandardCharsets.ISO_8859_1)
      reader.close()
      out.result()
    }
    (splits.size, records)

  private val fixtureFile = fixture("dblp/dblp-fixture.xml")

  test("the fixture is read as 16 configured records in one split") {
    val (splits, records) = readAll(fixtureFile, Long.MaxValue)
    assert(splits == 1)
    assert(records.size == 16)
    assert(records.forall(r => r.startsWith("<article") || r.startsWith("<inproceedings")))
    assert(records.forall(r => r.endsWith("</article>") || r.endsWith("</inproceedings>")))
    assert(
      !records.exists(_.contains("<proceedings")),
      "an unconfigured type is never synchronized on"
    )
    // Two records on one line are read as two records.
    assert(records.exists(_.contains("Same14")) && records.exists(_.contains("Same15")))
    assert(!records.exists(r => r.contains("Same14") && r.contains("Same15")))
  }

  test("every forced split size yields exactly the single-split records (uncompressed)") {
    val (_, single) = readAll(fixtureFile, Long.MaxValue)
    val sizes = (Seq(1L, 2L, 7L, 13L, 31L, 64L, 100L, 128L, 257L, 512L,
      1000L) ++ (40L to 400L by 9L)).distinct
    sizes.foreach { size =>
      val (splits, records) = readAll(fixtureFile, size)
      assert(splits > 1 || size >= fixtureFile.length, s"size $size should split the file")
      assert(records == single, s"split size $size: ${records.size} records vs ${single.size}")
    }
  }

  test("the committed DBLP sample reads identically with 64-byte splits") {
    val sample = new File(samplesDir, "dblp/dblp-2026-11-01-excerpt.xml")
    val (_, single) = readAll(sample, Long.MaxValue)
    val (splits, small) = readAll(sample, 64)
    assert(splits > 300)
    assert(single.size == 35, "2 article + 33 inproceedings records in the excerpt")
    assert(small == single)
  }

  private def bigXml(records: Int): String =
    val sb = new StringBuilder("""<?xml version="1.0" encoding="ISO-8859-1"?>""" + "\n<dblp>\n")
    (0 until records).foreach { i =>
      val t = if i % 3 == 0 then "article" else "inproceedings"
      sb.append(
        s"""<$t mdate="2024-01-01" key="conf/gen/R$i">\n<author>Author ${i % 97} &uuml;</author>\n"""
      )
      sb.append(
        s"<title>Generated title number $i with some ${(i * 7919) % 104729} noise\n over two lines.</title>\n"
      )
      sb.append(s"<year>${2000 + i % 26}</year>\n</$t>")
      if i % 5 == 0 then sb.append("\n")
    }
    sb.append("\n</dblp>\n").toString

  test("bzip2 input with many blocks is split on block boundaries without losing records") {
    val xml = bigXml(6000)
    val dir = scratch("bz2")
    val file = new File(dir, "gen.xml.bz2")
    val out = new FileOutputStream(file)
    out.write('B'.toInt)
    out.write('Z'.toInt)
    // Block size 1 (100k): the ~900 KB document becomes about nine bzip2 blocks.
    val bz = new CBZip2OutputStream(out, 1)
    bz.write(xml.getBytes(StandardCharsets.ISO_8859_1))
    bz.close()
    val (oneSplit, single) = readAll(file, Long.MaxValue)
    assert(oneSplit == 1)
    assert(single.size == 6000)
    Seq(file.length / 9, file.length / 5, file.length / 3, 4096L, 10007L).foreach { size =>
      val (splits, records) = readAll(file, size)
      assert(splits > 1, s"size $size")
      assert(records.sorted == single.sorted, s"bzip2 split size $size")
      assert(records.distinct.size == records.size, s"bzip2 split size $size read a record twice")
    }
  }

  test("gzip input is never split: one split per file") {
    val dir = scratch("gz")
    val file = new File(dir, "gen.xml.gz")
    val gz = new GZIPOutputStream(new FileOutputStream(file))
    gz.write(bigXml(500).getBytes(StandardCharsets.ISO_8859_1))
    gz.close()
    val (splits, records) = readAll(file, 512)
    assert(splits == 1)
    assert(records.size == 500)
  }

  test("a truncated final record is handed to the parser instead of being dropped") {
    val dir = scratch("trunc")
    val file = write(
      new File(dir, "t.xml"),
      """<dblp><article key="a/b/c"><title>ok</title></article><article key="a/b/d"><title>cut"""
    )
    val (_, records) = readAll(file, Long.MaxValue)
    assert(records.size == 2)
    assert(records(1).startsWith("<article key=\"a/b/d\">"))
  }

  // Truncated record mid-file (Phase 04 repair, defects 1-3).

  private val truncDoc =
    """<?xml version="1.0" encoding="ISO-8859-1"?>
      |<dblp>
      |<article mdate="2024-01-01" key="t/x/G1"><author>A G</author><title>One.</title><year>2010</year></article>
      |<inproceedings mdate="2024-01-01" key="t/x/T1">
      |<author>Tru Ncated</author>
      |<title>never ends
      |<article mdate="2024-01-01" key="t/x/G2"><title>Two.</title><year>2010</year></article>
      |<article key="t/x/T2"><title>cut again</title>
      |<inproceedings key="t/x/G3"><title>Three.</title><year>2011</year></inproceedings >
      |<article key="t/x/T3
      |<article key="t/x/G4"><title>Four.</title><year>2012</year></article>
      |</dblp>
      |""".stripMargin

  private def keyOf(r: String): String =
    """key="([^"\n<]*)""".r.findAllMatchIn(r).map(_.group(1)).mkString("+")

  test("a truncated record ends at the next configured start tag and never swallows neighbours") {
    val f = write(new File(scratch("trunc-mid"), "t.xml"), truncDoc, StandardCharsets.ISO_8859_1)
    val (_, records) = readAll(f, Long.MaxValue)
    assert(
      records
        .map(keyOf) == Vector("t/x/G1", "t/x/T1", "t/x/G2", "t/x/T2", "t/x/G3", "t/x/T3", "t/x/G4")
    )
    // The truncated values stop just before the next record; complete ones end at their end tag
    // (an end tag with whitespace before '>' is accepted).
    assert(records(1).startsWith("<inproceedings") && records(1).trim.endsWith("never ends"))
    assert(records(3).trim.endsWith("<title>cut again</title>"))
    assert(records(4).endsWith("</inproceedings >"))
    assert(records(5).trim == """<article key="t/x/T3""")
    assert(records(6).startsWith("<article key=\"t/x/G4\"") && records(6).endsWith("</article>"))
  }

  test("with truncated records mid-file every split size yields exactly the single-split records") {
    val f = write(new File(scratch("trunc-split"), "t.xml"), truncDoc, StandardCharsets.ISO_8859_1)
    val (_, single) = readAll(f, Long.MaxValue)
    ((1L to 40L) ++ (41L to 400L by 7L)).foreach { size =>
      val (splits, records) = readAll(f, size)
      assert(splits > 1)
      assert(records == single, s"split size $size")
    }
  }
