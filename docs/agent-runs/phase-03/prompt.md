# Phase 03 work order: contracts, configuration, dataset manifest

Orchestrator rules for this phase:
- Delegate in two separate work orders. Run dataset-engineer first. Then run domain-engineer with the dataset-engineer final report pasted into its work order.
- Do not do specialist work inline.
- If an agent needs a dependency that is not in the Phase 2 version ledger, it must return BLOCKED-ON build-engineer. Route the request to build-engineer, have it run sbt check, then rerun the blocked agent in a fresh context.
- After both agents report COMPLETE, invoke code-reviewer (read-only) against the Phase 3 gate below. Route findings to the owning agent. Never let the reviewer fix anything.
- Save each final report verbatim: dataset-engineer and domain-engineer reports to docs/agent-runs/phase-03/agent-report.md, reviewer report to docs/agent-runs/phase-03/review-report.md. Record repairs as repair-NN.md.
- Stop for human ratification. Do not commit.

Selected datasets: DBLP (required), OpenAlex, ORCID, Crossref.

## Work order A: dataset-engineer
- Create datasets/dataset-manifest.yaml for every selected dataset with these fields: name, purpose, source and documentation URLs, license, snapshot date, retrieval command, format and compression, expected and observed size, S3 raw prefix, checksum or ETag policy, schema version, subset rule, linkage fields, update strategy, privacy notes, limitations, validation commands, owning agent.
- NEVER invent a license, URL, checksum, size, snapshot date or identifier. Any field you cannot confirm from an official page or observed command output must be null with verification_status: UNVERIFIED and a note saying what a human must check.
- S3 prefixes use placeholders such as ${RAW_BUCKET}, never a hardcoded bucket name.
- datasets/scripts/: bounded, idempotent bash scripts in strict mode (set -euo pipefail).
  - download script: sample mode by default, plus --dry-run. Full-corpus mode requires both --full and SCHOLARCHAIN_ALLOW_FULL_DOWNLOAD=1.
  - Skip any file whose checksum already matches.
  - validate-manifest script: exits nonzero on any missing required field.
- datasets/samples/: small REAL excerpts fetched with bounded commands (at most 50 records and 200 KB per file). Add a SHA256SUMS file. No synthetic records presented as real.
- datasets/schemas/: field-level schema notes per dataset.
- Run bash -n on all scripts, the manifest validator, sha256sum -c on the samples, and a --dry-run proving nothing large is fetched by default.

## Work order B: domain-engineer (in modules/core)
- Immutable records:
  - WorkRecord, AuthorMention, CanonicalResearcher, AuthorshipRecord, PublicationTeam, CoauthorPairCandidate, CoauthorEdge, ResearcherMetrics
  - Venue, Institution, Topic, Provenance, MatchDecision
  - GraphNode, GraphEdge, EvidenceItem, EvidenceBundle
  - QueryPlan, AnswerClaim, AnswerResponse
- Every record carries a schemaVersion and a Provenance.
- Enums: SourceType, MatchOutcome (ACCEPTED, REJECTED, UNRESOLVED, CONFLICT), EvidenceType, CollaborationMetric, QueryOperation, VerificationStatus.
- Use only the JSON library and test framework already pinned in the version ledger.
- Stable IDs (SHA-256 hex with explicit namespace prefixes):
  - work: namespace + authoritative source ID
  - researcher: validated external ID, otherwise a versioned ScholarChain identity ID
  - coauthor edge: hash(canonical ordered researcher IDs + window + policy version)
  - chunk or profile: hash(entity ID + source version + doc type + ordinal + text hash)
  - graph edge: hash(edge type + canonical endpoints + policy version + source evidence)
  - run ID: hash of run-manifest content, independent of timestamps
- Pair canonicalization: researcherA < researcherB lexicographically. Self-pairs are rejected.
- Config loader: env vars override a versioned environment file, which overrides a base file. Bucket names, endpoints, account IDs, model IDs, thresholds, graph windows and bridge-algorithm parameters must have no hardcoded defaults. Missing required keys produce a typed error that lists them.
- Validation rejects:
  - missing provenance
  - confidence outside [0,1]
  - unsupported schemaVersion
  - malformed stable IDs
  - pair ordering that is wrong or a self-pair
  - windowStartYear > windowEndYear
- Tests: JSON round-trip for every record, frozen golden JSON wire examples under the core test resources, schema-version tests, one test per validation rule, stable-ID determinism tests, config precedence and missing-key tests.
- Run sbt check.

## Required report contents
- Sample serialized records
- Stable ID formulas
- Pair canonicalization rule
- Config precedence
- Schema versions
- Exact test counts
- Known unresolved dataset questions
- Every UNVERIFIED manifest field

## Phase 3 gate (for code-reviewer)
- Every record has provenance.
- Wire-format contract tests pass.
- Manifest validation passes.
- Sample scripts do not silently fetch large files.
- Pair-ordering and time-window tests pass.
- No license, identifier or source is invented: every license, URL and checksum is traceable or explicitly marked UNVERIFIED.

## Pinned stack (from project/Dependencies.scala; do not add or change dependencies)
- JSON: uPickle 4.4.3 (ReadWriter derivation for every record and enum)
- Tests: ScalaTest 3.2.20. Any test needing live AWS must use the existing awsIntegrationTag so sbt check excludes it.
- Config: Typesafe Config 1.4.10 (HOCON). Use modules/core/src/main/resources/reference.conf for non-secret structure only, plus a versioned conf/<env>.conf layer. Env var overrides use ${?VAR} substitution. Required keys with no value must fail with a typed error listing every missing key.
