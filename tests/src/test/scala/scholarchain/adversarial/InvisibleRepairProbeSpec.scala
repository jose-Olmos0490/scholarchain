package scholarchain.adversarial

import java.nio.charset.StandardCharsets
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.ids.ExternalIds
import scholarchain.core.json.WireCodec
import scholarchain.core.model.{GraphNode, WorkRecord}
import scholarchain.hadoop.dblp.{DblpNormalizer, DblpOutcome}
import scholarchain.hadoop.pairs.TeamExpansion
import scholarchain.hadoop.sources.*
import scholarchain.hadoop.text.Normalize

/** Probes of Repair 06 (core: `normalizeDoi` rejects non-graphic code points) and Repair 07
  * (hadoop: one `Normalize.isBlank` rule for names, titles and JSON/ORCID strings). The owners'
  * feature tests cover Cf-only values; these cover no-break spaces, line/paragraph separators,
  * ISO controls and mixes in every source, combining-mark-only names, the fate of non-graphic
  * DOIs in each source, and invisible-only non-name fields.
  *
  * Every invisible or special code point is built from its hex value with `u(...)`, so the source
  * holds no literal invisible characters.
  */
class InvisibleRepairProbeSpec extends AnyFunSuite:
  import Adv.*

  /** The string of the given code points (a lone surrogate is allowed). */
  private def u(cps: Int*): String = cps.map(cp => new String(Character.toChars(cp))).mkString

  private val NBSP = u(0xa0)
  private val NNBSP = u(0x202f)
  private val LS = u(0x2028)
  private val PS = u(0x2029)
  private val IDSP = u(0x3000)
  private val SOH = u(0x01)
  private val NEL = u(0x85)
  private val ZWSP = u(0x200b)
  private val ZWJ = u(0x200d)
  private val BOM = u(0xfeff)
  private val SHY = u(0xad)
  private val HighSurrogate = u(0xd800)
  private val LowSurrogate = u(0xdfff)

  private def cps(s: String): String = s.map(c => f"U+${c.toInt}%04X").mkString(" ")

  private val srcCtx = SourceContext("2026-10-14", RunId, "openalex/adv.jsonl")
  private val crCtx = srcCtx.copy(inputLocation = "crossref/adv.jsonl")
  private val orcidCtx = srcCtx.copy(inputLocation = "orcid/adv.xml")

  private def codes(e: Vector[Emitted]): Vector[String] =
    e.collect { case Emitted.Reject(c, _, _) => c }

  private def rowsOf(e: Vector[Emitted], table: String): Vector[String] =
    e.collect { case Emitted.Row(t, _, j) if t == table => j }

  private def workOf(e: Vector[Emitted]): WorkRecord =
    WireCodec.decode[WorkRecord](rowsOf(e, "work").head).fold(err => fail(err.toString), identity)

  private def nodeOf(e: Vector[Emitted], table: String): GraphNode =
    WireCodec.decode[GraphNode](rowsOf(e, table).head).fold(err => fail(err.toString), identity)

  /** JSON as a real file holds it: non-ASCII and controls as `\\uXXXX` escapes. */
  private def json(v: ujson.Value): String = v.render(escapeUnicode = true)

  /** Invisible-only values that are not Cf-only (the owners' tests cover Cf-only). */
  private val invisibleJson = Seq(
    NBSP -> "NBSP",
    LS -> "LINE SEPARATOR",
    PS -> "PARAGRAPH SEPARATOR",
    IDSP -> "IDEOGRAPHIC SPACE",
    SOH -> "C0 SOH",
    NEL -> "C1 NEL",
    (NBSP + ZWSP + "\t" + NNBSP + BOM) -> "mixed NBSP ZWSP TAB NNBSP BOM"
  )

  // XML 1.0 forbids C0 controls other than TAB/LF/CR, so the DBLP and ORCID variants use C1 instead.
  private val invisibleXml = Seq(
    "&#160;" -> "NBSP",
    "&#8232;" -> "LINE SEPARATOR",
    "&#8233;" -> "PARAGRAPH SEPARATOR",
    "&#12288;" -> "IDEOGRAPHIC SPACE",
    "&#133;" -> "C1 NEL",
    "&#160;&#8203;&#9;&#8239;&#65279;" -> "mixed NBSP ZWSP TAB NNBSP BOM"
  )

  private def dblpOutcome(xml: String): DblpOutcome =
    parse(xml) match
      case Left(f) => fail(s"parse failed: $f")
      case Right(raw) => DblpNormalizer.normalize(raw, ctx)

  // -------------------------------------------------------------------------------------------
  // Invisible-only names and titles in every source
  // -------------------------------------------------------------------------------------------

  test("DBLP: separator-, C1- and mixed-invisible authors and titles are missing, not accepted") {
    val problems = invisibleXml.flatMap { (v, label) =>
      val author = dblpOutcome(article("journals/adv/I1", Seq("Real Person", v, "Other Person")))
      val title = dblpOutcome(article("journals/adv/I2", Seq("Real Person"), title = v))
      val authorProblem = author match
        case DblpOutcome.Rejected("MISSING_AUTHOR_NAME", _) => None
        case other => Some(s"author $label: $other")
      val titleProblem = title match
        case DblpOutcome.Rejected("MISSING_TITLE", _) => None
        case other => Some(s"title $label: $other")
      authorProblem.toSeq ++ titleProblem
    }
    assert(problems.isEmpty, problems.mkString("\n"))
  }

  test("OpenAlex: an invisible raw name falls back to display_name; both invisible is a reject") {
    val problems = invisibleJson.flatMap { (v, label) =>
      def line(raw: String, display: String, title: String) = json(
        ujson.Obj(
          "id" -> "https://openalex.org/W1",
          "title" -> title,
          "display_name" -> "Display Title",
          "publication_year" -> 2010,
          "authorships" -> ujson.Arr(
            ujson.Obj("raw_author_name" -> "Ann Lee"),
            ujson.Obj("raw_author_name" -> raw, "author" -> ujson.Obj("display_name" -> display))
          )
        )
      )
      val fallback = OpenAlexNormalizer.works(line(v, "Bo Chen", v), srcCtx)
      val both = OpenAlexNormalizer.works(line(v, v, "Real Title"), srcCtx)
      val p1 =
        if codes(fallback).nonEmpty then Some(s"$label fallback: rejects ${codes(fallback)}")
        else
          val w = workOf(fallback)
          val m = w.authors(1)
          Option.when(
            m.rawName != "Bo Chen" || m.normalizedName != "bo chen" || w.title != "Display Title"
          )(s"$label fallback: rawName=${cps(m.rawName)} title=${cps(w.title)}")
      val p2 = Option.when(codes(both) != Vector("MISSING_AUTHOR_NAME"))(
        s"$label both invisible: ${codes(both)} rows=${rowsOf(both, "work").size}"
      )
      p1.toSeq ++ p2
    }
    assert(problems.isEmpty, problems.mkString("\n"))
  }

  test("Crossref: an invisible given or family next to a real part gives exactly the real part") {
    val problems = invisibleJson.flatMap { (v, label) =>
      def item(givenPart: String, familyPart: String) = json(
        ujson.Obj(
          "DOI" -> "10.1000/inv",
          "title" -> ujson.Arr(v, "Real Title"),
          "issued" -> ujson.Obj("date-parts" -> ujson.Arr(ujson.Arr(2021))),
          "author" -> ujson.Arr(ujson.Obj("given" -> givenPart, "family" -> familyPart))
        )
      )
      Seq((v, "Lee", "Lee", "lee"), ("Ann", v, "Ann", "ann")).flatMap { (g, f, raw, norm) =>
        val out = CrossrefNormalizer.works(item(g, f), crCtx)
        if codes(out).nonEmpty then Seq(s"$label given=${cps(g)}: rejects ${codes(out)}")
        else
          val w = workOf(out)
          val m = w.authors.head
          val node = nodeOf(out, "mention_node")
          Seq(
            Option.when(m.rawName != raw || m.normalizedName != norm)(
              s"$label given=${cps(g)}: rawName=${cps(m.rawName)} normalized=${cps(m.normalizedName)}"
            ),
            Option.when(node.label != raw)(s"$label: mention label ${cps(node.label)}"),
            Option.when(
              node.properties.values.exists(p => Normalize.isBlank(p))
            )(s"$label: invisible-only mention property ${node.properties}"),
            Option.when(w.title != "Real Title")(s"$label: title ${cps(w.title)}")
          ).flatten
      }
    }
    assert(problems.isEmpty, problems.mkString("\n"))
  }

  private def orcidXml(
      path: String,
      givenNames: String,
      family: String,
      credit: String,
      doi: String
  ) =
    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<record:record xmlns:record="r" xmlns:common="c" xmlns:person="p" xmlns:personal-details="pd" xmlns:activities="a">
       |<common:orcid-identifier><common:path>$path</common:path></common:orcid-identifier>
       |<person:person><person:name>
       |<personal-details:given-names>$givenNames</personal-details:given-names>
       |<personal-details:family-name>$family</personal-details:family-name>
       |<personal-details:credit-name>$credit</personal-details:credit-name>
       |</person:name></person:person>
       |<activities:activities-summary><activities:works><activities:group><common:external-ids>
       |<common:external-id><common:external-id-type>doi</common:external-id-type>
       |<common:external-id-value>$doi</common:external-id-value></common:external-id>
       |</common:external-ids></activities:group></activities:works></activities:activities-summary>
       |</record:record>""".stripMargin

  test("ORCID: invisible-only name parts are absent; an invisible-only iD path is MISSING_KEY") {
    val orcid = "0000-0002-1825-0097"
    val problems = invisibleXml.flatMap { (v, label) =>
      val creditInvisible =
        OrcidNormalizer.record(orcidXml(orcid, "Josiah", "Carberry", v, "10.1000/x"), orcidCtx)
      val givenInvisible =
        OrcidNormalizer.record(orcidXml(orcid, v, "Carberry", v, "10.1000/x"), orcidCtx)
      val pathInvisible =
        OrcidNormalizer.record(orcidXml(v, "Josiah", "Carberry", "J C", "10.1000/x"), orcidCtx)
      val n1 = nodeOf(creditInvisible, "researcher_node")
      val n2 = nodeOf(givenInvisible, "researcher_node")
      Seq(
        Option.when(n1.label != "Josiah Carberry")(s"$label credit: label ${cps(n1.label)}"),
        Option.when(n2.label != "Carberry")(s"$label given+credit: label ${cps(n2.label)}"),
        Option.when(n2.properties.contains("givenNames"))(s"$label: givenNames kept"),
        Option.when(codes(pathInvisible) != Vector("MISSING_KEY"))(
          s"$label path: ${codes(pathInvisible)} rows=${rowsOf(pathInvisible, "researcher_node").size}"
        )
      ).flatten
    }
    assert(problems.isEmpty, problems.mkString("\n"))
  }

  // -------------------------------------------------------------------------------------------
  // Combining-mark-only names; invisible variants of one name on one paper
  // -------------------------------------------------------------------------------------------

  test("a combining-mark-only DBLP author is visible: kept as the mark, with no blocking key") {
    Seq("&#769;", "&#8203;&#769;&#173;", "&#776;&#769;").foreach { v =>
      dblpOutcome(article("journals/adv/M1", Seq("Real Person", v))) match
        case DblpOutcome.Accepted(dw) =>
          val m = dw.work.authors(1)
          assert(m.normalizedName.nonEmpty, v)
          assert(
            !m.normalizedName.exists(c => Normalize.isInvisible(c.toInt)),
            cps(m.normalizedName)
          )
          assert(
            m.normalizedName.codePoints.allMatch(cp =>
              Character.getType(cp) == Character.NON_SPACING_MARK
            ),
            cps(m.normalizedName)
          )
          assert(Normalize.surnameAndInitial(m.rawName).isEmpty, cps(m.rawName))
        case DblpOutcome.Rejected("MISSING_AUTHOR_NAME", _) => () // also acceptable
        case other => fail(s"$v: $other")
    }
  }

  test("Cf-only spelling differences of one DBLP name on one paper are one team member") {
    // Folding already treats these as one name (attempt-2 defects 7-8); de-duplication must agree,
    // or the paper gets a self-coauthor pair.
    val variants = Seq(
      Seq("Wei Wang", "Wei Wa&#173;ng") -> "soft hyphen inside",
      Seq("Wei Wang", "Wei Wang&#8203;") -> "trailing ZWSP",
      Seq("Wei Wang 0001", "&#65279;Wei Wang 0001") -> "leading BOM, homonym suffix"
    )
    val problems = variants.flatMap { (names, label) =>
      val w = work(article("journals/adv/T1", names :+ "Other Person"))
      val t = TeamExpansion.team(w)
      Option.when(t.size != 2 || t.removedDuplicates != 1)(
        s"$label: team size ${t.size}, removed ${t.removedDuplicates}, keys " +
          w.authors.map(m => cps(TeamExpansion.mentionKey(m))).mkString(" | ")
      )
    }
    assert(problems.isEmpty, problems.mkString("\n"))
  }

  test(
    "an invisible character next to a DBLP homonym suffix does not make the suffix the surname"
  ) {
    val cases = Seq(
      ("Wei Wang 0001" + ZWSP) -> "trailing ZWSP",
      ("Wei Wang 0001" + SHY) -> "trailing soft hyphen",
      ("Wei Wang" + NBSP + "0001") -> "NBSP before the suffix"
    )
    val problems = cases.flatMap { (raw, label) =>
      val got = Normalize.surnameAndInitial(raw)
      Option.when(got != Some(("wang", "w")))(s"$label: $got")
    }
    assert(problems.isEmpty, problems.mkString("\n"))
  }

  // -------------------------------------------------------------------------------------------
  // normalizeDoi properties
  // -------------------------------------------------------------------------------------------

  private def pctEncodeAll(s: String): String =
    s.getBytes(StandardCharsets.UTF_8).map(b => f"%%${b & 0xff}%02X").mkString

  /** Separators and lone surrogates: never part of an accepted DOI, in any form. */
  private val nonGraphicInserts = Seq(NBSP, LS, PS, IDSP, NNBSP, HighSurrogate, LowSurrogate)

  test("normalizeDoi: every accepted value round-trips through the URL, doi: and padded forms") {
    val inserts = nonGraphicInserts ++ Seq(
      ZWSP,
      ZWJ,
      BOM,
      SHY,
      u(0x301), // combining acute
      u(0xc9), // E with acute
      u(0xdf), // sharp s
      u(0x130), // capital I with dot above (lower-cases to two code points)
      u(0x212a), // KELVIN SIGN (lower-cases to ASCII k)
      u(0x1f600), // emoji (surrogate pair)
      "%",
      "%25",
      "%C2%A0",
      "%E2%80%8B",
      "%E2%80%A8",
      "%ED%A0%80",
      "/",
      ":"
    )
    val bases = Seq("10.1000/AbC", "doi:10.1000/AbC", "https://doi.org/10.1000/AbC")
    val corpus = for
      b <- bases
      i <- inserts
      pos <- Seq(b.length, b.length - 1, b.indexOf('/') + 1)
    yield b.substring(0, pos) + i + b.substring(pos)
    val problems = corpus.flatMap { raw =>
      val r =
        try ExternalIds.normalizeDoi(raw)
        catch case e: Throwable => Left(s"THREW $e")
      r match
        case Left(msg) if msg.startsWith("THREW") => Seq(s"${cps(raw)}: $msg")
        case Left(_) => Nil
        case Right(v) =>
          val forms = Seq(
            v,
            "doi:" + v,
            "DOI:" + v,
            " \t" + v + " \n",
            "https://doi.org/" + pctEncodeAll(v),
            "HTTP://DX.DOI.ORG/" + pctEncodeAll(v)
          )
          val bad = forms.filter(f => ExternalIds.normalizeDoi(f) != Right(v))
          val graphic = v.codePoints.allMatch { cp =>
            !Character.isISOControl(cp) && !(cp >= 0xd800 && cp <= 0xdfff) &&
            !Character.isSpaceChar(cp)
          }
          bad.map(f =>
            s"${cps(raw)} -> ${cps(v)}; form ${cps(f)} -> ${ExternalIds.normalizeDoi(f)}"
          ) ++
            Option.when(!graphic)(s"${cps(raw)} -> ${cps(v)} holds a non-graphic code point")
    }
    assert(problems.isEmpty, problems.take(20).mkString("\n"))
    val accepted = corpus
      .filter(raw => nonGraphicInserts.exists(raw.contains))
      .filter(raw => ExternalIds.normalizeDoi(raw).isRight)
    assert(accepted.isEmpty, accepted.map(cps))
  }

  // -------------------------------------------------------------------------------------------
  // Fate of non-graphic DOIs in each source: the DOI is dropped (or the record rejected when the
  // DOI is the key), never silently mangled, and the work survives
  // -------------------------------------------------------------------------------------------

  test("OpenAlex: a non-graphic doi drops only the DOI; a valid ids.doi is still used") {
    def line(doi: String, idsDoi: Option[String]) = json(
      ujson.Obj(
        "id" -> "https://openalex.org/W2",
        "title" -> "T",
        "publication_year" -> 2010,
        "doi" -> doi,
        "ids" -> ujson.Obj.from(idsDoi.map(d => "doi" -> ujson.Str(d)).toSeq),
        "authorships" -> ujson.Arr(ujson.Obj("raw_author_name" -> "Ann Lee"))
      )
    )
    val bad = Seq(
      "https://doi.org/10.1000/a%C2%A0b",
      "https://doi.org/10.1000/a%E2%80%A8b",
      "10.1000/a" + NBSP + "b",
      "doi:10.1000/a" + PS + "b",
      "10.1000/a" + HighSurrogate + "b"
    )
    val problems = bad.flatMap { d =>
      val alone = OpenAlexNormalizer.works(line(d, None), srcCtx)
      val withIds = OpenAlexNormalizer.works(line(d, Some("https://doi.org/10.1000/AB")), srcCtx)
      Seq(
        if codes(alone).nonEmpty || rowsOf(alone, "work").size != 1 then
          Some(s"${cps(d)} alone: rejects ${codes(alone)}")
        else Option.when(workOf(alone).doi.nonEmpty)(s"${cps(d)} alone: doi ${workOf(alone).doi}"),
        if codes(withIds).nonEmpty || rowsOf(withIds, "work").size != 1 then
          Some(s"${cps(d)} + ids.doi: rejects ${codes(withIds)}")
        else
          Option.when(workOf(withIds).doi != Some("10.1000/ab"))(
            s"${cps(d)} + valid ids.doi: WorkRecord.doi=${workOf(withIds).doi}"
          )
      ).flatten
    }
    assert(problems.isEmpty, problems.mkString("\n"))
  }

  test("Crossref and ORCID: a non-graphic DOI is a visible MISSING_KEY or a dropped DOI") {
    val bad = Seq(
      "10.1000/a" + NBSP + "b",
      "https://doi.org/10.1000/a%E2%80%A8b",
      "10.1000/a" + HighSurrogate + "b"
    )
    val problems = bad.flatMap { d =>
      val item = json(
        ujson.Obj(
          "DOI" -> d,
          "title" -> ujson.Arr("T"),
          "author" -> ujson.Arr(ujson.Obj("given" -> "Ann", "family" -> "Lee"))
        )
      )
      val cr = CrossrefNormalizer.works(item, crCtx)
      val p1 = Option.when(codes(cr) != Vector("MISSING_KEY") || rowsOf(cr, "work").nonEmpty)(
        s"crossref ${cps(d)}: rejects ${codes(cr)} works ${rowsOf(cr, "work").size}"
      )
      p1.toSeq
    }
    // ORCID (XML cannot hold a lone surrogate): the node survives, the raw value is kept, and no
    // normalized DOI is derived from it.
    val orcidProblems = Seq("10.1000/a&#160;b", "10.1000/a&#8232;b").flatMap { d =>
      val out = OrcidNormalizer.record(
        orcidXml("0000-0002-1825-0097", "Josiah", "Carberry", "", d),
        orcidCtx
      )
      if codes(out).nonEmpty then Seq(s"orcid $d: rejects ${codes(out)}")
      else
        val n = nodeOf(out, "researcher_node")
        Seq(
          Option.when(n.properties.contains("workExternalId.0.normalizedDoi"))(
            s"orcid $d: normalizedDoi ${n.properties("workExternalId.0.normalizedDoi")}"
          ),
          Option.when(!n.properties.get("workExternalId.0.value").exists(_.contains("10.1000/a")))(
            s"orcid $d: raw value lost ${n.properties}"
          )
        ).flatten
    }
    assert((problems ++ orcidProblems).isEmpty, (problems ++ orcidProblems).mkString("\n"))
  }

  // -------------------------------------------------------------------------------------------
  // Invisible-only non-name JSON fields
  // -------------------------------------------------------------------------------------------

  test("invisible-only key fields are a MISSING_KEY reject, never a silent drop") {
    val problems = invisibleJson.flatMap { (v, label) =>
      val oaWork = OpenAlexNormalizer.works(
        json(ujson.Obj("id" -> v, "title" -> "T", "authorships" -> ujson.Arr())),
        srcCtx
      )
      val oaAuthor =
        OpenAlexNormalizer.authors(json(ujson.Obj("id" -> v, "display_name" -> "A")), srcCtx)
      val cr = CrossrefNormalizer.works(
        json(ujson.Obj("DOI" -> v, "title" -> ujson.Arr("T"), "author" -> ujson.Arr())),
        crCtx
      )
      Seq("openalex work" -> oaWork, "openalex author" -> oaAuthor, "crossref" -> cr).flatMap {
        (what, out) =>
          Option.when(codes(out) != Vector("MISSING_KEY") || out.size != 1)(
            s"$what id $label: $out"
          )
      }
    }
    assert(problems.isEmpty, problems.mkString("\n"))
  }

  test("an invisible-only type or abstract is absent everywhere, as Json.str now treats it") {
    // Repair 07 states that any invisible-only JSON string field is now absent. The WorkRecord
    // honours that (recordType "unknown"); the work node and the abstract reference must agree.
    val problems = invisibleJson.flatMap { (v, label) =>
      val oa = OpenAlexNormalizer.works(
        json(
          ujson.Obj(
            "id" -> "https://openalex.org/W3",
            "title" -> "T",
            "type" -> v,
            "language" -> v,
            "abstract_inverted_index" -> ujson.Obj(v -> ujson.Arr(0)),
            "authorships" -> ujson.Arr(ujson.Obj("raw_author_name" -> "Ann Lee"))
          )
        ),
        srcCtx
      )
      val cr = CrossrefNormalizer.works(
        json(
          ujson.Obj(
            "DOI" -> "10.1000/typ",
            "title" -> ujson.Arr("T"),
            "type" -> v,
            "publisher" -> v,
            "abstract" -> v,
            "author" -> ujson.Arr(ujson.Obj("given" -> "Ann", "family" -> "Lee"))
          )
        ),
        crCtx
      )
      Seq("openalex" -> oa, "crossref" -> cr).flatMap { (what, out) =>
        if codes(out).nonEmpty then Seq(s"$what $label: rejects ${codes(out)}")
        else
          val w = workOf(out)
          val node = nodeOf(out, "work_node")
          val invisibleProps = node.properties.filter((_, p) => Normalize.isBlank(p))
          Seq(
            Option.when(w.recordType != "unknown")(
              s"$what $label: recordType ${cps(w.recordType)}"
            ),
            Option.when(invisibleProps.nonEmpty)(
              s"$what $label: work_node keeps invisible-only ${invisibleProps.keys.toVector.sorted.mkString(",")}"
            ),
            Option.when(what == "crossref" && w.abstractTextRef.nonEmpty)(
              s"$what $label: abstractTextRef ${w.abstractTextRef.get} for an invisible abstract"
            )
          ).flatten
      }
    }
    assert(problems.isEmpty, problems.mkString("\n"))
  }

  // -------------------------------------------------------------------------------------------
  // Pipeline: non-graphic DBLP DOIs and a combining-mark-only author
  // -------------------------------------------------------------------------------------------

  private lazy val pipe = runPipeline(
    dblpInput(
      "invisible-pipe",
      dblpDoc(
        "ISO-8859-1",
        article(
          "journals/adv/P1",
          Seq("Pea One"),
          extra = "\n<ee>https://doi.org/10.1000/nb%C2%A0sp</ee>" +
            "\n<ee>doi:10.1000/ls&#8232;x</ee>\n<ee>https://doi.org/10.1000/Good1</ee>"
        ),
        article("journals/adv/P2", Seq("Pea Two"), extra = "\n<ee>10.1000/nb&#160;sp</ee>"),
        article(
          "journals/adv/P3",
          Seq("Pea Three"),
          extra = "\n<ee>https://doi.org/10.1000/a%E2%80%A9b</ee>"
        ),
        article("journals/adv/P4", Seq("Pea Four", "&#769;"))
      )
    )
  )

  test("pipeline: non-graphic DBLP DOIs are dropped, the works survive, a later valid ee wins") {
    val works = pipe
      .rows("dblp", "work")
      .map(WireCodec.decode[WorkRecord](_).toOption.get)
      .map(w => w.sourceRecordId -> w.doi)
      .toMap
    assert(works.get("journals/adv/P1") == Some(Some("10.1000/good1")), works)
    assert(works.get("journals/adv/P2") == Some(None), works)
    assert(works.get("journals/adv/P3") == Some(None), works)
    assert(pipe.counter("j01", "SCHEMA_FAILURE") == 0)
  }

  test("pipeline: a combining-mark-only author is either rejected or counted MISSING_KEY in j05") {
    val works = pipe.rows("dblp", "work").map(WireCodec.decode[WorkRecord](_).toOption.get)
    works.find(_.sourceRecordId == "journals/adv/P4") match
      case Some(w) =>
        assert(w.authors.size == 2)
        assert(pipe.counter("j05", "MISSING_KEY") == 1, pipe.report("j05").counters)
      case None =>
        val rejects = pipe.rows("dblp", "rejects_j01")
        assert(rejects.exists(r => r.contains("P4") && r.contains("MISSING_AUTHOR_NAME")), rejects)
  }
