package scholarchain.rag

import java.nio.file.{Files, Paths}
import scholarchain.rag.embed.*
import scholarchain.tags.AwsIntegration
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import RagFixtures.*

/** Live Bedrock check behind the explicit AWS profile: SCHOLARCHAIN_AWS_IT=true, the variable
  * named by SCHOLARCHAIN_IT_AWS_PROFILE_VARIABLE holds a profile name, and the embedding model
  * settings are in the environment. The default credential chain is never used. Cancelled (not
  * passed) when any condition is missing. Bounded: five requests, one per index document type.
  * The S3 Vectors half of the smoke fixture waits for the `rag` -> `storage` dependency.
  */
class BedrockEmbedderIT extends RagSuite:
  private def skipReason: Option[String] =
    if !sys.env.get("SCHOLARCHAIN_AWS_IT").contains("true") then
      Some("SCHOLARCHAIN_AWS_IT is not 'true'")
    else
      val profile = sys.env
        .get("SCHOLARCHAIN_IT_AWS_PROFILE_VARIABLE")
        .flatMap(sys.env.get)
        .filter(_.trim.nonEmpty)
      if profile.isEmpty then
        Some("no explicit AWS profile (SCHOLARCHAIN_IT_AWS_PROFILE_VARIABLE names none)")
      else if sys.env.get("AWS_REGION").forall(_.trim.isEmpty) then Some("AWS_REGION is not set")
      else if sys.env
          .get("SCHOLARCHAIN_VECTOR_INDEX_RESEARCHERS_DIMENSION")
          .flatMap(_.toIntOption)
          .isEmpty
      then Some("SCHOLARCHAIN_VECTOR_INDEX_RESEARCHERS_DIMENSION is not set")
      else None

  test("Bedrock embeds one document per template; latency samples are recorded", AwsIntegration) {
    skipReason.foreach(r => cancel(r))
    val spec = good(
      EmbeddingModelSpec.load(
        sys.env,
        sys.env("SCHOLARCHAIN_VECTOR_INDEX_RESEARCHERS_DIMENSION").toInt
      )
    )
    val profile = sys.env(sys.env("SCHOLARCHAIN_IT_AWS_PROFILE_VARIABLE"))
    val client = BedrockRuntimeClient
      .builder()
      .region(Region.of(sys.env("AWS_REGION")))
      .credentialsProvider(ProfileCredentialsProvider.create(profile))
      .build()
    try
      val embedder = new BedrockEmbedder(client, spec)
      val texts = Seq(
        "Researcher profile Researcher ID: " + researcher(1),
        "Collaboration community summary " + researcher(3),
        "Paper Scalable graph mining",
        "Verified collaboration path " + researcher(1) + " -> " + researcher(4),
        "Topic summary Graph analytics"
      )
      val samples = texts.map { t =>
        val t0 = System.nanoTime()
        val v = embedder.embed(Seq(t)).head
        v.length shouldBe spec.dimension
        (System.nanoTime() - t0) / 1_000_000L
      }.sorted
      val line =
        s"bedrock embed latency ms (n=${samples.size}): min=${samples.head} median=${samples(samples.size / 2)} max=${samples.last}"
      println(line)
      val out = Paths.get("modules/rag/target/rag-latency.txt")
      Files.createDirectories(out.getParent)
      Files.writeString(out, line + "\n")
    finally client.close()
  }
