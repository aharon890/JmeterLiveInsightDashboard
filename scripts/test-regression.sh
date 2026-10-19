#!/usr/bin/env bash
# =============================================================================
#  REGRESSION TIER — the real gate. Run it on a committed, clean tree when a
#  feature or fix is finished. scripts/merge-pr.sh refuses to merge unless a
#  fully green run is recorded for the exact content being merged, and runs
#  this tier itself when none is.
#
#  There is no CI: this repo runs its automation locally, on purpose. Nothing
#  re-checks the work after you push, so this run is the only verification that
#  will ever happen for these changes. Target: a few minutes.
#
#    Java    full unit suite, including @Tag("slow")   ~50s
#    Web     full Playwright suite (parallel)          ~30s
#    Docker  real JMeter + real jar, D1-D15           ~115s   [needs Docker]
#
#  The long .jmx load plans are deliberately NOT here — they are load
#  scenarios, not pass/fail tests. Run one on demand; see docs/TESTING.md.
#
#  Skip the container layer with:  SKIP_DOCKER=1 ./scripts/test-regression.sh
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

# Soft ceiling only — see warn_over_budget(). Sized off the measured ~3.5m local
# run with headroom, so it flags real drift rather than a slow morning.
REGRESSION_BUDGET_S=300

print_banner "Regression Tier — Live Insight Dashboard"

started=$(date +%s)
cd "$PROJECT_DIR"

# The green marker written at the end vouches for one exact content, and the
# push and merge gates both trust it. That only holds when the suite ran on a
# commit: with uncommitted or untracked files present it tested content no
# commit holds, and recording HEAD would certify a tree that was never tested.
start_tree=""
[[ -z "$(git status --porcelain 2>/dev/null)" ]] && start_tree="$(git rev-parse 'HEAD^{tree}' 2>/dev/null)"

# --- Committed script modes ---
# Instant, and only meaningful at commit time: Windows never notices a wrong
# exec bit, Linux/macOS clones break on it — see verify_script_modes.
echo "=== [1/4] Static checks (script modes, plan classes and keys, docker paths) ==="
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

# --- Java (full) ---
# The reports have to be copied aside BEFORE the Docker layer runs: that layer
# calls build_plugin() -> `mvn clean package`, and clean deletes target/ along
# with the surefire output this FAIL note points at. Archive first, record
# second, so the note can name a path that still exists when it is read.
echo ""
echo "=== [2/4] Java unit tests (full, including @slow) ==="
t0=$(date +%s)
mvn -B test -q
java_rc=$?
java_secs=$(( $(date +%s) - t0 ))
java_reports="$(archive_java_reports)"
if [[ $java_rc -eq 0 ]]; then
    record JAVA "Java unit tests (full)" PASS "" "$java_secs"
else
    record JAVA "Java unit tests (full)" FAIL "see $java_reports" "$java_secs"
fi

# --- Playwright (full) ---
# One record() for the layer whichever way it fails. Recording a FAIL for a
# broken `npm ci` and then running Playwright anyway counted the web layer
# twice and reported TOTAL: 3 for a three-layer tier that had only run two.
echo ""
echo "=== [3/4] Playwright full suite ==="
t0=$(date +%s)
if ! ensure_playwright_deps; then
    record WEB "Playwright full suite" FAIL "npm ci failed" "$(( $(date +%s) - t0 ))"
elif (cd "$PROJECT_DIR/tests/playwright" && npx playwright test --reporter=line); then
    record WEB "Playwright full suite" PASS "" "$(( $(date +%s) - t0 ))"
else
    record WEB "Playwright full suite" FAIL "npm --prefix tests/playwright run report" "$(( $(date +%s) - t0 ))"
fi

# --- Docker E2E ---
# The only layer that exercises the real plugin jar inside a real JMeter, so it
# catches what unit and UI tests structurally cannot. Skipped rather than failed
# when Docker is unavailable — but the summary says so, because a silent skip
# would read as a pass.
echo ""
echo "=== [4/4] Docker E2E (real JMeter) ==="
t0=$(date +%s)
if [[ "${SKIP_DOCKER:-0}" == "1" ]]; then
    echo "[SKIP] SKIP_DOCKER=1 — container layer not run."
    record DOCKER "Docker E2E (D1-D15)" SKIP "explicitly skipped via SKIP_DOCKER=1"
elif ! docker info >/dev/null 2>&1; then
    echo "[SKIP] Docker daemon not running — start Docker Desktop to include this layer."
    record DOCKER "Docker E2E (D1-D15)" SKIP "docker daemon unavailable"
elif bash "$PROJECT_DIR/scripts/run-docker-tests.sh"; then
    record DOCKER "Docker E2E (D1-D15)" PASS "" "$(( $(date +%s) - t0 ))"
else
    record DOCKER "Docker E2E (D1-D15)" FAIL "see test-results/docker/jmeter.log" "$(( $(date +%s) - t0 ))"
fi

elapsed=$(( $(date +%s) - started ))
print_summary "REGRESSION TIER SUMMARY (${elapsed}s)"
warn_over_budget "$elapsed" "$REGRESSION_BUDGET_S" "Regression"

# A green suite does NOT mean the jar in lib/ext matches src — the Docker layer
# builds its own. Say so here or the next manual JMeter run silently uses old code.
warn_stale_deploy

if [[ $FAIL -gt 0 ]]; then
    echo ""
    echo "Regression FAILED — do not push."
    exit $FAIL
fi
echo ""
if [[ $SKIP -gt 0 ]]; then
    # Exit 0 — a skipped layer is not a failure — but do not call this green.
    # "Safe to push" next to an unrun layer is exactly how a gap gets shipped.
    echo "Regression passed WITH $SKIP layer(s) skipped — not full coverage."
    echo "Run the skipped layer before pushing if the change touches it."
else
    # Fully green: record which CONTENT this run vouches for. The Claude Code
    # push gate (.claude/hooks/pre-push-gate.sh) checks it before `git push`,
    # and scripts/merge-pr.sh merges on it instead of re-running this tier.
    # Tree hash, not commit sha: a merge commit whose tree is identical to the
    # tested branch head must stay green — same content, different sha — while
    # any content change still invalidates the marker. A SKIP run deliberately
    # records nothing — a skipped layer is a gap, and the gates must treat it as
    # one. Neither does a run that started or ended with a dirty tree (above).
    end_tree="$(git -C "$PROJECT_DIR" rev-parse 'HEAD^{tree}' 2>/dev/null)"
    if [[ -n "$start_tree" && "$start_tree" == "$end_tree" \
          && -z "$(git -C "$PROJECT_DIR" status --porcelain 2>/dev/null)" ]]; then
        mkdir -p "$PROJECT_DIR/test-results"
        echo "$start_tree" > "$PROJECT_DIR/test-results/.regression-green"
        echo "Regression green. Safe to push."
    else
        echo "Regression green, but NOT recorded: the working tree had uncommitted"
        echo "or untracked changes, so this run vouches for no commit. Commit, then"
        echo "re-run before pushing."
    fi
fi
exit 0
