"""Validate the committed dataset samples.

Owner: dataset-engineer.
Usage: python3 -I check_samples.py <samples-dir>

Checks every file listed in <samples-dir>/SHA256SUMS:
  - SHA-256 matches, and no unlisted file exists in the samples tree
  - size <= 200000 bytes ("200 KB" = 200,000 bytes, human decision
    2026-11-19), record count <= 50
  - .jsonl: every line is a JSON object; Crossref lines carry no "abstract";
    OpenAlex lines carry no non-null, non-empty "abstract_inverted_index"
    (manifest datasets.openalex.sample_policy, human decision 2026-11-19)
  - .xml: well-formed; DBLP excerpts are parsed with the committed local DTD
    (entities resolved offline, no network access)
  - ORCID privacy policy (manifest datasets.orcid.privacy_policy), enforced
    as an ALLOWLIST: every element of an ORCID sample must sit on an
    allowed path (ORCID iD, name, affiliation summaries, work external
    identifiers) and carry only allowed attributes (path, put-code,
    visibility); anything else fails, e.g. person external-identifiers,
    work:title, common:source or history. Email, biography, address,
    other-name, keyword and researcher-url elements are also reported by
    name. All ORCID samples together hold at most 5 person records.
Exit status 1 on any failure.
"""

import hashlib
import json
import os
import sys
import xml.parsers.expat

MAX_BYTES = 200000
MAX_RECORDS = 50
ORCID_MAX_RECORDS = 5
OPENALEX_STRIPPED_KEY = "abstract_inverted_index"
ORCID_FORBIDDEN = {
    "email", "emails", "biography", "address", "addresses",
    "other-name", "other-names", "keyword", "keywords",
    "researcher-url", "researcher-urls",
}
# ORCID allowlist (manifest datasets.orcid.privacy_policy.kept_categories).
# Written independently of orcid_reduce.py so the validator checks the
# reducer's output rather than sharing its code.
ORCID_NS = {
    "http://www.orcid.org/ns/record": "record",
    "http://www.orcid.org/ns/common": "common",
    "http://www.orcid.org/ns/person": "person",
    "http://www.orcid.org/ns/personal-details": "personal-details",
    "http://www.orcid.org/ns/activities": "activities",
    "http://www.orcid.org/ns/education": "education",
    "http://www.orcid.org/ns/employment": "employment",
    "http://www.orcid.org/ns/distinction": "distinction",
    "http://www.orcid.org/ns/invited-position": "invited-position",
    "http://www.orcid.org/ns/membership": "membership",
    "http://www.orcid.org/ns/qualification": "qualification",
    "http://www.orcid.org/ns/service": "service",
}
ORCID_ALLOWED_ATTRS = {"path", "put-code", "visibility"}


def _orcid_allowed_paths():
    r = "record:record"
    ident = f"{r}/common:orcid-identifier"
    name = f"{r}/person:person/person:name"
    acts = f"{r}/activities:activities-summary"
    paths = {r, ident, f"{r}/person:person", name, acts}
    paths |= {f"{ident}/common:{x}" for x in ("uri", "path", "host")}
    paths |= {f"{name}/personal-details:{x}" for x in ("given-names", "family-name", "credit-name")}
    sections = {
        "distinctions": "distinction", "educations": "education",
        "employments": "employment", "invited-positions": "invited-position",
        "memberships": "membership", "qualifications": "qualification",
        "services": "service",
    }
    for section, pfx in sections.items():
        sec = f"{acts}/activities:{section}"
        grp = f"{sec}/activities:affiliation-group"
        summ = f"{grp}/{pfx}:{section[:-1]}-summary"
        org = f"{summ}/common:organization"
        dis = f"{org}/common:disambiguated-organization"
        paths |= {sec, grp, summ, org, dis, f"{org}/common:name"}
        paths |= {f"{summ}/common:{x}" for x in ("department-name", "role-title")}
        for d in ("start-date", "end-date"):
            paths.add(f"{summ}/common:{d}")
            paths |= {f"{summ}/common:{d}/common:{x}" for x in ("year", "month", "day")}
        paths |= {f"{dis}/common:{x}" for x in ("disambiguated-organization-identifier", "disambiguation-source")}
    works = f"{acts}/activities:works"
    ids = f"{works}/activities:group/common:external-ids"
    xid = f"{ids}/common:external-id"
    paths |= {works, f"{works}/activities:group", ids, xid}
    paths |= {f"{xid}/common:{x}" for x in ("external-id-type", "external-id-value", "external-id-relationship")}
    return frozenset(paths)


ORCID_ALLOWED_PATHS = _orcid_allowed_paths()


def orcid_qname(expat_name):
    """'uri local' (expat namespace mode) -> 'prefix:local'."""
    if " " in expat_name:
        uri, local = expat_name.rsplit(" ", 1)
        return f"{ORCID_NS.get(uri, '{' + uri + '}')}:{local}"
    return expat_name


DBLP_RECORD_TYPES = {
    "article", "inproceedings", "proceedings", "book", "incollection",
    "phdthesis", "mastersthesis", "www", "data",
}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def count_jsonl(path, errors, forbid_abstract, forbid_openalex_abstract=False):
    n = 0
    with open(path, "rb") as f:
        for i, line in enumerate(f, 1):
            if not line.strip():
                continue
            try:
                obj = json.loads(line)
            except ValueError as e:
                errors.append(f"{path}:{i}: invalid JSON ({e})")
                continue
            if not isinstance(obj, dict):
                errors.append(f"{path}:{i}: line is not a JSON object")
            elif forbid_abstract and "abstract" in obj:
                errors.append(f"{path}:{i}: Crossref abstract present (copyrighted; must be dropped)")
            elif forbid_openalex_abstract and obj.get(OPENALEX_STRIPPED_KEY) not in (None, {}, []):
                errors.append(f"{path}:{i}: OpenAlex {OPENALEX_STRIPPED_KEY} present and non-empty "
                              "(sample policy strips it, human decision 2026-11-19)")
            n += 1
    return n


def local_name(tag):
    return tag.rsplit(" ", 1)[-1].rsplit(":", 1)[-1]


def count_xml(path, errors, dtd_dir, orcid=False):
    """Parse XML; resolve only local DTD files that sit in dtd_dir.

    With orcid=True, report forbidden personal-data elements and count
    record:record elements (namespace-aware) as person records.
    """
    depth = 0
    orcid_records = 0
    forbidden = set()
    stack = []
    off_list = set()
    bad_attrs = set()
    records = 0
    root = None

    p = xml.parsers.expat.ParserCreate(namespace_separator=" ") if orcid else xml.parsers.expat.ParserCreate()
    p.SetParamEntityParsing(xml.parsers.expat.XML_PARAM_ENTITY_PARSING_ALWAYS)

    def external(context, base, system_id, public_id):
        name = os.path.basename(system_id or "")
        local = os.path.join(dtd_dir, name)
        if not name or not os.path.isfile(local):
            errors.append(f"{path}: external entity {system_id!r} not available locally")
            return 0
        sub = p.ExternalEntityParserCreate(context)
        with open(local, "rb") as f:
            sub.Parse(f.read(), True)
        return 1

    def start(name, attrs):
        nonlocal depth, records, root, orcid_records
        if orcid:
            ln = local_name(name)
            if ln in ORCID_FORBIDDEN:
                forbidden.add(ln)
            if name == "http://www.orcid.org/ns/record record":
                orcid_records += 1
            stack.append(orcid_qname(name))
            p_ = "/".join(stack)
            if p_ not in ORCID_ALLOWED_PATHS:
                off_list.add(p_)
            for a in attrs:
                if a not in ORCID_ALLOWED_ATTRS:
                    bad_attrs.add(f"{p_}/@{a}")
        if depth == 0:
            root = name
        elif depth == 1 and root == "dblp" and name in DBLP_RECORD_TYPES:
            records += 1
        depth += 1

    def end(name):
        nonlocal depth
        depth -= 1
        if orcid:
            stack.pop()

    p.ExternalEntityRefHandler = external
    p.StartElementHandler = start
    p.EndElementHandler = end
    try:
        with open(path, "rb") as f:
            p.Parse(f.read(), True)
    except xml.parsers.expat.ExpatError as e:
        errors.append(f"{path}: not well-formed XML ({e})")
        return 0
    if orcid:
        for ln in sorted(forbidden):
            errors.append(f"{path}: ORCID privacy policy forbids <{ln}> elements")
        # Report only the outermost element of each off-list subtree.
        for p_ in sorted(off_list):
            parent = p_.rsplit("/", 1)[0]
            if parent not in off_list:
                errors.append(f"{path}: element outside the ORCID allowlist: {p_}")
        for a in sorted(bad_attrs):
            errors.append(f"{path}: attribute outside the ORCID allowlist: {a}")
        if orcid_records == 0:
            errors.append(f"{path}: no ORCID record:record element found")
        return orcid_records
    return records if root == "dblp" else 1


def main():
    root = sys.argv[1]
    sums_path = os.path.join(root, "SHA256SUMS")
    errors = []
    listed = {}
    with open(sums_path, encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            digest, rel = line.split(None, 1)
            listed[rel.strip().lstrip("*")] = digest

    on_disk = set()
    for d, _dirs, files in os.walk(root):
        for name in files:
            rel = os.path.relpath(os.path.join(d, name), root)
            if rel != "SHA256SUMS":
                on_disk.add(rel)
    for rel in sorted(on_disk - set(listed)):
        errors.append(f"{rel}: present but not listed in SHA256SUMS")

    orcid_total = 0
    for rel in sorted(listed):
        path = os.path.join(root, rel)
        if not os.path.isfile(path):
            errors.append(f"{rel}: listed in SHA256SUMS but missing")
            continue
        size = os.path.getsize(path)
        if sha256(path) != listed[rel]:
            errors.append(f"{rel}: sha256 mismatch")
        if size > MAX_BYTES:
            errors.append(f"{rel}: {size} bytes > {MAX_BYTES}")
        if rel.endswith(".jsonl"):
            n = count_jsonl(path, errors, rel.startswith("crossref/"), rel.startswith("openalex/"))
        elif rel.endswith(".xml"):
            is_orcid = rel.startswith("orcid/")
            n = count_xml(path, errors, os.path.dirname(path), orcid=is_orcid)
            if is_orcid:
                orcid_total += n
        elif rel.endswith(".dtd"):
            n = 0
        else:
            errors.append(f"{rel}: unexpected sample type")
            n = 0
        if n > MAX_RECORDS:
            errors.append(f"{rel}: {n} records > {MAX_RECORDS}")
        print(f"CHECKED {rel} bytes={size} records={n}")

    print(f"ORCID person records across samples: {orcid_total} (max {ORCID_MAX_RECORDS})")
    if orcid_total > ORCID_MAX_RECORDS:
        errors.append(f"orcid/: {orcid_total} person records > {ORCID_MAX_RECORDS} (ORCID privacy policy)")

    if errors:
        for e in errors:
            print(f"FAIL {e}")
        print(f"samples: {len(errors)} error(s)")
        return 1
    print(f"samples: {len(listed)} file(s) valid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
