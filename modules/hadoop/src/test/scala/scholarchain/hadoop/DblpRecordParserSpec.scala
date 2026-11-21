package scholarchain.hadoop

import java.nio.charset.StandardCharsets
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.ids.{StableIds, WorkNamespace}
import scholarchain.hadoop.dblp.*

class DblpRecordParserSpec extends AnyFunSuite:
  import HadoopTestSupport.entities

  private val ctx =
    DblpContext(Set("article", "inproceedings"), 2000, 2025, "2024-01-01", "run:" + "a" * 64)

  private def parse(xml: String): DblpRawRecord =
    DblpRecordParser.parseString(xml, entities).fold(f => fail(s"unexpected $f"), identity)

  private def accepted(xml: String): DblpWork =
    DblpNormalizer.normalize(parse(xml), ctx) match
      case DblpOutcome.Accepted(w) => w
      case other => fail(s"expected an accepted work, got $other")

  test("the entity table comes from the DTD itself, not from code") {
    assert(entities.replacement("uuml").contains("ü"))
    assert(entities.replacement("micro").contains("µ"))
    assert(entities.size >= 60)
    assert(!entities.contains("field"), "parameter entities are not general entities")
  }

  test("a multiline title with inline markup is flattened and whitespace-collapsed") {
    val w = accepted(
      """<inproceedings key="conf/x/A01"><author>A B</author><title>Multiline
        |   <i>Title</i> with
        | <sub>2</sub> parts.</title><year>2001</year></inproceedings>""".stripMargin
    )
    assert(w.work.title == "Multiline Title with 2 parts.")
    assert(w.work.normalizedTitle == "multiline title with 2 parts")
  }

  test("DTD entities in text and attributes resolve to their characters") {
    val r = parse(
      """<article key="journals/x/J&uuml;r"><author>Jan J&uuml;rjens</author><title>&Agrave; &amp; &micro;</title><year>2005</year></article>"""
    )
    assert(r.key.contains("journals/x/Jür"))
    assert(r.all("author").head.text == "Jan Jürjens")
    assert(r.first("title").contains("À & µ"))
  }

  test("an undeclared entity is reported as UnknownEntity with its name") {
    val out = DblpRecordParser.parseString(
      """<article key="journals/x/U"><author>A</author><title>&bogus; x</title><year>2005</year></article>""",
      entities
    )
    assert(out == Left(DblpParseFailure.UnknownEntity("bogus")))
  }

  test("malformed XML is reported as Malformed, never thrown") {
    val out = DblpRecordParser.parseString(
      """<article key="journals/x/M"><title>Bad <i>nest</title></i></article>""",
      entities
    )
    assert(
      out.isInstanceOf[Left[?, ?]] && out.left.toOption.get.isInstanceOf[DblpParseFailure.Malformed]
    )
  }

  test("Unicode survives: ISO-8859-1 bytes, numeric references and non-Latin letters") {
    val iso = """<article key="journals/x/G"><author>Ana G""" + "ó" +
      """mez</author><author>&#26446;&#20025;</author><title>T</title><year>2010</year></article>"""
    val bytes = iso.getBytes(StandardCharsets.ISO_8859_1)
    val r = DblpRecordParser
      .parse(bytes, bytes.length, StandardCharsets.ISO_8859_1, entities)
      .toOption
      .get
    assert(r.all("author").map(_.text) == Vector("Ana Gómez", "李丹"))
    val w = DblpNormalizer.normalize(r, ctx).asInstanceOf[DblpOutcome.Accepted].work.work
    assert(w.authors.map(_.normalizedName) == Vector("ana gomez", "李丹"))
  }

  test("missing optional fields still give a valid work (no ee, venue, pages, authors)") {
    val w = accepted(
      """<article key="journals/x/Min"><title>Minimal.</title><year>2003</year></article>"""
    )
    assert(w.work.authors.isEmpty)
    assert(w.work.doi.isEmpty && w.work.arxivId.isEmpty && w.work.venueId.isDefined)
    assert(w.venueName.isEmpty)
    assert(scholarchain.hadoop.io.Records.json(w.work).isRight)
  }

  test("a repeated author keeps both mentions in the WorkRecord, in source order") {
    val w = accepted(
      """<inproceedings key="conf/x/D"><author>Dee Dup</author><author>Eve</author><author>Dee Dup</author><title>T</title><year>2006</year></inproceedings>"""
    )
    assert(w.work.authors.map(_.position) == Vector(1, 2, 3))
    assert(w.work.authors.map(_.rawName) == Vector("Dee Dup", "Eve", "Dee Dup"))
    assert(w.work.authors.map(_.mentionId).distinct.size == 3)
  }

  test("the subset rule: configured types and an integer year inside the window") {
    def outcome(xml: String) = DblpNormalizer.normalize(parse(xml), ctx)
    assert(
      outcome("""<article key="journals/x/Y"><title>T</title><year>1999</year></article>""")
        .isInstanceOf[DblpOutcome.OutOfSubset]
    )
    assert(
      outcome("""<article key="journals/x/Y"><title>T</title><year>2026</year></article>""")
        .isInstanceOf[DblpOutcome.OutOfSubset]
    )
    assert(
      outcome("""<article key="journals/x/Y"><title>T</title></article>""")
        .isInstanceOf[DblpOutcome.OutOfSubset]
    )
    assert(
      outcome("""<book key="books/x/Y"><title>T</title><year>2005</year></book>""")
        .isInstanceOf[DblpOutcome.OutOfSubset]
    )
    assert(
      outcome("""<article><title>T</title><year>2005</year></article>""") == DblpOutcome.Rejected(
        "MISSING_KEY",
        "record has no key attribute"
      )
    )
    assert(
      outcome("""<article key="journals/x/T"><year>2005</year></article>""")
        .isInstanceOf[DblpOutcome.Rejected]
    )
  }

  test("DOI, arXiv and ORCID are normalized from ee links and author attributes") {
    val w = accepted(
      """<article key="journals/x/Ids"><author orcid="0000-0002-1825-0097">Valid</author><author orcid="0000-0002-1825-0098">Bad Checksum</author>
        |<title>T</title><year>2016</year><ee>https://example.org/x.pdf</ee><ee>https://doi.org/10.1145/ABC.DEF</ee><ee>https://arxiv.org/abs/2101.00001v2</ee></article>""".stripMargin
    )
    assert(w.work.doi.contains("10.1145/abc.def"))
    assert(w.work.arxivId.contains("2101.00001"))
    assert(w.work.authors.map(_.orcid) == Vector(Some("0000-0002-1825-0097"), None))
    // The rejected ORCID is not lost: the raw attribute is kept as a work property.
    assert(w.properties.get("author.1.orcid").contains("0000-0002-1825-0098"))
    assert(w.properties.get("ee.0").contains("https://example.org/x.pdf"))
    assert(w.work.workId == StableIds.workId(WorkNamespace.Dblp, "journals/x/Ids").toOption.get)
  }

  test("an undeclared entity with a non-ASCII XML name is UnknownEntity with its name") {
    val out = DblpRecordParser.parseString(
      """<article key="journals/x/U"><author>A</author><title>x &café; y &ñ_1·;</title><year>2005</year></article>""",
      entities
    )
    assert(out == Left(DblpParseFailure.UnknownEntity("café")))
  }

  test("bytes illegal in the declared UTF-8 are Malformed, never replaced by U+FFFD") {
    val head = """<article key="journals/x/B"><author>A</author><title>bad """
      .getBytes(StandardCharsets.US_ASCII)
    val tail = " x</title><year>2005</year></article>".getBytes(StandardCharsets.US_ASCII)
    val bad = head ++ Array(0xc3, 0x28, 0x20, 0xff).map(_.toByte) ++ tail
    val out = DblpRecordParser.parse(bad, bad.length, StandardCharsets.UTF_8, entities)
    assert(out.left.toOption.exists(_.isInstanceOf[DblpParseFailure.Malformed]), s"got $out")
    assert(out.left.toOption.get.toString.contains(s"offset ${head.length}"))
    // The same bytes are legal ISO-8859-1, and valid multi-byte UTF-8 decodes exactly.
    assert(DblpRecordParser.parse(bad, bad.length, StandardCharsets.ISO_8859_1, entities).isRight)
    val good = (new String(head, StandardCharsets.US_ASCII) + "Größe 𠀀" +
      new String(tail, StandardCharsets.US_ASCII)).getBytes(StandardCharsets.UTF_8)
    val ok = DblpRecordParser.parse(good, good.length, StandardCharsets.UTF_8, entities)
    assert(ok.toOption.flatMap(_.first("title")).contains("bad Größe 𠀀 x"))
  }
