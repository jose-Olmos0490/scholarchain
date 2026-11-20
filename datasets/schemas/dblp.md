# DBLP XML: field-level schema notes

Owner: dataset-engineer. Source schema: `dblp-2023-06-28.dtd` (committed as
`datasets/samples/dblp/dblp-2023-06-28.dtd`). Raw schema id:
`dblp-xml/dtd-2023-06-28`. Pinned release: `dblp-2026-11-01.xml.gz`.

These notes describe the source as observed. Parsing and normalization belong
to `hadoop-engineer`. Canonical record shapes belong to `domain-engineer`.

## Document

| Aspect | Observed value |
|---|---|
| Prolog | `<?xml version="1.0" encoding="ISO-8859-1"?>` then `<!DOCTYPE dblp SYSTEM "dblp-2023-06-28.dtd">` |
| Root | `<dblp>`; every child is one publication record |
| Record elements | `article`, `inproceedings`, `proceedings`, `book`, `incollection`, `phdthesis`, `mastersthesis`, `www`, `data` |
| Characters | Non-ASCII letters appear as named entities (`&uuml;`, `&ouml;`, `&atilde;`, ...). They are defined in the DTD, so a parser needs the DTD or an equivalent entity table |
| Record boundaries | Records can sit on the same line (`</incollection><incollection ...>`). The sample keeps one record per line, but the release does not |
| Order | Grouped by key prefix. Observed order in the first 64 MiB of compressed data: `reference/`, `ms/` and `data/`, `dblpnote/`, `series/`, then `conf/` (from about 7.8 MiB compressed). No `journals/` article or `www` record was seen in the first 64 MiB |

## Record attributes

| Attribute | Required | Meaning | Linkage use |
|---|---|---|---|
| `key` | yes | Stable dblp key, e.g. `conf/dac/GuoXJLHYZZS26`. The prefix gives the stream. Prefixes observed so far: `reference/`, `data/`, `ms/`, `dblpnote/`, `series/`, `conf/` | primary source record ID (`dblp_key`) |
| `mdate` | yes | Last modification date `YYYY-MM-DD` | change detection between releases |
| `publtype` | no | NMTOKENS. Values in the excerpt: `encyclopedia`, `informal`, `version` | filtering |
| `cdate` | no | Declared in the DTD (CDATA) | not used |

## Child elements (order and repetition preserved)

| Element | Cardinality | Content | Linkage use and caveats |
|---|---|---|---|
| `author` | 0..n, ordered | Person name as dblp shows it. Homonyms get a 4-digit suffix (`Manish Singh 0001`). Optional attribute `orcid` | `normalized_author_sequence`. The `orcid` attribute is the only identity evidence in the record. A name is never identity evidence |
| `editor` | 0..n | As `author` (with `orcid`) | editors are not coauthors |
| `title` | 1 | Mixed content: `sub`, `sup`, `i`, `tt`, `ref` can appear. Usually ends with `.` | `normalized_title` |
| `year` | 0..1 | 4-digit year | `year`, time windows |
| `booktitle` | 0..1 | Venue name for `inproceedings` and `incollection` | `venue` |
| `journal` | 0..1 | Journal name for `article` | `venue` |
| `volume`, `number`, `pages`, `month` | 0..1 | Bibliographic detail | tie-breakers only |
| `ee` | 0..n | Electronic edition URL. `https://doi.org/<doi>` gives a DOI. Other URLs can appear. Optional attributes `type` (NMTOKENS, `oa` seen 5 times in the excerpt), `aux`, `label` | `doi`, `ee_urls` |
| `url` | 0..1 | dblp-relative TOC URL (`db/conf/dac/...html#Key`) | none |
| `crossref` | 0..1 | Key of the parent `proceedings` record. This is dblp's field, not Crossref Inc. | `crossref_key`, venue grouping |
| `stream` | 0..n | Structured venue stream reference (DTD change of 2023-05-10) | `venue` |
| `rel` | 0..n | Relation to another dblp key, with `type`, `label`, and `sort` (2023-06-28) | version grouping |
| `isbn`, `publisher`, `series`, `school`, `note`, `cite`, `cdrom`, `chapter`, `publnr`, `address` | 0..n | As named | not used for linkage |

## Observed in the committed excerpt (50 records)

- 48 `author` elements and 7 `editor` elements carry an `orcid` attribute.
- 6 conference records have entity-encoded author names. Example: `Jan J&uuml;rjens` with an `orcid` attribute.
- `dblpnote/neverpublished` and `dblpnote/error` are dblp housekeeping
  records. They are not publications and must be filtered.
- Encyclopedia `incollection` records (`publtype="encyclopedia"`) often have
  no author.
- All 30 `conf/` records come from one venue (DAC), because of dump order.

## Known gaps in the sample

- No `journals/` `article` records and no `www` (person or homepage)
  records. Both appear later in the release than the 16 MiB sampling cap.
  The human decided not to stream more of the dump. The per-record endpoints
  (`https://dblp.org/rec/<key>.xml`, `https://dblp.org/pid/<pid>.xml`)
  returned HTTP 202 with an HTML bot-challenge page on 2026-11-20, so these
  types are still missing. This is an open question for the human.

## Functional-run subset rule (approved, decision 2a; values 2026-11-20)

Keep a record if and only if its element name is `article` or
`inproceedings` and its `<year>` is an integer Y with 2000 <= Y <= 2025
(both bounds inclusive). Records with no integer year are excluded. The
record types are fixed by the approved rule.

- **Partial year.** 2026 is excluded (human decision, 2026-11-20). The
  2026-11-01 snapshot has 2026 records; the committed excerpt has four
  (`conf/dac/GuoXJLHYZZS26` and three `data/13/` records). The rule drops
  them.
- **Cap.** The functional run is bounded at 100,000 works (`max_works`).
  The deterministic rule for which works are kept when more pass the subset
  rule is not yet defined. It belongs to `hadoop-engineer` (Phase 4).
- **Analysis windows.** 2000-2005, 2006-2010, 2011-2015, 2016-2020,
  2021-2025.

The values live in `modules/core/src/main/resources/scholarchain/conf/dev.conf`
(owned by `domain-engineer`) and are not hardcoded in dataset scripts:

| Parameter | Config key | Environment variable |
|---|---|---|
| record types | `scholarchain.datasets.dblpSubset.recordTypes` | `DBLP_SUBSET_RECORD_TYPES` (pending human confirmation as a key) |
| start year | `scholarchain.datasets.dblpSubset.startYear` | `DBLP_SUBSET_START_YEAR` |
| end year | `scholarchain.datasets.dblpSubset.endYear` | `DBLP_SUBSET_END_YEAR` |
| cap | `scholarchain.datasets.dblpSubset.maxWorks` | `DBLP_SUBSET_MAX_WORKS` |
| analysis windows | `scholarchain.graph.windows` | `SCHOLARCHAIN_GRAPH_WINDOWS` |

See `datasets.dblp.subset_rule` and `datasets.dblp.subset_parameters` in the
manifest.
