package scholarchain.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.Fixtures.*
import scholarchain.core.TestSupport.*
import scholarchain.core.ids.*
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions
import scholarchain.core.validation.RecordContract.given
import scholarchain.core.validation.ValidationError
import scholarchain.core.validation.ValidationError.*
import upickle.default.writeJs

/** `coauthor-pair-candidate-v2`: the pair contract with source author positions (Phase 4 repair).
  *
  * v2 is frozen like v1: `golden/v2/coauthor-pair-candidate.json` is pinned by
  * `golden/v2/SHA256SUMS` and must be reproduced byte for byte by the encoder. v1 stays decodable
  * and its frozen golden is covered by [[GoldenFreezeSpec]].
  */
class CoauthorPairCandidateV2Spec extends AnyFunSuite with Matchers:

  private val GoldenFile = "coauthor-pair-candidate.json"

  private def readResource(path: String): String =
    val in = getClass.getResourceAsStream(path)
    require(in != null, s"missing resource $path")
    try new String(in.readAllBytes(), StandardCharsets.UTF_8)
    finally in.close()

  private def goldenV2Text: String = readResource(s"/golden/v2/$GoldenFile")
  private def goldenV2Json: ujson.Value = ujson.read(goldenV2Text)

  private def sha256(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private def encodedBytes(c: CoauthorPairCandidate): String =
    ujson.write(writeJs(c), indent = 2) + "\n"

  private def decode(json: String) = WireCodec.decode[CoauthorPairCandidate](json)

  private def build(x: Int, y: Int) =
    CoauthorPairCandidate.fromPositions(workId, x, y, 2000, 5, dblpProv)

  private def mention(p: Int): String = ok(StableIds.mentionId(workId, p))

  // --- Freeze ---------------------------------------------------------------------------------

  test("v2 golden: SHA256SUMS pins exactly the v2 golden file and its bytes") {
    val sums = readResource("/golden/v2/SHA256SUMS").linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(_.split("\\s+", 2) match
        case Array(h, f) => f -> h
        case other => fail(s"malformed line ${other.mkString(" ")}"))
      .toMap
    sums.keySet shouldBe Set(GoldenFile)
    sha256(goldenV2Text) shouldBe sums(GoldenFile)
  }

  test("v2 golden: the encoder reproduces it byte for byte") {
    encodedBytes(pairCandidateV2) shouldBe goldenV2Text
  }

  test("v2 golden: wire field names, in order") {
    goldenV2Json.obj.keys.toVector shouldBe Vector(
      "schemaVersion",
      "candidateId",
      "workId",
      "memberA",
      "memberB",
      "positionA",
      "positionB",
      "year",
      "teamSize",
      "provenance"
    )
    goldenV2Json.obj("schemaVersion").str shouldBe "coauthor-pair-candidate-v2"
  }

  test("v2 golden: decodes to the fixture, round-trips, and the fixture is valid") {
    decode(goldenV2Text) shouldBe Right(pairCandidateV2)
    decode(writeJs(pairCandidateV2).render()) shouldBe Right(pairCandidateV2)
    errorsOf(pairCandidateV2) shouldBe empty
  }

  test("CoauthorPairCandidate accepts exactly v1 and v2") {
    SchemaVersions.supportedFor("CoauthorPairCandidate") shouldBe Set(
      "coauthor-pair-candidate-v1",
      "coauthor-pair-candidate-v2"
    )
    SchemaVersions.CoauthorPairCandidateV2 shouldBe "coauthor-pair-candidate-v2"
  }

  test("v3 is rejected before field decoding") {
    val json = editObj(goldenV2Json)(_("schemaVersion") = ujson.Str("coauthor-pair-candidate-v3"))
    decode(json) match
      case Left(Vector(UnsupportedSchemaVersion("schemaVersion", "CoauthorPairCandidate", f, _))) =>
        f shouldBe "coauthor-pair-candidate-v3"
      case other => fail(s"expected one UnsupportedSchemaVersion, got $other")
  }

  // --- v1 compatibility -----------------------------------------------------------------------

  test("v1: the frozen golden decodes with no positions and re-encodes without position keys") {
    val v1 = decode(goldenText(GoldenFile))
    v1 shouldBe Right(pairCandidate)
    pairCandidate.positionA shouldBe None
    pairCandidate.positionB shouldBe None
    writeJs(pairCandidate).obj.keySet should not contain "positionA"
    writeJs(pairCandidate).obj.keySet should not contain "positionB"
  }

  test("v1: a record labelled v1 that carries positions is rejected") {
    paths(errorsOf(pairCandidate.copy(positionA = Some(4), positionB = Some(2)))) shouldBe Vector(
      "positionA",
      "positionB"
    )
    val json = editObj(goldenJson(GoldenFile)) { o =>
      o("positionA") = ujson.Num(4)
    }
    paths(decode(json).left.toOption.get) shouldBe Vector("positionA")
  }

  test("candidateId is the v1 formula: the same pair has the same ID in v1 and v2") {
    pairCandidateV2.candidateId shouldBe pairCandidate.candidateId
    pairCandidateV2.candidateId shouldBe ok(
      StableIds.pairCandidateId(workId, mention(2), mention(4))
    )
    (pairCandidateV2.memberA, pairCandidateV2.memberB) shouldBe
      (pairCandidate.memberA, pairCandidate.memberB)
  }

  // --- Producer helper ------------------------------------------------------------------------

  test("fromPositions: input order does not matter and positions stay aligned with members") {
    build(4, 2) shouldBe build(2, 4)
    val c = build(2, 4).toOption.get
    CanonicalPair.isCanonical(c.memberA, c.memberB) shouldBe true
    c.memberA shouldBe mention(c.positionA.get)
    c.memberB shouldBe mention(c.positionB.get)
    // In this fixture the canonical order is the reverse of position order.
    c.positionA shouldBe Some(4)
    c.positionB shouldBe Some(2)
    c.schemaVersion shouldBe SchemaVersions.CoauthorPairCandidateV2
  }

  test("fromPositions: every position pair of a 6-author team yields a valid, aligned record") {
    val all = for
      x <- 1 to 6
      y <- 1 to 6
      if x != y
    yield build(x, y).toOption.get
    all.foreach { c =>
      errorsOf(c) shouldBe empty
      c.memberA shouldBe mention(c.positionA.get)
      c.memberB shouldBe mention(c.positionB.get)
    }
    all.map(_.candidateId).distinct should have size 15
  }

  test("fromPositions: rejects equal positions, position 0 and a malformed workId") {
    build(3, 3).isLeft shouldBe true
    build(0, 2).isLeft shouldBe true
    build(2, 0).isLeft shouldBe true
    build(-1, 2).isLeft shouldBe true
    CoauthorPairCandidate.fromPositions("work:bad", 1, 2, 2000, 5, dblpProv).isLeft shouldBe true
    CoauthorPairCandidate.fromPositions(workId, 1, 2, 2000, 5, null).isLeft shouldBe true
  }

  // --- v2 negative validation -----------------------------------------------------------------

  private def paths(errors: Vector[ValidationError]): Vector[String] = errors.map(_.path)

  test("v2: misaligned (swapped) positions are rejected on both members") {
    val swapped = pairCandidateV2.copy(
      positionA = pairCandidateV2.positionB,
      positionB = pairCandidateV2.positionA
    )
    errorsOf(swapped).collect { case e: StableIdMismatch => e.path } shouldBe Vector(
      "memberA",
      "memberB"
    )
    val json = editObj(goldenV2Json) { o =>
      o("positionA") = ujson.Num(2)
      o("positionB") = ujson.Num(4)
    }
    decode(json).left.toOption.get.collect { case e: StableIdMismatch => e.path } shouldBe Vector(
      "memberA",
      "memberB"
    )
  }

  test("v2: equal positions are rejected") {
    val equal = pairCandidateV2.copy(positionB = pairCandidateV2.positionA)
    val errors = errorsOf(equal)
    paths(errors) should contain("positionA,positionB")
    errors.collect { case e: StableIdMismatch => e.path } shouldBe Vector("memberB")
  }

  test("v2: position 0 and negative positions are rejected") {
    paths(errorsOf(pairCandidateV2.copy(positionA = Some(0)))) shouldBe Vector("positionA")
    paths(errorsOf(pairCandidateV2.copy(positionB = Some(-3)))) shouldBe Vector("positionB")
    val json = editObj(goldenV2Json)(_("positionB") = ujson.Num(0))
    paths(decode(json).left.toOption.get) shouldBe Vector("positionB")
  }

  test("v2: a position that does not derive its member's mention ID is rejected") {
    errorsOf(pairCandidateV2.copy(positionA = Some(5))).collect { case e: StableIdMismatch =>
      (e.path, e.expected)
    } shouldBe Vector(("memberA", mention(5)))
    errorsOf(pairCandidateV2.copy(memberB = mention(1))).collect { case e: StableIdMismatch =>
      e.path
    } should contain("memberB")
  }

  test("v2: missing or null positions are rejected") {
    paths(errorsOf(pairCandidateV2.copy(positionA = None))) shouldBe Vector("positionA")
    paths(errorsOf(pairCandidateV2.copy(positionA = None, positionB = None))) shouldBe Vector(
      "positionA",
      "positionB"
    )
    paths(decode(editObj(goldenV2Json)(_.remove("positionB"))).left.toOption.get) shouldBe
      Vector("positionB")
    paths(decode(editObj(goldenV2Json)(_("positionA") = ujson.Null)).left.toOption.get) shouldBe
      Vector("positionA")
    paths(errorsOf(pairCandidateV2.copy(positionA = null))) shouldBe Vector("positionA")
  }

  test("v2: missing provenance is rejected on decode") {
    decode(editObj(goldenV2Json)(_.remove("provenance"))) shouldBe Left(
      Vector(MissingProvenance("provenance"))
    )
  }
