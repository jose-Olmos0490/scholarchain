STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 16 read-only re-review (round 2) of /home/olmos/scholarchain/docs/evaluation.md against the Part A outputs (f79d007)
VERDICT: APPROVE
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools only: Read, Grep, Glob)

SUMMARY
F1-F11, F13 and F14 are resolved, and so are the four stale factory-count rows. F12 is kept as a known limitation, as decided. I found no number that contradicts the files I read, no Part B claim, and no phase record used outside F2-F5 and F13. There are three minor wording findings, none blocking.

PER-FINDING STATUS
| Finding | Status | Check |
|---|---|---|
| F1 | resolved | Five of six events carry the flag and the Terraform-state one does not (raw factory/ownership.csv:4-6, 8, 9, 15) |
| F2 | resolved | 5.1 and section 8 item 32 match phase-11/human-gate.md:10-57 |
| F3 | resolved | 3.4, 3.5, section 8 items 16 and 19, and section 9 row 5 match phase-15/human-gate.md:27, 36, 44, 47, 50 |
| F4 | resolved | 5.3 and item 23 match gate:24, 39, 50, both deploy reports (smoke FAIL on "evidence") and raw work_orders.csv:32 |
| F5 | resolved | 3.5 and items 18, 21, 22 match the gate and the incident report; no secret values |
| F6 | resolved | 3.3 matches queue_scenario.py:162-215, observations.jsonl lines 2, 84, 85, 107, run.json and manifest.yaml:153; arithmetic and the 23.3 s margin hold; inference is labelled |
| F7 | resolved | "8 SAME researcher pairs" |
| F8 | resolved | 6ce56c9 named in section 3; four code versions in 5.3 |
| F9 | resolved | Every flagged cell in the hook table matches raw hook_blocks.csv |
| F10 | resolved | 5.3 says the match is not independent corroboration (see N1 for section 8) |
| F11 | resolved | aws/observed-at-utc.txt holds 2026-11-26T01:00:00Z and is listed in SHA256SUMS |
| F12 | not fixed by decision | Kept as a limitation in 5.4 and item 37; the listed literals match part_a_other.py:104-107, 97-99, 183-187, 229, 259-268 |
| F13 | resolved | Items 9, 17, 33 match KNOWN-LIMITATIONS.md, gate:49 and build.sbt:24-27; the clean-clone exclusion is labelled as inference |
| F14 | resolved | Item 6 is labelled Interpretation |
| Stale rows (4) | resolved | 4.1, item 24 and section 9 row 1 match phase-16/human-gate.md:10 |

NEW FINDINGS
| # | Severity | File:line | Concrete failure input | Expected impact | Owning agent |
|---|---|---|---|---|---|
| N1 | minor | /home/olmos/scholarchain/docs/evaluation.md:983-984 | Item 36 says "for two of three deploys the only match is the deploy's own report", without qualification. phase-15/human-gate.md:23 and :39 also record the 2a196bb0 and 47dd1a5c prefixes. Section 5.3 correctly says "recorded there". | A reader concludes no other record exists, which is false. Adding "in plan-checksums-in-logs.json" fixes it without using another phase record. | documentation-engineer |
| N2 | minor | docs/evaluation.md:954-956, 963-965 | Items 23, 25 and 26 restate flagged figures without the flag mark: the 6ce56c9 work-order row, "22 of 30" (4.2 flags "8 of 30"), and "four landed, two unresolved" (4.3 flags both). | Section 8 read alone looks verified. Item 29 only partly offsets this. I cannot tell whether items 25 and 26 predate this round. | documentation-engineer |
| N3 | minor | docs/evaluation.md:3 | "Revised after code review; not re-reviewed" | Stale once this verdict is archived. | documentation-engineer |

IGNORED-TEST CONFIRMATION
Confirmed. /tmp/clean-check.log has exactly 24 "!!! IGNORED !!!" lines. Each name matches one of exactly 24 ignore(...) sites under /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/. I found no ignore sites under modules/*/src/test.

| Defect | Log lines | Site (file:line) |
|---|---|---|
| D6 | 201000 | er/ErCommonNameSpec:115 |
| D7 | 520738, 520739 | er/ErNameOrderSpec:122, 132 |
| D8 | 225272-225275 | er/ErUnicodeConfusableSpec:138, 144, 150, 171 |
| D9 | 225232 | er/ErEmptyFieldsSpec:72 |
| D10 | 225226 | er/ErEmptyFieldsSpec:29 |
| T1 | 262223, 262224 | storage/AwsAdaptersAdvSpec:535, 560 |
| T2 | 262210, 262211 | storage/AwsAdaptersAdvSpec:204, 231 |
| T3 | 262208 | storage/AwsAdaptersAdvSpec:177 |
| T4 | 191572 | storage/GraphReadModelAdvSpec:75 |
| T5 | 262226 | storage/AwsAdaptersAdvSpec:585 |
| T6 | 200973 | storage/VectorIngestionAdvSpec:248 |
| T7 | 191555 | services/JobStoreAdvSpec:299 |
| T8 | 225221 | services/ApiResourceAdvSpec:205 |
| T9 | 201118 | rag/BedrockAdvSpec:335 |
| T10 | 6978 | rag/IngestMainAdvSpec:61 |
| T11 | 520757, 520758 | rag/RenderInjectionAdvSpec:279, 292 |
| T12 | 520717 | services/LoggingAndQueueAdvSpec:294 |

The same log shows:
- 8 summary lines totalling 2391 succeeded and 0 failed; only the last (579) has ignored tests (24).
- 6 [success] lines, 18:34:45 to 19:15:01, and no [error] line at line start.
- The markTestRun success line.
- No match for 1b0e893 or "commit <hash>".

This supersedes round 1's unverified item 3, except that I could not confirm /tmp/clean-check.log is the sealed log.

AUTHOR CLAIMS
| Claim | Result |
|---|---|
| F11 finding was incomplete | Confirmed that the stamp file exists. It is a general aws/ stamp and does not name the EMR call; the draft's "AWS observation stamp" wording is accurate. Round 1 was wrong not to find it. |
| F5 was understated | Confirmed: incident report lines 112-139 and gate:42 cover graph-evidence-api and the secret response |
| queue_scenario.py last changed in 6ce56c9 | NOT verified (no git) |
| Log SHA-256 equals clone-state.json | NOT verified; I cannot hash. clone-state.json and clean-clone-outcome.csv agree with each other (4938fe2f...) |
| 5.4 lists one more literal | Confirmed: part_a_other.py:229 |
| 3.3 does not rely on the Phase 15 C report | Confirmed |
| F10 gate prefixes not added | Confirmed. Consistent with the phase-record limit, subject to N1 |

SECRETS AND ACCOUNT IDS
- docs/evaluation.md: no 12-digit number or access key. The one hit is the pattern name "IQoJ" in 5.5.
- Uncompressed files under experiments/evaluation: no account-bearing ARN, ECR or SQS URL, access key or session token value matched.
- Unpacked clean-build log, counts only: no match for access keys, IQoJ values, the security-token header, or account-bearing ARNs, ECR or SQS URLs. This matters because the human gate does not say compressed files were decompressed for its scan.
- I did not search for bare 12-digit numbers in the log or raw files, or inside other gzip or Parquet files. This is not a full credential scan.

COVERAGE (matched this round)
- Section 1: tables against the derived ER CSVs; Wilson values, comparison run and thresholds against raw er/summary.md.
- Section 2: row counts, integrity, duplicates, orphans, support, recomputation (re-added to 1496), structure, paths, test counts, audit summary, S3 table (re-added to 21 tables, 2315 rows, 462 files).
- Section 3: timeline, measures, status and the 9 checksum "yes" rows against the derived perf CSVs.
- Section 4: first-pass, summary counts, hooks, ownership, severity (re-summed by phase), repairs (17 and 32), the 41-row queue breakdown.
- Section 5: clean-clone values, deploy table, plan-checksum sources, pipeline record, EMR listing, redaction wording against gate lines 8 and 11.
- Separation of fact, interpretation and phase record is adequate. The negative results from F2-F5 and F13 are present.

NOT VERIFIED (do not count as matching)
1. Every hash or byte-identity claim: input, load-manifest and Experiment C checksums, the 881e3e99 value, the log SHA-256, the 71 source files, "580 lines pass", and that redaction was the only change.
2. The regeneration replay (exit 0, 580 files, clean status) is an owner claim.
3. Git claims: the queue_scenario.py history, tests unchanged between 1b0e893 and f79d007, what f79d007 contains, and that no script changed in this revision.
4. Graph recomputation from gzip and Parquet inputs; it rests on derived CSVs. "One supporting work for all 50" was only spot-checked in provenance-audit.csv.
5. Bootstrap intervals, the Experiment C worker_outcomes.jsonl values, JUnit XML and raw review_findings.csv were not recomputed. Factory classifications were not checked against the phase reports.
6. Links in the new sections were not all resolved one by one.
7. The previous draft is untracked, so I could not diff it. Regression checking is by re-matching the current text.

TEST RESULTS: not run by reviewer
VALIDATION RESULTS: findings verified against files: N1 yes, N2 yes, N3 yes
ASSUMPTIONS: /tmp/clean-check.log is a faithful unpacking of the sealed log. Its counts and times agree with the derived CSVs.
RISKS:
- The Bedrock cause is the human gate's attribution. experiment-C-report.md:81-84 calls the link inferred and the underlying cause unknown. The draft labels it a phase record, which I accept.
- The defects behind the ignored tests were not re-checked in production code, and the draft says so.
MANUAL ACTION REQUIRED:
- Human: commit the two uncommitted lines in docs/agent-runs/phase-16/human-gate.md with or before the draft, which relies on them.
- Notice from the aws-api MCP server: it is entering end of development; migration guide at https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md. I did not use it. Any .mcp.json change belongs to factory-engineer with human ratification.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human acceptance. N1-N3 are optional small repairs by documentation-engineer; F12 goes to experiment-engineer in the Part B work order.
