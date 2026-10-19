#!/usr/bin/env bash
# Run perf-harness scenarios back to back; one failing scenario does not stop
# the queue. Progress goes to stdout and test-results/perf/queue.log.
#
#   scripts/perf/run-all.sh B10 A1 A1-noplugin ...
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
log="$here/../../test-results/perf/queue.log"
mkdir -p "$(dirname "$log")"
for s in "$@"; do
    echo "$(date '+%F %T') START $s" | tee -a "$log"
    bash "$here/run-scenario.sh" "$s" > "$here/../../test-results/perf/last-$s.out" 2>&1
    rc=$?
    echo "$(date '+%F %T') END   $s rc=$rc $(grep -o 'test-results/perf/[^ ]*report.md' "$here/../../test-results/perf/last-$s.out" | tail -1)" | tee -a "$log"
done
echo "$(date '+%F %T') QUEUE DONE" | tee -a "$log"
