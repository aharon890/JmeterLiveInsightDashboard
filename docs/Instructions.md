# Build / Test / Develop Guide

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Java (JDK) | 11–21 | Build and test. The jar targets Java 11. The scripts prefer 17, then 21; Java 26 breaks Mockito |
| Maven | 3.6+ | Build system |
| JMeter | 5.6.3 | Runtime host for the plugin. Optional locally: Docker covers it |
| Node.js | 18+ | Playwright browser tests |
| Docker Desktop | — | The Docker E2E layer of the regression tier, the perf harness, the zero-install dev loop |
| Git Bash | — | Windows only: every script is bash |

## One-time setup after cloning

```bash
./scripts/install-hooks.sh
```

It points git at the versioned hooks in `.githooks/`, sets `origin/HEAD`, and repairs
the executable bit on scripts. Re-run it if a tier reports a script-mode problem.

## Building

### Scripts (macOS / Linux / Windows Git Bash)

```bash
./scripts/build.sh                            # build the jar and deploy it to the local JMeter
./scripts/build-and-test.sh                   # build, deploy, open the JMeter GUI with a smoke plan
./scripts/run-docker-dashboard.sh [plan.jmx]  # build, run a plan in Docker, browse localhost:7566
./scripts/run-5min-comparison.sh              # 5-min non-GUI run against the local JMeter
```

- **Scripts:** bash is the single source of truth; each `scripts/*.bat` is a thin wrapper
  for Windows.
- **JMeter** is found from `$JMETER_HOME`, then a project-local `apache-jmeter-*/`, then
  `jmeter` on PATH.
- **JDK:** a set `JAVA_HOME` always wins. Otherwise the scripts pick JDK 17, then 21, on
  macOS and Linux. On Windows, set `JAVA_HOME`.

**After any change under `src/main`, run `./scripts/build.sh` again.**
- JMeter runs whatever jar is in its `lib/ext/`, so a stale jar makes the dashboard look
  broken for no visible reason.
- Deploying removes older `jmeter-live-dashboard-*.jar` files first: JMeter loads every
  jar in that folder, so a leftover copy can shadow the new one.
- The regression tier warns when the deployed jar is older than `src/main`. Smoke
  deliberately does not: mid-change the deployed jar is almost always older.

### Manual

```bash
mvn clean package -DskipTests
```

JAR output: `target/jmeter-live-dashboard-1.6.2.jar`

Copy it to `$JMETER_HOME/lib/ext/` to install, removing any older
`jmeter-live-dashboard-*.jar` there first.

## Running Tests

All automation runs locally — this repo has no CI:

```bash
./scripts/test-smoke.sh          # ~35s  — the fast gate, run while developing
./scripts/test-regression.sh     # ~3.5m — the real gate, when a feature or fix is done
./scripts/merge-pr.sh            # merge the PR on a green regression of that exact content
./scripts/test.sh                # Java tests only
./scripts/test-report.sh         # Java tests, then open the Surefire HTML report
```

**Commit first, then run regression; it runs once per content.**
- **Recorded:** a fully green run on a clean tree records that content in
  `test-results/.regression-green`. Pushing requires it, and `merge-pr.sh` reuses it
  instead of testing the same content again.
- **Not recorded:** a run with a skipped layer (Docker not running), or with
  uncommitted or untracked files present, records nothing.

**See [TESTING.md](TESTING.md) for test counts, the per-layer breakdown, how to run a
single class or spec, and how to diagnose a failure.** Counts live there and only there.

Playwright runs fully parallel. Each worker starts its own mock server on an OS-assigned
port ([`fixtures.js`](../tests/playwright/fixtures.js)). Specs must import
`{ test, expect }` from `./fixtures`, never from `@playwright/test`: that is also
what fails a test on an error in the page.

**Perf harness — on demand only.** A scenario takes 10–60 min:
- it runs the real jar in Docker;
- it compares the dashboard's numbers with JMeter's own, computed from the JTL;
- the report goes to `test-results/perf/`.

```bash
bash scripts/perf/run-scenario.sh list
```

## Releasing

Releases are cut with the `_perf_release` Claude Code skill (`/_perf_release`). It:
- bumps the version;
- deploys the jar;
- runs the regression, then the release check (`scripts/test-release.sh`);
- merges through `merge-pr.sh`;
- tags the release and publishes it on GitHub with the jar attached.

The version lives in one place, `<version>` in `pom.xml`; every script reads it from there.
Release versions have no `-SNAPSHOT` suffix.

## Project Structure

```
src/
  main/
    java/com/jmeterinsight/livedashboard/
      plugin/        LiveDashboardListener (the listener), LiveDashboardListenerGui (its panel)
      engine/        LiveAggregator, SamplerAggregator, ExactHistogram (exact percentiles),
                     TimeBucket (open slot), FrozenBucket (closed slot),
                     Columnar (wire format), CatchupSnapshot (catch-up for a new tab)
      server/        DashboardServer (embedded HTTP + SSE)
    resources/dashboard/
      index.html     The whole dashboard (HTML + CSS + JS)
      echarts.min.js ECharts 5.5.0, served from the jar
  test/java/         JUnit 5 tests

tests/playwright/
  *.spec.js          Browser tests against the real index.html
  fixtures.js        Per-worker mock server; specs import test/expect from here
  setup/             The mock SSE server
  fixtures/          Canned catch-up data
  take-screenshots.js  Regenerates the screenshots in docs/images/
  preview-md.js      Generates docs/Tutorial/01-Overview-preview.html

scripts/
  *.sh               Every entry point (build, test tiers, merge gate, Docker runs)
  *.bat              Thin Windows wrappers, no logic
  lib/common.sh      Shared helpers: JDK/JMeter discovery, build, deploy, checks
  lib/*.groovy, *.mjs  Docker D13 and D15: the dashboard's numbers against JMeter's own
  perf/              Perf harness (on demand)

test-plans/          JMeter plans
  docker-smoke-test.jmx       Docker E2E and run-docker-dashboard.sh
  quick-smoke-test_2.jmx      build-and-test.sh (5 users, 30s)
  quick-smoke-test.jmx        Same smoke load, without the Aggregate Report; no script uses it
  5min-comparison-test.jmx    run-5min-comparison.sh
  duration-pause-test.jmx     3 min with a 1-min pause in the middle
  omtg-test.jmx               Open Model Thread Group
  spike-load-test-30min.jmx   30-min spike with an idle period
  full-load-test.jmx          No dashboard listener; doesn't exercise this plugin
  report-stress-test.jmx      No dashboard listener; doesn't exercise this plugin
  perf/                       Perf harness plan and its Groovy scripts

docker/Dockerfile.jmeter      JMeter image for Docker E2E, the perf harness and the dev loop
.githooks/pre-push            Blocks direct pushes to main/master
.claude/                      Claude Code: skills (release, run-tests, prune-actions) and the push gate

docs/
  index.html, _sidebar.md, .nojekyll   Docsify site
  Tutorial/          User guide, one page per tab and feature
  images/            Screenshots used by the guide
  FEATURES.md        Detailed feature reference
  Instructions.md    This file
  TESTING.md         Test layers, flow and counts
  TODO.md            Known issues, current limitations, design decisions
```

## Development Workflow

1. **Branch:** one branch per change, from `master`. Direct pushes to `master` are
   blocked by the pre-push hook.
2. **Develop:**
   - implement the change;
   - run `./scripts/build.sh` if you touched `src/main`;
   - run `./scripts/test-smoke.sh`.
3. **Fix first:** fix bugs before moving to the next feature.
4. **Finish:** commit, then run `./scripts/test-regression.sh` with Docker running.
   Then push and open a PR.
5. **Merge:** with `./scripts/merge-pr.sh`.
   - It first checks that the branch is the exact merge result.
   - It then merges on the green run from step 4.
   - It runs the regression again only if the content changed since, for example
     after merging `master` in.
6. **Docs:**
   - update `docs/` in the same PR;
   - test counts go only in `TESTING.md`;
   - known limitations go in `TODO.md`;
   - no plans, reviews or notes are committed. Finished work lives in git history.
