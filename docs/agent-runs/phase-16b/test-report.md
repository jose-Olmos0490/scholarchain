# Phase 16b test reports

## Delegation 7: test-engineer (first run)

```text
STATUS: BLOCKED-ON
OWNER: test-engineer
WORK ORDER: Phase 16b, delegation 7 of 8: adversarial tests for the Anthropic and Voyage adapters. The tests are written and run. `sbt check` fails because 7 new tests expose defects in production code.

FILES CREATED (all under /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/provider/):
- ProviderAdvKit.scala (loopback stub, raw-socket server, synthetic canary keys)
- AnthropicAdaptersAdvSpec.scala (27 tests, `rag` and `entityResolution` adapters)
- VoyageEmbedderAdvSpec.scala (19 tests)
- ProviderKeysAdvSpec.scala (8 tests: ProviderKey, stub Secrets Manager client, ProviderWiring)

FILES MODIFIED: none. No production or build files were touched.

COMMANDS RUN:
- `sbt adversarialTests/Test/scalafmt` and `adversarialTests/testOnly` on the new package (several iterations)
- `AnthropicAdaptersAdvSpec` three more times: the same 22 passed and 5 failed each time
- `sbt check` once on the final tree: exit 1. Log: /tmp/claude-1000/-home-olmos-scholarchain/6472a609-d73f-4016-b2a0-9412d7fd4718/scratchpad/check.log
- One shell command was blocked by the guard hook because it ran `stat` on the test marker with a redirect in the same line. Rerun without the `stat`. Nothing wrote to the marker.

TEST RESULTS: passed=2538 failed=7 skipped=24 errored=0 (exact, observed)

| Project | Passed | Failed | Ignored |
|---|---|---|---|
| core | 718 | 0 | 0 |
| hadoop | 75 | 0 | 0 |
| entity-resolution | 228 | 0 | 0 |
| spark | 69 | 0 | 0 |
| storage | 369 | 0 | 0 |
| rag | 285 | 0 | 0 |
| services | 168 | 0 | 0 |
| tests | 626 | 7 | 24 |

- The new tests account for 54 of the `tests` results: 47 passed, 7 failed.
- The 24 ignored tests are in `tests`; none are in the new files.

VALIDATION RESULTS:
- In `sbt check`, the format checks, compilation and launcher check passed. `markTestRun` failed, so the test marker was not updated.
- `scripts/check.sh` not run.
- All HTTP was to loopback stubs. No AWS or provider calls; no key, env var, tfstate or tfvars read.

DEFECTS FOUND (all LOW severity: each needs a broken or hostile provider answer)

| # | Test | Failing input | Expected | Observed | Owner |
|---|---|---|---|---|---|
| D1 | rag adapter: deeply nested 200 body | `[` x200000 + `]` x200000, or that array as `content` | `Transient` | `StackOverflowError` escapes `complete` | rag-engineer |
| D2 | entity-resolution adapter: same | same | `Transient` | `StackOverflowError` escapes `call` | entity-resolution-engineer |
| D3 | VoyageEmbedderAdvSpec: same | same as body or `data` | typed embedding exception | `StackOverflowError` escapes `embed` | rag-engineer |
| D4 | rag adapter: key echoed in model-name field | 200 with `"model": "<canary>"` | key not recorded | `LlmResponse.modelVersion` is the key | rag-engineer |
| D5 | entity-resolution adapter: same | same | key not recorded | `AdjudicatorReply.modelVersion` is the key | entity-resolution-engineer |
| D6 | property name chosen by the model is not repeated | `{"a":1,"<key canary>":2}` with `additionalProperties:false` | name not repeated | `MODEL_OUTPUT_INVALID` message contains the canary | rag-engineer |
| D7 | fractional index refused | Voyage indexes `0.7` and `1.2` for 2 texts | refused | accepted as positions 0 and 1 | rag-engineer |

- D1 to D3: the stack trace shows recursive ujson transform calls. Inference: a type mismatch builds an exception message that renders the whole value. `StackOverflowError` is not caught by `NonFatal`.
- D4 and D5: the model-name pattern lets an `sk-ant-...` shaped string through into provenance, and in entity resolution into the cache and decisions.
- D6 predates the phase (`modules/rag/src/main/scala/scholarchain/rag/llm/JsonSchema.scala`). It contradicts the `StructuredLlmCall` Scaladoc.

COVERAGE BY REQUIREMENT (all passed unless a defect is cited): 429 then success; 529 and classification (17 statuses); timeout; malformed JSON; wrong dimension and index; NaN and Infinity vectors; empty batch; missing or bad key; redaction of error bodies; redirects not followed; interrupt; huge or odd retry-after; secret-backed keys; provider failures not cached.

ASSUMPTIONS:
- A fractional index and a key-shaped model name should be refused. The work order does not say so explicitly; D4, D5 and D7 are strictness findings the orchestrator may choose to accept.
- Canary keys imitate the key shapes; they are not real.

RISKS (not failing tests):
- `AnthropicAdjudicator.send` has no pre-send interrupt check, unlike `ProviderHttp`. Not reproducible in 40 interrupted calls.
- Requests reach (adapter retries + 1) x (structured-call retries + 1); a test confirms 6 for 2 and 1.
- From reading the code: a `max_tokens` cut-off adjudication is cached; the cache key omits the token limit.
- From reading the code: a key echoed in model text would be stored in the adjudication cache's `rawOutput`.
- Not bounded or checked: response body size; all-zero Voyage vector accepted.
- Not tested: connect timeout, TLS, real provider response shapes.
- The two Anthropic adapters duplicate HTTP and redaction logic.

MANUAL ACTION REQUIRED: none. (Notice: the `aws-api` MCP server announces it is deprecated: https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md.)

BLOCKED-ON OWNER: rag-engineer (D1, D3, D4, D6, D7); entity-resolution-engineer (D2, D5)

BLOCKED-ON EVIDENCE: `sbt check` exit 1, `(adversarialTests / Test / test) sbt.TestsFailedException`, "Tests: succeeded 626, failed 7, canceled 0, ignored 24".

NEXT GATE: owner repair, then test rerun, then code-reviewer (delegation 8).
```

## Final tree: scripts/check.sh (run by the orchestrator after all repairs)

Run with ANTHROPIC_API_KEY, VOYAGE_API_KEY and SCHOLARCHAIN_AWS_IT removed from its environment. Exit 0. This run includes the adversarial tests that failed in the first run above; test-engineer was not re-invoked.

```text
Tests: succeeded 718, 234, 295, 382, 168, 75, 69, 633 (2574 total); failed 0; ignored 24
GATE agent-files: PASS
GATE json: PASS
GATE bash-syntax: PASS
GATE shellcheck: PASS
GATE sbt: PASS
GATE contracts: PASS
GATE schemas: PASS
GATE python-tests: PASS
GATE terraform: PASS
CHECK RESULT: PASS
```
