package scholarchain.rag.answer

import java.util.concurrent.CountDownLatch
import scala.jdk.CollectionConverters.*
import scholarchain.rag.RagFixtures.*
import scholarchain.rag.RagSuite
import scholarchain.rag.agents.*
import scholarchain.rag.workflow.StageRunner
import scholarchain.storage.common.Result
import scholarchain.storage.directory.{NameCandidate, ResearcherDirectory}

/** Repair 02, findings 6 and 10. */
class ResolverRunnerRepairSpec extends RagSuite:
  import World.*

  private def agentWith(candidates: Vector[NameCandidate]) =
    val dir = new ResearcherDirectory:
      def candidatesByName(n: String, limit: Int): Result[Vector[NameCandidate]] =
        Right(candidates)
    new ResearcherResolverAgent(dir, resolverCfg)

  private def withConfidence(n: Int, c: Double) =
    candidate(n, "Ada Example", 0.9).copy(confidence = c)

  test("a candidate with a NaN confidence is refused with a typed reason, not silently dropped") {
    val out = agentWith(Vector(candidate(1, "Ada Example", 0.9), withConfidence(2, Double.NaN)))
      .resolve("Ada Example")
    out match
      case Left(StageFailure.Rejected(code, detail)) =>
        code shouldBe "CANDIDATE_CONFIDENCE_INVALID"
        detail should include("outside")
      case other => fail(s"expected a typed refusal, got $other")
  }

  test("a candidate with an out-of-range confidence is refused, whatever its rank") {
    Seq(1.5, -0.1, Double.PositiveInfinity).foreach { bad =>
      val out =
        agentWith(Vector(candidate(1, "Ada Example", 0.97), withConfidence(2, bad)))
          .resolve("Ada Example")
      withClue(bad.toString)(out.left.map(_.code) shouldBe Left("CANDIDATE_CONFIDENCE_INVALID"))
    }
  }

  test("valid candidates still resolve") {
    val out = agentWith(Vector(candidate(1, "Ada Example", 0.97))).resolve("Ada Example")
    out.toOption.get shouldBe a[Resolution.Resolved]
  }

  test("the stage runner keeps a bounded number of threads when stages ignore interruption") {
    val release = new CountDownLatch(1)
    val runner = new StageRunner(30)
    val threads = java.util.concurrent.ConcurrentHashMap.newKeySet[Thread]()
    def stuck(): scholarchain.rag.agents.SResult[Unit] =
      threads.add(Thread.currentThread())
      var done = false
      while !done do
        try
          release.await()
          done = true
        catch case _: InterruptedException => () // an uncooperative store call
      Right(())
    try
      val outcomes = (1 to 40).map(i => runner.run(s"s$i")(stuck()))
      // every stage overran its deadline and failed as retryable, whether it ran or queued
      outcomes.foreach(o => o.left.map(_.retryable) shouldBe Left(true))
      // the threads that ran a stage: a cached pool starts a new one for each stuck stage
      threads.size should be <= 16
    finally release.countDown()
  }
