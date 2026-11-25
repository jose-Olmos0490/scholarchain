# Phase 15, repair 01

## 1. False INDEX_UNAVAILABLE in vector ingestion
- Owner: storage-engineer
- Defect: VectorIngestor compared the deployed index definition to the configured one
  with ==. The non-filterable keys are an ordered Vector, and GetIndex does not return
  them in configured order, so a matching index was reported as different and every item
  in the batch was rejected with INDEX_UNAVAILABLE.
- Repair: added IndexDefinition.matches, which compares the non-filterable keys as a set
  and the other fields exactly. VectorIngestor now uses it.
- Files: modules/storage VectorIngestor.scala, VectorTypes.scala; tests VectorContracts.scala,
  VectorIngestionSpec.scala, VectorSpec.scala.
- Review: code-reviewer (read-only) found the storage change sound; one nit accepted:
  duplicate keys also compare equal as a set.
- State: committed with this record; not yet deployed (last deployed build 2365bf5).

## 2. sbt check
- First run: failed on scalafmt (VectorIngestionSpec.scala).
- Rerun: passed. 2383 succeeded, 0 failed, 24 ignored.

## 3. Terraform state contents printed by agent searches
- Occurrence 1: storage-engineer search matched terraform.tfstate.
- Occurrence 2: experiment-engineer grep -r over infra/terraform printed part of
  terraform.tfstate (account ID, resource names, endpoint, queue URLs, image tag).
- Agents reported no secret values and none copied into files; experiment-engineer
  scanned its new files for the account ID and endpoint.
- Open: guard does not block recursive searches reaching *.tfstate. Follow-up routed to
  factory-engineer (constitutional change, requires ratification and restart).
