# Entity-resolution policy changelog

Owner: entity-resolution-engineer. Scope: `modules/entity-resolution/**`.

The configured policy version (`SCHOLARCHAIN_ER_POLICY_VERSION`) selects the threshold set and
the weights. The decision-logic revision (`policyRevision` in
`src/main/resources/scholarchain/er/policy/er-v1.json`) changes whenever the decision logic
changes and the er-v1 thresholds do not. Every decision records the revision in `rulesFired`
(`POLICY_REVISION:<rev>`). The run manifest records it, so a revision change always gives a
new run ID. The adjudication cache key also includes it.

## er-v1, revision er-v1-r2 (feature version er-features-v2)

Phase 05 repair-01 (human-approved). The er-v1 accept, reject and LLM-band thresholds are
unchanged: accept 0.80, reject 0.45, band [0.60, 0.80), all from configuration. The weights and
the three cutoff values (0.85, 0.5, 0.7) are also unchanged; only their source moved (F7).

| Finding | Change |
|---|---|
| F1 | `AdjudicationSchema.parse` rejects any LLM output in which an object repeats a key. Keys are compared after unescaping (error `DUPLICATE_KEY`). Cached outputs are re-validated on replay, so a cached duplicate-key output is also invalid. |
| F2 | `ExactIdRules.compare`. For researcher identifier types, any difference between two non-empty sets is a disagreement. For work types, it is a disagreement when each side holds a value the other lacks. A shared value plus a disagreement on the same type is a conflict. So ORCID `{A,C}` vs `{B,C}` is now CONFLICT; in r1 it was ACCEPTED. |
| F3 | Two mentions attached to the same work at different author positions are never ACCEPTED and never sent to the LLM. "Same work" means the same record, agreeing work IDs, or bibliographically identical non-generic records (new feature `sameWorkBibliographic`). The outcomes are: an agreeing ID is CONFLICT; a disagreeing ID is REJECTED; incompatible names are REJECTED; the same record is REJECTED (`SAME_WORK_DISTINCT_AUTHOR_SLOTS`); any other case is UNRESOLVED (`SAME_WORK_DIFFERENT_POSITION`). `sameWorkContext` is now also 1 for two mentions on the same work record. |
| D2 | `coauthorOverlap` (er-features-v2) uses a one-to-one matching of compatible names instead of the Jaccard of `<surname> <initial>` keys. Two full given names must be equal: `Li Wang` and `Lei Wang` no longer overlap. Initials, hyphenation and name-order variants still match. This applies to both researcher coauthor lists and work author lists. |
| F4 | Generic titles are listed in the versioned resource `scholarchain/er/policy/generic-titles-v1.txt`. A work pair with a generic title is never ACCEPTED without an agreeing exact identifier: it is REJECTED below the reject threshold, otherwise UNRESOLVED (`GENERIC_TITLE_NEEDS_IDENTIFIER`), and it is never sent to the LLM. `exactBibliographic` is 0 for generic titles. A new work feature `genericTitle` is in {0, 1}. Generic titles are excluded from the researcher `titleSimilarity`. |
| F7 | Human decision 2 (repair-01 step 2b). `minSurnameSimilarity=0.85`, `minSharedIdTitleSimilarity=0.5` and `llmMinConfidence=0.7` are in `config/er-v1-thresholds.properties`, the file that holds the four er-v1 thresholds, and are read only from there (`DecisionCutoffs.load`). The path is supplied by the required `ResolveSample --er-policy-properties <path>` argument (`Policy.load(env, path)`); no path or value is built in. They were removed from `er-v1.json`, and a policy resource that names one is refused. There is no fallback: a missing file, or a missing, blank, duplicated, non-numeric, NaN, infinite or out-of-[0, 1] value stops the run. `Policy.fromConfig(c)` without cutoffs always fails. The run manifest records each value, its source (`properties-file:er-v1-thresholds.properties#<key>`) and the file's SHA-256 (`entityResolution.cutoffsSourceSha256`), all run-ID inputs. The earlier `scholarchain.entityResolution.<name>` config-key path was removed; core is unchanged. |
| F8 | Decisions record `LLM_ADJUDICATION` for both a cache hit and a model call. `summary.json` no longer reports cache hits. `--decided-at` is required. A warm and a cold cache produce byte-identical outputs. |
| F9 | The run manifest adds: the code fingerprint (SHA-256 of every class and resource under `scholarchain/er/`; since the repair-01 follow-up, the evaluator package is excluded, see N5 below), the policy revision, SHA-256 hashes of the policy JSON, generic-title list and prompt, and the cutoffs with their sources. Outputs are write-once: an existing file with different bytes stops the run before anything is written. |
| F10 | An unsuffixed dblp person key (`Wei Zhang`) is not an exact identifier. Only suffixed keys (`Wei Zhang 0001`) are compared. A shared `REPOSITORY_URL` is not identity (`ErIdType.agreementIsIdentity = false`). |
| F12 | `Phase4Input.load` fails when any row fails its core contract. Previously such rows were counted and dropped. |
| F13 | The adjudication cache key includes the SHA-256 of the prompt text (`AdjudicationKey.promptSha256`, also stored in each cache entry) and the policy revision (`Policy.cacheVersion`). |

Observed effect on the bounded Phase 4 sample (target/phase04/run-b4, fake adjudicator, same
thresholds). This was a scratch run, outside the repository: the existing er-v1 r1 run
directory, the review queue and the sealed key were not touched or regenerated. The pair set
is the same 92 pairs.

- Researcher: 67 accepted, 3 rejected, 1 conflict, 0 unresolved became 59 accepted,
  0 rejected, 1 conflict, 11 unresolved.
- Work: unchanged (18 accepted, 1 rejected, 2 conflict).
- 8 researcher pairs moved from ACCEPTED (score) to UNRESOLVED `SAME_WORK_DIFFERENT_POSITION`
  (F3). All 8 are same-DOI mentions at adjacent positions in two sources.
- 3 researcher pairs moved from REJECTED `EXACT_ID_DISAGREE` to UNRESOLVED (F10). Their
  disagreement involved an unsuffixed dblp key.

Official r2 decision outputs (repair-01 step 2b, PART B input): `local-run/er-v1/run_id=7449422852604780fd2de54fcaa1ff18ebe1cf761c260d8cf4de08b368ce4e15`
(target/phase04/run-b4, fake adjudicator, `--decided-at 2026-11-20T21:40:18Z`, no review queue).
Researcher: 59 accepted, 0 rejected, 1 conflict, 11 unresolved. Work: 18 accepted, 1 rejected,
2 conflict, 0 unresolved. No pair entered the LLM band. Every one of the 92 decisions carries
`POLICY_REVISION:er-v1-r2` in `rulesFired`, `policyVersion` `er-v1` and `provenance.policyVersion`
`er-v1`. The revision is not a typed provenance field: `core` `Provenance` has no field for it.
Resolved by ratified decision (repair-01, decision 4, option (a)): the revision is carried by
`POLICY_REVISION:<rev>` in `rulesFired` plus the `provenance.runId` link to the run manifest, with
no new core field. It is no longer BLOCKED-ON domain-engineer (re-review N2; the remaining
limitation is in KNOWN-LIMITATIONS.md).

### Repair-01 follow-up: code fingerprint scope (re-review N5; no decision-logic change)

The revision stays `er-v1-r2`: no rule, feature, threshold, weight or cutoff changed, and the
decision content of a run is unchanged.

| Finding | Change |
|---|---|
| N5 | `CodeFingerprint.of` (the run-ID input from F9) leaves out `scholarchain/er/evaluation/**`, the gold-set evaluator, which reads finished runs and decides nothing. Evaluator changes no longer change a decision run's run ID. Decision packages and resources (policy JSON, generic titles, prompt, schema) still change it. The evaluator's own evaluation ID uses `CodeFingerprint.ofModule`, which still covers the whole module, evaluator included. Tests: `CodeFingerprintSpec`. |
| N6 | The weak F11 tests in `MatchPolicySpec` ("no CONFLICT input is ever ACCEPTED under any threshold setting", "decisions are deterministic for the same input and context") were removed. `PolicyRevisionR2Spec` and `ResolveSampleSpec` cover the same claims more strongly. Tests only. |

Reproducibility of the committed r2 run: the N5 fix is itself a change to fingerprinted
decision-run code (`scholarchain/er/run/CodeFingerprint`). Replaying the r2 command on this tree
therefore gives a new code fingerprint and a new run ID, not `run_id=7449…`. The decision content
is identical apart from the embedded run ID. The r2 directory was not regenerated. Details are in
KNOWN-LIMITATIONS.md (N5).

## er-v1, revision er-v1-r1 (feature version er-features-v1)

The initial Phase 05 PART A policy. Its run is
`local-run/er-v1/run_id=9241e65e…`, and the review queue `review-queue-v1.csv` was generated
from it.

## Phase 16b: Anthropic adjudication adapter (no decision-logic change)

Policy version er-v1, revision er-v1-r2, feature version er-features-v2, thresholds, cutoffs,
prompt `er-adj-prompt-v1` and response schema `er-adjudication-response-v1` are unchanged.

- Every `ResolveSample` run loads `llm.provider` (`SCHOLARCHAIN_LLM_PROVIDER`).
- `--adjudicator llm` calls the configured provider. Only `anthropic` is available;
  `bedrock` is refused as before. The adapter is in this module because it may depend only on
  `core` (pending human ratification).
- The cache key holds the configured model ID. Cache entries also keep the answering model and
  token usage. No live model has been called, so there are no model results.
- Model output nested deeper than 64 levels is refused before parsing (`TOO_DEEP`, so
  `LLM_OUTPUT_INVALID`); the same bound applies to provider response and error bodies.
- The response `model` is recorded only when it is the configured ID, alone or with a
  `-YYYYMMDD` suffix; otherwise the configured ID is recorded.
- The module code changed, so decision runs and evaluations get new IDs.
- Review finding F5: an answer is used and cached only when `stop_reason` is `end_turn`. A
  truncated (`max_tokens`), otherwise unfinished or unlabelled answer is a transport error: the
  pair stays UNRESOLVED (`LLM_UNAVAILABLE`), nothing is cached, and a later run asks again. The
  token usage of such a call is not recorded. The cache format and key are unchanged; no live
  call had been made, so no existing entry needs removal.
