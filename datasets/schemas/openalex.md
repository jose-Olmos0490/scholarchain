# OpenAlex snapshot: field-level schema notes

Owner: dataset-engineer. Pinned release: `2026-10-14` (from
`data/jsonl/manifest.json`, sha256
`2b3d611b3ec93912b7b655ee139310936048a689c0f3508052a73af03a6c453f`). Raw
schema id: `openalex-jsonl/2026-10-14`. Official field documentation:
https://help.openalex.org/ . The field lists below come from the committed
samples.

## Layout

- `data/jsonl/<entity>/updated_date=YYYY-MM-DD/part_NNNN.gz`. Each file is
  gzip JSON Lines with one entity per line.
- `data/jsonl/manifest.json` has `date`, `format`, `meta.{record_count,
  content_length}`, and `entities[].files[].{url, meta.{content_length,
  record_count}}`. It is written last, so its presence means the release is
  complete (bucket README).
- `data/jsonl/works/deleted_ids.csv.gz` (234,102,326 bytes) and per-entity
  `manifest.json` objects exist beside the partitions. They are not listed in
  the top-level manifest.
- A Parquet copy is under `data/parquet/`.

## Work (entity `works`)

Top-level keys seen in the snapshot excerpt:
`id, doi, title, display_name, ids, indexed_in, publication_date,
publication_year, language, type, authorships, authors_count,
corresponding_author_ids, corresponding_institution_ids, primary_topic,
topics, keywords, concepts, locations, locations_count, primary_location,
best_oa_location, sustainable_development_goals, study_designs, awards,
funders, institutions, countries_distinct_count, institutions_distinct_count,
open_access, is_paratext, is_retracted, is_xpac, biblio, volume,
referenced_works, referenced_works_count, related_works,
abstract_inverted_index, cited_by_count, counts_by_year, apc_list, apc_paid,
fwci, citation_normalized_percentile, cited_by_percentile_year, mesh,
has_content, has_fulltext, created_date, updated_date`.

| Field | Type | Linkage use and caveats |
|---|---|---|
| `id` | URL string `https://openalex.org/W...` | `openalex_work_id` |
| `doi` | URL string `https://doi.org/...` or null | Strip the prefix and lower-case it before joining with DBLP or Crossref. The API can answer a requested DOI with a work whose primary `doi` is an alias. The linked sample shows `10.1145/1065579.1065662` answered by `W2131909792` with DOI `10.1109/dac.2005.193824` |
| `ids` | object (`openalex`, `doi`, `mag`, `pmid`, ...) | secondary identifiers |
| `title` / `display_name` | string | `title`. Not normalized |
| `publication_year` / `publication_date` | int / `YYYY-MM-DD` | `year` |
| `type` | string (e.g. `article`) | filtering |
| `authorships[]` | ordered list | Author sequence. Keys: `author{id, display_name, orcid, observed_orcids}`, `author_position` (`first`/`middle`/`last`), `raw_author_name`, `raw_orcid`, `is_corresponding`, `institutions[]{id, display_name, ror, country_code, type, lineage}`, `affiliations[]`, `raw_affiliation_strings[]`, `countries[]`. `author.id` can be null |
| `primary_location.source` | object | `id`, `display_name`, `issn_l`, `issn`, `host_organization*`, `type`. Used for venue linkage |
| `topics[]`, `primary_topic` | object | `id`, `display_name`, `score`, `subfield`, `field`, `domain`. Used for topic filters |
| `is_xpac` | bool | The API default view excludes xpac works. The snapshot includes them |
| `abstract_inverted_index` | object or null | Not used for linkage. Present in the raw snapshot; REMOVED from every committed sample (see "Sample policy" below) |
| `updated_date` | ISO timestamp | Incremental sync key |

## Author (entity `authors`)

Keys: `id, display_name, display_name_alternatives, raw_author_names,
full_name, orcid, observed_orcids, works_count, cited_by_count,
summary_stats, ids, affiliations, last_known_institutions, topics,
topic_share, x_concepts, sources, counts_by_year, works_api_url,
updated_date, created_date`.

- `id` (`https://openalex.org/A...`) is an algorithmic cluster ID. The
  release notes record past over-merges and splits.
- `orcid` and `observed_orcids` are the only strong identity evidence on an
  author.
- The committed author excerpt comes from a small, recent partition. Its 21
  profiles are sparse (`works_count` 0) and are not typical of linked
  authors.

## Sample policy (human decision 2026-11-19, review finding 4)

`abstract_inverted_index` is removed from every committed OpenAlex sample,
for the same reason Crossref abstracts are dropped: abstracts are publisher
or author copyright, and an inverted index can be turned back into the text.
The removal is byte-preserving (`sample_tools.py strip_key_bytes`): every
other byte of a line is unchanged, including number formats such as
`2.0E-4`, and the line parses to the original record minus that key. For
snapshot excerpts the strip happens before the 50-record / 200,000-byte caps.
`download.sh` applies it before install, and `validate-samples.sh` fails on a
non-null, non-empty `abstract_inverted_index` in any OpenAlex sample. Parsers
must therefore accept works lines without this key. The raw snapshot under
`s3://${RAW_BUCKET}/openalex/` keeps it.

## Samples

| File | Origin |
|---|---|
| `openalex/openalex-works-snapshot-2026-10-14-excerpt.jsonl` | Pinned snapshot, whole lines with `abstract_inverted_index` removed (30 records, 197,230 bytes; 1 record had a non-empty index) |
| `openalex/openalex-authors-snapshot-2026-10-14-excerpt.jsonl` | Pinned snapshot, unmodified lines (author entities have no abstract key) |
| `openalex/openalex-works-api-linked.jsonl` | Live API with `select=` projection (the key is not selected). Not the pinned snapshot |
