"""Offline stand-in for curl, used only by datasets/scripts/test-download.sh.

Owner: dataset-engineer. Test support only; performs no network I/O.

Usage: python3 -I stub_curl.py <log.jsonl> <url-table.tsv> <ignore-max-filesize 0|1> <curl args...>

Behaviour
  - Appends one JSON line per invocation to <log.jsonl>: {"args": [...]},
    then {"written": <bytes delivered>, "url": ...} when it finishes.
  - Body for a URL:
      * https://api.crossref.org/works?filter=doi:a,doi:b,...  a generated
        work-list response with one item per requested DOI (each item also
        carries an "abstract", which the code under test must drop);
      * otherwise the <url-table.tsv> entry "<url>\t<path>" (file bytes) or
        "<url>\tGEN:<n>" (n bytes of b"x");
      * no entry: exit 22, like curl --fail on HTTP 404.
  - --continue-at N|- skips N bytes (or the current size of --output);
    an offset beyond the body exits 22 (HTTP 416 with --fail).
  - --max-filesize is honoured (exit 63 before writing) unless
    ignore-max-filesize is 1, which simulates a chunked response without
    Content-Length on curl < 8.4, where the limit is not applied.
  - Writes to --output (append when --continue-at is given) or to stdout in
    64 KiB chunks; a closed pipe ends the transfer with exit 23, like curl.
"""

import json
import os
import sys
import urllib.parse

VALUE_OPTS = {
    "--output", "-o", "--continue-at", "-C", "--max-filesize", "--max-time",
    "--range", "-r", "--header", "-H", "--user-agent", "-A", "--retry",
    "--retry-delay",
}


def body_for(url, table):
    parsed = urllib.parse.urlsplit(url)
    if parsed.netloc == "api.crossref.org" and parsed.path == "/works":
        q = urllib.parse.parse_qs(parsed.query)
        dois = [p[len("doi:"):] for p in q.get("filter", [""])[0].split(",") if p.startswith("doi:")]
        items = [{"DOI": d, "title": [f"stub {d}"], "abstract": "<jats:p>stub</jats:p>"} for d in dois]
        doc = {"status": "ok", "message-type": "work-list", "message-version": "1.0.0",
               "message": {"items": items, "total-results": len(items)}}
        return json.dumps(doc).encode()
    with open(table, encoding="utf-8") as f:
        for line in f:
            if "\t" not in line:
                continue
            u, src = line.rstrip("\n").split("\t", 1)
            if u == url:
                if src.startswith("GEN:"):
                    return b"x" * int(src[4:])
                with open(src, "rb") as g:
                    return g.read()
    return None


def main():
    log, table, ignore_maxfs = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
    args = sys.argv[4:]
    with open(log, "a", encoding="utf-8") as f:
        f.write(json.dumps({"args": args}) + "\n")
    out = cont = maxfs = url = None
    i = 0
    while i < len(args):
        a = args[i]
        if a in VALUE_OPTS:
            v = args[i + 1]
            if a in ("--output", "-o"):
                out = v
            elif a in ("--continue-at", "-C"):
                cont = v
            elif a == "--max-filesize":
                maxfs = int(v)
            i += 2
        elif a.startswith("-"):
            i += 1
        else:
            url = a
            i += 1
    body = body_for(url, table)
    if body is None:
        print(f"stub curl: no fixture for {url}", file=sys.stderr)
        return 22
    offset = 0
    if cont == "-":
        offset = os.path.getsize(out) if out and os.path.exists(out) else 0
    elif cont is not None:
        offset = int(cont)
    if offset > len(body):
        print("stub curl: range not satisfiable", file=sys.stderr)
        return 22
    body = body[offset:]
    if maxfs is not None and not ignore_maxfs and len(body) > maxfs:
        print("stub curl: (63) Maximum file size exceeded", file=sys.stderr)
        return 63
    written = 0
    rc = 0
    if out:
        with open(out, "ab" if cont is not None else "wb") as f:
            f.write(body)
        written = len(body)
    else:
        try:
            for k in range(0, len(body), 65536):
                chunk = body[k:k + 65536]
                sys.stdout.buffer.write(chunk)
                sys.stdout.buffer.flush()
                written += len(chunk)
        except BrokenPipeError:
            rc = 23
            try:
                sys.stdout = None
            except Exception:
                pass
    with open(log, "a", encoding="utf-8") as f:
        f.write(json.dumps({"written": written, "url": url}) + "\n")
    return rc


if __name__ == "__main__":
    code = main()
    os._exit(code)
