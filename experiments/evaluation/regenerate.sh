#!/usr/bin/env bash
# One command that regenerates every Part A table and plot from raw data:
#   experiments/evaluation/regenerate.sh <run-id>
# Needs uv (fetches the two pinned packages on first use). No AWS access.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec uv run --no-project --with pyarrow==21.0.0 --with pyyaml==6.0.2 python3 -B "$here/bin/build_part_a.py" --run-id "${1:?run id required}"
