"""Print one scalar value from the dataset manifest.

Owner: dataset-engineer.
Usage: python3 -I manifest_get.py <manifest.yaml> <dotted.path>
Exits 1 when the path is missing or null, so callers never act on a guess.
"""

import sys

import yaml


def main() -> int:
    path, dotted = sys.argv[1], sys.argv[2]
    with open(path, encoding="utf-8") as f:
        node = yaml.safe_load(f)
    for part in dotted.split("."):
        if isinstance(node, dict) and part in node:
            node = node[part]
        elif isinstance(node, list) and part.isdigit() and int(part) < len(node):
            node = node[int(part)]
        else:
            print(f"manifest path not found: {dotted}", file=sys.stderr)
            return 1
    if node is None:
        print(f"manifest value is null (UNVERIFIED): {dotted}", file=sys.stderr)
        return 1
    if isinstance(node, (dict, list)):
        print(f"manifest path is not a scalar: {dotted}", file=sys.stderr)
        return 1
    print(node)
    return 0


if __name__ == "__main__":
    sys.exit(main())
