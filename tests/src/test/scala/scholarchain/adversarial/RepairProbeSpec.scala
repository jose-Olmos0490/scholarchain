package scholarchain.adversarial

import java.io.{ByteArrayOutputStream, File}
import java.nio.charset.StandardCharsets
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.ids.ExternalIds
import scholarchain.core.json.WireCodec
import scholarchain.core.model.{RejectRecord, WorkRecord}
import scholarchain.hadoop.dblp.{DblpNormalizer, DblpOutcome, DblpParseFailure, DblpRecordParser}
import scholarchain.hadoop.pairs.TeamExpansion
import scholarchain.hadoop.text.Normalize

/** Probes of the Repair 04/05 fixes: Cf characters at token edges and Cf-only names, NFC team
  * de-duplication for combining sequences without a precomposed form, strict decoding of illegal
  * bytes at split boundaries, and percent-encoded DOI edge cases (unit and pipeline).
  */
class RepairProbeSpec extends AnyFunSuite:
  import Adv.*

  /** Every non-ASCII code point as a numeric reference, so the parser sees exact code points. */
  private def esc(s: String): String =
    s.codePoints.toArray.map(cp => if cp < 128 then cp.toChar.toString else s"&#$cp;").mkString

  // -------------------------------------------------------------------------------------------
  // Cf characters
  // -------------------------------------------------------------------------------------------

  test("Cf characters at token edges are dropped without merging or splitting tokens") {
    val cases = Seq(
      "\u200BJohn Smith\u200B" -> "john smith",
      "John\u200B Smith" -> "john smith",
      "John \u200BSmith" -> "john smith",
      "\uFEFFJohn\u00AD Smith\u2060" -> "john smith",
      "Jo\u200Dhn Sm\u200Cith" -> "john smith",
      "\u202EJohn Smith\u202C" -> "john smith",
      // U+E0041 TAG LATIN CAPITAL LETTER A (supplementary Cf) inside a token.
      "John Smi\uDB40\uDC41th" -> "john smith",
      // Cf between a base letter and its combining mark.
      "Jos\u200B\u0065\u0301 Smith" -> "jose smith"
    )
    cases.foreach { (in, out) =>
      assert(Normalize.fold(in) == out, s"fold(${in.map(c => f"U+${c.toInt}%04X").mkString(" ")})")
    }
    assert(Normalize.surnameAndInitial("\u200BJohn Smith") == Some(("smith", "j")))
    assert(Normalize.surnameAndInitial("\u00ADJohn \u200BSmith\u200B") == Some(("smith", "j")))
  }

  private def normalized(xml: String): DblpOutcome =
    parse(xml) match
      case Left(f) => fail(s"parse failed: $f")
      case Right(raw) => DblpNormalizer.normalize(raw, ctx)

  test("an author name made only of Cf characters is MISSING_AUTHOR_NAME, like a blank one") {
    val invisible = Seq(
      "&#8203;" -> "ZWSP",
      "&#173;" -> "soft hyphen",
      "&#8203;&#8204;&#8205;&#8288;&#65279;" -> "ZWSP ZWNJ ZWJ WJ BOM",
      "&#173; &#8203;" -> "soft hyphen, space, ZWSP"
    )
    val problems = invisible.flatMap { (name, label) =>
      val xml = article("journals/adv/Cf1", Seq("Real Person", name, "Other Person"))
      normalized(xml) match
        case DblpOutcome.Rejected("MISSING_AUTHOR_NAME", _) => None
        case DblpOutcome.Accepted(dw) =>
          val m = dw.work.authors(1)
          Some(
            s"$label: accepted; mention 2 rawName=${m.rawName.map(c => f"U+${c.toInt}%04X")} " +
              s"normalizedName=${m.normalizedName.map(c => f"U+${c.toInt}%04X")}"
          )
        case other => Some(s"$label: $other")
    }
    assert(problems.isEmpty, problems.mkString("\n"))
  }

  // -------------------------------------------------------------------------------------------
  // NFC de-duplication
  // -------------------------------------------------------------------------------------------

  test("NFC dedup: combining sequences with no precomposed form, in either mark order") {
    // q + dot below (ccc 220) + dot above (ccc 230): no precomposed character exists; canonical
    // ordering makes both orders the same NFC string.
    val a = "Q\u0323\u0307uinn Lee"
    val b = "Q\u0307\u0323uinn Lee"
    val w = work(article("journals/adv/N1", Seq(a, "Other Person", b).map(esc)))
    assert(w.authors.map(_.rawName) == Vector(a, "Other Person", b))
    val team = TeamExpansion.team(w)
    assert(team.members.map(_.position) == Vector(1, 2), team.members.map(_.rawName))
    assert(team.removedDuplicates == 1)
  }

  test("NFC dedup: Hangul jamo vs syllable and canonical singletons are one member") {
    val jamo = "\u1100\u1161 Kim" // conjoining jamo, composes to U+AC00
    val syll = "\uAC00 Kim"
    val ohm = "\u2126mega Lee" // OHM SIGN, canonical singleton of U+03A9
    val omega = "\u03A9mega Lee"
    val w = work(article("journals/adv/N2", Seq(jamo, ohm, syll, omega).map(esc)))
    val team = TeamExpansion.team(w)
    assert(team.members.map(_.position) == Vector(1, 2), team.members.map(_.rawName))
    assert(team.removedDuplicates == 2)
    // Stable mention IDs still follow positions, and raw names are stored unchanged.
    assert(w.authors(2).rawName == syll)
    assert(w.authors.map(_.mentionId).distinct.size == 4)
  }

  test("NFC dedup does not merge different combining marks or a mark vs no mark") {
    val w = work(
      article(
        "journals/adv/N3",
        Seq("Q\u0307 Lee", "Q\u0323 Lee", "Q Lee", "Q\u0307\u0307 Lee").map(esc)
      )
    )
    val team = TeamExpansion.team(w)
    assert(team.size == 4, team.members.map(_.rawName))
    assert(TeamExpansion.pairs(team, 2010, RunId).count(_.isRight) == 6)
  }

  // -------------------------------------------------------------------------------------------
  // Illegal bytes at split boundaries (UTF-8 file)
  // -------------------------------------------------------------------------------------------

  private def utf8(s: String) = s.getBytes(StandardCharsets.UTF_8)

  /** UTF-8 document: X has a lone 0xFF; Y has a lead byte 0xC3 right before `</title>`; T is
    * truncated and its value ends with a lead byte 0xE2 right before the next record's `<`; Z is
    * valid with 2-, 3- and 4-byte characters.
    */
  private lazy val badBytes: Array[Byte] =
    val out = new ByteArrayOutputStream()
    def w(s: String): Unit = out.write(utf8(s))
    def raw(bs: Int*): Unit = bs.foreach(out.write)
    w("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<dblp>\n")
    w("""<article key="journals/adv/X"><author>X X</author><title>bad """)
    raw(0xff)
    w(""" byte</title><year>2010</year></article>""" + "\n")
    w("""<article key="journals/adv/Y"><author>Y Y</author><title>half """)
    raw(0xc3)
    w("""</title><year>2010</year></article>""" + "\n")
    w("""<article key="journals/adv/T"><author>T T</author><title>cut """)
    raw(0xe2, 0x82)
    w(
      """<article key="journals/adv/Z"><author>Zoë Ŝmith</author><title>ok é € 𝒜 ✓</title><year>2010</year></article>""" + "\n"
    )
    w("</dblp>\n")
    out.toByteArray

  test("illegal UTF-8 bytes at every split boundary: same values, strict Malformed, Z intact") {
    val f = writeBytes(new File(scratch("badbytes"), "dblp.xml"), badBytes)
    val (_, single) = readAll(f, Long.MaxValue)
    val expected = single.map(r => (r.offset, r.bytes.toVector))
    val diffs = (1L to f.length).flatMap { size =>
      val (_, recs) = readAll(f, size)
      if recs.map(r => (r.offset, r.bytes.toVector)) != expected then Some(size) else None
    }
    assert(diffs.isEmpty, s"split sizes with different values: ${diffs.take(20)}")
    assert(single.size == 4, single.map(_.text(StandardCharsets.UTF_8)))
    val parsed =
      single.map(r =>
        DblpRecordParser.parse(r.bytes, r.bytes.length, StandardCharsets.UTF_8, entities)
      )
    parsed.take(3).zipWithIndex.foreach { (p, i) =>
      p match
        case Left(DblpParseFailure.Malformed(msg)) =>
          assert(msg.contains("byte offset"), s"record $i: $msg")
        case other => fail(s"record $i: expected strict Malformed, got $other")
    }
    // The truncated value ends with the partial sequence, not with the next record's bytes.
    assert(single(2).bytes.takeRight(2).toVector == Vector(0xe2.toByte, 0x82.toByte))
    parsed(3) match
      case Right(rec) =>
        assert(rec.first("title") == Some("ok é € 𝒜 ✓"))
        assert(rec.first("author") == Some("Zoë Ŝmith"))
      case Left(e) => fail(s"Z did not parse: $e")
  }

  test("pipeline: illegal bytes with 37-byte splits give the single-split rejects and works") {
    def run(extra: (String, String)*) =
      runPipeline(dblpInputBytes("badbytes-pipe", badBytes), conf = localConf(extra*))
    val one = run()
    val many = run(
      "mapreduce.input.fileinputformat.split.maxsize" -> "37",
      "mapreduce.input.fileinputformat.split.minsize" -> "1"
    )
    def summary(r: Result) =
      (
        r.rows("dblp", "work").map(WireCodec.decode[WorkRecord](_).toOption.get.title).sorted,
        r.rows("dblp", "rejects_j01")
          .map(WireCodec.decode[RejectRecord](_).toOption.get)
          .map(x => x.sourceRecordRef -> x.reasonCode)
          .sorted
      )
    assert(
      summary(one) == (
        Vector("ok é € 𝒜 ✓"),
        Vector(
          "journals/adv/T" -> "MALFORMED_XML",
          "journals/adv/X" -> "MALFORMED_XML",
          "journals/adv/Y" -> "MALFORMED_XML"
        )
      )
    )
    assert(summary(many) == summary(one))
    assert(many.report("j01").mapTasks > 10)
  }

  // -------------------------------------------------------------------------------------------
  // Percent-encoded DOIs
  // -------------------------------------------------------------------------------------------

  test("DOI: double encoding decodes once; %2F and other escapes in the prefix") {
    assert(ExternalIds.normalizeDoi("https://doi.org/10.1000/a%252Fb") == Right("10.1000/a%2fb"))
    assert(ExternalIds.normalizeDoi("10.1000/a%2Fb") == Right("10.1000/a%2fb"))
    assert(ExternalIds.normalizeDoi("https://doi.org/10.1000/a%2Fb") == Right("10.1000/a/b"))
    assert(ExternalIds.normalizeDoi("https://doi.org/10.1000%2Fabc") == Right("10.1000/abc"))
    assert(ExternalIds.normalizeDoi("HTTPS://DX.DOI.ORG/10.1000%2fABC") == Right("10.1000/abc"))
    assert(ExternalIds.normalizeDoi("https://doi.org/%31%30%2E1000%2Fabc") == Right("10.1000/abc"))
    assert(
      ExternalIds.normalizeDoi("https://doi.org/10.1000/%C3%89t%C3%A9") == Right("10.1000/été")
    )
    // Bare and doi: forms are never decoded.
    assert(ExternalIds.normalizeDoi("10.1000%2Fabc").isLeft)
    assert(ExternalIds.normalizeDoi("doi:10.1000/a%2Fb") == Right("10.1000/a%2fb"))
  }

  test("DOI: every accepted value is a fixed point of normalizeDoi") {
    val inputs = Seq(
      "https://doi.org/10.1000/a%252Fb",
      "https://doi.org/10.1000/a%25",
      "https://doi.org/10.1000/a%2525zz",
      "https://doi.org/10.1000/%C3%89t%C3%A9",
      "doi:10.1000/X%y",
      "10.1000/ABC"
    )
    inputs.foreach { in =>
      ExternalIds.normalizeDoi(in) match
        case Right(v) => assert(ExternalIds.normalizeDoi(v) == Right(v), s"$in -> $v")
        case Left(e) => fail(s"$in rejected: $e")
    }
  }

  test("DOI: normalizeDoi is idempotent when a URL decodes to a trailing control character") {
    // The core WorkRecord contract requires doi == normalizeDoi(doi). A decoded value that a
    // second normalization changes (here: trim removes a trailing C0 control) breaks that
    // contract downstream. Either form is acceptable (Left, or a fixed point); a moving value is not.
    val inputs = Seq("%00", "%01", "%08", "%1F").map(e => s"https://doi.org/10.1000/abc$e")
    val moving = inputs.flatMap { in =>
      ExternalIds.normalizeDoi(in) match
        case Right(v) =>
          val again = ExternalIds.normalizeDoi(v)
          if again == Right(v) then None
          else
            def show(s: String) =
              s.map(c => if Character.isISOControl(c) then f"\\u${c.toInt}%04X" else c.toString)
                .mkString
            Some(s"$in -> ${show(v)} -> ${again.map(show)}")
        case Left(_) => None
    }
    assert(moving.isEmpty, moving.mkString("\n"))
  }

  test("DOI: malformed escapes and invalid UTF-8 are Left and never throw") {
    val bad = Seq(
      "https://doi.org/10.1000/abc%",
      "https://doi.org/10.1000/abc%2",
      "https://doi.org/10.1000/abc%G0",
      "https://doi.org/10.1000/abc%%41",
      "https://doi.org/10.1000/abc%zz",
      "https://doi.org/10.1000/abc%FF",
      "https://doi.org/10.1000/abc%C0%AF", // overlong '/'
      "https://doi.org/10.1000/abc%ED%A0%80", // encoded surrogate
      "https://doi.org/10.1000/abc%C3", // truncated sequence
      "https://doi.org/10.1000/abc%20def",
      "https://doi.org/%"
    )
    bad.foreach { in =>
      val r =
        try ExternalIds.normalizeDoi(in)
        catch case t: Throwable => fail(s"$in threw $t")
      assert(r.isLeft, s"$in -> $r")
    }
  }

  test("DOI: decoded control characters are never part of a normalized DOI") {
    // C0 controls, DEL and C1 controls are not graphic characters; a DOI containing them cannot be
    // a legal DOI name, and it would carry an invisible control into identifiers and outputs.
    val inputs = Seq(
      "https://doi.org/10.1000/abc%00",
      "https://doi.org/10.1000/abc%01def",
      "https://doi.org/10.1000/abc%1B",
      "https://doi.org/10.1000/abc%7F",
      "https://doi.org/10.1000/abc%C2%85",
      // Bare forms with the control inside the suffix (a trailing one is removed by trim).
      "10.1000/ab\u0000c",
      "10.1000/abc\u0001def",
      "10.1000/ab\u007Fc"
    )
    val accepted = inputs.flatMap { in =>
      ExternalIds.normalizeDoi(in) match
        case Right(v) if v.exists(Character.isISOControl) =>
          Some(
            s"${in.replace("\u0000", "\\u0000").replace("\u0001", "\\u0001").replace("\u007F", "\\u007F")} -> " +
              v.map(c => if Character.isISOControl(c) then f"\\u${c.toInt}%04X" else c.toString)
                .mkString
          )
        case _ => None
    }
    assert(accepted.isEmpty, accepted.mkString("\n"))
  }

  private val doiDoc = dblpDoc(
    "ISO-8859-1",
    article(
      "journals/adv/D1",
      Seq("Dee One"),
      extra = "\n<ee>https://doi.org/10.1000/abc%zz</ee>"
    ),
    article(
      "journals/adv/D2",
      Seq("Dee Two"),
      extra = "\n<ee>https://doi.org/10.1000/x%</ee>\n<ee>https://doi.org/10.1000%2FGood</ee>"
    ),
    article(
      "journals/adv/D3",
      Seq("Dee Three"),
      extra = "\n<ee>https://doi.org/10.1000/a%252Fb</ee>"
    ),
    article("journals/adv/D4", Seq("Dee Four"), extra = "\n<ee>10.1000/a%2Fb</ee>")
  )

  private lazy val doiRun = runPipeline(dblpInput("doi-pipe", doiDoc))

  test("pipeline: malformed DOI escapes give a missing DOI, never a crash or a reject") {
    val works = doiRun
      .rows("dblp", "work")
      .map(WireCodec.decode[WorkRecord](_).toOption.get)
      .map(w => w.sourceRecordId -> w.doi)
      .toMap
    assert(
      works == Map(
        "journals/adv/D1" -> None,
        "journals/adv/D2" -> Some("10.1000/good"),
        "journals/adv/D3" -> Some("10.1000/a%2fb"),
        "journals/adv/D4" -> Some("10.1000/a%2fb")
      )
    )
    assert(doiRun.counter("j01", "REJECTED") == 0)
    // D3 (URL, double-encoded) and D4 (bare) are one DOI: one exact-identifier outcome.
    val outcomes = doiRun.json("scholarchain", "exact_identifier_outcome")
    val doiOutcomes = outcomes.filter(_.toString.contains("10.1000/a%2fb"))
    assert(doiOutcomes.size == 1, outcomes)
  }

  test("pipeline: a DOI URL with an encoded control character does not reach WorkRecord.doi") {
    val doc = dblpDoc(
      "ISO-8859-1",
      article(
        "journals/adv/C1",
        Seq("Cee One"),
        extra = "\n<ee>https://doi.org/10.1000/abc%00</ee>"
      ),
      article(
        "journals/adv/C2",
        Seq("Cee Two"),
        extra = "\n<ee>https://doi.org/10.1000/abc%01</ee>"
      )
    )
    val r = runPipeline(dblpInput("doi-ctl", doc))
    val dois = r.rows("dblp", "work").map(WireCodec.decode[WorkRecord](_).toOption.get).map(_.doi)
    // Both records are otherwise valid: at worst the bad DOI is dropped, the work never is.
    assert(
      dois.size == 2,
      s"works=${dois.size}; j01=${r.report("j01").counters}; j01c=${r.report("j01c").counters}; " +
        s"rejects=${(r.rows("dblp", "rejects_j01") ++ r.rows("dblp", "rejects_j01c")).map(_.take(400))}"
    )
    val withControls = dois.flatten.filter(_.exists(Character.isISOControl))
    assert(
      withControls.isEmpty,
      withControls.map(
        _.map(c => if Character.isISOControl(c) then f"\\u${c.toInt}%04X" else c.toString).mkString
      )
    )
  }
