# Crossref works: field-level schema notes

Owner: dataset-engineer. Primary route (human decision 2c, Phase 03 run 2):
the Crossref REST API `/works`, queried only for DOIs of the selected DBLP
subset. Raw schema id: `crossref-api-jsonl/v1.0.0` (REST API works item,
`message-version` 1.0.0, observed 2026-11-20). REST API documentation:
https://www.crossref.org/documentation/retrieve-metadata/rest-api/ .

Alternative (documented, not implemented): the bulk Public Data File
("March 2026 Public Data File from Crossref", DOI 10.13003/nggf-vt1j) from
the Requester Pays S3 bucket `api-snapshots-reqpays-crossref`. The requester
is billed. BitTorrent is not an approved route. UNVERIFIED: that bulk lines
have the same item shape as API items; see
`crossref.verification.source.alternative_bulk.item_schema` in the manifest.

## Item keys seen in the sample

`indexed, publisher-location, reference-count, publisher, license,
content-domain, chair, published-print, DOI, type, created, page, source,
is-referenced-by-count, title, prefix, author, member, event,
container-title, link, deposited, score, resource, proceedings-subject,
issued, references-count, URL, published, update-policy, published-online,
reference, alternative-id, assertion, subtitle, aliases, funder`.

`abstract` is removed from the committed sample because abstracts carry
publisher or author copyright. `score` is an API search artifact, not
bibliographic data.

## Full-mode output layout (API route)

`download.sh --full --dataset crossref --out <out> --doi-list <file>` binds
`<out>` to one normalised DOI list (`<out>/CROSSREF_RUN`: `doi_list_sha256`,
`doi_count`, `batch_size`, `api_url`; `<out>/DOI_LIST.txt`). A non-empty
`<out>` recorded for another list or batch size, or with no record, is
refused. Each batch `crossref-api-batch-NNNNNN.jsonl` has a `.jsonl.sha256`
and a `.meta` (`doi_set_sha256`, `doi_count`, `retrieved_at`). A batch is
skipped only if all three verify and every item DOI belongs to the batch.
`<out>/RETRIEVED_AT` is the latest batch `retrieved_at` and
`<out>/RETRIEVAL_WINDOW` records the first and last. The snapshot of record
for the S3 prefix is the date in `RETRIEVED_AT`.

| Field | Type | Linkage use and caveats |
|---|---|---|
| `DOI` | string, case-insensitive | Primary join key with DBLP `ee` DOIs and OpenAlex `doi`. Lower-case it before joining |
| `title` | list of strings | `title`. Usually one element |
| `author[]` | ordered list | Keys: `given`, `family`, `sequence` (`first`/`additional`), `ORCID` (URL form `https://orcid.org/...`), `authenticated-orcid` (bool), `affiliation[]` (objects with `name`; only `name` was seen in the sample), `role[]`. `ORCID` is identity evidence. Treat `authenticated-orcid: false` as weaker, member-asserted evidence |
| `issued`, `published`, `published-print`, `published-online` | `{date-parts: [[Y, M, D]]}` with partial dates allowed | `year` |
| `container-title` | list of strings | `venue` |
| `event` | `{name, location, acronym, sponsor[]}` | conference venue matching |
| `type` | string, e.g. `proceedings-article` | filtering |
| `reference[]` | list of `{key, DOI?, unstructured?, ...}` | citation context (optional) |
| `ISSN`, `ISBN` | lists | venue matching |
| `member`, `prefix`, `publisher` | strings | provenance |
| `indexed`, `deposited`, `created` | `{date-time, timestamp, ...}` | incremental refresh (`from-index-date`, `from-update-date`) |

## Observed in the sample (10 DAC DOIs)

- Only the 2026 record (`10.1145/3770743.3803897`) has author ORCIDs (9 of
  9). The older records have none.
- Several older records have no `reference` list.
