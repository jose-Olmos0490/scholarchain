STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Review the uncommitted diff to infra/terraform/modules/compute/main.tf (ALB listener rule chunking, 5 values max) against /tmp/phase14/repair-03.md.
VERDICT: APPROVE
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools only)
FINDINGS:
| # | Severity | File:line | Concrete failure input | Expected impact | Owning agent |
| 1 | minor | /tmp/phase14/repair-03.md:12, :31 | The report says dev has graph-evidence-api at priority 10 and query-api at 20. environments/dev/main.tf:280-289 shows the reverse: query-api has 6 paths and priority 10, and graph-evidence-api has 2 paths and priority 20. | The report is wrong, but the code is right. The report's RISKS line ("query-api now gets 20 and up") is false. Actual priorities are query-api-0=10, query-api-1=11 and graph-evidence-api-0=20. Anyone reading the report could review the plan against the wrong numbers. Correct the report before it is archived. | infra-engineer (report) |
| 2 | minor | modules/compute/main.tf:35-38 | The query-api moved block is probably dead. Its rule never got created, because the apply failed validation. | It does no harm. Terraform ignores a moved block whose source is not in state. It could only matter if a partial rule exists in state. In that case it would be moved to query-api-0 and then updated in place. A human can drop the block after the apply. | infra-engineer |
| 3 | minor | modules/compute/main.tf:16-18 | There is no validation that a service has at most 10 chunks (50 paths) or that service priorities are spaced at least 10 apart. | No collision exists today. Priorities in dev are 10, 11 and 20, and the embedding worker is excluded by the `alb_paths` filter. The report disclosed this. A guard would be nice to have, and the work order said to change nothing else. | infra-engineer (optional, future) |
FINDINGS VERIFIED against files: yes, all three.

Verification notes:
- Chunking: The code at main.tf:20-27 is correct. `length/5` is a float and `ceil` rounds it up, so 6 paths give 2 chunks and 2 paths give 1. `slice(list, i*5, min((i+1)*5, len))` has no off-by-one. `alb_services` already filters out empty path lists. `merge([...]...)` on an empty list returns `{}`, so a configuration with no routed services is safe.
- Priorities: The only `aws_lb_listener_rule` in the repo is in the compute module (main.tf:244). A grep of `infra` for `priority` and `alb_priority` found no other rule on this listener. The listener's default action is a fixed-response 404. Priorities are unique across all services (10, 11, 20). Query-before-graph ordering is kept.
- graph-evidence-api: It has 2 paths, so one chunk. Its priority stays 20 and its conditions are unchanged. The target group is the same, now looked up through `each.value.service`. The moved block (main.tf:30-33) therefore gives a pure address move with no replacement.
- query-api: Its rule should be a new create at 10 and 11. It no longer carries the 6-value condition that caused the ValidationError.
- Scope: The diff touches only main.tf, in the locals, the two moved blocks and the rule resource. Other `alb_paths` uses (`aws_ecs_service`, lines 275-306) read the service map, not the rule, so they are unaffected. `depends_on` at line 317 still points at the resource and works. Only infra-engineer territory is touched, and the ledger is untouched.
- Report vs diff: The changes, the moved blocks and the fmt and validate claims match the diff. I could not check the fmt and validate claims myself. The only mismatch is finding 1.

TEST RESULTS: not run by reviewer
VALIDATION RESULTS: findings verified against files: yes. fmt and validate output is from the infra-engineer's report and was not re-run.
ASSUMPTIONS: The diff pasted in the work order is complete for infra/terraform. The graph-evidence-api rule is in state, as the work order says.
RISKS: The Terraform state was not inspected. If a partially created query-api rule exists, the plan could show a conflict at priority 10. The human plan review should confirm that graph-evidence-api shows as "moved" with no destroy, and that query-api-0 and query-api-1 show as creates.
MANUAL ACTION REQUIRED: A human reviews `terraform plan` for dev and then applies it. The infra-engineer corrects the priority numbers in the report.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human plan sign-off, then human-only apply. Finding 1 should be corrected before the report is archived.
