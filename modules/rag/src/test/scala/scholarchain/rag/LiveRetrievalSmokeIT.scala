package scholarchain.rag

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.mutable
import scholarchain.core.model.*
import scholarchain.rag.common.Hashing
import scholarchain.rag.embed.*
import scholarchain.rag.ingest.*
import scholarchain.rag.render.PaperInput
import scholarchain.storage.StorageSettings
import scholarchain.storage.aws.*
import scholarchain.storage.common.Retrier
import scholarchain.storage.objects.*
import scholarchain.storage.vectors.{DocumentStore, IndexDefinition, VectorFilter, VectorQuery}
import scholarchain.tags.AwsIntegration
import upickle.default.*
import RagFixtures.*

/** Live smoke test: real Bedrock embeddings and real S3 Vectors, through the same entrypoint as
  * production (`IngestMain`). It writes a small fixture (five documents, one per index plus a
  * second researcher) to the configured indexes, so it runs only behind the explicit AWS profile:
  * SCHOLARCHAIN_AWS_IT=true, the variable named by SCHOLARCHAIN_IT_AWS_PROFILE_VARIABLE holds a
  * profile name, and the full configuration (storage, ingestion, rag and embedding model
  * variables) is in the environment. Cancelled, never passed, when any condition is missing.
  * `sbt check` never runs it (AwsIntegration tag). Not run in this session.
  */
class LiveRetrievalSmokeIT extends RagSuite:
  private val W1 = (2010, 2013)

  private def profile: Option[String] =
    sys.env.get("SCHOLARCHAIN_IT_AWS_PROFILE_VARIABLE").flatMap(sys.env.get).filter(_.trim.nonEmpty)

  private def skipReason: Option[String] =
    if !sys.env.get("SCHOLARCHAIN_AWS_IT").contains("true") then
      Some("SCHOLARCHAIN_AWS_IT is not 'true'")
    else if profile.isEmpty then Some("no explicit AWS profile")
    else
      StorageSettings
        .load(sys.env)
        .left
        .toOption
        .orElse(scholarchain.rag.provider.ModelProviders.load(sys.env).left.toOption)
        .map(e => s"configuration: ${e.message}")

  test("ingest, then query: top-1, filters and the textS3Uri round trip", AwsIntegration) {
    skipReason.foreach(r => cancel(r))
    val s = StorageSettings.load(sys.env).toOption.get
    val names = s.vectorIndexNames
    val facts = Vector(
      fact(SemanticSubjectType.RESEARCHER, researcher(1), W1, Some("Ada Example")),
      fact(SemanticSubjectType.RESEARCHER, researcher(2), W1, Some("Bo Sample")),
      fact(SemanticSubjectType.COMMUNITY, researcher(1), W1, Some("Graph community")),
      fact(SemanticSubjectType.TOPIC, topic(1), W1, Some("Graph analytics"))
    )
    val paths = Vector(path(1, 4, Vector(2), W1))
    val papers = Vector(
      PaperInput(workRecord(1, "Scalable graph mining", Some(2011)), Some("VLDB"), None)
    )
    val dir = Files.createTempDirectory("rag-live-")
    def lines[A: Writer](name: String, xs: Seq[A]): Unit =
      Files.writeString(dir.resolve(name), xs.map(write(_)).mkString("", "\n", "\n"))
    locally {
      import IngestionInputs.given
      lines(IngestionInputs.FactsFile, facts)
      lines(IngestionInputs.PathsFile, paths)
      lines(IngestionInputs.PapersFile, papers)
    }
    val env = sys.env + (s.runtime.endpoints.awsProfileVariable -> profile.get)
    val out = mutable.ArrayBuffer.empty[String]
    val err = mutable.ArrayBuffer.empty[String]
    val code = IngestMain.execute(
      Seq(
        "--input-dir",
        dir.toString,
        "--limit",
        "10",
        "--run-id",
        runId,
        "--snapshot",
        snapshot
      ),
      env,
      out += _,
      err += _
    )
    withClue(err.mkString("\n"))(code shouldBe 0)

    val defs = IndexDefinition.fromSettings(s)
    val spec = good(EmbeddingModelSpec.load(env, defs.head.dimension))
    val embedder = BedrockEmbedder.live(s.awsRegion, profile, spec)
    val clients = AwsClients.forProfile(s, profile.get)
    try
      val retrier = new Retrier(s.runtime.retry, AwsErrors.isTransient)
      val index = new S3VectorsIndex(clients.vectors, s.buckets.vectorBucket, retrier, defs)
      def query(name: String, text: String, filter: Option[VectorFilter] = None) =
        val q = embedder.embed(Seq(text)).head.toVector
        index.query(name, VectorQuery(q, 5, filter)).fold(e => fail(e.message), identity)

      val top = query(names.researchers, s"Researcher profile ${researcher(1)} Ada Example")
      top.head.metadata.get.entityId shouldBe researcher(1)

      val byEntity = query(
        names.researchers,
        "Researcher profile",
        Some(VectorFilter.Eq("entityId", ujson.Str(researcher(2))))
      )
      byEntity.map(_.metadata.get.entityId).distinct shouldBe Vector(researcher(2))

      val window = query(
        names.researchers,
        "Researcher profile",
        Some(
          VectorFilter.And(
            Vector(
              VectorFilter.Gte("windowStartYear", 2020),
              VectorFilter.Lte("windowEndYear", 2025)
            )
          )
        )
      )
      window shouldBe empty

      val pathHits = query(
        names.paths,
        "Verified collaboration path",
        Some(VectorFilter.Eq("entityId", ujson.Str(researcher(1))))
      )
      pathHits.size should be >= 1

      val objects = new S3ObjectStore(clients.s3, ingestLayout(s), retrier)
      val documents = new DocumentStore(objects, ingestLayout(s))
      val meta = top.head.metadata.get
      val ref = documents
        .ref(names.researchers, meta.documentType, meta.entityId, meta.textHash)
        .fold(e => fail(e.message), identity)
      documents.uri(ref) shouldBe meta.textS3Uri
      val doc = documents.read(ref, meta.textHash).fold(e => fail(e.message), identity)
      Hashing.textHash(doc.text) shouldBe meta.textHash
    finally clients.close()
  }

  private def ingestLayout(s: StorageSettings): ObjectLayout =
    ObjectLayout
      .fromConfig(s.buckets, s.runtime.s3Prefixes, ObjectArea.Rag)
      .fold(e => fail(e.message), identity)
