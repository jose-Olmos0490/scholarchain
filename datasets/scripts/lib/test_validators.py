"""Negative and boundary tests for the dataset validators.

Owner: dataset-engineer.
Usage: python3 -I datasets/scripts/lib/test_validators.py

Each case copies datasets/samples and the manifest into a fresh temporary
directory, applies one mutation, and runs the real validators
(check_samples.py, validate_manifest.py) on the copy as subprocesses. The
committed tree is never modified. Exit status 1 if any case fails.

Cases
  baseline       committed samples and manifest pass both validators
  cap (finding 3, human decision 2026-11-19: 200 KB = 200,000 bytes)
                 a 200,001-byte sample fails both validators; exactly
                 200,000 bytes passes both
  openalex (finding 4)
                 a non-empty abstract_inverted_index in any OpenAlex sample
                 fails; a null value passes; a manifest without the
                 OpenAlex sample policy fails
  orcid (finding 7)
                 anything outside the ORCID allowlist fails: person
                 external-identifiers, work:title, common:source,
                 record:history, an unknown element, an unknown attribute;
                 the forbidden personal elements (email) still fail
  dblp subset (human decision 2026-11-20)
                 max_works not a positive integer, analysis_windows
                 missing/empty/unsorted/overlapping/inverted/outside the
                 year window/non-integer, placeholder year bounds, end_year
                 in the partial snapshot year, and a config key or env var
                 that differs from domain-engineer's names each fail.
                 The two record_types cases and "start_year > end_year"
                 guard rules that existed before this change.
"""

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile

import yaml

LIB = os.path.dirname(os.path.abspath(__file__))
DATASETS = os.path.dirname(os.path.dirname(LIB))
SAMPLES = os.path.join(DATASETS, "samples")
MANIFEST = os.path.join(DATASETS, "dataset-manifest.yaml")

WORKS = "openalex/openalex-works-snapshot-2026-10-14-excerpt.jsonl"
ORCID = "orcid/orcid-0000-0002-1825-0097-record.xml"

RESULTS = []


def sha256(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


class Tree:
    """A disposable copy of samples/ and the manifest."""

    def __init__(self):
        self.root = tempfile.mkdtemp(prefix="scholarchain-validator-test.")
        self.samples = os.path.join(self.root, "samples")
        shutil.copytree(SAMPLES, self.samples)
        self.manifest = os.path.join(self.root, "dataset-manifest.yaml")
        shutil.copyfile(MANIFEST, self.manifest)

    def close(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def path(self, rel):
        return os.path.join(self.samples, rel)

    def write(self, rel, data):
        with open(self.path(rel), "wb") as f:
            f.write(data)
        self.resync(rel)

    def resync(self, rel):
        """Update SHA256SUMS and the manifest sample entry for rel, so the
        only remaining problem is the one the case introduces."""
        sums = os.path.join(self.samples, "SHA256SUMS")
        lines = []
        with open(sums, encoding="utf-8") as f:
            for line in f:
                d, r = line.split(None, 1)
                r = r.strip()
                lines.append(f"{sha256(self.path(rel)) if r == rel else d}  {r}\n")
        with open(sums, "w", encoding="utf-8") as f:
            f.writelines(lines)
        doc = self.load_manifest()
        for ds in doc["datasets"].values():
            for s in ds.get("samples") or []:
                if s.get("path") == rel:
                    s["sha256"] = sha256(self.path(rel))
                    s["bytes"] = os.path.getsize(self.path(rel))
                    s["records"] = min(s["records"], 50)
        self.save_manifest(doc)

    def load_manifest(self):
        with open(self.manifest, encoding="utf-8") as f:
            return yaml.safe_load(f)

    def save_manifest(self, doc):
        with open(self.manifest, "w", encoding="utf-8") as f:
            yaml.safe_dump(doc, f, sort_keys=False, allow_unicode=True)

    def check_samples(self):
        return run("check_samples.py", self.samples)

    def validate_manifest(self):
        return run("validate_manifest.py", self.manifest, self.samples)


def run(script, *args):
    p = subprocess.run([sys.executable, "-I", os.path.join(LIB, script), *args],
                       capture_output=True, text=True)
    return p.returncode, p.stdout + p.stderr


def record(name, ok, detail=""):
    RESULTS.append(ok)
    print(f"{'PASS' if ok else 'FAIL'} {name}" + ("" if ok else f": {detail}"))


def expect(name, result, want_fail, needle=None):
    rc, out = result
    failed = rc != 0
    ok = failed == want_fail and (needle is None or needle in out)
    last = [l for l in out.splitlines() if l.startswith(("FAIL", "RESULT", "samples:"))]
    record(name, ok, f"exit {rc}; " + " | ".join(last[-3:]))


def case(fn):
    t = Tree()
    try:
        fn(t)
    finally:
        t.close()


# ------------------------------------------------------------------ cases --

def baseline(t):
    expect("baseline: committed samples pass check_samples", t.check_samples(), False)
    expect("baseline: committed manifest passes validate_manifest", t.validate_manifest(), False)


def jsonl_of_size(n):
    """Valid JSON Lines (no abstract field) of exactly n bytes."""
    lines = []
    size = 0
    while n - size > 6000:
        line = json.dumps({"id": f"pad-{len(lines)}", "pad": "x" * 4900}) + "\n"
        lines.append(line)
        size += len(line)
    rest = n - size
    stub = json.dumps({"id": "last", "pad": ""}) + "\n"
    lines.append(json.dumps({"id": "last", "pad": "x" * (rest - len(stub))}) + "\n")
    data = "".join(lines).encode()
    assert len(data) == n, len(data)
    return data


def cap_over(t):
    t.write(WORKS, jsonl_of_size(200001))
    expect("F3 cap: 200,001-byte sample fails check_samples", t.check_samples(), True, "200001 bytes > 200000")
    expect("F3 cap: 200,001-byte sample fails validate_manifest", t.validate_manifest(), True, "200001 bytes > 200000")


def cap_exact(t):
    t.write(WORKS, jsonl_of_size(200000))
    expect("F3 cap: exactly 200,000 bytes passes check_samples", t.check_samples(), False)
    expect("F3 cap: exactly 200,000 bytes passes validate_manifest", t.validate_manifest(), False)


def set_abstract(t, rel, value):
    with open(t.path(rel), "rb") as f:
        lines = f.read().splitlines()
    obj = json.loads(lines[0])
    obj["abstract_inverted_index"] = value
    lines[0] = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode()
    t.write(rel, b"\n".join(lines) + b"\n")


def openalex_abstract_present(t):
    set_abstract(t, WORKS, {"Graph": [0], "analytics": [1]})
    expect("F4 openalex: non-empty abstract_inverted_index in the works excerpt fails",
           t.check_samples(), True, "abstract_inverted_index")


def openalex_abstract_in_api_sample(t):
    set_abstract(t, "openalex/openalex-works-api-linked.jsonl", {"x": [0]})
    expect("F4 openalex: non-empty abstract_inverted_index in the API-linked sample fails",
           t.check_samples(), True, "abstract_inverted_index")


def openalex_abstract_null(t):
    set_abstract(t, WORKS, None)
    expect("F4 openalex: abstract_inverted_index null passes", t.check_samples(), False)


def openalex_policy_missing(t):
    doc = t.load_manifest()
    doc["datasets"]["openalex"].pop("sample_policy", None)
    t.save_manifest(doc)
    expect("F4 openalex: manifest without the abstract-stripping sample policy fails",
           t.validate_manifest(), True, "sample_policy")


def orcid_inject(name, snippet, anchor="</person:person>", needle="ORCID allowlist"):
    def fn(t):
        with open(t.path(ORCID), encoding="utf-8") as f:
            xml = f.read()
        assert anchor in xml, anchor
        xml = xml.replace(anchor, snippet + anchor, 1)
        t.write(ORCID, xml.encode("utf-8"))
        expect(name, t.check_samples(), True, needle)
    return fn


NS_DECL = ('xmlns:person="http://www.orcid.org/ns/person" '
           'xmlns:common="http://www.orcid.org/ns/common" '
           'xmlns:external-identifier="http://www.orcid.org/ns/external-identifier" '
           'xmlns:work="http://www.orcid.org/ns/work" '
           'xmlns:history="http://www.orcid.org/ns/history" '
           'xmlns:email="http://www.orcid.org/ns/email"')

ORCID_CASES = [
    ("F7 orcid: person external-identifiers fails",
     f'<person:external-identifiers {NS_DECL}><external-identifier:external-identifier>'
     '<common:external-id-type>Scopus</common:external-id-type>'
     '<common:external-id-value>1</common:external-id-value>'
     '</external-identifier:external-identifier></person:external-identifiers>',
     "</person:person>"),
    ("F7 orcid: work:title inside a works group fails",
     f'<work:work-summary {NS_DECL}><work:title><common:title>t</common:title></work:title></work:work-summary>',
     "</activities:group>"),
    ("F7 orcid: common:source inside an employment summary fails",
     f'<common:source {NS_DECL}><common:source-name>s</common:source-name></common:source>',
     "</employment:employment-summary>"),
    ("F7 orcid: record history fails",
     f'<history:history {NS_DECL}><history:creation-method>Member-referred</history:creation-method></history:history>',
     "</record:record>"),
    ("F7 orcid: an unknown element in an allowed namespace fails",
     f'<common:not-on-the-allowlist {NS_DECL}>x</common:not-on-the-allowlist>',
     "</common:orcid-identifier>"),
]


def orcid_unknown_attribute(t):
    with open(t.path(ORCID), encoding="utf-8") as f:
        xml = f.read()
    xml = xml.replace("<person:name ", '<person:name created-date="2016-04-15" ', 1)
    t.write(ORCID, xml.encode("utf-8"))
    expect("F7 orcid: an attribute outside the allowlist fails", t.check_samples(), True, "ORCID allowlist")


def orcid_email_still_fails(t):
    with open(t.path(ORCID), encoding="utf-8") as f:
        xml = f.read()
    xml = xml.replace("</person:person>",
                      f'<email:emails {NS_DECL}><email:email><email:email>x@example.org</email:email></email:email></email:emails></person:person>', 1)
    t.write(ORCID, xml.encode("utf-8"))
    expect("F7 orcid: an emails element still fails", t.check_samples(), True, "emails")


def dblp_mutate(name, mutate, needle):
    """Human decision 2026-11-20 (DBLP subset): one mutation of
    datasets.dblp.subset_parameters must make validate_manifest fail."""
    def fn(t):
        doc = t.load_manifest()
        mutate(doc["datasets"]["dblp"]["subset_parameters"])
        t.save_manifest(doc)
        expect(name, t.validate_manifest(), True, needle)
    return fn


def _set(key, value):
    return lambda sp: sp.__setitem__(key, value)


def _windows(*pairs):
    return _set("analysis_windows", [{"start_year": s, "end_year": e} for s, e in pairs])


def _year(key, value):
    return lambda sp: sp["year_window"].__setitem__(key, value)


def _conf(key, field, value):
    return lambda sp: sp["configuration"]["keys"][key].__setitem__(field, value)


OK5 = [(2000, 2005), (2006, 2010), (2011, 2015), (2016, 2020), (2021, 2025)]

DBLP_CASES = [
    # cap
    ("D cap: max_works 0 fails", _set("max_works", 0), "max_works must be a positive integer"),
    ("D cap: max_works -1 fails", _set("max_works", -1), "max_works must be a positive integer"),
    ("D cap: max_works as a string fails", _set("max_works", "100000"), "max_works must be a positive integer"),
    ("D cap: max_works true (bool) fails", _set("max_works", True), "max_works must be a positive integer"),
    ("D cap: max_works missing fails", lambda sp: sp.pop("max_works"), "max_works must be a positive integer"),
    # windows
    ("D windows: missing fails", lambda sp: sp.pop("analysis_windows"), "analysis_windows must be a non-empty list"),
    ("D windows: empty list fails", _windows(), "analysis_windows must be a non-empty list"),
    ("D windows: unsorted fails", _windows(*[OK5[1], OK5[0]] + OK5[2:]), "is not sorted"),
    ("D windows: overlapping fails", _windows((2000, 2006), *OK5[1:]), "overlaps"),
    ("D windows: shared boundary year overlaps", _windows((2000, 2005), (2005, 2010), *OK5[2:]), "overlaps"),
    ("D windows: start_year > end_year fails", _windows((2005, 2000), *OK5[1:]), "start_year > end_year"),
    ("D windows: ending after year_window (2026) fails", _windows(*OK5[:4], (2021, 2026)), "outside year_window"),
    ("D windows: starting before year_window (1999) fails", _windows((1999, 2005), *OK5[1:]), "outside year_window"),
    ("D windows: non-integer bound fails", _windows(("2000", 2005), *OK5[1:]), "needs integer"),
    ("D windows: placeholder year_window bounds fail containment",
     lambda sp: sp["year_window"].update(start_year="${DBLP_SUBSET_START_YEAR}", end_year="${DBLP_SUBSET_END_YEAR}"),
     "containment needs integer year_window bounds"),
    # partial year
    ("D year: end_year 2026 (partial snapshot year) fails",
     lambda sp: (sp["year_window"].__setitem__("end_year", 2026)), "partial year excluded"),
    ("D year: start_year > end_year still fails", _year("start_year", 2026), "start_year > end_year"),
    # record types (regression guard: the rule existed before this change)
    ("D types: adding proceedings fails", _set("record_types", ["article", "inproceedings", "proceedings"]), "record_types must be"),
    ("D types: article only fails", _set("record_types", ["article"]), "record_types must be"),
    # configuration names
    ("D config: wrong env var for max_works fails", _conf("max_works", "env_var", "DBLP_MAX_WORKS"), "keys.max_works must map"),
    ("D config: wrong config key for windows fails", _conf("analysis_windows", "config_key", "scholarchain.windows"), "keys.analysis_windows must map"),
    ("D config: missing record_types mapping fails", lambda sp: sp["configuration"]["keys"].pop("record_types"), "keys.record_types must map"),
    ("D config: wrong values_file fails", lambda sp: sp["configuration"].__setitem__("values_file", "conf/dev.conf"), "values_file must be"),
]


def main():
    case(baseline)
    for name, mutate, needle in DBLP_CASES:
        case(dblp_mutate(name, mutate, needle))
    case(cap_over)
    case(cap_exact)
    case(openalex_abstract_present)
    case(openalex_abstract_in_api_sample)
    case(openalex_abstract_null)
    case(openalex_policy_missing)
    for name, snippet, anchor in ORCID_CASES:
        case(orcid_inject(name, snippet, anchor))
    case(orcid_unknown_attribute)
    case(orcid_email_still_fails)
    failed = RESULTS.count(False)
    print(f"RESULT: {'PASS' if not failed else 'FAIL'} ({RESULTS.count(True)} passed, {failed} failed)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
