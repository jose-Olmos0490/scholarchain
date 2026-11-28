package scholarchain.rag.ingest

import java.nio.file.Paths
import scholarchain.core.config.{EmbeddingProvider, EmbeddingProviderConfig}
import scholarchain.rag.common.*
import scholarchain.rag.embed.*
import scholarchain.rag.provider.*
import scholarchain.storage.StorageSettings
import scholarchain.storage.aws.*
import scholarchain.storage.common.*
import scholarchain.storage.dynamo.DynamoPorts
import scholarchain.storage.objects.*
import scholarchain.storage.vectors.*

/** Ingestion entrypoint: render -> chunk -> storage ingestor, with the rag embedder.
  *
  * {{{
  * IngestMain --input-dir <dir> [--index <name>] [--limit <n>] [--dry-run] [--run-id <run:...>] [--snapshot <label>]
  * }}}
  * `--dry-run` reads only local files and prints counts and sample metadata: no AWS client is
  * built. A live run needs `--limit`, `--run-id`, `--snapshot` and the configuration environment.
  * An input directory that yields no record at all is an error (exit 2), dry run or not.
  *
  * Both model-provider sections (`llm.provider`, `embedding.provider`) are loaded and checked at
  * start-up, dry run or not. A live run embeds with the configured embedding provider; with Voyage
  * the key is read from `VOYAGE_API_KEY`.
  */
object IngestMain:
  val SampleCount = 3

  def main(args: Array[String]): Unit =
    val code = execute(args.toSeq, sys.env, println, System.err.println)
    if code != 0 then sys.exit(code)

  def execute(
      args: Seq[String],
      env: Map[String, String],
      out: String => Unit,
      err: String => Unit
  ): Int =
    val result =
      for
        o <- IngestionOptions.parse(args)
        dir <- o.inputDir.toRight(RagProblem("BAD_ARGUMENT", "--input-dir is required"))
        inputs <- IngestionInputs.readDir(Paths.get(dir)).flatMap(_.nonEmptyFrom(dir))
        rs <- RenderSettings.load(env)
        s <- StorageSettings.load(env).left.map(e => RagProblem("MISCONFIGURED", e.message))
        providers <- ModelProviders
          .load(env)
          .left
          .map(e => RagProblem("MISCONFIGURED", e.message))
        defs = IndexDefinition.fromSettings(s)
        runner = new IngestionRunner(
          new RenderPipeline(s.vectorIndexNames, rs),
          defs,
          new InMemoryIndexBindings,
          SampleCount
        )
        outcome <-
          if o.dryRun then
            runner.run(
              o,
              inputs,
              Left(RagProblem("INTERNAL", "dry run never starts a run")),
              Left(RagProblem("INTERNAL", "dry run never embeds")),
              _ => throw new IllegalStateException("dry run never builds an ingestor")
            )
          else liveRun(o, inputs, env, s, providers.embedding, defs, runner)
      yield outcome
    result match
      case Right(o) => out(o.text); 0
      case Left(p) => err(p.message); 2

  private def liveRun(
      o: IngestionOptions,
      inputs: IngestionInputs,
      env: Map[String, String],
      s: StorageSettings,
      embedding: EmbeddingProviderConfig,
      defs: Vector[IndexDefinition],
      runner: IngestionRunner
  ): RResult[IngestionOutcome] =
    for
      runId <- o.runId.toRight(RagProblem("BAD_ARGUMENT", "--run-id is required for a live run"))
      snap <- o.snapshot.toRight(RagProblem("BAD_ARGUMENT", "--snapshot is required"))
      ing <- IngestionSettings.load(s, env).left.map(e => RagProblem("MISCONFIGURED", e.message))
      model <- ModelProviders.embeddingSpec(embedding, env)
      voyageKey = env.getOrElse(ModelProviders.VoyageKeyVariable, "")
      _ <- Either.cond(
        embedding.provider != EmbeddingProvider.Voyage || voyageKey.trim.nonEmpty,
        (),
        RagProblem(
          "MISCONFIGURED",
          s"${ModelProviders.VoyageKeyVariable} is required for a live run with Voyage"
        )
      )
      layout <- ObjectLayout
        .fromConfig(s.buckets, s.runtime.s3Prefixes, ObjectArea.Rag)
        .left
        .map(e => RagProblem("MISCONFIGURED", e.message))
      profile = env.get(s.runtime.endpoints.awsProfileVariable).map(_.trim).filter(_.nonEmpty)
      embedder <- ModelProviders.embedder(
        embedding,
        model,
        EmbeddingPurpose.Ingest,
        RetryDelays(s.runtime.retry.baseDelayMs.toLong, s.runtime.retry.maxDelayMs.toLong),
        () => voyageKey,
        s.awsRegion,
        profile
      )
      outcome <- withClients(s, profile) { clients =>
        val retrier = new Retrier(s.runtime.retry, AwsErrors.isTransient)
        // the ingestor owns retries: the index adapter makes a single attempt
        val indexRetrier = new Retrier(s.runtime.retry.copy(maxAttempts = 1), AwsErrors.isTransient)
        val objects = new S3ObjectStore(clients.s3, layout, retrier)
        val ports = DynamoPorts(
          new DynamoItemStore(clients.dynamo, retrier),
          s.runtime.dynamoTables,
          s.runtime.querySession.ttlSeconds
        )
        val index = new S3VectorsIndex(clients.vectors, s.buckets.vectorBucket, indexRetrier, defs)
        val time = new MonotonicClock with Sleeper:
          def nanos: Long = java.lang.System.nanoTime()
          def sleep(ms: Long): Unit = Sleeper.Real.sleep(ms)
        val result = runner.run(
          o,
          inputs,
          Right(IngestionRun(runId, snap)),
          Right(embedder),
          fn =>
            new VectorIngestor(
              index,
              defs,
              new DocumentStore(objects, layout),
              new ManifestStore(objects, layout),
              objects,
              layout,
              ports.runLedger,
              ports.artifacts,
              new InMemoryRejectSink,
              fn,
              ing,
              new RateLimiter(ing.rateLimitPerSecond, time, time),
              time,
              Jitter.random()
            )
        )
        // token usage is reported for the manually tracked provider cost
        embedder match
          case v: VoyageEmbedder =>
            result.map(r => r.copy(text = s"${r.text}voyage tokens=${v.tokensUsed}\n"))
          case _ => result
      }
    yield outcome

  private def withClients[A](s: StorageSettings, profile: Option[String])(
      f: AwsClients => A
  ): A =
    val clients = profile.fold(AwsClients.forRuntime(s))(AwsClients.forProfile(s, _))
    try f(clients)
    finally clients.close()
