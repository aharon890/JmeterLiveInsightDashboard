#!/usr/bin/env bash
# =============================================================================
#  Docker E2E Runner — Live Insight Dashboard
#
#  Builds the plugin on the host, builds the JMeter image, runs the docker
#  smoke plan in a container, and asserts against the LIVE dashboard while the
#  test runs (the plugin's output is an HTTP server, not a report file).
#
#  PLATFORM: macOS, Linux, WSL2 and Git Bash on Windows.
#  Windows needs care with docker -v: MSYS rewrites POSIX-looking arguments
#  into Windows paths before docker.exe sees them, mangling the CONTAINER side
#  into e.g. /test/C:/Program Files/Git/test/docker-smoke-test.jmx. That is
#  handled by docker_run()/docker_host_path() in lib/common.sh — do not inline
#  a bare `docker run` with volume mounts here or Windows breaks again.
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

IMAGE_NAME="${IMAGE_NAME:-jmeter-livedashboard-test:5.6.3}"
CONTAINER_NAME="ld-smoke-$$"
DOCKER_RESULTS="$RESULTS_DIR/docker"
CONTAINER_EXT="/opt/apache-jmeter-5.6.3/lib/ext"
SMOKE_PLAN="docker-smoke-test.jmx"
# The plan's sub-result labels: the steps of its transaction that generates a
# parent sample. The JTL writes them as rows of their own, but JMeter's live
# reports and the dashboard's TOTAL count each transaction once (D10, D13).
SMOKE_CHILDREN='^TX step '
# Smoke plan runs 30s thread groups; idle timeout 10s; generous margin for JVM start.
EXIT_DEADLINE_S=120

# 7566 is the port a REAL dashboard listens on, so defaulting the E2E to it put
# the test in direct competition with the developer's own browser: a dashboard
# tab left open holds an EventSource, keeps the server alive past its idle
# timeout, and D5 then fails looking exactly like a product bug. Default
# somewhere nothing else claims; an explicit DASHBOARD_PORT still wins.
if [[ "${DASHBOARD_PORT_EXPLICIT:-0}" == "0" ]]; then
    DASHBOARD_PORT=7599
fi

print_banner "Docker E2E Runner — Live Insight Dashboard"

if ! docker info >/dev/null 2>&1; then
    echo "ERROR: Docker daemon is not running. Start Docker Desktop and retry." >&2
    exit 1
fi

# A better default only lowers the odds of a collision; this removes the
# guesswork. Fail now, naming the cause, rather than letting it resurface as a
# baffling D5 failure two minutes from here.
if dashboard_status "$DASHBOARD_PORT" >/dev/null 2>&1; then
    cat >&2 <<EOF
ERROR: something is already serving a dashboard on port $DASHBOARD_PORT.

  The E2E needs the port to itself: it asserts on /api/status and on the
  container exiting once no SSE client is left. An existing dashboard (or a
  browser tab still holding one open) breaks both.

  Close it, or run the suite elsewhere:

      DASHBOARD_PORT=7601 bash scripts/run-docker-tests.sh
EOF
    exit 1
fi

# Distributed scenario (D9-D11): two jmeter-server engines and a controller on a
# private network. Names double as hostnames, which JMeter reports per engine.
DIST_NET="ld-dist-$$"
DIST_ENGINES=("ld-engine-1-$$" "ld-engine-2-$$")
DIST_CONTROLLER="ld-controller-$$"
DIST_RESULTS="$DOCKER_RESULTS/distributed"

cleanup() {
    docker rm -f "$CONTAINER_NAME" "${DIST_ENGINES[@]}" "$DIST_CONTROLLER" >/dev/null 2>&1 || true
    docker network rm "$DIST_NET" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# --- Build plugin (host-side; the container only runs it) ---
build_plugin || exit 1
require_plugin_jar

# --- Build image ---
echo "[DOCKER] Building JMeter image $IMAGE_NAME ..."
if ! docker build ${JAVA_IMAGE:+--build-arg JAVA_IMAGE="$JAVA_IMAGE"} -t "$IMAGE_NAME" -f "$PROJECT_DIR/docker/Dockerfile.jmeter" "$PROJECT_DIR/docker/"; then
    echo "[DOCKER] Image build FAILED" >&2
    exit 1
fi

mkdir -p "$DOCKER_RESULTS"
rm -f "$DOCKER_RESULTS/results.jtl" "$DOCKER_RESULTS/jmeter.log"

# --- Run the smoke plan ---
# bind=0.0.0.0 is required: the default bind is localhost, which is unreachable
# through Docker's published port. The docker smoke plan leaves port/bind/
# open-browser element properties EMPTY so these -J flags actually govern.
#
# Precedence is NOT uniform, which is worth knowing before editing a plan:
#   port, interval, ...        saved element value wins over -J
#   bind, open.browser         -J wins over the saved element value
# The exceptions are deliberate — see LiveDashboardListener.getBindConfig() and
# shouldOpenBrowser(): both describe the machine, not the test, and the GUI
# writes both into every saved plan, so a headless box must be able to force
# -Jlive.dashboard.bind=0.0.0.0 and -Jlive.dashboard.open.browser=false.
# Consequence: automation never has to rewrite a .jmx for either.
echo "[DOCKER] Starting $SMOKE_PLAN in container $CONTAINER_NAME ..."
docker_run run -d --name "$CONTAINER_NAME" \
    -p "${DASHBOARD_PORT}:7566" \
    -v "$(docker_host_path "$PLUGIN_JAR"):$CONTAINER_EXT/$(basename "$PLUGIN_JAR")" \
    -v "$(docker_host_path "$TEST_PLANS/$SMOKE_PLAN"):/test/$SMOKE_PLAN" \
    -v "$(docker_host_path "$DOCKER_RESULTS"):/results" \
    "$IMAGE_NAME" \
    -n -t "/test/$SMOKE_PLAN" \
    -l /results/results.jtl \
    -j /results/jmeter.log \
    -Jlive.dashboard.bind=0.0.0.0 \
    -Jlive.dashboard.open.browser=false \
    -Jlive.dashboard.idle.timeout=10 \
    >/dev/null || { echo "[DOCKER] container start FAILED" >&2; exit 1; }

# --- D1: dashboard comes up ---
if wait_for_dashboard "$DASHBOARD_PORT" 45; then
    record D1 "Dashboard reachable through published port" PASS
else
    record D1 "Dashboard reachable through published port" FAIL "no /api/status answer in 45s"
    echo "--- container log tail ---"; docker logs --tail 30 "$CONTAINER_NAME" 2>&1 || true
    print_summary "DOCKER E2E SUMMARY"; exit $FAIL
fi

# --- D1b: the running jar names its build ---
# /api/status reads the version from the jar's manifest, which only a real jar has.
reported="$(status_field "$DASHBOARD_PORT" pluginVersion)"
if [[ "$reported" == "\"$PLUGIN_VERSION\"" ]]; then
    record D1b "/api/status pluginVersion is the pom's" PASS
else
    record D1b "/api/status pluginVersion is the pom's" FAIL "got ${reported:-nothing}, expected \"$PLUGIN_VERSION\""
fi

# --- D2: test is running and producing samples ---
sleep 5
running="$(status_field "$DASHBOARD_PORT" testRunning)"
samples="$(status_field "$DASHBOARD_PORT" totalSamples)"
if [[ "$running" == "true" ]]; then
    record D2a "testRunning true mid-run" PASS
else
    record D2a "testRunning true mid-run" FAIL "testRunning=$running"
fi
if [[ "${samples:-0}" =~ ^[0-9]+$ ]] && [[ "${samples:-0}" -gt 0 ]]; then
    record D2b "samples flowing (totalSamples=$samples)" PASS
else
    record D2b "samples flowing" FAIL "totalSamples=$samples"
fi

# --- D3: dashboard page serves ---
# Capture first, grep second: `curl | grep -q` under pipefail fails the
# pipeline when grep quits at first match and curl takes the SIGPIPE.
page="$(curl -sf "http://localhost:${DASHBOARD_PORT}/" || true)"
if grep -q "liveIndicator" <<< "$page"; then
    record D3 "dashboard HTML serves" PASS
else
    record D3 "dashboard HTML serves" FAIL
fi

# --- D4: SSE stream delivers catchup ---
# curl exits 28 when --max-time cuts the endless stream; that's expected.
sse="$(curl -sN --max-time 6 "http://localhost:${DASHBOARD_PORT}/api/events" 2>/dev/null | head -c 4096 || true)"
if grep -q "catchup" <<< "$sse"; then
    record D4 "SSE catchup event delivered" PASS
else
    record D4 "SSE catchup event delivered" FAIL
fi

# --- D12: the dashboard names the plan it runs ---
# The name used to come from a property JMeter never sets, so every run read
# "JMeter Load Test". Non-GUI JMeter knows the plan only as the script it
# loaded (FileServer), which no unit test can stand in for, so pin it here.
# The plan's name contains no XML entities, so the raw attribute is the name.
plan_name="$(grep -o 'testclass="TestPlan" testname="[^"]*"' "$PROJECT_DIR/test-plans/$SMOKE_PLAN" | sed 's/.*testname="//; s/"$//')"
dash_name="$(dashboard_status "$DASHBOARD_PORT" | sed -n 's/.*"testName":"\([^"]*\)".*/\1/p')"
if [[ -n "$plan_name" && "$dash_name" == "$plan_name" ]]; then
    record D12 "dashboard shows the plan's name ($dash_name)" PASS
else
    record D12 "dashboard shows the plan's name" FAIL "dashboard='${dash_name:-unread}' plan='${plan_name:-unread}'"
fi

# --- D7: per-sampler aggregation ran under real JMeter ---
# Everything above proves the plumbing works; nothing yet proves the aggregation
# that is the actual product. D7 and D8 cover it, and they have to run HERE,
# before D5 waits for the container to exit and takes the server with it.
#
# docker-smoke-test.jmx produces exactly 15 labels: 6 JSR223 samplers in the
# first two thread groups; in the third, 6 more, 2 transactions and the Debug
# Sampler, which counts even though the dashboard hides it. The "Think Time" and
# "Pacing" elements are timers, not samplers. Poll rather than read once: the
# groups ramp over 5s, so knownSamplers is still climbing when the dashboard
# first answers, and a single read races it.
if wait_for_status_field "$DASHBOARD_PORT" knownSamplers 15 25; then
    record D7 "all 15 samplers registered (knownSamplers=$(status_field "$DASHBOARD_PORT" knownSamplers))" PASS
else
    record D7 "all 15 samplers registered" FAIL "knownSamplers=$(status_field "$DASHBOARD_PORT" knownSamplers)"
fi

# --- D8: percentiles computed from real samples ---
# Percentiles only ever ran against mock data before this. /api/history returns
# an ARRAY of bucket objects, so status_field's sed is not usable — it would
# match the first p95 in an arbitrary bucket.
#
# Buckets legitimately carry "p95": null when they hold no samples (see
# LiveAggregator.getStatsMap), so the assertion is "at least one bucket has a
# non-zero p95", never "no bucket is null". Requiring non-zero matters: a
# percentile calculation that silently failed would report 0, not an error.
history="$(curl -sf "http://localhost:${DASHBOARD_PORT}/api/history" || true)"
p95="$(grep -oE '"p95":[[:space:]]*[0-9]+(\.[0-9]+)?' <<< "$history" \
       | grep -vE ':[[:space:]]*0(\.0+)?$' | head -1)"
if [[ -n "$p95" ]]; then
    record D8 "non-zero percentile in /api/history ($p95)" PASS
else
    record D8 "non-zero percentile in /api/history" FAIL "no bucket reported a non-zero p95"
fi

# --- D13 (capture): the dashboard's final numbers ---
# Taken once the run has ended, while the server stays up for review (it exits
# on the idle timeout, which D5 then waits out). The catch-up carries the
# finished run's TOTAL and per-sampler statistics in full; they are compared
# with JMeter's own after D6, once results.jtl is complete. D14 reads its
# hidden list and hierarchy.
DASH_FINAL="$DOCKER_RESULTS/dashboard-final.json"
rm -f "$DASH_FINAL" "$DOCKER_RESULTS/jmeter-aggregate.json" "$DOCKER_RESULTS/jmeter-aggregate.err"
for _ in $(seq 1 90); do
    [[ "$(status_field "$DASHBOARD_PORT" testRunning)" == "false" ]] && break
    sleep 1
done
curl -sN --max-time 3 "http://localhost:${DASHBOARD_PORT}/api/events" 2>/dev/null \
    | awk '/^event: catchup/ { getline; sub(/^data: /, ""); print; exit }' > "$DASH_FINAL" || true

# --- D5: container exits cleanly after test end + idle timeout ---
waited=0
while [[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER_NAME" 2>/dev/null)" == "true" ]]; do
    sleep 2
    waited=$((waited + 2))
    if [[ $waited -ge $EXIT_DEADLINE_S ]]; then break; fi
done
state="$(docker inspect -f '{{.State.Running}} {{.State.ExitCode}}' "$CONTAINER_NAME" 2>/dev/null || echo "gone 0")"
if [[ "$state" == "false 0" ]]; then
    record D5 "clean container exit within ${EXIT_DEADLINE_S}s" PASS
else
    record D5 "clean container exit within ${EXIT_DEADLINE_S}s" FAIL "state=$state"
fi

# --- D6: results written ---
if file_size_gt "$DOCKER_RESULTS/results.jtl" 100; then
    record D6 "results.jtl written ($(stat_size "$DOCKER_RESULTS/results.jtl") bytes)" PASS
else
    record D6 "results.jtl written" FAIL
fi

# --- D13: the dashboard's numbers equal JMeter's own ---
# The plugin is a reporter, and JMeter's Aggregate Report is the reference for
# every number it shows. Until this check, the gate compared one number with
# JMeter (D10's total count) and every wrong-number bug was found by hand.
# JMeter's own calculator (SamplingStatCalculator, behind the Aggregate and
# Summary Reports) runs over this run's results.jtl inside the image, so the
# host needs nothing new; scripts/lib/dashboard-vs-jmeter.groovy then compares
# TOTAL and every sampler, field by field (exact where JMeter's value is exact).
# SMOKE_CHILDREN lets it rebuild JMeter's live view, which TOTAL is held to.
JM_HOME=/opt/apache-jmeter-5.6.3
in_image() {
    docker_run run --rm --entrypoint java \
        -v "$(docker_host_path "$PROJECT_DIR/scripts"):/scripts:ro" \
        -v "$(docker_host_path "$DOCKER_RESULTS"):/results" \
        "$IMAGE_NAME" -cp "$JM_HOME/lib/*:$JM_HOME/lib/ext/*" groovy.ui.GroovyMain "$@"
}
if [[ ! -s "$DASH_FINAL" ]]; then
    record D13 "dashboard numbers = JMeter's Aggregate Report" FAIL "no final catch-up captured"
elif ! in_image /scripts/perf/jmeter-aggregate.groovy /results/results.jtl "$SMOKE_CHILDREN" \
        > "$DOCKER_RESULTS/jmeter-aggregate.json" 2> "$DOCKER_RESULTS/jmeter-aggregate.err"; then
    record D13 "dashboard numbers = JMeter's Aggregate Report" FAIL "JMeter's calculator failed (see jmeter-aggregate.err)"
else
    d13="$(in_image /scripts/lib/dashboard-vs-jmeter.groovy \
        /results/dashboard-final.json /results/jmeter-aggregate.json 2>&1)"
    d13_rc=$?
    echo "$d13" | sed 's/^/  /'
    d13_scope="$(sed -n 's/^D13: compared \([0-9]*\) values in \([0-9]*\) rows.*/\1 values, \2 rows/p' <<< "$d13")"
    if [[ $d13_rc -eq 0 ]]; then
        record D13 "dashboard numbers = JMeter's Aggregate Report (${d13_scope})" PASS
    else
        # The first wrong value, or the reason the comparison could not run
        d13_first="$(grep -m1 ' :: ' <<< "$d13" | sed 's/^ *//')"
        record D13 "dashboard numbers = JMeter's Aggregate Report" FAIL "${d13_first:-$(tail -1 <<< "$d13")}"
    fi
fi

# --- D14: the hidden list and the transaction hierarchy from a real run ---
# Both are built from what real JMeter delivers: the Debug Sampler matches a
# default hide pattern, and the Checkout Transaction's steps arrive as its
# sub-results. The unit tests feed hand-made results; only a real run shows
# JMeter's own results produce them. The Browse Transaction generates no parent
# sample, so its steps arrive on their own and must not appear. The catch-up is
# one line of JSON and no label here holds a ] or }, so a match is enough.
hidden="$(grep -o '"hiddenSamplers":\[[^]]*\]' "$DASH_FINAL" 2>/dev/null)"
hierarchy="$(grep -o '"hierarchy":{[^}]*}' "$DASH_FINAL" 2>/dev/null)"
pairs="$(grep -o '"[^"]*":"[^"]*"' <<< "${hierarchy#\"hierarchy\":}" | wc -l)"
if [[ "$hidden" == '"hiddenSamplers":["Debug Sampler"]' && $((pairs)) -eq 2 ]] \
        && grep -qF '"TX step 1 - add to cart":"Checkout Transaction"' <<< "$hierarchy" \
        && grep -qF '"TX step 2 - pay":"Checkout Transaction"' <<< "$hierarchy"; then
    record D14 "Debug Sampler hidden; both TX steps under Checkout Transaction" PASS
else
    record D14 "Debug Sampler hidden; both TX steps under Checkout Transaction" FAIL \
        "${hidden:-no hiddenSamplers} ${hierarchy:-no hierarchy}"
fi

# --- D15: the Timeline's grouped points = JMeter's numbers per clock period ---
# At 6s/10s/30s/1m the Timeline plots one point per clock period [kG, (k+1)G), which is how
# JMeter's own over-time graphs define a point. JMeter's calculator computes every period
# from this run's JTL inside the image (jmeter-buckets.groovy); chart-vs-jmeter.mjs loads the
# real index.html with the run's final catch-up and compares every plotted point: Mean, Max,
# throughput and error % for each sampler and the total, and P95 at 1m. Points used to be
# groups counted from the window's start, which match a JMeter period only by chance.
# Needs Node and the Playwright install in tests/playwright, as the WEB layer does.
d15_iv="$(grep -o '"interval":[0-9]*' "$DASH_FINAL" 2>/dev/null | head -1 | cut -d: -f2)"
d15_spans="$(for g in 3 5 15 30; do printf '%s,' $(( g * ${d15_iv:-2000} )); done)"
if [[ ! -s "$DASH_FINAL" ]]; then
    record D15 "Timeline points = JMeter's per clock period" FAIL "no final catch-up captured"
elif ! in_image /scripts/lib/jmeter-buckets.groovy /results/results.jtl "$SMOKE_CHILDREN" "${d15_spans%,}" \
        > "$DOCKER_RESULTS/jmeter-buckets.json" 2> "$DOCKER_RESULTS/jmeter-buckets.err"; then
    record D15 "Timeline points = JMeter's per clock period" FAIL "JMeter's calculator failed (see jmeter-buckets.err)"
else
    d15="$(node "$PROJECT_DIR/scripts/lib/chart-vs-jmeter.mjs" "$DASH_FINAL" \
        "$DOCKER_RESULTS/jmeter-buckets.json" "$SMOKE_CHILDREN" 2>&1)"
    d15_rc=$?
    echo "$d15" | sed 's/^/  /'
    d15_scope="$(sed -n 's/^D15: compared \([0-9]*\) values in \([0-9]*\) chart points.*/\1 values, \2 points/p' <<< "$d15")"
    if [[ $d15_rc -eq 0 ]]; then
        record D15 "Timeline points = JMeter's per clock period (${d15_scope})" PASS
    else
        record D15 "Timeline points = JMeter's per clock period" FAIL \
            "$(grep -m1 -E 'differ|D15:' <<< "$d15")"
    fi
fi

# =============================================================================
#  Distributed run: 2 engines + controller (D9-D11)
#
#  The dashboard lives on the controller, where JMeter hands EACH engine its own
#  copy of the listener. Before this was checked against real JMeter, the code
#  assumed one copy served every engine: the dashboard counted one engine's
#  samples (306 of 613) and one engine's threads, and looked perfectly healthy.
#  Only a real -R run can pin that, so it lives here, not in the unit tests.
#
#  Runs after D5, so the local container has exited and DASHBOARD_PORT is free.
#  Default sample sender mode on purpose: it is what users get. The engines need
#  no plugin jar — the controller replaces the listener before shipping the plan.
# =============================================================================
echo ""
echo "[DOCKER] Distributed run: ${DIST_ENGINES[*]} driven by $DIST_CONTROLLER ..."
mkdir -p "$DIST_RESULTS"
rm -f "$DIST_RESULTS/results.jtl" "$DIST_RESULTS/jmeter.log"
docker network create "$DIST_NET" >/dev/null || { echo "[DOCKER] network create FAILED" >&2; exit 1; }

dist_ok=1
for engine in "${DIST_ENGINES[@]}"; do
    # java.rmi.server.hostname: the controller must reach the engine's remote
    # object by a name that resolves on this network, not the container's IP
    # as the engine sees it.
    docker_run run -d --name "$engine" --hostname "$engine" --network "$DIST_NET" \
        -e JVM_ARGS="-Djava.rmi.server.hostname=$engine" \
        "$IMAGE_NAME" -s -Jserver.rmi.ssl.disable=true -Jserver.rmi.localport=50000 \
        >/dev/null || { echo "[DOCKER] engine $engine start FAILED" >&2; dist_ok=0; }
done
for engine in "${DIST_ENGINES[@]}"; do
    waited=0
    until docker logs "$engine" 2>&1 | grep -q "Created remote object"; do
        sleep 1; waited=$((waited + 1))
        if [[ $waited -ge 45 ]]; then
            echo "[DOCKER] engine $engine not ready in 45s" >&2
            docker logs --tail 20 "$engine" 2>&1 || true
            dist_ok=0; break
        fi
    done
done

if [[ $dist_ok -eq 1 ]]; then
    docker_run run -d --name "$DIST_CONTROLLER" --hostname "$DIST_CONTROLLER" --network "$DIST_NET" \
        -p "${DASHBOARD_PORT}:7566" \
        -e JVM_ARGS="-Djava.rmi.server.hostname=$DIST_CONTROLLER" \
        -v "$(docker_host_path "$PLUGIN_JAR"):$CONTAINER_EXT/$(basename "$PLUGIN_JAR")" \
        -v "$(docker_host_path "$TEST_PLANS/$SMOKE_PLAN"):/test/$SMOKE_PLAN" \
        -v "$(docker_host_path "$DIST_RESULTS"):/results" \
        "$IMAGE_NAME" \
        -n -t "/test/$SMOKE_PLAN" \
        -R "$(IFS=,; echo "${DIST_ENGINES[*]}")" \
        -Jserver.rmi.ssl.disable=true \
        -l /results/results.jtl \
        -j /results/jmeter.log \
        -Jlive.dashboard.bind=0.0.0.0 \
        -Jlive.dashboard.open.browser=false \
        -Jlive.dashboard.idle.timeout=15 \
        >/dev/null || { echo "[DOCKER] controller start FAILED" >&2; dist_ok=0; }
fi

# The dashboard's final numbers must be read in the window between the last
# engine finishing (testRunning=false) and the idle shutdown 15s later. Polling
# at 1s leaves ample margin; a missed window reads as empty and fails loudly.
dash_total=""; dash_threads=""
if [[ $dist_ok -eq 1 ]] && wait_for_dashboard "$DASHBOARD_PORT" 60; then
    for _ in $(seq 1 $EXIT_DEADLINE_S); do
        [[ "$(status_field "$DASHBOARD_PORT" testRunning)" == "false" ]] && break
        sleep 1
    done
    dash_total="$(status_field "$DASHBOARD_PORT" totalSamples)"
    dash_threads="$(status_field "$DASHBOARD_PORT" maxThreads)"
else
    echo "--- controller log tail ---"; docker logs --tail 30 "$DIST_CONTROLLER" 2>&1 || true
fi

# The JTL is complete only once the controller has exited.
waited=0
while [[ "$(docker inspect -f '{{.State.Running}}' "$DIST_CONTROLLER" 2>/dev/null)" == "true" ]]; do
    sleep 2; waited=$((waited + 2))
    [[ $waited -ge $EXIT_DEADLINE_S ]] && break
done

# Remote thread names carry the engine's hostname as a prefix, which is how a
# JTL row is attributed to its engine. The total counts top-level rows only:
# transaction steps are rows of their own in the JTL, but JMeter's live reports
# and the dashboard count each transaction once. The steps' labels hold no
# comma, so field 3 is their label.
jtl="$DIST_RESULTS/results.jtl"
jtl_total=0; per_engine=()
if [[ -f "$jtl" ]]; then
    jtl_total="$(awk -F, -v child="$SMOKE_CHILDREN" 'NR > 1 && $3 !~ child { n++ } END { print n + 0 }' "$jtl")"
    for engine in "${DIST_ENGINES[@]}"; do
        per_engine+=("$(grep -c ",$engine-" "$jtl")")
    done
fi

# --- D9: both engines really ran ---
# Without this, D10 would pass trivially for a run where one engine never
# joined: the dashboard and the JTL would agree on one engine's samples.
if [[ ${#per_engine[@]} -eq 2 && "${per_engine[0]}" -gt 0 && "${per_engine[1]}" -gt 0 ]]; then
    record D9 "both engines produced samples (${per_engine[0]} + ${per_engine[1]})" PASS
else
    record D9 "both engines produced samples" FAIL "per-engine JTL rows: ${per_engine[*]:-none}"
fi

# --- D10: the dashboard counted every engine's samples ---
if [[ "$dash_total" =~ ^[0-9]+$ && "$dash_total" -gt 0 && "$dash_total" -eq "$jtl_total" ]]; then
    record D10 "dashboard total = JTL top-level rows across engines ($dash_total)" PASS
else
    record D10 "dashboard total = JTL top-level rows across engines" FAIL "dashboard=${dash_total:-unread} jtl=$jtl_total"
fi

# --- D11: the thread gauge sums the engines ---
# docker-smoke-test.jmx runs 5 + 5 + 2 threads, so two engines peak at 24. One
# engine's share (12) is exactly what the pre-fix gauge reported.
if [[ "$dash_threads" == "24" ]]; then
    record D11 "maxThreads sums both engines (24)" PASS
else
    record D11 "maxThreads sums both engines (24)" FAIL "maxThreads=${dash_threads:-unread}"
fi

print_summary "DOCKER E2E SUMMARY"
echo ""
echo "  Results: $DOCKER_RESULTS"

exit $FAIL
