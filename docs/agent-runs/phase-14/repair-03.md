STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: Repair module.compute aws_lb_listener_rule.this so no path-pattern condition has more than 5 values (apply error for query-api).
FILES CREATED: none
FILES MODIFIED: /home/olmos/scholarchain/infra/terraform/modules/compute/main.tf
COMMANDS RUN: grep of the compute module and the dev environment for alb_paths and alb_priority; a python3 script to edit main.tf; `terraform fmt -check -recursive` in infra/terraform; `terraform validate` in infra/terraform/environments/dev; `git diff --stat`. I did not run `terraform init`, because validate worked without it. I did not run plan, apply or destroy.
TEST RESULTS: n/a (Terraform change)
VALIDATION RESULTS: fmt -check -recursive passed (exit 0). validate in environments/dev: "Success! The configuration is valid." I did not run plan, bash -n, shellcheck or a security scan; no scripts changed and no plan was requested.
Changes:
- New local `alb_rule_chunks` splits each service's `alb_paths` into chunks of 5 with `slice`. Keys are "<service>-<chunk index>". Each value holds service, paths and priority.
- The rule now uses `for_each = local.has_listener ? local.alb_rule_chunks : {}`. It takes `priority = each.value.priority` and `values = each.value.paths`. The target group is looked up with `aws_lb_target_group.this[each.value.service]`.
Priority scheme: priority = the service's `alb_priority` + chunk index. Current dev values are graph-evidence-api 10 and query-api 20 (the embedding worker has no routes). Services are 10 apart, so up to 10 chunks (50 paths) per service cannot collide. The existing order is kept.
Moved blocks (in modules/compute/main.tf):
```
moved {
  from = aws_lb_listener_rule.this["graph-evidence-api"]
  to   = aws_lb_listener_rule.this["graph-evidence-api-0"]
}
moved {
  from = aws_lb_listener_rule.this["query-api"]
  to   = aws_lb_listener_rule.this["query-api-0"]
}
```
I could not tell from the code which rules are in state. The query-api block is harmless if its rule was never created. If graph-evidence-api has 5 or fewer paths, its priority (10) and conditions are unchanged, so there should be no replacement.
ASSUMPTIONS:
- graph-evidence-api's rule exists in state and query-api's creation failed. This is from the work order, not checked against state.
- A service has at most 10 chunks. There is no validation enforcing this.
RISKS:
- Whether query-api's rule exists in state is unconfirmed. If the failed apply left a partial rule, the plan may show a conflict; check it.
- A service with more than 50 paths, or a future `alb_priority` gap under 10, could collide on priority. I added no validation, since you said to change nothing else.
- query-api now gets rule priorities 20 and up (20 and 21 if it has 6 to 10 paths). Check the plan for any other listener rule already using one of those priorities.
MANUAL ACTION REQUIRED: run `scripts/plan.sh` or `terraform plan` for dev and confirm that graph-evidence-api shows as moved with no destroy and recreate. Human plan review, then a human-only apply.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer, then human plan sign-off.
