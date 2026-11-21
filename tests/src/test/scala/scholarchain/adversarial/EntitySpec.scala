package scholarchain.adversarial

import java.nio.charset.StandardCharsets
import org.scalatest.funsuite.AnyFunSuite
import scala.util.Try
import scholarchain.hadoop.dblp.{DblpParseFailure, DblpRecordParser, DtdEntities}

/** Unknown, case-confused, non-ASCII, external and recursive entities. */
class EntitySpec extends AnyFunSuite:
  import Adv.*

  private def rec(title: String, authorAttr: String = ""): String =
    s"""<article key="journals/adv/E1"><author$authorAttr>A B</author><title>$title</title><year>2010</year></article>"""

  test("an undeclared entity inside an attribute value is UnknownEntity") {
    val r = parse(rec("T", """ orcid="&bogus;""""))
    assert(r == Left(DblpParseFailure.UnknownEntity("bogus")))
  }

  test("entity names are case-sensitive: &UUML; is unknown while &Uuml; and &uuml; differ") {
    assert(parse(rec("&UUML;")) == Left(DblpParseFailure.UnknownEntity("UUML")))
    val upper = parse(rec("&Uuml;")).toOption.get.first("title")
    val lower = parse(rec("&uuml;")).toOption.get.first("title")
    assert(upper.contains("Ü") && lower.contains("ü"))
  }

  test("an undeclared entity with a non-ASCII name is UnknownEntity, not Malformed or dropped") {
    // `café` is a legal XML Name; it is not declared in the DTD.
    val r = parse(rec("x &café; y"))
    assert(r == Left(DblpParseFailure.UnknownEntity("café")), s"got $r")
  }

  test("an escaped ampersand before a name is text, not an entity reference") {
    val r = parse(rec("&amp;bogus; and &amp;uuml;"))
    assert(r.toOption.flatMap(_.first("title")).contains("&bogus; and &uuml;"), s"got $r")
  }

  test("a numeric reference that spells an entity is not re-resolved") {
    // &#38; is '&'; the result must be the literal text "&bogus;", not an entity lookup.
    val r = parse(rec("&#38;bogus;"))
    assert(r.toOption.flatMap(_.first("title")).contains("&bogus;"), s"got $r")
  }

  private def dtd(body: String): Array[Byte] =
    (new String(
      java.nio.file.Files.readAllBytes(dtdFile.toPath),
      StandardCharsets.ISO_8859_1
    ) + body)
      .getBytes(StandardCharsets.ISO_8859_1)

  test("XXE: an external general entity in the DTD is never resolved into record text") {
    val ents = DtdEntities.load(dtd("""<!ENTITY xxe SYSTEM "file:///etc/hostname">"""))
    assert(!ents.contains("xxe"))
    val r = DblpRecordParser.parseString(rec("&xxe;"), ents)
    assert(r == Left(DblpParseFailure.UnknownEntity("xxe")), s"got $r")
  }

  test("XXE: an external parameter entity (file or network) makes the DTD load fail") {
    val file = Try(DtdEntities.load(dtd("""<!ENTITY % ext SYSTEM "file:///etc/hostname"> %ext;""")))
    assert(file.isFailure, s"file parameter entity was accepted: $file")
    val net =
      Try(DtdEntities.load(dtd("""<!ENTITY % net SYSTEM "http://127.0.0.1:9/x.dtd"> %net;""")))
    assert(net.isFailure, s"network parameter entity was accepted: $net")
  }

  test("billion laughs: nested entity definitions never expand exponentially") {
    val chain = (1 to 9)
      .map(i => s"""<!ENTITY lol$i "${("&lol" + (i - 1) + ";") * 10}">""")
      .mkString("\n")
    val ents = DtdEntities.load(dtd("<!ENTITY lol0 \"lol\">\n" + chain))
    val t0 = System.nanoTime()
    val r = DblpRecordParser.parseString(rec("&lol9;"), ents)
    val ms = (System.nanoTime() - t0) / 1000000
    assert(ms < 5000, s"took $ms ms")
    r match
      case Right(raw) => assert(raw.first("title").forall(_.length < 100000))
      case Left(_) => succeed
  }

  test("an entity that expands to markup characters stays character data") {
    val ents = DtdEntities.load(dtd("""<!ENTITY inj "&#60;year&#62;1999&#60;/year&#62;">"""))
    val r = DblpRecordParser.parseString(rec("x&inj;"), ents).toOption.get
    assert(r.all("year").map(_.text) == Vector("2010"), "entity text must not create elements")
    assert(r.first("title").contains("x<year>1999</year>"))
  }
