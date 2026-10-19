#!/usr/bin/env bash
# =============================================================================
#  Live Insight Dashboard — shared shell library
#
#  Sourced by every build/test script. Detects the environment rather than
#  hardcoding it, so the same code runs on macOS, Linux, WSL2 and Git Bash.
#
#  Override via environment:
#    JMETER_HOME      explicit JMeter install (else project-local, then PATH)
#    JAVA_HOME        passed through to Maven/JMeter when set
#    PLUGIN_VERSION   explicit version (else parsed from pom.xml)
#    RESULTS_DIR      test output root (default <project>/test-results)
#    DASHBOARD_PORT   dashboard port for E2E assertions (default 7566)
# =============================================================================

# ---- project layout ---------------------------------------------------------
# This file lives at <project>/scripts/lib/common.sh — everything else is
# relative to that, so no script ever needs an absolute path.
_common_lib_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$_common_lib_dir/../.." && pwd)"
TEST_PLANS="$PROJECT_DIR/test-plans"
RESULTS_DIR="${RESULTS_DIR:-$PROJECT_DIR/test-results}"

# Did the caller choose the port, or is this the default?
#
# Captured BEFORE the default is applied — afterwards the variable is always
# non-empty and the distinction is gone, which is why run-docker-tests.sh cannot
# just write ${DASHBOARD_PORT:-7599} for itself. The E2E uses this to pick a
# safer default without overriding an explicit request.
if [[ -n "${DASHBOARD_PORT:-}" ]]; then
    DASHBOARD_PORT_EXPLICIT=1
else
    DASHBOARD_PORT_EXPLICIT=0
fi
DASHBOARD_PORT="${DASHBOARD_PORT:-7566}"

# ---- platform shims ---------------------------------------------------------
# BSD (macOS) and GNU (Linux) disagree on stat and md5. Resolve once, up front.
if stat -f%z /dev/null >/dev/null 2>&1; then
    stat_size() { [[ -f "$1" ]] || { echo 0; return 0; }; stat -f%z "$1"; }
else
    stat_size() { [[ -f "$1" ]] || { echo 0; return 0; }; stat -c%s "$1"; }
fi

if command -v md5sum >/dev/null 2>&1; then
    file_md5() { [[ -f "$1" ]] || { echo "none"; return 0; }; md5sum "$1" | cut -d' ' -f1; }
elif command -v md5 >/dev/null 2>&1; then
    file_md5() { [[ -f "$1" ]] || { echo "none"; return 0; }; md5 -q "$1"; }
else
    file_md5() { echo "no-md5-tool"; }
fi

# Docker on Git Bash / MSYS needs both halves of a -v handled differently.
#
# MSYS rewrites anything that looks like a POSIX path before the Windows
# docker.exe ever sees it, so a CONTAINER path like /test/plan.jmx arrives as
# C:/Program Files/Git/test/plan.jmx and JMeter cannot open it. Turning the
# rewriting off (MSYS_NO_PATHCONV=1) fixes the container side but then the HOST
# side stays /d/project, which Docker Desktop does not understand.
#
# So: disable the rewriting FOR THE DOCKER PROCESS ONLY and convert host paths
# explicitly. Exporting MSYS_NO_PATHCONV globally is not an option — Maven's
# launcher needs the conversion for its own classworlds arguments and dies with
# ClassNotFoundException without it. Hence a wrapper, not an export.
#
# docker_run()       — use for any docker command carrying container-side paths.
# docker_host_path() — convert a host path for -v; no-op off MSYS.
if [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* || "$(uname -s)" == CYGWIN* ]]; then
    # -m yields D:/path (forward slashes): unambiguous for docker's -v parser,
    # unlike -w's D:\path where the backslashes and drive colon invite trouble.
    docker_host_path() { cygpath -m "$1"; }
    docker_run() { MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' docker "$@"; }
else
    docker_host_path() { printf '%s\n' "$1"; }
    docker_run() { docker "$@"; }
fi

# macOS has no `readlink -f`; walk the symlink chain by hand.
resolve_link() {
    local p="$1" target
    while [[ -L "$p" ]]; do
        target="$(readlink "$p")"
        if [[ "$target" == /* ]]; then
            p="$target"
        else
            p="$(cd "$(dirname "$p")" && cd "$(dirname "$target")" && pwd)/$(basename "$target")"
        fi
    done
    printf '%s\n' "$p"
}

# Open a file/URL in the platform's default handler; prints the path if no opener.
open_path() {
    if command -v open >/dev/null 2>&1; then open "$1"
    elif command -v xdg-open >/dev/null 2>&1; then xdg-open "$1"
    elif command -v cmd.exe >/dev/null 2>&1; then cmd.exe /c start "" "$1"
    else echo "Open manually: $1"
    fi
}

iso_timestamp() { date +%Y-%m-%dT%H:%M:%S; }
file_timestamp() { date +%Y%m%d_%H%M; }

# ---- Java discovery ---------------------------------------------------------
# Homebrew's mvn (and a bare `java`) default to the newest JDK on the box,
# which can be ahead of what Mockito/ByteBuddy support (Java 26 broke the
# suite). Prefer the project's baseline (17), then 21. A pre-set JAVA_HOME
# always wins.
require_java() {
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
        export JAVA_HOME
        return 0
    fi
    local v home
    if [[ -x /usr/libexec/java_home ]]; then            # macOS
        for v in 17 21; do
            if home="$(/usr/libexec/java_home -v "$v" 2>/dev/null)"; then
                export JAVA_HOME="$home"
                return 0
            fi
        done
    fi
    for home in /usr/lib/jvm/java-17-* /usr/lib/jvm/temurin-17-* \
                /usr/lib/jvm/java-21-* /usr/lib/jvm/temurin-21-*; do   # Linux
        if [[ -x "$home/bin/java" ]]; then
            export JAVA_HOME="$home"
            return 0
        fi
    done
    echo "WARNING: no JDK 17/21 found; using the system default java." >&2
    return 0
}
require_java

# ---- version ----------------------------------------------------------------
# Read from the pom rather than hardcoding — hardcoded versions are how the old
# .bat scripts ended up needing a hand-edit on every version bump.
detect_plugin_version() {
    sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$PROJECT_DIR/pom.xml" | head -1
}
PLUGIN_VERSION="${PLUGIN_VERSION:-$(detect_plugin_version)}"

PLUGIN_JAR="$PROJECT_DIR/target/jmeter-live-dashboard-${PLUGIN_VERSION}.jar"

# ---- JMeter discovery -------------------------------------------------------
# A valid JMETER_HOME has both bin/jmeter and lib/ext. Homebrew's bin/jmeter is
# a wrapper that execs libexec/bin/jmeter, so probe <dir>/libexec too.
_is_jmeter_home() { [[ -x "$1/bin/jmeter" && -d "$1/lib/ext" ]]; }

_normalize_jmeter_home() {
    local candidate="$1"
    if _is_jmeter_home "$candidate"; then printf '%s\n' "$candidate"; return 0; fi
    if _is_jmeter_home "$candidate/libexec"; then printf '%s\n' "$candidate/libexec"; return 0; fi
    return 1
}

detect_jmeter_home() {
    local candidate resolved

    # 1. Explicit override.
    if [[ -n "${JMETER_HOME:-}" ]]; then
        if candidate="$(_normalize_jmeter_home "$JMETER_HOME")"; then
            printf '%s\n' "$candidate"; return 0
        fi
        echo "ERROR: JMETER_HOME is set to '$JMETER_HOME' but that isn't a JMeter install." >&2
        return 1
    fi

    # 2. Project-local install (the layout on the Windows box).
    for candidate in "$PROJECT_DIR"/apache-jmeter-*; do
        if resolved="$(_normalize_jmeter_home "$candidate")"; then
            printf '%s\n' "$resolved"; return 0
        fi
    done

    # 3. On PATH (Homebrew, apt, manual install).
    if command -v jmeter >/dev/null 2>&1; then
        resolved="$(resolve_link "$(command -v jmeter)")"
        candidate="$(cd "$(dirname "$resolved")/.." && pwd)"
        if resolved="$(_normalize_jmeter_home "$candidate")"; then
            printf '%s\n' "$resolved"; return 0
        fi
    fi

    return 1
}

require_jmeter() {
    if ! JMETER_HOME="$(detect_jmeter_home)"; then
        cat >&2 <<'EOF'
ERROR: No JMeter installation found.

Tried, in order:
  1. $JMETER_HOME
  2. <project>/apache-jmeter-*
  3. `jmeter` on PATH

Install one (macOS: `brew install jmeter`), set JMETER_HOME explicitly,
or use the Docker runners (scripts/run-docker-*.sh) which need no local JMeter.
EOF
        exit 1
    fi
    export JMETER_HOME
    JMETER="$JMETER_HOME/bin/jmeter"
    JMETER_EXT="$JMETER_HOME/lib/ext"
}

require_plugin_jar() {
    if [[ ! -f "$PLUGIN_JAR" ]]; then
        echo "ERROR: Plugin JAR not found: $PLUGIN_JAR" >&2
        echo "Run ./scripts/build.sh first." >&2
        exit 1
    fi
}

# ---- build & deploy ---------------------------------------------------------
# Every jar-producing path (build.sh, the Docker layer, the release) checks what it
# built, not only the smoke tier: regression and the merge gate never ran the check.
build_plugin() {
    echo "[BUILD] Building plugin ${PLUGIN_VERSION}..."
    if ! (cd "$PROJECT_DIR" && mvn clean package -DskipTests -q); then
        echo "[BUILD] FAILED" >&2
        return 1
    fi
    local problems
    if ! problems="$(assert_jar_sane)"; then
        echo "[BUILD] FAILED: the jar is not usable:" >&2
        echo "$problems" >&2
        return 1
    fi
    echo "[BUILD] SUCCESS"
}

# Only the shaded JAR is deployed — it is self-contained (everything else is
# provided by JMeter), so it is the single artifact JMeter needs.
#
# Older versions are purged first: JMeter loads *every* jar in lib/ext, so a
# leftover jmeter-live-dashboard-<older>.jar puts a second copy of the plugin's
# classes on the classpath and JMeter may bind the stale one. Version bumps
# would otherwise silently accumulate jars here.
deploy_plugin() {
    require_plugin_jar
    local stale
    for stale in "$JMETER_EXT"/jmeter-live-dashboard-*.jar; do
        [[ -f "$stale" ]] || continue                            # unmatched glob
        [[ "$(basename "$stale")" == "$(basename "$PLUGIN_JAR")" ]] && continue
        echo "[DEPLOY] Removing stale $(basename "$stale") ..."
        rm -f "$stale" || return 1
    done
    echo "[DEPLOY] Copying $(basename "$PLUGIN_JAR") to $JMETER_EXT ..."
    cp -f "$PLUGIN_JAR" "$JMETER_EXT/" || return 1
    echo "[DEPLOY] Done"
    warn_other_installs
}

# The staleness reference every "is this jar out of date" check shares: the
# newest file under src/main.
#
# Byte-comparing jars is useless here — mvn rebuilds on every deploy and jar
# entries carry their own timestamps, so two jars built from identical source
# never match. "Older than the source it was built from" is the condition that
# actually means an install is running out-of-date code.
#
# -printf is GNU find. On BSD find (macOS) this yields nothing, so callers must
# treat empty as "cannot tell" and stay silent rather than warn wrongly.
newest_src_file() {
    find "$PROJECT_DIR/src/main" -type f -printf '%T@ %p\n' 2>/dev/null \
        | sort -rn | head -1 | cut -d' ' -f2-
}

# Is the PRIMARY install's jar older than src/main?
#
# warn_other_installs deliberately skips the install we deploy to, because
# deploy_plugin has just refreshed it. That assumption holds for build.sh and
# breaks for the test tiers, which never deploy:
#
#     build.sh (deploys)  ->  edit src/main  ->  test-regression.sh (green)
#
# The Docker layer builds its own jar from the new source and passes, so the
# suite goes green while $JMETER_HOME/lib/ext still holds the pre-edit jar. A
# green run does not imply a current deployed jar. Getting this wrong looks like
# a dashboard that is broken for no visible reason while every test passes, so
# say it out loud at the end of a run rather than trusting anyone to remember.
warn_stale_deploy() {
    local ext="${JMETER_EXT:-}" newest_src jar home found=0
    newest_src="$(newest_src_file)"
    [[ -n "$newest_src" ]] || return 0
    # JMETER_EXT is only set once require_jmeter has run, and the tiers do not
    # need a local JMeter at all. Resolve opportunistically; stay quiet when
    # there is no install to check.
    if [[ -z "$ext" ]]; then
        home="$(detect_jmeter_home 2>/dev/null)" || return 0
        ext="$home/lib/ext"
    fi
    [[ -d "$ext" ]] || return 0
    for jar in "$ext"/jmeter-live-dashboard-*.jar; do
        [[ -f "$jar" ]] || continue
        [[ "$jar" -ot "$newest_src" ]] || continue
        if [[ $found -eq 0 ]]; then
            found=1
            echo ""
            echo "WARNING: the DEPLOYED jar is older than src/main:"
        fi
        echo "  $jar  ($(date -r "$jar" '+%Y-%m-%d %H:%M'))"
    done
    if [[ $found -eq 1 ]]; then
        echo "  newest source: $newest_src"
        echo "  The suite builds its own jar, so tests can be green while JMeter"
        echo "  here still runs the OLD code. Redeploy with:  ./scripts/build.sh"
    fi
}

# Deploying updates ONE JMeter install — whichever detect_jmeter_home picked.
# A second install elsewhere on the box keeps whatever plugin jar it was given,
# and running the GUI from there silently exercises old code: the symptom is a
# dashboard that looks broken (no charts, status ENDED) while the source tree
# and this build are perfectly fine. Point it out rather than let it hide.
warn_other_installs() {
    local dir ext other found=0
    # Three layouts are checked: the project's own install, a standalone one
    # beside the project (<parent>/apache-jmeter-*), and one nested inside
    # another project next to it (<parent>/<project>/apache-jmeter-*), so
    # the sibling glob has to descend one level too.
    local here seen=" " newest_src
    here="$(cd "$JMETER_EXT" && pwd)"
    newest_src="$(newest_src_file)"
    [[ -n "$newest_src" ]] || return 0
    for dir in "$PROJECT_DIR"/apache-jmeter-* \
               "$(dirname "$PROJECT_DIR")"/apache-jmeter-* \
               "$(dirname "$PROJECT_DIR")"/*/apache-jmeter-*; do
        ext="$dir/lib/ext"
        [[ -d "$ext" ]] || continue
        ext="$(cd "$ext" && pwd)"
        [[ "$ext" == "$here" ]] && continue
        # The globs overlap — <project>/apache-jmeter-* is also matched by the
        # sibling pattern — so the same install can be visited twice.
        case "$seen" in *" $ext "*) continue ;; esac
        seen="$seen$ext "
        for other in "$ext"/jmeter-live-dashboard-*.jar; do
            [[ -f "$other" ]] || continue
            # Newer than the newest source file => built from current code.
            # Only an install predating src/main is actually stale.
            [[ "$other" -ot "$newest_src" ]] || continue
            if [[ $found -eq 0 ]]; then
                found=1
                echo ""
                echo "WARNING: another JMeter install has a plugin jar OLDER than src/:"
            fi
            echo "  $other  ($(date -r "$other" '+%Y-%m-%d %H:%M'))"
        done
    done
    if [[ $found -eq 1 ]]; then
        echo "If you run JMeter from there, redeploy with:"
        echo "  JMETER_HOME=<that install> ./scripts/build.sh"
    fi
}

# ---- JMeter invocation ------------------------------------------------------
run_jmeter() { "$JMETER" "$@"; }

# ---- dashboard E2E helpers --------------------------------------------------
# The plugin's runtime output is a live HTTP server, not a report file, so E2E
# assertions poll the dashboard endpoints while a test runs.

# wait_for_dashboard [port] [timeout_s] — poll until /api/status answers.
wait_for_dashboard() {
    local port="${1:-$DASHBOARD_PORT}" timeout="${2:-30}" i=0
    until curl -sf "http://localhost:${port}/api/status" >/dev/null 2>&1; do
        i=$((i + 1))
        [[ $i -ge $timeout ]] && return 1
        sleep 1
    done
    return 0
}

dashboard_status() { curl -sf "http://localhost:${1:-$DASHBOARD_PORT}/api/status"; }

# status_field <port> <field> — extract a numeric/boolean field from /api/status.
#
# sed is enough because /api/status is FLAT (see LiveAggregator.getStatus). Do
# not reach for this on /api/history, which returns an array of bucket objects —
# the pattern would match the first occurrence in an arbitrary bucket.
status_field() {
    dashboard_status "$1" | sed -n "s/.*\"$2\":\([^,}]*\).*/\1/p"
}

# wait_for_status_field <port> <field> <min> [timeout_s] — poll /api/status until
# a numeric field reaches at least <min>.
#
# Reading once races anything thread-driven. The smoke plan ramps over 5s, so
# knownSamplers climbs from 0 to its final value well after the dashboard first
# answers /api/status; a single sample would assert against a half-started test.
wait_for_status_field() {
    local port="$1" field="$2" min="$3" timeout="${4:-30}" i=0 v
    while :; do
        v="$(status_field "$port" "$field")"
        # Fields the aggregator has not populated yet come back empty or "null",
        # so verify it is a number before comparing — [[ -ge ]] on "null" is a
        # syntax error that would abort the whole assertion.
        if [[ "$v" =~ ^[0-9]+$ ]] && [[ "$v" -ge "$min" ]]; then
            return 0
        fi
        i=$((i + 1))
        [[ $i -ge $timeout ]] && return 1
        sleep 1
    done
}

# ---- assertions -------------------------------------------------------------
file_exists()   { [[ -f "$1" ]]; }
file_size_gt()  { [[ -f "$1" ]] && [[ "$(stat_size "$1")" -gt "$2" ]]; }
assert_contains() { [[ -f "$1" ]] && grep -qF -- "$2" "$1" 2>/dev/null; }

# ---- test-run plumbing shared by the tiers ----------------------------------

# Copy surefire output somewhere the Docker layer cannot delete; echo the path.
#
# run-docker-tests.sh calls build_plugin(), which runs `mvn clean package` — and
# `clean` deletes target/, including the surefire reports that a failing Java
# layer just told the operator to go read. Without copying them aside first, a
# failing run destroys its own evidence, and since this repo has no CI there is
# no second copy anywhere. This is not redundant with target/ — that is the
# whole point.
archive_java_reports() {
    local dest="$RESULTS_DIR/surefire-reports"
    rm -rf "$dest"
    mkdir -p "$dest"
    if [[ -d "$PROJECT_DIR/target/surefire-reports" ]]; then
        cp -R "$PROJECT_DIR/target/surefire-reports/." "$dest/" 2>/dev/null || true
    fi
    printf '%s\n' "$dest"
}

# Structural check on the shaded jar. Echoes what is wrong; non-zero if anything is.
#
# `mvn verify` proves the shade plugin RAN, not that it produced a usable jar.
# Two failure modes survive it, and both are silent:
#
#   - a dashboard resource stops being copied in, so the plugin serves 404s for
#     its own UI while every unit test passes;
#   - a dependency gets bundled at its original coordinates and collides with
#     whatever JMeter has on the classpath at runtime. Nothing is bundled now
#     (t-digest was, relocated, until exact percentiles replaced it), so any
#     class outside the plugin's own package is a mistake.
#
# Neither is visible until a real JMeter run, which is why this is worth the
# ~0.2s here rather than waiting for the Docker layer to catch it by behaviour.
#
# Two more, cheaper still:
#
#   - a signature file (META-INF/*.SF, .RSA, ...) carried over from a signed
#     dependency: the JVM then checks the whole jar against it and refuses to load
#     any class from it ("Invalid signature file digest");
#   - the manifest's Implementation-Version, which the start log and /api/status
#     report, must be the pom's version, or bug reports name the wrong build.
assert_jar_sane() {
    local jar="${1:-$PLUGIN_JAR}" listing problems=0
    if [[ ! -f "$jar" ]]; then
        echo "  jar not found: $jar"
        return 1
    fi
    # Absolute: the manifest is read from inside a temp directory, where a relative
    # path (the release skill passes target/...) no longer points at the jar.
    jar="$(cd "$(dirname "$jar")" && pwd)/$(basename "$jar")"
    # The JDK's own jar tool: Maven found it through JAVA_HOME, PATH may not have it.
    local jartool=jar
    [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jar" ]] && jartool="$JAVA_HOME/bin/jar"
    listing="$("$jartool" tf "$jar" 2>/dev/null)" || {
        echo "  cannot read jar: $jar"
        return 1
    }
    local entry
    for entry in dashboard/index.html dashboard/echarts.min.js; do
        if ! grep -qx "$entry" <<< "$listing"; then
            echo "  missing resource: $entry"
            problems=1
        fi
    done
    if ! grep -q '^com/jmeterinsight/livedashboard/plugin/LiveDashboardListener.class$' <<< "$listing"; then
        echo "  the plugin's own classes are missing"
        problems=1
    fi
    local foreign
    foreign="$(grep '\.class$' <<< "$listing" | grep -v '^com/jmeterinsight/livedashboard/' | head -3)"
    if [[ -n "$foreign" ]]; then
        echo "  classes outside com/jmeterinsight/livedashboard/ are bundled, e.g.:"
        sed 's/^/    /' <<< "$foreign"
        problems=1
    fi
    local signed
    signed="$(grep -iE '^META-INF/[^/]+\.(SF|RSA|DSA|EC)$' <<< "$listing" | head -3)"
    if [[ -n "$signed" ]]; then
        echo "  signature files from a signed dependency (the JVM would reject the jar):"
        sed 's/^/    /' <<< "$signed"
        problems=1
    fi
    local version
    local tmp version
    tmp="$(mktemp -d)"
    (cd "$tmp" && "$jartool" xf "$jar" META-INF/MANIFEST.MF 2>/dev/null)
    version="$(tr -d '\r' < "$tmp/META-INF/MANIFEST.MF" 2>/dev/null | sed -n 's/^Implementation-Version: //p')"
    rm -rf "$tmp"
    if [[ "$version" != "$PLUGIN_VERSION" ]]; then
        echo "  manifest Implementation-Version is '${version}', the pom says '${PLUGIN_VERSION}'"
        problems=1
    fi
    return $problems
}

# Committed exec bits on the shell entry points. Echoes what is wrong;
# non-zero if anything is.
#
# A script tracked as 100644 still runs fine on this machine — Windows git
# ignores the x bit and everything is invoked as `bash foo.sh` — which is
# exactly why a wrong mode survives unnoticed here. On a Linux/macOS clone,
# ./scripts/merge-pr.sh dies with "Permission denied", and worse,
# .githooks/pre-push silently never fires: protection that looks installed
# and does nothing. Both tiers run this so the mode breaks a local gate
# instead of a future clone. scripts/install-hooks.sh repairs what it reports.
verify_script_modes() {
    local bad
    bad="$(git -C "$PROJECT_DIR" ls-files -s -- .githooks scripts .claude/hooks 2>/dev/null \
        | awk '$1 != "100755" {
              path = $4
              if (path ~ /\.sh$/ || path ~ /^\.githooks\//)
                  print "  not executable in the index: " path " (mode " $1 ")"
          }')"
    [[ -z "$bad" ]] && return 0
    echo "$bad"
    echo "  fix and stage with: ./scripts/install-hooks.sh"
    return 1
}

# Test plans must reference only THIS plugin's classes. Echoes what is wrong;
# non-zero if anything is.
#
# Five plans carried another plugin's listener, left behind by a JMeter that
# had both plugins installed. It costs nothing on a developer
# box with both jars in lib/ext, which is exactly why it survived: in a clean
# JMeter holding only this plugin, loading such a plan fails outright with a
# missing-class error, and the container tests had to route around it with a
# dedicated plan. A plan that cannot load in a clean install is not a test of
# this plugin. Cheap and static, so both tiers run it.
# Matches on the `com.` namespace only: JMeter's own elements are org.apache.jmeter.*
# and the jmeter-plugins ones are kg.apc.*, so a `com.` class in a plan here should
# always be com.jmeterinsight.*. Narrow on purpose — a broader rule would flag any
# legitimate third-party element the plans might grow later.
verify_test_plan_classes() {
    local bad
    bad="$(grep -oE '(guiclass|testclass)="com\.[A-Za-z0-9_.]+"' \
        "$PROJECT_DIR"/test-plans/*.jmx 2>/dev/null \
        | grep -v 'com\.jmeterinsight\.' \
        | sed "s|^$PROJECT_DIR/||" | sort -u | sed 's/^/  foreign plugin class: /')"
    [[ -z "$bad" ]] && return 0
    echo "$bad"
    echo "  a plan referencing another plugin's class cannot load in a clean JMeter"
    return 1
}

# The version a release is published as. Echoes what is wrong; non-zero if anything is.
# Release-time only (scripts/test-release.sh): mid-bump the docs lag the pom by design.
#
#   - no -SNAPSHOT: v1.1.0 had to be marked pre-release because its jar carried one;
#   - every jar filename the docs spell out by hand names this version, so the
#     README never tells a new user to download a jar that isn't in the release.
verify_release_versions() {
    local bad=""
    if [[ "$PLUGIN_VERSION" == *-SNAPSHOT ]]; then
        bad="  pom version ${PLUGIN_VERSION} is a SNAPSHOT"
    fi
    local stale
    stale="$(grep -rnoE 'jmeter-live-dashboard-[0-9][0-9A-Za-z.-]*[0-9A-Za-z]\.jar' \
            "$PROJECT_DIR/README.md" "$PROJECT_DIR/docs" 2>/dev/null \
        | grep -vF ":jmeter-live-dashboard-${PLUGIN_VERSION}.jar" \
        | sed "s|^$PROJECT_DIR/||; s/^/  names another version: /")"
    [[ -n "$stale" ]] && bad="${bad:+$bad
}$stale"
    [[ -z "$bad" ]] && return 0
    echo "$bad"
    return 1
}

# Every live.dashboard.* property the test plans set is one the plugin reads. Echoes
# what is wrong; non-zero if anything is. Eight plans carried live.dashboard.window.default,
# which nothing had read for many versions, so the plans looked like they configured a
# setting that does nothing.
verify_test_plan_keys() {
    local known bad
    known="$(grep -rohE '"live\.dashboard\.[A-Za-z0-9._-]+"' "$PROJECT_DIR/src/main/java" | tr -d '"' | sort -u)"
    bad="$(grep -rnoE 'name="live\.dashboard\.[A-Za-z0-9._-]+"' "$PROJECT_DIR"/test-plans 2>/dev/null \
        | while IFS= read -r hit; do
              key="${hit##*name=\"}"; key="${key%\"}"
              grep -qxF "$key" <<< "$known" || echo "  unknown key: ${hit#"$PROJECT_DIR"/}"
          done)"
    [[ -z "$bad" ]] && return 0
    echo "$bad"
    return 1
}

# Every `docker run` in scripts/ must go through docker_run (with docker_host_path for
# the host half of each -v), or carry MSYS_NO_PATHCONV itself. Echoes offenders;
# non-zero if any.
#
# Under Git Bash, MSYS rewrites POSIX-looking arguments before docker.exe sees them.
# run-docker-dashboard.sh used a bare `docker run`: on Windows it mounted the jar under
# \Program Files\Git\opt\... and told JMeter to open C:/Program Files/Git/test/<plan>,
# so the container exited at once and the README's "no JMeter installed" path showed
# no dashboard. Nothing on Linux/macOS notices, which is why it survived. Static, so
# both tiers run it.
verify_docker_path_handling() {
    local bad
    bad="$(grep -nE '(^|[^_[:alnum:]])docker[[:space:]]+run([[:space:]]|$)' \
            "$PROJECT_DIR"/scripts/*.sh "$PROJECT_DIR"/scripts/*/*.sh 2>/dev/null \
        | grep -vE '^[^:]+:[0-9]+:[[:space:]]*#' \
        | grep -v 'MSYS_NO_PATHCONV=1' \
        | sed "s|^$PROJECT_DIR/||; s/^/  bare docker run: /")"
    [[ -z "$bad" ]] && return 0
    echo "$bad"
    echo "  Git Bash rewrites its paths; use docker_run / docker_host_path (scripts/lib/common.sh)"
    return 1
}

# Install Playwright's node deps on first run. Non-zero on failure.
#
# Returns a status rather than recording a result itself, so the caller can fold
# setup failure and test failure into exactly ONE record() for the layer. Both
# tiers previously recorded a FAIL here and then ran Playwright anyway, counting
# the web layer twice and reporting TOTAL: 3 for a two-layer tier.
ensure_playwright_deps() {
    local dir="$PROJECT_DIR/tests/playwright"
    [[ -d "$dir/node_modules" ]] && return 0
    echo "[SETUP] Installing test dependencies (first run only)..."
    (cd "$dir" && npm ci)
}

# warn_over_budget <elapsed_s> <budget_s> <tier-name>
#
# A soft ceiling, deliberately not a failure. The suite is budgeted at under 15
# minutes end to end, and the way a budget like that gets blown is one layer
# creeping up a few seconds at a time with nothing watching. Surface the drift
# while it is still cheap to fix; do not block a push over it.
warn_over_budget() {
    local elapsed="$1" budget="$2" tier="$3"
    [[ "$elapsed" -le "$budget" ]] && return 0
    echo ""
    echo "NOTE: $tier took ${elapsed}s, over its ${budget}s target."
    echo "      Not a failure. The per-layer times above show what grew."
}

# ---- result tracking --------------------------------------------------------
PASS=0
FAIL=0
SKIP=0
TOTAL=0
RESULTS=()

# record <id> <name> PASS|FAIL|SKIP [note] [seconds]
#
# SKIP is not FAIL: a layer that could not run (no Docker daemon, for example)
# must not fail the gate, but must not be counted as a pass either — a silent
# skip reads as green and is how a missing layer goes unnoticed. It is reported
# separately and called out in the summary.
#
# [seconds] is optional so every existing four-argument call site still works.
# Per-layer timings are what make budget creep visible: a total alone tells you
# the tier got slower but not which layer did it.
record() {
    local id="$1" name="$2" status="$3" note="${4:-}" secs="${5:-}" timing=""
    TOTAL=$((TOTAL + 1))
    if [[ "$status" == "PASS" ]]; then
        PASS=$((PASS + 1))
    elif [[ "$status" == "SKIP" ]]; then
        SKIP=$((SKIP + 1))
    else
        FAIL=$((FAIL + 1))
    fi
    [[ -n "$secs" ]] && timing=" (${secs}s)"
    RESULTS+=("[$status] $id: $name${timing}${note:+ -- $note}")
    echo "  [$status] $id: $name${timing}"
    [[ -n "$note" ]] && echo "          $note"
    return 0
}

print_banner() {
    echo "==============================================================="
    echo "  $1"
    echo "  $(date)"
    echo "==============================================================="
    echo ""
}

print_summary() {
    local title="${1:-TEST SUMMARY}"
    echo ""
    echo "==============================================================="
    echo "  $title"
    echo "==============================================================="
    echo ""
    local r
    for r in ${RESULTS[@]+"${RESULTS[@]}"}; do
        echo "  $r"
    done
    echo ""
    # JMETER_HOME is unset for container runs, where JMeter lives in the image.
    if [[ -n "${JMETER_HOME:-}" ]]; then
        echo "  Plugin: $PLUGIN_VERSION   JMeter: $JMETER_HOME"
    else
        echo "  Plugin: $PLUGIN_VERSION"
    fi
    if [[ $SKIP -gt 0 ]]; then
        echo "  TOTAL: $TOTAL    PASS: $PASS    FAIL: $FAIL    SKIP: $SKIP"
        echo "  NOTE: $SKIP layer(s) did not run — this result is not full coverage."
    else
        echo "  TOTAL: $TOTAL    PASS: $PASS    FAIL: $FAIL"
    fi
    echo "==============================================================="
}
