package scholarchain.adversarial

import java.io.File
import java.nio.charset.StandardCharsets
import org.scalatest.funsuite.AnyFunSuite

/** Records straddling split boundaries in awkward places: start tags with `>` and `/>` inside
  * quoted attribute values, CRLF and tab after the element name, adjacent records with no
  * separator, decoy element names, self-closing records, a record larger than the reader buffer
  * and a record that ends exactly at end of file.
  */
class SplitBoundarySpec extends AnyFunSuite:
  import Adv.*

  private val big =
    article(
      "journals/adv/Big",
      (1 to 2500).map(i => f"Author$i%04d Straddle"),
      title = "Big record."
    )

  private val records: Seq[String] = Seq(
    """<article mdate="2024-01-01" key="journals/adv/Q1" note="a>b"><author>Q One</author><title>Quoted gt.</title><year>2010</year></article>""",
    """<article mdate="2024-01-01" key="journals/adv/Q2" note='x/>'><author>Q Two</author><title>Quoted slash-gt.</title><year>2010</year></article>""",
    "<article\r\nmdate=\"2024-01-01\"\tkey=\"journals/adv/C1\"\r\n><author>C One</author><title>CRLF.</title><year>2010</year></article>",
    "<article\tkey=\"journals/adv/T1\"><author>T One</author><title>Tab.</title><year>2010</year></article><inproceedings key=\"conf/adv/T2\"><author>T Two</author><title>Adjacent.</title><year>2010</year></inproceedings>",
    """<articles key="decoy/1"><title>Not a record type.</title></articles>""",
    """<www key="homepages/x/y"><author>Home Page</author><title>Home Page</title></www>""",
    """<article key="journals/adv/S1"/>""",
    """<proceedings key="conf/adv/2010"><title>Not configured.</title><year>2010</year></proceedings>"""
  )

  /** Document whose last configured record ends exactly at the last byte of the file. */
  private def docOf(rs: Seq[String]): String =
    s"""<?xml version="1.0" encoding="ISO-8859-1"?>
       |<dblp>
       |${rs.mkString("\n")}
       |<article key="journals/adv/Last"><author>L Ast</author><title>Last.</title><year>2010</year></article>""".stripMargin

  private val doc = docOf(records)
  private val bigDoc = docOf(records.take(4) ++ Seq(big) ++ records.drop(4))

  private lazy val file: File = write(new File(scratch("straddle"), "dblp.xml"), doc)
  private lazy val bigFile: File = write(new File(scratch("straddle-big"), "dblp.xml"), bigDoc)

  private val expectedKeys = Vector(
    "journals/adv/Q1",
    "journals/adv/Q2",
    "journals/adv/C1",
    "journals/adv/T1",
    "conf/adv/T2",
    "journals/adv/S1",
    "journals/adv/Last"
  )

  private def key(r: Read): String =
    """key="([^"]*)"""".r.findFirstMatchIn(r.text(StandardCharsets.ISO_8859_1)).get.group(1)

  test("single split: exactly the configured records, each from its start tag to its end") {
    val (_, small) = readAll(file, Long.MaxValue)
    assert(small.map(key) == expectedKeys)
    val (_, recs) = readAll(bigFile, Long.MaxValue)
    assert(
      recs.map(key) == expectedKeys.take(5) ++ Vector("journals/adv/Big") ++ expectedKeys.drop(5)
    )
    recs.foreach { r =>
      val t = r.text(StandardCharsets.ISO_8859_1)
      assert(
        t.endsWith("</article>") || t.endsWith("</inproceedings>") || t.endsWith("/>"),
        t.takeRight(40)
      )
      assert(
        bigDoc.substring(r.offset.toInt).startsWith(t),
        s"offset ${r.offset} does not point at the record"
      )
    }
    assert(recs.find(key(_) == "journals/adv/Big").get.bytes.length > 64 * 1024)
  }

  private def sameAsSingle(f: File, sizes: Seq[Long]): Unit =
    val (_, single) = readAll(f, Long.MaxValue)
    sizes.distinct.filter(_ > 0).foreach { size =>
      val (_, recs) = readAll(f, size)
      assert(recs.map(_.offset) == single.map(_.offset), s"offsets differ at split size $size")
      assert(
        recs.map(r => new String(r.bytes, StandardCharsets.ISO_8859_1)) ==
          single.map(r => new String(r.bytes, StandardCharsets.ISO_8859_1)),
        s"records differ at split size $size"
      )
    }

  test("every small split size gives the same records and offsets as one split") {
    val len = file.length
    sameAsSingle(
      file,
      (1L to 80L) ++ Seq(97L, 101L, 127L, 128L, 255L, 256L, 257L, len - 1, len / 2, len / 3)
    )
  }

  test("a record larger than the reader buffer straddles many splits and buffer refills") {
    val len = bigFile.length
    sameAsSingle(
      bigFile,
      Seq(1000L, 4095L, 4096L, 8191L, 65535L, 65536L, 65537L, len - 1, len / 2, len / 3)
    )
  }

  test("a split boundary at every byte of a start tag and of an end tag") {
    val (_, single) = readAll(file, Long.MaxValue)
    // Split sizes that put a boundary at each byte of "<article" of Q2 and of "</article>" of Q1.
    val q2 = doc.indexOf("<article mdate=\"2024-01-01\" key=\"journals/adv/Q2\"")
    val q1End = doc.indexOf("</article>", doc.indexOf("journals/adv/Q1"))
    val boundaries = (q2 to q2 + 9) ++ (q1End to q1End + 10)
    boundaries.foreach { b =>
      val (_, recs) = readAll(file, b.toLong)
      assert(recs.map(key) == single.map(key), s"boundary at $b")
    }
  }
