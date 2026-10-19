# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A JMeter listener plugin that serves a live browser dashboard from inside the
JMeter process. There is no external infrastructure: the plugin embeds a JDK
`com.sun.net.httpserver` instance, pushes metrics over SSE, and serves a
single-file dashboard from its own jar. Deliverable is one shaded jar dropped
into `$JMETER_HOME/lib/ext/`.

## Commands

```bash
# Build + deploy to the detected local JMeter
./scripts/build.sh                    # scripts/build.bat on Windows
mvn clean package -DskipTests         # jar only -> target/jmeter-live-dashboard-<version>.jar

# Test tiers — ALL automation is local; there is no CI (see "Automation" below)
./scripts/test-smoke.sh               # ~35s : Java fast tier + shaded jar + Playwright @smoke
./scripts/test-regression.sh          # ~3.5m: full Java + full Playwright + Docker E2E
./scripts/merge-pr.sh                 # merge the PR only on a green regression of this content
SKIP_DOCKER=1 ./scripts/test-regression.sh    # when Docker isn't running

# Individual layers
mvn test                              # full suite
mvn test -DexcludedGroups=slow        # skips the idle-timeout waits
mvn test -Dtest=DashboardServerTest   # one class
mvn test -Dtest='DashboardServerTest#testPortFallbackWhenConfiguredPortIsBusy'   # one method
cd tests/playwright && npm test                    # full suite, ~27s
cd tests/playwright && npm run test:smoke          # the @smoke subset
cd tests/playwright && npx playwright test live-updates.spec.js   # one spec
cd tests/playwright && npx playwright test --headed # watch it
bash scripts/run-docker-tests.sh      # real JMeter + real jar, asserts D1-D15
./scripts/test-release.sh             # once per release, ~5m: versions, jar, Firefox/WebKit, Java 11
bash scripts/perf/run-scenario.sh list   # perf harness — on demand only, 10-60 min a scenario
```

Test **counts** live in `docs/TESTING.md` and nowhere else — they were
duplicated across four files and went stale twice.

**Releases go through the `_perf_release` skill**
(`.claude/skills/_perf_release/SKILL.md`), not by hand — `/_perf_release`, or with an
explicit `patch`/`minor`/`major`. It bumps the version, deploys to the local JMeter
install, runs regression and then the release check (`scripts/test-release.sh`),
merges through `merge-pr.sh`, tags, and publishes the
GitHub release with the shaded jar attached. The version has exactly one functional
home, `<version>` in `pom.xml`; `detect_plugin_version()` in `scripts/lib/common.sh`
reads it back, so no script ever needs a hand-edit on a bump. Release versions carry
no `-SNAPSHOT` suffix — `v1.1.0` had to be flagged pre-release only because it
shipped one.

Bash is the single source of truth; every `scripts/*.bat` is a thin wrapper.
Don't add logic to a `.bat`. Run the tiers from Git Bash, not PowerShell.

**The Docker layer runs `mvn clean package`, which deletes `target/`.** So a
regression run destroys `target/surefire-reports` partway through — that is why
both tiers copy them to `test-results/surefire-reports/` before the container
layer starts (`archive_java_reports` in `scripts/lib/common.sh`). Read failures
there, not in `target/`, and don't delete the copy as redundant.

**Direct pushes to `main`/`master` are blocked** by `.githooks/pre-push`. Work
on a branch and open a PR. In a fresh clone, run the installer once:

```bash
./scripts/install-hooks.sh    # hooksPath + origin/HEAD + index exec bits
```

It is also the repair tool when the tiers' script-mode check fails — a hook or
`.sh` committed 100644 works on Windows and silently breaks Linux/macOS clones.

## Automation runs locally, and only locally

There are **no GitHub Actions workflows**, deliberately — all verification runs
locally. Nothing on the server re-checks a branch, so:

- A local run is not a preview of CI. It is the only verification that happens.
- **A skipped layer is a gap, not a deferral.** Regression reports SKIP
  separately from PASS for exactly this reason, and `merge-pr.sh` refuses to
  merge when anything skipped.
- **Regression runs once per content.** Commit, then run it when a feature is
  finished or a bug is fixed.
  - A fully green run on a clean tree writes HEAD's tree hash to
    `test-results/.regression-green`.
  - A run with a SKIP, or with uncommitted or untracked files present, records
    nothing.
  - Tree, not commit sha, so a merge commit with an identical tree stays green.
- **Pushing:** in Claude Code sessions, a PreToolUse hook (`.claude/settings.json`
  → `.claude/hooks/pre-push-gate.sh`) blocks `git push` unless that marker
  matches HEAD. A blocked push means "run regression", not "bypass";
  `PUSH_GATE_BYPASS=1` is for the user to invoke, not you. Manual pushes from a
  terminal are not gated.
- **Merging** goes through `./scripts/merge-pr.sh`. It first checks that HEAD's
  tree is the merge result: clean tree, local HEAD == PR head, branch already
  contains master.
  - If the marker matches that tree, it merges on it, with no second run.
  - Otherwise it runs regression itself, with Docker required.

Don't reintroduce a workflow file to "get CI back" without asking — the absence
is a deliberate decision, not an oversight.

**The jar in `lib/ext/` must always match `src/`.** After changing anything
under `src/main`, re-run `./scripts/build.sh` — otherwise JMeter silently runs
the old code and the dashboard looks broken for no visible reason.

## Architecture

```
JMeter process                                    Browser
  LiveDashboardListener  (a ResultCollector)
    -> LiveAggregator                               <- SSE   /api/events  (gzip)
         live tier:     2s slots, last hour         <- REST  /api/status|config|history
         overview tier: 1-min slots, whole run      -> POST  /api/stop|stop-now
         SamplerAggregator + ExactHistogram   (exact percentiles)
    -> DashboardServer  (JDK HttpServer + SSE broadcast every 2s)
```

`src/main/resources/dashboard/index.html` is the entire front end — ~1,800 lines
of HTML, CSS and JS in one file, served from the jar. `echarts.min.js` (5.5.0) is
bundled alongside it.

### The numbers must match JMeter's own

The plugin is a reporter. JMeter's Aggregate Report and HTML report are the
reference for every metric; where the dashboard disagrees with them, the
dashboard is wrong.

- **Slots follow JMeter's timing model.** A sample belongs to the slot holding its
  *end* time, slots are aligned to the clock, and rates divide by the nominal slot
  width. Two tiers are fed as samples arrive:
  - the live tier at the update interval, pruned to `window.max` (1 h);
  - a 1-minute tier covering the whole run, never pruned.

  The minute tier can't be derived from the live one: closed slots are frozen, and
  percentiles don't merge.
- **An open slot is a `TimeBucket`; a closed one is a `FrozenBucket`.** A frozen
  slot is plain numbers keyed by sampler id, with the working data dropped. Keeping
  closed slots live ran out of memory after ~5 min at 500 samplers.
- **Percentiles are exact.** `ExactHistogram` computes them the way
  `StatCalculator.getPercentPoint` does, exactly up to 10 s. Above 10 s a
  percentile can read up to 0.1% low. It replaced a T-Digest that was up to 11.5%
  off at p99 on small samples. The 10 s bound is deliberate and not configurable;
  see `docs/TODO.md`.
- **The wire format is built for many samplers:**
  - per-sampler maps go out columnar (`Columnar`, decoded by `decodeColumnar` in
    `index.html`);
  - SSE and `/api/history` are gzipped when the client accepts it;
  - a new tab's catch-up is streamed straight to the socket (`CatchupSnapshot`),
    not built as one string.

### Lifecycle state is static, and one copy per engine feeds it

JMeter constructs and **clones** listener instances, so `server`, `aggregator`,
`startedHosts` and `feeders` in `LiveDashboardListener` are all `static`,
guarded by `LIFECYCLE_LOCK`. Every copy receives samples, but
`sampleOccurred()` returns immediately unless the copy is in `feeders`: the
first copy to start for each engine host. A second listener element in the plan
starts for a host that already has a feeder, so it only joins and cannot
double-count. If you add state to the listener, decide deliberately whether it
is per-instance or shared — getting this wrong produces duplicated or silently
dropped samples.

A distributed run (`-R`) gives **each remote engine its own copy** on the
controller (`ClientJMeterEngine.configure` clones the tree per engine), each
seeing only that engine's `testStarted(host)` and samples. The code once assumed
one copy served every engine; real JMeter showed the dashboard counting one
engine's samples and looking healthy. `feeders` is an identity map because test
elements' `equals()` compares properties, so the copies are all "equal". Docker
D9–D11 pin this against real JMeter, which is the only place it can be pinned.

`startedHosts` tracks running engines so a distributed run tears the dashboard
down only when the **last** engine finishes, not the first. Thread counts are
per engine too (`allThreads` covers only the engine that produced the result),
so `LiveAggregator` sums each engine's latest report.

Distributed runs are aggregated correctly but are otherwise unsupported:
Stop/Shutdown act on the local engine only (deliberately not fixed; see
`docs/TODO.md`).

### The server deliberately outlives the test

After `testEnded()` the HTTP server keeps serving so the operator can still read
the results. It shuts down on an **idle timeout** (no SSE clients for N
seconds; `live.dashboard.idle.timeout`, default 600, `0` disables it), not on
JVM shutdown. A shutdown hook was tried and reverted — see `docs/TODO.md` —
because it killed the dashboard the moment JMeter exited.

Consequence worth knowing: a dashboard tab left open in a browser holds an
`EventSource` open, which keeps the server alive indefinitely and will look
like a hang.

Reconnect behaviour splits on *how* the connection ended, and the two halves are
deliberately different:

- **Announced** (a new run starting): `startDashboard()` broadcasts `serverClosing`
  first, and the client latches `S.serverClosed` and stops reconnecting for good. The
  tab keeps showing the finished run's results instead of silently switching to the new
  one — pinned by `server-closing.spec.js`, including that the data survives. A reload
  link appears, because the replacement server binds the same port.
- **Unannounced** (JMeter killed, laptop sleep, network blip): no `serverClosing`
  arrives, so `eventSource.onerror` retries every 3s and the tab *does* re-attach — to
  whatever now owns that port, which may be a different JMeter process. The catchup
  carries a `serverInstanceId`; when it changes, the page says so rather than quietly
  showing another run's numbers.

If the configured port is taken the server auto-increments (up to 10 attempts,
so 7566–7575) and logs `Port N was in use, using port N+1 instead`. Windows
TIME_WAIT after a killed run is the usual cause.

### Config precedence is not uniform

Settings resolve element-value-first, falling back to the JMeter property:

```java
getIntConfig(key, default)  // element property -> JMeterUtils.getPropDefault
```

**`live.dashboard.open.browser` and `live.dashboard.bind` are the deliberate
exceptions** — for both, the `-J` property beats the saved element value
(`shouldOpenBrowser()`, `getBindConfig()`). Both describe the *machine* rather
than the test, and the GUI writes both into every saved plan, so automation
never has to rewrite a `.jmx` to suppress the browser or to make the dashboard
reachable from another host.

`bind` is the stronger case of the two: its GUI box comes pre-filled with
`localhost` and is written into every saved plan (until it became editable, so a
plan can name one interface, it was a fixed dropdown that could not even be
cleared) — under the element-wins rule `-Jlive.dashboard.bind=0.0.0.0` was
silently ineffective for every GUI-saved plan. The Port field was always a text
box that could be cleared, which is why it needs no exception.

`live.dashboard.enabled` and `live.dashboard.error.samples.max` have no element
value at all: they are read from the JMeter property only.

All of this is pinned by tests; don't "fix" the asymmetry.

## Testing layers

| Layer | Location | Covers |
|---|---|---|
| Java unit | `src/test/java` | aggregation, bucketing, percentiles, server lifecycle, config precedence, GUI field mapping |
| Playwright | `tests/playwright` | the real `index.html` against a mock SSE server; every tab |
| Docker E2E | `scripts/run-docker-tests.sh` | the only layer with real JMeter and the real jar |
| Perf harness | `scripts/perf/`, `test-plans/perf/` | **on demand only**, 10–60 min a scenario: the real jar in Docker, with the dashboard's numbers compared against JMeter's own, computed from the JTL. Reports go to `test-results/perf/` |

Playwright runs **fully parallel**: each worker starts its own mock server on
its own OS-assigned port (`tests/playwright/fixtures.js`) and `baseURL` points
at it. Specs drive the server through page-relative `fetch`, so requests always
land on their own worker's origin. Import `{ test, expect }` from `./fixtures`,
never from `@playwright/test`, or the spec loses that isolation, and the check
that fails a test on any error in the page.

`@Tag("slow")` marks six tests in `DashboardServerTest`, five of them idle-timeout
tests. Their waits *are* the assertion, so they can't be mocked away. Smoke skips them,
regression runs them.

The long `.jmx` plans in `test-plans/` are **not** part of automation — they are
load scenarios taking 20+ minutes. Run one on demand with `docker run` (see
`docs/TESTING.md`). Note `full-load-test.jmx` and
`report-stress-test.jmx` contain no dashboard listener at all and never
exercised this plugin.

See `docs/TESTING.md` for the full picture.

## Windows notes

This repo is developed on Windows; both are already handled in
`scripts/lib/common.sh`, but hand-rolled commands hit them:

- **`docker -v` under Git Bash.** MSYS rewrites POSIX-looking arguments into
  Windows paths before `docker.exe` sees them, mangling the *container* side of
  a mount. Use `docker_run` / `docker_host_path` from `common.sh`; a bare
  `docker run` with mounts will break.
- **`-J` arguments in PowerShell must be quoted.** Unquoted, PowerShell splits
  `-Jlive.dashboard.bind=0.0.0.0` at the first dot and JMeter reads the property
  name as just `live`, silently ignoring the setting.

## Conventions

- Java 11 target. Nothing is bundled: everything comes from JMeter at runtime
  (`provided` scope). `assert_jar_sane` fails the tiers if a class outside
  `com/jmeterinsight/livedashboard/` lands in the jar.
- Comments in this codebase explain *why*, especially where behaviour looks
  wrong at first glance. Several encode hard-won findings — keep that style and
  update the comment when you change the reasoning behind it.
- **All documentation lives in `docs/`.** The repo root holds only `README.md`,
  `CLAUDE.md`, `LICENSE` and `NOTICE`, besides the build and git files.
  - The user guide is `Tutorial/` and `FEATURES.md`, served as a docsify site by
    `index.html` and `_sidebar.md`.
  - `Tutorial/01-Overview-preview.html` is meant to be published, so people can
    see the overview. `tests/playwright/preview-md.js` generates it from
    `01-Overview.md`; regenerate it instead of editing it by hand.
  - Developer docs: `Instructions.md` (build, structure, workflow) and `TESTING.md`.
  - `docs/TODO.md` holds known issues, measured current limitations, and design
    constraints that were deliberately chosen.
  - Don't commit plans, reviews or session notes, and don't make an archive
    folder for them. Finished work lives in git history.
