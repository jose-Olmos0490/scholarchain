---
description: Deploy an approved ScholarChain commit and plan to a human-approved environment via deploy-engineer
argument-hint: <env> <commit-sha> <plan-sha256>
---

# /deploy

**Responsible agent:** `deploy-engineer`. The orchestrator delegates this
work and does not perform any deployment step inline.

**Arguments:** `$ARGUMENTS` (expected: environment, commit SHA, approved
Terraform plan checksum).

## Gates. All must hold before delegation, or stop and report which failed.

1. **Human approval.** The human architect explicitly approved this deployment
   in the current session: target account, Region, environment, and cost
   bounds.
2. **Approved environment.** The environment is listed in the human-set
   `SCHOLARCHAIN_APPROVED_ENVS`. Agents never set this variable.
3. **Approved plan.** The plan checksum matches the plan that the human
   reviewed. The plan has no unexplained destroy or replacement of stateful
   resources.
4. **Infrastructure applied by the human.** Any Terraform apply has already
   been executed by the human (Phase 14a). Agents never run `terraform apply`,
   directly or through wrappers.
5. **Clean tree.** `git status --porcelain` is empty, and HEAD equals the
   approved commit.
6. **Code review.** The deployed commit's phases have `APPROVE` verdicts.

## Work order to pass to deploy-engineer

> Use the deploy-engineer agent to deploy commit `<commit-sha>` with approved
> plan checksum `<plan-sha256>` to environment `<env>`. Refuse a dirty tree.
> Record the commit SHA, plan checksum, account, Region, and environment. Run
> only `scripts/deploy.sh --env <env>`, then `scripts/smoke-test.sh --env
> <env>`. Verify image digests and task revisions. On smoke failure, collect
> evidence and run `scripts/rollback.sh --env <env>` per the approved policy.
> Do not edit Terraform, scripts, or source. Do not perform destructive data
> repair. Write the report under `reports/deployments/`. Return the standard
> report.

## Exit gate

The deployment is accepted only when:

- services are healthy and ready
- digests match the commit
- the smoke query returns an evidence-backed response
- no critical alarm is active
- the human accepts the deployment report

Failures route to `incident-responder` (diagnosis) and `infra-engineer`
(script defects) through the orchestrator.
