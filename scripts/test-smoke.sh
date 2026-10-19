#!/usr/bin/env bash
# =============================================================================
#  SMOKE TIER — the fast gate. Run this while developing.
#
#  There is no CI: this repo runs its automation locally, on purpose. So this is
#  not a preview of a verdict handed down elsewhere — it IS the verdict, and a
#  layer that does not run here does not run anywhere. Target: under a minute.
#
#    Java   unit tests, minus @Tag("slow"), and the shaded jar   ~25s
#    Web    Playwright @smoke subset                             ~10s
#
#  For the full pre-push gate including real JMeter, use test-regression.sh.
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

# Soft ceiling only — see warn_over_budget(). This tier's whole value is being
# fast enough to run mid-change, so drift here matters more than in regression.
# Measured 32-58s on this machine.
SMOKE_BUDGET_S=90

print_banner "Smoke Tier — Live Insight Dashboard"

started=$(date +%s)
cd "$PROJECT_DIR"

# --- Committed script modes ---
# Instant, and only meaningful at commit time: Windows never notices a wrong
# exec bit, Linux/macOS clones break on it — see verify_script_modes.
echo "=== [1/3] Static checks (script modes, plan classes and keys, docker paths) ==="
STATIC="Static checks (script modes, plan classes and keys, docker paths)"
if ! mode_problems="$(verify_script_modes)"; then
    echo "$mode_problems"
    record MODES "$STATIC" FAIL "a 100644 hook/script breaks Linux/macOS clones"
elif ! plan_problems="$(verify_test_plan_classes)"; then
    echo "$plan_problems"
    record MODES "$STATIC" FAIL "a test plan references another plugin's class"
elif ! key_problems="$(verify_test_plan_keys)"; then
    echo "$key_problems"
    record MODES "$STATIC" FAIL "a test plan sets a property the plugin doesn't read"
elif ! docker_problems="$(verify_docker_path_handling)"; then
    echo "$docker_problems"
    record MODES "$STATIC" FAIL "a script runs docker without Git Bash path handling"
else
    record MODES "$STATIC" PASS
fi

# --- Java (fast tier) + packaging ---
# `verify`, not `test`: the shade plugin is bound to `package` (pom.xml), so
# `mvn test` never runs it. A broken <relocation>, or a resource that stops
# being copied into the jar, therefore passed smoke and only surfaced on a
# master push or in the Docker layer. `verify` runs the same tests plus the
# packaging for a few extra seconds — no failsafe is configured, so there is
# nothing else in that phase to pay for.
echo ""
echo "=== [2/3] Java unit tests (excluding @slow) + shaded jar ==="
t0=$(date +%s)
mvn -B verify -DexcludedGroups=slow -q
java_rc=$?
java_reports="$(archive_java_reports)"
# `verify` proves shade ran; assert_jar_sane proves what it produced is usable.
# Only meaningful if the build got that far, hence the guard.
jar_problems=""
if [[ $java_rc -eq 0 ]]; then
    jar_problems="$(assert_jar_sane)" || java_rc=1
fi
java_secs=$(( $(date +%s) - t0 ))
if [[ $java_rc -eq 0 ]]; then
    record JAVA "Java unit tests (fast tier) + shaded jar" PASS "" "$java_secs"
elif [[ -n "$jar_problems" ]]; then
    echo "$jar_problems"
    record JAVA "Java unit tests (fast tier) + shaded jar" FAIL "shaded jar is wrong, see above" "$java_secs"
else
    record JAVA "Java unit tests (fast tier) + shaded jar" FAIL "see $java_reports" "$java_secs"
fi

# --- Playwright (@smoke) ---
# One record() for the layer whichever way it fails — see the same note in
# test-regression.sh.
echo ""
echo "=== [3/3] Playwright @smoke subset ==="
t0=$(date +%s)
if ! ensure_playwright_deps; then
    record WEB "Playwright @smoke subset" FAIL "npm ci failed" "$(( $(date +%s) - t0 ))"
elif (cd "$PROJECT_DIR/tests/playwright" && npx playwright test --grep "@smoke" --reporter=line); then
    record WEB "Playwright @smoke subset" PASS "" "$(( $(date +%s) - t0 ))"
else
    record WEB "Playwright @smoke subset" FAIL "npm --prefix tests/playwright run report" "$(( $(date +%s) - t0 ))"
fi

elapsed=$(( $(date +%s) - started ))
print_summary "SMOKE TIER SUMMARY (${elapsed}s)"
warn_over_budget "$elapsed" "$SMOKE_BUDGET_S" "Smoke"

# Deliberately no warn_stale_deploy() here, unlike test-regression.sh. Mid-change
# the deployed jar is almost always older than src/main — that is the normal
# state of iterating — so warning every run would train everyone to ignore it.
# The pre-push gate is where it carries information.

if [[ $FAIL -gt 0 ]]; then
    echo ""
    echo "Smoke FAILED — do not push. Re-run a single layer while you fix it:"
    echo "  mvn test -DexcludedGroups=slow"
    echo "  cd tests/playwright && npm run test:smoke"
fi
exit $FAIL
