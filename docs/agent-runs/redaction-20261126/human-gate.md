# Human gate: history redaction (2026-11-26)

- What: git filter-repo --replace-text, run by the human (not an agent) on a fresh clone.
- Replaced in every commit: AWS account ID -> <ACCOUNT_ID>, alert email -> <ALERT_EMAIL>, operator IP -> <OPERATOR_IP>, dev ALB DNS -> <DEV_ALB_DNS>, Neptune endpoint -> <NEPTUNE_ENDPOINT>. The rules file is kept outside the repository.
- Why: the repository is public. No credentials were found: no access keys, secret keys, session-token values, API tokens or private keys (only AWS's documented example key in a test).
- Verified: 0 matches for each value across all commits after the rewrite.
- Consequence: phases 00-13 keep their hashes. The 25 commits from phase 14 onward have new hashes; sha-map.csv maps old -> new. Hashes inside commit.txt files and reports from phases 14, 14b, 15 and 16 refer to the old hashes; resolve them through sha-map.csv. The final-submission tag was rewritten too.
- Deviation: the spec says not to rewrite public history. This was done once, by the human, before the extended deadline, to remove personal and account identifiers.
- Raw-data checksums: no SHA256SUMS file covers a redacted file.
