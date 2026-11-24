# Phase 14b Human Gate — Storage loader entry point

Reviewed: storage-engineer report, code-reviewer REQUEST_CHANGES (2 major, 5 minor), repair-01, code-reviewer re-review APPROVE.
Decision: ACCEPTED.
Why this phase exists: Phase 14 deployment found no runnable loader (GraphLoadWorkflow had no caller), so query-api could not be given a serving run.
Manual changes: none to code.
Deferred (minor, advisory): finding 3 remainder (invalid ledger-sequence env var, live-mode argument errors, new SUCCEEDED on changed detail), N1 conflict test stored-state assertion, N2 metric-cache failure-path test, N3 dry-run overcount check, N4 suffix-lookup ambiguity (mitigated by SHA-256 check).
Remaining risk: live load path has no automated test outside profile-gated AWS suites; Neptune bulk load is not triggered by LoadMain.
Accepted commit hash: see commit.txt
