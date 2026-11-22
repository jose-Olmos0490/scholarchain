package scholarchain.rag.embed

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest
import scholarchain.rag.common.*

/** Thrown when an embedding does not have the index dimension; fails the whole batch. */
final class EmbeddingDimensionException(message: String) extends RuntimeException(message)

/** Turns texts into vectors for one model. */
trait Embedder:
  def spec: EmbeddingModelSpec

  /** One vector per text, same order. */
  def embed(texts: Seq[String]): Seq[Array[Float]]

object Embedder:
  def l2Normalize(v: Array[Float]): Array[Float] =
    val norm = math.sqrt(v.foldLeft(0.0)((a, x) => a + x.toDouble * x))
    if norm == 0.0 then v else v.map(x => (x / norm).toFloat)

  /** The function storage injects (`Seq[String] => Seq[Array[Float]]`). Fails fast when the
    * embedder's configured dimension differs from the index dimension, and again, per vector,
    * if the model returns a different length.
    */
  def forIndex(
      embedder: Embedder,
      indexName: String,
      indexDimension: Int
  ): RResult[Seq[String] => Seq[Array[Float]]] =
    if embedder.spec.dimension != indexDimension then
      Left(
        RagProblem(
          "DIMENSION_MISMATCH",
          s"embedding model ${embedder.spec.modelId}@${embedder.spec.version} has dimension ${embedder.spec.dimension}, index $indexName has $indexDimension"
        )
      )
    else
      Right { texts =>
        val out = embedder.embed(texts)
        if out.size != texts.size then
          throw new EmbeddingDimensionException(s"${out.size} vectors for ${texts.size} texts")
        out.zipWithIndex.foreach { (v, i) =>
          if v.length != indexDimension then
            throw new EmbeddingDimensionException(
              s"text $i: model returned ${v.length} values, index $indexName has $indexDimension"
            )
        }
        out
      }

/** Deterministic embedder for tests: signed feature hashing of lower-case alphanumeric tokens
  * (log-scaled term frequency), L2-normalized when the spec says so. Texts that share tokens
  * (for example a researcher ID) get a high cosine similarity. It is not a language model.
  */
final class FakeEmbedder(val spec: EmbeddingModelSpec) extends Embedder:
  def embed(texts: Seq[String]): Seq[Array[Float]] = texts.map(one)

  private def one(text: String): Array[Float] =
    val counts = text.toLowerCase
      .split("[^\\p{L}\\p{N}]+")
      .filter(_.nonEmpty)
      .groupMapReduce(identity)(_ => 1)(_ + _)
    val v = new Array[Double](spec.dimension)
    counts.foreach { (tok, n) =>
      val d = MessageDigest.getInstance("SHA-256").digest(tok.getBytes(StandardCharsets.UTF_8))
      val bucket =
        java.lang.Math.floorMod(java.nio.ByteBuffer.wrap(d).getLong, spec.dimension.toLong).toInt
      val sign = if (d(8) & 1) == 0 then 1.0 else -1.0
      v(bucket) += sign * (1.0 + math.log(n.toDouble))
    }
    val f = v.map(_.toFloat)
    val out = if f.forall(_ == 0f) then
      Array.tabulate(spec.dimension)(i => if i == 0 then 1f else 0f)
    else f
    if spec.normalize then Embedder.l2Normalize(out) else out

/** Amazon Bedrock embedder for models that take `{"inputText", "dimensions", "normalize"}` and
  * answer `{"embedding": [...]}` (the Titan Text Embeddings V2 request shape). One request per
  * text. The model ID, version, dimension and normalization come from the injected spec; this
  * class holds no default model or Region. Never constructed by `sbt check`; exercised only by the
  * AWS-profile-gated integration test. The request shape is not verified against a live model yet.
  */
final class BedrockEmbedder(client: BedrockRuntimeClient, val spec: EmbeddingModelSpec)
    extends Embedder:
  def embed(texts: Seq[String]): Seq[Array[Float]] = texts.map(one)

  private def one(text: String): Array[Float] =
    val body = ujson.Obj(
      "inputText" -> text,
      "dimensions" -> spec.dimension,
      "normalize" -> spec.normalize
    )
    val response = client.invokeModel(
      InvokeModelRequest
        .builder()
        .modelId(spec.modelId)
        .contentType("application/json")
        .accept("application/json")
        .body(SdkBytes.fromString(body.render(), StandardCharsets.UTF_8))
        .build()
    )
    val json = ujson.read(response.body().asString(StandardCharsets.UTF_8))
    val v = json("embedding").arr.map(_.num.toFloat).toArray
    if v.length != spec.dimension then
      throw new EmbeddingDimensionException(
        s"${spec.modelId} returned ${v.length} values, configured dimension is ${spec.dimension}"
      )
    v

object BedrockEmbedder:
  /** A client for one Region, with an explicit named profile when given (otherwise the default
    * credential chain, i.e. the task role). Kept here so the ingestion entrypoint needs no SDK
    * import. The Region comes from configuration; nothing is defaulted.
    */
  def live(region: String, profile: Option[String], spec: EmbeddingModelSpec): Embedder =
    val b = BedrockRuntimeClient.builder().region(software.amazon.awssdk.regions.Region.of(region))
    profile.foreach(p =>
      b.credentialsProvider(
        software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider.create(p)
      )
    )
    new BedrockEmbedder(b.build(), spec)
