# ScholarChain evaluation

**DRAFT. Revised after code review; not re-reviewed. Part A (offline) only.**
Retrieval quality and answer quality are pending Part B.

## 0. Scope, evidence and reading rules

| Item | Value |
|---|---|
| Evaluation run | `partA-1b0e893-20261126T014300Z` |
| Commit recorded by the run | `1b0e893` |
| Commit that holds the evidence | `f79d007` |
| Environment | dev, us-east-2 |
| Raw data | [experiments/evaluation/raw/partA-1b0e893-20261126T014300Z/](../experiments/evaluation/raw/partA-1b0e893-20261126T014300Z/) |
| Derived tables | [experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/) |
| Human-owned file | [factory-counts.csv](../experiments/evaluation/labels/factory-counts.csv) |
| Run configuration and commands | [config.json](../experiments/evaluation/raw/partA-1b0e893-20261126T014300Z/config.json), [commands.json](../experiments/evaluation/raw/partA-1b0e893-20261126T014300Z/commands.json) |

Tables and plain statements report what the outputs and records show.
Paragraphs headed **Interpretation** are the author's reading, not
measurements. Paragraphs headed **Not covered** list what was not checked.
Decisions attributed to the "human gate" come from
[phase-16/human-gate.md](agent-runs/phase-16/human-gate.md). Section 8
collects the negative results and limitations known to this document.

Statements marked **Phase record** come from project records outside the Part A
outputs (earlier phase gates and reports, the incident report, module
limitation files and build settings). They are not Part A measurements, and
Part A did not re-test them.

The working tree was not clean when the run was sealed: `config.json` records
`experiments/evaluation/` as untracked. The evaluation scripts therefore are
not part of commit `1b0e893`. They were committed in `f79d007`.

## 1. Entity resolution

Method: the Phase 5 evaluator scored decision run `run:74494228...` (policy
`er-v1`, revision `er-v1-r2`, features `er-features-v2`; accept 0.8, reject
0.45, LLM band [0.6, 0.8)) against the human gold set. Only `ACCEPTED` counts
as a predicted match. `REJECTED`, `UNRESOLVED` and `CONFLICT` all count as
"not merged". So an unresolved SAME pair is a false negative, and an
unresolved DIFFERENT pair is a **true negative**. Source:
[er/summary.md](../experiments/evaluation/raw/partA-1b0e893-20261126T014300Z/er/summary.md).

### 1.1 Gold-set size

Source: [er/gold-set-size.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/er/gold-set-size.csv).

| Measure | Value |
|---|---:|
| Gold pairs | 92 |
| Target | 200 |
| Shortfall | 108 |
| SAME | 88 |
| DIFFERENT | 4 |
| UNSURE | 0 |

The human gate states 92 pairs with 4 DIFFERENT. This matches the outputs. The
human gate also records that the gold-set top-up was skipped and that
precision 1.000 is weak evidence.

The Phase 5 summary adds that the 92 pairs come from the r1 review-queue
strata, not from a random sample of candidate pairs, so the rates do not
generalize to the full population.

### 1.2 Confusion matrix

Source: [er/confusion-matrix.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/er/confusion-matrix.csv).

| Group | Human label | ACCEPTED | REJECTED | UNRESOLVED | CONFLICT |
|---|---|---:|---:|---:|---:|
| overall | SAME | 77 | 0 | 8 | 3 |
| overall | DIFFERENT | 0 | 1 | 3 | 0 |
| researcher | SAME | 59 | 0 | 8 | 1 |
| researcher | DIFFERENT | 0 | 0 | 3 | 0 |
| work | SAME | 18 | 0 | 0 | 2 |
| work | DIFFERENT | 0 | 1 | 0 | 0 |

### 1.3 Metrics with bootstrap 95% intervals

1000 resamples, seed `20261125`, percentile intervals. The generator is
seeded per group (`20261125:<group>`). "degenerate" means the lower and upper
bounds are equal. Sources:
[er/metrics.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/er/metrics.csv),
[er/bootstrap-ci.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/er/bootstrap-ci.csv),
plot [er/metrics-ci.svg](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/er/metrics-ci.svg).

| Metric | overall (92) | researcher (71) | work (21) |
|---|---|---|---|
| TP / FP / FN / TN | 77 / 0 / 11 / 4 | 59 / 0 / 9 / 3 | 18 / 0 / 2 / 1 |
| Precision | 1.0000, degenerate | 1.0000, degenerate | 1.0000, degenerate |
| Recall | 0.8750 [0.8068, 0.9342] | 0.8676 [0.7746, 0.9420] | 0.9000 [0.7619, 1.0000] |
| F1 | 0.9333 [0.8931, 0.9660] | 0.9291 [0.8730, 0.9701] | 0.9474 [0.8649, 1.0000] |
| Specificity | 1.0000, degenerate (991 of 1000 resamples defined) | 1.0000, degenerate (954) | 1.0000, degenerate (641) |
| Coverage (ACCEPTED or REJECTED) | 0.8478 [0.7826, 0.9130] | 0.8310 [0.7324, 0.9155] | 0.9048 [0.7619, 1.0000] |
| Unresolved rate | 0.1196 [0.0543, 0.1848] | 0.1549 [0.0842, 0.2394] | 0.0000, degenerate |
| Conflict rate | 0.0326 [0.0000, 0.0655] | 0.0141 [0.0000, 0.0423] | 0.0952 [0.0000, 0.2381] |

The Phase 5 tool reports Wilson intervals, which do not collapse at 1.000
([er/summary.md](../experiments/evaluation/raw/partA-1b0e893-20261126T014300Z/er/summary.md)):

| Kind | Precision (Wilson 95%) | Specificity (Wilson 95%) |
|---|---|---|
| researcher | 59/59, [0.939, 1.000] | 3/3, [0.439, 1.000] |
| work | 18/18, [0.824, 1.000] | 1/1, [0.207, 1.000] |

### 1.4 By match path

Intervals are bootstrap 95%. `n/a` means no defined value.

| Kind / path | Pairs (SAME / DIFF) | TP/FP/FN/TN | Precision | Recall | F1 | Coverage | Unresolved |
|---|---|---|---|---|---|---|---|
| researcher / EXACT_ID | 11 (11/0) | 10/0/1/0 | 1.0000 | 0.9091 [0.7273, 1.0000] | 0.9524 [0.8421, 1.0000] | 0.9091 [0.7273, 1.0000] | 0.0000 |
| researcher / DETERMINISTIC_BIBLIOGRAPHIC | 49 (49/0) | 41/0/8/0 | 1.0000 | 0.8367 [0.7347, 0.9388] | 0.9111 [0.8471, 0.9684] | 0.8367 [0.7347, 0.9388] | 0.1633 [0.0612, 0.2653] |
| researcher / PROBABILISTIC | 11 (8/3) | 8/0/0/3 | 1.0000 | 1.0000 | 1.0000 | 0.7273 [0.4545, 1.0000] | 0.2727 [0.0000, 0.5455] |
| researcher / LLM_ADJUDICATED | 0 | no data | n/a | n/a | n/a | n/a | n/a |
| work / EXACT_ID | 21 (20/1) | 18/0/2/1 | 1.0000 | 0.9000 [0.7500, 1.0000] | 0.9474 [0.8571, 1.0000] | 0.9048 [0.7619, 1.0000] | 0.0000 |
| work / DETERMINISTIC_BIBLIOGRAPHIC | 0 | no data | n/a | n/a | n/a | n/a | n/a |
| work / PROBABILISTIC | 0 | no data | n/a | n/a | n/a | n/a | n/a |
| work / LLM_ADJUDICATED | 0 | no data | n/a | n/a | n/a | n/a | n/a |
| all / EXACT_ID | 32 (31/1) | 28/0/3/1 | 1.0000 | 0.9032 [0.8065, 1.0000] | 0.9492 [0.8929, 1.0000] | 0.9062 [0.8125, 1.0000] | 0.0000 |
| all / DETERMINISTIC_BIBLIOGRAPHIC | 49 (49/0) | 41/0/8/0 | 1.0000 | 0.8367 [0.7347, 0.9388] | 0.9111 [0.8471, 0.9684] | 0.8367 [0.7347, 0.9388] | 0.1633 [0.0612, 0.2653] |
| all / PROBABILISTIC | 11 (8/3) | 8/0/0/3 | 1.0000 | 1.0000 | 1.0000 | 0.7273 [0.4545, 0.9114] | 0.2727 [0.0886, 0.5455] |
| all / LLM_ADJUDICATED | 0 | no data | n/a | n/a | n/a | n/a | n/a |

All specificity, conflict-rate and per-group values are in the two CSV files.

The LLM path was **not evaluated**. The run used only the deterministic fake
adjudicator (`fake-deterministic-adjudicator-v1`), never a model. No pair fell
in the LLM band.

### 1.5 Errors

There are 0 false positives and 11 false negatives. Source:
[er/errors.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/er/errors.csv).

| Count | Kind | Path | Decision | Reason code |
|---:|---|---|---|---|
| 8 | researcher | DETERMINISTIC_BIBLIOGRAPHIC | UNRESOLVED | `SAME_WORK_DIFFERENT_POSITION` |
| 1 | researcher | EXACT_ID | CONFLICT | `EXACT_ID_DISAGREES_WITH_EVIDENCE` |
| 2 | work | EXACT_ID | CONFLICT | `EXACT_ID_DISAGREES_WITH_EVIDENCE` |

The three CONFLICT errors carry a human note ending "Relabeled after review"
(dual DOIs for the two works; a probable ORCID source error for the
researcher).

The recomputed TP, FP, FN and TN match the Phase 5 tool in 8 of 8 cells
([er/tool-crosscheck.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/er/tool-crosscheck.csv)).

### 1.6 Comparison with the earlier policy run

The Phase 5 summary compares the scored run with an earlier run
(`er-features-v1`) on the same labels. It marks this "for reference only".

| Researcher metric | Scored (r2) | Earlier run |
|---|---|---|
| Recall | 59/68 (0.8676) | 67/68 (0.9853) |
| Coverage | 59/71 (0.8310) | 70/71 (0.9859) |
| Unresolved rate | 11/71 (0.1549) | 0/71 |
| False positives | 0 | 0 |

Work metrics are identical in the two runs. The same summary holds a
report-only threshold sensitivity table. No threshold was changed.

**Interpretation.** No false merge was observed, but there were only 4 chances
to make one, and 3 of those 4 pairs were left unresolved, not rejected.
Specificity 1.000 therefore does not show that the policy actively
distinguishes different entities. On this gold set the scored policy has lower
researcher recall than the earlier run, and the set is too thin in negatives
to show what that recall bought.

**Not covered.** Population-level accuracy; LLM adjudication; the
deterministic and probabilistic paths for works; transitive cluster quality.

## 2. Graph correctness

Method: plain-Python checks over the S3 copy of the Spark output of run
`run:f6023de3...` (snapshot 2026-11-01). The checks share no code with the
Spark jobs. That Spark run executed **locally, not on EMR** (Spark code
version `a75b607`). Source:
[part_a_graph.py](../experiments/evaluation/bin/part_a_graph.py).

The graph is small: 89 researchers, 24 works, 13 venues, 63 topics, 32
institutions, 89 authorships and 334 coauthor edge rows over six windows (167
in the all-years window). Source:
[graph/table-rows.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/graph/table-rows.csv).

### 2.1 Input and copy integrity

- Hadoop inputs match the Spark run manifest checksums: 3 of 3
  ([hadoop-input-checksums.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/graph/hadoop-input-checksums.csv)).
- S3 tables match the S3 load manifest in checksum set, row count and file
  count: 21 of 21 tables (2315 rows, 462 files)
  ([s3-vs-load-manifest.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/graph/s3-vs-load-manifest.csv)).

### 2.2 Referential integrity

Source: [graph/referential-integrity.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/graph/referential-integrity.csv).

| Check | Violations / references |
|---|---:|
| authored.researcherId to researcher | 0 / 89 |
| authored.workId to work | 0 / 89 |
| coauthored_with endpoints to researcher | 0 / 668 |
| support.edgeId to coauthored_with | 0 / 334 |
| support.workId to work | 0 / 334 |
| support (endpoint, work) to authored | 0 / 668 |
| support endpoints equal edge endpoints | 0 / 334 |
| has_topic.fromId to work | 0 / 12 |
| has_topic.toId to topic | 0 / 12 |
| work.venueId to venue | 0 / 24 |
| researcher affiliation IDs to institution | 0 / 11 |
| researcher_collaboration.researcherId to researcher | 0 / 178 |
| components.researcherId to researcher | 0 / 178 |
| community_membership.researcherId to researcher | 0 / 178 |
| bridge_measures.researcherId to researcher | 0 / 178 |
| coreness.researcherId to researcher | 0 / 178 |
| researcher_window_change.researcherId to researcher | 0 / 123 |
| path hop edgeId to coauthored_with | 0 / 28 |
| path hop supporting works to work | 0 / 28 |

### 2.3 Duplicates

Source: [graph/duplicate-edges.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/graph/duplicate-edges.csv).

| Check | Violations / rows |
|---|---:|
| coauthored_with edgeId | 0 / 334 |
| coauthored_with (window, unordered pair) | 0 / 334 |
| support (edgeId, workId) | 0 / 334 |
| authored (researcher, work) | 0 / 89 |
| authored authorshipId | 0 / 89 |
| has_topic edgeId | 0 / 12 |
| self-loops | 0 / 334 |
| edges not in canonical order | 0 / 334 |

### 2.4 Orphans

Source: [graph/orphans.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/graph/orphans.csv).

| Check | Orphans / nodes | Rate |
|---|---:|---:|
| researcher without authored edge | 0 / 89 | 0.000 |
| researcher without coauthor edge (any window) | 0 / 89 | 0.000 |
| work without authored edge | 0 / 24 | 0.000 |
| work without HAS_TOPIC | 20 / 24 | 0.833 |
| venue without work | 11 / 13 | 0.846 |
| topic without HAS_TOPIC | 57 / 63 | 0.905 |
| institution without affiliated researcher | 28 / 32 | 0.875 |

### 2.5 Coauthor-edge support

An edge is supported when every listed supporting work exists and both
endpoints authored it. Source:
[graph/edge-support.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/graph/edge-support.csv).

| Window | Supported / edges | Joint count equals support | Representative works in support |
|---|---:|---:|---:|
| 2000-2005 | 23 / 23 | 23 / 23 | 23 / 23 |
| 2000-2025 | 167 / 167 | 167 / 167 | 167 / 167 |
| 2006-2010 | 4 / 4 | 4 / 4 | 4 / 4 |
| 2011-2015 | 32 / 32 | 32 / 32 | 32 / 32 |
| 2016-2020 | 26 / 26 | 26 / 26 | 26 / 26 |
| 2021-2025 | 82 / 82 | 82 / 82 | 82 / 82 |
| all rows | 334 / 334 | 334 / 334 | 334 / 334 |

### 2.6 Independent recomputation

Totals over the six windows, summed from
[graph/recomputation.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/graph/recomputation.csv).

| Check | Mismatches / compared |
|---|---:|
| Coauthor edges and supporting works | 0 / 334 |
| Degree | 0 / 178 |
| Weighted degree | 0 / 178 |
| Component ID and size | 0 / 178 |
| k-core coreness | 0 / 178 |
| Community size field | 0 / 178 |
| Community lies in one component (labels not recomputed) | 0 / 48 |
| Bridge degree, cross-community degree, communities connected | 0 / 178 |
| Stored paths are shortest walks | 0 / 28 |
| Window summary researcher, edge, component counts | 0 / 18 |
| Total | 0 / 1496 |

### 2.7 What the data exercises

Sources: [graph/structure.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/graph/structure.csv),
[graph/path-status.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/graph/path-status.csv).

| Window | Researchers | Edges | Components | Largest | Communities | Max coreness | Cross-community edges |
|---|---:|---:|---:|---:|---:|---:|---:|
| 2000-2005 | 20 | 23 | 7 | 5 | 7 | 4 | 0 |
| 2000-2025 | 89 | 167 | 24 | 8 | 24 | 7 | 0 |
| 2006-2010 | 5 | 4 | 2 | 3 | 2 | 2 | 0 |
| 2011-2015 | 16 | 32 | 5 | 8 | 5 | 7 | 0 |
| 2016-2020 | 13 | 26 | 3 | 6 | 3 | 5 | 0 |
| 2021-2025 | 35 | 82 | 7 | 8 | 7 | 7 | 0 |

Path evidence has 28 rows, all `FOUND`, with maximum length 1. The
`integrity_reject`, `identity_conflict` and `cross_community_edge` tables are
empty.

**Interpretation.** In every window the community count equals the component
count and no edge crosses communities. The deployed data therefore cannot
test community separation or bridge detection in a non-trivial case. It also
holds no multi-hop path and no "no path" result. Zero mismatches on these
checks says nothing about those behaviours; only the known-graph tests below
exercise them.

### 2.8 Known small-graph tests

Run at evaluation time with `sbt` (not replayed for this document). Sources:
[tests/known-graph-tests.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/tests/known-graph-tests.csv),
[tests/known-graph-test-cases.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/tests/known-graph-test-cases.csv).

| Module | Suite | Passed / tests |
|---|---|---:|
| sparkAnalytics | CommunitySpec | 13 / 13 |
| sparkAnalytics | ConnectedComponentsSpec | 3 / 3 |
| sparkAnalytics | ExtraCasesSpec | 9 / 9 |
| sparkAnalytics | IdentityResolverSpec | 11 / 11 |
| sparkAnalytics | KnownGraphSpec | 9 / 9 |
| sparkAnalytics | LoadManifestsSpec | 1 / 1 |
| sparkAnalytics | PathsSpec | 11 / 11 |
| sparkAnalytics | PipelineIntegrationSpec | 8 / 8 |
| sparkAnalytics | SettingsSpec | 4 / 4 |
| **sparkAnalytics total** | | **69 / 69** |
| adversarialTests | SparkDeterminismSpec | 4 / 4 |
| adversarialTests | SparkDriverCollectScanSpec | 6 / 6 |
| adversarialTests | SparkGraphShapeSpec | 8 / 8 |
| adversarialTests | SparkIdentityAdvSpec | 9 / 9 |
| adversarialTests | SparkLpaTieSpec | 8 / 8 |
| adversarialTests | SparkPairsWindowsSpec | 5 / 5 |
| adversarialTests | SparkPathsAdvSpec | 4 / 4 |
| **adversarialTests (Spark) total** | | **44 / 44** |

No failures, errors or skips. Only the Spark adversarial suites were selected.

### 2.9 Provenance audit of 50 edges

Seed `20261125`, 50 of 334 coauthor edge rows. Sources:
[provenance-audit-summary.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/graph/provenance-audit-summary.csv),
[provenance-audit.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/graph/provenance-audit.csv).

| Measure | Value |
|---|---:|
| Provenance fields correct | 50 / 50 |
| Traced to the DBLP XML | 50 / 50 |
| Distinct researcher pairs in the sample | 46 |
| Distinct DBLP keys in the sample | 15 |
| Supporting works per sampled edge | 1 for all 50 |

The trace goes edge, support, work, authorship, Hadoop mention, then the
author name at its position in the committed excerpt
[dblp-2026-11-01-excerpt.xml](../datasets/samples/dblp/dblp-2026-11-01-excerpt.xml).

**Not covered (all of section 2).**

- Neptune, DynamoDB and S3 Vectors contents were not queried. The checks read
  only the S3 copy of the Spark output.
- Community labels, participation coefficient, distance estimates,
  window-change values, semantic-fact text and several researcher metrics were
  not independently recomputed.
- The audit traces to a bounded excerpt, not to the full DBLP release, and
  does not trace OpenAlex, ORCID or Crossref fields. It covers only coauthor
  edges.
- Correctness of researcher identity merges is not checked here.

## 3. Performance and elasticity

Phase 15 Experiment C (embedding backlog) is the only performance experiment
that ran. It ran once, on commit `6ce56c9`
([run summary](../experiments/derived/C-embedding-backlog/C-embedding-backlog-6ce56c9-20261125T171458Z/summary.md)).

| Experiment | Status |
|---|---|
| A EMR baseline | not run |
| B EMR burst | not run |
| C embedding backlog | run once: **FAILED, `NO_VECTORS_WRITTEN`** |
| D query load | not run |
| E retrieval modes | not run |
| F poison message | not run |

Sources: [experiments-run-status.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/perf/experiments-run-status.csv),
[c-measures.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/perf/c-measures.csv),
[c-timeline.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/perf/c-timeline.csv),
[c-errors.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/perf/c-errors.csv).
The 9 Experiment C raw files match their checksums
([c-raw-checksums.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/perf/c-raw-checksums.csv)).

### 3.1 Timeline (2026-11-25 UTC)

| Event | Time | Seconds after first send |
|---|---|---:|
| First send | 17:15:03.727 | 0.0 |
| First poll with visible > 0 | 17:15:49.989 | 46.3 |
| Scale-out: first poll with desired > 0 | 17:17:37.101 | 153.4 |
| First poll with running > 0 | 17:17:54.794 | 171.1 |
| First poll with in flight > 0 | 17:18:12.565 | 188.8 |
| First worker batch line | 17:18:19.552 | 195.8 |
| Last worker batch line | 17:39:34.130 | 1470.4 |
| First empty poll | 17:39:53.760 | 1490.0 |
| Scale-in: first poll with desired 0 | 17:46:43.027 | 1899.3 |
| First later poll with running 0 | 17:46:43.027 | 1899.3 |

### 3.2 Measures

| Measure | Value |
|---|---|
| Scaling delay, first send to desired > 0 | 153.4 s |
| Scaling delay, first visible poll to desired > 0 | 107.1 s |
| Scale-out, desired > 0 to running > 0 | 17.7 s |
| Drain, first send to first empty poll | 1490.0 s (runner milestone: 1476.7 s) |
| Scale-in, first empty poll to desired 0 | 409.3 s (runner: 391.5 s after its drain mark, 1868.2 - 1476.7) |
| Scale to zero, runner `scaledToZeroAfterSeconds` (from watch start) | 1868.2 s (derived, from first send: 1899.3 s) |
| Margin of the runner drain mark under the 1500 s drain timeout | 23.3 s |
| Maximum desired / running tasks | 2 / 2 |
| Median poll interval (timing resolution) | 17.8 s |
| Messages sent / accepted | 300 / 300 |
| Messages received / acknowledged / retried / dead-lettered | 300 / 300 / 0 / 0 |
| Maximum DLQ visible | 0 |
| Message acknowledgement rate | 0.2354 per second |
| **New successful vector put batches** | **0** |
| **Item errors** | **90600 `EMBEDDING_FAILED` (store `s3vectors`), 71.08 per second** |
| Running task-seconds (approximate) | 3456 |
| Billed cost | **not measured** (no billing data in raw) |
| Cost estimate | about USD 1.5 against a cap of 1.0; **prices unverified**; taken from the Phase 15 report, not from raw data |

The drain timeout is `drain_timeout_seconds` in the run's
[manifest.yaml](../experiments/raw/C-embedding-backlog/C-embedding-backlog-6ce56c9-20261125T171458Z/manifest.yaml);
the runner milestones are in
[runner/run.json](../experiments/raw/C-embedding-backlog/C-embedding-backlog-6ce56c9-20261125T171458Z/runner/run.json).

### 3.3 Why the derived and runner timings differ

**Established from code and timestamps.** Sources:
[queue_scenario.py](../load-tests/queue_scenario.py) (last changed in `6ce56c9`,
the commit the run records),
[part_a_other.py](../experiments/evaluation/bin/part_a_other.py),
[runner/observations.jsonl](../experiments/raw/C-embedding-backlog/C-embedding-backlog-6ce56c9-20261125T171458Z/runner/observations.jsonl).

- Origin. Derived figures start at the first send record (17:15:03.727). Runner
  figures use a monotonic clock that starts inside `watch()`, which is called
  after all sends and after one `sent` sample (stamped 17:15:32.049).
- Stamping point. The runner takes `elapsed` before the poll's AWS calls; a
  poll's `atUtc` is stamped after them.
- Drain definition. The derived figure uses the first empty poll after
  receiving began (17:39:53.760). The runner needs two empty polls in a row and
  records the second (17:40:11.508).
- Scale to zero is the same poll in both (17:46:43.027).

**Inferred.** The watch start instant is not recorded; it is approximated here
by the `sent` sample, 28.3 s after the first send. Per-poll AWS call time is not
recorded either; the timestamps imply about 2.8 s. For scale to zero,
28.3 + 2.8 = 31.1 s, which matches 1899.3 - 1868.2. For drain, 31.1 s less one
poll gap (17.75 s) is about 13.35 s, against 1490.0 - 1476.7 = 13.3 s. The same
poll gap separates 409.3 s from 391.5 s. On this reading neither difference is
a data error.

### 3.4 Interpretation and limits

**Interpretation.** The run shows that the worker service scaled from zero to
two tasks and back to zero for this backlog. It does **not** measure embedding
or vector-write throughput, because nothing was written. The acknowledgement
rate is a rate of messages consumed while their items failed. Drain time and
scale-in timing come from a failing workload and may differ for a successful
one. All 300 messages were acknowledged with no retry and no dead-letter
despite the item errors; whether that is the intended failure handling is not
established by the Part A outputs (a Phase record in 3.5 treats it as a
defect).

**Not covered.** The Part A outputs do not establish the cause of the
embedding failures. The Part A tables report no request latency, no CPU or
memory summary and no billed cost. There is one run, so no variance.
Query-path performance and EMR elasticity were not measured.

### 3.5 Phase records on this run and its incident (not Part A measurements)

**Phase record.** Source unless stated: the
[Phase 15 human gate](agent-runs/phase-15/human-gate.md).

- **Acknowledged rejects are a recorded defect.** The gate lists "rejected items
  acknowledged instead of retried/dead-lettered" as a new defect and carries it
  forward as an open item.
- **Recorded cause of the failure: a Bedrock account block.** The gate first
  recorded a Bedrock `ValidationException` with unknown cause. It later records
  an account-wide block (`NOT_AUTHORIZED` for the models checked, in three US
  Regions), finds that it is not IAM or an SCP, treats it as an AWS-side account
  restriction, and closes the phase with "0 vectors written: Bedrock account
  block".
- **Credentials were written to DEBUG logs** by `query-api` and
  `embedding-worker`; the gate's containment counts also cover matches in old
  `graph-evidence-api` task streams. The incident is
  [INC-20261125-debug-credential-logging](incidents/INC-20261125-debug-credential-logging.md),
  summarized there as task role credential material and the API-key secret
  response written to CloudWatch Logs. A logging fix (`3c3e1a1`) was deployed.
  The status is **CONTAINED, not resolved**.
- **Still open at phase close:** API token rotation, the existing log material,
  Neptune audit log credential capture, TLS on the dev ALB, and a log
  containment check of the embedding worker on its first run under the fix.
- **The API token was sent unencrypted**, because the dev ALB serves plain HTTP.
- **Cost exceeded the cap.** About USD 1.50 (an estimate) against USD 1.00,
  mostly CloudWatch log ingestion (2.8 GB) caused by the DEBUG logging.
- **Experiments D, E and F** were not run because they were Bedrock-dependent or
  blocked by the acknowledge-on-reject defect.

## 4. Agentic factory

### 4.1 Status of the human file

[factory-counts.csv](../experiments/evaluation/labels/factory-counts.csv) was
committed in `f79d007`. Its content is as follows.

- It is **byte-identical** to the automated queue
  [needs-human-confirmation.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/factory/needs-human-confirmation.csv)
  (SHA-256 `881e3e99...` for both).
- It has 41 rows with columns `table`, `row`, `source_file`, `source_line`.
  It has no verdict, corrected-value or comment column and no aggregate
  counts.
- The run configuration records an empty `labels/` directory at sealing time,
  so the file was added after the run was sealed.

The [human gate](agent-runs/phase-16/human-gate.md) records that the file is an
unedited copy of the agent's needs-confirmation list, **not a human ruling**
(the `f79d007` commit message overstates this). The human **accepts the
automated counts as reported**. **Flagged rows remain unverified** and are
reported as such.

The figures below are therefore the automated extraction, and no human figure
differs from them. **†** marks a figure that is flagged as needing human
confirmation, and so is unverified.

The extraction parses review findings from the reports. All other events are
hand-coded records in
[factory_extract.py](../experiments/evaluation/bin/factory_extract.py), and
the classifications are the extractor's reading of the text. The 71 source
files it recorded still match their checksums at `f79d007`.

### 4.2 First-pass acceptance by agent

Source: [first-pass-by-agent.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/factory/first-pass-by-agent.csv).
The unit is a primary work order of a non-reviewer agent.

| Agent | Work orders | Yes | No | Unknown | Rate over known | Rows flagged |
|---|---:|---:|---:|---:|---:|---:|
| build-engineer | 2 | 1 | 1 | 0 | 0.50 † | 1 |
| dataset-engineer | 1 | 0 | 1 | 0 | 0.00 | 0 |
| deploy-engineer | 2 | 0 | 2 | 0 | 0.00 † | 1 |
| domain-engineer | 2 | 0 | 2 | 0 | 0.00 † | 1 |
| entity-resolution-engineer | 1 | 0 | 1 | 0 | 0.00 | 0 |
| experiment-engineer | 1 | 0 | 1 | 0 | 0.00 | 0 |
| factory-engineer | 1 | 0 | 0 | 1 | n/a † | 1 |
| hadoop-engineer | 1 | 0 | 1 | 0 | 0.00 | 0 |
| infra-engineer | 3 | 0 | 3 | 0 | 0.00 † | 1 |
| rag-engineer | 4 | 1 | 3 | 0 | 0.25 † | 1 |
| service-engineer | 3 | 2 | 1 | 0 | 0.67 † | 1 |
| spark-engineer | 1 | 0 | 1 | 0 | 0.00 | 0 |
| storage-engineer | 5 | 3 | 2 | 0 | 0.60 † | 3 |
| test-engineer | 4 | 1 | 3 | 0 | 0.25 | 0 |
| **Total (summed here)** | **31** | **8** | **22** | **1** | **8 of 30** † | **10** |

Several "yes" rows were not reviewed or tested independently: the queue lists
Phase 8 vector write path (executed inline by the orchestrator, not
reviewed), Phase 10 REST services (reviewer and test engineer not run) and
three Phase 10b work orders (not reviewed).

### 4.3 Event counts

Source: [factory/summary.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/factory/summary.csv).

| Measure | Count |
|---|---:|
| BLOCKED-ON results | 14 † |
| of which with an archived report | 12 |
| Partial blocks inside other statuses (not in the 14) | 2 † |
| Ownership or authority events: blocked write | 5 † |
| Ownership or authority events: refused by the agent | 3 |
| Ownership or authority events: **landed** | 4 † |
| Ownership or authority events: **unresolved** | 2 † |
| Guard block events documented (approximate) | 60 † (52 from exact entries, 8 from minimum entries) |
| Stop-hook block events documented (approximate) | 2 † (both minimums) |
| Permission block events documented (approximate) | 3 † |
| Manual interventions: unplanned | 21 † |
| Manual interventions: gate action | 5 † |
| Manual interventions: process | 3 † |
| Artifacts needing substantial human rewrite (documented) | 0 † |

The "landed" and "unresolved" events
([ownership.csv](../experiments/evaluation/raw/partA-1b0e893-20261126T014300Z/factory/ownership.csv))
are six. Five are flagged †; the Phase 15 Terraform-state event is not.

| Phase | Category | Event | Flag |
|---|---|---|---|
| 04 | landed | Three edits via Bash or Python bypassed the guard (inside own territory) | † |
| 08 | landed | `git rm --cached` run and reverted, against the git rule | † |
| 08 | landed | Orchestrator executed specialist work inline | † |
| 15 | landed | Searches printed Terraform state content, against the secret-handling rule | not flagged |
| 05 | unresolved | Edits under `tests/**` during a repair; author unrecorded | † |
| 06 | unresolved | Unattributed file in human-owned `docs/agent-runs` | † |

Interventions recorded in
[interventions.csv](../experiments/evaluation/raw/partA-1b0e893-20261126T014300Z/factory/interventions.csv)
include, for Phase 14, an unplanned `terraform apply` that was started and
cancelled, an API-key secret set and a redeployment forced by CLI, and an ALB
ingress change that allowed plaintext HTTP. For Phase 07 they include a
commit and push before review findings were resolved.

Guard blocks by phase
([hook-blocks-by-phase.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/factory/hook-blocks-by-phase.csv));
`min` means the phase total includes a minimum count, and † that the cell
includes a flagged row
([hook_blocks.csv](../experiments/evaluation/raw/partA-1b0e893-20261126T014300Z/factory/hook_blocks.csv)):

| Phase | 01 | 02 | 03 | 04 | 07 | 08 | 09 | 10 | 10b | 11 | 12 | 13 | 14 | 15 |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| Guard | 1 | 2 | 6 | 1 | 2 min † | 1 min † | 1 | 1 | 2 min † | 1 | 7 min † | 1 min † | 4 | 30 min † |
| Stop hook | | | | | | | 1 min † | | | 1 min † | | | | |
| Permission | | | 1 † | | | | | | | | | | | 2 |

### 4.4 Reviewer findings by severity

Source: [review-findings-by-severity.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/factory/review-findings-by-severity.csv),
plot [review-findings.svg](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/factory/review-findings.svg).
Phases with several reports are summed here.

| Phase | Critical | Blocker | Major | Minor | Nit | Total |
|---|--:|--:|--:|--:|--:|--:|
| 02 | 0 | 0 | 1 | 2 | 0 | 3 |
| 03 | 0 | 0 | 2 | 17 | 0 | 19 |
| 04 | 0 | 0 | 0 | 9 | 0 | 9 |
| 05 | 0 | 0 | 0 | 3 | 0 | 3 |
| 06 | 0 | 0 | 3 | 7 | 5 | 15 |
| 07 | 0 | 0 | 3 | 12 | 0 | 15 |
| 09 | 0 | 0 | 9 | 19 | 0 | 28 |
| 11 | 0 | 0 | 6 | 9 | 0 | 15 |
| 12 | 0 | 0 | 6 | 10 | 0 | 16 |
| 13 | 0 | 0 | 3 | 16 | 0 | 19 |
| 14 | 0 | 0 | 0 | 5 | 0 | 5 |
| 14b | 0 | 0 | 2 | 9 | 0 | 11 |
| 15 | 0 | 0 | 11 | 35 | 12 | 58 |
| **All** | **0** | **0** | **46** | **153** | **17** | **216** |

These are findings in archived reports. They are not de-duplicated across
review rounds, and they do not show which findings were fixed.

Evidence gaps ([review-evidence-gaps.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/factory/review-evidence-gaps.csv);
a hand-typed table, see 5.4):

| Phase | Gap |
|---|---|
| 02 | `review-report-02.md` is empty (0 bytes); the human gate records APPROVE |
| 05 | First review (findings F1-F14) is not archived; its severities are not counted |
| 06 | A cited review round 2 and repair-02 are not archived |
| 08 | Code review skipped by human decision |
| 10 | Code review not run |
| 10b | Code review not run |

### 4.5 Repair iterations

Source: [repair-iterations.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/factory/repair-iterations.csv).

| Phase | 00 | 01 | 02 | 03 | 04 | 05 | 06 | 07 | 08 | 09 | 10 | 10b | 11 | 12 | 13 | 14 | 14b | 15 |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| Repair files present | 0 | 0 | 3 | 2 | 0 | 1 | 1 | 0 | 1 | 0 | 0 | 1 | 0 | 1 | 2 | 3 | 1 | 1 |
| Rounds documented | 0 † | 0 † | 3 | 2 † | 8 † | 1 | 2 † | 2 † | 1 | 4 † | 0 | 1 | 0 † | 1 | 2 | 3 | 1 | 1 † |

Summed here: 17 repair files present against 32 rounds documented.

### 4.6 The confirmation queue

The 41 queued rows are: 10 work-order classifications, 10 hook-block entries,
9 repair-round entries, 7 ownership events, 3 interventions and 2 rewrite
judgements. Some summary figures are also flagged without every underlying row
being in the queue (for example the intervention totals and the partial
blocks).

**Interpretation.** The counts show frequent blocking, repair and manual
action. They are a hand-coded reading of incomplete records, so they support
description, not a comparison of agent quality. "0 substantial rewrites" means
none was documented.

**Not covered.** Time or cost per agent, the quality of accepted work, and
which review findings were fixed. Work after Phase 15 is outside the
extraction.

## 5. Reproducibility

### 5.1 Clean-clone build

The human ran the build and supplied the log. The agent did not run it. Human
gate: it ran at commit `1b0e893`. Sources:
[clean-clone-outcome.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/repro/clean-clone-outcome.csv),
[clean-clone-test-summaries.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/repro/clean-clone-test-summaries.csv),
[clean-check.log.gz](../experiments/evaluation/raw/partA-1b0e893-20261126T014300Z/repro/clean-check.log.gz).

| Measure | Value |
|---|---|
| Clone HEAD observed on disk | `1b0e893` |
| Clone `git status` lines | 0 |
| Commit named inside the log | **no** |
| `[success]` / `[error]` lines | 6 / 0 |
| Tests succeeded / failed (8 summary lines) | 2391 / 0 |
| **Tests ignored** | **24** |
| `markTestRun` success line | present |
| First to last `[success]` time (local) | 2026-11-25 18:34:45 to 19:15:01 |

The link between the log and the commit rests on the clone state observed
afterwards and the human's statement, not on the log itself.

#### The 24 ignored tests

**Established.** The log marks 24 test names `!!! IGNORED !!!`. For this
revision they were read from an unpacked copy of the log whose SHA-256 equals
the one in `clean-clone-outcome.csv`. The names match the 24 `ignore(...)` sites
under [tests/src/test](../tests/src/test), which are unchanged between
`1b0e893` and `f79d007`. They fall in the last test summary line (579
succeeded, 24 ignored). The Part A tables do not say why they are ignored.

**Phase record.** All 24 are deliberate deferrals of recorded findings.
Sources: [entity-resolution known limitations](../modules/entity-resolution/KNOWN-LIMITATIONS.md),
[Phase 11 human gate](agent-runs/phase-11/human-gate.md).

- 9 tests are the Phase 05 entity-resolution items D6 to D10, deferred by the
  human: D6 1 (name-only LLM accept), D7 2 (family-name-first order), D8 4
  (look-alike Unicode characters), D9 1 (blank identifiers), D10 1 (suffix-only
  name).
- 15 tests are Phase 11 deferrals, made because of the submission deadline.
- The Phase 11 gate ("no critical or major verified finding remains") was **NOT
  met**: 5 MAJOR findings were deferred (T1, T2, T4, T7 and T11 below). The same
  gate also lists R13, the D6-D10 deferral, as MAJOR (human decision). It
  records the deferred MAJOR defects as "real and unrepaired".

| Defect | Tests | Severity | Subject |
|---|---:|---|---|
| T1 | 2 | MAJOR | DynamoDB transaction conflict and throttling classified permanent |
| T2 | 2 | MAJOR | Neptune rows read with silent defaults (provenance loss) |
| T3 | 1 | MINOR | `directCoauthors` skips the window check |
| T4 | 1 | MAJOR | JSON null record throws a NullPointerException |
| T5 | 1 | MINOR, policy pending | Credential-shaped SDK text in storage errors |
| T6 | 1 | MINOR, policy pending | Embedder exception text in ingest rejects |
| T7 | 1 | MAJOR | Asynchronous job can stay `RUNNING` forever |
| T8 | 1 | MINOR (latent) | Neighbors response overstates depth |
| T9 | 1 | MINOR, policy pending | Model property name echoed in a rejection |
| T10 | 1 | MINOR | Non-UTF-8 input throws |
| T11 | 2 | MAJOR | Chunker splits on ASCII whitespace only |
| T12 | 1 | MINOR | Poison message coerces a numeric `runId` |

**Inferred.** The defects are taken to be still unfixed at `1b0e893`, because
the tests are still ignored and the sources read record no later repair. The
production code was not re-checked. T5, T6 and T9 await a human policy
decision, so not every ignored test marks a settled defect.

### 5.2 Pipeline replay evidence

Source: [pipeline-evidence.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/repro/pipeline-evidence.csv),
[pipeline-run-phase14.json](../experiments/evaluation/raw/partA-1b0e893-20261126T014300Z/repro/pipeline-run-phase14.json).
The first file is typed into the script, not computed (see 5.4).

| Item | Value |
|---|---|
| Hadoop run (local, Phase 14) | `run:dc970f17...` |
| Spark run (local, Phase 14) | `run:f6023de3...` |
| First Spark stage attempt | **failed** |
| Later local pipeline record (commit `298d78e`, stage spark) | **FAILED, exit 1** |
| Full replay of the bounded pipeline with output comparison | **no evidence found** |
| EMR clusters in us-east-2 ([listing](../experiments/evaluation/raw/partA-1b0e893-20261126T014300Z/aws/emr-list-clusters.json); [AWS observation stamp](../experiments/evaluation/raw/partA-1b0e893-20261126T014300Z/aws/observed-at-utc.txt) `2026-11-26T01:00:00Z`) | 0 |
| S3 copy matches local Spark manifest checksums | yes, 21 of 21 tables (section 2.1) |

**Interpretation.** The deployed graph data is traceable to a local run, but
pipeline reproducibility has **not** been demonstrated. Checksum agreement
shows a faithful copy, not a repeatable computation.

**Not covered (all of section 5).** No pipeline stage, deployment or AWS read
was replayed for this document. Image digests of earlier deploys were not
compared, and no second clean-clone build was run.

### 5.3 Approved plan checksum and deployed revision

Source: [plan-vs-deployed.csv](../experiments/evaluation/derived/partA-1b0e893-20261126T014300Z/repro/plan-vs-deployed.csv).
All three services share the values in each row.

| Deploy (UTC) | Git SHA | Approved plan SHA-256 | Task revision | Live when observed | ECR digest matches |
|---|---|---|---:|---|---|
| 2026-11-24 17:16:52 | `2365bf5` | `993bf21a...` | 5 | no | not checked |
| 2026-11-25 16:52:57 | `6ce56c9` | `2a196bb0...` | 8 | no | not checked |
| 2026-11-25 20:05:54 | `3c3e1a1` | `47dd1a5c...` | 10 | yes | yes, 3 of 3 |

Each approved checksum in a deploy record also appears in a Phase 14 or 15
document
([plan-checksums-in-logs.json](../experiments/evaluation/raw/partA-1b0e893-20261126T014300Z/repro/plan-checksums-in-logs.json)).
For `2a196bb0...` and `47dd1a5c...` the only match recorded there is that
deploy's own deploy report, so it is **not independent corroboration**. For
`993bf21a...` the matches are in two Phase 14 files. These are text matches
between records. No plan file was re-hashed, so the check does not prove that
the applied plan had that checksum.

**A matching revision is not a passing deployment.**

- The `6ce56c9` deploy work order is recorded with report status **FAILED
  (smoke)** in the Part A raw data
  ([work_orders.csv](../experiments/evaluation/raw/partA-1b0e893-20261126T014300Z/factory/work_orders.csv),
  a flagged † row).
- **Phase record** ([Phase 15 human gate](agent-runs/phase-15/human-gate.md)):
  smoke passed 7 of 8 checks on `6ce56c9` and again on `3c3e1a1`, the revision
  live when observed. The failing check both times was "evidence"; the gate
  calls the first failure identical to Phase 14. For `3c3e1a1` it gives a
  **suspected** cause, the Bedrock account block. The failure was still open at
  phase close.

The evaluation involves four code versions: services deployed from `3c3e1a1`,
Experiment C run on `6ce56c9`, graph data produced by Spark code `a75b607`, and
evaluation at `1b0e893`.

### 5.4 Regeneration

From the repository root, one command regenerates every Part A table and plot:

```bash
experiments/evaluation/regenerate.sh partA-1b0e893-20261126T014300Z
```

It needs `uv`, which fetches `pyarrow==21.0.0` and `pyyaml==6.0.2` on first
use. It needs no AWS access. It verifies the run's `SHA256SUMS` first and
writes under `experiments/evaluation/derived/`. It also rebuilds the
Experiment C plots under `experiments/derived/`.

Replayed for this document on 2026-11-25 in a local clone of `f79d007` outside
the repository: exit 0, 580 raw files verified, and `git status --porcelain`
in the clone was empty afterwards, so the regenerated tracked files are
identical to the committed ones.

Limits of the command: it does not re-run the `sbt` tests, the ER evaluator,
the AWS reads, the S3 downloads or the factory extraction; it starts from
their sealed outputs. It also reads inputs outside the evaluation raw
directory (the DBLP excerpt and the Experiment C raw directory), which that
directory's `SHA256SUMS` does not cover.

**Known limitation: some "derived" tables are typed, not computed.**
[part_a_other.py](../experiments/evaluation/bin/part_a_other.py) writes the
following as literals, so regenerating them reproduces the typed text and checks
nothing.

| Derived file | Typed into the script |
|---|---|
| `perf/experiments-run-status.csv` | The "not run" status of A, B, D, E and F, and the "run once" wording (C's status and stop reason are read from the run) |
| `perf/c-measures.csv` | The "billed cost: not measured" row and the cost estimate row |
| `factory/review-evidence-gaps.csv` | The whole table |
| `repro/pipeline-evidence.csv` | The whole table, including "failed", "no evidence found" and the EMR cluster count "0" |
| `repro/clean-clone-outcome.csv` | The "commit named in the log: no" value |

The empty EMR listing can be seen separately in the raw file cited in 5.2. The
factory extraction records are also hand-coded (4.1). The script was not changed
for this revision.

### 5.5 Raw-data redaction

The human gate records that the human **redacted the AWS account ID from
`aws/ecs-services.json` and `repro/deploy-records.json` after collection** and
updated their two `SHA256SUMS` lines. This is a post-collection change to raw
files. The original checksums are kept in
[SHA256SUMS.pre-redaction](agent-runs/phase-16/SHA256SUMS.pre-redaction).

The gate also records the human's verification of the redaction: a scan
(2026-11-25, about 19:50) of `experiments/evaluation` for the AWS account ID and
credential patterns (AKIA/ASIA keys, IQoJ, x-amz-security-token). The account ID
was found in 2 text files and redacted, with 0 binary matches and 0 credentials,
and a rescan was clean. That is the whole recorded scope. The record does not
say that compressed contents were decompressed and scanned, and this document
did not repeat the scan.

Checked for this document: all 580 current lines of
[SHA256SUMS](../experiments/evaluation/raw/partA-1b0e893-20261126T014300Z/SHA256SUMS)
pass `sha256sum -c`, and it differs from the pre-redaction file only in those
two files' lines. The pre-redaction contents are not available here, so the
original checksums cannot be re-verified and it cannot be independently
confirmed that redaction was the only change.

### 5.6 Other deviations accepted by the human gate

- The experiment engineer used `s3api get-object` (read-only) after the guard
  blocked `aws s3 cp`.
- Guard blocks on shell writes to protected paths occurred; the agent used its
  scratchpad or the edit tool instead.
- The dev environment window was extended past the Phase 12 48-hour limit to
  Sunday 2026-11-29. The gate records a month-to-date cost of USD 8.17, covered
  by credits.

## 6. Retrieval quality

**Pending Part B.**

## 7. Answer quality

**Pending Part B.**

## 8. Negative results and threats to validity

Entity resolution

1. The gold set has 92 of 200 target pairs; the top-up was skipped (human
   gate).
2. Only 4 pairs are DIFFERENT, so precision 1.000 is weak evidence (human
   gate). Its bootstrap interval is degenerate and carries no information
   about uncertainty.
3. 3 of the 4 DIFFERENT pairs were UNRESOLVED yet count as true negatives.
4. The gold set is not a random sample of candidate pairs.
5. 11 false negatives: 8 SAME researcher pairs unresolved and 3 pairs in
   conflict.
6. Three of the error pairs were relabeled after review. **Interpretation:**
   those labels may not be independent of system output.
7. Researcher recall is lower than in the earlier policy run (59/68 against
   67/68).
8. LLM adjudication was not evaluated. Two work match paths have no data.
9. **Phase record**
   ([known limitations](../modules/entity-resolution/KNOWN-LIMITATIONS.md)): the
   scored run (r2) is not byte-reproducible from the current tree. A replay gives
   a new run ID; its decision content was recorded as byte-identical apart from
   that ID. F14 (heuristic rejections recorded as certain) and D6 to D10 remain
   open.

Graph correctness

10. The graph is small (89 researchers, 24 works).
11. Communities coincide with components; no cross-community edge, no
    multi-hop path and no "no path" row exists in the data.
12. High orphan rates for topics, venues, institutions and works without
    topics (83% to 90%).
13. Spark ran locally, not on EMR. Neptune, DynamoDB and S3 Vectors were not
    checked.
14. The 50-edge audit covers 15 DBLP records, each edge with one supporting
    work, against a bounded excerpt.

Performance

15. Experiment C failed: no vectors written, 90600 item errors.
16. All messages were acknowledged despite the item errors. **Phase record:**
    this is a defect, open at Phase 15 close (3.5).
17. Experiments A, B, D, E and F were not run. **Phase record:** D, E and F were
    Bedrock-dependent or blocked by the acknowledge-on-reject defect.
18. Billed cost was not measured. The unverified estimate exceeds the cap.
    **Phase record:** mostly from DEBUG log volume.
19. One run only. The Part A outputs do not establish the cause of failure.
    **Phase record:** a Bedrock account-wide block (3.5).
20. The runner's drain mark was only 23.3 s inside the 1500 s drain timeout.

Security and deployment

21. **Phase record:** credential material and the API-key secret response were
    written to DEBUG logs (INC-20261125-debug-credential-logging). Contained,
    not resolved.
22. **Phase record:** the API token was sent over plain HTTP. Token rotation,
    existing log material, the Neptune audit log and ALB TLS were still open.
23. **Phase record:** smoke passed only 7 of 8 checks on both Phase 15 deploys,
    including the one live when observed. The `6ce56c9` deploy report status is
    FAILED (also in the Part A raw data).

Agentic factory

24. `factory-counts.csv` is an unedited copy of the needs-confirmation list, not
    a human ruling. The automated counts are accepted as reported; flagged rows
    remain unverified (human gate).
25. 22 of 30 classified primary work orders were not accepted first pass.
26. Four ownership or authority violations are recorded as landed, including
    Terraform state content printed by searches; two events are unresolved.
27. Review evidence is missing or review was not run for six phases.
28. Fewer repair files are archived than repair rounds documented.
29. Counts are hand-coded, partly minimums, and partly unverified.

Reproducibility and testing

30. No evidence of a full pipeline replay with output comparison; a Spark
    attempt and a later local pipeline record failed.
31. The clean-clone log does not name its commit; the agent did not run the
    build.
32. 24 tests were ignored. **Phase record:** all are deliberate deferrals, 5
    deferred findings are MAJOR and the Phase 11 gate was not met (5.1).
33. **Phase record** ([build.sbt](../build.sbt)): tests tagged `AwsIntegration`
    are excluded unless `SCHOLARCHAIN_AWS_IT=true`. That the clean-clone run
    excluded them is an inference from this default.
34. Two raw files were redacted after collection.
35. Evaluation scripts were untracked when the run was sealed.
36. Plan-to-deployment agreement is a record match, not a re-hash; for two of
    three deploys the only match is the deploy's own report.
37. Several derived tables are literals typed into the script (5.4).
38. Retrieval quality and answer quality have not been evaluated.

## 9. Open inconsistencies

| # | Observation | Status |
|---|---|---|
| 1 | The commit subject of `f79d007` calls `factory-counts.csv` a ruling; the file is the unchanged queue | **Closed** by the human gate: not a ruling; automated counts accepted as reported; flagged rows unverified |
| 2 | Experiment C drain: 1490.0 s from observations, 1476.7 s from the runner milestone | **Explained** in 3.3 (origin, drain definition, stamping point; partly inferred) |
| 3 | `scaledToZeroAfterSeconds` is 1868.2; the first desired-0 poll is at 1899.3 s after first send | **Explained** in 3.3 (origin, stamping point; partly inferred) |
| 4 | The same 11 PROBABILISTIC pairs get different intervals as `researcher/PROBABILISTIC` (coverage upper 1.0000) and `all/PROBABILISTIC` (0.9114); similarly `work` and `work/EXACT_ID` recall lower bounds differ (0.7619, 0.7500) | Open. Expected from per-group seeding with 1000 resamples; treat small-group bounds as unstable |
| 5 | Zero dead-lettered and zero retried messages alongside 90600 item errors and zero vector writes | **Closed as to intent** by a Phase record: a defect, still open (3.5) |
