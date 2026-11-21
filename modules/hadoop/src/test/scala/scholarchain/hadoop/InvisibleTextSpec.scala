package scholarchain.hadoop

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.json.WireCodec
import scholarchain.core.model.{GraphNode, WorkRecord}
import scholarchain.hadoop.dblp.*
import scholarchain.hadoop.sources.*
import scholarchain.hadoop.text.Normalize

/** Repair 07: a required text field made only of whitespace, no-break spaces, controls or
  * invisible format characters (Unicode Cf) is missing, in every normalizer. A non-blank value
  * whose folded form is empty falls back to a form without invisible characters.
  */
class InvisibleTextSpec extends AnyFunSuite:
  import HadoopTestSupport.entities

  private val dblpCtx =
    DblpContext(Set("article", "inproceedings"), 2000, 2025, "2024-01-01", "run:" + "a" * 64)
  private val srcCtx = SourceContext("2026-10-14", "run:" + "c" * 64, "openalex/x.jsonl")

  private def dblp(title: String, authors: Seq[String]): DblpOutcome =
    val xml =
      s"""<article key="journals/inv/X1">${authors
          .map(a => s"<author>$a</author>")
          .mkString}<title>$title</title><year>2010</year><journal>J</journal></article>"""
    DblpRecordParser
      .parseString(xml, entities)
      .fold(f => fail(s"parse failed: $f"), r => DblpNormalizer.normalize(r, dblpCtx))

  private def codes(e: Vector[Emitted]): Vector[String] =
    e.collect { case Emitted.Reject(c, _, _) => c }

  private def rowsOf(e: Vector[Emitted], table: String): Vector[String] =
    e.collect { case Emitted.Row(t, _, j) if t == table => j }

  private def cps(s: String): String = s.map(c => f"U+${c.toInt}%04X").mkString(" ")

  test("Normalize.isBlank: whitespace, no-break spaces, controls and Cf only") {
    val blank = Seq(
      "",
      " \t\n",
      "​",
      "­",
      " ",
      " 　",
      "­ ​",
      "​‌‍⁠﻿",
      "‮‬",
      "\u0007",
      "󠁁" // U+E0041 TAG LATIN CAPITAL LETTER A (supplementary Cf)
    )
    blank.foreach(s => assert(Normalize.isBlank(s), cps(s)))
    assert(Normalize.isBlank(null))
    val notBlank = Seq("a", "​a", "-", "李", "­-​", "́")
    notBlank.foreach(s => assert(!Normalize.isBlank(s), cps(s)))
  }

  test("Normalize.stripInvisible and fallbackLower drop Cf and invisible edges only") {
    assert(Normalize.stripInvisible("​-­​") == "-")
    assert(Normalize.stripInvisible(" ? ​! ") == "? !")
    assert(Normalize.stripInvisible("​") == "")
    assert(Normalize.fallbackLower("​É? ") == "é?")
  }

  test("DBLP: an author made only of Cf characters is MISSING_AUTHOR_NAME, like a blank one") {
    val invisible = Seq(
      "",
      "&#8203;",
      "&#173;",
      "&#8203;&#8204;&#8205;&#8288;&#65279;",
      "&#173; &#8203;",
      "&#160;",
      "&#8238;&#8236;"
    )
    invisible.foreach { name =>
      dblp("A Title", Seq("Real Person", name, "Other Person")) match
        case DblpOutcome.Rejected(code, detail) =>
          assert(code == "MISSING_AUTHOR_NAME", name)
          assert(detail == "journals/inv/X1 author 2 is blank", name)
        case other => fail(s"$name: expected MISSING_AUTHOR_NAME, got $other")
    }
  }

  test("DBLP: a title made only of Cf characters is MISSING_TITLE") {
    Seq("&#8203;", "&#173; &#8203;", "&#160;", "&#65279;").foreach { t =>
      assert(
        dblp(t, Seq("Real Person")) == DblpOutcome.Rejected(
          "MISSING_TITLE",
          "journals/inv/X1 has no title"
        ),
        t
      )
    }
  }

  test("DBLP: a punctuation-only name or title falls back without invisible characters") {
    dblp("&#8203;?&#173;", Seq("Real Person", "&#8203;-&#173;")) match
      case DblpOutcome.Accepted(dw) =>
        val m = dw.work.authors(1)
        assert(m.rawName == "​-­", "raw name is stored unchanged")
        assert(m.normalizedName == "-", cps(m.normalizedName))
        assert(dw.work.normalizedTitle == "?", cps(dw.work.normalizedTitle))
        assert(dw.work.title == "​?­", "raw title is stored unchanged")
      case other => fail(s"expected accepted, got $other")
  }

  test("OpenAlex: Cf-only author name is MISSING_AUTHOR_NAME, Cf-only title is MISSING_TITLE") {
    val invisibleAuthor =
      """{"id":"https://openalex.org/W1","title":"T","publication_year":2010,"authorships":[{"raw_author_name":"Ann Lee"},{"raw_author_name":"​­"},{"raw_author_name":"Bo Chen"}]}"""
    assert(
      codes(OpenAlexNormalizer.works(invisibleAuthor, srcCtx)) == Vector("MISSING_AUTHOR_NAME")
    )
    val displayOnly =
      """{"id":"https://openalex.org/W1","title":"T","authorships":[{"author":{"display_name":"﻿"}}]}"""
    assert(codes(OpenAlexNormalizer.works(displayOnly, srcCtx)) == Vector("MISSING_AUTHOR_NAME"))
    val invisibleTitle =
      """{"id":"https://openalex.org/W1","title":"​","display_name":"­ ","authorships":[]}"""
    assert(codes(OpenAlexNormalizer.works(invisibleTitle, srcCtx)) == Vector("MISSING_TITLE"))
  }

  test("OpenAlex: punctuation-only name and title fall back without invisible characters") {
    val line =
      """{"id":"https://openalex.org/W1","title":"​?","publication_year":2010,"authorships":[{"raw_author_name":"​-­"}]}"""
    val out = OpenAlexNormalizer.works(line, srcCtx)
    assert(codes(out).isEmpty, out)
    val w = WireCodec.decode[WorkRecord](rowsOf(out, "work").head).toOption.get
    assert(w.authors.head.normalizedName == "-", cps(w.authors.head.normalizedName))
    assert(w.normalizedTitle == "?", cps(w.normalizedTitle))
  }

  test(
    "Crossref: Cf-only given and family name is MISSING_AUTHOR_NAME; Cf-only title MISSING_TITLE"
  ) {
    val invisibleAuthor =
      """{"DOI":"10.1145/X.Y","title":["T"],"author":[{"given":"Ann","family":"Lee"},{"given":"​","family":"­"}]}"""
    val loc = srcCtx.copy(inputLocation = "crossref/c.jsonl")
    assert(codes(CrossrefNormalizer.works(invisibleAuthor, loc)) == Vector("MISSING_AUTHOR_NAME"))
    val invisibleName =
      """{"DOI":"10.1145/X.Y","title":["T"],"author":[{"name":"⁠ "}]}"""
    assert(codes(CrossrefNormalizer.works(invisibleName, loc)) == Vector("MISSING_AUTHOR_NAME"))
    val invisibleTitle =
      """{"DOI":"10.1145/X.Y","title":["​"],"author":[{"given":"Ann","family":"Lee"}]}"""
    assert(codes(CrossrefNormalizer.works(invisibleTitle, loc)) == Vector("MISSING_TITLE"))
  }

  test("Crossref: a Cf-only given name is absent, so the name is the family name") {
    val item =
      """{"DOI":"10.1145/X.Y","title":["T"],"issued":{"date-parts":[[2021]]},"author":[{"given":"​","family":"Lee"}]}"""
    val out = CrossrefNormalizer.works(item, srcCtx.copy(inputLocation = "crossref/c.jsonl"))
    assert(codes(out).isEmpty, out)
    val w = WireCodec.decode[WorkRecord](rowsOf(out, "work").head).toOption.get
    assert(w.authors.head.rawName == "Lee", cps(w.authors.head.rawName))
  }

  test("ORCID: a Cf-only credit name is absent and the label comes from given and family names") {
    val xml =
      """<?xml version="1.0" encoding="UTF-8"?>
        |<record:record xmlns:record="r" xmlns:common="c" xmlns:person="p" xmlns:personal-details="pd">
        |<common:orcid-identifier><common:path>0000-0002-1825-0097</common:path></common:orcid-identifier>
        |<person:person><person:name><personal-details:given-names>Josiah</personal-details:given-names><personal-details:family-name>Carberry</personal-details:family-name>
        |<personal-details:credit-name>&#8203;&#173;</personal-details:credit-name></person:name></person:person>
        |</record:record>""".stripMargin
    val out = OrcidNormalizer.record(xml, srcCtx.copy(inputLocation = "orcid/r.xml"))
    val n = WireCodec.decode[GraphNode](rowsOf(out, "researcher_node").head).toOption.get
    assert(n.label == "Josiah Carberry", cps(n.label))
    assert(!n.properties.contains("creditName"))
  }
