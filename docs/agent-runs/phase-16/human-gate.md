# Phase 16 human gate

## Decisions (2026-11-25 evening)
- Dev environment window extended past the Phase 12 48-hour limit to Sunday 2026-11-29 for Part B, the smoke test and the demo; cleanup runs after the demo. Month-to-date cost checked: $8.17 used, covered by credits (net $0.00).
- Accepted: experiment-engineer used s3api get-object (read-only) after the guard blocked aws s3 cp.
- Accepted: guard blocks on protected-path shell writes; the agent used the scratchpad or the Edit tool instead.
- Gold-set top-up skipped; 92 pairs (4 DIFFERENT), so precision 1.000 is weak evidence. Disclosed as a threat to validity.
- Account ID redacted by the human from aws/ecs-services.json and repro/deploy-records.json after collection; their two SHA256SUMS lines updated; original checksums kept in docs/agent-runs/phase-16/SHA256SUMS.pre-redaction.
- Clean-clone check ran at commit 1b0e893.
- Factory counts: experiments/evaluation/labels/factory-counts.csv is an unedited copy of the agent's needs-confirmation list, not a human ruling (commit f79d007's message overstates this). The human accepts the automated counts as reported; flagged rows remain unverified and are reported as such.
- Human scan (2026-11-25 ~19:50) of experiments/evaluation for the AWS account ID and credential patterns (AKIA/ASIA keys, IQoJ, x-amz-security-token): account ID found in 2 text files and redacted, 0 binary matches, 0 credentials; rescan clean.
- Redaction scan scope: uncompressed text files only. Compressed raw files were not decompressed or scanned for bare 12-digit IDs; the reviewer's pattern search of the unpacked build log found no keys, tokens or account-bearing ARNs.
- Part A write-up: code-reviewer REQUEST_CHANGES (F1–F14), repaired by documentation-engineer, re-review APPROVE. Minor N1–N3 deferred to the Part B write-up pass.
- Guard blocks accepted as false positives (no workaround used beyond the Edit tool): zcat of a raw log, the doc agent's batch edit script, orchestrator writing review-report-02.md. All three go to factory-engineer after submission.
- Deferred: F12 (derived tables typed by hand into regenerate.sh) goes into the Part B work order for experiment-engineer.
