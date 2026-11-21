package scholarchain.hadoop

import java.io.File
import java.nio.charset.StandardCharsets
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.hadoop.jobs.DblpExtractJob

/** LocalJobRunner integration tests: the full job chain on the hand-checkable fixture and on the
  * committed samples (J01 and J07/J08 counters), blocking overflow, the maxWorks cap,
  * idempotency and determinism.
  */
class PipelineIntegrationSpec extends AnyFunSuite:
  import HadoopTestSupport.*

  private lazy val fixtureInput = inputRoot("fixture-in", fixture("dblp/dblp-fixture.xml"))
  private val smallTeams = config(largeTeamThreshold = 3, maxPairExpansionTeamSize = 5)
  private lazy val first = runPipeline(fixtureInput, scratch("fixture-out-a"), smallTeams)

  test("J01 on the fixture: exact input, accepted, rejected and subset counters") {
    assert(first.counter("j01", "INPUT_RECORDS") == 16)
    assert(first.counter("j01", "ACCEPTED") == 11)
    assert(first.counter("j01", "REJECTED") == 3)
    assert(first.counter("j01", "UNKNOWN_ENTITY") == 1)
    assert(first.counter("j01", "MISSING_KEY") == 1)
    assert(first.counter("j01", "OUT_OF_SUBSET") == 2)
    assert(first.counter("j01", "SCHEMA_FAILURE") == 0)
    val rejects = json(rows(first.outputRoot, "dblp", "rejects_j01"))
    assert(
      rejects.map(_("reasonCode").str).sorted == Vector(
        "MALFORMED_XML",
        "MISSING_KEY",
        "UNKNOWN_ENTITY"
      )
    )
    assert(
      rejects
        .find(_("reasonCode").str == "UNKNOWN_ENTITY")
        .get("sourceRecordRef")
        .str == "journals/test/Unknown09"
    )
    rows(first.outputRoot, "dblp", "rejects_j01").foreach(r =>
      assert(WireCodec.decode[RejectRecord](r).isRight)
    )
  }

  test("J01C writes valid works with entities and Unicode resolved") {
    val works =
      rows(first.outputRoot, "dblp", "work").map(WireCodec.decode[WorkRecord](_).toOption.get)
    assert(works.size == 11)
    val multi = works.find(_.sourceRecordId == "conf/test/Multi01").get
    assert(multi.title == "Multiline Title with entities & µ-markup.")
    assert(
      multi.authors.map(_.rawName).take(4) == Vector(
        "Jan Jürjens",
        "Frédéric Mallet",
        "Ming Hu 0003",
        "Ana Gómez"
      )
    )
    assert(multi.authors.head.orcid.contains("0000-0002-8938-0470"))
    assert(multi.authors(4).orcid.isEmpty, "bad ORCID checksum dropped")
    assert(multi.doi.contains("10.1145/test.0001"))
    assert(rows(first.outputRoot, "dblp", "author_mention").size == works.map(_.authors.size).sum)
  }

  test("J07/J08: hand-checked teams, pairs, duplicates, large and oversize routing") {
    assert(first.counter("j07", "EMITTED_PAIRS") == 26)
    assert(first.counter("j07", "DUPLICATE_AUTHOR_IN_TEAM") == 1)
    assert(first.counter("j07", "LARGE_AUTHOR_TEAM") == 3)
    assert(first.counter("j07", "OVERSIZE_AUTHOR_TEAM") == 1)
    val teams = rows(first.outputRoot, "dblp", "publication_team").map(
      WireCodec.decode[PublicationTeam](_).toOption.get
    )
    assert(teams.size == 10, "every work with an author has a team, including the oversize one")
    val pairs = rows(first.outputRoot, "dblp", "coauthor_pair").map(
      WireCodec.decode[CoauthorPairCandidate](_).toOption.get
    )
    val byWork = pairs.groupBy(_.workId).view.mapValues(_.size).toMap
    val sizeOf = teams.map(t => t.workId -> t.teamSize).toMap
    byWork.foreach((w, n) => assert(n == sizeOf(w) * (sizeOf(w) - 1) / 2))
    val oversize = rows(first.outputRoot, "dblp", "oversize_team").map(
      WireCodec.decode[PublicationTeam](_).toOption.get
    )
    assert(oversize.map(_.teamSize) == Vector(7))
    assert(!byWork.contains(oversize.head.workId), "oversize teams are not expanded")
    assert(rows(first.outputRoot, "dblp", "large_team").size == 3)
    val dupWork = rows(first.outputRoot, "dblp", "work")
      .map(WireCodec.decode[WorkRecord](_).toOption.get)
      .find(_.sourceRecordId == "conf/test/Dup06")
      .get
    assert(sizeOf(dupWork.workId) == 2)
    assert(
      pairs
        .filter(_.workId == dupWork.workId)
        .map(p => Set(p.positionA.get, p.positionB.get)) == Vector(Set(1, 2))
    )
    assert(
      pairs.forall(p => p.schemaVersion == "coauthor-pair-candidate-v2" && p.memberA < p.memberB)
    )
    val authorPapers = rows(first.outputRoot, "dblp", "author_paper")
    assert(authorPapers.size == teams.map(_.teamSize).sum)
    val summary = first.log.find(_.startsWith("SUMMARY")).get
    assert(summary.contains("\"value\": 7") && summary.contains(oversize.head.workId))
  }

  test("every output record carries provenance with the run ID and a snapshot") {
    val runIdLine = first.log.find(_.startsWith("run ID ")).get
    val runId = runIdLine.split(' ')(2)
    Seq(
      "work",
      "author_mention",
      "publication_team",
      "author_paper",
      "coauthor_pair",
      "venue",
      "work_node"
    ).foreach { t =>
      json(rows(first.outputRoot, "dblp", t)).foreach { r =>
        assert(r("provenance")("runId").str == runId, t)
        assert(r("provenance")("snapshot").str == "2024-01-01", t)
        assert(r("provenance")("sourceDataset").str == "DBLP", t)
      }
    }
    Seq("match_candidate", "exact_identifier_outcome").foreach { t =>
      json(rows(first.outputRoot, "scholarchain", t)).foreach(r =>
        assert(r("provenance")("runId").str == runId)
      )
    }
  }

  test("counters.json and _SUCCESS sit next to every output table") {
    val dirs = tableDirs(first.outputRoot, "dblp", "coauthor_pair") ++ tableDirs(
      first.outputRoot,
      "dblp",
      "oversize_team"
    )
    assert(dirs.nonEmpty)
    dirs.foreach { d =>
      assert(new File(d, "_SUCCESS").isFile && new File(d, "counters.json").isFile)
      val c = ujson.read(
        new String(
          java.nio.file.Files.readAllBytes(new File(d, "counters.json").toPath),
          StandardCharsets.UTF_8
        )
      )
      assert(c("slug").str == "j07")
      assert(c("counters")("scholarchain.hadoop.HadoopCounter")("EMITTED_PAIRS").num == 26)
    }
  }

  test("a repeated run into the same output root skips every job and changes nothing") {
    val before = logicalContent(first.outputRoot)
    val again = runPipeline(fixtureInput, first.outputRoot, smallTeams)
    assert(
      again.reports.forall(_.status == "SUCCEEDED"),
      "skipped jobs return their stored reports"
    )
    assert(again.log.count(_.contains("SKIPPED")) == again.reports.size)
    assert(logicalContent(first.outputRoot) == before)
  }

  test("determinism: two runs into different output roots give identical sorted output") {
    val second = runPipeline(fixtureInput, scratch("fixture-out-b"), smallTeams)
    val a = logicalContent(first.outputRoot)
    val b = logicalContent(second.outputRoot)
    assert(a.keySet == b.keySet)
    a.keys.foreach(k => assert(a(k) == b(k), k))
  }

  test("an output table without _SUCCESS fails the run with an actionable message, never deletes") {
    val out = scratch("partial-out")
    val r = runPipeline(fixtureInput, out, smallTeams)
    val pairDir = tableDirs(out, "dblp", "coauthor_pair").head
    new File(pairDir, "_SUCCESS").delete()
    val e = intercept[IllegalStateException](runPipeline(fixtureInput, out, smallTeams))
    assert(e.getMessage.contains("without _SUCCESS") && e.getMessage.contains("never deletes"))
    assert(pairDir.listFiles.exists(_.getName.startsWith("part-")), "nothing was deleted")
    assert(r.reports.nonEmpty)
  }

  test("maxWorks cap keeps at most maxWorks works, chosen by work-ID bucket") {
    val r = runPipeline(fixtureInput, scratch("cap-out"), smallTeams, maxWorks = 5)
    val kept = rows(r.outputRoot, "dblp", "work").map(WireCodec.decode[WorkRecord](_).toOption.get)
    val all =
      rows(first.outputRoot, "dblp", "work").map(WireCodec.decode[WorkRecord](_).toOption.get)
    assert(kept.size <= 5 && kept.nonEmpty)
    assert(r.counter("j01c", "CAPPED_WORKS") == 11 - kept.size)
    val limit = kept.map(w => DblpExtractJob.bucket(w.workId)).max
    assert(
      all
        .filterNot(w => kept.exists(_.workId == w.workId))
        .forall(w => DblpExtractJob.bucket(w.workId) > limit)
    )
  }

  test(
    "blocking: oversize primary blocks are re-keyed once; still-oversize blocks are routed, not dropped"
  ) {
    val recs = (0 until 4).map(i =>
      s"""<article key="journals/b/C$i"><author>Li Wei</author><author>Bo Chen</author><title>Chen paper $i</title><year>${2000 + i}</year></article>"""
    ) ++ (0 until 3).map(i =>
      s"""<article key="journals/b/Z$i"><author>Li Wei</author><author>Ann Zhu</author><title>Zhu paper $i</title><year>${2010 + i}</year></article>"""
    )
    val xml = write(
      new File(scratch("block-src"), "block.xml"),
      s"<dblp>\n${recs.mkString("\n")}\n</dblp>\n"
    )
    val r = runPipeline(inputRoot("block-in", xml), scratch("block-out"), config(maxBlockSize = 3))
    // r|wei|l has 7 members and r|chen|b has 4: both exceed 3 and are re-keyed once.
    assert(r.counter("j05", "REKEYED_MEMBERS") == 11)
    // r|wei|l|co:chen (4) and r|chen|b|co:wei (4) are still oversize; r|wei|l|co:zhu (3) fits.
    assert(r.counter("j05b", "OVERSIZE_BLOCK") == 2)
    assert(r.counter("j05b", "OVERSIZE_BLOCK_MEMBERS") == 8)
    val oversize = rows(r.outputRoot, "scholarchain", "oversize_block").map(
      WireCodec.decode[RejectRecord](_).toOption.get
    )
    assert(oversize.size == 8 && oversize.forall(_.reasonCode == "OVERSIZE_BLOCK"))
    assert(oversize.count(_.rawExcerpt.contains("r|wei|l|co:chen")) == 4)
    val fallback = json(rows(r.outputRoot, "scholarchain", "blocking_block_fallback"))
    assert(fallback.map(_("blockKey").str).contains("r|wei|l|co:zhu"))
    assert(fallback.forall(_("members").arr.size <= 3))
    val cands = rows(r.outputRoot, "scholarchain", "match_candidate").map(
      WireCodec.decode[MatchCandidate](_).toOption.get
    )
    assert(cands.count(_.blockKey == "r|wei|l|co:zhu") == 3)
  }

  test("committed samples: J01 and J07/J08 run on real DBLP, OpenAlex, Crossref and ORCID data") {
    val r = runPipeline(samplesDir, scratch("samples-out"), config())
    assert(r.counter("j01", "INPUT_RECORDS") == 35)
    assert(r.counter("j01", "ACCEPTED") == 24)
    assert(r.counter("j01", "OUT_OF_SUBSET") == 11)
    assert(r.counter("j01", "REJECTED") == 0)
    assert(r.counter("j07", "EMITTED_PAIRS") == 167)
    assert(r.counter("j02", "INPUT_RECORDS") == 72)
    val pairs = rows(r.outputRoot, "dblp", "coauthor_pair")
    assert(pairs.size == 167)
    pairs.foreach(p => assert(WireCodec.decode[CoauthorPairCandidate](p).isRight))
    assert(rows(r.outputRoot, "scholarchain", "exact_identifier_outcome").nonEmpty)
  }

  test("J01: a truncated record costs one reject; illegal UTF-8 bytes are rejected, not replaced") {
    def art(k: String, t: String) =
      s"""<article mdate="2024-01-01" key="journals/r/$k"><author>Au $k</author><title>$t</title><year>2010</year><journal>R</journal></article>"""
    val out = new java.io.ByteArrayOutputStream()
    def put(s: String) = out.write(s.getBytes(StandardCharsets.UTF_8))
    put("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<dblp>\n")
    put(art("G1", "Größe one.") + "\n")
    put(
      "<inproceedings mdate=\"2024-01-01\" key=\"conf/r/Trunc\">\n<author>Tru N</author>\n<title>never ends\n"
    )
    put(art("G2", "Two.") + "\n" + art("G3", "Three.") + "\n")
    put("<article mdate=\"2024-01-01\" key=\"journals/r/Bad\"><author>B</author><title>Bad ")
    out.write(Array(0xc3, 0x28, 0x20, 0xff).map(_.toByte))
    put(" bytes.</title><year>2010</year><journal>R</journal></article>\n")
    put(art("G4", "Four.") + "\n</dblp>\n")
    val src = new File(scratch("trunc-src"), "trunc.xml")
    java.nio.file.Files.write(src.toPath, out.toByteArray)
    val r = runPipeline(inputRoot("trunc-in", src), scratch("trunc-out"), config())
    val works =
      rows(r.outputRoot, "dblp", "work").map(WireCodec.decode[WorkRecord](_).toOption.get)
    assert(
      works.map(_.sourceRecordId).sorted ==
        Vector("journals/r/G1", "journals/r/G2", "journals/r/G3", "journals/r/G4")
    )
    assert(works.find(_.sourceRecordId == "journals/r/G1").get.title == "Größe one.")
    assert(!works.exists(_.title.contains('�')))
    assert(r.counter("j01", "INPUT_RECORDS") == 6)
    assert(r.counter("j01", "REJECTED") == 2)
    val rejects =
      rows(r.outputRoot, "dblp", "rejects_j01").map(WireCodec.decode[RejectRecord](_).toOption.get)
    assert(
      rejects.map(x => x.sourceRecordRef -> x.reasonCode).sorted ==
        Vector("conf/r/Trunc" -> "MALFORMED_XML", "journals/r/Bad" -> "MALFORMED_XML")
    )
    // The truncated record's excerpt holds only that record, not its neighbours.
    val trunc = rejects.find(_.sourceRecordRef == "conf/r/Trunc").get
    assert(!trunc.rawExcerpt.contains("journals/r/G2"))
    assert(
      rejects
        .find(_.sourceRecordRef == "journals/r/Bad")
        .get
        .rawExcerpt
        .contains("not legal in the declared encoding UTF-8")
    )
  }
