#!/usr/bin/env bash
# =============================================================================
#  Run the 5-minute comparison plan (non-GUI) against the local JMeter.
#  Dashboard activation comes from the Live Insight Dashboard listener saved
#  in the .jmx plan.
# =============================================================================
set -euo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

require_jmeter
require_plugin_jar

mkdir -p "$RESULTS_DIR"
JTL="$RESULTS_DIR/comparison_$(file_timestamp).jtl"

echo "=== Running 5-Min Comparison Test ==="
echo "Live Dashboard will be available at http://localhost:${DASHBOARD_PORT}"
echo "After the test ends: export a Live Dashboard snapshot for comparison."
echo ""

run_jmeter -n -t "$TEST_PLANS/5min-comparison-test.jmx" -l "$JTL"

echo ""
echo "=== Test Complete ==="
echo ""
echo "Reports generated:"
echo "  1. Live Dashboard: http://localhost:${DASHBOARD_PORT} (export snapshot now!)"
echo "  2. JTL file: $JTL"
echo ""
echo "To generate JMeter's native HTML report from the JTL:"
echo "  \"$JMETER\" -g \"$JTL\" -o \"$RESULTS_DIR/html-report\""
