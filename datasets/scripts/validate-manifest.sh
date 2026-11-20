#!/usr/bin/env bash
# datasets/scripts/validate-manifest.sh
#
# Owner: dataset-engineer.
# Validates datasets/dataset-manifest.yaml: required fields, UNVERIFIED
# bookkeeping for every null, ${RAW_BUCKET} placeholders, https sources, the
# identity limitation, and sample entries against files and SHA256SUMS.
# Exits nonzero on any missing required field or other violation.
# Performs no network I/O and writes nothing.
#
# Usage: datasets/scripts/validate-manifest.sh [manifest.yaml]

set -euo pipefail

# shellcheck source=datasets/scripts/common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

require_cmd python3
python3 -I -c 'import yaml' 2>/dev/null || die "python3 module 'yaml' (PyYAML) is required"

manifest="${1:-$MANIFEST_FILE}"
[[ -f "$manifest" ]] || die "manifest not found: $manifest"

py validate_manifest.py "$manifest" "$SAMPLES_DIR"
