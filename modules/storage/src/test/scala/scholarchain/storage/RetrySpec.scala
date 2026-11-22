package scholarchain.storage

import scholarchain.core.config.RetryPolicyConfig
import scholarchain.storage.aws.AwsErrors
import scholarchain.storage.common.*

/** Retry policy: transient failures are retried with bounded exponential backoff and jitter;
  * permanent failures are not.
  */
class RetrySpec extends StorageSuite:
  private val policy = RetryPolicyConfig(maxAttempts = 5, baseDelayMs = 200, maxDelayMs = 1000)
  private val transient = (t: Throwable) => t.isInstanceOf[IllegalStateException]

  private def retrier(sleeper: Sleeper = new RecordingSleeper) =
    new Retrier(policy, transient, sleeper, MaxJitter)

  test("a transient failure followed by success is retried and the result is returned") {
    val sleeper = new RecordingSleeper
    val r = retrier(sleeper)
    var calls = 0
    val out = r.run("op") {
      calls += 1
      if calls < 3 then throw new IllegalStateException("throttled")
      "done"
    }
    out shouldBe Right("done")
    calls shouldBe 3
    sleeper.delays.toVector shouldBe Vector(200L, 400L) // base * 2^(n-1), jitter at its bound
    r.stats.snapshot shouldBe RetrySnapshot(
      operations = 1,
      retries = 2,
      exhausted = 0,
      permanentFailures = 0
    )
  }

  test("a permanent failure is not retried") {
    val sleeper = new RecordingSleeper
    val r = retrier(sleeper)
    var calls = 0
    val out = r.run("op") {
      calls += 1
      throw new IllegalArgumentException("bad request")
    }
    calls shouldBe 1
    sleeper.delays shouldBe empty
    out.left.toOption.get shouldBe a[StorageError.Permanent]
    r.stats.snapshot.permanentFailures shouldBe 1
    r.stats.snapshot.retries shouldBe 0
  }

  test("retries are bounded: the last transient failure is reported with the attempt count") {
    val sleeper = new RecordingSleeper
    val r = retrier(sleeper)
    var calls = 0
    val out = r.run("op") {
      calls += 1
      throw new IllegalStateException("still throttled")
    }
    calls shouldBe policy.maxAttempts
    out.left.toOption.get shouldBe a[StorageError.Transient]
    out.left.toOption.get.asInstanceOf[StorageError.Transient].attempts shouldBe 5
    // delays are capped at maxDelayMs: 200, 400, 800, then min(1000, 1600) = 1000
    sleeper.delays.toVector shouldBe Vector(200L, 400L, 800L, 1000L)
    r.stats.snapshot.exhausted shouldBe 1
  }

  test("jitter is a uniform value between zero and the bound, never above it") {
    val j = Jitter.random(Some(42L))
    val draws = (1 to 500).map(_ => j.upTo(300))
    draws.min should be >= 0L
    draws.max should be <= 300L
    draws.distinct.size should be > 50
    j.upTo(0) shouldBe 0L
  }

  test("a typed StorageException passes through without a retry") {
    val r = retrier()
    var calls = 0
    val out = r.run("op") {
      calls += 1
      throw new StorageException(StorageError.AlreadyExists("k"))
    }
    calls shouldBe 1
    out shouldBe Left(StorageError.AlreadyExists("k"))
  }

  test(
    "AWS classification: throttling, timeouts and 5xx are transient; validation and access are not"
  ) {
    import software.amazon.awssdk.awscore.exception.{AwsErrorDetails, AwsServiceException}
    def ex(status: Int, code: String): Throwable =
      AwsServiceException
        .builder()
        .statusCode(status)
        .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage("m").build())
        .message("m")
        .build()
    AwsErrors.isTransient(ex(429, "TooManyRequestsException")) shouldBe true
    AwsErrors.isTransient(ex(503, "ServiceUnavailableException")) shouldBe true
    AwsErrors.isTransient(ex(500, "InternalServerException")) shouldBe true
    AwsErrors.isTransient(ex(408, "RequestTimeoutException")) shouldBe true
    AwsErrors.isTransient(ex(400, "ProvisionedThroughputExceededException")) shouldBe true
    AwsErrors.isTransient(ex(400, "ValidationException")) shouldBe false
    AwsErrors.isTransient(ex(403, "AccessDeniedException")) shouldBe false
    AwsErrors.isTransient(ex(402, "ServiceQuotaExceededException")) shouldBe false
    AwsErrors.isTransient(ex(404, "NotFoundException")) shouldBe false
    AwsErrors.isTransient(
      software.amazon.awssdk.core.exception.SdkClientException
        .builder()
        .message("io")
        .cause(new java.io.IOException("reset"))
        .build()
    ) shouldBe true
    AwsErrors.isTransient(
      software.amazon.awssdk.core.exception.SdkClientException
        .builder()
        .message("configuration problem")
        .build()
    ) shouldBe false
  }
