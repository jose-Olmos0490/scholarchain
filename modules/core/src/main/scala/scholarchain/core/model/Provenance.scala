package scholarchain.core.model

import upickle.default.ReadWriter

/** Provenance carried by every ScholarChain record.
  *
  * @param schemaVersion
  *   `provenance-v1`
  * @param sourceDataset
  *   dataset the record came from; `SCHOLARCHAIN` for records derived by a pipeline stage
  * @param sourceRecordId
  *   ID of the record in that dataset (for example a dblp key or an OpenAlex ID); for derived
  *   records, the stable ID of the primary input
  * @param snapshot
  *   dataset snapshot or release label (for example `2026-11-01`)
  * @param runId
  *   stable run ID (`run:<sha256>`) of the run manifest that produced the record
  * @param policyVersion
  *   entity-resolution, graph or fusion policy version, where applicable
  * @param modelVersion
  *   model ID and version, where a model contributed
  * @param promptVersion
  *   prompt version, where a prompt contributed
  * @param derivedFrom
  *   stable IDs of further input records (may be empty)
  */
final case class Provenance(
    schemaVersion: String,
    sourceDataset: SourceType,
    sourceRecordId: String,
    snapshot: String,
    runId: String,
    policyVersion: Option[String],
    modelVersion: Option[String],
    promptVersion: Option[String],
    derivedFrom: Vector[String]
) derives ReadWriter
