# Phase 16 human gate

## Decisions (2026-11-25 evening)
- Dev environment window extended past the Phase 12 48-hour limit to Sunday 2026-11-29 for Part B, the smoke test and the demo; cleanup runs after the demo. Month-to-date cost checked: $8.17 used, covered by credits (net $0.00).
- Accepted: experiment-engineer used s3api get-object (read-only) after the guard blocked aws s3 cp.
- Accepted: guard blocks on protected-path shell writes; the agent used the scratchpad or the Edit tool instead.
- Gold-set top-up skipped; 92 pairs (4 DIFFERENT), so precision 1.000 is weak evidence. Disclosed as a threat to validity.
- Account ID redacted by the human from aws/ecs-services.json and repro/deploy-records.json after collection; their two SHA256SUMS lines updated; original checksums kept in docs/agent-runs/phase-16/SHA256SUMS.pre-redaction.
- Clean-clone check ran at commit 1b0e893.
