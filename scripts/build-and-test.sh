#!/usr/bin/env bash
# =============================================================================
#  Build, deploy to the local JMeter, and open the smoke plan in the JMeter GUI.
#  Requires a local JMeter install; for a zero-install loop use
#  ./scripts/run-docker-dashboard.sh instead.
# =============================================================================
set -euo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

require_jmeter

echo "Project:  $PROJECT_DIR"
echo "Version:  $PLUGIN_VERSION"
echo "JMeter:   $JMETER_HOME"
echo ""

build_plugin
deploy_plugin

echo "=== Launching JMeter GUI with smoke test ==="
run_jmeter -t "$TEST_PLANS/quick-smoke-test_2.jmx"
