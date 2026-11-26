"""Item 2: integrity, duplicate, orphan and support checks, independent recomputation, and a seeded
provenance audit over the deployed run's Spark outputs (the S3 copy). The recomputation here is
plain Python over the stored tables; it shares no code with the Spark jobs."""

import collections
import glob
import gzip
import hashlib
import html
import json
import os
import random
import re
import xml.etree.ElementTree as ElementTree

import pyarrow.parquet as pq

from part_a_common import sha256_file, write_csv

SEED = 20261125
SAMPLE = 50
RUN_HEX = "f6023de34a312366583b3eacbc2b2a689234cf1abe79e184fb66875bac297a60"
RUN_ID = "run:" + RUN_HEX
SNAPSHOT = "2026-11-01"


def table(root, name):
    rows = []
    for path in sorted(glob.glob(os.path.join(root, name, "**", "*.parquet"), recursive=True)):
        rows.extend(pq.ParquetFile(path).read().to_pylist())
    return rows


def window(row):
    return (row["windowStartYear"], row["windowEndYear"])


def label(w):
    return "%d-%d" % w


def rate(part, whole):
    return "n/a" if not whole else "%.6f" % (part / whole)


def jsonl(directory):
    rows = []
    for dirpath, _, files in sorted(os.walk(directory)):
        for name in sorted(files):
            if name.startswith("part-") and not name.endswith(".crc"):
                opener = gzip.open if name.endswith(".gz") else open
                with opener(os.path.join(dirpath, name), "rt", encoding="utf-8") as handle:
                    rows.extend(json.loads(line) for line in handle if line.strip())
    return rows


def bfs(adjacency, source):
    distance = {source: 0}
    queue = collections.deque([source])
    while queue:
        node = queue.popleft()
        for other in adjacency.get(node, ()):
            if other not in distance:
                distance[other] = distance[node] + 1
                queue.append(other)
    return distance


def coreness_of(adjacency):
    degree = {n: len(v) for n, v in adjacency.items()}
    alive = set(adjacency)
    result, level = {}, 0
    while alive:
        node = min(alive, key=lambda n: (degree[n], n))
        level = max(level, degree[node])
        result[node] = level
        alive.remove(node)
        for other in adjacency[node]:
            if other in alive:
                degree[other] -= 1
    return result


def checksum_tables(raw, out):
    """S3 objects against the S3 load manifest the Spark run wrote."""
    with open(os.path.join(raw, "graph", "run-metadata", "load-manifests", "target=s3", "load-manifest.json")) as handle:
        manifest = json.load(handle)
    root = os.path.join(raw, "graph", "spark-output-s3")
    rows = []
    for entry in manifest["tables"]:
        name = entry["tableName"]
        files = glob.glob(os.path.join(root, name, "**", "*.parquet"), recursive=True)
        actual = {"sha256:" + sha256_file(f) for f in files}
        expected = {f["checksum"] for f in entry["files"]}
        count = sum(pq.ParquetFile(f).metadata.num_rows for f in files)
        rows.append([name, entry["rowCount"], count, len(entry["files"]), len(files), len(expected & actual),
                     "yes" if expected == actual and count == entry["rowCount"] and len(files) == len(entry["files"])
                     else "NO"])
    write_csv(out, "graph/s3-vs-load-manifest.csv",
              ("table", "manifest_rows", "s3_rows", "manifest_files", "s3_files", "distinct_checksums_matched", "match"),
              rows)


def input_checksums(raw, out):
    """Hadoop inputs used for tracing, against the checksums in the Spark run manifest: SHA-256 of the
    sorted '<relative path> <sha256>' lines of the part files."""
    with open(os.path.join(raw, "graph", "run-metadata", "run-manifest.json")) as handle:
        manifest = json.load(handle)
    rows = []
    for entry in manifest["inputs"]:
        match = re.match(r"hadoop:scholarchain/([a-z_]+)/", entry["location"])
        if not match:
            continue
        directory = os.path.join(raw, "graph", "hadoop-inputs", match.group(1))
        if not os.path.isdir(directory):
            continue
        lines = []
        for dirpath, _, files in os.walk(directory):
            for name in files:
                if name.startswith("part-") and not name.endswith(".crc"):
                    path = os.path.join(dirpath, name)
                    lines.append("%s %s\n" % (os.path.relpath(path, directory), sha256_file(path)))
        actual = "sha256:" + hashlib.sha256("".join(sorted(lines)).encode()).hexdigest()
        rows.append([match.group(1), entry["checksum"], actual, "yes" if actual == entry["checksum"] else "NO"])
    write_csv(out, "graph/hadoop-input-checksums.csv", ("input", "run_manifest", "recomputed", "match"), rows)


def dblp_records(path):
    """key -> (authors, year) from the committed DBLP excerpt; named entities are decoded first."""
    with open(path, encoding="iso-8859-1") as handle:
        text = handle.read()
    body = text[text.index("<dblp>"):]
    xml_names = {"amp", "lt", "gt", "quot", "apos"}
    body = re.sub(r"&([A-Za-z]+);", lambda m: m.group(0) if m.group(1) in xml_names else html.unescape(m.group(0)), body)
    records = {}
    for element in ElementTree.fromstring(body):
        authors = ["".join(a.itertext()) for a in element.findall("author")]
        records[element.get("key")] = (authors, int(element.findtext("year") or 0))
    return records


def build(raw, out, repo):
    root = os.path.join(raw, "graph", "spark-output-s3")
    checksum_tables(raw, out)
    input_checksums(raw, out)
    names = ("researcher", "work", "venue", "topic", "institution", "authored", "has_topic", "coauthored_with",
             "coauthored_with_support", "researcher_collaboration", "components", "community_membership",
             "cross_community_edge", "bridge_measures", "coreness", "path_evidence", "researcher_window_change",
             "window_summary", "integrity_reject", "identity_conflict")
    t = {n: table(root, n) for n in names}
    write_csv(out, "graph/table-rows.csv", ("table", "rows"), [[n, len(t[n])] for n in names])

    researchers = {r["researcherId"] for r in t["researcher"]}
    works = {w["workId"]: w for w in t["work"]}
    venues = {v["venueId"] for v in t["venue"]}
    topics = {v["topicId"] for v in t["topic"]}
    institutions = {v["institutionId"] for v in t["institution"]}
    authored_pairs = {(a["researcherId"], a["workId"]) for a in t["authored"]}
    edges = t["coauthored_with"]
    edge_by_id = {e["edgeId"]: e for e in edges}
    support = collections.defaultdict(list)
    for s in t["coauthored_with_support"]:
        support[s["edgeId"]].append(s)

    integrity = []

    def check(name, values, allowed):
        values = list(values)
        bad = sum(1 for v in values if v not in allowed)
        integrity.append([name, len(values), bad, rate(len(values) - bad, len(values))])

    check("authored.researcherId -> researcher", (a["researcherId"] for a in t["authored"]), researchers)
    check("authored.workId -> work", (a["workId"] for a in t["authored"]), works)
    check("coauthored_with.endpoints -> researcher",
          (x for e in edges for x in (e["researcherA"], e["researcherB"])), researchers)
    check("support.edgeId -> coauthored_with", (s["edgeId"] for s in t["coauthored_with_support"]), edge_by_id)
    check("support.workId -> work", (s["workId"] for s in t["coauthored_with_support"]), works)
    check("support (endpoint, work) -> authored",
          ((x, s["workId"]) for s in t["coauthored_with_support"] for x in (s["researcherA"], s["researcherB"])),
          authored_pairs)
    check("support endpoints equal edge endpoints",
          ((s["edgeId"], s["researcherA"], s["researcherB"]) for s in t["coauthored_with_support"]),
          {(e["edgeId"], e["researcherA"], e["researcherB"]) for e in edges})
    check("has_topic.fromId -> work", (e["fromId"] for e in t["has_topic"]), works)
    check("has_topic.toId -> topic", (e["toId"] for e in t["has_topic"]), topics)
    check("work.venueId -> venue", (w["venueId"] for w in works.values() if w["venueId"]), venues)
    check("researcher.affiliationInstitutionIds -> institution",
          (i for r in t["researcher"] for i in (r["affiliationInstitutionIds"] or [])), institutions)
    for name in ("researcher_collaboration", "components", "community_membership", "bridge_measures", "coreness",
                 "researcher_window_change"):
        check("%s.researcherId -> researcher" % name, (r["researcherId"] for r in t[name]), researchers)
    check("path hop edgeId -> coauthored_with", (h["edgeId"] for p in t["path_evidence"] for h in (p["hops"] or [])),
          edge_by_id)
    check("path hop supporting works -> work",
          (w for p in t["path_evidence"] for h in (p["hops"] or []) for w in h["supportingWorkIds"]), works)
    write_csv(out, "graph/referential-integrity.csv", ("check", "references", "violations", "satisfied_rate"), integrity)

    duplicates = []
    for name, keys in (
            ("coauthored_with edgeId", [e["edgeId"] for e in edges]),
            ("coauthored_with (window, unordered pair)",
             [(window(e), frozenset((e["researcherA"], e["researcherB"]))) for e in edges]),
            ("coauthored_with_support (edgeId, workId)",
             [(s["edgeId"], s["workId"]) for s in t["coauthored_with_support"]]),
            ("authored (researcher, work)", [(a["researcherId"], a["workId"]) for a in t["authored"]]),
            ("authored authorshipId", [a["authorshipId"] for a in t["authored"]]),
            ("has_topic edgeId", [e["edgeId"] for e in t["has_topic"]])):
        extra = len(keys) - len(set(keys))
        duplicates.append([name, len(keys), extra, rate(extra, len(keys))])
    for name, bad in (("coauthored_with self-loops", sum(e["researcherA"] == e["researcherB"] for e in edges)),
                      ("coauthored_with not in canonical order (A < B)",
                       sum(not e["researcherA"] < e["researcherB"] for e in edges))):
        duplicates.append([name, len(edges), bad, rate(bad, len(edges))])
    write_csv(out, "graph/duplicate-edges.csv", ("check", "rows", "violations", "rate"), duplicates)

    linked = {x for e in edges for x in (e["researcherA"], e["researcherB"])}
    orphans = []
    for name, nodes, used in (
            ("researcher without authored edge", researchers, {a["researcherId"] for a in t["authored"]}),
            ("researcher without coauthor edge (any window)", researchers, linked),
            ("work without authored edge", set(works), {a["workId"] for a in t["authored"]}),
            ("work without HAS_TOPIC", set(works), {e["fromId"] for e in t["has_topic"]}),
            ("venue without work", venues, {w["venueId"] for w in works.values()}),
            ("topic without HAS_TOPIC", topics, {e["toId"] for e in t["has_topic"]}),
            ("institution without affiliated researcher", institutions,
             {i for r in t["researcher"] for i in (r["affiliationInstitutionIds"] or [])})):
        count = len(nodes - used)
        orphans.append([name, len(nodes), count, rate(count, len(nodes))])
    write_csv(out, "graph/orphans.csv", ("check", "nodes", "orphans", "rate"), orphans)

    def supported(edge):
        items = support.get(edge["edgeId"], [])
        return bool(items) and all(
            s["workId"] in works and (edge["researcherA"], s["workId"]) in authored_pairs
            and (edge["researcherB"], s["workId"]) in authored_pairs for s in items)

    windows = sorted({window(e) for e in edges} | {window(r) for r in t["window_summary"]})
    support_rows = []
    for w in windows + [None]:
        members = [e for e in edges if w is None or window(e) == w]
        ok = sum(supported(e) for e in members)
        counted = sum(len({s["workId"] for s in support.get(e["edgeId"], [])}) == e["jointPublicationCount"]
                      for e in members)
        representative = sum(set(e["representativeWorkIds"]) <= {s["workId"] for s in support.get(e["edgeId"], [])}
                             for e in members)
        support_rows.append(["all rows" if w is None else label(w), len(members), ok, rate(ok, len(members)),
                             counted, representative])
    write_csv(out, "graph/edge-support.csv", ("window", "edges", "edges_with_valid_support", "support_rate",
                                              "joint_count_equals_support", "representative_works_in_support"),
              support_rows)

    # Independent recomputation per window.
    recompute = []
    for w in windows:
        start, end = w
        by_work = collections.defaultdict(set)
        for a in t["authored"]:
            if a["windowStartYear"] <= a["windowEndYear"] and a["year"] is not None and start <= a["year"] <= end:
                by_work[a["workId"]].add(a["researcherId"])
        expected_pairs = collections.defaultdict(set)
        for work_id, team in by_work.items():
            for a in team:
                for b in team:
                    if a < b:
                        expected_pairs[(a, b)].add(work_id)
        stored_pairs = {(e["researcherA"], e["researcherB"]):
                        {s["workId"] for s in support[e["edgeId"]]} for e in edges if window(e) == w}
        nodes = set().union(*by_work.values()) if by_work else set()
        adjacency = {n: set() for n in nodes}
        for a, b in stored_pairs:
            adjacency.setdefault(a, set()).add(b)
            adjacency.setdefault(b, set()).add(a)

        def compare(name, expected, stored):
            keys = set(expected) | set(stored)
            bad = sum(expected.get(k) != stored.get(k) for k in keys)
            recompute.append([label(w), name, len(keys), bad])

        compare("coauthor edges and supporting works", dict(expected_pairs), stored_pairs)
        metric_rows = {r["researcherId"]: r for r in t["researcher_collaboration"] if window(r) == w}
        compare("degree C_r", {n: len(v) for n, v in adjacency.items()},
                {n: r["distinctCoauthorCount"] for n, r in metric_rows.items()})
        weighted = collections.Counter()
        for (a, b), shared in stored_pairs.items():
            weighted[a] += len(shared)
            weighted[b] += len(shared)
        compare("weighted degree W_r", {n: float(weighted[n]) for n in adjacency},
                {n: r["weightedDegree"] for n, r in metric_rows.items()})
        component = {}
        for node in sorted(adjacency):
            if node not in component:
                members = set(bfs(adjacency, node))
                for member in members:
                    component[member] = (min(members), len(members))
        compare("component id and size", component,
                {r["researcherId"]: (r["componentId"], r["componentSize"]) for r in t["components"] if window(r) == w})
        cores = coreness_of(adjacency)
        compare("k-core coreness", cores,
                {r["researcherId"]: r["coreness"] for r in t["coreness"] if window(r) == w})
        community = {r["researcherId"]: r["communityId"] for r in t["community_membership"] if window(r) == w}
        sizes = collections.Counter(community.values())
        compare("community size field", {n: sizes[c] for n, c in community.items()},
                {r["researcherId"]: r["communitySize"] for r in t["community_membership"] if window(r) == w})
        compare("community lies in one component (labels not recomputed)",
                {c: 1 for c in sizes},
                {c: len({component[n][0] for n, x in community.items() if x == c}) for c in sizes})
        bridge_expected = {}
        for node, neighbours in adjacency.items():
            outside = sum(community.get(o) != community.get(node) for o in neighbours)
            connected = len({community.get(o) for o in neighbours} | {community.get(node)})
            bridge_expected[node] = (len(neighbours), outside, connected)
        compare("bridge degree, cross-community degree, communities connected", bridge_expected,
                {r["researcherId"]: (r["degree"], r["crossCommunityDegree"], r["communitiesConnected"])
                 for r in t["bridge_measures"] if window(r) == w})
        bad_paths = 0
        paths = [p for p in t["path_evidence"] if window(p) == w]
        for p in paths:
            distance = bfs(adjacency, p["sourceResearcherId"]).get(p["targetResearcherId"])
            hops = p["hops"] or []
            if p["status"] == "FOUND":
                node, walk_ok = p["sourceResearcherId"], True
                for h in hops:
                    walk_ok &= h["fromResearcherId"] == node and h["toResearcherId"] in adjacency.get(node, ())
                    node = h["toResearcherId"]
                bad_paths += not (walk_ok and node == p["targetResearcherId"] and len(hops) == p["length"] == distance)
            else:
                bad_paths += distance is not None and distance <= p["maxDepth"]
        recompute.append([label(w), "stored paths are shortest walks (or correctly not found)", len(paths), bad_paths])
        summary = next(r for r in t["window_summary"] if window(r) == w)
        compare("window summary researcher, edge, component counts",
                {"researchers": len(adjacency), "edges": len(stored_pairs),
                 "components": len({c for c, _ in component.values()})},
                {"researchers": summary["researcherCount"], "edges": summary["coauthorEdgeCount"],
                 "components": summary["componentCount"]})
    write_csv(out, "graph/recomputation.csv", ("window", "check", "compared", "mismatches"), recompute)
    write_csv(out, "graph/path-status.csv", ("status", "paths", "max_length"), [
        [status, len(group), max((p["length"] or 0 for p in group), default=0)]
        for status in sorted({p["status"] for p in t["path_evidence"]})
        for group in [[p for p in t["path_evidence"] if p["status"] == status]]])
    write_csv(out, "graph/structure.csv", ("window", "researchers", "edges", "components", "largest_component",
                                           "communities", "max_coreness", "cross_community_edges"),
              [[label(window(r)), r["researcherCount"], r["coauthorEdgeCount"], r["componentCount"],
                r["largestComponentSize"], r["communityCount"], r["maxCoreness"],
                sum(window(c) == window(r) for c in t["cross_community_edge"])]
               for r in sorted(t["window_summary"], key=window)])

    # Seeded provenance audit: edge -> support -> work -> authored -> Hadoop mention -> DBLP XML.
    xml = dblp_records(os.path.join(repo, "datasets", "samples", "dblp", "dblp-2026-11-01-excerpt.xml"))
    mentions = {m["mentionId"]: m for m in jsonl(os.path.join(raw, "graph", "hadoop-inputs", "spark_author_mention"))}
    authored = {(a["researcherId"], a["workId"]): a for a in t["authored"]}
    population = sorted(edges, key=lambda e: e["edgeId"])
    sample = random.Random(SEED).sample(population, min(SAMPLE, len(population)))
    audit = []
    for edge in sorted(sample, key=lambda e: e["edgeId"]):
        provenance = edge["provenance"]
        fields = all(provenance.get(k) for k in ("sourceDataset", "sourceRecordId", "snapshot", "runId", "policyVersion"))
        fields = fields and provenance["runId"] == RUN_ID and provenance["snapshot"] == SNAPSHOT
        fields = fields and provenance["sourceRecordId"] in {s["workId"] for s in support.get(edge["edgeId"], [])}
        items = support.get(edge["edgeId"], [])
        traced, keys, reasons = bool(items), [], []
        if not items:
            reasons.append("no support row")
        for s in items:
            work = works.get(s["workId"])
            if work is None:
                traced, reasons = False, reasons + ["work missing"]
                continue
            key = work["sourceRecordId"]
            keys.append(key)
            record = xml.get(key)
            if record is None or work["provenance"].get("sourceDataset") != "DBLP":
                traced, reasons = False, reasons + ["DBLP record not found"]
                continue
            if record[1] != work["year"] or not edge["windowStartYear"] <= record[1] <= edge["windowEndYear"]:
                traced, reasons = False, reasons + ["year mismatch"]
            for endpoint in (edge["researcherA"], edge["researcherB"]):
                row = authored.get((endpoint, s["workId"]))
                mention = mentions.get(row["mentionId"]) if row else None
                if mention is None:
                    traced, reasons = False, reasons + ["authored or mention missing"]
                elif (mention["provenance"]["sourceRecordId"] != key or mention["position"] > len(record[0])
                      or record[0][mention["position"] - 1] != mention["rawName"]):
                    traced, reasons = False, reasons + ["author not at that position in the DBLP record"]
        audit.append([edge["edgeId"], label(window(edge)), len(items), ";".join(sorted(keys)),
                      "yes" if fields else "NO", "yes" if traced else "NO", ";".join(sorted(set(reasons)))])
    write_csv(out, "graph/provenance-audit.csv", ("edge_id", "window", "support_works", "dblp_keys",
                                                  "provenance_fields_ok", "traced_to_dblp_xml", "failure"), audit)
    write_csv(out, "graph/provenance-audit-summary.csv", ("measure", "value"), [
        ["population (coauthored_with rows, all windows)", len(population)], ["sampled", len(audit)],
        ["seed", SEED], ["distinct researcher pairs sampled",
                         len({(edge_by_id[a[0]]["researcherA"], edge_by_id[a[0]]["researcherB"]) for a in audit})],
        ["distinct DBLP keys sampled", len({k for a in audit for k in a[3].split(";") if k})],
        ["provenance fields ok", sum(a[4] == "yes" for a in audit)],
        ["traced to DBLP XML", sum(a[5] == "yes" for a in audit)]])
