package scholarchain.adversarial

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.model.MatchEntityType
import scholarchain.hadoop.blocking.BlockingKeys
import scholarchain.hadoop.dblp.{DblpNormalizer, DblpOutcome}

/** Missing, empty and degenerate fields: each has one documented outcome, never a crash and never
  * an accepted record that violates its contract.
  */
class MissingFieldsSpec extends AnyFunSuite:
  import Adv.*

  private def outcome(xml: String): DblpOutcome =
    DblpNormalizer.normalize(parse(xml).toOption.get, ctx)

  private def rejected(xml: String): String = outcome(xml) match
    case DblpOutcome.Rejected(code, _) => code
    case other => fail(s"not rejected: $other")

  test("whitespace-only and markup-only titles are MISSING_TITLE") {
    assert(rejected(article("journals/adv/F1", Seq("A B"), title = "  \n\t  ")) == "MISSING_TITLE")
    assert(
      rejected(
        article("journals/adv/F2", Seq("A B"), title = "<i> </i><sub></sub>")
      ) == "MISSING_TITLE"
    )
  }

  test("an empty or blank key attribute is MISSING_KEY") {
    assert(
      rejected("""<article key=""><title>T</title><year>2010</year></article>""") == "MISSING_KEY"
    )
    assert(
      rejected(
        """<article key="   "><title>T</title><year>2010</year></article>"""
      ) == "MISSING_KEY"
    )
    assert(
      rejected(
        """<article mdate="2020-01-01"><title>T</title><year>2010</year></article>"""
      ) == "MISSING_KEY"
    )
  }

  test("a blank author among real ones is MISSING_AUTHOR_NAME, never a shifted team") {
    assert(rejected(article("journals/adv/F3", Seq("A B", "   ", "C D"))) == "MISSING_AUTHOR_NAME")
    assert(rejected(article("journals/adv/F4", Seq("A B", "<i></i>"))) == "MISSING_AUTHOR_NAME")
  }

  test("a key with whitespace or control characters is never accepted verbatim") {
    outcome(
      """<article key="journals/adv/x&#10;y"><title>T</title><year>2010</year></article>"""
    ) match
      case DblpOutcome.Accepted(dw) => fail(s"accepted key ${dw.work.sourceRecordId}")
      case _ => succeed
  }

  test("a year surrounded by whitespace is still the year; a non-integer year is out of subset") {
    val w = work(article("journals/adv/F5", Seq("A B"), year = "\n  2010 \n"))
    assert(w.year.contains(2010))
    assert(
      outcome(article("journals/adv/F6", Seq("A B"), year = "2010a"))
        .isInstanceOf[DblpOutcome.OutOfSubset]
    )
    assert(
      outcome(article("journals/adv/F7", Seq("A B"), year = ""))
        .isInstanceOf[DblpOutcome.OutOfSubset]
    )
  }

  test("a record with no child elements at all is not accepted") {
    assert(
      !outcome("""<article key="journals/adv/F8"></article>""").isInstanceOf[DblpOutcome.Accepted]
    )
    assert(!outcome("""<article key="journals/adv/F9"/>""").isInstanceOf[DblpOutcome.Accepted])
  }

  test("empty and degenerate ee links give no DOI and no arXiv ID") {
    val w = work(
      article(
        "journals/adv/F10",
        Seq("A B"),
        extra =
          "\n<ee></ee>\n<ee>https://doi.org/</ee>\n<ee>https://arxiv.org/abs/</ee>\n<ee>doi:</ee>"
      )
    )
    assert(w.doi.isEmpty && w.arxivId.isEmpty)
  }

  test("punctuation-only author names and titles give no blocking key instead of an empty one") {
    val w = work(article("journals/adv/F11", Seq("-- .", "Real Person"), title = "?!? ..."))
    val ms = BlockingKeys.members(w)
    val workMember = ms.find(_.entityType == MatchEntityType.WORK).get
    assert(BlockingKeys.primary(workMember).isEmpty)
    val punct = ms.filter(_.entityType == MatchEntityType.RESEARCHER).head
    assert(BlockingKeys.primary(punct).isEmpty)
    val real = ms.filter(_.entityType == MatchEntityType.RESEARCHER)(1)
    assert(BlockingKeys.primary(real).contains("r|person|r"))
    // Keys never contain an empty component.
    ms.flatMap(BlockingKeys.primary).foreach(k => assert(!k.split('|').exists(_.isEmpty), k))
  }

  test("a homonym suffix alone is not stripped to an empty name") {
    val w = work(article("journals/adv/F12", Seq("0001", "Li 0002")))
    assert(w.authors.forall(_.normalizedName.nonEmpty))
    assert(w.authors(1).normalizedName == "li")
  }
