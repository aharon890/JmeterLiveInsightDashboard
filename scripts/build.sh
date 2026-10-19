#!/usr/bin/env bash
# =============================================================================
#  Build the plugin and deploy the JAR to the local JMeter install (if any).
# =============================================================================
set -euo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

echo "Project:  $PROJECT_DIR"
echo "Version:  $PLUGIN_VERSION"

build_plugin

if JMETER_HOME="$(detect_jmeter_home)"; then
    export JMETER_HOME
    JMETER_EXT="$JMETER_HOME/lib/ext"
    echo "JMeter:   $JMETER_HOME"
    deploy_plugin
    echo ""
    echo "Plugin ${PLUGIN_VERSION} deployed to ${JMETER_EXT}"
else
    echo ""
    echo "No local JMeter found — build only, deploy skipped."
    echo "Use ./scripts/run-docker-dashboard.sh to run without a local install."
fi
