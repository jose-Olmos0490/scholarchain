package scholarchain.adversarial

import java.io.{File, FileOutputStream}
import java.nio.charset.StandardCharsets
import org.apache.hadoop.io.compress.bzip2.CBZip2OutputStream
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.json.WireCodec
import scholarchain.core.model.{RejectRecord, WorkRecord}
import scholarchain.hadoop.dblp.DblpRecordParser

/** Probes of the Repair 05 truncated-record rule (a value ends at its end tag or just before the
  * next configured start tag) against split boundaries: cut points on every boundary, chains of
  * truncated records, truncation inside a start tag, decoy and non-configured tags, and bzip2
  * block ownership.
  */
class TruncationSplitSpec extends AnyFunSuite:
  import Adv.*

  private val L1 = StandardCharsets.ISO_8859_1

  private def keyOf(r: Read): String =
    """key="([^"]*)"""".r
      .findFirstMatchIn(r.text(L1))
      .map(_.group(1))
      .getOrElse(s"@${r.offset}")

  /** Every split size from 1 to the file length gives exactly the single-split values. The number
    * of reader instances is about len * ln(len), so the documents are kept small.
    */
  private def assertSplitIndependent(f: File): Vector[Read] =
    val (_, single) = readAll(f, Long.MaxValue)
    val expected = single.map(r => (r.offset, r.text(L1)))
    val len = f.length
    val diffs = (1L to len).iterator.flatMap { size =>
      val (splits, recs) = readAll(f, size)
      val got = recs.map(r => (r.offset, r.text(L1)))
      if got != expected then
        Some(
          s"split size $size ($splits splits): offsets ${got.map(_._1)} vs ${expected.map(_._1)}"
        )
      else None
    }.toVector
    assert(diffs.isEmpty, diffs.take(5).mkString("\n"))
    single

  // -------------------------------------------------------------------------------------------
  // Cut point exactly on a split boundary
  // -------------------------------------------------------------------------------------------

  private val r1 =
    """<article key="journals/adv/R1"><author>R One</author><title>R1.</title><year>2010</year></article>"""
  private val truncA =
    """<article key="journals/adv/A"><author>A Cut</author><title>A is cut"""
  private val b =
    """<article key="journals/adv/B"><author>B Ok</author><title>B.</title><year>2010</year></article>"""
  private val truncCInStartTag = """<inproceedings key="conf/adv/C" mdate="""
  private val d =
    """<inproceedings key="conf/adv/D"><author>D Ok</author><title>D.</title><year>2010</year></inproceedings>"""
  private val truncE = """<article key="journals/adv/E"><author>E Eof</author><title>E"""

  private val cutDoc =
    s"""<?xml version="1.0" encoding="ISO-8859-1"?>
       |<dblp>
       |$r1
       |$truncA
       |$b
       |$truncCInStartTag
       |$d
       |$truncE""".stripMargin

  private lazy val cutFile = write(new File(scratch("cut-boundary"), "dblp.xml"), cutDoc)

  test("single split: truncated values end exactly before the next configured start tag") {
    val (_, recs) = readAll(cutFile, Long.MaxValue)
    assert(
      recs.map(keyOf) == Vector(
        "journals/adv/R1",
        "journals/adv/A",
        "journals/adv/B",
        "conf/adv/C",
        "conf/adv/D",
        "journals/adv/E"
      ),
      recs.map(_.text(L1))
    )
    val byKey = recs.map(r => keyOf(r) -> r).toMap
    assert(byKey("journals/adv/A").text(L1) == truncA + "\n")
    assert(byKey("journals/adv/B").text(L1) == b)
    assert(byKey("conf/adv/C").text(L1) == truncCInStartTag + "\n")
    assert(byKey("conf/adv/D").text(L1) == d)
    assert(byKey("journals/adv/E").text(L1) == truncE)
    recs.foreach(r => assert(cutDoc.substring(r.offset.toInt).startsWith(r.text(L1))))
    // Truncated values never parse as records; complete ones do.
    Seq("journals/adv/A", "conf/adv/C", "journals/adv/E").foreach { k =>
      assert(parse(byKey(k).text(L1)).isLeft, s"$k parsed")
    }
    Seq("journals/adv/R1", "journals/adv/B", "conf/adv/D").foreach { k =>
      assert(parse(byKey(k).text(L1)).isRight, s"$k did not parse")
    }
  }

  test("a cut-point start tag on every possible split boundary: values independent of split") {
    // Covers boundaries at, just before and just after the `<` of B (cut point of A), of D (cut
    // point of C, truncated inside its start tag) and inside the EOF-truncated E.
    assertSplitIndependent(cutFile)
  }

  test("a chain of adjacent truncated records (pending start reused across splits)") {
    val chain =
      """<?xml version="1.0" encoding="ISO-8859-1"?>
        |<dblp><article key="journals/adv/P"><title>p<article key="journals/adv/Q"><title>q<inproceedings key="conf/adv/R"><inproceedings key="conf/adv/S"><author>S Ok</author><title>S.</title><year>2010</year></inproceedings><article key="journals/adv/T"/></dblp>
        |""".stripMargin
    val f = write(new File(scratch("chain"), "dblp.xml"), chain)
    val single = assertSplitIndependent(f)
    assert(
      single.map(keyOf) == Vector(
        "journals/adv/P",
        "journals/adv/Q",
        "conf/adv/R",
        "conf/adv/S",
        "journals/adv/T"
      )
    )
    assert(single(0).text(L1) == """<article key="journals/adv/P"><title>p""")
    assert(single(2).text(L1) == """<inproceedings key="conf/adv/R">""")
    assert(parse(single(3).text(L1)).isRight)
  }

  // -------------------------------------------------------------------------------------------
  // Decoys and non-configured types
  // -------------------------------------------------------------------------------------------

  test("an escaped start tag in character data is text, not a cut point") {
    val rec =
      """<article key="journals/adv/Esc"><author>E Sc</author><title>On &lt;article key="x"&gt; and &lt;inproceedings&gt;</title><year>2010</year></article>"""
    val f = write(new File(scratch("escaped"), "dblp.xml"), dblpDoc("ISO-8859-1", rec, b))
    val single = assertSplitIndependent(f)
    assert(single.map(keyOf) == Vector("journals/adv/Esc", "journals/adv/B"))
    val w = work(single.head.text(L1))
    assert(w.title == """On <article key="x"> and <inproceedings>""")
  }

  test("non-configured start tags after a truncated record are not cut points") {
    // `proceedings`, `articles` and `www` are not configured record types; only `<article ` of G
    // ends the truncated value. The good record G must survive on its own at every split size.
    val trunc = """<article key="journals/adv/NT"><author>N T</author><title>never closed"""
    val decoys =
      """<proceedings key="conf/adv/2010"><title>Proc.</title><year>2010</year></proceedings>
        |<articles key="decoy/1"><title>x</title></articles>
        |<www key="homepages/x/y"><author>W W</author></www>""".stripMargin
    val g =
      """<article key="journals/adv/G"><author>G Ood</author><title>G.</title><year>2010</year></article>"""
    val f =
      write(new File(scratch("nonconf"), "dblp.xml"), dblpDoc("ISO-8859-1", trunc, decoys, g))
    val single = assertSplitIndependent(f)
    assert(single.map(keyOf) == Vector("journals/adv/NT", "journals/adv/G"))
    assert(single.head.text(L1) == trunc + "\n" + decoys + "\n")
    assert(parse(single.head.text(L1)).isLeft)
    assert(work(single(1).text(L1)).sourceRecordId == "journals/adv/G")
  }

  test("a raw configured start tag inside an attribute value: split-independent, G survives") {
    // Illegal XML (`<` in an attribute value). The reader treats `<article x` as a cut point;
    // whatever it does, it must do it identically for every split and must not swallow G.
    val bad =
      """<article key="journals/adv/Dec" note="see <article x"><author>D Ec</author><title>Decoy.</title><year>2010</year></article>"""
    val g =
      """<article key="journals/adv/G2"><author>G Two</author><title>G2.</title><year>2010</year></article>"""
    val f = write(new File(scratch("attr-decoy"), "dblp.xml"), dblpDoc("ISO-8859-1", bad, g))
    val single = assertSplitIndependent(f)
    val parsed = single.map(r => keyOf(r) -> parse(r.text(L1)))
    assert(parsed.last._1 == "journals/adv/G2")
    assert(parsed.last._2.isRight)
    assert(parsed.init.forall(_._2.isLeft), parsed)
  }

  // -------------------------------------------------------------------------------------------
  // bzip2: block ownership of a pending cut point
  // -------------------------------------------------------------------------------------------

  test("bzip2 multi-block file with many truncated records: split size does not change values") {
    val sb = new StringBuilder("""<?xml version="1.0" encoding="ISO-8859-1"?>""" + "\n<dblp>\n")
    (0 until 5000).foreach { i =>
      val t = if i % 2 == 0 then "article" else "inproceedings"
      sb.append(s"""<$t key="journals/bz/K$i"><author>Author $i Name${i * 7919 % 10007}</author>""")
      sb.append(s"<title>Title $i ${i * 31 % 977} words ${i * 17 % 263}.")
      // Every 7th record is cut off right after its title text, so its value ends at the next
      // record's start tag, which may lie in the next bzip2 block.
      if i % 7 != 3 then sb.append(s"</title><year>${2000 + i % 26}</year></$t>")
      sb.append("\n")
    }
    sb.append("</dblp>\n")
    val file = new File(scratch("bz2-trunc"), "dblp.xml.bz2")
    val out = new FileOutputStream(file)
    out.write('B'.toInt)
    out.write('Z'.toInt)
    val bz = new CBZip2OutputStream(out, 1)
    bz.write(sb.toString.getBytes(L1))
    bz.close()
    val (_, single) = readAll(file, Long.MaxValue)
    assert(single.size == 5000)
    assert(single.map(keyOf) == (0 until 5000).map(i => s"journals/bz/K$i").toVector)
    val truncated = single.count(r => DblpRecordParser.parseString(r.text(L1), entities).isLeft)
    assert(truncated == (0 until 5000).count(_ % 7 == 3))
    val expected = single.map(r => (keyOf(r), r.text(L1)))
    Seq(4096L, 10007L, 33333L, file.length / 7, file.length / 3).foreach { size =>
      val (splits, recs) = readAll(file, size)
      assert(splits > 1, s"size $size")
      val got = recs.map(r => (keyOf(r), r.text(L1)))
      assert(got.size == expected.size, s"bzip2 split size $size: ${got.size} values")
      assert(got == expected, s"bzip2 split size $size: values differ")
    }
  }

  // -------------------------------------------------------------------------------------------
  // Pipeline: truncated records at split boundaries
  // -------------------------------------------------------------------------------------------

  private val pipeDoc = dblpDoc(
    "ISO-8859-1",
    r1,
    truncA,
    b,
    truncCInStartTag,
    d,
    """<article key="journals/adv/F"><author>F Ok</author><title>F.</title><year>2011</year></article>"""
  )

  private lazy val pipeSingle = runPipeline(dblpInput("trunc-pipe-1", pipeDoc))
  private lazy val pipeSplit = runPipeline(
    dblpInput("trunc-pipe-n", pipeDoc),
    conf = localConf(
      "mapreduce.input.fileinputformat.split.maxsize" -> "53",
      "mapreduce.input.fileinputformat.split.minsize" -> "1"
    )
  )

  private val RunIdPattern = "run:[0-9a-f]{64}".r
  private def logical(r: Result, ds: String, table: String) =
    r.rows(ds, table).map(RunIdPattern.replaceAllIn(_, "run:X")).sorted

  test("pipeline: truncated records cost one reject each, excerpts hold only that record") {
    val works = pipeSingle.rows("dblp", "work").map(WireCodec.decode[WorkRecord](_).toOption.get)
    assert(
      works.map(_.sourceRecordId).sorted ==
        Vector("conf/adv/D", "journals/adv/B", "journals/adv/F", "journals/adv/R1")
    )
    val rejects =
      pipeSingle.rows("dblp", "rejects_j01").map(WireCodec.decode[RejectRecord](_).toOption.get)
    assert(rejects.map(_.sourceRecordRef).sorted == Vector("conf/adv/C", "journals/adv/A"))
    assert(rejects.forall(_.reasonCode == "MALFORMED_XML"))
    rejects.foreach { r =>
      Seq("journals/adv/B", "conf/adv/D", "journals/adv/F", "journals/adv/R1").foreach { k =>
        assert(!r.rawExcerpt.contains(k), s"excerpt of ${r.sourceRecordRef} contains $k")
      }
    }
    assert(pipeSingle.counter("j01", "INPUT_RECORDS") == 6)
  }

  test("pipeline: 53-byte splits give the same tables and rejects as one split") {
    assert(pipeSplit.report("j01").mapTasks > 10, s"map tasks ${pipeSplit.report("j01").mapTasks}")
    assert(pipeSplit.counter("j01", "INPUT_RECORDS") == pipeSingle.counter("j01", "INPUT_RECORDS"))
    Seq("work", "author_mention", "rejects_j01").foreach { t =>
      assert(logical(pipeSplit, "dblp", t) == logical(pipeSingle, "dblp", t), s"table $t differs")
    }
  }
