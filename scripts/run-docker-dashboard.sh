#!/usr/bin/env bash
# =============================================================================
#  Interactive Docker dev loop — build the plugin, run a plan in a JMeter
#  container, and browse the live dashboard from the host. No local JMeter
#  install required.
#
#  Usage:
#    ./scripts/run-docker-dashboard.sh [plan.jmx] [extra -J args...]
#
#  Default plan: docker-smoke-test.jmx. Ctrl-C stops the container.
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

IMAGE_NAME="${IMAGE_NAME:-jmeter-livedashboard-test:5.6.3}"
CONTAINER_NAME="ld-dashboard-$$"
DOCKER_RESULTS="$RESULTS_DIR/docker-interactive"
CONTAINER_EXT="/opt/apache-jmeter-5.6.3/lib/ext"

PLAN="${1:-docker-smoke-test.jmx}"
[[ $# -gt 0 ]] && shift

if [[ ! -f "$TEST_PLANS/$PLAN" ]]; then
    echo "ERROR: test plan not found: $TEST_PLANS/$PLAN" >&2
    exit 1
fi

if ! docker info >/dev/null 2>&1; then
    echo "ERROR: Docker daemon is not running. Start Docker Desktop and retry." >&2
    exit 1
fi

cleanup() { docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM

build_plugin || exit 1
require_plugin_jar

echo "[DOCKER] Building JMeter image $IMAGE_NAME ..."
docker build -q -t "$IMAGE_NAME" -f "$PROJECT_DIR/docker/Dockerfile.jmeter" "$PROJECT_DIR/docker/" >/dev/null || exit 1

mkdir -p "$DOCKER_RESULTS"

# bind=0.0.0.0 so the published port reaches the server; long idle timeout so
# the dashboard stays up for review after the test ends. NOTE: for most settings
# a value saved in the plan wins over -J; bind and open.browser are the
# exceptions (-J wins), which is why those two flags work with any plan.
# docker_run/docker_host_path, never a bare `docker run`: under Git Bash, MSYS
# rewrote every container path here into C:/Program Files/Git/..., JMeter could
# not find the plan and the container exited before the dashboard started.
echo "[DOCKER] Running $PLAN ..."
docker_run run -d --name "$CONTAINER_NAME" \
    -p "${DASHBOARD_PORT}:7566" \
    -v "$(docker_host_path "$PLUGIN_JAR"):$CONTAINER_EXT/$(basename "$PLUGIN_JAR")" \
    -v "$(docker_host_path "$TEST_PLANS/$PLAN"):/test/$PLAN" \
    -v "$(docker_host_path "$DOCKER_RESULTS"):/results" \
    "$IMAGE_NAME" \
    -n -t "/test/$PLAN" \
    -l /results/results.jtl \
    -j /results/jmeter.log \
    -Jlive.dashboard.bind=0.0.0.0 \
    -Jlive.dashboard.open.browser=false \
    -Jlive.dashboard.idle.timeout=1800 \
    "$@" >/dev/null || exit 1

if wait_for_dashboard "$DASHBOARD_PORT" 45; then
    echo ""
    echo "==============================================================="
    echo "  Dashboard: http://localhost:${DASHBOARD_PORT}"
    echo "  Plan:      $PLAN    Plugin: $PLUGIN_VERSION"
    echo "  Ctrl-C stops the container."
    echo "==============================================================="
    echo ""
else
    echo "WARNING: dashboard did not answer within 45s — container log follows." >&2
fi

# Stream logs until the container exits (idle timeout) or Ctrl-C.
docker logs -f "$CONTAINER_NAME" 2>&1 || true
echo ""
echo "Container finished. Results: $DOCKER_RESULTS"
