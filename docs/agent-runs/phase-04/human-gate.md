# Phase 04 human gate
Reviewed: work order A-D reports, repairs 01-08, human-escalation-01, test-report (attempt 5: sbt check exit 0, adversarial 114/114), review-report (APPROVE, no blocking findings), determinism diff, counters and rejects.
Decision: ACCEPTED
Manual changes: none to source. Human escalation ended the open-ended adversarial loop after repair 08 (human-escalation-01.md). Human did not rerun sbt check (about 32 min, F9); relied on test-engineer attempt 5 output on the same tree.
Feature version bumped to hadoop-blocking-features-v2 during repair 08; match-candidate IDs differ from pre-repair runs, none of which were committed or consumed.
Scope note: repair 08 also folds non-breaking/invisible spacing to ordinary space (beyond test-engineer's request). Accepted.
Process deviation (F8): three edits by hadoop-engineer and domain-engineer were made via Bash/Python instead of Edit/Write, bypassing the guard and the formatter hook. Format check passed afterward. Follow-up: factory-engineer guard hardening before Phase 11.
Known risks: (1) a DOI with a leading/trailing zero-width character is treated as a different DOI; (2) a Crossref record whose DOI ends in a non-breaking space is rejected whole (visible MISSING_KEY reject). Both go to DOI normalization in Phase 5.
Not yet tested at scale: bzip2 multi-split DBLP (first EMR run must compare record count with a single-split count); possible large reducer group for a prolific ORCID; XML with an unrecognized charset declaration falls back to UTF-8 and rejects non-ASCII records.
Follow-ups: F9 slow sbt check (tests run twice) -> build-engineer before Phase 5.
Accepted commit: d56fbcba630f059a3ad45f0e3d69e30f96aef0fa

## Correction (recorded 2026-11-21)
The original commit.txt recorded d56fbcb, which is a Phase 3 commit; the Phase 4 code was never committed at gate time. The Phase 4 work was committed late in the commit now recorded in commit.txt, unchanged except: build.sbt also includes the entityResolution dependency on adversarialTests added during Phase 5 repair F5.
