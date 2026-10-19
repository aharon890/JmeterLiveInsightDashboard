#!/usr/bin/env bash
# =============================================================================
#  RELEASE CHECK: run once per release, by the _perf_release skill after the
#  version bump, or on demand. Never a regression layer: it needs minutes and
#  Docker every time, and merge-pr.sh refuses any run with a SKIP.
#
#  It covers what the tiers can't, because they only ever run one way, on one box:
#
#    Versions   pom has no -SNAPSHOT; every jar name in the docs is this version
#    Jar        build_plugin's jar check, on the jar this script builds
#    Browsers   Playwright @smoke on Firefox and WebKit (the tiers run Chromium)
#    Java 11    the Docker gate, D1-D15, with JMeter on Java 11, the oldest
#               Java the jar is built for (the tiers run Java 17)  [needs Docker]
#
#  ~5 min. Nothing here is skipped quietly: an unavailable layer is a FAIL,
#  because a release is the one run where a gap ships.
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

print_banner "Release Check — Live Insight Dashboard"

started=$(date +%s)
cd "$PROJECT_DIR"

echo "=== [1/4] Versions ==="
if problems="$(verify_release_versions)"; then
    record VERSIONS "Release version ${PLUGIN_VERSION} (pom and docs)" PASS
else
    echo "$problems"
    record VERSIONS "Release version ${PLUGIN_VERSION} (pom and docs)" FAIL "see above"
fi

echo ""
echo "=== [2/4] Jar ==="
if build_plugin; then
    record JAR "Shaded jar builds and passes the jar check" PASS
else
    record JAR "Shaded jar builds and passes the jar check" FAIL "see above"
fi

echo ""
echo "=== [3/4] Playwright @smoke on Firefox and WebKit ==="
t0=$(date +%s)
if ensure_playwright_deps \
    && (cd tests/playwright && npx playwright install firefox webkit \
        && PW_BROWSERS=firefox,webkit npx playwright test --grep @smoke); then
    record BROWSERS "Playwright @smoke, Firefox + WebKit" PASS "" "$(( $(date +%s) - t0 ))"
else
    record BROWSERS "Playwright @smoke, Firefox + WebKit" FAIL "see above" "$(( $(date +%s) - t0 ))"
fi

echo ""
echo "=== [4/4] Docker gate with JMeter on Java 11 ==="
t0=$(date +%s)
if ! docker info >/dev/null 2>&1; then
    record JAVA11 "Docker E2E (D1-D15) on Java 11" FAIL "docker daemon unavailable"
elif JAVA_IMAGE=eclipse-temurin:11-jre IMAGE_NAME=jmeter-livedashboard-test:5.6.3-java11 \
        bash "$PROJECT_DIR/scripts/run-docker-tests.sh"; then
    record JAVA11 "Docker E2E (D1-D15) on Java 11" PASS "" "$(( $(date +%s) - t0 ))"
else
    record JAVA11 "Docker E2E (D1-D15) on Java 11" FAIL "see test-results/docker/jmeter.log" "$(( $(date +%s) - t0 ))"
fi

elapsed=$(( $(date +%s) - started ))
print_summary "RELEASE CHECK SUMMARY (${elapsed}s)"
if [[ $FAIL -gt 0 ]]; then
    echo ""
    echo "Release check FAILED — do not publish."
    exit $FAIL
fi
echo ""
echo "Release check passed."
