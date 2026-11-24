Use the infra-engineer agent to repair two Phase 14 terraform apply failures in us-east-2. Do not run terraform apply or destroy.

1. DynamoDB CreateTable ValidationException "The Tag Value provided is invalid" for tables query_session ("sessions, idempotency markers, jobs and audit items") and graph_read_model ("key-addressed graph read model, reloadable from Spark outputs"). DynamoDB tag values allow only letters, digits, spaces and _ . : / = + - @. Rewrite every free-text tag value in infra/terraform/modules/dynamodb (and any other module using the same pattern) to conform, and add a variable validation so a disallowed character fails at plan time.

2. Neptune CreateDBCluster FreeTierRestrictionError: "The specified backup retention period exceeds the maximum available to free tier customers." Expose backup_retention_period as a variable with default 1 and use it in modules/neptune. Keep deletion protection and encryption unchanged.

Change nothing else. Run terraform fmt -check -recursive and terraform validate in infra/terraform/environments/dev. Report with STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE.
