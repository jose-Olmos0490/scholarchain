package scholarchain.hadoop

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.ids.{CanonicalPair, StableIds, WorkNamespace}
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions
import scholarchain.hadoop.io.Records
import scholarchain.hadoop.pairs.TeamExpansion
import scholarchain.hadoop.pairs.TeamExpansion.Route

class TeamExpansionSpec extends AnyFunSuite:
  private val runId = "run:" + "b" * 64

  private def work(names: String*): WorkRecord =
    val key = s"conf/t/W${names.size}${names.mkString.hashCode.abs}"
    val workId = StableIds.workId(WorkNamespace.Dblp, key).toOption.get
    val prov = Records.provenance(SourceType.DBLP, key, "2024-01-01", runId)
    WorkRecord(
      SchemaVersions.WorkRecord,
      workId,
      SourceType.DBLP,
      key,
      "inproceedings",
      "T",
      "t",
      Some(2010),
      None,
      None,
      None,
      names.zipWithIndex.map { (n, i) =>
        AuthorMention(
          SchemaVersions.AuthorMention,
          StableIds.mentionId(workId, i + 1).toOption.get,
          workId,
          i + 1,
          n,
          n.toLowerCase,
          Some(n),
          None,
          prov
        )
      }.toVector,
      None,
      prov
    )

  private def pairsOf(names: String*): Vector[CoauthorPairCandidate] =
    val t = TeamExpansion.team(work(names*))
    TeamExpansion.pairs(t, 2010, runId).map(_.fold(e => fail(e), identity)).toVector

  test("0, 1, 2, 3 and 5 authors give 0, 0, 1, 3 and 10 pairs (n(n-1)/2)") {
    assert(pairsOf().size == 0)
    assert(pairsOf("A").size == 0)
    assert(pairsOf("A", "B").size == 1)
    assert(pairsOf("A", "B", "C").size == 3)
    assert(pairsOf("A", "B", "C", "D", "E").size == 10)
    assert((0 to 6).map(TeamExpansion.pairCount) == Vector(0L, 0L, 1L, 3L, 6L, 10L, 15L))
  }

  test("hand-checked 3-author pairs: positions {1,2}, {1,3}, {2,3}, each a valid v2 record") {
    val ps = pairsOf("A", "B", "C")
    assert(
      ps.map(p => Set(p.positionA.get, p.positionB.get)).toSet == Set(
        Set(1, 2),
        Set(1, 3),
        Set(2, 3)
      )
    )
    ps.foreach { p =>
      assert(p.schemaVersion == SchemaVersions.CoauthorPairCandidateV2)
      assert(p.year == 2010 && p.teamSize == 3)
      assert(
        p.provenance.sourceDataset == SourceType.DBLP && p.provenance.derivedFrom == Vector(
          p.workId
        )
      )
      assert(WireCodec.encodeValidated(p).isRight)
    }
  }

  test("pairs are in canonical order (memberA < memberB) and positions follow their members") {
    pairsOf("A", "B", "C", "D", "E").foreach { p =>
      assert(CanonicalPair.isCanonical(p.memberA, p.memberB))
      assert(StableIds.mentionId(p.workId, p.positionA.get).toOption.contains(p.memberA))
      assert(StableIds.mentionId(p.workId, p.positionB.get).toOption.contains(p.memberB))
    }
  }

  test("a repeated author is removed from the team (first position kept)") {
    val t = TeamExpansion.team(work("Dee", "Eve", "Dee"))
    assert(t.removedDuplicates == 1)
    assert(t.size == 2)
    assert(t.members.map(_.position) == Vector(1, 2))
    val pt = TeamExpansion.publicationTeam(t, runId)
    assert(pt.teamSize == 2 && WireCodec.encodeValidated(pt).isRight)
    assert(TeamExpansion.pairs(t, 2010, runId).size == 1)
  }

  test("NFC and NFD spellings of one author on a paper are one member; case still differs") {
    val nfc = java.text.Normalizer.normalize("Jos\u00e9 P\u00e9rez", java.text.Normalizer.Form.NFC)
    val nfd = java.text.Normalizer.normalize(nfc, java.text.Normalizer.Form.NFD)
    assert(nfc != nfd)
    val t = TeamExpansion.team(work(nfc, "Other Person", nfd))
    assert(t.size == 2 && t.removedDuplicates == 1)
    assert(t.members.map(_.position) == Vector(1, 2))
    assert(TeamExpansion.pairs(t, 2010, runId).size == 1)
    // NFC only: a different-case spelling is a different source key and is kept.
    assert(TeamExpansion.team(work("Ann Lee", "ANN LEE")).size == 2)
  }

  test("routing: large teams are flagged and expanded, oversize teams are not expanded") {
    assert(TeamExpansion.route(3, 3, 5) == Route.Expand(false))
    assert(TeamExpansion.route(4, 3, 5) == Route.Expand(true))
    assert(TeamExpansion.route(5, 3, 5) == Route.Expand(true))
    assert(TeamExpansion.route(6, 3, 5) == Route.Oversize)
    assert(TeamExpansion.route(2000, 50, 2000) == Route.Expand(true))
    assert(TeamExpansion.route(2001, 50, 2000) == Route.Oversize)
  }

  test("author-paper rows carry the de-duplicated team size") {
    val t = TeamExpansion.team(work("A", "B", "A", "C"))
    val aps = TeamExpansion.authorPapers(t, runId)
    assert(aps.map(_.position) == Vector(1, 2, 4))
    assert(aps.forall(_.teamSize == 3))
    aps.foreach(a => assert(WireCodec.encodeValidated(a).isRight))
  }
