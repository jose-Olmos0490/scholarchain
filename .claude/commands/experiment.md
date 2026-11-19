---
description: Run a controlled ScholarChain experiment from an approved manifest via experiment-engineer
argument-hint: <experiment-id> <env> <manifest-path>
---

# /experiment

**Responsible agent:** `experiment-engineer`.

**Arguments:** `$ARGUMENTS` (experiment ID, for example A to F; environment;
committed manifest path under `experiments/`).

## Gates

1. **Committed manifest.** The manifest is committed (YAML or JSON) and
   states resource limits, duration, concurrency, dataset snapshot, and
   expected cost.
2. **Human approval** of the manifest in the current session. A
   higher-than-previously-approved scale (EMR capacity, ECS maximums,
   concurrency, duration) is an expensive scale change and needs explicit
   approval.
3. **Approved environment.** The environment is in the human-set
   `SCHOLARCHAIN_APPROVED_ENVS`.
4. **Approved script only.** `scripts/run-experiment.sh --env <env>`, plus
   `scripts/archive-evidence.sh` for archiving.
5. **No tuning.** No change to production algorithms, thresholds, or
   configuration during or for the experiment.

## Work order to pass to experiment-engineer

> Use the experiment-engineer agent to execute experiment `<id>` from
> `<manifest>` in `<env>`. Verify the gates. Execute only
> `scripts/run-experiment.sh --env <env>` with the manifest. Collect raw
> metrics into a new `experiments/<id>/raw/` directory with synchronized
> timestamps. Generate derived plots and tables by script into
> `experiments/<id>/derived/`. Record the commit SHA, snapshot, configuration,
> resource limits, start and end time, Region, and run IDs. Keep failed runs.
> Return the standard report with limitations and estimated cost.

## Exit gate

Raw data is preserved and checksummed, derived outputs regenerate from raw,
and the human accepts the report. Interpretation is routed to
`documentation-engineer`.
