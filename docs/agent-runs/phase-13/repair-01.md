# Phase 13 Repair 01 (human-recorded; the guard blocks agent writes to repair records)

Trigger: human rulings (a) deploy.sh stays rollout-only; (b) CONFIRM_CLEANUP=<env>-<account-id>. Plus build/ and evidence/ missing from .gitignore.
Owners: infra-engineer (cleanup.sh, infra/terraform/README.md), build-engineer (.gitignore)
Rerun evidence: bash -n and shellcheck clean; check.sh --skip-sbt passed; git check-ignore matches /build/

## infra-engineer report

## build-engineer report
