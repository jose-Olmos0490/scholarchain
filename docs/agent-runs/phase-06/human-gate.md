# Phase 06 human gate

Reviewed: Part A and Part B agent reports, test report, review reports (round 1 and round 2), repair-01.md, repair-02.md, final run manifest (run_id 501e3dab6a5521b205d0d744f2c98aecda7fef1c4ccfe18c10344829663309ed).
Independent checks run by me: KnownGraphSpec 9/9 and PipelineIntegrationSpec 4/4 (including determinism); Parquet check with DuckDB showing W_r == T_r with 0 mismatches over 178 rows and all output tables populated; fixture values compared line by line against my hand-computed oracle (P, C, T, A, M, W, edges, components, 6/9 fraction, 2010-2015 window).
Decision: ACCEPTED

Human decisions:
- repair-01: identity clusters use ACCEPTED links only; no merge of mentions on the same work; at most one ORCID and one OpenAlex author ID per cluster; violating clusters split to fallback IDs and recorded as CONFLICT; chain-case test required.
- repair-02: LPA ID-order dependence accepted as a stated limitation; C_r and W_r exclude coauthors from oversize-team works (P_r, T_r, M_r include them); year-less-work exception to W_r == T_r accepted; maxClusterSize counts distinct identity anchors, not mentions (value 50 for bounded runs).
- Part B scope: betweenness not implemented; bridge measures are crossCommunityDegree, communitiesConnected, participationCoefficient; effective diameter is one seeded sampled estimate for the largest component.

Manual changes: none to source code. Human deleted invalid run d6487046 and superseded runs 308a4868 and 501e3dab; placed run-manifest.json from final run 501e3dab6a5521b205d0d744f2c98aecda7fef1c4ccfe18c10344829663309ed.

Remaining risks:
- Real bounded sample is degenerate (24 single-paper teams); Part B logic is validated by fixtures and the independent oracle only. A larger bounded sample is planned before Phase 15.
- Bounded EMR Spark run deferred to Phases 13 and 15 (no cluster before Phase 12).
- Driver-side checksum streaming (review finding 8) must be fixed before any non-bounded run.
- Deferred nits: findings 11, 12, 14, 15 (15 is a requirement for run-bounded-pipeline.sh in Phase 13).
- ExactIdentifierJob failed once during concurrent sbt runs and passed on rerun; to be rechecked in an isolated run.
- sbt check takes about 70 minutes; build speed-up planned.
