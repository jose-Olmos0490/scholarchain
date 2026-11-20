# Phase 03 repair 02: review findings
Trigger: code-reviewer REQUEST_CHANGES (review-report.md, review 1).
Human decisions:
- Config: missing conf/<env>.conf = empty layer, AND a non-secret versioned dev.conf is shipped.
- Sample cap = 200,000 bytes; OpenAlex works excerpt trimmed.
- OpenAlex abstract_inverted_index stripped from samples (same rule as Crossref).
- Finding 8 exception accepted: crossref.size.observed.local_bytes has no official URL.
- v1 schemas kept (never released); every claim in a final answer must cite evidence; a non-declining answer has at least one claim.
- Researcher ID order: ORCID > DBLP pid > OpenAlex > sc-researcher-v1.
- DBLP subset: article and inproceedings, 2000–2025, configurable cap; config may narrow record types but not widen them.
Owners repaired: dataset-engineer (datasets/**), domain-engineer (modules/core/**).
Rerun evidence: sbt check PASS; validate-manifest PASS; validate-samples PASS; final code-reviewer verdict APPROVE.
