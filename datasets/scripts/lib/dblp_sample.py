"""Select a small, deterministic excerpt of real DBLP records.

Owner: dataset-engineer. Sample building only. This is NOT the production
DBLP parser (that belongs to hadoop-engineer in modules/hadoop).

Reads a gzip byte stream of a DBLP XML release on stdin (the caller bounds the
transfer with an HTTP Range request), decompresses it incrementally, and keeps
complete top-level records in stream order according to fixed strata quotas.
Record bytes are copied verbatim (ISO-8859-1, entity references intact). The
original XML declaration and DOCTYPE are preserved and a closing </dblp> is
appended so the excerpt is a well-formed document.

Usage: python3 -I dblp_sample.py <output.xml> <max-compressed-bytes>
Exit codes: 0 all quotas filled, 3 byte cap reached before quotas filled.
"""

import re
import sys
import zlib

RECORD_TYPES = (
    b"article|inproceedings|proceedings|book|incollection|"
    b"phdthesis|mastersthesis|www|data"
)
START = re.compile(rb"<(" + RECORD_TYPES + rb")\s[^>]*>")
KEY = re.compile(rb'\skey="([^"]+)"')
AUTHOR = re.compile(rb"<author[\s>]")
DOI_EE = re.compile(rb"<ee[^>]*>https://doi\.org/")
ENTITY_AUTHOR = re.compile(rb"<author[^>]*>[^<]*&[A-Za-z]+;")

# (name, quota, predicate). A record goes to the FIRST stratum with remaining
# quota whose predicate matches. Order is part of the frozen subset rule; see
# datasets/dataset-manifest.yaml (dblp.samples[].subset_rule).
STRATA = [
    ("conf_inproceedings_entity_author", 6,
     lambda t, k, r: t == b"inproceedings" and k.startswith(b"conf/")
     and len(AUTHOR.findall(r)) >= 2 and DOI_EE.search(r)
     and ENTITY_AUTHOR.search(r)),
    ("conf_inproceedings_multi_author_doi", 24,
     lambda t, k, r: t == b"inproceedings" and k.startswith(b"conf/")
     and len(AUTHOR.findall(r)) >= 2 and DOI_EE.search(r)),
    ("series_inproceedings_multi_author", 3,
     lambda t, k, r: t == b"inproceedings" and k.startswith(b"series/")
     and len(AUTHOR.findall(r)) >= 2),
    ("reference_incollection_with_author", 3,
     lambda t, k, r: t == b"incollection" and k.startswith(b"reference/")
     and len(AUTHOR.findall(r)) >= 1),
    ("data", 3, lambda t, k, r: t == b"data"),
    ("mastersthesis", 2, lambda t, k, r: t == b"mastersthesis"),
    ("phdthesis", 2, lambda t, k, r: t == b"phdthesis"),
    ("proceedings", 3, lambda t, k, r: t == b"proceedings"),
    ("book", 2, lambda t, k, r: t == b"book"),
    ("dblpnote_article", 2,
     lambda t, k, r: t == b"article" and k.startswith(b"dblpnote/")),
]


def main() -> int:
    out_path = sys.argv[1]
    cap = int(sys.argv[2])
    remaining = {name: quota for name, quota, _ in STRATA}
    chosen = []  # (stratum, key, bytes) in stream order
    header = None
    buf = b""
    dec = zlib.decompressobj(16 + zlib.MAX_WBITS)
    read = 0
    stdin = sys.stdin.buffer

    def done() -> bool:
        return all(v == 0 for v in remaining.values())

    while read < cap and not done():
        chunk = stdin.read(min(65536, cap - read))
        if not chunk:
            break
        read += len(chunk)
        try:
            buf += dec.decompress(chunk)
        except zlib.error:
            break
        if header is None:
            i = buf.find(b"<dblp>")
            if i < 0:
                continue
            header = buf[: i + len(b"<dblp>")] + b"\n"
            buf = buf[i + len(b"<dblp>"):]
        pos = 0
        while not done():
            m = START.search(buf, pos)
            if not m:
                break
            rtype = m.group(1)
            end_tag = b"</" + rtype + b">"
            e = buf.find(end_tag, m.end())
            if e < 0:
                break
            rec = buf[m.start(): e + len(end_tag)]
            pos = e + len(end_tag)
            km = KEY.search(m.group(0))
            key = km.group(1) if km else b""
            for name, _quota, pred in STRATA:
                if remaining[name] > 0 and pred(rtype, key, rec):
                    remaining[name] -= 1
                    chosen.append((name, key, rec))
                    break
        buf = buf[pos:]
        if len(buf) > 8_000_000:  # no record is this large; resync
            buf = buf[-1_000_000:]

    if header is None:
        print("no <dblp> root found in the bounded stream", file=sys.stderr)
        return 2
    with open(out_path, "wb") as f:
        f.write(header)
        for _name, _key, rec in chosen:
            f.write(rec + b"\n")
        f.write(b"</dblp>\n")
    counts = {}
    for name, _k, _r in chosen:
        counts[name] = counts.get(name, 0) + 1
    print(f"compressed_bytes_read={read} records={len(chosen)}", file=sys.stderr)
    for name, _q, _p in STRATA:
        print(f"  stratum {name}: {counts.get(name, 0)}", file=sys.stderr)
    return 0 if done() else 3


if __name__ == "__main__":
    sys.exit(main())
