package scholarchain.spark

import org.apache.spark.sql.DataFrame

/** Lineage cuts at the job's stage boundaries.
  *
  * The graph builder reuses its intermediate tables (identity groups, AUTHORED, mapped pairs,
  * edges, researcher-paper rows) in several downstream plans. Without a cut, every reuse copies
  * the whole upstream plan tree, so plans (and task binaries) grow multiplicatively. Each cut
  * materializes the table eagerly: a reliable checkpoint to `spark.checkpointDir` when one is
  * configured, otherwise a local checkpoint (executor block storage, not fault tolerant).
  */
object Lineage:
  def cut(df: DataFrame, reliable: Boolean): DataFrame =
    if reliable then df.checkpoint(eager = true) else df.localCheckpoint(eager = true)
