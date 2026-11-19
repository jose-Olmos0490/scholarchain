---
description: Diagnose a ScholarChain incident and write the incident report via incident-responder
argument-hint: <short symptom description> [env] [trace-or-run-id]
---

# /incident

**Responsible agent:** `incident-responder`. The orchestrator delegates this
work and does not diagnose inline.

**Arguments:** `$ARGUMENTS` (symptom, optional environment, optional trace or
run ID).

## Gates

1. **Read-only first.** Diagnosis uses read-only log, metric, trace, and AWS
   inspection only.
2. **Escalation.** Any action touching data integrity, security, or anything
   destructive stops with `BLOCKED-ON human`.
3. **Recovery.** Recovery is limited to an approved rollback (see
   `/rollback`) or a bounded action the human explicitly approves in this
   session.
4. **No manufactured incidents.** Destructive incidents are never staged in a
   shared course account.

## Work order to pass to incident-responder

> Use the incident-responder agent to diagnose: `<symptom>` in `<env>`
> (trace/run `<id>`). Collect the detection evidence. Diagnose with read-only
> inspection. Do not change code, data, schemas, security, or infrastructure.
> Write `docs/incidents/<incident-id>.md` with: incident ID, start and end
> time, detection source, user-visible impact, trace and run IDs, timeline,
> root cause, recovery action, data-integrity assessment, follow-up owner, and
> the preventive test or control. Return the standard report with follow-ups
> grouped by owning agent.

## Exit gate

The human reviews the incident report. The orchestrator routes each follow-up
to its owner from `docs/agents.md`.
