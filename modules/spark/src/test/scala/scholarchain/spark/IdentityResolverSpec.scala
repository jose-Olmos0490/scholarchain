package scholarchain.spark

import org.apache.spark.sql.Row
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.model.MatchOutcome
import scholarchain.spark.Fixture.*
import scholarchain.spark.SparkTestSupport.*
import scholarchain.spark.identity.IdentityResolver

/** The ratified identity rule (Phase 06 repair 01), including the mandatory chain case. */
class IdentityResolverSpec extends AnyFunSuite:

  private def researchersOf(out: graph.GraphOutputs): Map[String, Row] =
    out.researchers.collect().map(r => r.getAs[String]("researcherId") -> r).toMap

  private def authoredResearcher(out: graph.GraphOutputs, mention: String): String =
    out.authored
      .collect()
      .find(_.getAs[String]("mentionId") == mention)
      .get
      .getAs[String]("researcherId")

  private def conflictReasons(out: graph.GraphOutputs): Vector[Vector[String]] =
    out.identity.conflicts
      .collect()
      .toVector
      .map(r => r.getSeq[Row](r.fieldIndex("details")).map(_.getAs[String]("reason")).toVector)

  test("chain case: X and Y share a paper and both link to A1; the cluster stays split") {
    val xy = DblpWork("XY", Some(2012), Vector("X", "Y"))
    val a1 = OpenAlexMention(101, 1, "X. Y. Author", Some("A5001"), None)
    val t = tables(
      Vector(xy),
      Vector(a1),
      Vector(
        decision(xy.mention(1), a1.mentionId, MatchOutcome.ACCEPTED),
        decision(xy.mention(2), a1.mentionId, MatchOutcome.ACCEPTED)
      )
    )
    val out = build(t)
    assert(authoredResearcher(out, xy.mention(1)) == researcher("X"))
    assert(authoredResearcher(out, xy.mention(2)) == researcher("Y"))
    assert(out.selfPairs.count() == 0)
    assert(inWindow(out.edges, AllYears).size == 1) // X-Y stays a real coauthorship
    assert(
      conflictReasons(out) == Vector(Vector("SAME_WORK_MENTIONS", "MULTIPLE_DBLP_AUTHOR_KEYS"))
    )
    val c = out.identity.conflicts.collect().head
    assert(
      c.getSeq[String](c.fieldIndex("memberIds")).toSet == Set(
        xy.mention(1),
        xy.mention(2),
        a1.mentionId
      )
    )
    assert(c.getAs[Int]("clusterSize") == 3)
    assert(violations(out.identity.conflicts, CoreRecords.Kind.Conflict).isEmpty)
    researchersOf(out).values.foreach(r =>
      assert(r.getAs[String]("identityStatus") == IdentityResolver.ConflictSplit)
    )
  }

  test("an ACCEPTED link to a mention with one ORCID merges every mention of the DBLP key") {
    val w1 = DblpWork("D1", Some(2011), Vector("Dana", "Eve"))
    val w2 = DblpWork("D2", Some(2013), Vector("Dana", "Fay"))
    val o = OpenAlexMention(201, 1, "Dana", Some("A777"), Some(Orcid1))
    val out = build(
      tables(
        Vector(w1, w2),
        Vector(o),
        Vector(decision(w1.mention(1), o.mentionId, MatchOutcome.ACCEPTED, 0.93))
      )
    )
    val id = orcidResearcher(Orcid1)
    assert(authoredResearcher(out, w1.mention(1)) == id)
    assert(authoredResearcher(out, w2.mention(1)) == id)
    val r = researchersOf(out)(id)
    assert(r.getAs[String]("idSource") == "ORCID" && r.getAs[String]("orcid") == Orcid1)
    assert(r.getAs[String]("openAlexAuthorId") == "A777")
    assert(r.getAs[Double]("identityConfidence") == 0.93)
    assert(r.getAs[Long]("mentionCount") == 2L)
    assert(out.identity.conflicts.count() == 0)
    assert(violations(out.researchers, CoreRecords.Kind.Researcher).isEmpty)
    assert(violations(out.authored, CoreRecords.Kind.Authorship).isEmpty)
    // Dana is one researcher with two papers and two coauthors.
    val m = inWindow(out.metrics, AllYears).find(_.getAs[String]("researcherId") == id).get
    assert(m.getAs[Long]("publicationCount") == 2L && m.getAs[Long]("distinctCoauthorCount") == 2L)
  }

  test("cross-source mentions of one author slot merge (DBLP, OpenAlex, Crossref of one paper)") {
    // The real-data shape: the same paper in three sources, linked by ACCEPTED work decisions;
    // the DBLP slot is linked to the OpenAlex slot and the Crossref slot of the same author.
    val w = DblpWork("P1", Some(2014), Vector("Rae", "Sol"))
    val oa = OpenAlexMention(901, 1, "Rae", Some("A9001"), Some(Orcid2))
    val cr = OpenAlexMention(902, 1, "Rae", None, None) // stands in for the Crossref slot
    val out = build(
      tables(
        Vector(w),
        Vector(oa, cr),
        Vector(
          decision(w.mention(1), oa.mentionId, MatchOutcome.ACCEPTED),
          decision(w.mention(1), cr.mentionId, MatchOutcome.ACCEPTED),
          decision(oa.mentionId, cr.mentionId, MatchOutcome.ACCEPTED),
          decision(w.workId, oa.workId, MatchOutcome.ACCEPTED),
          decision(w.workId, cr.workId, MatchOutcome.ACCEPTED)
        )
      )
    )
    assert(out.identity.conflicts.count() == 0)
    assert(authoredResearcher(out, w.mention(1)) == orcidResearcher(Orcid2))
    assert(authoredResearcher(out, w.mention(2)) == researcher("Sol"))
    assert(violations(out.researchers, CoreRecords.Kind.Researcher).isEmpty)
  }

  test("an ACCEPTED link to an OpenAlex author with no ORCID gives an OpenAlex researcher ID") {
    val w = DblpWork("G1", Some(2011), Vector("Gil", "Hal"))
    val o = OpenAlexMention(301, 1, "Gil", Some("A4242"), None)
    val out = build(
      tables(
        Vector(w),
        Vector(o),
        Vector(decision(w.mention(1), o.mentionId, MatchOutcome.ACCEPTED))
      )
    )
    assert(authoredResearcher(out, w.mention(1)) == openAlexResearcher("A4242"))
    assert(violations(out.researchers, CoreRecords.Kind.Researcher).isEmpty)
  }

  test("UNRESOLVED, CONFLICT and REJECTED decisions never merge") {
    val w = DblpWork("U1", Some(2011), Vector("Ula", "Vic"))
    val o1 = OpenAlexMention(401, 1, "Ula", Some("A1"), Some(Orcid1))
    val o2 = OpenAlexMention(402, 1, "Ula", Some("A2"), Some(Orcid2))
    val o3 = OpenAlexMention(403, 1, "Vic", Some("A3"), None)
    val out = build(
      tables(
        Vector(w),
        Vector(o1, o2, o3),
        Vector(
          decision(w.mention(1), o1.mentionId, MatchOutcome.UNRESOLVED),
          decision(w.mention(1), o2.mentionId, MatchOutcome.CONFLICT),
          decision(w.mention(2), o3.mentionId, MatchOutcome.REJECTED)
        )
      )
    )
    assert(authoredResearcher(out, w.mention(1)) == researcher("Ula"))
    assert(authoredResearcher(out, w.mention(2)) == researcher("Vic"))
    assert(out.identity.conflicts.count() == 0)
    assert(
      researchersOf(out).values.forall(
        _.getAs[String]("identityStatus") == IdentityResolver.Unlinked
      )
    )
  }

  test("more than one ORCID in a cluster: not merged, MULTIPLE_ORCIDS conflict") {
    val w = DblpWork("M1", Some(2011), Vector("Max", "Ned"))
    val o1 = OpenAlexMention(501, 1, "Max", Some("A51"), Some(Orcid1))
    val o2 = OpenAlexMention(502, 1, "Max", Some("A51"), Some(Orcid2))
    val out = build(
      tables(
        Vector(w),
        Vector(o1, o2),
        Vector(
          decision(w.mention(1), o1.mentionId, MatchOutcome.ACCEPTED),
          decision(w.mention(1), o2.mentionId, MatchOutcome.ACCEPTED)
        )
      )
    )
    assert(authoredResearcher(out, w.mention(1)) == researcher("Max"))
    assert(conflictReasons(out) == Vector(Vector("MULTIPLE_ORCIDS")))
    assert(violations(out.identity.conflicts, CoreRecords.Kind.Conflict).isEmpty)
  }

  test("more than one OpenAlex author ID in a cluster: not merged") {
    val w = DblpWork("M2", Some(2011), Vector("Oli", "Pat"))
    val o1 = OpenAlexMention(601, 1, "Oli", Some("A61"), None)
    val o2 = OpenAlexMention(602, 1, "Oli", Some("A62"), None)
    val out = build(
      tables(
        Vector(w),
        Vector(o1, o2),
        Vector(
          decision(w.mention(1), o1.mentionId, MatchOutcome.ACCEPTED),
          decision(o2.mentionId, w.mention(1), MatchOutcome.ACCEPTED)
        )
      )
    )
    assert(authoredResearcher(out, w.mention(1)) == researcher("Oli"))
    assert(conflictReasons(out) == Vector(Vector("MULTIPLE_OPENALEX_AUTHOR_IDS")))
  }

  test("a cluster larger than graph.identity.maxClusterSize is not merged") {
    val w1 = DblpWork("L1", Some(2011), Vector("Lee", "Kim"))
    val w2 = DblpWork("L2", Some(2012), Vector("Lee", "Kim"))
    val o = OpenAlexMention(701, 1, "Lee", Some("A71"), Some(Orcid1))
    val out = build(
      tables(
        Vector(w1, w2),
        Vector(o),
        Vector(decision(w1.mention(1), o.mentionId, MatchOutcome.ACCEPTED))
      ),
      params(maxClusterSize = 2)
    )
    assert(authoredResearcher(out, w1.mention(1)) == researcher("Lee"))
    assert(conflictReasons(out) == Vector(Vector("CLUSTER_TOO_LARGE")))
    val c = out.identity.conflicts.collect().head
    assert(c.getAs[Int]("clusterSize") == 3 && c.getAs[Int]("maxClusterSize") == 2)
    assert(violations(out.identity.conflicts, CoreRecords.Kind.Conflict).isEmpty)
  }

  test("two DBLP keys linked to different mentions carrying one ORCID are not silently merged") {
    val w1 = DblpWork("C1", Some(2011), Vector("Ann 0001", "Bob"))
    val w2 = DblpWork("C2", Some(2012), Vector("Ann 0002", "Cid"))
    val o1 = OpenAlexMention(801, 1, "Ann", Some("A81"), Some(Orcid1))
    val o2 = OpenAlexMention(802, 1, "Ann", Some("A81"), Some(Orcid1))
    val out = build(
      tables(
        Vector(w1, w2),
        Vector(o1, o2),
        Vector(
          decision(w1.mention(1), o1.mentionId, MatchOutcome.ACCEPTED),
          decision(w2.mention(1), o2.mentionId, MatchOutcome.ACCEPTED)
        )
      )
    )
    assert(authoredResearcher(out, w1.mention(1)) == researcher("Ann 0001"))
    assert(authoredResearcher(out, w2.mention(1)) == researcher("Ann 0002"))
    assert(conflictReasons(out) == Vector(Vector("MULTIPLE_DBLP_AUTHOR_KEYS")))
    assert(violations(out.identity.conflicts, CoreRecords.Kind.Conflict).isEmpty)
  }

  test("homonym-disambiguated DBLP keys with similar names never merge without a link") {
    val w1 = DblpWork("H1", Some(2011), Vector("Wei Wang 0001", "Bo"))
    val w2 = DblpWork("H2", Some(2012), Vector("Wei Wang 0002", "Bo"))
    val out = build(tables(Vector(w1, w2)))
    assert(authoredResearcher(out, w1.mention(1)) != authoredResearcher(out, w2.mention(1)))
    assert(out.researchers.count() == 3)
  }

  test("an ACCEPTED decision naming an unknown mention is ignored and rejected") {
    val w = DblpWork("K1", Some(2011), Vector("Kai", "Lou"))
    val ghost = mentionId(oaWorkId(999), 1)
    val out = build(
      tables(Vector(w), Vector.empty, Vector(decision(w.mention(1), ghost, MatchOutcome.ACCEPTED)))
    )
    assert(authoredResearcher(out, w.mention(1)) == researcher("Kai"))
    assert(
      out.rejects.select("reasonCode").collect().map(_.getString(0)).toVector == Vector(
        "DECISION_UNKNOWN_MENTION"
      )
    )
    assert(violations(out.rejects, CoreRecords.Kind.Reject).isEmpty)
  }
