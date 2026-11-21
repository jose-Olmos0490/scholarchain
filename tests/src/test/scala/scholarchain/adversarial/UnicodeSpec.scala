package scholarchain.adversarial

import java.io.{ByteArrayOutputStream, File}
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.json.WireCodec
import scholarchain.core.model.{MatchEntityType, RejectRecord, WorkRecord}
import scholarchain.hadoop.blocking.{BlockingKeys, MatchFeatures}
import scholarchain.hadoop.pairs.TeamExpansion
import scholarchain.hadoop.text.Normalize

/** Unicode normalization, invisible characters and confusables in names, keys and teams. */
class UnicodeSpec extends AnyFunSuite:
  import Adv.*

  // -------------------------------------------------------------------------------------------
  // Folding and keys
  // -------------------------------------------------------------------------------------------

  test("a soft hyphen (U+00AD, legal in ISO-8859-1 DBLP) inside a surname does not split it") {
    assert(Normalize.surnameAndInitial("John Smi­th").contains(("smith", "j")))
  }

  test("a zero-width space (U+200B) inside a surname does not split it") {
    assert(Normalize.surnameAndInitial("John Smi​th").contains(("smith", "j")))
  }

  test("NFC and NFD spellings of a name fold to the same key") {
    val nfc = Normalizer.normalize("José Pérez", Normalizer.Form.NFC)
    val nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD)
    assert(nfc != nfd)
    assert(Normalize.personName(nfc) == Normalize.personName(nfd))
    assert(Normalize.surnameAndInitial(nfd).contains(("perez", "j")))
  }

  test("fullwidth and mathematical-alphanumeric compatibility forms fold to ASCII") {
    assert(Normalize.surnameAndInitial("Ｊｏｈｎ Ｓｍｉｔｈ").contains(("smith", "j")))
    // U+1D412.. MATHEMATICAL BOLD CAPITAL S, small m, i, t, h (supplementary plane)
    val boldSmith = "𝐒𝐦𝐢𝐭𝐡"
    assert(Normalize.surnameAndInitial("John " + boldSmith).contains(("smith", "j")))
  }

  test("folding is independent of the JVM default locale (Turkish dotted and dotless I)") {
    val before = Locale.getDefault
    val names = Seq("İsmail Öztürk", "IRMAK Iildiz", "Ilknur INCE")
    val root = names.map(Normalize.personName)
    try
      Locale.setDefault(Locale.forLanguageTag("tr-TR"))
      assert(names.map(Normalize.personName) == root)
    finally Locale.setDefault(before)
    assert(root.head == "ismail ozturk")
    assert(root(1) == "irmak iildiz")
  }

  test("a supplementary-plane initial keeps both UTF-16 code units") {
    val (surname, initial) = Normalize.surnameAndInitial("𠀀𠀁 Wang").get
    assert(surname == "wang")
    assert(initial == "𠀀")
  }

  test("confusables: a Cyrillic look-alike surname is never folded onto the Latin one") {
    // U+0405 CYRILLIC CAPITAL DZE looks like S; U+0430 CYRILLIC SMALL A looks like a.
    val latin = Normalize.surnameAndInitial("Anna Smith").get
    val cyr = Normalize.surnameAndInitial("Anna Ѕmith").get
    val cyrA = Normalize.surnameAndInitial("Аnna Smith").get
    assert(latin != cyr && latin._1 != cyr._1)
    assert(latin._2 != cyrA._2, "a Cyrillic initial must not silently equal the Latin one")
    assert(cyr._1.nonEmpty, "confusable letters are letters, never stripped")
  }

  // -------------------------------------------------------------------------------------------
  // Teams
  // -------------------------------------------------------------------------------------------

  private def workWith(key: String, authors: Seq[String]): WorkRecord =
    // Numeric character references keep the exact code points (the parser does not normalize).
    def esc(s: String) =
      s.codePoints.toArray.map(cp => if cp < 128 then cp.toChar.toString else s"&#$cp;").mkString
    work(article(key, authors.map(esc)))

  test("the same author spelled in NFC and NFD on one paper is one team member") {
    val nfc = "José Pérez"
    val nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD)
    val w = workWith("journals/adv/U1", Seq(nfc, "Other Person", nfd))
    assert(w.authors.map(_.rawName) == Vector(nfc, "Other Person", nfd))
    val team = TeamExpansion.team(w)
    assert(team.size == 2, s"members: ${team.members.map(_.rawName)}")
    assert(team.removedDuplicates == 1)
  }

  test("confusable look-alike authors on one paper stay two members (no false merge)") {
    val w = workWith("journals/adv/U2", Seq("Anna Smith", "Anna Ѕmith"))
    val team = TeamExpansion.team(w)
    assert(team.size == 2)
    assert(TeamExpansion.pairs(team, 2010, RunId).toVector.count(_.isRight) == 1)
  }

  test("confusable researcher mentions get different surname features") {
    val w1 = workWith("journals/adv/U3", Seq("Anna Smith", "Bo Li"))
    val w2 = workWith("journals/adv/U4", Seq("Anna Ѕmith", "Bo Li"))
    val m1 = BlockingKeys.members(w1).find(_.entityType == MatchEntityType.RESEARCHER).get
    val m2 = BlockingKeys.members(w2).find(_.entityType == MatchEntityType.RESEARCHER).get
    assert(BlockingKeys.primary(m1) != BlockingKeys.primary(m2))
    assert(MatchFeatures.features(m1, m2)("surnameEqual") == 0.0)
  }

  // -------------------------------------------------------------------------------------------
  // Encoded input through the reader and J01
  // -------------------------------------------------------------------------------------------

  private val utf8Titles = Vector(
    "深层学習 survey.",
    "Emoji 😀 and 𠀀 in titles.",
    "Größe Διάγραμμα."
  )

  private def utf8Doc: String =
    dblpDoc(
      "UTF-8",
      utf8Titles.zipWithIndex.map((t, i) => article(s"journals/adv/U8$i", Seq(s"李 娜$i"), t))*
    )

  test("multi-byte UTF-8 characters straddling every split boundary are read intact") {
    val f = write(new File(scratch("utf8-split"), "dblp.xml"), utf8Doc, StandardCharsets.UTF_8)
    val (_, single) = readAll(f, Long.MaxValue)
    assert(single.size == 3)
    (1L to 120L).foreach { size =>
      val (_, recs) = readAll(f, size)
      assert(
        recs.map(_.text(StandardCharsets.UTF_8)) == single.map(_.text(StandardCharsets.UTF_8)),
        s"size $size"
      )
    }
  }

  private lazy val utf8Run: Result =
    val good = utf8Doc.getBytes(StandardCharsets.UTF_8)
    // A fourth record whose title holds bytes that are not legal UTF-8 (0xC3 0x28, lone 0xFF).
    val bad = new ByteArrayOutputStream()
    bad.write(
      """<article mdate="2024-01-01" key="journals/adv/BadUtf8">
        |<author>Bad Bytes</author>
        |<title>Invalid """.stripMargin.getBytes(StandardCharsets.US_ASCII)
    )
    bad.write(Array(0xc3, 0x28, 0x20, 0xff).map(_.toByte))
    bad.write(
      " bytes.</title>\n<year>2010</year>\n</article>\n".getBytes(StandardCharsets.US_ASCII)
    )
    val text = new String(good, StandardCharsets.UTF_8)
    val cut = text.lastIndexOf("</dblp>")
    val out = new ByteArrayOutputStream()
    out.write(text.substring(0, cut).getBytes(StandardCharsets.UTF_8))
    out.write(bad.toByteArray)
    out.write("</dblp>\n".getBytes(StandardCharsets.UTF_8))
    runPipeline(dblpInputBytes("utf8", out.toByteArray))

  test("pipeline: UTF-8 titles and names (BMP, supplementary, CJK) survive J01/J01C exactly") {
    val works = utf8Run.rows("dblp", "work").map(WireCodec.decode[WorkRecord](_).toOption.get)
    val byKey = works.map(w => w.sourceRecordId -> w).toMap
    utf8Titles.zipWithIndex.foreach { (t, i) =>
      val w = byKey(s"journals/adv/U8$i")
      assert(w.title == t)
      assert(w.authors.map(_.rawName) == Vector(s"李 娜$i"))
    }
  }

  test(
    "pipeline: bytes that are illegal in the declared UTF-8 are rejected, not replaced by U+FFFD"
  ) {
    val works = utf8Run.rows("dblp", "work").map(WireCodec.decode[WorkRecord](_).toOption.get)
    val corrupted =
      works.filter(w => w.title.contains('�') || w.authors.exists(_.rawName.contains('�')))
    assert(
      corrupted.isEmpty,
      s"accepted with replacement characters: ${corrupted.map(w => w.sourceRecordId -> w.title)}"
    )
    val rejects =
      utf8Run.rows("dblp", "rejects_j01").map(WireCodec.decode[RejectRecord](_).toOption.get)
    assert(rejects.map(_.sourceRecordRef) == Vector("journals/adv/BadUtf8"))
  }
