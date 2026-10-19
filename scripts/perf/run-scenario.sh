#!/usr/bin/env bash
# =============================================================================
#  Perf harness: one load/duration scenario against the real jar.
#
#    scripts/perf/run-scenario.sh <scenario>      scenarios are listed below
#    scripts/perf/run-scenario.sh list
#
#  Runs test-plans/perf/perf-harness.jmx in Docker with JMeter's default heap
#  and records, side by side:
#    - the JTL (every sample: the ground truth),
#    - the dashboard's SSE stream as a client receives it (sse-probe.mjs),
#    - a real Chromium tab on the dashboard (browser-watch.mjs),
#    - JMeter's GC log and container CPU/memory,
#  then derives JMeter's own numbers from the JTL (its Aggregate Report
#  calculator and its HTML report) and compares (compare.py).
#
#  ON DEMAND ONLY. Not part of smoke/regression: a scenario takes 10-60 min.
#  Results land in test-results/perf/<scenario>-<timestamp>/.
# =============================================================================
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"

IMAGE_NAME="${IMAGE_NAME:-jmeter-livedashboard-test:5.6.3}"
JM_HOME="/opt/apache-jmeter-5.6.3"
PERF_PORT="${PERF_PORT:-7590}"
PERF_DIR="$PROJECT_DIR/scripts/perf"
PLAN_DIR="$PROJECT_DIR/test-plans/perf"
LINGER_S="${LINGER_S:-120}"   # how long probe + browser stay after the end

# -----------------------------------------------------------------------------
# Scenarios. Each sets: DURATION (s), JARGS (plan -J knobs), PLUGIN (1/0),
# BROWSER (1/0), RELOAD_AT (s into the run; 0 = never), and a one-line WHAT.
#
# Pacing needs threads >= rate x ~0.3s at these response times, or threads
# fall behind and the achieved rate drops below the target.
# -----------------------------------------------------------------------------
scenario() {
    PLUGIN=1; BROWSER=1; RELOAD_AT=0; HTML=1; JVM_EXTRA=""
    local steady="-Jlabels=30 -Jrt_base_ms=50 -Jerror_pct=2 -Jerror_kinds=4 -Jtx_threads=5"
    case "$1" in
        B10)  WHAT="steady profile, 10 min";                  DURATION=600
              JARGS="-Jthreads=150 -Jramp=30 -Jrate=500 $steady"; RELOAD_AT=300 ;;
        B60)  WHAT="steady profile, 1 h";                     DURATION=3600
              JARGS="-Jthreads=150 -Jramp=30 -Jrate=500 $steady"; RELOAD_AT=2400 ;;
        A1)   WHAT="rate steps 2k/5k/10k/20k per s, 150 s each"; DURATION=600
              JARGS="-Jthreads=1000 -Jramp=20 -Jrate_steps=2000,5000,10000,20000 -Jstep_s=150 -Jlabels=30 -Jrt_base_ms=10 -Jerror_pct=2" ;;
        A1-noplugin) WHAT="A1 without the plugin (overhead baseline)"; DURATION=600; PLUGIN=0; BROWSER=0
              JARGS="-Jthreads=1000 -Jramp=20 -Jrate_steps=2000,5000,10000,20000 -Jstep_s=150 -Jlabels=30 -Jrt_base_ms=10 -Jerror_pct=2" ;;
        A1-max) WHAT="unpaced, no response time: the CPU ceiling"; DURATION=180; HTML=0
              JARGS="-Jthreads=100 -Jramp=5 -Jrate=0 -Jlabels=30 -Jrt_base_ms=0 -Jerror_pct=2" ;;
        A1-max-noplugin) WHAT="A1-max without the plugin"; DURATION=180; PLUGIN=0; BROWSER=0; HTML=0
              JARGS="-Jthreads=100 -Jramp=5 -Jrate=0 -Jlabels=30 -Jrt_base_ms=0 -Jerror_pct=2" ;;
        A2-100)  WHAT="100 samplers at 2k/s";  DURATION=600
              JARGS="-Jthreads=600 -Jramp=20 -Jrate=2000 -Jlabels=100 -Jrt_base_ms=50 -Jerror_pct=2" ;;
        A2-500)  WHAT="500 samplers at 2k/s";  DURATION=600
              JARGS="-Jthreads=600 -Jramp=20 -Jrate=2000 -Jlabels=500 -Jrt_base_ms=50 -Jerror_pct=2" ;;
        A2-1000) WHAT="1000 samplers at 2k/s"; DURATION=600
              JARGS="-Jthreads=600 -Jramp=20 -Jrate=2000 -Jlabels=1000 -Jrt_base_ms=50 -Jerror_pct=2" ;;
        # A low marking threshold makes G1 run concurrent cycles and mixed GCs
        # all along, so the heap after them approximates the live set; with
        # the default 45% it never marks below ~460 MB and "after GC" keeps
        # promoted garbage. GC tuning only: heap size stays the default.
        A2-1000-70m) WHAT="1000 samplers at 2k/s for 70 min: past the 1 h window, reload at 68 min"; DURATION=4200; RELOAD_AT=4080
              JVM_EXTRA="-XX:InitiatingHeapOccupancyPercent=10"
              JARGS="-Jthreads=600 -Jramp=20 -Jrate=2000 -Jlabels=1000 -Jrt_base_ms=50 -Jerror_pct=2" ;;
        # Long runs: is the plugin still correct and bounded once the 1 h live
        # window has been full for hours? Same marking threshold as
        # A2-1000-70m, so the heap after mixed GCs is the live set. Reload 5 min
        # before the end: the catch-up then carries a full window plus the
        # minute tier's whole-run head.
        B180-small)  WHAT="steady profile, 30 samplers at 500/s, 3 h";   DURATION=10800; RELOAD_AT=10500
              JVM_EXTRA="-XX:InitiatingHeapOccupancyPercent=10"
              JARGS="-Jthreads=150 -Jramp=30 -Jrate=500 $steady" ;;
        B180-medium) WHAT="steady profile, 100 samplers at 1k/s, 3 h";   DURATION=10800; RELOAD_AT=10500
              JVM_EXTRA="-XX:InitiatingHeapOccupancyPercent=10"
              JARGS="-Jthreads=300 -Jramp=30 -Jrate=1000 ${steady/-Jlabels=30/-Jlabels=100}" ;;
        B720-small)  WHAT="steady profile, 30 samplers at 500/s, 12 h";  DURATION=43200; RELOAD_AT=42900
              JVM_EXTRA="-XX:InitiatingHeapOccupancyPercent=10"
              JARGS="-Jthreads=150 -Jramp=30 -Jrate=500 $steady" ;;
        B720-medium) WHAT="steady profile, 100 samplers at 1k/s, 12 h";  DURATION=43200; RELOAD_AT=42900
              JVM_EXTRA="-XX:InitiatingHeapOccupancyPercent=10"
              JARGS="-Jthreads=300 -Jramp=30 -Jrate=1000 ${steady/-Jlabels=30/-Jlabels=100}" ;;
        A6)   WHAT="1000 samplers, response times uniform over 0-60 s, at 2k/s"; DURATION=600
              JARGS="-Jthreads=100 -Jramp=10 -Jrate=2000 -Jlabels=1000 -Jrt_fake=1 -Jrt_uniform_max_ms=60000 -Jerror_pct=2" ;;
        A3)   WHAT="2000 threads, ~0.5-1 s responses, unpaced"; DURATION=600
              JARGS="-Jthreads=2000 -Jramp=60 -Jrate=0 -Jlabels=30 -Jrt_base_ms=500 -Jerror_pct=2" ;;
        A3-noplugin) WHAT="A3 without the plugin"; DURATION=600; PLUGIN=0; BROWSER=0
              JARGS="-Jthreads=2000 -Jramp=60 -Jrate=0 -Jlabels=30 -Jrt_base_ms=500 -Jerror_pct=2" ;;
        A4)   WHAT="50% errors across 40 codes/messages at 2k/s"; DURATION=600
              JARGS="-Jthreads=600 -Jramp=20 -Jrate=2000 -Jlabels=30 -Jrt_base_ms=50 -Jerror_pct=50 -Jerror_kinds=40" ;;
        A5)   WHAT="bursts: 30 s at 5k/s, 30 s idle"; DURATION=600
              JARGS="-Jthreads=1500 -Jramp=10 -Jrate=5000 -Jburst_period_ms=30000 -Jlabels=30 -Jrt_base_ms=50 -Jerror_pct=2" ;;
        smoke) WHAT="harness self-check, 90 s"; DURATION=90; RELOAD_AT=45
              JARGS="-Jthreads=60 -Jramp=5 -Jrate=200 $steady" ;;
        *) return 1 ;;
    esac
}

ALL="smoke B10 B60 B180-small B180-medium B720-small B720-medium A1 A1-noplugin A1-max A1-max-noplugin A2-100 A2-500 A2-1000 A2-1000-70m A3 A3-noplugin A4 A5 A6"
if [[ "${1:-}" == "list" || -z "${1:-}" ]]; then
    for s in $ALL; do scenario "$s"; printf '  %-16s %5ss  %s\n' "$s" "$DURATION" "$WHAT"; done
    exit 0
fi
NAME="$1"
scenario "$NAME" || { echo "unknown scenario: $NAME (try: list)" >&2; exit 2; }

OUT="$RESULTS_DIR/perf/$NAME-$(file_timestamp)"
mkdir -p "$OUT"
CONTAINER="ld-perf-$NAME-$$"
echo "[PERF] $NAME: $WHAT -> $OUT"

# --- jar: rebuild only if src/main is newer than it -------------------------
newest="$(newest_src_file)"
if [[ ! -f "$PLUGIN_JAR" || ( -n "$newest" && "$PLUGIN_JAR" -ot "$newest" ) ]]; then
    echo "[PERF] building the plugin jar"
    (cd "$PROJECT_DIR" && mvn -q clean package -DskipTests) || exit 1
fi

# --- plan --------------------------------------------------------------------
# The Groovy files are inlined into the run's copy of the plan. Referenced by
# filename, JSR223 stats the file on every sample to check its cache, and on a
# Docker bind mount from Windows that alone cost ~8 ms per sample — the
# harness, not JMeter or the plugin, set the throughput ceiling.
# The no-plugin variant is the same plan minus the listener.
python - "$PLAN_DIR" "$OUT/plan.jmx" "$PLUGIN" <<'PY'
import os, re, sys
from xml.sax.saxutils import escape
plan_dir, dest, plugin = sys.argv[1], sys.argv[2], sys.argv[3] == '1'
src = open(os.path.join(plan_dir, 'perf-harness.jmx'), encoding='utf-8').read()

def inline(m):
    body = open(os.path.join(plan_dir, m.group(1)), encoding='utf-8').read()
    return ('<stringProp name="filename"></stringProp>' + m.group(2)
            + '<stringProp name="script">' + escape(body, {'"': '&quot;'}) + '</stringProp>')

src, n = re.subn(r'<stringProp name="filename">\$\{__P\(perf\.dir,[^}]*\}/([\w.-]+\.groovy)</stringProp>'
                 r'(\s*<stringProp name="parameters">[^<]*</stringProp>\s*)<stringProp name="script"></stringProp>',
                 inline, src)
assert n == 5, f'expected 5 JSR223 elements to inline, found {n}'
if not plugin:
    src, n = re.subn(r'\s*<com\.jmeterinsight\.livedashboard\.plugin\.LiveDashboardListener\b.*?'
                     r'</com\.jmeterinsight\.livedashboard\.plugin\.LiveDashboardListener>\s*<hashTree/>',
                     '', src, flags=re.S)
    assert n == 1, 'listener block not found'
open(dest, 'w', encoding='utf-8').write(src)
PY
[[ $? -eq 0 ]] || { echo "[PERF] plan preparation FAILED" >&2; exit 1; }

if [[ $PLUGIN -eq 1 ]] && dashboard_status "$PERF_PORT" >/dev/null 2>&1; then
    echo "ERROR: something already serves a dashboard on port $PERF_PORT (PERF_PORT=... to move)" >&2
    exit 1
fi

cat > "$OUT/scenario.json" <<EOF
{"name":"$NAME","what":"$WHAT","duration":$DURATION,"plugin":$PLUGIN,"browser":$BROWSER,
 "reloadAt":$RELOAD_AT,"linger":$LINGER_S,"jargs":"$JARGS","started":"$(iso_timestamp)",
 "jar":"$(basename "$PLUGIN_JAR")","gitSha":"$(git -C "$PROJECT_DIR" rev-parse --short HEAD)"}
EOF

# --- run ---------------------------------------------------------------------
mounts=(-v "$(docker_host_path "$PLAN_DIR"):/test/perf:ro" -v "$(docker_host_path "$OUT"):/results")
[[ $PLUGIN -eq 1 ]] && mounts+=(-v "$(docker_host_path "$PLUGIN_JAR"):$JM_HOME/lib/ext/$(basename "$PLUGIN_JAR")")

# shellcheck disable=SC2086
docker_run run -d --name "$CONTAINER" -p "$PERF_PORT:7566" "${mounts[@]}" \
    -e JVM_ARGS="-Xlog:gc:file=/results/gc.log:uptimemillis,time $JVM_EXTRA" \
    "$IMAGE_NAME" -n -t /results/plan.jmx -l /results/results.jtl -j /results/jmeter.log \
    -Jperf.dir=/test/perf -Jduration="$DURATION" $JARGS \
    -Jlive.dashboard.bind=0.0.0.0 -Jlive.dashboard.open.browser=false \
    -Jlive.dashboard.idle.timeout=20 >/dev/null || { echo "[PERF] container start FAILED" >&2; exit 1; }

pids=()
if [[ $PLUGIN -eq 1 ]]; then
    node "$PERF_DIR/sse-probe.mjs" "http://localhost:$PERF_PORT" "$OUT" "$LINGER_S" 60 \
        >"$OUT/probe.out" 2>&1 & pids+=($!)
    if [[ $BROWSER -eq 1 ]]; then
        node "$PERF_DIR/browser-watch.mjs" "http://localhost:$PERF_PORT/" "$OUT" 30 \
            "$(( DURATION >= 3600 ? 600 : 120 ))" "$LINGER_S" "$RELOAD_AT" \
            >"$OUT/browser.out" 2>&1 & pids+=($!)
    fi
fi

# Container CPU/memory every 10 s, host clock.
(
    while [[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" == "true" ]]; do
        s="$(docker stats --no-stream --format '{{.CPUPerc}}|{{.MemUsage}}' "$CONTAINER" 2>/dev/null)"
        [[ -n "$s" ]] && echo "$(date +%s%3N)|$s" >> "$OUT/stats.txt"
        sleep 10
    done
) & stats_pid=$!

# A JMeter that has hit OutOfMemoryError never recovers: it thrashes in full GCs
# with dying threads until the deadline. Two minutes of that is evidence enough.
deadline=$(( $(date +%s) + DURATION + LINGER_S + 900 ))
oom_at=0
while [[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" == "true" ]]; do
    if [[ $(date +%s) -ge $deadline ]]; then
        echo "[PERF] deadline passed, stopping the container" >&2
        docker stop -t 10 "$CONTAINER" >/dev/null
        break
    fi
    if [[ $oom_at -eq 0 ]] && grep -q OutOfMemoryError "$OUT/jmeter.log" 2>/dev/null; then
        oom_at=$(date +%s)
        echo "[PERF] JMeter hit OutOfMemoryError; stopping it in 120 s" >&2
    fi
    if [[ $oom_at -gt 0 && $(date +%s) -ge $(( oom_at + 120 )) ]]; then
        docker stop -t 10 "$CONTAINER" >/dev/null
        break
    fi
    sleep 5
done
docker logs "$CONTAINER" > "$OUT/container.log" 2>&1
echo "exit=$(docker inspect -f '{{.State.ExitCode}}' "$CONTAINER")" >> "$OUT/container.log"
docker rm -f "$CONTAINER" >/dev/null 2>&1
for p in "${pids[@]}"; do wait "$p"; done
wait "$stats_pid" 2>/dev/null
echo "[PERF] run finished; deriving JMeter's own numbers"

# --- JMeter's own numbers from the JTL --------------------------------------
docker_run run --rm --entrypoint java \
    -v "$(docker_host_path "$PERF_DIR"):/perf:ro" -v "$(docker_host_path "$OUT"):/results" \
    "$IMAGE_NAME" -cp "$JM_HOME/lib/*:$JM_HOME/lib/ext/*" groovy.ui.GroovyMain \
    /perf/jmeter-aggregate.groovy /results/results.jtl '^tx-step-' > "$OUT/jmeter-aggregate.json" \
    2> "$OUT/jmeter-aggregate.err" || echo "[PERF] aggregate calculator FAILED (see jmeter-aggregate.err)" >&2

# The HTML report twice: as users get it by default, and with percentiles
# over every sample instead of the default last-20,000 window. The exact
# variant keeps every value in memory, hence the larger heap; the CPU-ceiling
# runs write tens of millions of rows and skip it.
if [[ $HTML -eq 1 ]]; then
    for variant in default exact; do
        extra=()
        [[ $variant == exact ]] && extra=(-Jjmeter.reportgenerator.statistic_window=-1)
        docker_run run --rm -e HEAP="-Xms1g -Xmx12g" -v "$(docker_host_path "$OUT"):/results" "$IMAGE_NAME" \
            -g /results/results.jtl -o "/results/html-report-$variant" -j "/results/report-$variant.log" \
            "${extra[@]}" > /dev/null 2>&1 || echo "[PERF] HTML report ($variant) FAILED" >&2
    done
fi

python "$PERF_DIR/compare.py" "$OUT"
