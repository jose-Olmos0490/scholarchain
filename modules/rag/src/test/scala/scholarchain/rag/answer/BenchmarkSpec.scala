package scholarchain.rag.answer

import java.nio.file.{Files, Paths}
import scala.collection.mutable
import scholarchain.core.model.*
import scholarchain.rag.RagFixtures.*
import scholarchain.rag.RagSuite
import scholarchain.rag.agents.*
import scholarchain.rag.workflow.*

/** One benchmark question as the resource file states it. Researchers, works and edges are written
  * as tokens (`r3`, `w900`, `e4-5`, `topic1`) and mapped to the fixture world's stable IDs.
  */
final case class BenchFact(
    kind: String,
    subject: String,
    obj: Option[String],
    field: String,
    value: ujson.Value,
    window: Option[(Int, Int)]
)

final case class BenchQuestion(
    id: String,
    category: String,
    question: String,
    operations: Vector[String],
    metric: Option[String],
    names: Vector[String],
    researcherNumbers: Vector[Int],
    topics: Vector[String],
    window: Option[(Int, Int)],
    expectedOperations: Vector[String],
    expectedTemplates: Vector[String],
    abstainCode: Option[String],
    abstainCandidates: Vector[Int],
    facts: Vector[BenchFact],
    absent: Vector[(String, String, String)],
    semanticFirstEntity: Option[Int],
    semanticDocumentType: Option[String]
)

/** The expected values re-derived from the fixture world's own data (its stored metric rows, edge
  * list and communities), never from the system under test. The benchmark file is accepted only if
  * every expected value equals what this oracle derives.
  */
object FixtureOracle:
  import World.*

  private def numberOf(token: String): Option[Int] =
    Option.when(token.matches("r\\d+"))(token.drop(1).toInt)

  private val basic: Map[String, ResearcherMetrics => Double] = Map(
    "PUBLICATION_COUNT" -> (_.publicationCount.toDouble),
    "DISTINCT_COAUTHOR_COUNT" -> (_.distinctCoauthorCount.toDouble),
    "TOTAL_COAUTHOR_OCCURRENCES" -> (_.totalCoauthorOccurrences.toDouble),
    "AVERAGE_COAUTHORS_PER_PAPER" -> (_.averageCoauthorsPerPaper),
    "MAXIMUM_COAUTHORS_ON_ONE_PAPER" -> (_.maximumCoauthorsOnOnePaper.toDouble),
    "WEIGHTED_DEGREE" -> (_.weightedDegree),
    "COMPONENT_SIZE" -> (_.componentSize.toDouble),
    "CORENESS" -> (_.coreness.getOrElse(0).toDouble)
  )

  private def row(n: Int): ResearcherMetrics = metricsRow(n)

  private val adjacency: Map[Int, Vector[(Int, Edge)]] =
    edges
      .flatMap(e => Vector(e.a -> (e.b, e), e.b -> (e.a, e)))
      .groupMap(_._1)(_._2)
      .withDefaultValue(Vector.empty)

  private def distances(from: Int): Map[Int, Int] =
    val adj = adjacency
    val dist = mutable.Map(from -> 0)
    val queue = mutable.Queue(from)
    while queue.nonEmpty do
      val cur = queue.dequeue()
      adj(cur).foreach((next, _) =>
        if !dist.contains(next) then
          dist(next) = dist(cur) + 1
          queue.enqueue(next)
      )
    dist.toMap

  private def edgeBetween(a: Int, b: Int): Option[Edge] =
    edges.find(e => (e.a == a && e.b == b) || (e.a == b && e.b == a))

  /** The value the fixture data implies for a fact, or none when the fact is not derivable. */
  def expected(f: BenchFact): Option[Either[Double, String]] =
    val subjectNumber = numberOf(f.subject)
    def metricOf(field: String, n: Int) = basic.get(field).map(g => Left(g(row(n))))
    (f.kind, f.field) match
      case ("METRIC", fld) if basic.contains(fld) => subjectNumber.flatMap(metricOf(fld, _))
      case ("METRIC", fld) if fld.startsWith("RANK_BY_") || fld.startsWith("TOPIC_RANK_BY_") =>
        val metric = fld.stripPrefix("TOPIC_RANK_BY_").stripPrefix("RANK_BY_")
        for
          n <- subjectNumber
          g <- basic.get(metric)
        yield
          val ordered =
            (1 to 12).sortBy(i => (-g(row(i)), row(i).researcherId))
          Left((ordered.indexOf(n) + 1).toDouble)
      case ("METRIC", "MAXIMUM_TEAM_WORK") =>
        subjectNumber.map(n => Right(row(n).maximumTeamWorkIds.head))
      case ("COAUTHOR_EDGE", fld) =>
        for
          a <- subjectNumber
          b <- f.obj.flatMap(numberOf)
          e <- edgeBetween(a, b)
          v <- fld match
            case "JOINT_PUBLICATION_COUNT" => Some(Left(e.count.toDouble))
            case "FIRST_COLLABORATION_YEAR" => Some(Left(e.first.toDouble))
            case "LAST_COLLABORATION_YEAR" => Some(Left(e.last.toDouble))
            case "COAUTHOR" => Some(Right(edgeId(e.a, e.b)))
            case _ => None
        yield v
      case ("PATH_HOP", "PATH_LENGTH") =>
        for
          a <- subjectNumber
          b <- f.obj.flatMap(numberOf)
          d <- distances(a).get(b)
        yield Left(d.toDouble)
      case ("PATH_HOP", fld) =>
        for
          a <- subjectNumber
          b <- f.obj.flatMap(numberOf)
          e <- edgeBetween(a, b)
          v <- fld match
            case "HOP" => Some(Right(edgeId(e.a, e.b)))
            case "HOP_JOINT_PUBLICATION_COUNT" => Some(Left(e.count.toDouble))
            case _ => None
        yield v
      case ("PUBLICATION", "SUPPORTING_WORK") =>
        // the edge whose supporting works include the work: work(a*100 + b*10 + k)
        edges
          .find(e => workIdsOf(e).exists(w => workToken(w).contains(f.subject)))
          .map(e => Right(edgeId(e.a, e.b)))
      case ("COMMUNITY", "COMMUNITY_ID") =>
        subjectNumber.map(n => Right(communityOfNumber(n)))
      case ("COMMUNITY", "COMMUNITY_SIZE") => subjectNumber.map(_ => Left(6.0))
      case ("METRIC", "COMMUNITY_SIZE") => subjectNumber.map(_ => Left(6.0))
      case ("COMMUNITY", "COMPONENT_ID") => subjectNumber.map(_ => Right(r(1)))
      case ("COMMUNITY", "COMPONENT_SIZE") =>
        subjectNumber.map(_ => Left(distances(1).size.toDouble))
      case ("BRIDGE", "CROSS_COMMUNITY_DEGREE") =>
        subjectNumber.flatMap(n =>
          row(n).bridgeScore
            .filter(_ => row(n).bridgeMetric.contains(CollaborationMetric.CROSS_COMMUNITY_DEGREE))
            .map(Left(_))
        )
      case ("BRIDGE", "CROSS_COMMUNITY_EDGE_COUNT") =>
        // the one stored cross-community edge joins researchers 6 and 7
        subjectNumber.map(_ =>
          Left(edges.count(e => communityOfNumber(e.a) != communityOfNumber(e.b)).toDouble)
        )
      case ("BRIDGE", "COMMUNITIES_CONNECTED") =>
        subjectNumber.map(n =>
          Left(
            edges
              .collect {
                case e if communityOfNumber(e.a) != communityOfNumber(e.b) =>
                  Set(communityOfNumber(e.a), communityOfNumber(e.b))
              }
              .flatten
              .filter(_ != communityOfNumber(n))
              .distinct
              .size
              .toDouble
          )
        )
      case ("IDENTITY", "CONFIDENCE") =>
        subjectNumber.flatMap(n => identityConfidence.get(n).map(Left(_)))
      case ("IDENTITY", "NAME") => subjectNumber.flatMap(n => identityName.get(n).map(Right(_)))
      case _ => None

  private val identityConfidence: Map[Int, Double] = Map(1 -> 0.97, 4 -> 0.95)
  private val identityName: Map[Int, String] = Map(1 -> "Ada Example", 4 -> "Lin Chen")

  private def workToken(id: String): Option[String] =
    (100 to 1300).find(n => work(n) == id).map(n => s"w$n")

  /** Resolves a token to a fixture ID. Anything that is not a token is a literal. */
  def id(token: String): String =
    if token.matches("r\\d+") then r(token.drop(1).toInt)
    else if token.matches("w\\d+") then work(token.drop(1).toInt)
    else if token.matches("e\\d+-\\d+") then
      val Array(a, b) = token.drop(1).split('-')
      edgeId(a.toInt, b.toInt)
    else if token.matches("topic\\d+") then topic(token.drop(5).toInt)
    else token

/** What happened to one question. */
final case class BenchOutcome(
    q: BenchQuestion,
    plan: Boolean,
    deterministic: Option[(Int, Int)],
    semantic: Option[Boolean],
    abstention: Option[Boolean],
    grounded: Option[Boolean],
    mutatedRemoved: Option[Boolean],
    adversarial: Option[Boolean]
):
  def deterministicOk: Boolean = deterministic.forall((f, t) => f == t)
  def passed: Boolean =
    plan && deterministicOk && semantic.forall(identity) && abstention.forall(identity) &&
      grounded.forall(identity) && mutatedRemoved.forall(identity) && adversarial.forall(identity)

/** The 30-question benchmark against the fixture world: plan operations and templates,
  * deterministic retrieval, semantic retrieval, abstention, and groundedness of an answer written
  * from the expected facts (and of the same answer with each value made wrong).
  */
class BenchmarkSpec extends RagSuite with WorkflowWorld:
  import World.*

  addDoc(
    indexNames.researchers,
    "researcher-profile",
    r(1),
    s"Researcher profile for ${r(1)} Ada Example works on graph mining and graph databases"
  )
  addDoc(
    indexNames.researchers,
    "researcher-profile",
    r(4),
    s"Researcher profile for ${r(4)} Lin Chen works on query optimization"
  )
  addDoc(
    indexNames.researchers,
    "researcher-profile",
    r(6),
    s"Researcher profile for ${r(6)} the bridge researcher between the two communities"
  )
  addDoc(
    indexNames.communities,
    "collaboration-community",
    r(1),
    s"Community led by ${r(1)} about graph mining and databases"
  )
  addDoc(
    indexNames.communities,
    "collaboration-community",
    r(7),
    s"Community led by ${r(7)} about stream processing and data pipelines"
  )

  // ---- reading the benchmark file ------------------------------------------------------------

  private def window(v: ujson.Value): Option[(Int, Int)] = v match
    case ujson.Arr(a) => Some((a(0).num.toInt, a(1).num.toInt))
    case _ => None

  private def strs(v: ujson.Value): Vector[String] = v.arr.toVector.map(_.str)

  private val questions: Vector[BenchQuestion] =
    val text = scala.io.Source
      .fromResource("benchmark/questions-v1.json")(scala.io.Codec.UTF8)
      .mkString
    ujson.read(text)("questions").arr.toVector.map { q =>
      val req = q("request")
      BenchQuestion(
        q("id").str,
        q("category").str,
        q("question").str,
        strs(req("operations")),
        req("metric").strOpt,
        strs(req("names")),
        req("researcherNumbers").arr.toVector.map(_.num.toInt),
        strs(req("topics")),
        window(req("window")),
        strs(q("expectedOperations")),
        strs(q("expectedTemplates")),
        q("expectedAbstain") match
          case o: ujson.Obj => Some(o("code").str)
          case _ => None
        ,
        q("expectedAbstain") match
          case o: ujson.Obj => o("candidateNumbers").arr.toVector.map(_.num.toInt)
          case _ => Vector.empty
        ,
        q("expectedFacts").arr.toVector.map { f =>
          BenchFact(
            f("kind").str,
            f("subject").str,
            f("object").strOpt,
            f("field").str,
            f("value"),
            window(f("window"))
          )
        },
        q("expectedAbsent").arr.toVector
          .map(a => (a("kind").str, a("subject").str, a("field").str)),
        q("expectedSemantic") match
          case o: ujson.Obj => Some(o("firstEntityNumber").num.toInt)
          case _ => None
        ,
        q("expectedSemantic") match
          case o: ujson.Obj => Some(o("documentType").str)
          case _ => None
      )
    }

  private def requestOf(q: BenchQuestion): AnswerRequest =
    request(
      StructuredFields(
        operations = q.operations,
        researcherNames = q.names,
        researcherIds = q.researcherNumbers.map(r),
        windowStartYear = q.window.map(_._1),
        windowEndYear = q.window.map(_._2),
        topics = q.topics.map(FixtureOracle.id),
        rankingMetric = q.metric
      ),
      question = Some(q.question),
      name = s"bench-${q.id}"
    )

  // ---- matching an expected fact against the bundle ----------------------------------------

  private def tokenValue(f: BenchFact): AssertedValue = f.value match
    case ujson.Num(d) => AssertedValue.Num(d)
    case ujson.Str(s) => AssertedValue.Text(FixtureOracle.id(s))
    case _ => AssertedValue.Absent

  private def subjectOf(f: BenchFact) = FixtureOracle.id(f.subject)
  private def objectOf(f: BenchFact) = f.obj.map(FixtureOracle.id)

  private def matches(f: BenchFact, fact: Fact): Boolean =
    fact.kind.toString == f.kind && fact.subject == subjectOf(f) && fact.field == f.field &&
      fact.obj == objectOf(f) && fact.window.map(w => (w.start, w.end)) == f.window &&
      ((tokenValue(f), fact.value) match
        case (AssertedValue.Num(x), FactValue.Num(y)) =>
          if AssertionFields.isInteger(f.field) then x == y else math.abs(x - y) <= Tolerance.Real
        case (AssertedValue.Text(x), FactValue.Text(y)) => x == y
        case _ => false)

  private def factsOf(b: EvidenceBundle): Vector[(Fact, ItemFacts)] =
    EvidenceFacts.ofBundle(b).filter(_.graph).flatMap(i => i.facts.map(_ -> i))

  // ---- running a question --------------------------------------------------------------------

  /** The evidence a fact is cited to, chosen without the system's fact parser: the graph items
    * whose stored text mentions the fact's subject (and object), or whose structured fields do
    * (an edge's referenced ID, a path's researcher list).
    */
  private def citationFor(f: BenchFact, b: EvidenceBundle): Vector[String] =
    val subject = subjectOf(f)
    val obj = objectOf(f)
    val cited = b.graphEvidence.filter { i =>
      val text = i.text.getOrElse("")
      val structural = i.graphPath.exists(p => p.contains(subject) && obj.forall(p.contains)) ||
        (f.kind == "COAUTHOR_EDGE" || f.kind == "PATH_HOP") && obj.exists(o =>
          i.referencedIds.contains(edgeOfIds(subject, o))
        )
      structural || (text.contains(subject) && obj.forall(text.contains))
    }
    withClue(s"no graph evidence for $f: ")(cited should not be empty)
    cited.map(_.evidenceId).distinct

  private def edgeOfIds(a: String, b: String): String =
    (1 to 12)
      .flatMap(x => (1 to 12).map(y => (x, y)))
      .collectFirst {
        case (x, y) if (r(x) == a && r(y) == b) || (r(x) == b && r(y) == a) => edgeId(x, y)
      }
      .getOrElse("")

  enum Mutation:
    case Value, Subject, Object, Window

  private def applies(f: BenchFact, m: Mutation): Boolean = m match
    case Mutation.Value | Mutation.Subject => true
    case Mutation.Object => f.obj.isDefined
    case Mutation.Window => f.window.isDefined

  /** One expected fact as an answer claim, optionally made wrong in one respect: the value, the
    * subject, the object or the window.
    */
  private def claimFor(
      f: BenchFact,
      g: Gathered,
      mutation: Option[Mutation]
  ): ujson.Value =
    val value: ujson.Value = (tokenValue(f), mutation.contains(Mutation.Value)) match
      case (AssertedValue.Num(d), false) => ujson.Num(d)
      // a path length one above the shortest is wrong even when a longer path is stored
      case (AssertedValue.Num(d), true) => ujson.Num(d + 1)
      case (AssertedValue.Text(t), false) => ujson.Str(t)
      case (AssertedValue.Text(_), true) => ujson.Str(researcher(9999))
      case (_, false) => ujson.Null
      case (_, true) => ujson.Num(7)
    claim(
      "one expected fact",
      citationFor(f, g.built.bundle),
      A(
        f.kind,
        if mutation.contains(Mutation.Subject) then researcher(9999) else subjectOf(f),
        f.field,
        value,
        if mutation.contains(Mutation.Window) then f.window.map((s, e) => (s, e - 1)) else f.window,
        if mutation.contains(Mutation.Object) then Some(researcher(9998)) else objectOf(f)
      )
    )

  private def answered(q: BenchQuestion, claims: Seq[ujson.Value]): WorkflowResult =
    new Rig(new ScriptedLlm(Seq(synthReply(claims)))).workflow
      .answer(requestOf(q))
      .fold(f => fail(f.message), identity)

  /** Presentations a misaligned or injected model might send to slip a wrong statement past the
    * verifier. The model writes no prose, so every attack is an assertion: a relation that no
    * stored edge, path or community backs, a window that was never stored, a bound the path
    * query does not give, a subject that is not in the evidence.
    */
  private def adversarialClaims(q: BenchQuestion, g: Gathered): Vector[ujson.Value] =
    val firstCite = g.built.bundle.graphEvidence.head.evidenceId
    val ws = q.window.getOrElse((WS, WE))
    val stray = r(12)
    Vector(
      claim(
        "a coauthor edge that is not stored",
        Seq(firstCite),
        A("COAUTHOR_EDGE", r(1), "COAUTHOR", ujson.Null, Some(ws), obj = Some(stray))
      ),
      claim(
        "a path bound for a pair without stored paths",
        Seq(firstCite),
        A("PATH_HOP", r(1), "PATH_WITHIN_HOPS", ujson.Num(2), Some(ws), obj = Some(stray))
      ),
      claim(
        "an exact shortest length for a pair without stored paths",
        Seq(firstCite),
        A("PATH_HOP", r(1), "PATH_LENGTH", ujson.Num(2), Some(ws), obj = Some(stray))
      ),
      claim(
        "an identity with an invented window",
        Seq(firstCite),
        A("IDENTITY", r(1), "CONFIDENCE", ujson.Num(0.97), Some((1900, 2100)))
      ),
      claim(
        "the same community for a researcher outside the evidence",
        Seq(firstCite),
        A("COMMUNITY", researcher(9999), "COMMUNITY_ID", ujson.Str(r(1)), Some(ws))
      ),
      claim(
        "a window that was never stored",
        Seq(firstCite),
        A("WINDOW", r(1), "ANALYSIS_WINDOW", ujson.Null, Some((1900, 2100)))
      ),
      claim(
        "an assertion that cites evidence which is not in the bundle",
        Seq(researcher(9999)),
        A("METRIC", r(1), "DISTINCT_COAUTHOR_COUNT", ujson.Num(4), Some(ws))
      )
    )

  private def run(q: BenchQuestion): BenchOutcome =
    val req = requestOf(q)
    val llm = new ScriptedLlm(Seq.empty)
    val g = new Rig(llm).workflow.collect(req).fold(f => fail(s"${q.id}: ${f.message}"), identity)
    llm.requests shouldBe empty

    val plan = g.plan match
      case Some(p) if q.abstainCode.isEmpty || g.abstention.exists(_.code != "PLAN_INCOMPLETE") =>
        val steps = OperationAllowlist
          .steps(
            p.operations,
            p.rankingMetric,
            p.topics.nonEmpty,
            p.maxPathDepth
          )
          .flatMap(_.templateIds)
        p.operations.map(
          _.toString
        ) == q.expectedOperations && steps.toSet == q.expectedTemplates.toSet
      case _ => false

    val deterministic = Option.when(q.facts.nonEmpty) {
      val all = factsOf(g.built.bundle).map(_._1)
      val found = q.facts.count(f => all.exists(matches(f, _)))
      val absentOk = q.absent.forall((kind, subject, field) =>
        !all.exists(a =>
          a.kind.toString == kind && a.subject == FixtureOracle.id(subject) && a.field == field
        )
      )
      (found, q.facts.size + (if absentOk then 0 else 1))
    }

    val semantic = q.semanticFirstEntity.map { n =>
      val first = g.built.bundle.semanticEvidence.headOption
      first.exists(i =>
        i.sourceUri.contains(r(n)) && q.semanticDocumentType.forall(t => i.sourceUri.contains(t))
      ) &&
      g.built.bundle.semanticEvidence.forall(i => !i.evidenceType.isGraphFact)
    }

    val abstention = q.abstainCode.map { code =>
      g.abstention.exists(a =>
        a.code == code && a.candidates.map(_.researcherId).toSet == q.abstainCandidates.map(r).toSet
      )
    }

    // groundedness: an answer written from exactly the expected facts verifies, and the same
    // answer made wrong in any one respect (value, subject, object, window) is removed
    val (grounded, mutated) =
      if q.abstainCode.isEmpty && q.facts.nonEmpty then
        def answerWith(m: Option[Mutation]) =
          val applicable = q.facts.filter(f => m.forall(applies(f, _)))
          applicable.grouped(20).toVector.map { fs =>
            answered(q, fs.map(claimFor(_, g, m)))
          }
        val ok1 = answerWith(None).forall(res =>
          res.removedClaims.isEmpty && res.response.claims.nonEmpty &&
            res.response.claims.forall(_.verificationStatus == VerificationStatus.VERIFIED) &&
            res.verification == VerificationStatus.VERIFIED
        )
        val ok2 = Mutation.values.toVector.forall(m =>
          answerWith(Some(m)).forall(res =>
            res.response.claims.isEmpty && res.removedClaims.forall(c =>
              c.status == VerificationStatus.CONFLICTING || c.status == VerificationStatus.UNSUPPORTED
            )
          )
        )
        (Some(ok1), Some(ok2))
      else (None, None)

    val adversarial = Option.when(q.abstainCode.isEmpty && q.facts.nonEmpty) {
      val attacks = adversarialClaims(q, g)
      val res = answered(q, attacks :+ claimFor(q.facts.head, g, None))
      res.response.claims.map(_.text) ==
        Vector(shownOf(g.built.bundle, claimFor(q.facts.head, g, None))) &&
        res.removedClaims.map(_.ordinal) == attacks.indices.toVector &&
        res.removedClaims.forall(c =>
          c.status == VerificationStatus.UNSUPPORTED || c.status == VerificationStatus.CONFLICTING
        )
    }

    BenchOutcome(q, plan, deterministic, semantic, abstention, grounded, mutated, adversarial)

  private lazy val outcomes: Vector[BenchOutcome] = questions.map(run)

  // ---- tests -----------------------------------------------------------------------------------

  test("the benchmark has 30 questions covering every required category") {
    questions.size shouldBe 30
    questions.map(_.id).distinct.size shouldBe 30
    questions.map(_.category).toSet shouldBe Set(
      "coauthor_breadth",
      "max_team_size",
      "direct_collaboration",
      "shortest_paths",
      "components",
      "communities",
      "bridges",
      "temporal_change",
      "topic_filters",
      "ambiguous_identity",
      "evidence"
    )
    questions.foreach { q =>
      withClue(q.id)(q.expectedOperations should not be empty)
      withClue(q.id)(
        (q.facts.nonEmpty || q.abstainCode.isDefined || q.semanticFirstEntity.isDefined) shouldBe true
      )
    }
  }

  test("every expected value equals the value derived from the fixture data") {
    val derivable = questions.flatMap(q => q.facts.map(q.id -> _))
    derivable.size should be > 80
    derivable.foreach { (id, f) =>
      val derived = FixtureOracle.expected(f)
      withClue(s"$id $f: the fixture oracle cannot derive it. ")(derived should not be empty)
      (derived.get, f.value) match
        case (Left(d), ujson.Num(v)) => withClue(s"$id $f")(v shouldBe d +- 1e-9)
        case (Right(s), ujson.Str(v)) =>
          withClue(s"$id $f")(FixtureOracle.id(v) shouldBe s)
        case other => fail(s"$id $f: value kind differs from the derived one: $other")
    }
  }

  test("plans: every question is planned with the expected operations and storage templates") {
    outcomes.foreach(o => withClue(o.q.id)(o.plan shouldBe true))
  }

  test("deterministic retrieval: every expected fact is in the bundle's graph evidence") {
    outcomes.foreach(o =>
      withClue(s"${o.q.id} ${o.deterministic}")(o.deterministicOk shouldBe true)
    )
  }

  test("semantic retrieval: the expected document is the first hit and is labeled context") {
    val sem = outcomes.filter(_.semantic.isDefined)
    sem.size shouldBe 3
    sem.foreach(o => withClue(o.q.id)(o.semantic shouldBe Some(true)))
  }

  test(
    "abstention: questions that cannot be answered abstain with the expected code and candidates, and never reach the model"
  ) {
    val ab = outcomes.filter(_.abstention.isDefined)
    ab.size shouldBe 6
    ab.foreach(o => withClue(o.q.id)(o.abstention shouldBe Some(true)))
  }

  test(
    "groundedness: an answer from the expected facts verifies; the same answer with wrong values is removed"
  ) {
    val g = outcomes.filter(_.grounded.isDefined)
    g.size should be >= 20
    g.foreach { o =>
      withClue(o.q.id)(o.grounded shouldBe Some(true))
      withClue(o.q.id)(o.mutatedRemoved shouldBe Some(true))
    }
  }

  test(
    "adversarial presentations over every answerable question: unstored relations, bounds, windows and citations are removed, the true claim stays"
  ) {
    val adv = outcomes.filter(_.adversarial.isDefined)
    adv.size should be >= 20
    adv.foreach(o => withClue(o.q.id)(o.adversarial shouldBe Some(true)))
  }

  test("per-category report") {
    val byCategory = outcomes.groupBy(_.q.category).toVector.sortBy(_._1)
    val lines = byCategory.map { (cat, os) =>
      val pass = os.count(_.passed)
      val det = os.flatMap(_.deterministic)
      val sem = os.flatMap(_.semantic)
      f"$cat%-22s pass ${pass}/${os.size}  deterministic ${det.map(_._1).sum}/${det.map(_._2).sum}  semantic ${sem.count(identity)}/${sem.size}"
    }
    val total = outcomes.count(_.passed)
    val detAll = outcomes.flatMap(_.deterministic)
    val semAll = outcomes.flatMap(_.semantic)
    val report = ujson.Obj(
      "benchmarkVersion" -> "benchmark-v1",
      "adversarial" -> ujson.Obj(
        "held" -> outcomes.count(_.adversarial.contains(true)),
        "questions" -> outcomes.count(_.adversarial.isDefined)
      ),
      "questions" -> outcomes.size,
      "passed" -> total,
      "deterministicRetrieval" -> ujson.Obj(
        "found" -> detAll.map(_._1).sum,
        "expected" -> detAll.map(_._2).sum
      ),
      "semanticRetrieval" -> ujson.Obj(
        "hitAtOne" -> semAll.count(identity),
        "questions" -> semAll.size
      ),
      "categories" -> ujson.Obj.from(byCategory.map { (cat, os) =>
        cat -> ujson.Obj(
          "passed" -> os.count(_.passed),
          "total" -> os.size,
          "failed" -> ujson.Arr.from(os.filterNot(_.passed).map(o => ujson.Str(o.q.id)))
        )
      })
    )
    val dir = Paths.get("modules/rag/target/rag-examples")
    Files.createDirectories(dir)
    Files.writeString(dir.resolve("benchmark-report.json"), ujson.write(report, indent = 2))
    println("BENCHMARK per category (pass / total):")
    lines.foreach(l => println(s"  $l"))
    println(
      s"BENCHMARK adversarial: ${outcomes.count(_.adversarial.contains(true))} / ${outcomes.count(_.adversarial.isDefined)}"
    )
    println(s"BENCHMARK total: $total / ${outcomes.size}")
    outcomes.filterNot(_.passed).map(_.q.id) shouldBe empty
  }
