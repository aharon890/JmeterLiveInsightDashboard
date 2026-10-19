#!/usr/bin/env bash
# =============================================================================
#  Run the Java tests and open the Surefire HTML report.
# =============================================================================
set -euo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

echo "=== Running Tests with Reports ==="
cd "$PROJECT_DIR"
if ! mvn test surefire-report:report; then
    echo "TEST REPORT GENERATION FAILED" >&2
    exit 1
fi

echo "=== Opening Java Test Report ==="
open_path "$PROJECT_DIR/target/site/surefire-report.html"
echo "Done."
