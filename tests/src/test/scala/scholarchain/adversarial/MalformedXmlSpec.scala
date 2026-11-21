package scholarchain.adversarial

import java.io.File
import java.nio.charset.StandardCharsets
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.json.WireCodec
import scholarchain.core.model.{RejectRecord, WorkRecord}
import scholarchain.hadoop.dblp.{DblpParseFailure, DblpRecordParser}

/** Malformed and truncated DBLP XML: the reader and parser never crash, every malformed record
  * becomes exactly one reject, and a broken record never takes its well-formed neighbours with it.
  */
class MalformedXmlSpec extends AnyFunSuite:
  import Adv.*

  private val good1 = article("journals/adv/Good1", Seq("Ann Good"), "First good paper.")
  private val good2 = article("journals/adv/Good2", Seq("Bob Good"), "Second good paper.")
  private val good3 = article("journals/adv/Good3", Seq("Cy Good"), "Third good paper.")
  private val goodInproc =
    """<inproceedings mdate="2024-01-01" key="conf/adv/Good4">
      |<author>Dee Good</author>
      |<title>Fourth good paper.</title>
      |<year>2011</year>
      |<booktitle>ADV</booktitle>
      |</inproceedings>""".stripMargin

  /** An inproceedings record cut off before its end tag (no `</inproceedings>`). */
  private val truncatedInproc =
    """<inproceedings mdate="2024-01-01" key="conf/adv/Trunc1">
      |<author>Tru Ncated</author>
      |<title>This record never ends""".stripMargin

  /** An article cut off before its end tag. */
  private val truncatedArticle =
    """<article mdate="2024-01-01" key="journals/adv/Trunc2">
      |<author>Tru Ncated</author>
      |<title>This article never ends</title>""".stripMargin

  private def keysOf(records: Vector[Read]): Vector[String] =
    records.map(r =>
      """key="([^"]*)"""".r
        .findAllMatchIn(r.text(StandardCharsets.ISO_8859_1))
        .map(_.group(1))
        .mkString("+")
    )

  // -------------------------------------------------------------------------------------------
  // Reader level
  // -------------------------------------------------------------------------------------------

  test("a truncated inproceedings does not swallow the following complete records (reader)") {
    val f = write(
      new File(scratch("swallow"), "dblp.xml"),
      dblpDoc("ISO-8859-1", good1, truncatedInproc, good2, good3, goodInproc)
    )
    val (_, recs) = readAll(f, Long.MaxValue)
    val keys = keysOf(recs)
    // Every complete record must be its own reader value, never part of the truncated one.
    Seq("journals/adv/Good1", "journals/adv/Good2", "journals/adv/Good3", "conf/adv/Good4")
      .foreach { k =>
        assert(keys.contains(k), s"record $k is not read on its own; reader values: $keys")
      }
  }

  test("a truncated article does not swallow the next article (reader)") {
    val f = write(
      new File(scratch("swallow2"), "dblp.xml"),
      dblpDoc("ISO-8859-1", truncatedArticle, good1, good2)
    )
    val (_, recs) = readAll(f, Long.MaxValue)
    val keys = keysOf(recs)
    assert(keys.contains("journals/adv/Good1"), s"reader values: $keys")
    assert(keys.contains("journals/adv/Good2"), s"reader values: $keys")
  }

  test("with a truncated record mid-file, every split size yields the single-split records") {
    val f = write(
      new File(scratch("swallow-split"), "dblp.xml"),
      dblpDoc("ISO-8859-1", good1, truncatedArticle, good2, good3)
    )
    val (_, single) = readAll(f, Long.MaxValue)
    val singleKeys = keysOf(single)
    val diffs = (Seq(16L, 32L, 50L, 64L, 100L, 128L, 200L, 256L, 300L, 400L)).flatMap { size =>
      val (splits, recs) = readAll(f, size)
      val ks = keysOf(recs)
      if ks != singleKeys then Some(s"split size $size ($splits splits): $ks") else None
    }
    assert(diffs.isEmpty, s"single split: $singleKeys; differing: ${diffs.mkString("; ")}")
  }

  test("a file cut at every byte never yields a partial record that parses as valid") {
    val full = dblpDoc("ISO-8859-1", good1, goodInproc, good2)
    val bytes = full.getBytes(StandardCharsets.ISO_8859_1)
    val (_, complete) =
      readAll(write(new File(scratch("cut-full"), "dblp.xml"), full), Long.MaxValue)
    val completeParsed =
      complete.map(r => DblpRecordParser.parseString(r.text(StandardCharsets.ISO_8859_1), entities))
    assert(completeParsed.forall(_.isRight))
    val validSet = completeParsed.flatMap(_.toOption).toSet
    val dir = scratch("cut")
    val problems = (1 until bytes.length by 3).flatMap { cut =>
      val f = writeBytes(new File(dir, s"cut-$cut.xml"), java.util.Arrays.copyOf(bytes, cut))
      val (_, recs) = readAll(f, Long.MaxValue)
      recs.flatMap { r =>
        DblpRecordParser.parseString(r.text(StandardCharsets.ISO_8859_1), entities) match
          case Right(rec) if !validSet.contains(rec) =>
            Some(s"cut $cut parsed a partial record $rec")
          case _ => None
      }
    }
    assert(problems.isEmpty, problems.take(5).mkString("\n"))
  }

  // -------------------------------------------------------------------------------------------
  // Parser level
  // -------------------------------------------------------------------------------------------

  private val malformedCases: Seq[(String, String)] = Seq(
    "mismatched end tag" ->
      """<article key="journals/adv/M1"><author>A B</author><title>T</journal><year>2010</year></article>""",
    "unclosed child" ->
      """<article key="journals/adv/M2"><author>A B</author><title>T<year>2010</year></article>""",
    "unquoted attribute" ->
      """<article mdate=2024 key="journals/adv/M3"><title>T</title><year>2010</year></article>""",
    "duplicate attribute" ->
      """<article key="journals/adv/M4" key="journals/adv/M4b"><title>T</title><year>2010</year></article>""",
    "bare ampersand" ->
      """<article key="journals/adv/M5"><title>R&D</title><year>2010</year></article>""",
    "stray less-than" ->
      """<article key="journals/adv/M6"><title>a < b</title><year>2010</year></article>""",
    "NUL character reference" ->
      """<article key="journals/adv/M7"><title>a&#0;b</title><year>2010</year></article>""",
    "lone surrogate reference" ->
      """<article key="journals/adv/M8"><title>a&#xD800;b</title><year>2010</year></article>""",
    "two root elements" ->
      """<article key="journals/adv/M9"><title>T</title><year>2010</year></article><article key="x"/>""",
    "entity without semicolon" ->
      """<article key="journals/adv/M10"><title>J&uuml rjens</title><year>2010</year></article>"""
  )

  malformedCases.foreach { (name, xml) =>
    test(s"parser reports Malformed, never throws: $name") {
      val r = DblpRecordParser.parseString(xml, entities)
      assert(r.isLeft, s"accepted: $r")
      assert(r.left.toOption.get.isInstanceOf[DblpParseFailure.Malformed], s"got $r")
    }
  }

  // -------------------------------------------------------------------------------------------
  // Pipeline level (LocalJobRunner, J01)
  // -------------------------------------------------------------------------------------------

  /** Cases that are one reader value each. ("two root elements" is split into two values by the
    * reader, so it is a parser-only case.)
    */
  private val pipelineCases = malformedCases.filterNot(_._1 == "two root elements")

  private lazy val run: Result =
    val malformed = pipelineCases.map(_._2)
    runPipeline(
      dblpInput(
        "malformed",
        dblpDoc("ISO-8859-1", (Seq(good1, good2) ++ malformed ++ Seq(good3, goodInproc))*)
      )
    )

  test("pipeline: each malformed record is one MALFORMED_XML reject; good records are accepted") {
    val works = run.rows("dblp", "work").map(WireCodec.decode[WorkRecord](_).toOption.get)
    assert(
      works.map(_.sourceRecordId).sorted == Vector(
        "conf/adv/Good4",
        "journals/adv/Good1",
        "journals/adv/Good2",
        "journals/adv/Good3"
      )
    )
    val rejects = run.rows("dblp", "rejects_j01").map(WireCodec.decode[RejectRecord](_))
    assert(rejects.forall(_.isRight), rejects.filter(_.isLeft))
    val rs = rejects.flatMap(_.toOption)
    assert(rs.size == pipelineCases.size, rs.map(r => r.sourceRecordRef -> r.reasonCode))
    assert(
      rs.forall(_.reasonCode == "MALFORMED_XML"),
      rs.map(r => r.sourceRecordRef -> r.reasonCode)
    )
    assert(rs.forall(r => r.rawExcerpt.codePointCount(0, r.rawExcerpt.length) <= 1024))
    assert(run.counter("j01", "INPUT_RECORDS") == 4 + pipelineCases.size)
    assert(run.counter("j01", "REJECTED") == pipelineCases.size)
  }

  private lazy val swallowRun: Result =
    runPipeline(
      dblpInput(
        "swallow-pipe",
        dblpDoc("ISO-8859-1", good1, truncatedInproc, good2, good3, goodInproc)
      )
    )

  test("pipeline: a truncated record mid-file costs one reject, not its neighbours") {
    val works = swallowRun.rows("dblp", "work").map(WireCodec.decode[WorkRecord](_).toOption.get)
    assert(
      works.map(_.sourceRecordId).sorted == Vector(
        "conf/adv/Good4",
        "journals/adv/Good1",
        "journals/adv/Good2",
        "journals/adv/Good3"
      ),
      s"J01 counters: ${swallowRun.report("j01").counters}"
    )
    assert(swallowRun.counter("j01", "REJECTED") == 1)
  }
