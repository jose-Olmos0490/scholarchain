package scholarchain.hadoop

/** The one counter enum of the ScholarChain MapReduce pipeline (Phase 4 work order B item 12).
  *
  * It extends `java.lang.Enum`, so Hadoop reports it as the counter group
  * `scholarchain.hadoop.HadoopCounter`. The first thirteen names are required by the work order;
  * the rest are additional, documented in `modules/hadoop/JOBS.md`.
  */
enum HadoopCounter extends java.lang.Enum[HadoopCounter]:
  /** Records read by a mapper (one DBLP element, one JSON line, one ORCID file, one table row). */
  case INPUT_RECORDS

  /** Records written to a primary (non-reject, non-side) output table. */
  case ACCEPTED

  /** Records written to a `rejects_*` output as `RejectRecord`. */
  case REJECTED

  /** Identical records repeated under one key (kept once). */
  case DUPLICATE

  /** Different records under one key (one kept deterministically, the rest rejected or flagged).
    */
  case CONFLICT

  /** Blocking groups still above `maxBlockSize` after the fallback key. */
  case OVERSIZE_BLOCK

  /** Teams larger than `largeTeamThreshold` (still expanded). */
  case LARGE_AUTHOR_TEAM

  /** Teams larger than `maxPairExpansionTeamSize` (not expanded; routed to `oversize_team`). */
  case OVERSIZE_AUTHOR_TEAM

  /** Coauthor pair candidates written. */
  case EMITTED_PAIRS

  /** A record whose required key (record key, ID, blocking key) could not be computed. */
  case MISSING_KEY

  /** A produced record that failed its core `RecordContract` validation. */
  case SCHEMA_FAILURE

  /** A DBLP record that references an entity not declared by the configured DTD. */
  case UNKNOWN_ENTITY

  /** A repeated author mention removed from one publication team. */
  case DUPLICATE_AUTHOR_IN_TEAM

  // ---- additional counters ----

  /** DBLP records outside the configured subset (record type or year window); not rejects. */
  case OUT_OF_SUBSET

  /** DBLP works dropped by the deterministic `maxWorks` cap. */
  case CAPPED_WORKS

  /** Blocking groups at most `maxBlockSize` that were emitted as blocks. */
  case BLOCKS_EMITTED

  /** Members re-keyed with the fallback blocking key. */
  case REKEYED_MEMBERS

  /** Members of blocks routed to the oversize-blocks output. */
  case OVERSIZE_BLOCK_MEMBERS

  /** Match candidates written (after de-duplication by candidate ID). */
  case EMITTED_MATCH_CANDIDATES

  /** Exact-identifier outcomes written. */
  case EXACT_ID_OUTCOMES

  /** Exact identifiers carried by only one record (no outcome is written). */
  case EXACT_ID_SINGLETONS

  /** Records written to side outputs (large teams, histograms, statistics, nodes, edges). */
  case SIDE_OUTPUT_RECORDS
