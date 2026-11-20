package scholarchain.core.ids

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scholarchain.core.model.{EdgeType, EvidenceType, RunInput, RunManifest, SourceType}
import upickle.default.writeJs

/** Authoritative namespaces for work IDs. */
enum WorkNamespace(val segment: String):
  case Dblp extends WorkNamespace("dblp")
  case Doi extends WorkNamespace("doi")
  case OpenAlex extends WorkNamespace("openalex")
  case Arxiv extends WorkNamespace("arxiv")

/** Validated external namespaces for researcher IDs. */
enum ResearcherNamespace(val segment: String):
  case Orcid extends ResearcherNamespace("orcid")
  case DblpPid extends ResearcherNamespace("dblp")
  case OpenAlex extends ResearcherNamespace("openalex")

/** The identifier a researcher ID is built from, highest precedence first. */
enum ResearcherIdBasis(val namespaceLabel: String):
  case Orcid extends ResearcherIdBasis("orcid")
  case DblpPid extends ResearcherIdBasis("dblp")
  case OpenAlex extends ResearcherIdBasis("openalex")
  case ScholarChain extends ResearcherIdBasis("sc-researcher-v<N>")

enum VenueNamespace(val segment: String):
  case Dblp extends VenueNamespace("dblp")
  case OpenAlex extends VenueNamespace("openalex")
  case Issn extends VenueNamespace("issn")

enum InstitutionNamespace(val segment: String):
  case Ror extends InstitutionNamespace("ror")
  case OpenAlex extends InstitutionNamespace("openalex")

enum TopicNamespace(val segment: String):
  case OpenAlex extends TopicNamespace("openalex")

/** ScholarChain stable-ID formulas (formula version `v1`).
  *
  * Every ID is `kind[:namespace]:hex(SHA-256(preimage))` with
  * {{{
  *   preimage = "scholarchain-id/v1/" + kind + "\n" + field_1 + field_2 + ... + field_n
  *   field_i  = utf8ByteLength(value_i) + ":" + value_i
  * }}}
  * Length-prefixing makes the encoding injective, and the kind in the preimage separates kinds.
  * Lists that are logically sets are sorted and de-duplicated before encoding; a list is encoded
  * as its element count followed by its elements. No formula reads a clock.
  *
  * Every function is total: a `null` argument (string, enum, option, list or record) yields a
  * `Left` with the parameter name; nothing throws.
  */
object StableIds:
  val FormulaVersion = "v1"

  private def sha256Hex(bytes: Array[Byte]): String =
    val d = MessageDigest.getInstance("SHA-256").digest(bytes)
    val sb = new StringBuilder(64)
    d.foreach(b => sb.append(f"${b & 0xff}%02x"))
    sb.toString

  private def field(value: String): String =
    s"${value.getBytes(StandardCharsets.UTF_8).length}:$value"

  private def listField(values: Seq[String]): String =
    field(values.size.toString) + values.map(field).mkString

  private[ids] def digest(kind: IdKind, fields: Seq[String]): String =
    val preimage = s"scholarchain-id/$FormulaVersion/${kind.prefix}\n" + fields.mkString
    sha256Hex(preimage.getBytes(StandardCharsets.UTF_8))

  private def mk(kind: IdKind, namespace: Option[String], fields: Seq[String]): String =
    StableId(kind, namespace, digest(kind, fields)).render

  private def requireNotNull[A](name: String, value: A): Either[String, A] =
    if value == null then Left(s"$name must not be null") else Right(value)

  private def requireIds(
      name: String,
      values: Seq[String],
      kinds: Set[IdKind]
  ): Either[String, Vector[String]] =
    requireNotNull(name, values).flatMap(
      _.foldLeft[Either[String, Vector[String]]](Right(Vector.empty)) { (acc, id) =>
        acc.flatMap(v =>
          (if kinds.isEmpty then requireAnyId(id) else requireId(id, kinds)).map(v :+ _)
        )
      }
    )

  private def requireId(value: String, kinds: Set[IdKind]): Either[String, String] =
    StableId.parseAs(value, kinds).map(_ => value)

  private def requireAnyId(value: String): Either[String, String] =
    StableId.parse(value).map(_ => value)

  private def requireNonBlank(name: String, value: String): Either[String, String] =
    if value == null || value.trim.isEmpty then Left(s"$name must not be blank") else Right(value)

  private def requireNonNegative(name: String, value: Int): Either[String, Int] =
    if value < 0 then Left(s"$name must be >= 0, got $value") else Right(value)

  private def requireWindow(start: Int, end: Int): Either[String, Unit] =
    if start > end then Left(s"window start year $start exceeds end year $end") else Right(())

  private def requireTextHash(value: String): Either[String, String] =
    if isTextHash(value) then Right(value)
    else Left(s"text hash '$value' must be sha256:<64 lower-case hex>")

  private val Sha256Pattern = "sha256:[0-9a-f]{64}".r

  // ---------------------------------------------------------------------------
  // Text hash
  // ---------------------------------------------------------------------------

  /** `sha256:<hex>` of the UTF-8 bytes of `text`. */
  def textHash(text: String): Either[String, String] =
    requireNotNull("text", text).map(t => "sha256:" + sha256Hex(t.getBytes(StandardCharsets.UTF_8)))

  /** True for `sha256:<64 lower-case hex>` (the format of text hashes and run-input checksums). */
  def isTextHash(value: String): Boolean = value != null && Sha256Pattern.matches(value)

  // ---------------------------------------------------------------------------
  // Entity IDs: namespace plus authoritative ID
  // ---------------------------------------------------------------------------

  /** `work:<ns>:sha256(ns, normalizedAuthoritativeId)`. */
  def workId(namespace: WorkNamespace, authoritativeId: String): Either[String, String] =
    val normalized = requireNotNull("namespace", namespace).flatMap {
      case WorkNamespace.Dblp => ExternalIds.normalizeDblpKey(authoritativeId)
      case WorkNamespace.Doi => ExternalIds.normalizeDoi(authoritativeId)
      case WorkNamespace.OpenAlex => ExternalIds.normalizeOpenAlex(authoritativeId, 'W')
      case WorkNamespace.Arxiv => ExternalIds.normalizeArxiv(authoritativeId)
    }
    normalized.map(n =>
      mk(IdKind.Work, Some(namespace.segment), Seq(field(namespace.segment), field(n)))
    )

  /** Researcher ID from a validated external ID: `researcher:<ns>:sha256(ns, normalizedId)` with
    * `ns` one of `orcid`, `dblp` (dblp person pid) or `openalex`.
    */
  def researcherIdFromExternal(
      namespace: ResearcherNamespace,
      externalId: String
  ): Either[String, String] =
    val normalized = requireNotNull("namespace", namespace).flatMap {
      case ResearcherNamespace.Orcid => ExternalIds.normalizeOrcid(externalId)
      case ResearcherNamespace.DblpPid => ExternalIds.normalizeDblpPid(externalId)
      case ResearcherNamespace.OpenAlex => ExternalIds.normalizeOpenAlex(externalId, 'A')
    }
    normalized.map(n =>
      mk(IdKind.Researcher, Some(namespace.segment), Seq(field(namespace.segment), field(n)))
    )

  /** Namespace token of a versioned ScholarChain identity ID: `sc-researcher-<identityVersion>`,
    * for example `sc-researcher-v1`.
    */
  def scholarChainResearcherNamespace(identityVersion: String): String =
    s"${IdKind.Researcher.versionedNamespacePrefix.get}$identityVersion"

  /** Versioned ScholarChain identity ID, used when no external ID is validated:
    * `researcher:sc-researcher-<identityVersion>:sha256(ns, anchorKey)` with
    * `ns = "sc-researcher-<identityVersion>"`, for example `researcher:sc-researcher-v1:<64 hex>`.
    *
    * `anchorKey` is the deterministic identity anchor chosen by the entity-resolution policy (for
    * example a dblp author key with homonym suffix).
    */
  def researcherIdScholarChain(identityVersion: String, anchorKey: String): Either[String, String] =
    for
      _ <-
        if identityVersion != null && IdKind.VersionTag.matches(identityVersion) then Right(())
        else Left(s"identity version '$identityVersion' must match v<N>")
      a <- requireNonBlank("anchorKey", anchorKey)
    yield
      val ns = scholarChainResearcherNamespace(identityVersion)
      mk(IdKind.Researcher, Some(ns), Seq(field(ns), field(a)))

  /** Which identifier a researcher ID is built from, by the frozen precedence (pure function):
    * validated ORCID iD > validated dblp person pid > validated OpenAlex author ID > versioned
    * ScholarChain identity. The first present identifier wins; absent ones fall through. A `null`
    * option counts as absent here; [[researcherId]] rejects it.
    */
  def researcherIdBasis(
      validatedOrcid: Option[String],
      validatedDblpPid: Option[String],
      validatedOpenAlexAuthorId: Option[String]
  ): ResearcherIdBasis =
    def present(o: Option[String]) = o != null && o.isDefined
    if present(validatedOrcid) then ResearcherIdBasis.Orcid
    else if present(validatedDblpPid) then ResearcherIdBasis.DblpPid
    else if present(validatedOpenAlexAuthorId) then ResearcherIdBasis.OpenAlex
    else ResearcherIdBasis.ScholarChain

  /** Researcher ID with the frozen precedence (human decision 3a, 2026-11-20):
    *
    *   1. validated ORCID iD -> `researcher:orcid:...`
    *   2. validated dblp person pid -> `researcher:dblp:...`
    *   3. validated OpenAlex author ID -> `researcher:openalex:...`
    *   4. versioned ScholarChain identity -> `researcher:sc-researcher-v<N>:...`
    *
    * Callers pass only identifiers that entity resolution has validated. A present identifier that
    * is malformed is an error (`Left`); it never silently falls through to a lower level, because
    * that would change the researcher's identity without a signal.
    */
  def researcherId(
      validatedOrcid: Option[String],
      validatedDblpPid: Option[String],
      validatedOpenAlexAuthorId: Option[String],
      identityVersion: String,
      anchorKey: String
  ): Either[String, String] =
    if validatedOrcid == null then Left("validatedOrcid must not be null (use None)")
    else if validatedDblpPid == null then Left("validatedDblpPid must not be null (use None)")
    else if validatedOpenAlexAuthorId == null then
      Left("validatedOpenAlexAuthorId must not be null (use None)")
    else
      researcherIdBasis(validatedOrcid, validatedDblpPid, validatedOpenAlexAuthorId) match
        case ResearcherIdBasis.Orcid =>
          researcherIdFromExternal(ResearcherNamespace.Orcid, validatedOrcid.get)
        case ResearcherIdBasis.DblpPid =>
          researcherIdFromExternal(ResearcherNamespace.DblpPid, validatedDblpPid.get)
        case ResearcherIdBasis.OpenAlex =>
          researcherIdFromExternal(ResearcherNamespace.OpenAlex, validatedOpenAlexAuthorId.get)
        case ResearcherIdBasis.ScholarChain => researcherIdScholarChain(identityVersion, anchorKey)

  def venueId(namespace: VenueNamespace, authoritativeId: String): Either[String, String] =
    val normalized = requireNotNull("namespace", namespace).flatMap {
      case VenueNamespace.Dblp => ExternalIds.normalizeDblpVenueKey(authoritativeId)
      case VenueNamespace.OpenAlex => ExternalIds.normalizeOpenAlex(authoritativeId, 'S')
      case VenueNamespace.Issn => ExternalIds.normalizeIssn(authoritativeId)
    }
    normalized.map(n =>
      mk(IdKind.Venue, Some(namespace.segment), Seq(field(namespace.segment), field(n)))
    )

  def institutionId(
      namespace: InstitutionNamespace,
      authoritativeId: String
  ): Either[String, String] =
    val normalized = requireNotNull("namespace", namespace).flatMap {
      case InstitutionNamespace.Ror => ExternalIds.normalizeRor(authoritativeId)
      case InstitutionNamespace.OpenAlex => ExternalIds.normalizeOpenAlex(authoritativeId, 'I')
    }
    normalized.map(n =>
      mk(IdKind.Institution, Some(namespace.segment), Seq(field(namespace.segment), field(n)))
    )

  def topicId(namespace: TopicNamespace, authoritativeId: String): Either[String, String] =
    val normalized = requireNotNull("namespace", namespace).flatMap {
      case TopicNamespace.OpenAlex => ExternalIds.normalizeOpenAlex(authoritativeId, 'T')
    }
    normalized.map(n =>
      mk(IdKind.Topic, Some(namespace.segment), Seq(field(namespace.segment), field(n)))
    )

  // ---------------------------------------------------------------------------
  // Derived record IDs
  // ---------------------------------------------------------------------------

  /** `mention:sha256(workId, position)`; position is 1-based. */
  def mentionId(workId: String, position: Int): Either[String, String] =
    for
      w <- requireId(workId, Set(IdKind.Work))
      _ <- if position >= 1 then Right(()) else Left(s"position must be >= 1, got $position")
    yield mk(IdKind.AuthorMention, None, Seq(field(w), field(position.toString)))

  /** `authorship:sha256(workId, mentionId, researcherId)`. */
  def authorshipId(
      workId: String,
      mentionId: String,
      researcherId: String
  ): Either[String, String] =
    for
      w <- requireId(workId, Set(IdKind.Work))
      m <- requireId(mentionId, Set(IdKind.AuthorMention))
      r <- requireId(researcherId, Set(IdKind.Researcher))
    yield mk(IdKind.Authorship, None, Seq(field(w), field(m), field(r)))

  /** `pair-candidate:sha256(workId, a, b)` where `(a, b)` is the canonical mention pair. Input
    * order of the two mentions does not matter. Self-pairs are rejected.
    */
  def pairCandidateId(workId: String, mentionX: String, mentionY: String): Either[String, String] =
    for
      w <- requireId(workId, Set(IdKind.Work))
      x <- requireId(mentionX, Set(IdKind.AuthorMention))
      y <- requireId(mentionY, Set(IdKind.AuthorMention))
      p <- CanonicalPair.of(x, y)
    yield mk(IdKind.PairCandidate, None, Seq(field(w), field(p.a), field(p.b)))

  /** `coauthor-edge:sha256(a, b, windowStartYear, windowEndYear, policyVersion)` where `(a, b)` is
    * the canonical researcher pair. Input order does not matter. Self-pairs and inverted windows
    * are rejected.
    */
  def coauthorEdgeId(
      researcherX: String,
      researcherY: String,
      windowStartYear: Int,
      windowEndYear: Int,
      policyVersion: String
  ): Either[String, String] =
    for
      x <- requireId(researcherX, Set(IdKind.Researcher))
      y <- requireId(researcherY, Set(IdKind.Researcher))
      p <- CanonicalPair.of(x, y)
      _ <- requireWindow(windowStartYear, windowEndYear)
      v <- requireNonBlank("policyVersion", policyVersion)
    yield mk(
      IdKind.CoauthorEdge,
      None,
      Seq(
        field(p.a),
        field(p.b),
        field(windowStartYear.toString),
        field(windowEndYear.toString),
        field(v)
      )
    )

  /** `metrics:sha256(researcherId, windowStartYear, windowEndYear, metricVersion, runId)`. */
  def metricsId(
      researcherId: String,
      windowStartYear: Int,
      windowEndYear: Int,
      metricVersion: String,
      runId: String
  ): Either[String, String] =
    for
      r <- requireId(researcherId, Set(IdKind.Researcher))
      _ <- requireWindow(windowStartYear, windowEndYear)
      v <- requireNonBlank("metricVersion", metricVersion)
      run <- requireId(runId, Set(IdKind.Run))
    yield mk(
      IdKind.Metrics,
      None,
      Seq(
        field(r),
        field(windowStartYear.toString),
        field(windowEndYear.toString),
        field(v),
        field(run)
      )
    )

  /** `match:sha256(sourceId, targetId, policyVersion)`. */
  def matchDecisionId(
      sourceId: String,
      targetId: String,
      policyVersion: String
  ): Either[String, String] =
    for
      s <- requireAnyId(sourceId)
      t <- requireAnyId(targetId)
      v <- requireNonBlank("policyVersion", policyVersion)
    yield mk(IdKind.MatchDecision, None, Seq(field(s), field(t), field(v)))

  /** `graph-edge:sha256(edgeType, from, to, policyVersion, sorted distinct sourceEvidenceIds)`.
    *
    * For undirected edge types the endpoints are canonicalized first (and self-loops rejected),
    * so either input order yields the same ID. Directed edges keep `from`, `to` order.
    */
  def graphEdgeId(
      edgeType: EdgeType,
      fromId: String,
      toId: String,
      policyVersion: String,
      sourceEvidenceIds: Seq[String]
  ): Either[String, String] =
    for
      et <- requireNotNull("edgeType", edgeType)
      f <- requireAnyId(fromId)
      t <- requireAnyId(toId)
      endpoints <-
        if et.undirected then CanonicalPair.of(f, t).map(p => (p.a, p.b))
        else Right((f, t))
      v <- requireNonBlank("policyVersion", policyVersion)
      evidence <- requireIds("sourceEvidenceIds", sourceEvidenceIds, Set.empty)
    yield mk(
      IdKind.GraphEdge,
      None,
      Seq(
        field(edgeType.toString),
        field(endpoints._1),
        field(endpoints._2),
        field(v),
        listField(evidence.distinct.sorted)
      )
    )

  private def textUnitId(
      kind: IdKind,
      entityId: String,
      sourceVersion: String,
      documentType: String,
      ordinal: Int,
      textHashValue: String
  ): Either[String, String] =
    for
      e <- requireAnyId(entityId)
      sv <- requireNonBlank("sourceVersion", sourceVersion)
      dt <- requireNonBlank("documentType", documentType)
      o <- requireNonNegative("ordinal", ordinal)
      th <- requireTextHash(textHashValue)
    yield mk(kind, None, Seq(field(e), field(sv), field(dt), field(o.toString), field(th)))

  /** `chunk:sha256(entityId, sourceVersion, documentType, ordinal, textHash)`. */
  def chunkId(
      entityId: String,
      sourceVersion: String,
      documentType: String,
      ordinal: Int,
      textHash: String
  ): Either[String, String] =
    textUnitId(IdKind.Chunk, entityId, sourceVersion, documentType, ordinal, textHash)

  /** `profile:sha256(entityId, sourceVersion, documentType, ordinal, textHash)`. */
  def profileId(
      entityId: String,
      sourceVersion: String,
      documentType: String,
      ordinal: Int,
      textHash: String
  ): Either[String, String] =
    textUnitId(IdKind.Profile, entityId, sourceVersion, documentType, ordinal, textHash)

  /** `evidence:sha256(evidenceType, referencedIds in order, runId)`. */
  def evidenceId(
      evidenceType: EvidenceType,
      referencedIds: Seq[String],
      runId: String
  ): Either[String, String] =
    for
      et <- requireNotNull("evidenceType", evidenceType)
      refs <- requireIds("referencedIds", referencedIds, Set.empty)
      _ <- if refs.nonEmpty then Right(()) else Left("referencedIds must not be empty")
      run <- requireId(runId, Set(IdKind.Run))
    yield mk(IdKind.Evidence, None, Seq(field(et.toString), listField(refs), field(run)))

  /** `bundle:sha256(queryId, sorted distinct evidenceIds)`. */
  def bundleId(queryId: String, evidenceIds: Seq[String]): Either[String, String] =
    for
      q <- requireId(queryId, Set(IdKind.Query))
      ev <- requireIds("evidenceIds", evidenceIds, Set(IdKind.Evidence))
    yield mk(IdKind.Bundle, None, Seq(field(q), listField(ev.distinct.sorted)))

  /** `claim:sha256(queryId, ordinal)`. */
  def claimId(queryId: String, ordinal: Int): Either[String, String] =
    for
      q <- requireId(queryId, Set(IdKind.Query))
      o <- requireNonNegative("ordinal", ordinal)
    yield mk(IdKind.Claim, None, Seq(field(q), field(o.toString)))

  /** `query:sha256(requestFingerprint)`. The service chooses the fingerprint (for example the
    * idempotency key plus the canonical request body); core only fixes the hashing.
    */
  def queryId(requestFingerprint: String): Either[String, String] =
    requireNonBlank("requestFingerprint", requestFingerprint).map(f =>
      mk(IdKind.Query, None, Seq(field(f)))
    )

  /** `snapshot:sha256(sourceDataset, snapshot)`. */
  def snapshotId(sourceDataset: SourceType, snapshot: String): Either[String, String] =
    for
      d <- requireNotNull("sourceDataset", sourceDataset)
      s <- requireNonBlank("snapshot", snapshot)
    yield mk(IdKind.Snapshot, None, Seq(field(d.toString), field(s)))

  // ---------------------------------------------------------------------------
  // Run ID
  // ---------------------------------------------------------------------------

  /** Canonical order of run inputs: by source dataset, snapshot, location, checksum. */
  private given Ordering[RunInput] =
    Ordering.by((i: RunInput) => (i.sourceDataset.toString, i.snapshot, i.location, i.checksum))

  /** Checks the run-ID inputs: a non-null manifest, at least one input, every input complete
    * with a `sha256:<64 hex>` checksum, no two inputs naming the same dataset and location, and
    * non-null maps without null keys or values.
    */
  private def requireRunManifest(m: RunManifest): Either[String, RunManifest] =
    def input(in: RunInput, i: Int): Either[String, RunInput] =
      for
        x <- requireNotNull(s"inputs[$i]", in)
        _ <- requireNotNull(s"inputs[$i].sourceDataset", x.sourceDataset)
        _ <- requireNonBlank(s"inputs[$i].snapshot", x.snapshot)
        _ <- requireNonBlank(s"inputs[$i].location", x.location)
        _ <-
          if isTextHash(x.checksum) then Right(())
          else Left(s"inputs[$i].checksum '${x.checksum}' must be sha256:<64 lower-case hex>")
      yield x
    def stringMap(name: String, v: Map[String, String]): Either[String, Unit] =
      requireNotNull(name, v).flatMap(m =>
        if m.exists((k, x) => k == null || x == null) then Left(s"$name has a null key or value")
        else Right(())
      )
    for
      mm <- requireNotNull("manifest", m)
      _ <- requireNonBlank("schemaVersion", mm.schemaVersion)
      _ <- requireNonBlank("pipelineStage", mm.pipelineStage)
      _ <- requireNonBlank("codeVersion", mm.codeVersion)
      inputs <- requireNotNull("inputs", mm.inputs)
      _ <- if inputs.nonEmpty then Right(()) else Left("inputs must not be empty")
      checked <- inputs.zipWithIndex.foldLeft[Either[String, Vector[RunInput]]](
        Right(Vector.empty)
      ) { case (acc, (in, i)) =>
        acc.flatMap(v => input(in, i).map(v :+ _))
      }
      _ <-
        val keys = checked.map(x => (x.sourceDataset, x.location))
        if keys.distinct.size == keys.size then Right(())
        else Left("inputs name the same sourceDataset and location more than once")
      _ <- stringMap("parameters", mm.parameters)
      _ <- stringMap("policyVersions", mm.policyVersions)
    yield mm

  /** Canonical JSON of a run manifest: the uPickle encoding with `inputs` sorted canonically (they
    * are a set) and every object's keys sorted, rendered compactly. Neither input order nor map
    * insertion order can change the run ID.
    */
  def canonicalManifestJson(manifest: RunManifest): Either[String, String] =
    def canon(v: ujson.Value): ujson.Value = v match
      case o: ujson.Obj =>
        ujson.Obj.from(o.value.toSeq.sortBy(_._1).map((k, x) => k -> canon(x)))
      case a: ujson.Arr => ujson.Arr.from(a.value.map(canon))
      case other => other
    requireRunManifest(manifest).map(m => canon(writeJs(m.copy(inputs = m.inputs.sorted))).render())

  /** `run:sha256(canonicalManifestJson)`.
    *
    * Timestamp-independent: the manifest has no time field. Tied to data: every input must carry
    * the `sha256:` checksum of the bytes it names, so re-staged data with different bytes gives a
    * different run ID, while the same inputs listed in any order give the same run ID.
    */
  def runId(manifest: RunManifest): Either[String, String] =
    canonicalManifestJson(manifest).map(json => mk(IdKind.Run, None, Seq(field(json))))
