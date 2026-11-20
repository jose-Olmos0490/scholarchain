"""Small, deterministic helpers for building dataset samples.

Owner: dataset-engineer. Sample building only, never pipeline parsing.
Fetched content is treated as untrusted data: it is parsed as JSON or bytes,
never evaluated or executed.

Subcommands:
  dblp-dois <dblp_sample.xml> <n>
      Print the first n distinct DOIs (lower-cased) found in <ee> links of
      conf/ inproceedings records, in file order.
  crossref-jsonl <response.json> <dois.txt> <out.jsonl>
      Write message.items as JSON Lines in DOI-list order, dropping the
      "abstract" field (abstracts are publisher/author copyright).
  openalex-api-jsonl <response.json> <dois.txt> <out.jsonl> [strip_key]
      Write results as JSON Lines in DOI-list order, then any results whose
      primary DOI differs from every requested DOI (alias matches). With
      strip_key, that top-level key is removed from every result.
  openalex-pick-file <manifest.json> <entity> <min_records> <max_records> <max_gz_bytes>
      Print "<s3-url> <content_length> <record_count>" of the most recent
      partition (last by URL, i.e. latest updated_date) of the entity that
      satisfies the bounds.
  jsonl-head <in.gz> <out.jsonl> <max_records> <max_bytes> [strip_key]
      Copy whole JSON lines from a gzip file until either cap would be hit.
      With strip_key, that top-level key is removed from each line first
      (byte-preserving, see strip_key_bytes), so the caps apply to the
      stripped lines.
  strip-file <in.jsonl> <out.jsonl> <strip_key>
      Apply strip_key_bytes to every line of a JSON Lines file.
  crossref-batch-check <batch.jsonl> <dois.txt>
      Exit 0 only if every line is a JSON object whose DOI (lower-cased)
      is in dois.txt; used to decide whether a full-mode batch may be
      skipped.
  openalex-url <base> <dois.txt> <select> [mailto]
  crossref-url <base> <dois.txt> [mailto]
      Print a single bounded batch-lookup URL for the listed DOIs. The
      optional mailto is only added when the caller passes a non-empty value.
"""

import gzip
import html
import json
import re
import sys
import urllib.parse

EE_DOI = re.compile(rb"<ee[^>]*>https://doi\.org/([^<]+)</ee>")
REC = re.compile(rb'<inproceedings\s[^>]*key="conf/[^"]+"[^>]*>.*?</inproceedings>', re.S)


def dump(obj) -> str:
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def read_dois(path):
    with open(path, encoding="utf-8") as f:
        return [line.strip() for line in f if line.strip()]


def dblp_dois(path, n):
    data = open(path, "rb").read()
    seen = []
    for rec in REC.finditer(data):
        for m in EE_DOI.finditer(rec.group(0)):
            doi = html.unescape(m.group(1).decode("latin-1")).strip().lower()
            if doi not in seen:
                seen.append(doi)
            break  # first DOI link per record
        if len(seen) >= n:
            break
    for d in seen:
        print(d)


def strip_key_bytes(line, key):
    """Remove one top-level key from a JSON object line, leaving every other
    byte unchanged (number formats such as 2.0E-4, escapes, key order).

    Returns (new_line, removed). The result is verified: it must parse to
    exactly the original object minus that key, otherwise ValueError.
    Lines without the key are returned unchanged.
    """
    obj = json.loads(line)
    if not isinstance(obj, dict) or key not in obj:
        return line, False
    expected = {k: v for k, v in obj.items() if k != key}
    eol = b"\n" if line.endswith(b"\n") else b""
    text = line[: len(line) - len(eol)].decode("utf-8")
    needle = json.dumps(key) + ":"
    dec = json.JSONDecoder()
    start = 0
    while True:
        i = text.find(needle, start)
        if i < 0:
            raise ValueError(f"could not remove top-level key {key!r} byte-exactly")
        try:
            _, end = dec.raw_decode(text, i + len(needle))
        except ValueError:
            start = i + 1
            continue
        if end < len(text) and text[end] == ",":
            cand = text[:i] + text[end + 1:]
        elif i > 0 and text[i - 1] == ",":
            cand = text[: i - 1] + text[end:]
        else:
            cand = text[:i] + text[end:]
        try:
            if json.loads(cand) == expected:
                return cand.encode("utf-8") + eol, True
        except ValueError:
            pass
        start = i + 1


def strip_file(src, out, key):
    n = removed = 0
    with open(src, "rb") as fin, open(out, "wb") as fout:
        for line in fin:
            if not line.strip():
                continue
            new, r = strip_key_bytes(line, key)
            fout.write(new)
            n += 1
            removed += r
    print(f"strip-file records={n} removed={removed} key={key}", file=sys.stderr)


def crossref_batch_check(path, dois_path):
    want = set(read_dois(dois_path))
    with open(path, encoding="utf-8") as f:
        for i, line in enumerate(f, 1):
            if not line.strip():
                continue
            obj = json.loads(line)
            doi = obj.get("DOI") if isinstance(obj, dict) else None
            if not isinstance(doi, str) or doi.lower() not in want:
                sys.exit(f"{path}:{i}: DOI {doi!r} is not in this batch's DOI set")


def _ordered(items, dois, getter):
    by = {}
    for it in items:
        d = getter(it)
        if d:
            by.setdefault(d.lower(), it)
    return [by[d] for d in dois if d in by]


def crossref_jsonl(resp, dois_path, out):
    doc = json.load(open(resp, encoding="utf-8"))
    if doc.get("status") != "ok":
        sys.exit("crossref response status is not ok")
    if doc.get("message-type") != "work-list":
        sys.exit("crossref response message-type is not work-list")
    items = doc["message"]["items"]
    rows = _ordered(items, read_dois(dois_path), lambda it: it.get("DOI"))
    with open(out, "w", encoding="utf-8") as f:
        for it in rows:
            it = {k: v for k, v in it.items() if k != "abstract"}
            f.write(dump(it) + "\n")
    print(f"crossref items={len(items)} written={len(rows)}", file=sys.stderr)


def openalex_api_jsonl(resp, dois_path, out, strip_key=""):
    doc = json.load(open(resp, encoding="utf-8"))
    items = doc.get("results", [])

    def doi_of(it):
        d = it.get("doi") or ""
        return d.replace("https://doi.org/", "", 1)

    rows = _ordered(items, read_dois(dois_path), doi_of)
    # OpenAlex can answer a requested DOI with a work whose primary DOI is a
    # different (alias) DOI. Keep those too, after the matched rows, sorted
    # by OpenAlex ID, so the alias case stays visible in the sample.
    matched = {id(r) for r in rows}
    rows += sorted((it for it in items if id(it) not in matched), key=lambda it: it.get("id", ""))
    with open(out, "w", encoding="utf-8") as f:
        for it in rows:
            if strip_key:
                it = {k: v for k, v in it.items() if k != strip_key}
            f.write(dump(it) + "\n")
    print(f"openalex results={len(items)} written={len(rows)}", file=sys.stderr)


def openalex_pick_file(manifest, entity, lo, hi, max_gz):
    m = json.load(open(manifest, encoding="utf-8"))
    for e in m["entities"]:
        if e["entity"] != entity:
            continue
        files = sorted(e["files"], key=lambda f: f["url"], reverse=True)
        for f in files:
            meta = f["meta"]
            if lo <= meta["record_count"] <= hi and meta["content_length"] <= max_gz:
                print(f["url"], meta["content_length"], meta["record_count"])
                return
    sys.exit(f"no {entity} file within bounds")


def jsonl_head(src, out, max_records, max_bytes, strip_key=""):
    n = 0
    size = 0
    removed = 0
    with gzip.open(src, "rb") as fin, open(out, "wb") as fout:
        for line in fin:
            if not line.strip():
                continue
            json.loads(line)  # must be valid JSON; raises otherwise
            if not line.endswith(b"\n"):
                line += b"\n"
            if strip_key:
                line, r = strip_key_bytes(line, strip_key)
                removed += r
            if n + 1 > max_records or size + len(line) > max_bytes:
                break
            fout.write(line)
            n += 1
            size += len(line)
    print(f"jsonl-head records={n} bytes={size} stripped={removed}", file=sys.stderr)
    if n == 0:
        sys.exit("jsonl-head wrote no records")


def openalex_url(base, dois_path, select, mailto=""):
    dois = read_dois(dois_path)
    q = {
        "filter": "doi:" + "|".join("https://doi.org/" + d for d in dois),
        "select": select,
        "per-page": "50",
    }
    if mailto:
        q["mailto"] = mailto
    print(base + "?" + urllib.parse.urlencode(q, safe=":/|,"))


def crossref_url(base, dois_path, mailto=""):
    dois = read_dois(dois_path)
    q = {"filter": ",".join("doi:" + d for d in dois), "rows": str(len(dois))}
    if mailto:
        q["mailto"] = mailto
    print(base + "?" + urllib.parse.urlencode(q, safe=":/,"))


def main():
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "openalex-url":
        openalex_url(*args)
        return
    if cmd == "crossref-url":
        crossref_url(*args)
        return
    if cmd == "dblp-dois":
        dblp_dois(args[0], int(args[1]))
    elif cmd == "crossref-jsonl":
        crossref_jsonl(*args)
    elif cmd == "openalex-api-jsonl":
        openalex_api_jsonl(*args)
    elif cmd == "strip-file":
        strip_file(*args)
    elif cmd == "crossref-batch-check":
        crossref_batch_check(*args)
    elif cmd == "openalex-pick-file":
        openalex_pick_file(args[0], args[1], int(args[2]), int(args[3]), int(args[4]))
    elif cmd == "jsonl-head":
        jsonl_head(args[0], args[1], int(args[2]), int(args[3]), args[4] if len(args) > 4 else "")
    else:
        sys.exit(f"unknown subcommand {cmd}")


if __name__ == "__main__":
    main()
