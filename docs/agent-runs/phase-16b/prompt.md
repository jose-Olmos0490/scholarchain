# Phase 16b work order: instructor-approved model-provider switch

Context: Bedrock is NOT_AUTHORIZED account-wide (docs/agent-runs/phase-15/bedrock-evidence.txt; AWS Support case open). On 2026-11-26 the instructor approved the Anthropic API for LLM generation and Voyage AI voyage-4-lite for embeddings. Bedrock must stay configurable. All external API cost counts toward the $25 project budget.
Environment: dev, us-east-2, profile cs441. No terraform apply, no deploy in this phase.
Secrets: keys come only from Secrets Manager (cloud) or the env vars ANTHROPIC_API_KEY / VOYAGE_API_KEY (human-run live tests). Never read, print, log or commit a key. Never read .env or tfstate.

Delegations in order (all routing through the orchestrator):
1. domain-engineer: config contract. llm.provider = bedrock|anthropic and embedding.provider = bedrock|voyage, both required keys with no code default. Anthropic: model ID, base URL, anthropic-version, timeouts, retries. Voyage: model ID, base URL, output dimension, batch size, timeouts. Reject at startup: unknown provider, or embedding output dimension != every vectorIndexSettings.*.dimension. Env overrides plus contract tests.
2. rag-engineer: AnthropicLlmClient implements LlmClient (POST /v1/messages via java.net.http + uPickle; 429/529 -> Overloaded, deadline -> TimedOut, other 5xx -> Transient, other 4xx -> Permanent; record model version and token usage; key from an injected supplier, never logged). VoyageEmbedder implements Embedder (POST /v1/embeddings; input_type=document for ingest, query for retrieval; output_dimension from config; batching; same normalization and dimension check as the existing Embedder contract). Provider factory chosen by config. Bedrock adapters unchanged. Stub-HTTP unit tests (no network); live ITs only behind the existing live profile; report the exact live command.
3. entity-resolution-engineer: adjudication uses the configured LlmClient; cache key includes the new model version; abstention and schema unchanged; tests.
4. service-engineer: QueryApiMain and EmbeddingWorkerMain wire providers from config; keys read from Secrets Manager by secret-name env var; /readyz fails clearly when a key secret is missing; extend the credential canary test to Anthropic- and Voyage-shaped keys.
5. build-engineer: only if a dependency change is unavoidable (prefer none).
6. infra-engineer: Secrets Manager secrets by name only (scholarchain/dev/anthropic-api-key, scholarchain/dev/voyage-api-key, KMS, no value in Terraform); GetSecretValue on exactly those ARNs for the task roles that need them; provider, model-ID and secret-name env vars; keep Bedrock IAM gated on provider; update terraform.tfvars.example (budget_limit_usd = 25, new model IDs). fmt, validate, offline plan. Do not apply.
7. test-engineer: adversarial tests for both adapters: 429-then-success, 529, timeout, malformed JSON, wrong dimension, empty batch, missing key, key-shaped text in error bodies redacted.
8. code-reviewer: read-only review of the whole phase diff.

Gate: sbt check and scripts/check.sh pass; the human runs one live Anthropic structured call and one Voyage batch (8 texts, dim 1024); reviewer APPROVE, or accepted findings recorded in human-gate.md.
