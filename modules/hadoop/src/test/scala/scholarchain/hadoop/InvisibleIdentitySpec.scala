package scholarchain.hadoop

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.json.WireCodec
import scholarchain.core.model.{GraphNode, MatchEntityType, WorkRecord}
import scholarchain.hadoop.blocking.{BlockingKeys, MatchFeatures}
import scholarchain.hadoop.dblp.*
import scholarchain.hadoop.pairs.TeamExpansion
import scholarchain.hadoop.sources.*
import scholarchain.hadoop.text.Normalize

/** Repair 08: identity keys, homonym suffixes, fallback candidates and JSON properties follow the
  * same invisible-character and blankness rules as `Normalize.fold` and `Normalize.isBlank`.
  *
  * The source is pure ASCII: every invisible code point is built from its hex value with `u`.
  */
class InvisibleIdentitySpec extends AnyFunSuite:
  import HadoopTestSupport.entities

  private def u(cp: Int): String = new String(Character.toChars(cp))
  private val SHY = u(0xad)
  private val ZWSP = u(0x200b)
  private val BOM = u(0xfeff)
  private val NBSP = u(0xa0)
  private val IDSP = u(0x3000)
  private val LS = u(0x2028)
  private val PS = u(0x2029)
  private val NEL = u(0x85)
  private val SOH = u(0x01)
  private val NNBSP = u(0x202f)

  private def cps(s: String): String =
    s.codePoints.toArray.map(cp => f"U+$cp%04X").mkString(" ")

  private val dblpCtx =
    DblpContext(Set("article", "inproceedings"), 2000, 2025, "2024-01-01", "run:" + "a" * 64)
  private val srcCtx = SourceContext("2026-10-14", "run:" + "c" * 64, "openalex/x.jsonl")
  private val crossrefCtx = srcCtx.copy(inputLocation = "crossref/c.jsonl")

  /** Every non-ASCII code point as a numeric character reference, so the XML keeps it exactly. */
  private def esc(s: String): String =
    s.codePoints.toArray.map(cp => if cp < 128 then cp.toChar.toString else s"&#$cp;").mkString

  private def dblpXml(key: String, authors: Seq[String], extra: String = ""): String =
    s"""<article key="$key">${authors
        .map(a => s"<author>${esc(a)}</author>")
        .mkString}<title>A Title</title><year>2010</year>$extra</article>"""

  private def dblpWork(xml: String): DblpWork =
    DblpRecordParser
      .parseString(xml, entities)
      .fold(f => fail(s"parse failed: $f"), r => DblpNormalizer.normalize(r, dblpCtx)) match
      case DblpOutcome.Accepted(dw) => dw
      case other => fail(s"expected accepted, got $other")

  private def rowsOf(e: Vector[Emitted], table: String): Vector[String] =
    e.collect { case Emitted.Row(t, _, j) if t == table => j }

  private def codes(e: Vector[Emitted]): Vector[String] =
    e.collect { case Emitted.Reject(c, _, _) => c }

  // ---------------------------------------------------------------------------------------------
  // Identity keys (defect 1)
  // ---------------------------------------------------------------------------------------------

  test("Normalize.identityKey drops Cf, collapses invisible spacing, applies NFC, keeps case") {
    assert(Normalize.identityKey(s"Wei Wa${SHY}ng") == "Wei Wang")
    assert(Normalize.identityKey(s"${BOM}Wei Wang 0001$ZWSP") == "Wei Wang 0001")
    assert(Normalize.identityKey(s"Wei${NBSP}Wang") == "Wei Wang")
    assert(Normalize.identityKey(s" Wei $IDSP\t$NNBSP Wang$LS") == "Wei Wang")
    val acute = u(0x301)
    assert(Normalize.identityKey("Jose" + acute) == "Jos" + u(0xe9))
    // A Cf character between a base letter and its combining mark does not block NFC.
    assert(Normalize.identityKey("Jose" + ZWSP + acute) == "Jos" + u(0xe9))
    // Case, compatibility forms and the homonym suffix stay significant.
    assert(Normalize.identityKey("wei wang") != Normalize.identityKey("Wei Wang"))
    assert(Normalize.identityKey(u(0xff37) + "ei Wang") != Normalize.identityKey("Wei Wang"))
    assert(Normalize.identityKey("Wei Wang 0001") != Normalize.identityKey("Wei Wang 0002"))
    assert(Normalize.identityKey(ZWSP + SHY) == "")
  }

  test("Cf-only and invisible-spacing spellings of one DBLP name on one paper are one member") {
    val variants = Seq(
      Seq("Wei Wang", s"Wei Wa${SHY}ng") -> "soft hyphen inside",
      Seq("Wei Wang", s"Wei Wang$ZWSP") -> "trailing ZWSP",
      Seq("Wei Wang 0001", s"${BOM}Wei Wang 0001") -> "leading BOM, homonym suffix",
      Seq("Wei Wang", s"Wei${NBSP}Wang") -> "NBSP instead of space",
      Seq("Wei Wang", s"Wei $ZWSP Wang") -> "space ZWSP space"
    )
    variants.foreach { (names, label) =>
      val w = dblpWork(dblpXml("journals/inv/T1", names :+ "Other Person")).work
      // Raw values are stored unchanged.
      assert(w.authors.map(_.rawName) == (names :+ "Other Person").toVector, label)
      assert(w.authors.flatMap(_.sourceAuthorKey) == (names :+ "Other Person").toVector, label)
      val t = TeamExpansion.team(w)
      assert(
        t.size == 2 && t.removedDuplicates == 1,
        s"$label: ${t.members.map(m => cps(m.rawName))}"
      )
      assert(t.members.map(_.position) == Vector(1, 3), label)
      assert(TeamExpansion.pairs(t, 2010, "run:" + "a" * 64).count(_.isRight) == 1, label)
    }
  }

  test("different case, homonym number or compatibility form stay separate team members") {
    val separate = Seq(
      Seq("Wei Wang", "wei wang"),
      Seq("Wei Wang 0001", "Wei Wang 0002"),
      Seq("Wei Wang", u(0xff37) + "ei Wang")
    )
    separate.foreach { names =>
      val t = TeamExpansion.team(dblpWork(dblpXml("journals/inv/T2", names)).work)
      assert(t.size == 2 && t.removedDuplicates == 0, names.map(cps))
    }
  }

  test("sourceAuthorKeyEqual compares identity keys (feature version v2)") {
    assert(MatchFeatures.FeatureVersion == "hadoop-blocking-features-v2")
    def member(key: String, names: Seq[String]) =
      BlockingKeys
        .members(dblpWork(dblpXml(key, names)).work)
        .filter(_.entityType == MatchEntityType.RESEARCHER)
        .head
    val a = member("journals/inv/F1", Seq("Wei Wang 0001", "Bo Li"))
    val b = member("journals/inv/F2", Seq(s"Wei Wang 0001$ZWSP", "Bo Li"))
    val c = member("journals/inv/F3", Seq("Wei Wang 0002", "Bo Li"))
    assert(BlockingKeys.primary(a) == BlockingKeys.primary(b))
    assert(MatchFeatures.features(a, b)("sourceAuthorKeyEqual") == 1.0)
    assert(MatchFeatures.features(a, c)("sourceAuthorKeyEqual") == 0.0)
    // The block member still carries the raw key.
    assert(b.sourceAuthorKey.contains(s"Wei Wang 0001$ZWSP"))
  }

  // ---------------------------------------------------------------------------------------------
  // Homonym suffix (defect 2)
  // ---------------------------------------------------------------------------------------------

  test("the dblp homonym suffix is found next to Cf characters and after any invisible space") {
    val cases = Seq(
      s"Wei Wang 0001$ZWSP",
      s"Wei Wang 0001$SHY",
      s"Wei Wang${NBSP}0001",
      s"Wei Wang${IDSP}0001",
      s"Wei Wang${NNBSP}0001",
      s"Wei Wang 00${SHY}01",
      s"Wei Wang$ZWSP 0001",
      s"Wei Wang 0001$NBSP",
      s"Wei Wang\t0001"
    )
    cases.foreach { raw =>
      assert(Normalize.surnameAndInitial(raw) == Some(("wang", "w")), cps(raw))
      assert(Normalize.personName(raw) == "wei wang", cps(raw))
    }
    // Unchanged: a number glued to the surname is not a homonym suffix, nor is a 3-digit number.
    assert(Normalize.surnameAndInitial("Wei Wang0001") == Some(("wang0001", "w")))
    assert(Normalize.surnameAndInitial("Wei Wang 001") == Some(("001", "w")))
  }

  test("blocking key of a DBLP name with an invisible character beside the suffix") {
    val w = dblpWork(dblpXml("journals/inv/B1", Seq(s"Wei Wang 0001$ZWSP", "Bo Li"))).work
    val m = BlockingKeys.members(w).filter(_.entityType == MatchEntityType.RESEARCHER).head
    assert(BlockingKeys.primary(m) == Some("r|wang|w"))
    assert(w.authors.head.normalizedName == "wei wang")
  }

  // ---------------------------------------------------------------------------------------------
  // First candidate that normalizes (defect 3)
  // ---------------------------------------------------------------------------------------------

  private def openAlexWork(fields: (String, ujson.Value)*): String =
    val o = ujson.Obj("id" -> "https://openalex.org/W1", "title" -> "T", "publication_year" -> 2010)
    fields.foreach((k, v) => o(k) = v)
    o.render()

  private def work(out: Vector[Emitted]): WorkRecord =
    WireCodec.decode[WorkRecord](rowsOf(out, "work").head).toOption.get

  private def node(out: Vector[Emitted], table: String): GraphNode =
    WireCodec.decode[GraphNode](rowsOf(out, table).head).toOption.get

  test("OpenAlex: an invalid doi does not hide a valid ids.doi") {
    val badDois = Seq(
      "https://doi.org/10.1000/a%C2%A0b",
      "https://doi.org/10.1000/a%E2%80%A8b",
      s"10.1000/a${NBSP}b",
      s"doi:10.1000/a${PS}b",
      "10.1000/a" + 0xd800.toChar + "b",
      "not a doi"
    )
    badDois.foreach { bad =>
      val line = openAlexWork(
        "doi" -> bad,
        "ids" -> ujson.Obj("doi" -> "https://doi.org/10.1000/AB"),
        "authorships" -> ujson.Arr()
      )
      val out = OpenAlexNormalizer.works(line, srcCtx)
      assert(codes(out).isEmpty, out)
      assert(work(out).doi == Some("10.1000/ab"), cps(bad))
    }
    // A valid doi still wins over ids.doi, and no valid candidate gives no DOI.
    val both = openAlexWork(
      "doi" -> "https://doi.org/10.1000/FIRST",
      "ids" -> ujson.Obj("doi" -> "https://doi.org/10.1000/second"),
      "authorships" -> ujson.Arr()
    )
    assert(work(OpenAlexNormalizer.works(both, srcCtx)).doi == Some("10.1000/first"))
    val none = openAlexWork(
      "doi" -> s"10.1000/a${NBSP}b",
      "ids" -> ujson.Obj("doi" -> "nope"),
      "authorships" -> ujson.Arr()
    )
    assert(work(OpenAlexNormalizer.works(none, srcCtx)).doi == None)
  }

  test("OpenAlex: an invalid author.orcid does not hide a valid raw_orcid") {
    val line = openAlexWork(
      "authorships" -> ujson.Arr(
        ujson.Obj(
          "raw_author_name" -> "Josiah Carberry",
          "author" -> ujson.Obj("orcid" -> "https://orcid.org/0000-0002-1825-0098"),
          "raw_orcid" -> "0000-0002-1825-0097"
        )
      )
    )
    val out = OpenAlexNormalizer.works(line, srcCtx)
    assert(codes(out).isEmpty, out)
    assert(work(out).authors.head.orcid == Some("0000-0002-1825-0097"))
  }

  test("DBLP: a blank first <journal> does not hide a later one") {
    val dw = dblpWork(
      dblpXml(
        "journals/inv/J1",
        Seq("Ann Lee"),
        s"<journal>${esc(ZWSP + NBSP)}</journal><journal>Real Journal</journal>"
      )
    )
    assert(dw.venueName == Some("Real Journal"))
  }

  // ---------------------------------------------------------------------------------------------
  // One blankness rule on every property path (defect 4)
  // ---------------------------------------------------------------------------------------------

  private val invisibleValues =
    Seq(NBSP, LS, PS, IDSP, SOH, NEL, NBSP + ZWSP + "\t" + NNBSP + BOM, ZWSP)

  test("Json.scalar and Json.nonEmpty use Normalize.isBlank") {
    invisibleValues.foreach { v =>
      assert(Json.scalar(ujson.Str(v)) == None, cps(v))
      assert(!Json.nonEmpty(ujson.Obj("k" -> v), "k"), cps(v))
      assert(!Json.nonEmpty(ujson.Obj("k" -> ujson.Arr(v, ujson.Null)), "k"), cps(v))
      assert(!Json.nonEmpty(ujson.Obj("k" -> ujson.Obj(v -> ujson.Arr(0))), "k"), cps(v))
    }
    assert(Json.scalar(ujson.Str(" a ")) == Some(" a "))
    assert(Json.scalar(ujson.Num(3)) == Some("3"))
    assert(Json.nonEmpty(ujson.Obj("k" -> ujson.Obj("word" -> ujson.Arr(0))), "k"))
    assert(Json.nonEmpty(ujson.Obj("k" -> ujson.Num(0)), "k"))
  }

  test("OpenAlex: an invisible-only type, language or ids entry is absent from the work node") {
    invisibleValues.foreach { v =>
      val line = openAlexWork(
        "type" -> v,
        "language" -> v,
        "ids" -> ujson.Obj("mag" -> v, "openalex" -> "https://openalex.org/W1"),
        "abstract_inverted_index" -> ujson.Obj(v -> ujson.Arr(0)),
        "authorships" -> ujson.Arr()
      )
      val out = OpenAlexNormalizer.works(line, srcCtx)
      assert(codes(out).isEmpty, out)
      val w = work(out)
      assert(w.recordType == "unknown", cps(v))
      assert(w.abstractTextRef == None, cps(v))
      val n = node(out, "work_node")
      Seq("type", "language", "ids.mag", "abstractTextRef").foreach(k =>
        assert(!n.properties.contains(k), s"$k for ${cps(v)}")
      )
      assert(n.properties.get("ids.openalex") == Some("https://openalex.org/W1"))
      assert(n.properties.values.forall(p => !Normalize.isBlank(p)), cps(v))
    }
  }

  test("Crossref: an invisible-only type, publisher or abstract is absent everywhere") {
    invisibleValues.foreach { v =>
      val item = ujson
        .Obj(
          "DOI" -> "10.1000/typ",
          "title" -> ujson.Arr("T"),
          "type" -> v,
          "publisher" -> v,
          "abstract" -> v,
          "author" -> ujson.Arr(ujson.Obj("given" -> "Ann", "family" -> "Lee"))
        )
        .render()
      val out = CrossrefNormalizer.works(item, crossrefCtx)
      assert(codes(out).isEmpty, out)
      val w = work(out)
      assert(w.recordType == "unknown", cps(v))
      assert(w.abstractTextRef == None, cps(v))
      val n = node(out, "work_node")
      Seq("type", "publisher", "abstractTextRef").foreach(k =>
        assert(!n.properties.contains(k), s"$k for ${cps(v)}")
      )
      assert(n.properties.values.forall(p => !Normalize.isBlank(p)), cps(v))
    }
    // A real abstract is still referenced.
    val real = ujson
      .Obj(
        "DOI" -> "10.1000/typ",
        "title" -> ujson.Arr("T"),
        "abstract" -> "<jats:p>Text</jats:p>",
        "author" -> ujson.Arr()
      )
      .render()
    assert(
      work(CrossrefNormalizer.works(real, crossrefCtx)).abstractTextRef ==
        Some("crossref/c.jsonl#10.1000/typ/abstract")
    )
  }

  test("DBLP: blank field text and attribute values are not stored; indexes follow the source") {
    val dw = dblpWork(
      dblpXml(
        "journals/inv/P1",
        Seq("Ann Lee"),
        s"""<ee type="${esc(NBSP)}">${esc(ZWSP)}</ee><ee type="oa">https://example.org/x</ee>""" +
          s"<pages>${esc(IDSP)}</pages><journal>J</journal>"
      )
    )
    val p = dw.properties
    assert(!p.contains("ee.0"))
    assert(!p.contains("ee.0.type"))
    assert(p.get("ee.1") == Some("https://example.org/x"))
    assert(p.get("ee.1.type") == Some("oa"))
    assert(!p.contains("pages.0"))
    assert(p.get("journal.0") == Some("J"))
    assert(p.values.forall(v => !Normalize.isBlank(v)))
  }
