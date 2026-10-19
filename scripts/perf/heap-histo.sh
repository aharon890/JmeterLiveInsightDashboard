#!/usr/bin/env bash
# =============================================================================
#  Live-set sampler for long perf-harness runs.
#
#    scripts/perf/heap-histo.sh [interval-s]      (default 1800)
#
#  Every interval, for each running ld-perf-* container, takes a class
#  histogram of live objects (jcmd GC.class_histogram) into
#  <run-dir>/histo/<host-epoch-s>.txt and appends one line to
#  <run-dir>/livesets.txt: host epoch s, total live bytes, plugin shallow
#  bytes, FrozenBucket count.
#
#  Why: "heap after mixed GCs" only measures the live set when G1 marks, and
#  at 30-100 samplers the old gen stays below even a 10% marking threshold
#  (B180-small: 0 mixed GCs in 2 h), so after-young-GC keeps uncollected
#  promoted garbage and looks like a leak. A histogram forces one full GC
#  (~20 ms at these heaps; gc.log shows it as "Heap Inspection Initiated GC")
#  and counts exactly what is reachable.
#
#  The image is a JRE without jcmd, so a JDK sidecar shares the container's
#  PID namespace and attaches from there (JVM 17 target, jcmd 21: the attach
#  protocol is compatible). Exits once queue.log ends with QUEUE DONE after
#  this sampler started.
# =============================================================================
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
perf="$here/../../test-results/perf"
interval="${1:-1800}"
JDK_IMAGE="${JDK_IMAGE:-eclipse-temurin:21-jdk}"
started=$(date +%s)
declare -A last

while :; do
    for c in $(docker ps --format '{{.Names}}' | grep '^ld-perf-'); do
        now=$(date +%s)
        [[ $(( now - ${last[$c]:-0} )) -lt $interval ]] && continue
        name="${c#ld-perf-}"; name="${name%-*}"
        run="$(ls -d "$perf/$name"-*/ 2>/dev/null | tail -1)"
        [[ -n "$run" ]] || continue
        pid="$(docker exec "$c" sh -c "ps -eo pid,args | grep '[A]pacheJMeter.jar' | awk '{print \$1}' | head -1")"
        [[ -n "$pid" ]] || continue
        mkdir -p "$run/histo"
        f="$run/histo/$now.txt"
        MSYS_NO_PATHCONV=1 docker run --rm --pid="container:$c" "$JDK_IMAGE" \
            jcmd "$pid" GC.class_histogram > "$f" 2>&1
        last[$c]=$now
        total="$(awk '/^Total/ {print $3}' "$f")"
        [[ -n "$total" ]] || continue
        plugin="$(awk '/jmeterinsight/ {s += $3} END {print s + 0}' "$f")"
        frozen="$(awk '/engine\.FrozenBucket$/ {print $2}' "$f")"
        echo "$now $total $plugin ${frozen:-0}" >> "$run/livesets.txt"
    done
    if [[ -f "$perf/queue.log" && $(stat -c %Y "$perf/queue.log") -gt $started ]] \
        && tail -1 "$perf/queue.log" | grep -q 'QUEUE DONE'; then
        exit 0
    fi
    sleep 60
done
