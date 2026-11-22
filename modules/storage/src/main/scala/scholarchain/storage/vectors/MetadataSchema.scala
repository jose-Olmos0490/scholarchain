package scholarchain.storage.vectors

/** The versioned vector metadata schema of one index. All five indexes share schema
  * [[MetadataSchema.Version]]; each index gets its own value so that the declared non-filterable
  * keys, caps and budget are visible per index.
  */
final case class MetadataSchema(
    indexName: String,
    version: String,
    filterableKeys: Vector[String],
    nonFilterableKeys: Vector[String],
    policy: MetadataPolicy
)

object MetadataSchema:
  val Version = "vector-metadata-v1"

  /** In emission order; `topicIds`, `institutionIds` and `communityId` are omitted when empty. */
  val FilterableKeyList: Vector[String] = Vector(
    "entityId",
    "documentType",
    "windowStartYear",
    "windowEndYear",
    "topicIds",
    "institutionIds",
    "sourceDataset",
    "communityId"
  )

  def forIndex(index: IndexDefinition, settings: IngestionSettings): MetadataSchema =
    MetadataSchema(
      index.name,
      Version,
      FilterableKeyList,
      index.nonFilterableKeys,
      settings.metadataPolicy
    )
