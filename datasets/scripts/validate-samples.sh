#!/usr/bin/env bash
# datasets/scripts/validate-samples.sh
#
# Owner: dataset-engineer.
# Verifies the committed samples: sha256sum -c against SHA256SUMS, then
# per-file caps (<= 50 records, <= 200000 bytes; "200 KB" = 200,000 bytes,
# human decision 2026-11-19), JSON Lines validity, XML well-formedness with
# the local DBLP DTD, no Crossref abstracts, no non-empty OpenAlex
# abstract_inverted_index, and the ORCID privacy policy as an allowlist
# (only allowed element paths and attributes; at most 5 ORCID person
# records in total).
# Performs no network I/O and writes nothing.
#
# Usage: datasets/scripts/validate-samples.sh

set -euo pipefail

# shellcheck source=datasets/scripts/common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

require_cmd python3 sha256sum
[[ -f "$SHA256SUMS_FILE" ]] || die "missing $SHA256SUMS_FILE"

(cd "$SAMPLES_DIR" && sha256sum -c SHA256SUMS)
py check_samples.py "$SAMPLES_DIR"
