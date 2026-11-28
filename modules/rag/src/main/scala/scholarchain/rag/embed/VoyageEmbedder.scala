package scholarchain.rag.embed

import java.util.concurrent.atomic.AtomicLong
import scala.util.control.NonFatal
import scholarchain.core.config.VoyageConfig
import scholarchain.rag.common.BoundedJson
import scholarchain.rag.llm.Backoff
import scholarchain.rag.provider.{ProviderFailureKind, ProviderHttp, RetryDelays}

/** What the vectors are for; sent as Voyage `input_type`. */
enum VoyageInputType(val wire: String):
  case Document extends VoyageInputType("document")
  case Query extends VoyageInputType("query")

/** A Voyage call that produced no usable vectors. The message holds only fixed text and numbers:
  * never a response body, an input text or the key. No cause is attached.
  */
final class EmbeddingProviderException(val kind: ProviderFailureKind, message: String)
    extends RuntimeException(message)

/** The [[Embedder]] over the Voyage embeddings API (`POST <baseUrl>/v1/embeddings`).
  *
  * Texts are sent in order, at most `config.batchSize` per request, with `output_dimension` from
  * configuration and `truncation: false`, so an over-long input is an error and nothing is cut
  * silently. Vectors are placed by the `index` the service returns, checked against the
  * configured dimension and L2-normalized when the model settings say so. Any failed batch fails
  * the whole call.
  *
  * Retries: 429, 529, deadlines, other 5xx and connection failures, at most `config.maxRetries`
  * times per batch. Another 4xx (for example a batch over the service's token limit) stops at
  * once.
  */
final class VoyageEmbedder(
    config: VoyageConfig,
    val spec: EmbeddingModelSpec,
    val inputType: VoyageInputType,
    apiKey: () => String,
    delays: RetryDelays,
    backoff: Backoff = Backoff.Real
) extends Embedder:
  require(spec.modelId == config.modelId, "the model must be the configured Voyage model")
  require(
    spec.dimension == config.outputDimension,
    "the dimension must be the configured Voyage output dimension"
  )

  private val http = new ProviderHttp(
    config.connectTimeoutMs,
    config.requestTimeoutMs,
    config.maxRetries,
    delays,
    backoff
  )
  private val url = s"${config.baseUrl}/v1/embeddings"
  private val tokens = new AtomicLong

  /** `usage.total_tokens` summed over the answered requests of this instance (cost tracking). */
  def tokensUsed: Long = tokens.get

  def embed(texts: Seq[String]): Seq[Array[Float]] =
    texts.grouped(config.batchSize).flatMap(batch).toVector

  private def batch(texts: Seq[String]): Vector[Array[Float]] =
    val body = ujson
      .Obj(
        "input" -> ujson.Arr.from(texts.map(ujson.Str(_))),
        "model" -> config.modelId,
        "input_type" -> inputType.wire,
        "output_dimension" -> config.outputDimension,
        "truncation" -> false
      )
      .render()
    http.postJson(url, apiKey, key => Seq("authorization" -> s"Bearer $key"), body) match
      case Left(f) =>
        val hint =
          if f.status.exists(s => s == 400 || s == 413) then
            s" (batch of ${texts.size} texts, ${texts.map(_.length).sum} characters; if the " +
              "request is over a service limit, lower SCHOLARCHAIN_VOYAGE_BATCH_SIZE)"
          else ""
        throw new EmbeddingProviderException(f.kind, s"Voyage API: ${f.detail}$hint")
      case Right(response) => vectors(response, texts.size)

  private def unreadable(): Nothing =
    throw new EmbeddingProviderException(
      ProviderFailureKind.Transient,
      "Voyage API: the response could not be read"
    )

  private def vectors(response: String, expected: Int): Vector[Array[Float]] =
    val (indexed, used) =
      try
        val json = BoundedJson.read(response).obj
        val data = json("data").arr.toVector.map { item =>
          // kept as a number: a fractional or oversize index must not become a position
          item("index").num -> item("embedding").arr.map(_.num.toFloat).toArray
        }
        val total =
          json.get("usage").flatMap(_.objOpt).flatMap(_.get("total_tokens")).flatMap(_.numOpt)
        (data.sortBy(_._1), total.fold(0L)(_.toLong))
      catch case NonFatal(_) => unreadable()
    tokens.addAndGet(math.max(used, 0L))
    if indexed.map(_._1) != Vector.tabulate(expected)(_.toDouble) then
      throw new EmbeddingDimensionException(
        s"Voyage returned ${indexed.size} vectors for $expected texts, or wrong indexes"
      )
    indexed.map { (_, v) =>
      if v.length != spec.dimension then
        throw new EmbeddingDimensionException(
          s"${spec.modelId} returned ${v.length} values, configured dimension is ${spec.dimension}"
        )
      if v.exists(x => x.isNaN || x.isInfinite) then unreadable()
      if spec.normalize then Embedder.l2Normalize(v) else v
    }
