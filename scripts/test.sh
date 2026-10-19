#!/usr/bin/env bash
# =============================================================================
#  Run the Java unit-test suite.
# =============================================================================
set -euo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

echo "=== Running Tests ==="
cd "$PROJECT_DIR"
if ! mvn test; then
    echo "TESTS FAILED" >&2
    exit 1
fi
echo "All tests passed."
