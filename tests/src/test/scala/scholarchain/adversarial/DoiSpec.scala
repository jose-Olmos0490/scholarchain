package scholarchain.adversarial

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.ids.ExternalIds
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.hadoop.jobs.{ExactIdentifierJob, IdRef}

/** Duplicate and conflicting DOIs and duplicate/conflicting DBLP keys. */
class DoiSpec extends AnyFunSuite:
  import Adv.*

  // -------------------------------------------------------------------------------------------
  // Normalization and the outcome rule (pure)
  // -------------------------------------------------------------------------------------------

  test("DOI case, resolver and whitespace variants normalize to one value") {
    val forms = Seq(
      "https://doi.org/10.1145/ADV.1",
      "HTTP://DX.DOI.ORG/10.1145/adv.1",
      "doi:10.1145/Adv.1",
      "  10.1145/adv.1\n"
    )
    assert(forms.map(ExternalIds.normalizeDoi).distinct == Seq(Right("10.1145/adv.1")))
  }

  test("a percent-encoded DOI URL and its decoded form normalize to the same DOI") {
    val raw = ExternalIds.normalizeDoi("https://doi.org/10.1002/(SICI)1097-4571(199806)49:8")
    val encoded =
      ExternalIds.normalizeDoi("https://doi.org/10.1002/%28SICI%291097-4571%28199806%2949%3A8")
    assert(raw.isRight)
    assert(encoded == raw, s"encoded=$encoded raw=$raw")
  }

  private def ref(n: Int, src: SourceType, year: Option[Int]) =
    IdRef(s"work:x:$n", src, Some(s"work:x:$n"), year)

  test("outcome rule: same source DUPLICATE, cross-source year disagreement CONFLICT, else MATCH") {
    import ExactIdOutcome.*
    val d = ExactIdType.DOI
    assert(
      ExactIdentifierJob.outcome(
        d,
        Vector(ref(1, SourceType.DBLP, Some(2010)), ref(2, SourceType.DBLP, Some(2012)))
      ) == DUPLICATE
    )
    assert(
      ExactIdentifierJob.outcome(
        d,
        Vector(ref(1, SourceType.DBLP, Some(2010)), ref(2, SourceType.OPENALEX, Some(2011)))
      ) == CONFLICT
    )
    assert(
      ExactIdentifierJob.outcome(
        d,
        Vector(ref(1, SourceType.DBLP, Some(2010)), ref(2, SourceType.OPENALEX, Some(2010)))
      ) == MATCH
    )
    assert(
      ExactIdentifierJob.outcome(
        d,
        Vector(ref(1, SourceType.DBLP, Some(2010)), ref(2, SourceType.CROSSREF, None))
      ) == MATCH
    )
  }

  test("outcome rule is independent of the order of the records") {
    val refs = Vector(
      ref(1, SourceType.DBLP, Some(2010)),
      ref(2, SourceType.OPENALEX, Some(2010)),
      ref(3, SourceType.CROSSREF, Some(2011)),
      ref(4, SourceType.DBLP, None)
    )
    val outcomes = refs.permutations.map(p => ExactIdentifierJob.outcome(ExactIdType.DOI, p)).toSet
    assert(outcomes == Set(ExactIdOutcome.CONFLICT))
  }

  test("an ORCID on two mentions of one work is a CONFLICT; on two works a MATCH") {
    val o = ExactIdType.ORCID
    val sameWork = Vector(
      IdRef("m1", SourceType.DBLP, Some("w1"), Some(2010)),
      IdRef("m2", SourceType.DBLP, Some("w1"), Some(2010))
    )
    val twoWorks = Vector(
      IdRef("m1", SourceType.DBLP, Some("w1"), Some(2010)),
      IdRef("m3", SourceType.DBLP, Some("w2"), Some(2011))
    )
    assert(ExactIdentifierJob.outcome(o, sameWork) == ExactIdOutcome.CONFLICT)
    assert(ExactIdentifierJob.outcome(o, twoWorks) == ExactIdOutcome.MATCH)
  }

  // -------------------------------------------------------------------------------------------
  // Pipeline: duplicate DOIs across DBLP keys, duplicate and conflicting DBLP keys
  // -------------------------------------------------------------------------------------------

  private def withDoi(key: String, doi: String, title: String, year: String = "2010") =
    article(key, Seq("Dee Oh", "Eye Dee"), title = title, year = year, extra = s"\n<ee>$doi</ee>")

  private val dupDoiRecords = Seq(
    withDoi("journals/adv/D1", "https://doi.org/10.1145/ADV.1", "Shared DOI one."),
    withDoi("journals/adv/D2", "http://dx.doi.org/10.1145/adv.1", "Shared DOI two.", "2012"),
    withDoi("conf/adv/D3", "doi:10.1145/Adv.1", "Shared DOI three.")
  )
  private val sameKeyA =
    withDoi("journals/adv/K1", "https://doi.org/10.1145/adv.k1", "Version A of K1.")
  private val sameKeyB =
    withDoi("journals/adv/K1", "https://doi.org/10.1145/adv.k1", "Version B of K1.")
  private val exactRepeat =
    withDoi("journals/adv/R1", "https://doi.org/10.1145/adv.r1", "Repeated.")

  private def doc(order: Seq[String]) = dblpDoc("ISO-8859-1", order*)

  private lazy val forward: Result =
    runPipeline(
      dblpInput("doi-fwd", doc(dupDoiRecords ++ Seq(sameKeyA, sameKeyB, exactRepeat, exactRepeat)))
    )
  private lazy val backward: Result =
    runPipeline(
      dblpInput(
        "doi-bwd",
        doc(Seq(exactRepeat, exactRepeat, sameKeyB, sameKeyA) ++ dupDoiRecords.reverse)
      )
    )

  private def outcomes(r: Result) =
    r.rows("scholarchain", "exact_identifier_outcome")
      .map(WireCodec.decode[ExactIdentifierOutcome](_).toOption.get)
  private def works(r: Result) =
    r.rows("dblp", "work").map(WireCodec.decode[WorkRecord](_).toOption.get)

  test("pipeline: one DOI in three spellings on three DBLP keys is one DUPLICATE outcome") {
    val ws = works(forward)
    val ids = ws
      .filter(w => Set("journals/adv/D1", "journals/adv/D2", "conf/adv/D3")(w.sourceRecordId))
      .map(_.workId)
      .sorted
    assert(ids.size == 3)
    val doiOutcomes = outcomes(forward).filter(o =>
      o.idType == ExactIdType.DOI && o.normalizedId == "10.1145/adv.1"
    )
    assert(doiOutcomes.size == 1, outcomes(forward))
    assert(doiOutcomes.head.outcome == ExactIdOutcome.DUPLICATE)
    assert(doiOutcomes.head.recordRefs.sorted == ids)
    assert(doiOutcomes.head.provenance.derivedFrom.sorted == ids)
  }

  test("pipeline: a byte-identical repeated record is one work, counted DUPLICATE") {
    assert(works(forward).count(_.sourceRecordId == "journals/adv/R1") == 1)
    assert(forward.counter("j01", "DUPLICATE") >= 1)
  }

  test(
    "pipeline: two different records under one key keep one, reject the other as CONFLICTING_RECORD"
  ) {
    val k1 = works(forward).filter(_.sourceRecordId == "journals/adv/K1")
    assert(k1.size == 1)
    assert(forward.counter("j01", "CONFLICT") == 1)
    val rejects =
      forward.rows("dblp", "rejects_j01").map(WireCodec.decode[RejectRecord](_).toOption.get)
    assert(
      rejects.map(r => r.sourceRecordRef -> r.reasonCode) == Vector(
        "journals/adv/K1" -> "CONFLICTING_RECORD"
      )
    )
  }

  test("pipeline: the kept version of a conflicting key does not depend on input order") {
    val f = works(forward).filter(_.sourceRecordId == "journals/adv/K1").map(_.title)
    val b = works(backward).filter(_.sourceRecordId == "journals/adv/K1").map(_.title)
    assert(f == b)
    assert(outcomes(forward).map(_.outcomeId).sorted == outcomes(backward).map(_.outcomeId).sorted)
    // Run IDs differ (input checksums differ); everything else must be equal.
    def logical(r: Result) =
      outcomes(r)
        .map(o => (o.outcomeId, o.idType, o.normalizedId, o.recordRefs, o.outcome))
        .sortBy(_._1)
    assert(logical(forward) == logical(backward))
  }
