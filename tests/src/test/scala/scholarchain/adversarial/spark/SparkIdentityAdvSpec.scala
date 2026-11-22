package scholarchain.adversarial.spark

import org.apache.spark.sql.Row
import org.apache.spark.sql.functions.col
import scholarchain.adversarial.spark.SparkAdv.*
import scholarchain.core.ids.StableIds
import scholarchain.core.model.MatchOutcome
import scholarchain.spark.CoreRecords.Kind
import scholarchain.spark.graph.GraphOutputs
import scholarchain.spark.identity.IdentityResolver

/** Case 5 (UNRESOLVED/CONFLICT endpoints never merged) and case 14 (the repair-01 identity
  * constraints, adversarially). Independent scenarios use disjoint keys and mentions, so they
  * share one build; each conflict is located by one of its member mentions.
  */
class SparkIdentityAdvSpec extends SparkSuite:

  private val O5 = orcidFrom("000000025555001")
  private val O5b = orcidFrom("000000025555002")
  private val O5c = orcidFrom("000000025555003")
  private val Oa = orcidFrom("000000026666001")
  private val Ob = orcidFrom("000000026666002")
  private val Oh = orcidFrom("000000027777001")
  private val Oc = orcidFrom("000000028888001")
  private val OxDoc = "0000-0002-1694-233X" // the ORCID documentation example (check char X)

  // case 5 -------------------------------------------------------------------------------
  // X and Y share U5. X -UNRESOLVED-> o5 and Y -ACCEPTED-> o5. If UNRESOLVED were an edge, this
  // would be the chain case and Y would be split; it must merge cleanly instead.
  private val u5 = Work("ID-U5", Some(2012), Vector("Unr X", "Unr Y"))
  private val o5 = OaMention(5001, 1, "Unr", Some("A5001"), Some(O5))
  // Single CONFLICT / REJECTED decisions that would be clean merges if they counted.
  private val c5 = Work("ID-C5", Some(2012), Vector("Cfl K", "Cfl Co"))
  private val oc5 = OaMention(5002, 1, "Cfl", Some("A5002"), Some(O5b))
  private val r5 = Work("ID-R5", Some(2013), Vector("Rej K", "Rej Co"))
  private val or5 = OaMention(5003, 1, "Rej", Some("A5003"), Some(O5c))
  // A CONFLICT decision directly between two DBLP mentions of different keys.
  private val d5a = Work("ID-D5a", Some(2014), Vector("Dd One", "Dd Co"))
  private val d5b = Work("ID-D5b", Some(2015), Vector("Dd Two", "Dd Co"))

  // case 14: chain through an OpenAlex-OpenAlex link -----------------------------------------
  private val ch = Work("ID-CH", Some(2013), Vector("Ch X", "Ch Y"))
  private val ch2 = Work("ID-CH2", Some(2016), Vector("Ch X", "Ch Z"))
  private val a1 = OaMention(8101, 1, "Ch", Some("A8101"), None)
  private val a2 = OaMention(8102, 2, "Ch", Some("A8101"), None)

  // case 14: multiple ORCIDs (DBLP-side ORCID vs OpenAlex ORCID); format variants of one ORCID
  private val mo = Work("ID-MO", Some(2012), Vector("Mo K", "Mo Co"), orcids = Map(1 -> Oa))
  private val omo = OaMention(8201, 1, "Mo", Some("A8201"), Some(Ob))
  private val mv = Work(
    "ID-MV",
    Some(2012),
    Vector("Mv K", "Mv Co"),
    orcids = Map(1 -> s"https://orcid.org/$OxDoc")
  )
  private val omv = OaMention(8202, 1, "Mv", Some("A8202"), Some(OxDoc.toLowerCase))

  // case 14: multiple OpenAlex author IDs; format variants of one OpenAlex ID
  private val ox = Work("ID-OX", Some(2012), Vector("Ox K", "Ox Co"))
  private val ox1 = OaMention(8301, 1, "Ox", Some("A9101"), None)
  private val ox2 = OaMention(8302, 1, "Ox", Some("A9102"), None)
  private val ov = Work("ID-OV", Some(2012), Vector("Ov K", "Ov Co"))
  private val ov1 = OaMention(8303, 1, "Ov", Some("A9201"), None)
  private val ov2 = OaMention(8304, 1, "Ov", Some("https://openalex.org/a9201"), None)

  // case 14: homonym DBLP keys
  private val hw1 = Work("ID-HW1", Some(2011), Vector("Wei Wang 0001", "Hw Co"))
  private val hw2 = Work("ID-HW2", Some(2012), Vector("Wei Wang 0002", "Hw Co"))
  private val hw3 = Work("ID-HW3", Some(2013), Vector("Wei Wang", "Hw Co"))
  private val ohw = OaMention(8401, 1, "Wei Wang", Some("A9401"), Some(Oh))

  // case 14: three clean clusters proposing one ORCID-based researcher ID
  private val colWorks =
    (1 to 3).map(i => Work(s"ID-COL$i", Some(2010 + i), Vector(s"Col $i", "Col Co")))
  private val ocol = (1 to 3).map(i => OaMention(8500 + i, 1, "Col", Some(s"A95$i"), Some(Oc)))

  private val allWorks =
    Vector(u5, c5, r5, d5a, d5b, ch, ch2, mo, mv, ox, ov, hw1, hw2, hw3) ++ colWorks
  private val allOa = Vector(o5, oc5, or5, a1, a2, omo, omv, ox1, ox2, ov1, ov2, ohw) ++ ocol
  private val allDecisions = Vector(
    decision(u5.mention(1), o5.mentionId, MatchOutcome.UNRESOLVED),
    decision(u5.mention(2), o5.mentionId, MatchOutcome.ACCEPTED),
    decision(c5.mention(1), oc5.mentionId, MatchOutcome.CONFLICT),
    decision(r5.mention(1), or5.mentionId, MatchOutcome.REJECTED),
    decision(d5a.mention(1), d5b.mention(1), MatchOutcome.CONFLICT),
    decision(ch.mention(1), a1.mentionId, MatchOutcome.ACCEPTED),
    decision(a1.mentionId, a2.mentionId, MatchOutcome.ACCEPTED),
    decision(a2.mentionId, ch.mention(2), MatchOutcome.ACCEPTED),
    decision(mo.mention(1), omo.mentionId, MatchOutcome.ACCEPTED),
    decision(mv.mention(1), omv.mentionId, MatchOutcome.ACCEPTED),
    decision(ox.mention(1), ox1.mentionId, MatchOutcome.ACCEPTED),
    decision(ox.mention(1), ox2.mentionId, MatchOutcome.ACCEPTED),
    decision(ov.mention(1), ov1.mentionId, MatchOutcome.ACCEPTED),
    decision(ov2.mentionId, ov.mention(1), MatchOutcome.ACCEPTED),
    decision(hw1.mention(1), ohw.mentionId, MatchOutcome.ACCEPTED),
    decision(hw2.mention(1), ohw.mentionId, MatchOutcome.ACCEPTED)
  ) ++ (0 until 3).map(i =>
    decision(colWorks(i).mention(1), ocol(i).mentionId, MatchOutcome.ACCEPTED)
  )

  private val out = memo(build(tables(allWorks, allOa, allDecisions)))

  private lazy val authored: Map[String, Row] =
    out().authored.collect().map(r => str(r, "mentionId") -> r).toMap
  private lazy val researchers: Map[String, Row] =
    out().researchers.collect().map(r => str(r, "researcherId") -> r).toMap
  private lazy val conflicts: Vector[Row] = out().identity.conflicts.collect().toVector

  private def ridOf(mention: String): String = str(authored(mention), "researcherId")
  private def conflictWith(mention: String): Option[Row] =
    conflicts.find(r => strs(r, "memberIds").contains(mention))
  private def reasonsOf(r: Row): Vector[String] =
    r.getSeq[Row](r.fieldIndex("details")).map(str(_, "reason")).toVector

  test("case 5: an UNRESOLVED link never joins a cluster: the coauthor merges cleanly instead") {
    assert(ridOf(u5.mention(1)) == rid("Unr X"))
    assert(ridOf(u5.mention(2)) == orcidRid(O5))
    assert(str(researchers(orcidRid(O5)), "identityStatus") == IdentityResolver.Merged)
    assert(str(researchers(rid("Unr X")), "identityStatus") == IdentityResolver.Unlinked)
    assert(conflictWith(u5.mention(1)).isEmpty && conflictWith(u5.mention(2)).isEmpty)
    // Only the ACCEPTED decision is cited as identity evidence, never the UNRESOLVED one.
    val prov = researchers(orcidRid(O5)).getAs[Row]("provenance")
    assert(
      strs(prov, "derivedFrom") ==
        Vector(ok(StableIds.matchDecisionId(u5.mention(2), o5.mentionId, ErPolicy)))
    )
  }

  test("case 5: CONFLICT and REJECTED decisions never merge, and leave no conflict record") {
    assert(ridOf(c5.mention(1)) == rid("Cfl K"))
    assert(ridOf(r5.mention(1)) == rid("Rej K"))
    assert(!researchers.contains(orcidRid(O5b)) && !researchers.contains(orcidRid(O5c)))
    assert(ridOf(d5a.mention(1)) == rid("Dd One") && ridOf(d5b.mention(1)) == rid("Dd Two"))
    Vector(c5.mention(1), r5.mention(1), d5a.mention(1), d5b.mention(1)).foreach(m =>
      assert(conflictWith(m).isEmpty, s"$m must not be in a conflict")
    )
    // Dd Co has two distinct coauthors, not one merged researcher.
    val co = inWindow(out().metrics, AllYears).find(r => str(r, "researcherId") == rid("Dd Co")).get
    assert(long(co, "distinctCoauthorCount") == 2L)
  }

  test("case 14: chain through an OpenAlex-OpenAlex link stays split with an IdentityConflict") {
    val c = conflictWith(ch.mention(1)).getOrElse(fail("no IdentityConflict for the chain"))
    assert(reasonsOf(c).toSet == Set("SAME_WORK_MENTIONS", "MULTIPLE_DBLP_AUTHOR_KEYS"))
    assert(
      strs(c, "memberIds").toSet ==
        Set(ch.mention(1), ch.mention(2), ch2.mention(1), a1.mentionId, a2.mentionId)
    )
    assert(long(c, "clusterSize") == 5L)
    assert(strs(c, "matchDecisionIds").size == 3)
    assert(ridOf(ch.mention(1)) == rid("Ch X") && ridOf(ch2.mention(1)) == rid("Ch X"))
    assert(ridOf(ch.mention(2)) == rid("Ch Y"))
    assert(str(researchers(rid("Ch X")), "identityStatus") == IdentityResolver.ConflictSplit)
    assert(str(researchers(rid("Ch Z")), "identityStatus") == IdentityResolver.Unlinked)
    assert(!researchers.contains(openAlexRid("A8101")))
    // X-Y is still a real coauthorship, and nothing became a self pair.
    val xy = inWindow(out().edges, AllYears).count(r =>
      Set(str(r, "researcherA"), str(r, "researcherB")) == Set(rid("Ch X"), rid("Ch Y"))
    )
    assert(xy == 1)
    assert(out().selfPairs.count() == 0L)
  }

  test("case 14: two ORCIDs across sources split; format variants of one ORCID merge") {
    val c = conflictWith(mo.mention(1)).getOrElse(fail("no conflict for two ORCIDs"))
    assert(reasonsOf(c) == Vector("MULTIPLE_ORCIDS"))
    assert(ridOf(mo.mention(1)) == rid("Mo K"))
    assert(!researchers.contains(orcidRid(Oa)) && !researchers.contains(orcidRid(Ob)))
    // Same iD written as a resolver URL and in lower case: one normalized ORCID, a clean merge.
    assert(orcidFrom("000000021694233") == OxDoc)
    assert(conflictWith(mv.mention(1)).isEmpty)
    assert(ridOf(mv.mention(1)) == orcidRid(OxDoc))
    assert(str(researchers(orcidRid(OxDoc)), "orcid") == OxDoc)
  }

  test("case 14: two OpenAlex author IDs split; format variants of one OpenAlex ID merge") {
    val c = conflictWith(ox.mention(1)).getOrElse(fail("no conflict for two OpenAlex IDs"))
    assert(reasonsOf(c) == Vector("MULTIPLE_OPENALEX_AUTHOR_IDS"))
    assert(ridOf(ox.mention(1)) == rid("Ox K"))
    assert(conflictWith(ov.mention(1)).isEmpty)
    assert(ridOf(ov.mention(1)) == openAlexRid("A9201"))
  }

  test("case 14: homonym DBLP keys are never merged, even when linked to one external author") {
    val ids = Vector(hw1, hw2, hw3).map(w => ridOf(w.mention(1)))
    assert(ids == Vector(rid("Wei Wang 0001"), rid("Wei Wang 0002"), rid("Wei Wang")))
    assert(ids.distinct.size == 3)
    val c = conflictWith(hw1.mention(1)).getOrElse(fail("no conflict for linked homonyms"))
    assert(reasonsOf(c) == Vector("MULTIPLE_DBLP_AUTHOR_KEYS"))
    assert(strs(c, "memberIds").contains(hw2.mention(1)))
    assert(!strs(c, "memberIds").contains(hw3.mention(1)))
    assert(!researchers.contains(orcidRid(Oh)))
    val co = inWindow(out().metrics, AllYears).find(r => str(r, "researcherId") == rid("Hw Co")).get
    assert(long(co, "distinctCoauthorCount") == 3L)
  }

  test("case 14: three clean clusters proposing one ORCID ID are evaluated together and split") {
    val c =
      conflictWith(colWorks(0).mention(1)).getOrElse(fail("no conflict for the ORCID collision"))
    assert(reasonsOf(c).contains("MULTIPLE_DBLP_AUTHOR_KEYS"))
    (0 until 3).foreach { i =>
      assert(strs(c, "memberIds").contains(colWorks(i).mention(1)))
      assert(ridOf(colWorks(i).mention(1)) == rid(s"Col ${i + 1}"))
    }
    assert(!researchers.contains(orcidRid(Oc)))
    assert(
      conflicts.count(r =>
        strs(r, "memberIds").exists(m => colWorks.exists(_.mention(1) == m))
      ) == 1
    )
  }

  test("case 14: every conflict member keeps a fallback ID; conflict rows meet the contract") {
    assert(conflicts.size == 5) // chain, ORCIDs, OpenAlex IDs, homonyms, ORCID collision
    val splitIds = out().identity.mentionIdentity
      .where(col("identityStatus") === IdentityResolver.ConflictSplit)
      .select("idSource")
      .distinct()
      .collect()
      .map(_.getString(0))
      .toVector
    assert(splitIds == Vector(IdentityResolver.SourceFallback))
    assert(violations(out().identity.conflicts, Kind.Conflict).isEmpty)
    assert(violations(out().researchers, Kind.Researcher).isEmpty)
    assert(violations(out().authored, Kind.Authorship).isEmpty)
  }

  // maxClusterSize boundary: a separate build with graph.identity.maxClusterSize = 3.
  private val fit = Vector(
    Work("CS-F1", Some(2011), Vector("Cs Fit", "Cs Co")),
    Work("CS-F2", Some(2012), Vector("Cs Fit", "Cs Co"))
  )
  private val over = Vector(
    Work("CS-O1", Some(2011), Vector("Cs Over", "Cs Co")),
    Work("CS-O2", Some(2012), Vector("Cs Over", "Cs Co")),
    Work("CS-O3", Some(2013), Vector("Cs Over", "Cs Co"))
  )
  private val big = (1 to 5).map(i => Work(s"CS-B$i", Some(2010 + i), Vector("Cs Big", "Cs Co")))
  private val Ofit = orcidFrom("000000029999001")
  private val Oover = orcidFrom("000000029999002")
  private val ofit = OaMention(8601, 1, "Fit", Some("A9601"), Some(Ofit))
  private val oover = OaMention(8602, 1, "Over", Some("A9602"), Some(Oover))

  test(
    "case 14: cluster size == maxClusterSize merges; maxClusterSize + 1 splits; unlinked never"
  ) {
    val o = build(
      tables(
        fit ++ over ++ big,
        Vector(ofit, oover),
        Vector(
          decision(fit.head.mention(1), ofit.mentionId, MatchOutcome.ACCEPTED),
          decision(over.head.mention(1), oover.mentionId, MatchOutcome.ACCEPTED)
        )
      ),
      params(maxClusterSize = 3)
    )
    val a = o.authored.collect().map(r => str(r, "mentionId") -> str(r, "researcherId")).toMap
    // Cs Fit: 2 DBLP mentions + 1 OpenAlex mention = 3 = max -> merged on the ORCID.
    fit.foreach(w => assert(a(w.mention(1)) == orcidRid(Ofit)))
    // Cs Over: 3 + 1 = 4 > 3 -> CLUSTER_TOO_LARGE, fallback IDs.
    over.foreach(w => assert(a(w.mention(1)) == rid("Cs Over")))
    // Cs Big: 5 DBLP mentions but no ACCEPTED link: UNLINKED, not a conflict.
    big.foreach(w => assert(a(w.mention(1)) == rid("Cs Big")))
    val cs = o.identity.conflicts.collect().toVector
    assert(cs.size == 1)
    assert(reasonsOf(cs.head) == Vector("CLUSTER_TOO_LARGE"))
    assert(long(cs.head, "clusterSize") == 4L && long(cs.head, "maxClusterSize") == 3L)
    assert(violations(o.identity.conflicts, Kind.Conflict).isEmpty)
  }
