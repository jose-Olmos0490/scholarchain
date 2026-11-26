"""Shared helpers: CSV and SVG output with no timestamps, so the same raw data gives the same bytes."""

import csv
import hashlib
import os
from xml.sax.saxutils import escape


def read_csv(path):
    with open(path, newline="") as handle:
        return list(csv.DictReader(handle))


def write_csv(out, name, header, rows):
    path = os.path.join(out, name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(header)
        writer.writerows(rows)


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def _save(out, name, parts, width, height):
    path = os.path.join(out, name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as handle:
        handle.write('<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" font-family="sans-serif" '
                     'font-size="12">\n<rect width="100%%" height="100%%" fill="white"/>\n' % (width, height))
        handle.write("\n".join(parts))
        handle.write("\n</svg>\n")


def _text(x, y, value, anchor="start"):
    return '<text x="%.1f" y="%.1f" text-anchor="%s">%s</text>' % (x, y, anchor, escape(str(value)))


def svg_intervals(out, name, title, items):
    """items: (label, point or None, low or None, high or None), values in [0, 1]."""
    left, span, step = 220, 420, 22
    height = 60 + step * len(items)
    parts = [_text(10, 20, title)]
    for tick in (0, 0.25, 0.5, 0.75, 1.0):
        x = left + span * tick
        parts.append('<line x1="%.1f" y1="30" x2="%.1f" y2="%d" stroke="#ddd"/>' % (x, x, height - 25))
        parts.append(_text(x, height - 8, "%.2f" % tick, "middle"))
    for index, (label, point, low, high) in enumerate(items):
        y = 45 + step * index
        parts.append(_text(left - 10, y + 4, label, "end"))
        if point is None:
            parts.append(_text(left + 4, y + 4, "n/a"))
            continue
        if low is not None:
            parts.append('<line x1="%.1f" y1="%d" x2="%.1f" y2="%d" stroke="black" stroke-width="2"/>'
                         % (left + span * low, y, left + span * high, y))
        parts.append('<circle cx="%.1f" cy="%d" r="4" fill="#1f5fa8"/>' % (left + span * point, y))
        parts.append(_text(left + span + 15, y + 4, "%.3f [%.3f, %.3f]" % (point, low, high)))
    _save(out, name, parts, left + span + 180, height)


def svg_bars(out, name, title, items):
    """items: (label, number)."""
    left, span, step = 260, 400, 20
    top = max([v for _, v in items] + [1])
    height = 45 + step * len(items)
    parts = [_text(10, 20, title)]
    for index, (label, value) in enumerate(items):
        y = 35 + step * index
        parts.append(_text(left - 8, y + 11, label, "end"))
        parts.append('<rect x="%d" y="%d" width="%.1f" height="14" fill="#1f5fa8"/>' % (left, y, span * value / top))
        parts.append(_text(left + span * value / top + 6, y + 11, value))
    _save(out, name, parts, left + span + 80, height)
