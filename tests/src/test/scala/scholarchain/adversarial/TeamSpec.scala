package scholarchain.adversarial

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.ids.StableIds
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.hadoop.blocking.{BlockingKeys, MatchFeatures}
import scholarchain.hadoop.pairs.TeamExpansion

/** Reordered author lists and a giant team above maxPairExpansionTeamSize. */
class TeamSpec extends AnyFunSuite:
  import Adv.*

  private def namePairs(w: WorkRecord): Set[Set[String]] =
    val team = TeamExpansion.team(w)
    val name = w.authors.map(a => a.position -> a.rawName).toMap
    TeamExpansion
      .pairs(team, 2010, RunId)
      .map(_.toOption.get)
      .map { p =>
        assert(StableIds.mentionId(w.workId, p.positionA.get).toOption.contains(p.memberA))
        assert(StableIds.mentionId(w.workId, p.positionB.get).toOption.contains(p.memberB))
        Set(name(p.positionA.get), name(p.positionB.get))
      }
      .toSet

  // -------------------------------------------------------------------------------------------
  // Reordered author lists
  // -------------------------------------------------------------------------------------------

  test("a reordered author list yields the same unordered name pairs, positions following names") {
    val w1 = work(article("journals/adv/O1", Seq("Ann A", "Bob B", "Cy C", "Dee D")))
    val w2 = work(article("journals/adv/O2", Seq("Dee D", "Bob B", "Ann A", "Cy C")))
    val p1 = namePairs(w1)
    assert(p1.size == 6)
    assert(p1 == namePairs(w2))
  }

  test("a duplicate in different list positions gives the same team after de-duplication") {
    val w1 = work(article("journals/adv/O3", Seq("Ann A", "Bob B", "Ann A", "Cy C")))
    val w2 = work(article("journals/adv/O4", Seq("Ann A", "Ann A", "Bob B", "Cy C")))
    val t1 = TeamExpansion.team(w1)
    val t2 = TeamExpansion.team(w2)
    assert(t1.members.map(_.rawName) == Vector("Ann A", "Bob B", "Cy C"))
    assert(t2.members.map(_.rawName) == Vector("Ann A", "Bob B", "Cy C"))
    assert(
      t1.members.map(_.position) == Vector(1, 2, 4),
      "first occurrence and source positions kept"
    )
    assert(t2.members.map(_.position) == Vector(1, 3, 4))
    assert(namePairs(w1) == namePairs(w2))
    assert(TeamExpansion.authorPapers(t1, RunId).forall(_.teamSize == 3))
  }

  test("homonym-suffixed names are different people and are never de-duplicated") {
    val w = work(article("journals/adv/O5", Seq("Wei Wang 0001", "Wei Wang 0002", "Wei Wang")))
    assert(TeamExpansion.team(w).size == 3)
    assert(namePairs(w).size == 3)
  }

  test("work match features are invariant under author reordering and symmetric") {
    val w1 =
      work(article("journals/adv/O6", Seq("Ann A", "Bob B", "Cy C"), title = "Same title here."))
    val w2 =
      work(article("journals/adv/O7", Seq("Cy C", "Ann A", "Bob B"), title = "Same title here."))
    val m1 = BlockingKeys.members(w1).head
    val m2 = BlockingKeys.members(w2).head
    assert(m1.entityType == MatchEntityType.WORK && m2.entityType == MatchEntityType.WORK)
    assert(BlockingKeys.primary(m1) == BlockingKeys.primary(m2))
    val f = MatchFeatures.features(m1, m2)
    assert(f == MatchFeatures.features(m2, m1))
    assert(f("authorSurnameJaccard") == 1.0)
    assert(f("authorCountDelta") == 0.0)
    assert(f("titleTokensEqual") == 1.0)
  }

  // -------------------------------------------------------------------------------------------
  // Routing boundaries (pure)
  // -------------------------------------------------------------------------------------------

  test("routing boundaries: threshold is exclusive, max expansion inclusive") {
    import TeamExpansion.Route.*
    assert(TeamExpansion.route(5, 5, 8) == Expand(false))
    assert(TeamExpansion.route(6, 5, 8) == Expand(true))
    assert(TeamExpansion.route(8, 5, 8) == Expand(true))
    assert(TeamExpansion.route(9, 5, 8) == Oversize)
    assert(TeamExpansion.pairCount(2000) == 1999000L)
    assert(TeamExpansion.pairCount(65536) == 2147450880L, "no Int overflow")
  }

  test("the size used for routing is the de-duplicated team size") {
    // 10 mentions, 2 repeats -> 8 distinct: with maxPairExpansionTeamSize = 8 it must expand.
    val names = (1 to 8).map(i => s"Person P$i") ++ Seq("Person P1", "Person P2")
    val t = TeamExpansion.team(work(article("journals/adv/O8", names)))
    assert(t.size == 8 && t.removedDuplicates == 2)
    assert(TeamExpansion.route(t.size, 5, 8) == TeamExpansion.Route.Expand(true))
    assert(TeamExpansion.pairs(t, 2010, RunId).count(_.isRight) == 28)
  }

  // -------------------------------------------------------------------------------------------
  // Giant team through LocalJobRunner (default thresholds 50 / 2000)
  // -------------------------------------------------------------------------------------------

  private val giantSize = 2001
  private val giant = article(
    "journals/adv/Giant",
    (1 to giantSize).map(i => f"Member$i%04d Giantteam"),
    title = "A giant collaboration."
  )
  private val large51 =
    article("journals/adv/Large51", (1 to 51).map(i => f"Person$i%02d Largeteam"))
  private val small3 = article("journals/adv/Small3", Seq("Ann A", "Bob B", "Cy C"))
  private val giantDoc = dblpDoc("ISO-8859-1", small3, giant, large51)

  private lazy val single: Result = runPipeline(dblpInput("giant-1", giantDoc))
  private lazy val split: Result = runPipeline(
    dblpInput("giant-n", giantDoc),
    conf = localConf(
      "mapreduce.input.fileinputformat.split.maxsize" -> "4096",
      "mapreduce.input.fileinputformat.split.minsize" -> "1"
    )
  )

  private def giantId(r: Result) =
    r.rows("dblp", "work")
      .map(WireCodec.decode[WorkRecord](_).toOption.get)
      .find(_.sourceRecordId == "journals/adv/Giant")
      .get
      .workId

  test("giant team: the record is larger than the reader buffer and is read whole") {
    assert(giant.length > 64 * 1024)
    assert(single.counter("j01", "INPUT_RECORDS") == 3)
    assert(single.counter("j01", "REJECTED") == 0)
    val w = single
      .rows("dblp", "work")
      .map(WireCodec.decode[WorkRecord](_).toOption.get)
      .find(_.sourceRecordId == "journals/adv/Giant")
      .get
    assert(w.authors.size == giantSize)
    assert(w.authors.last.rawName == f"Member$giantSize%04d Giantteam")
  }

  test("giant team above maxPairExpansionTeamSize: not expanded, routed and counted") {
    val id = giantId(single)
    assert(single.counter("j07", "OVERSIZE_AUTHOR_TEAM") == 1)
    val over =
      single.rows("dblp", "oversize_team").map(WireCodec.decode[PublicationTeam](_).toOption.get)
    assert(over.map(t => t.workId -> t.teamSize) == Vector(id -> giantSize))
    val pairs = single
      .rows("dblp", "coauthor_pair")
      .map(WireCodec.decode[CoauthorPairCandidate](_).toOption.get)
    assert(!pairs.exists(_.workId == id))
    assert(single.counter("j07", "EMITTED_PAIRS") == 3 + 51 * 50 / 2)
    assert(pairs.size == 3 + 51 * 50 / 2)
  }

  test("giant team still emits its team and every author-paper row (max team size stays correct)") {
    val id = giantId(single)
    val teams =
      single.rows("dblp", "publication_team").map(WireCodec.decode[PublicationTeam](_).toOption.get)
    assert(teams.find(_.workId == id).map(_.teamSize).contains(giantSize))
    val aps = single
      .rows("dblp", "author_paper")
      .map(WireCodec.decode[AuthorPaperRecord](_).toOption.get)
      .filter(_.workId == id)
    assert(aps.size == giantSize)
    assert(aps.forall(_.teamSize == giantSize))
    assert(aps.map(_.position).sorted == (1 to giantSize).toVector)
    val stats = single.json("scholarchain", "stats_j07").filter(_("metric").str == "maxTeamSize")
    val best = stats.maxBy(_("value").num)
    assert(best("value").num == giantSize && best("key").str == id)
  }

  test("a 51-author team is large and fully expanded (1275 pairs)") {
    val large =
      single.rows("dblp", "large_team").map(WireCodec.decode[PublicationTeam](_).toOption.get)
    assert(large.exists(_.teamSize == 51))
    assert(!large.exists(_.teamSize == 3))
  }

  private val RunIdPattern = "run:[0-9a-f]{64}".r
  private def logical(r: Result, ds: String, table: String) =
    r.rows(ds, table).map(RunIdPattern.replaceAllIn(_, "run:X")).sorted

  test("a giant record straddling ~20 splits gives the same output as one split") {
    assert(split.report("j01").mapTasks > 10, s"map tasks: ${split.report("j01").mapTasks}")
    assert(split.counter("j01", "INPUT_RECORDS") == 3)
    Seq(
      "work",
      "author_mention",
      "publication_team",
      "author_paper",
      "coauthor_pair",
      "oversize_team"
    ).foreach { t =>
      assert(logical(split, "dblp", t) == logical(single, "dblp", t), s"table $t differs")
    }
  }
