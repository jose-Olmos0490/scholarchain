package scholarchain.er

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.er.TestData.*
import scholarchain.er.policy.MatchPolicy
import scholarchain.er.review.*

class ReviewQueueSpec extends AnyFunSuite with Matchers:

  private val result = ok(MatchPolicy.decide(borderlineResearcherPair, ctx()))

  private def entries(n: Int, s: Stratum, prefix: String): Vector[QueueEntry] =
    (0 until n).toVector.map(i =>
      QueueEntry(f"rq1-$prefix$i%04d", s, borderlineResearcherPair, result)
    )

  test("pair IDs are stable and independent of the decision") {
    val p = borderlineResearcherPair
    ReviewQueue.pairId(p) shouldBe ReviewQueue.pairId(p)
    ReviewQueue.pairId(p) should startWith("rq1-")
    ReviewQueue.pairId(p).length shouldBe 20
    ReviewQueue.pairId(p.copy(hadoopFeatures = Map.empty)) shouldBe ReviewQueue.pairId(p)
  }

  test("select: fills each quota when enough pairs exist") {
    val all =
      entries(80, Stratum.SHARED_EXACT_ID, "a") ++ entries(80, Stratum.LIKELY_NON_MATCH, "b") ++
        entries(150, Stratum.BORDERLINE, "c")
    val chosen = ReviewQueue.select(all, ReviewQueue.Quotas(50, 50, 100))
    chosen.size shouldBe 200
    chosen.count(_.stratum == Stratum.SHARED_EXACT_ID) shouldBe 50
    chosen.count(_.stratum == Stratum.LIKELY_NON_MATCH) shouldBe 50
    chosen.count(_.stratum == Stratum.BORDERLINE) shouldBe 100
    chosen.map(_.pairId).distinct.size shouldBe 200
  }

  test("select: a short stratum is filled from the others, without duplicates") {
    val all =
      entries(120, Stratum.SHARED_EXACT_ID, "a") ++ entries(10, Stratum.LIKELY_NON_MATCH, "b") ++
        entries(30, Stratum.BORDERLINE, "c") ++ entries(60, Stratum.HIGH_SCORE, "d")
    val chosen = ReviewQueue.select(all, ReviewQueue.Quotas(50, 50, 100))
    chosen.size shouldBe 200
    chosen.map(_.pairId).distinct.size shouldBe 200
    chosen.count(_.stratum == Stratum.LIKELY_NON_MATCH) shouldBe 10
    chosen.count(_.stratum == Stratum.BORDERLINE) shouldBe 30
  }

  test("select: fewer pairs than requested returns each pair once, never padding") {
    val all = entries(7, Stratum.SHARED_EXACT_ID, "a") ++ entries(3, Stratum.BORDERLINE, "b")
    val chosen = ReviewQueue.select(all, ReviewQueue.Quotas(50, 50, 100))
    chosen.size shouldBe 10
    chosen.map(_.pairId).toSet shouldBe all.map(_.pairId).toSet
  }

  test("select: output is ordered by pair ID, not by stratum or score") {
    val all = entries(3, Stratum.BORDERLINE, "z") ++ entries(3, Stratum.SHARED_EXACT_ID, "a")
    val chosen = ReviewQueue.select(all, ReviewQueue.Quotas(1, 1, 1))
    chosen.map(_.pairId) shouldBe chosen.map(_.pairId).sorted
  }

  test("queue row has exactly the queue columns, empty label and notes, no system values") {
    val e = QueueEntry("rq1-0000000000000000", Stratum.BORDERLINE, borderlineResearcherPair, result)
    val row = ReviewQueue.queueRow(e)
    row.size shouldBe ReviewQueue.QueueColumns.size
    row(ReviewQueue.QueueColumns.indexOf("human_label")) shouldBe ""
    row(ReviewQueue.QueueColumns.indexOf("notes")) shouldBe ""
    row should not contain result.decision.decision.toString
    row should not contain f"${result.score}%.6f"
    row should not contain Stratum.BORDERLINE.toString
    row(ReviewQueue.QueueColumns.indexOf("kind")) shouldBe "researcher"
    val left = borderlineResearcherPair.left.asInstanceOf[scholarchain.er.model.ResearcherView]
    row(ReviewQueue.QueueColumns.indexOf("left_name_or_title")) shouldBe left.name
    row(ReviewQueue.QueueColumns.indexOf("source_record_id")) shouldBe "rec#author1"
  }

  test("key row maps the pair ID to the system decision and score") {
    val e = QueueEntry("rq1-0000000000000000", Stratum.BORDERLINE, borderlineResearcherPair, result)
    val k = ReviewQueue.keyRow(e)
    k.size shouldBe ReviewQueue.KeyColumns.size
    k(ReviewQueue.KeyColumns.indexOf("decision")) shouldBe result.decision.decision.toString
    k(ReviewQueue.KeyColumns.indexOf("score")) shouldBe f"${result.score}%.6f"
    k(ReviewQueue.KeyColumns.indexOf("left_ref")) shouldBe borderlineResearcherPair.left.ref
  }

  test("CSV quoting round-trips commas, quotes and newlines") {
    val rows = Vector(Vector("a,b", "say \"hi\"", "line1\nline2", ""), Vector("x", "", "", "z"))
    ReviewQueue.parseCsv(ReviewQueue.csv(Vector("c1", "c2", "c3", "c4"), rows)) shouldBe
      (Vector("c1", "c2", "c3", "c4") +: rows)
  }
