---
description: Roll a ScholarChain environment back to a prior approved revision via incident-responder, with bounded autonomy
argument-hint: <env> <target-revision-or-sha> <reason>
---

# /rollback

**Responsible agent:** `incident-responder`. A rollback inside an active
`/deploy` work order, after a failed smoke test, is handled by
`deploy-engineer` within that work order. This command covers every other
rollback.

**Arguments:** `$ARGUMENTS` (expected: environment, target revision or commit
SHA, reason).

## Gates

1. **Human approval** of this rollback in the current session.
2. **Approved environment.** The environment is in the human-set
   `SCHOLARCHAIN_APPROVED_ENVS`.
3. **Prior approved target.** The target revision was itself previously
   approved and deployed (it is recorded under `reports/deployments/`).
4. **Approved script only.** Execution is `scripts/rollback.sh --env <env>`.
   No Terraform, no editing, no direct AWS mutation.

## Limits on autonomous action

- Allowed without further approval: read-only diagnosis, one execution of
  `scripts/rollback.sh` to the named target, and `scripts/smoke-test.sh`
  afterwards.
- Never allowed: schema changes, data deletion or repair, IAM, KMS, or
  security changes, scale changes, Terraform apply or destroy, cleanup,
  repeated rollback attempts to other targets. For any of these, stop and
  return `BLOCKED-ON human`.

## Work order to pass to incident-responder

> Use the incident-responder agent to roll environment `<env>` back to
> `<target>` because `<reason>`. Capture the detection evidence first. Run
> `scripts/rollback.sh --env <env>` exactly once with the target, then
> `scripts/smoke-test.sh --env <env>`. Do not change data, schemas, security,
> or infrastructure. Write `docs/incidents/<incident-id>.md` with the full
> incident fields. Return the standard report.

## Exit gate

The rollback is accepted when the post-rollback smoke test passes, the
incident report is complete, and the human accepts it. Root-cause fixes are
routed to their owners.
