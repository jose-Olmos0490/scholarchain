STATUS: COMPLETE
OWNER: experiment-engineer
WORK ORDER: Phase 15 repair of review findings 1-5 and 6-19. All are fixed on the tool side. Finding 21's decision is recorded in D and E.

**Read before approving C or F**
Requirement 5 is applied literally, so **C and F as designed will report FAILED (NO_VECTORS_WRITTEN) on a healthy system** if the staged run is already indexed. Unchanged items are skipped and nothing is written.
- Each execution needs a staged run with items not yet in the vector index. This includes F after C, and any rerun.
- C's cost note assumed no embedding calls. I changed the note to say so; the figures are unchanged.
- I did not weaken the rule.

**What "written" means**
- The worker logs message counts and item reject codes, but no created or skipped counts.
- `VectorIngestor.recordLedger` writes a `vector-ingest-<index>-bNNNNN` SUCCEEDED run-ledger entry only after a successful PutVectors.
- The tool lists those entries with `dynamodb query` before the send and after the watch. It needs the new variable `SC_DDB_TABLE_RUN_LEDGER`.
- No new SUCCEEDED entry, or no readable ledger, gives FAILED. This also applies when the run stopped for another reason.
- Limits: it counts put batches, not vectors. A successful put whose ledger write failed would read as FAILED. A partly rejected run can still be COMPLETED.
- Not verified live: the key layout comes from the code, and I did not check that your profile may query the table.

**Fields the human must fill (every load-test manifest)**
- `approval.status`: exactly `APPROVED`.
- `approval.approved_by`: a name, not a placeholder.
- `approval.approved_on`: `YYYY-MM-DD`, no later than tomorrow UTC.
- Then commit.
- "Not blocked" means top-level `blocked` is present and boolean `false`, and no precondition starts with `BLOCKED`. B is set to `blocked: true`.
- I filled no approval value. All six manifests are still `PENDING_HUMAN_APPROVAL`.

The load tools also now require:
- the manifest's environment in `SCHOLARCHAIN_APPROVED_ENVS`;
- file name equal to `name`;
- `args` pointing at the manifest itself;
- a run-experiment output directory named for the same manifest.

**Still unguarded**
- **A and B (pipeline runner), owner infra-engineer:** neither `run-experiment.sh` nor the pipeline script reads `approval` or `blocked`. A committed B still runs the same pipeline as A.
- **Direct invocation of a load tool, owner infra-engineer:** no clean-tree or committed-manifest check.
- **Finding 18 residual, owner infra-engineer:** `run-experiment.sh` passes only the manifest's `args` and `SC_EXPERIMENT_OUT`, not the manifest path. A copy under a new file name that keeps the old `name` and `args` is not detected before the run. `collect.py` catches a content difference afterwards by SHA-256. Passing the manifest path to the runner would close this.

**Cap changes needing re-approval**

| Manifest | Field | Old | New |
|---|---|---|---|
| D | `limits.max_duration_seconds` | 1800 | 2400 |
| D | `load.max_load_seconds` | 1500 | 2100 |
| D | `load.estimated_usd_per_request` | none | 0.036 |
| E | `load.estimated_usd_per_request` | none | 0.0375 |

- **Why D's time caps rose:** the worst case for all four levels is 2025 s.
- **Timeout check (by reading, not run):** `run-experiment.sh` accepts 1..86400, sends TERM at the limit and KILL 60 s later. 2100 s plus one 45 s request fits inside 2400 s.
- **Cost cap model:** requests sent times the manifest price, checked against `cost_cap_usd` (D 15, E 5, unchanged). It is an estimate, not a bill, and the model and price are unverified. At these prices the request cap binds first (D estimate 9.0, E 3.0).
- **C and F:** their cost cap is still declared only.
- **New code ceilings:**
  - rate 600/min;
  - request timeout 120 s (now a total deadline);
  - level, load and queue timeouts 3600 s;
  - cooldown 600 s;
  - queue poll interval 1..300 s.

**Behaviour changes to accept or reject**
- `collect.py --allow-missing` now covers every missing input. It seals and records them in `missingInputs`. This is an explicit opt-out of requirement 2, kept so a failed run with no results can still be preserved.
- `--raw-root` is removed.
- `build_plots.py --out` must lie under `experiments/derived/`.
- The collector reads the manifest from the run's commit.
- A D run with a level cut by its time cap ends `INCOMPLETE` (exit 1) after attempting all levels.

**Findings**
"Observed" means the new test was run against a scratch copy of the pre-fix code and failed for the old behaviour.

| # | Fix | Test (suffix `_fNN`) | Old failure observed |
|---|---|---|---|
| 1 | `load-tests/queue_scenario.py:104` counts per batch; unknown outcomes recorded as null | `a_failed_later_batch_keeps_the_accepted_count`, `a_partly_refused_batch_counts_what_was_accepted` | yes (0 instead of 20 and 13) |
| 2 | `experiments/bin/collect.py:235,353` | `a_missing_variable_seals_nothing...`, `missing_runner_results...`, `a_missing_pipeline_record...`, `a_deployed_service...`, `a_truncated_run_json...` | yes, except the truncated-JSON test (added after the old-code run) |
| 3 | `load-tests/sclib.py:76,112`; `query_load.py:339`; `queue_scenario.py:227` | `..._for_an_unapproved_or_blocked_manifest`, `an_unapproved_environment...`, `blocked_is_stated...` | yes (the helper unit test only errored on the missing function) |
| 4 | `query_load.py:194,388-394`; D and E manifests | `a_slow_level_does_not_cancel...`, `the_estimated_cost_cap...`, `a_missing_price...`, `d_time_caps...`, `the_price_estimate...` | yes |
| 5 | `queue_scenario.py:139,263,284-287` | `no_vector_written_is_failed`, `unreadable_write_evidence...`, `an_unreadable_ledger_before_the_send...` | yes (old code exited 0) |
| 6 | `build_plots.py:69,205` | `absent_answer_values_are_missing_not_zero` | yes ("0.0") |
| 7 | `build_plots.py:87,91,166,268,334,348`; `queue_scenario.py:163` | `gaps_failed_polls...`, `planned_levels...`, `absent_sections...`, `worker_counts...`, `a_milestone_never_observed...` | yes |
| 8 | `build_plots.py:257` | `interleaved_modes_have_no_per_mode_throughput` | yes |
| 9 | `collect.py:197`; `build_plots.py:312` | `only_this_runs_traces...`, `stage_latency_excludes_preflight...` | yes |
| 10 | `collect.py:267` | `the_manifest_of_the_run_commit...`, `a_manifest_absent...` | yes |
| 11 | `collect.py:144,153`; `build_plots.py:394` | `images_are_dated_at_collection...` | yes |
| 12 | `query_load.py:100,108` | `a_malformed_token_is_never_printed_or_written` | yes (token appeared) |
| 13 | `query_load.py:141,329-334`; `queue_scenario.py:218` | `..._over_the_code_ceilings...`, `the_request_timeout_is_a_total_deadline`, `poll_interval_zero...` | yes |
| 14 | `query_load.py:207` | `a_tripped_stop_cancels_requests_waiting...` | yes (12 sends instead of 3) |
| 15 | `collect.py:287,362,406` stages beside the destination, seals, then one rename | `a_failed_seal...`, `a_failed_move...` | yes |
| 16 | `collect.py:326`; `build_plots.py:441` | `a_raw_root_elsewhere...`, `a_symlinked_experiment_directory...`, `derived_output_stays...` | yes |
| 17 | `queue_scenario.py:195` | `zero_counts_right_after_the_send...` | yes |
| 18 | `sclib.py:131`; `collect.py:249` | `a_manifest_whose_args...`, `a_run_launched_for_another...`, `a_result_made_by_another...` | yes |
| 19 | tests only | error sanitizing, message cap, both time caps, TERM, pacing | not applicable (coverage gap); 6 of 6 deliberate mutations of scratch copies were caught |
| 21 | preconditions in D and E; opt-in kept | `the_plain_http_decision_is_a_precondition...` | yes |

**Not fixed**
- **Finding 19 gate wiring:** the Python tests are in neither `sbt check` (build-engineer) nor `scripts/check.sh` (infra-engineer).
- **Finding 20:** not in this work order (infra-engineer, with EE for the format).
- **Nits N2 and N3:** not in scope. N1 is storage-engineer's and N4 factory-engineer's.

FILES CREATED: none in the repository.

FILES MODIFIED (all still untracked, under `/home/olmos/scholarchain/`):
- `load-tests/`: `sclib.py`, `query_load.py`, `queue_scenario.py`, `tests/fakes.py`, `tests/test_load_tools.py`
- `experiments/`: `bin/collect.py`, `bin/build_plots.py`, `tests/test_experiments.py`
- `experiments/manifests/`: all six YAML files

COMMANDS RUN:
- `python3 -m unittest discover -s load-tests/tests` and `-s experiments/tests`
- the same tests against pre-fix copies in the scratchpad, plus `mutate.py`
- `sbt check`
- read-only `git status`, `git log`, `git rev-parse`, and searches limited to `modules/` and named files

Blocked commands:
1. The guard hook rejected a Bash heredoc edit as a write touching `spec/`. Nothing targeted `spec/`; I made the edits with Edit and Write.
2. A permission denial rejected the first old-code run, which began with an unneeded `rm -rf`. I reran it into new directories with no deletion.
3. The guard rejected a command that listed `.claude/.last-test-run` next to a redirect. I dropped the listing. The marker was not written by me.

No search was rooted at the repository root or under `infra/`. One existing test reads the named file `infra/terraform/environments/dev/variables.tf`. No AWS calls, live scripts or deployed endpoints.

MANIFEST / COMMIT SHA / SNAPSHOT / REGION / RUN IDS / START-END: no experiment was run. HEAD is `55b0d3b`.

TEST RESULTS:
- Python: load-tests 43/43 and experiments 38/38 pass.
- `sbt check`: exit 0, 2383 succeeded, 0 failed, 24 ignored; `markTestRun` ran. It does not run the Python tests.
- Old-code runs: in the scratch copies, two manifest tests errored only because `scripts/` and `infra/` were not copied. A few other tests errored on new keys or functions; none is counted above as an observed failure on that basis, except `planned_levels...`, where the old code produced no load-plan table at all.

VALIDATION RESULTS: no raw data exists, so there are no checksums to report and no runs to retain. Log: `/tmp/claude-1000/-home-olmos-scholarchain/4e7179d0-408e-4b9d-9c2d-d85823a69a92/scratchpad/sbt-check.log`

RESULTS SUMMARY AND LIMITATIONS: tooling repair only, with no measurements. Everything is tested against stubs and a localhost fake, not against real AWS output.

ESTIMATED COST: zero for this work. D may now run up to 10 minutes longer.

ASSUMPTIONS:
- The run ledger table used by the deployed worker is the one you will name in `SC_DDB_TABLE_RUN_LEDGER`.
- The deployed worker matches the code at HEAD.

RISKS:
- The approval check reads a text field and cannot prove who wrote it.
- The phase 15 `agent-report.md` may now be out of date (for example `--raw-root` and the C/F outcomes).

MANUAL ACTION REQUIRED:
- Decide how C and F get an unindexed staged run.
- Re-approve the D cap and price changes.
- Issue a dedicated short-lived token for D and E, and revoke it after the runs.
- Do not run B.
- Have this diff re-reviewed before committing.
- Notice from the connected `aws-api` MCP server (I did not use it): it is reaching end of development. Migration guide: https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md

BLOCKED-ON OWNER: none for this work order. The follow-ups above belong to infra-engineer, build-engineer and the human.
BLOCKED-ON EVIDENCE: none.
NEXT GATE: reproducible rerun and raw-result review; first, code-reviewer re-review and human approval of the manifests.
