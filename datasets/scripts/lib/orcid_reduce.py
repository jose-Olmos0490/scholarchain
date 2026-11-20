"""Reduce an ORCID v3.0 record to the fields the privacy policy allows.

Owner: dataset-engineer. Sample building only, never pipeline parsing.
Usage: python3 -I orcid_reduce.py <record.xml> <out.xml>

Policy (datasets/dataset-manifest.yaml, datasets.orcid.privacy_policy):
keep ONLY
  1. the ORCID iD            (common:orcid-identifier: uri, path, host)
  2. the name                (person:name: given-names, family-name, credit-name)
  3. affiliations            (distinctions, educations, employments,
                              invited-positions, memberships, qualifications,
                              services; per summary: department-name,
                              role-title, start-date, end-date, organization
                              name and disambiguated-organization)
  4. work identifiers        (works group common:external-ids: type, value,
                              relationship)
Everything else is dropped, including emails, biography, addresses (also the
organization address), other-names, keywords, researcher-urls, person
external identifiers, history, preferences, sources, titles, fundings,
peer-reviews and research-resources.

The input is untrusted: DOCTYPE and ENTITY declarations are refused, the
document is only parsed, and the output is built by copying allowlisted
elements into a new tree. Output is deterministic for a given input.
"""

import sys
import xml.etree.ElementTree as ET

NS = {
    "record": "http://www.orcid.org/ns/record",
    "common": "http://www.orcid.org/ns/common",
    "person": "http://www.orcid.org/ns/person",
    "personal-details": "http://www.orcid.org/ns/personal-details",
    "activities": "http://www.orcid.org/ns/activities",
    "work": "http://www.orcid.org/ns/work",
    "education": "http://www.orcid.org/ns/education",
    "employment": "http://www.orcid.org/ns/employment",
    "distinction": "http://www.orcid.org/ns/distinction",
    "invited-position": "http://www.orcid.org/ns/invited-position",
    "membership": "http://www.orcid.org/ns/membership",
    "qualification": "http://www.orcid.org/ns/qualification",
    "service": "http://www.orcid.org/ns/service",
}
# activities section -> namespace prefix of its *-summary elements
AFFILIATION_SECTIONS = {
    "distinctions": "distinction",
    "educations": "education",
    "employments": "employment",
    "invited-positions": "invited-position",
    "memberships": "membership",
    "qualifications": "qualification",
    "services": "service",
}
SUMMARY_KEEP = ["department-name", "role-title", "start-date", "end-date"]
ORG_KEEP = ["name", "disambiguated-organization"]
NAME_KEEP = ["given-names", "family-name", "credit-name"]
EXTID_KEEP = ["external-id-type", "external-id-value", "external-id-relationship"]
ATTR_KEEP = ["path", "put-code", "visibility"]


def q(prefix, local):
    return f"{{{NS[prefix]}}}{local}"


def sub(parent, prefix, local, src=None):
    e = ET.SubElement(parent, q(prefix, local))
    if src is not None:
        for a in ATTR_KEEP:
            if a in src.attrib:
                e.set(a, src.attrib[a])
    return e


def copy_tree(parent, src):
    """Deep-copy src (text and children), dropping attributes not in ATTR_KEEP."""
    e = ET.SubElement(parent, src.tag)
    for a in ATTR_KEEP:
        if a in src.attrib:
            e.set(a, src.attrib[a])
    e.text = src.text.strip() if src.text and src.text.strip() else None
    for c in src:
        copy_tree(e, c)
    return e


def reduce_record(root):
    if root.tag != q("record", "record"):
        sys.exit("input is not an ORCID record:record document")
    out = ET.Element(q("record", "record"))
    if "path" in root.attrib:
        out.set("path", root.attrib["path"])

    ident = root.find("common:orcid-identifier", NS)
    if ident is None or ident.find("common:path", NS) is None:
        sys.exit("record has no common:orcid-identifier/common:path")
    oi = sub(out, "common", "orcid-identifier")
    for local in ("uri", "path", "host"):
        c = ident.find(f"common:{local}", NS)
        if c is not None:
            copy_tree(oi, c)

    person = root.find("person:person", NS)
    if person is not None:
        p = sub(out, "person", "person", person)
        name = person.find("person:name", NS)
        if name is not None:
            n = sub(p, "person", "name", name)
            for local in NAME_KEEP:
                c = name.find(f"personal-details:{local}", NS)
                if c is not None:
                    copy_tree(n, c)

    acts = root.find("activities:activities-summary", NS)
    if acts is not None:
        a = sub(out, "activities", "activities-summary", acts)
        for section, pfx in AFFILIATION_SECTIONS.items():
            sec = acts.find(f"activities:{section}", NS)
            if sec is None:
                continue
            s_out = sub(a, "activities", section, sec)
            for grp in sec.findall("activities:affiliation-group", NS):
                g_out = sub(s_out, "activities", "affiliation-group")
                summary_local = section[:-1] + "-summary"
                for summ in grp.findall(f"{pfx}:{summary_local}", NS):
                    s = sub(g_out, pfx, summary_local, summ)
                    for local in SUMMARY_KEEP:
                        c = summ.find(f"common:{local}", NS)
                        if c is not None:
                            copy_tree(s, c)
                    org = summ.find("common:organization", NS)
                    if org is not None:
                        o = sub(s, "common", "organization")
                        for local in ORG_KEEP:
                            c = org.find(f"common:{local}", NS)
                            if c is not None:
                                copy_tree(o, c)
        works = acts.find("activities:works", NS)
        if works is not None:
            w_out = sub(a, "activities", "works", works)
            for grp in works.findall("activities:group", NS):
                g_out = sub(w_out, "activities", "group")
                ids = grp.find("common:external-ids", NS)
                if ids is None:
                    continue
                i_out = sub(g_out, "common", "external-ids")
                for x in ids.findall("common:external-id", NS):
                    x_out = sub(i_out, "common", "external-id")
                    for local in EXTID_KEEP:
                        c = x.find(f"common:{local}", NS)
                        if c is not None:
                            copy_tree(x_out, c)
    return out


def main():
    src, dst = sys.argv[1], sys.argv[2]
    data = open(src, "rb").read()
    if b"<!DOCTYPE" in data or b"<!ENTITY" in data:
        sys.exit("refusing ORCID input with DOCTYPE/ENTITY declarations")
    for prefix, uri in NS.items():
        ET.register_namespace(prefix, uri)
    root = ET.fromstring(data)
    out = reduce_record(root)
    ET.indent(out, space="  ")
    tree = ET.ElementTree(out)
    with open(dst, "wb") as f:
        tree.write(f, encoding="UTF-8", xml_declaration=True)
        f.write(b"\n")


if __name__ == "__main__":
    main()
