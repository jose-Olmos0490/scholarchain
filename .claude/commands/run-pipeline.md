---
description: Run a bounded ScholarChain Hadoop + Spark data pipeline through approved scripts via experiment-engineer
argument-hint: <env> <dataset-manifest-or-subset> [run-notes]
---

# /run-pipeline

**Responsible agent:** `experiment-engineer`. A local bounded run for a
module's own gate (`--local`) belongs to that module's owner and does not use
this command.

**Arguments:** `$ARGUMENTS` (environment, dataset manifest entry or bounded
subset, optional notes).

## Gates

1. **Human approval** of the run in the current session, including the
   bounded input size and maximum EMR capacity.
2. **Approved environment.** The environment is in the human-set
   `SCHOLARCHAIN_APPROVED_ENVS`, and its infrastructure was applied by the
   human.
3. **Approved script only.** `scripts/run-bounded-pipeline.sh --env <env>`.
   No direct EMR, S3, or DynamoDB mutation. No `aws emr create-cluster` or
   scale changes outside the script.
4. **Bounded input.** The dataset subset is defined in
   `datasets/dataset-manifest.yaml`. Full corpora are never run by default.
5. **Clean tree.** The run is attributable to a commit SHA.
6. **Cost escalation.** A larger subset or capacity than previously approved
   is an expensive scale change: stop and get human approval.

## Work order to pass to experiment-engineer

> Use the experiment-engineer agent to run the bounded pipeline in `<env>` for
> `<subset>`. Verify the gates. Execute only `scripts/run-bounded-pipeline.sh
> --env <env>` with the approved parameters. Record the commit SHA, dataset
> snapshot, configuration, run IDs, start and end time, Region, Hadoop
> counters, Spark stage metrics, and output locations under
> `experiments/runs/<run-id>/`. Keep failed runs. Do not change production
> code or configuration. Return the standard report.

## Exit gate

The run record is complete with immutable raw outputs, and the human accepts
the report.
