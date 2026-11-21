package scholarchain.adversarial.er

import java.text.Normalizer
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.model.{MatchOutcome, SourceType}
import scholarchain.er.features.Features
import scholarchain.er.ids.{ErIdType, ExactIdRules}
import scholarchain.er.text.TextNormalize

/** Unicode normalization and confusable characters in entity-resolution names and identifiers.
  */
class ErUnicodeConfusableSpec extends AnyFunSuite with Matchers:
  import ErAdv.*

  private val latin = TextNormalize.parseName("Wei Zhang")

  // --- canonical equivalence, compatibility forms, invisible characters ----------------------

  test("NFC and NFD spellings of a diacritic name parse to the same PersonName") {
    val nfc = Normalizer.normalize("José García-Núñez", Normalizer.Form.NFC)
    val nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD)
    nfc should not be nfd
    TextNormalize.parseName(nfd) shouldBe TextNormalize.parseName(nfc)
    Features.nameSimilarity(
      TextNormalize.parseName(nfc),
      TextNormalize.parseName(nfd)
    ) shouldBe 1.0
  }

  test("diacritics fold to the base letter (Zhāng, Müller/Muller, Øystein/Oystein)") {
    TextNormalize.parseName("Wéi Zhāng") shouldBe latin
    TextNormalize.parseName("Hans Müller").key shouldBe TextNormalize.parseName("Hans Muller").key
    TextNormalize.parseName("Øystein Ørsted").key shouldBe "orsted o"
  }

  test("full-width compatibility letters fold to ASCII") {
    TextNormalize.parseName("\uFF37\uFF45\uFF49 \uFF3A\uFF48\uFF41\uFF4E\uFF47") shouldBe latin
  }

  private val invisibles = Seq(
    "\u200B" -> "ZERO WIDTH SPACE",
    "\u200C" -> "ZERO WIDTH NON-JOINER",
    "\u200D" -> "ZERO WIDTH JOINER",
    "\u2060" -> "WORD JOINER",
    "\uFEFF" -> "ZERO WIDTH NO-BREAK SPACE (BOM)",
    "\u00AD" -> "SOFT HYPHEN",
    "\u200E" -> "LEFT-TO-RIGHT MARK",
    "\u200F" -> "RIGHT-TO-LEFT MARK",
    "\u034F" -> "COMBINING GRAPHEME JOINER"
  )

  for (ch, label) <- invisibles do
    test(s"an invisible $label inside the surname does not split or change the name") {
      val spoofed = s"Wei Zh${ch}ang"
      TextNormalize.parseName(spoofed) shouldBe latin
      Features.nameKey(spoofed) shouldBe "zhang w"
    }

  test("no-break and narrow no-break spaces separate tokens like an ASCII space") {
    TextNormalize.parseName("Wei\u00A0Zhang") shouldBe latin
    TextNormalize.parseName("Wei\u202FZhang") shouldBe latin
    TextNormalize.parseName("Zhang,\u00A0Wei") shouldBe latin
  }

  test("a homonym suffix after a no-break space is still removed") {
    TextNormalize.parseName("Wei Zhang\u00A00001") shouldBe latin
  }

  test("dblp author keys that differ only by a no-break space or a zero-width char agree") {
    ExactIdRules.normalizeDblpAuthorKey("Wei Zhang\u00A00001") shouldBe
      ExactIdRules.normalizeDblpAuthorKey("Wei Zhang 0001")
    ExactIdRules.normalizeDblpAuthorKey("Wei Zh\u200Bang 0001") shouldBe
      ExactIdRules.normalizeDblpAuthorKey("Wei Zhang 0001")
  }

  test("a thousand stacked combining marks fold away without error") {
    val zalgo = "Wei Zh" + "a" + ("\u0301" * 1000) + "ng"
    TextNormalize.parseName(zalgo) shouldBe latin
  }

  test("a DOI with a zero-width space or BOM normalizes to the clean DOI") {
    val clean = ExactIdRules.normalizeDoi("10.1145/3299869.3319891")
    clean.isRight shouldBe true
    ExactIdRules.normalizeDoi("10.1145/3299869\u200B.3319891") shouldBe clean
    ExactIdRules.normalizeDoi("\uFEFFhttps://doi.org/10.1145/3299869.3319891") shouldBe clean
  }

  // --- confusables in identifiers: a homoglyph must never create agreement --------------------

  test("a DOI containing a Cyrillic homoglyph is not the Latin DOI") {
    val latinDoi = ExactIdRules.normalizeDoi("10.1145/abc.123")
    val cyr = ExactIdRules.normalizeDoi("10.1145/\u0430bc.123") // CYRILLIC SMALL LETTER A
    latinDoi.isRight shouldBe true
    cyr should not be latinDoi
  }

  test("an ORCID iD written with full-width or Arabic-Indic digits is not silently a new iD") {
    val fullWidth =
      "\uFF10\uFF10\uFF10\uFF10-\uFF10\uFF10\uFF10\uFF12-\uFF11\uFF18\uFF12\uFF15-\uFF10\uFF10\uFF19\uFF17"
    val arabicIndic =
      "\u0660\u0660\u0660\u0660-\u0660\u0660\u0660\u0662-\u0661\u0668\u0662\u0665-\u0660\u0660\u0669\u0667"
    for raw <- Seq(fullWidth, arabicIndic) do
      // Either rejected, or normalized to the very same ASCII iD; never a distinct valid value
      // that would make a spurious ORCID disagreement (CONFLICT) against the ASCII spelling.
      val n = ExactIdRules.normalizeOrcid(raw)
      assert(n.isLeft || n == Right(orcidA), s"$raw normalized to $n")
  }

  test("a dblp author key with a Cyrillic homoglyph does not agree with the Latin key") {
    val a = ExactIdRules.normalizeDblpAuthorKey("Wei Zhang 0001")
    val b = ExactIdRules.normalizeDblpAuthorKey("Wei Zh\u0430ng 0001")
    a.isRight shouldBe true
    b should not be a
  }

  test("homoglyph dblp author keys do not ACCEPT two mentions by exact identifier") {
    val w1 = work(dblpWorkId("conf/a/X18"), authors = Vector("Wei Zhang", "Ann Lee"))
    val w2 = work(
      dblpWorkId("conf/b/Y21"),
      title = "Energy aware scheduling",
      year = Some(2021),
      authors = Vector("Wei Zh\u0430ng", "Bo Chen"),
      venues = Vector("ICDE")
    )
    val p = pair(
      mention(w1, 1, researcherIds = ids(ErIdType.DBLP_AUTHOR_KEY -> "Wei Zhang 0001")),
      mention(w2, 1, researcherIds = ids(ErIdType.DBLP_AUTHOR_KEY -> "Wei Zh\u0430ng 0001"))
    )
    val r = decide(p)
    r.decision.rulesFired should not contain "EXACT_ID_AGREE:DBLP_AUTHOR_KEY"
    withClue(explain(r)) { r.decision.decision should not be MatchOutcome.ACCEPTED }
  }

  // --- confusables in names: a single homoglyph must not produce a certain rejection ----------

  // D8: known limitation, deferred in Phase 5 (modules/entity-resolution/KNOWN-LIMITATIONS.md).
  ignore("a mixed-script surname (Cyrillic Es in 'Chen') folds to the same comparison key") {
    // U+0421 CYRILLIC CAPITAL LETTER ES renders exactly like Latin C.
    Features.nameKey("Li \u0421hen") shouldBe Features.nameKey("Li Chen")
  }

  // D8: known limitation, deferred in Phase 5 (modules/entity-resolution/KNOWN-LIMITATIONS.md).
  ignore("a Greek Omicron in a surname folds to the same comparison key as Latin O") {
    // U+039F GREEK CAPITAL LETTER OMICRON renders exactly like Latin O.
    Features.nameKey("Jan \u039Flsen") shouldBe Features.nameKey("Jan Olsen")
  }

  // D8: known limitation, deferred in Phase 5 (modules/entity-resolution/KNOWN-LIMITATIONS.md).
  ignore("same DOI, same author slot, one homoglyph in the surname: not REJECTED with certainty") {
    val doi = "10.1109/TPDS.2019.2912345"
    val authors = Vector("Li Chen", "Mark Hill", "Sara Diaz")
    val dblp = work(
      dblpWorkId("journals/tpds/ChenHD19"),
      authors = authors,
      workIds = ids(ErIdType.DOI -> doi)
    )
    val crossref = work(
      doiWorkId(doi),
      SourceType.CROSSREF,
      authors = authors.updated(0, "Li \u0421hen"),
      workIds = ids(ErIdType.DOI -> doi)
    )
    val r = decide(pair(mention(dblp, 1), mention(crossref, 1)))
    // A confusable-only difference in the same slot of the same DOI is the same author slot. A
    // REJECTED decision with confidence 1.0 (SAME_WORK_DIFFERENT_AUTHOR) asserts the opposite.
    withClue(explain(r)) { r.decision.decision should not be MatchOutcome.REJECTED }
  }

  // D8: known limitation, deferred in Phase 5 (modules/entity-resolution/KNOWN-LIMITATIONS.md).
  ignore("confusable-only coauthor spellings still count as coauthor overlap") {
    val a = Vector("Li Chen", "Mark Hill")
    val b = Vector("Li \u0421hen", "Mark Hill")
    Features.coauthorOverlap(a, b) shouldBe Some(1.0)
  }

  test("a homoglyph-spoofed name never scores above the genuine Latin spelling") {
    val genuine = Features.nameSimilarity(latin, TextNormalize.parseName("Wei Zhang"))
    val spoof = Features.nameSimilarity(latin, TextNormalize.parseName("W\u0435i Zh\u0430ng"))
    spoof should be <= genuine
  }
