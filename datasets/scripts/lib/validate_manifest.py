"""Validate datasets/dataset-manifest.yaml.

Owner: dataset-engineer.
Usage: python3 -I validate_manifest.py <manifest.yaml> <samples-dir>

Exit status 0 only when:
  - every selected dataset has every required field (key present);
  - every null value anywhere in a dataset entry has a verification entry
    with status UNVERIFIED and a non-empty note telling a human what to check;
  - every verification entry is VERIFIED (with evidence) or UNVERIFIED (with
    a note) and names a path that exists in the entry;
  - owning_agent is dataset-engineer and the S3 raw prefix uses the
    ${RAW_BUCKET} placeholder;
  - source URLs use https;
  - the identity limitation is recorded;
  - every sample entry matches the file on disk and SHA256SUMS, respects the
    50-record / 200000-byte caps ("200 KB" = 200,000 bytes, human decision
    2026-11-19), and every sample file has an entry;
  - the DBLP subset rule is the approved one: record_types exactly
    [article, inproceedings] and a year window whose bounds are integers or
    ${CONFIG} placeholders (start <= end when both are integers), and the
    rule text is no longer PROPOSED;
  - the DBLP subset values of the human decision of 2026-11-20 are well
    formed: end_year is before the snapshot year (partial year excluded),
    max_works is a positive integer, analysis_windows are integer ranges
    with start <= end, sorted, non-overlapping and inside year_window, and
    the configuration block cites domain-engineer's config keys and
    environment variables exactly;
  - the ORCID privacy policy is recorded: 4 kept categories (none of them
    email, biography or address), emails/biography/addresses stripped, and
    at most 5 real-person sample records;
  - the OpenAlex sample policy is recorded (human decision 2026-11-19):
    sample_policy.strip_key is abstract_inverted_index, with decided_by
    and a reason, and every OpenAlex sample's transformation names it.
Prints every UNVERIFIED field so reviewers see them in one place.
"""

import hashlib
import os
import re
import sys

import yaml

REQUIRED = [
    "name",
    "purpose",
    "owning_agent",
    "source.landing_url",
    "source.download_url",
    "source.documentation_urls",
    "license.id",
    "license.url",
    "license.notes",
    "snapshot.date",
    "snapshot.label",
    "retrieval.script",
    "retrieval.command",
    "retrieval.sample_command",
    "retrieval.dry_run_command",
    "format.file_format",
    "format.compression",
    "size.expected",
    "size.observed",
    "size.observed.remote_bytes",
    "size.observed.local_bytes",
    "s3_raw_prefix",
    "integrity.policy",
    "integrity.publisher_checksum",
    "integrity.sha256",
    "schema_version.source_schema",
    "schema_version.raw_schema_id",
    "subset_rule.functional_run",
    "subset_rule.sample",
    "linkage_fields",
    "update_strategy",
    "privacy_notes",
    "limitations",
    "validation_commands",
    "samples",
    "verification",
]
NON_EMPTY_LISTS = [
    "source.documentation_urls", "linkage_fields", "privacy_notes",
    "limitations", "validation_commands", "samples",
]
SAMPLE_FIELDS = [
    "path", "description", "source_url", "retrieval_command", "retrieved_at",
    "subset_rule", "transformation", "records", "bytes", "sha256", "license",
]
IDENTITY_LIMITATION = "A matching author name is not sufficient evidence of author identity."
RAW_PREFIX = re.compile(r"^s3://\$\{RAW_BUCKET\}/[a-z0-9_-]+/snapshot=([0-9A-Za-z-]+|\$\{[A-Z_]+\})/$")
PLACEHOLDER = re.compile(r"^\$\{[A-Z][A-Z0-9_]*\}$")
DBLP_RECORD_TYPES = ["article", "inproceedings"]
DBLP_VALUES_FILE = "modules/core/src/main/resources/scholarchain/conf/dev.conf"
DBLP_CONFIG_KEYS = {
    "record_types": ("scholarchain.datasets.dblpSubset.recordTypes", "DBLP_SUBSET_RECORD_TYPES"),
    "start_year": ("scholarchain.datasets.dblpSubset.startYear", "DBLP_SUBSET_START_YEAR"),
    "end_year": ("scholarchain.datasets.dblpSubset.endYear", "DBLP_SUBSET_END_YEAR"),
    "max_works": ("scholarchain.datasets.dblpSubset.maxWorks", "DBLP_SUBSET_MAX_WORKS"),
    "analysis_windows": ("scholarchain.graph.windows", "SCHOLARCHAIN_GRAPH_WINDOWS"),
}
ORCID_FORBIDDEN_KEPT = ("email", "biograph", "address")
MAX_BYTES = 200000
OPENALEX_STRIP_KEY = "abstract_inverted_index"
MAX_RECORDS = 50


def get(node, dotted):
    for part in dotted.split("."):
        if isinstance(node, dict) and part in node:
            node = node[part]
        else:
            raise KeyError(dotted)
    return node


def has(node, dotted):
    try:
        get(node, dotted)
        return True
    except KeyError:
        return False


def null_paths(node, prefix=""):
    if node is None:
        yield prefix
    elif isinstance(node, dict):
        for k, v in node.items():
            if prefix == "" and k == "verification":
                continue
            yield from null_paths(v, f"{prefix}.{k}" if prefix else str(k))
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from null_paths(v, f"{prefix}.{i}")


def urls_in(node):
    if isinstance(node, str):
        if re.match(r"^[a-z]+://", node):
            yield node
    elif isinstance(node, dict):
        for v in node.values():
            yield from urls_in(v)
    elif isinstance(node, list):
        for v in node:
            yield from urls_in(v)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def check_dblp_subset(ds):
    """Human decision 2a: subset bounded by record type and a year window
    whose bounds are parameters (configuration placeholders or integers)."""
    errs = []
    if not isinstance(ds, dict):
        return errs
    sp = ds.get("subset_parameters")
    if not isinstance(sp, dict):
        return ["dblp: subset_parameters missing (record_types + year_window required)"]
    if sp.get("record_types") != DBLP_RECORD_TYPES:
        errs.append(f"dblp: subset_parameters.record_types must be {DBLP_RECORD_TYPES}")
    yw = sp.get("year_window")
    if not isinstance(yw, dict):
        return errs + ["dblp: subset_parameters.year_window missing"]
    bounds = {}
    for k in ("start_year", "end_year"):
        v = yw.get(k)
        if isinstance(v, bool) or not (isinstance(v, int) or (isinstance(v, str) and PLACEHOLDER.match(v))):
            errs.append(f"dblp: subset_parameters.year_window.{k} must be an integer or a ${{CONFIG}} placeholder (got {v!r})")
        elif isinstance(v, int):
            bounds[k] = v
    if len(bounds) == 2 and bounds["start_year"] > bounds["end_year"]:
        errs.append("dblp: subset_parameters.year_window start_year > end_year")
    snap = str((ds.get("snapshot") or {}).get("date", ""))
    if "end_year" in bounds and re.match(r"^[0-9]{4}-", snap) and bounds["end_year"] >= int(snap[:4]):
        errs.append(f"dblp: subset_parameters.year_window.end_year {bounds['end_year']} must be before the "
                    f"snapshot year {snap[:4]} (partial year excluded, human decision 2026-11-20)")
    errs.extend(check_dblp_cap(sp))
    errs.extend(check_dblp_windows(sp, bounds))
    errs.extend(check_dblp_config(sp))
    rule = str((ds.get("subset_rule") or {}).get("functional_run", ""))
    if "PROPOSED" in rule:
        errs.append("dblp: subset_rule.functional_run is still PROPOSED")
    return errs


def check_dblp_cap(sp):
    """Human decision 2026-11-20: max_works is a positive integer cap."""
    v = sp.get("max_works")
    if isinstance(v, bool) or not isinstance(v, int) or v <= 0:
        return [f"dblp: subset_parameters.max_works must be a positive integer (got {v!r})"]
    return []


def check_dblp_windows(sp, bounds):
    """Human decision 2026-11-20: analysis windows are inclusive integer
    ranges, each start <= end, sorted by start, non-overlapping, and inside
    year_window (which therefore needs integer bounds)."""
    ws = sp.get("analysis_windows")
    if not isinstance(ws, list) or not ws:
        return ["dblp: subset_parameters.analysis_windows must be a non-empty list"]
    errs = []
    parsed = []
    for i, w in enumerate(ws):
        s = w.get("start_year") if isinstance(w, dict) else None
        e = w.get("end_year") if isinstance(w, dict) else None
        if any(isinstance(x, bool) or not isinstance(x, int) for x in (s, e)):
            errs.append(f"dblp: analysis_windows[{i}] needs integer start_year and end_year (got {w!r})")
            continue
        if s > e:
            errs.append(f"dblp: analysis_windows[{i}] {s}-{e} has start_year > end_year")
        parsed.append((i, s, e))
    for (_, ps, pe), (i, s, e) in zip(parsed, parsed[1:]):
        if s < ps:
            errs.append(f"dblp: analysis_windows[{i}] {s}-{e} is not sorted by start_year (follows {ps}-{pe})")
        elif s <= pe:
            errs.append(f"dblp: analysis_windows[{i}] {s}-{e} overlaps {ps}-{pe}")
    if len(bounds) != 2:
        errs.append("dblp: analysis_windows containment needs integer year_window bounds")
    else:
        for i, s, e in parsed:
            if s < bounds["start_year"] or e > bounds["end_year"]:
                errs.append(f"dblp: analysis_windows[{i}] {s}-{e} is outside year_window "
                            f"{bounds['start_year']}-{bounds['end_year']}")
    return errs


def check_dblp_config(sp):
    """The configuration keys and environment variables are domain-engineer's
    names (Phase 03 Work Order B repair 2); the manifest must cite them
    exactly so the two never drift apart."""
    conf = sp.get("configuration")
    if not isinstance(conf, dict):
        return ["dblp: subset_parameters.configuration missing"]
    errs = []
    if conf.get("values_file") != DBLP_VALUES_FILE:
        errs.append(f"dblp: subset_parameters.configuration.values_file must be {DBLP_VALUES_FILE}")
    keys = conf.get("keys") if isinstance(conf.get("keys"), dict) else {}
    for name, (ckey, env) in DBLP_CONFIG_KEYS.items():
        k = keys.get(name)
        if not isinstance(k, dict) or k.get("config_key") != ckey or k.get("env_var") != env:
            errs.append(f"dblp: subset_parameters.configuration.keys.{name} must map {ckey} via {env}")
    return errs


def check_orcid_policy(ds):
    """Human decision 2d: ORCID privacy policy recorded and complete."""
    if not isinstance(ds, dict):
        return []
    pol = ds.get("privacy_policy")
    if not isinstance(pol, dict):
        return ["orcid: privacy_policy missing"]
    errs = []
    if pol.get("max_real_person_sample_records") != 5:
        errs.append("orcid: privacy_policy.max_real_person_sample_records must be 5")
    kept = pol.get("kept_categories") or []
    if len(kept) != 4:
        errs.append("orcid: privacy_policy.kept_categories must list exactly 4 categories (iD, name, affiliations, work identifiers)")
    for k in kept:
        if any(word in str(k).lower() for word in ORCID_FORBIDDEN_KEPT):
            errs.append(f"orcid: privacy_policy.kept_categories must not keep {k!r}")
    stripped = str(pol.get("stripped", "")).lower()
    for word in ("emails", "biography", "addresses"):
        if word not in stripped:
            errs.append(f"orcid: privacy_policy.stripped must name {word}")
    return errs


def check_openalex_policy(ds):
    """Human decision 2026-11-19 (review finding 4): abstract_inverted_index
    is stripped from every committed OpenAlex sample."""
    if not isinstance(ds, dict):
        return []
    pol = ds.get("sample_policy")
    if not isinstance(pol, dict):
        return ["openalex: sample_policy missing (abstract_inverted_index stripping, human decision 2026-11-19)"]
    errs = []
    if pol.get("strip_key") != OPENALEX_STRIP_KEY:
        errs.append(f"openalex: sample_policy.strip_key must be {OPENALEX_STRIP_KEY}")
    for k in ("decided_by", "reason", "enforcement"):
        if not pol.get(k):
            errs.append(f"openalex: sample_policy.{k} missing")
    for i, smp in enumerate(ds.get("samples") or []):
        if isinstance(smp, dict) and OPENALEX_STRIP_KEY not in str(smp.get("transformation", "")):
            errs.append(f"openalex.samples[{i}]: transformation must state how {OPENALEX_STRIP_KEY} was handled (sample_policy)")
    return errs


def main():
    manifest_path, samples_dir = sys.argv[1], sys.argv[2]
    errors = []
    unverified = []
    with open(manifest_path, encoding="utf-8") as f:
        doc = yaml.safe_load(f)

    if doc.get("manifest_schema_version") != 1:
        errors.append("manifest_schema_version must be 1")
    if doc.get("owner") != "dataset-engineer":
        errors.append("owner must be dataset-engineer")
    selected = doc.get("selected_datasets") or []
    datasets = doc.get("datasets") or {}
    for want in ("dblp",):
        if want not in selected:
            errors.append(f"selected_datasets must include {want}")
    for name in selected:
        if name not in datasets:
            errors.append(f"selected dataset {name} has no entry")

    sums = {}
    sums_path = os.path.join(samples_dir, "SHA256SUMS")
    if os.path.isfile(sums_path):
        with open(sums_path, encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    d, rel = line.split(None, 1)
                    sums[rel.strip().lstrip("*")] = d
    else:
        errors.append("samples SHA256SUMS missing")
    described = set()

    for name in selected:
        ds = datasets.get(name)
        if not isinstance(ds, dict):
            continue
        tag = f"{name}"
        for path in REQUIRED:
            if not has(ds, path):
                errors.append(f"{tag}: missing required field {path}")
        for path in NON_EMPTY_LISTS:
            if has(ds, path):
                v = get(ds, path)
                if not isinstance(v, list) or not v:
                    errors.append(f"{tag}: {path} must be a non-empty list")
        if ds.get("owning_agent") != "dataset-engineer":
            errors.append(f"{tag}: owning_agent must be dataset-engineer")
        prefix = ds.get("s3_raw_prefix")
        if not isinstance(prefix, str) or not RAW_PREFIX.match(prefix):
            errors.append(f"{tag}: s3_raw_prefix must look like s3://${{RAW_BUCKET}}/<dataset>/snapshot=<id>/ (got {prefix!r})")
        if IDENTITY_LIMITATION not in (ds.get("limitations") or []):
            errors.append(f"{tag}: limitations must state: {IDENTITY_LIMITATION}")
        for url in urls_in(ds.get("source")):
            if not url.startswith("https://") and not url.startswith("s3://"):
                errors.append(f"{tag}: source URL is not https: {url}")

        ver = ds.get("verification") or {}
        if not isinstance(ver, dict):
            errors.append(f"{tag}: verification must be a mapping")
            ver = {}
        for vpath, entry in ver.items():
            if not has(ds, vpath):
                errors.append(f"{tag}: verification.{vpath} names a missing field")
            if not isinstance(entry, dict):
                errors.append(f"{tag}: verification.{vpath} must be a mapping")
                continue
            st = entry.get("status")
            if st == "VERIFIED":
                if not entry.get("evidence"):
                    errors.append(f"{tag}: verification.{vpath} VERIFIED without evidence")
                if has(ds, vpath) and get(ds, vpath) is None:
                    errors.append(f"{tag}: verification.{vpath} VERIFIED but the value is null")
            elif st == "UNVERIFIED":
                if not entry.get("note"):
                    errors.append(f"{tag}: verification.{vpath} UNVERIFIED without a note for the human")
                unverified.append(f"{name}.{vpath}: {entry.get('note', '').strip()}")
            else:
                errors.append(f"{tag}: verification.{vpath} status must be VERIFIED or UNVERIFIED")
        for npath in null_paths(ds):
            entry = ver.get(npath)
            if not (isinstance(entry, dict) and entry.get("status") == "UNVERIFIED" and entry.get("note")):
                errors.append(f"{tag}: {npath} is null without verification.{npath} UNVERIFIED + note")

        for i, s in enumerate(ds.get("samples") or []):
            stag = f"{tag}.samples[{i}]"
            if not isinstance(s, dict):
                errors.append(f"{stag}: must be a mapping")
                continue
            for k in SAMPLE_FIELDS:
                if s.get(k) in (None, ""):
                    errors.append(f"{stag}: missing {k}")
            rel = s.get("path")
            if not rel:
                continue
            described.add(rel)
            full = os.path.join(samples_dir, rel)
            if not os.path.isfile(full):
                errors.append(f"{stag}: file {rel} not found")
                continue
            actual = sha256(full)
            if s.get("sha256") != actual:
                errors.append(f"{stag}: sha256 {s.get('sha256')} != file {actual}")
            if sums.get(rel) != actual:
                errors.append(f"{stag}: SHA256SUMS entry for {rel} does not match the file")
            size = os.path.getsize(full)
            if s.get("bytes") != size:
                errors.append(f"{stag}: bytes {s.get('bytes')} != file size {size}")
            if size > MAX_BYTES:
                errors.append(f"{stag}: {size} bytes > {MAX_BYTES}")
            if not isinstance(s.get("records"), int) or s["records"] > MAX_RECORDS:
                errors.append(f"{stag}: records must be an integer <= {MAX_RECORDS}")

    errors.extend(check_dblp_subset(datasets.get("dblp")))
    errors.extend(check_orcid_policy(datasets.get("orcid")))
    errors.extend(check_openalex_policy(datasets.get("openalex")))

    for rel in sorted(set(sums) - described):
        errors.append(f"sample {rel} is in SHA256SUMS but has no manifest samples[] entry")

    print(f"manifest: {manifest_path}")
    print(f"datasets checked: {', '.join(selected)}")
    print(f"required fields per dataset: {len(REQUIRED)}")
    print(f"UNVERIFIED fields: {len(unverified)}")
    for u in unverified:
        print(f"  UNVERIFIED {u}")
    if errors:
        for e in errors:
            print(f"FAIL {e}")
        print(f"RESULT: FAIL ({len(errors)} error(s))")
        return 1
    print("RESULT: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
