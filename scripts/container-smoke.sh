#!/usr/bin/env bash
set -euo pipefail
# Requires an immutable local image; never publishes a port or starts collection.
exec python3 "$(dirname "$0")/../tools/check-isolated-container.py" "${1:?immutable sha256 image ID required}"
