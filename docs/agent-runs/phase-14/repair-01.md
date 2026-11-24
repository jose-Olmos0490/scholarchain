STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: Repair two Phase 14 apply failures in us-east-2. (1) DynamoDB invalid tag values. (2) Neptune FreeTierRestrictionError on backup retention. No apply or destroy.
FILES CREATED: none
FILES MODIFIED:
- /home/olmos/scholarchain/infra/terraform/modules/dynamodb/main.tf
- /home/olmos/scholarchain/infra/terraform/modules/neptune/main.tf
- /home/olmos/scholarchain/infra/terraform/modules/neptune/variables.tf
Changes:
- dynamodb: the query_session note is now "sessions and idempotency markers and jobs and audit items". The graph_read_model note is now "key-addressed graph read model: reloadable from Spark outputs". The commas are gone. I added a lifecycle precondition on aws_dynamodb_table.this. It checks each note, which becomes the Purpose tag value, against ^[A-Za-z0-9 _.:/=+@-]*$, so a disallowed character fails at plan time. The other table notes were already conforming.
- neptune: variable backup_retention_days is renamed to backup_retention_period, with default 1, a description and a validation of 1 to 35. The cluster's backup_retention_period now uses it. Nothing else referenced the old name. Deletion protection, encryption and the final snapshot logic are unchanged.
COMMANDS RUN: grep and sed inspection; a python3 edit script; sed rename; terraform fmt -check -recursive (in infra/terraform); terraform validate (in environments/dev); git status --short
TEST RESULTS: not applicable (Terraform only). I did not run sbt check because no source changed.
VALIDATION RESULTS:
- fmt -check -recursive: exit 0, no diffs.
- validate (environments/dev): "Success! The configuration is valid."
- plan, bash -n, shellcheck and the security scan: not run. You scoped this repair to fmt and validate, and no scripts changed.
ASSUMPTIONS:
- Free-text tag values exist only in the dynamodb module (Purpose = note). The other tags in modules are Name and Tier values built from name_prefix and fixed strings, which are within the allowed set. The provider default_tags (course, project, environment, owner, expiration) are unchanged. owner and expiration come from variables and must also use only allowed characters.
- The precondition is the plan-time guard. The notes are a local, not a variable, and a local cannot carry a validation block.
RISKS:
- Neptune: default retention drops from 7 to 1 day. Persistent environments can set backup_retention_period higher through the module input once the account is off the free tier. The environment does not pass it today.
- If any table or the Neptune cluster was partially created by the failed apply, state may differ. Review the next plan. The DynamoDB tag fix changes only the tag value, so any table that was created would update in place.
MANUAL ACTION REQUIRED: human plan review and human-only apply. Re-run the plan with scripts/plan.sh, verify the checksum, then apply.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer and human plan sign-off
