# ORCID record XML: field-level schema notes

Owner: dataset-engineer. Pinned release: ORCID Public Data File 2026 (public
data as of 2026-10-13, DOI 10.23640/07243.34697766.v1). Message schema
v3.0. Raw schema id: `orcid-xml/v3.0-pdf-2026`. Schema documentation:
https://info.orcid.org/documentation/integration-guide/working-with-bulk-data/ .

## Layout of the Public Data File

- `ORCID_2026_10_summaries.tar.gz`: one XML per iD at
  `ORCID_2026_10_summaries/<last 3 digits>/<iD>.xml` (observed in the first
  entries of the archive). The archive also contains PAX headers.
- `ORCID_2026_10_activities_{0..9,X}.tar.gz`: full activity items (works and
  affiliations) split by the iD checksum character.
- Root element `record:record` in namespace
  `http://www.orcid.org/ns/record`. This root was observed both in the
  archive and in the Public API v3.0 `/record` response that forms the
  committed sample.

## Privacy policy (human decision 2d, Phase 03 run 2)

Public ORCID data only. Keep only four categories: the ORCID iD, the name,
affiliations, and work identifiers. Emails, biographies and addresses are
never kept; other-names, keywords, researcher-urls, person external
identifiers, history, sources and work titles are stripped as well. At most
5 real-person records may appear in samples. `datasets/scripts/lib/orcid_reduce.py`
implements the allowlist. `datasets/scripts/validate-samples.sh` enforces it
independently as an allowlist (review finding 7): every element must sit on
an allowed path (the rows marked KEPT below, plus their date parts and
disambiguated-organization children) and carry only `path`, `put-code` or
`visibility` attributes. Anything else fails, for example person
`external-identifiers`, `work:title`, `common:source` or `history`. More than
5 ORCID records in total also fails. The manifest entry
`datasets.orcid.privacy_policy` is the authoritative text.

The table below describes the raw ORCID v3.0 record (observed in the run-1
API response, which is not retained). Rows marked STRIPPED are removed by the
policy and must not be stored by any pipeline stage.

## Element paths of the raw record

| Path (prefixes are namespace short names) | Linkage use and caveats |
|---|---|
| `common:orcid-identifier/{uri,path,host}` | `path` is the iD `0000-0002-1825-0097` and the identity key |
| `person:person/person:name/personal-details:{given-names,family-name}` (and `credit-name` when the holder sets one; absent in the sample) | names, display only. A name is never identity evidence |
| `person:person/other-name:other-names/other-name:other-name/other-name:content` | STRIPPED (name variants) |
| `person:person/person:biography/personal-details:content` | STRIPPED (free text) |
| `person:person/researcher-url:researcher-urls` | STRIPPED (homepage URLs) |
| `person:person/address:addresses`, `person:person/keyword:keywords` | STRIPPED |
| `person:person/external-identifier:external-identifiers/external-identifier:external-identifier/common:{external-id-type,external-id-value,external-id-url,external-id-relationship}` | STRIPPED (cross-system person IDs, e.g. `Scopus Author ID`; not among the four kept categories) |
| `person:person/email:emails` | STRIPPED. Never stored even if present |
| `activities:activities-summary/activities:works/activities:group/common:external-ids` | KEPT: `external-id-type`, `external-id-value`, `external-id-relationship` of each group. Types in the reduced sample: `doi`, `eid` |
| `.../activities:group/work:work-summary/{work:title, work:type, common:publication-date, work:journal-title}` | STRIPPED (titles and other work fields are not work identifiers) |
| `activities:activities-summary/activities:employments/activities:affiliation-group/employment:employment-summary` | KEPT: `department-name`, `role-title`, `start-date`, `end-date`, organization `name` and `disambiguated-organization` (`disambiguation-source` is `RINGGOLD` in both sample entries). Organization `address` is STRIPPED |
| `activities:activities-summary/activities:{educations,distinctions,invited-positions,memberships,qualifications,services}` | KEPT with the same allowlist as employments (empty in the sample) |
| `history:history/*` | STRIPPED |
| `common:source`, `common:last-modified-date` on every item | STRIPPED from samples (source names can be personal names) |

Every item carries a `visibility` attribute. Only `public` items appear in
the Public Data File and the Public API.

## Sample

`orcid/orcid-0000-0002-1825-0097-record.xml` is ORCID's fictitious
demonstration record (Josiah Carberry), reduced to the four kept categories:
iD, name (given and family names), 2 employment affiliations, and the
external identifiers of 6 work groups. It holds no email, biography,
address, other-name, keyword or researcher-url element. No real-person
record is committed (0 of the allowed 5), so the sample is not
representative of typical record size or completeness.
