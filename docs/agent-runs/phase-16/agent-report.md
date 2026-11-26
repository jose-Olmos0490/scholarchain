STATUS: COMPLETE (draft written; one finding needs a human decision before the factory section can be final)
OWNER: documentation-engineer
WORK ORDER: Phase 16b, Part A write-up (docs/evaluation.md)
FILES CREATED: /home/olmos/scholarchain/docs/evaluation.md (untracked, not committed)
FILES MODIFIED: none outside that file

KEY FINDINGS
1. /home/olmos/scholarchain/experiments/evaluation/labels/factory-counts.csv is byte-identical to derived/partA-1b0e893-20261126T014300Z/factory/needs-human-confirmation.csv (SHA-256 881e3e99... for both).
   - It has 41 rows and no verdict, correction or count columns.
   - The f79d007 subject calls it a "human factory-count ruling", but phase-16/human-gate.md does not mention factory counts.
   - So no human figure differs from an automated one, and what the ruling covers cannot be determined. The draft reports the automated figures, keeps every flag (marked with a dagger), and does not assume the rows were accepted.
2. The gate's "92 pairs, 4 DIFFERENT" matches the outputs. In addition, 3 of the 4 DIFFERENT pairs are UNRESOLVED yet count as true negatives.
3. Experiment C: FAILED, NO_VECTORS_WRITTEN, with 90600 EMBEDDING_FAILED items. All 300 messages were still acknowledged, with 0 retried and 0 dead-lettered.
4. Deployed graph data: communities equal components in every window, there are no cross-community edges, and all 28 paths are FOUND with maximum length 1.
5. The clean-clone log shows 24 ignored tests. The outputs do not say why.

CONTRADICTIONS BETWEEN OUTPUTS
- Drain time: 1490.0 s from observations vs 1476.7 s from the runner milestone.
- Scale to zero: desired 0 first seen at 1899.3 s after first send vs scaledToZeroAfterSeconds 1868.2. The reference points may differ; the outputs do not say.
- Bootstrap bounds differ for identical pair sets (researcher/PROBABILISTIC vs all/PROBABILISTIC; work vs work/EXACT_ID). Per-group seeding explains this.
- The factory ruling issue and the acknowledged-despite-errors behaviour above.

All are listed in section 9 of the draft.

COMMANDS RUN (observed results)
- Replay of regeneration: `git clone --no-hardlinks` of the repository into the scratchpad (HEAD f79d007), then `experiments/evaluation/regenerate.sh partA-1b0e893-20261126T014300Z`. Exit 0, "verified 580 raw files", and `git status --porcelain` in the clone printed nothing. Clone: /tmp/claude-1000/-home-olmos-scholarchain/85f569e1-10dc-4674-868c-79b453aa84a0/scratchpad/evalclone
- `sha256sum -c SHA256SUMS` in the raw run directory: 580 of 580 OK; 580 lines and 580 files.
- `diff` of the sorted SHA256SUMS against SHA256SUMS.pre-redaction: only aws/ecs-services.json and repro/deploy-records.json differ.
- `sha256sum -c --quiet factory/sources.sha256`: exit 0 (71 sources).
- `sha256sum` of factory-counts.csv, needs-human-confirmation.csv and the gold set: the first two are identical; the gold set matches config.json.
- Read-only inspection of sources and outputs (cat, sed, grep, zcat, ls, git log/show/ls-files, and small `python3 -I` tallies).
- `python3 -I <scratchpad>/check.py` (link, path and number check): results below.
- One compound read command was blocked by guard-dangerous.sh ("modifying or deleting raw experiment results"). It was meant only to read, including a 12-digit pattern scan of raw files. I did not retry that scan; I read the files I needed separately. Raw account-ID redaction is therefore not independently verified by me.
- A final `.claude/hooks/validate-agent-files.sh --help` call had its output discarded. It is not a validation result.
- One python3 edit script was used for wording fixes in docs/evaluation.md.

NOT REPLAYED
The sbt known-graph tests, the ER evaluator, the factory extraction, the clean-clone build, the pipeline runs and all AWS or S3 reads. No AWS calls were made. The draft describes these from sealed outputs.

TEST RESULTS: `sbt check` not run (documentation only).

VALIDATION RESULTS
- Links: 57 links (54 unique), none missing and none untracked.
- No 12-digit account-ID-like strings in the document.
- Automated cross-check against the CSVs, no mismatches:
  - 75 entity-resolution interval cells;
  - the referential-integrity, duplicate and orphan tables;
  - rows for known-graph tests, structure, edge support, Experiment C timeline, first-pass by agent and review findings by phase.
- Other figures (performance measures, factory event totals, repair and hook tables, reproducibility tables) were checked by reading the source outputs, not by script.
- git status shows only `?? docs/evaluation.md`.

UNVERIFIED OR ILLUSTRATIVE STATEMENTS
- No illustrative examples are used.
- The cost estimate (about USD 1.5 against a cap of 1.0, prices unverified) comes from the Phase 15 report via c-measures.csv, not from raw data.
- The clean-clone commit link rests on the observed clone HEAD and the human gate; the log does not name a commit.
- The descriptions of the ignored tests come from their names only.
- Paragraphs headed "Interpretation" are my reading, not measurements.

ASSUMPTIONS
- The factory raw CSVs and the Phase 5 summary.md are inside the allowed evidence, since they are under the Part A raw directory.
- No AWS service limits are quoted, so no AWS documentation links were needed.

RISKS
- Replacing the factory ruling status later will change section 4, items 19 and 24 of section 8, and row 1 of section 9.
- The draft names recorded security-relevant events (Terraform state content printed, plaintext HTTP allowed) without any values. Check that this is acceptable for the audience.

MANUAL ACTION REQUIRED
- Human: state what the factory-count ruling decides (row by row or blanket), or supply the intended file if the wrong one was committed.
- Note passed on from the aws-api MCP server's own instructions: that server is entering end of development and recommends migrating to the AWS MCP Server (https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md). Any change to .mcp.json belongs to factory-engineer. I did not use that server.

BLOCKED-ON OWNER: none for the draft; human architect for the ruling.
BLOCKED-ON EVIDENCE: factory-count verdicts; Part B outputs for retrieval and answer quality (both headed "Pending Part B", with no numbers).
NEXT GATE: evidence review and code-reviewer
