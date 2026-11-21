package scholarchain.adversarial

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.hadoop.blocking.Block
import upickle.default.read

/** Common names overflow blocking groups: re-keyed once, then routed to the oversize output,
  * never truncated, never silently dropped, never emitted as a block above maxBlockSize.
  *
  * With maxBlockSize = 3:
  *   - 5 works by "Wei Wang 000k" + "Li Na", all titled "Introduction." (2010): researcher group
  *     r|wang|w also gets 2 mentions from the Wang+Alpha works (7 > 3); fallback co:na (5 > 3)
  *     goes oversize, fallback co:alpha (2) becomes a block. Group r|na|l (5) -> co:wang (5) ->
  *     oversize. Work group w|introduction|2010-2014 (5) -> fa:wang (5) -> oversize.
  *   - 2 works by "Wei Wang" + "Ann Alpha" with distinct titles: r|alpha|a (2) is a block.
  *   - 3 single-author works by "Hua Zhang": r|zhang|h has exactly maxBlockSize members and is a
  *     primary block, not re-keyed.
  */
class BlockOverflowSpec extends AnyFunSuite:
  import Adv.*

  private val records: Seq[String] =
    (1 to 5).map(k =>
      article(s"journals/adv/WL$k", Seq(s"Wei Wang 000$k", "Li Na"), "Introduction.")
    ) ++
      Seq(
        article("journals/adv/WA1", Seq("Wei Wang", "Ann Alpha"), "Graph sampling at scale."),
        article("journals/adv/WA2", Seq("Wei Wang", "Ann Alpha"), "Streaming joins revisited.")
      ) ++
      Seq(
        article("journals/adv/HZ1", Seq("Hua Zhang"), "Quantum widgets."),
        article("journals/adv/HZ2", Seq("Hua Zhang"), "Tensor gadgets."),
        article("journals/adv/HZ3", Seq("Hua Zhang"), "Lattice doohickeys.")
      )

  private lazy val run: Result =
    runPipeline(dblpInput("blocks", dblpDoc("ISO-8859-1", records*)), config(maxBlockSize = 3))

  private lazy val works =
    run.rows("dblp", "work").map(WireCodec.decode[WorkRecord](_).toOption.get)
  private def mentions(pred: AuthorMention => Boolean) =
    works.flatMap(_.authors).filter(pred).map(_.mentionId).toSet
  private lazy val primaryBlocks = run.rows("scholarchain", "blocking_block").map(read[Block](_))
  private lazy val fallbackBlocks =
    run.rows("scholarchain", "blocking_block_fallback").map(read[Block](_))
  private lazy val oversize =
    run.rows("scholarchain", "oversize_block").map(WireCodec.decode[RejectRecord](_).toOption.get)

  test("no emitted block has more than maxBlockSize members") {
    val all = primaryBlocks ++ fallbackBlocks
    assert(all.nonEmpty)
    all.foreach(b => assert(b.members.size <= 3, s"${b.blockKey} has ${b.members.size} members"))
  }

  test("a group of exactly maxBlockSize is a primary block, not re-keyed") {
    val zhang = primaryBlocks.filter(_.blockKey == "r|zhang|h")
    assert(zhang.size == 1)
    assert(zhang.head.members.size == 3)
  }

  test("every common-name mention is accounted for exactly once: fallback block or oversize") {
    val wangs = mentions(_.rawName.startsWith("Wei Wang"))
    assert(wangs.size == 7)
    val inPrimary = primaryBlocks.flatMap(_.members.map(_.ref)).toSet
    assert(
      (wangs intersect inPrimary).isEmpty,
      "an overflowing group must not be emitted as a block"
    )
    val inFallback = fallbackBlocks.flatMap(_.members.map(_.ref))
    val inOversize = oversize.map(_.sourceRecordRef)
    val placed = (inFallback ++ inOversize).filter(wangs)
    assert(placed.sorted == wangs.toVector.sorted, s"placed=${placed.size}")
    val alphaWangs = mentions(m => m.rawName == "Wei Wang")
    assert(fallbackBlocks.exists(b => b.members.map(_.ref).toSet == alphaWangs))
  }

  test("oversize groups are counted per group and per member, with OVERSIZE_BLOCK rejects") {
    assert(run.counter("j05", "REKEYED_MEMBERS") == 7 + 5 + 5)
    assert(run.counter("j05b", "OVERSIZE_BLOCK") == 3)
    assert(run.counter("j05b", "OVERSIZE_BLOCK_MEMBERS") == 15)
    assert(oversize.size == 15)
    assert(oversize.forall(_.reasonCode == "OVERSIZE_BLOCK"))
    val lis = mentions(_.rawName == "Li Na")
    val introWorks = works.filter(_.title == "Introduction.").map(_.workId).toSet
    assert(
      oversize.map(_.sourceRecordRef).toSet == (mentions(
        _.rawName.matches("Wei Wang 000[1-5]")
      ) ++ lis ++ introWorks)
    )
  }

  test("no match candidate is produced from an oversize group") {
    val cands = run
      .rows("scholarchain", "match_candidate")
      .map(WireCodec.decode[MatchCandidate](_).toOption.get)
    val oversizeRefs = oversize.map(_.sourceRecordRef).toSet
    val leaked = cands.filter(c => oversizeRefs(c.leftRef) && oversizeRefs(c.rightRef))
    assert(leaked.isEmpty, leaked.map(_.blockKey))
    // The blocks that exist do produce candidates (Zhang 3 -> 3 pairs, Alpha 2 -> 1, Wang/alpha 2 -> 1).
    assert(cands.count(_.blockKey == "r|zhang|h") == 3)
  }

  test("the largest blocking group is reported with its key") {
    val stats = run.json("scholarchain", "stats_j05")
    val best = stats.maxBy(_("value").num)
    assert(best("value").num == 7)
    assert(best("key").str == "r|wang|w")
  }
