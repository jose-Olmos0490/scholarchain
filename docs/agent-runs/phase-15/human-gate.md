# Phase 15 human gate (in progress)

## 2026-11-25 05:20 — experiment tooling accepted with open findings
- Reviewed: review-report.md and review-report-02.md (code-reviewer verdict REQUEST_CHANGES, no blocker, no unguarded AWS write).
- Accepted with known risk: finding 3 partial (run-experiment.sh does not enforce approval for A/B; A and B stay unapproved and will not be run), finding 5 partial (F duplicate case), finding 19 partial (Python tests not in sbt check / check.sh; routed to infra-engineer).
- Decision 1 (disjoint C/F input) not yet met; only experiment C is approved.
- Guard blocked agent-report-02.md and review-report-02.md writes (suffixed names unowned in docs/agents.md); human copied them from the orchestrator scratchpad.
- Manual changes: none to code.
