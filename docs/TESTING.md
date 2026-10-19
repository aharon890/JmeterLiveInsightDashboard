# Testing

**All automation runs locally. There is no CI, deliberately** — this repo has no
GitHub Actions workflows.
Nothing on the server re-checks a branch, so a local run is not a preview of a
verdict issued elsewhere; it is the only verdict there is.

That changes what a skipped layer means. With CI, skipping the container layer
locally is a deferral. Here it is a gap that nothing closes, which is why the
tiers report SKIP separately from PASS and why the merge gate refuses to merge
when anything skipped.

| Tier | When it runs | Contents | Measured |
|---|---|---|---|
| **Smoke** | while developing, on demand | Java fast tier + shaded jar, Playwright `@smoke` | ~35s |
| **Regression** | a feature is finished or a bug is fixed | Full Java, full Playwright, Docker E2E | ~3.5 min |
| **Merge gate** | before merging a PR to `master` | Reuses the green regression recorded for the same content; runs it only if there is none | seconds, or ~3.5 min |
| **Release check** | once per release, by the `_perf_release` skill, or on demand | Release version in pom and docs, the jar check, `@smoke` on Firefox and WebKit, Docker E2E with JMeter on Java 11 | ~5 min |
| **Load plans** | on demand only, by hand | one long `.jmx` at a time | 20 min+ |

## The flow

```
develop  ──▶  ./scripts/test-smoke.sh          fast feedback, as often as you like
                     │
feature done ──▶  commit, then ./scripts/test-regression.sh   everything, including real JMeter
   or bug fixed     │
                     │
   push branch, open PR
                     │
   merge  ──────▶  ./scripts/merge-pr.sh        merge if that run covers this exact content
```

The regression runs **once** per content. A fully green run on a clean, committed
tree records that tree in `test-results/.regression-green`. The push gate and the
merge gate both accept it, so pushing and merging the same commit does not test it
twice. Any change — a new commit, or merging `master` in — means a new run.

Direct pushes to `main`/`master` are blocked by `.githooks/pre-push`, so work
reaches master through a PR and the merge gate.

### The merge gate

```bash
./scripts/merge-pr.sh            # infers the PR from the current branch
./scripts/merge-pr.sh 11         # or name it
./scripts/merge-pr.sh --squash   # merge strategy passes through to gh
```

It merges only on a fully green regression of the content being merged. It
refuses to start unless three things hold, each of which would otherwise let a
green run certify the wrong tree:

- the working tree is clean — uncommitted work is not in the PR;
- local `HEAD` matches the PR's head commit — otherwise you tested a different
  commit than the one being merged;
- the branch already contains `master` — otherwise you tested the branch, not the
  merge result, and any interaction with what landed since is unverified.

Together these make `HEAD`'s tree the merge result.
- **A green run already recorded for that tree** — usually the one you ran before
  pushing — is reused.
- **Otherwise the gate runs the regression itself.** It requires Docker so the
  container layer cannot skip, and merges only if every layer passed.

A run is recorded only when every layer passed with nothing skipped, on a tree
with no uncommitted or untracked changes.

Needs the GitHub CLI (`gh auth login` once).

## Running it locally

One command per tier:

```bash
./scripts/test-smoke.sh          # ~35s  : Java fast tier + shaded jar + Playwright @smoke
./scripts/test-regression.sh     # ~3.5m : full Java + full Playwright + Docker E2E
```

`scripts/test-smoke.bat` / `test-regression.bat` are thin Windows wrappers. Both
print a PASS/FAIL/SKIP summary and exit non-zero on failure, so they work as a
pre-push gate. If Docker isn't running, regression records the container layer
as **SKIP** rather than PASS — a skipped layer is not a green run.

```bash
SKIP_DOCKER=1 ./scripts/test-regression.sh    # deliberately skip the container layer
DASHBOARD_PORT=7601 ./scripts/test-regression.sh  # override the E2E's port
```

The Docker E2E defaults to port **7599**, not the dashboard's own 7566. Sharing
7566 put the test in competition with the developer's browser: a dashboard tab
left open holds an `EventSource`, keeps the server alive past its idle timeout,
and D5 then fails looking exactly like a product bug. It also refuses to start if
anything is already answering on its port, so a collision is reported up front
instead of surfacing as a puzzling D5 failure a minute later.

Individual layers, when you want to iterate on one:

```bash
mvn test -DexcludedGroups=slow                     # ~20s
mvn test                                           # ~50s
mvn test -Dtest=DashboardServerTest                # one class
cd tests/playwright && npm run test:smoke          # ~7s
cd tests/playwright && npm test                    # ~27s
cd tests/playwright && npx playwright test live-updates.spec.js
bash scripts/run-docker-tests.sh                   # D1-D15, ~2m
./scripts/test-release.sh                          # the release check, ~5m
cd tests/playwright && PW_BROWSERS=firefox,webkit npx playwright test --grep @smoke
```

`scripts/test-release.sh` is never a regression layer: it needs Docker and
minutes every time, and it covers what doesn't change between commits, only between
machines and releases. It reports an unavailable layer as FAIL, not SKIP.
Every build (`build_plugin`) checks the jar it made (`assert_jar_sane`): the
dashboard files are in it, no other library's classes or signature files are, and
the manifest's `Implementation-Version` is the pom's. The tiers' static checks also
fail on a test plan that sets a `live.dashboard.*` property the plugin doesn't read.

A long plan on demand, when you actually want load signal. From Git Bash, a bare
`docker run` with mounts breaks (see Platform notes); the scripts use `docker_run`
and `docker_host_path` from `scripts/lib/common.sh` instead:

```bash
docker run --rm \
  -v "$PWD/target/jmeter-live-dashboard-1.6.2.jar:/opt/apache-jmeter-5.6.3/lib/ext/plugin.jar" \
  -v "$PWD/test-plans:/test:ro" -v "$PWD/test-results/load:/results" \
  jmeter-livedashboard-test:5.6.3 \
  -n -t /test/spike-load-test-30min.jmx -l /results/out.jtl -j /results/out.log \
  "-Jlive.dashboard.bind=0.0.0.0" "-Jlive.dashboard.open.browser=false" \
  "-Jlive.dashboard.idle.timeout=10"
```

## Test inventory

**This section is the only place in the repo that states test counts.** They
used to be duplicated into `docs/Instructions.md`, `FEATURES.md` and `CLAUDE.md`,
including per-class and per-spec tables, and they went stale twice — the last
drift had three different totals in three files. Everything else links here.

| Layer | Count | Command |
|---|---|---|
| Java, fast tier (smoke) | 158 | `mvn test -DexcludedGroups=slow` |
| Java, full (regression) | 164 across 9 classes | `mvn test` |
| Playwright `@smoke` | 27 | `npm run test:smoke` |
| Playwright full | 206 across 24 specs | `npm test` |
| Docker E2E | 17 checks, D1–D15 (with D1b, D2a, D2b) | `bash scripts/run-docker-tests.sh` |
| **Total automated** | **370** | both tiers |

Measured 2026-10-18. Rather than trusting the table, regenerate it — the
breakdown per class and per spec is one command each, which is why neither is
written down:

```bash
mvn test | grep 'Tests run:'                       # per class, then the total
cd tests/playwright && npx playwright test --list | tail -1          # full
cd tests/playwright && npx playwright test --list --grep @smoke | tail -1
```

If you add tests, update this table and nothing else.

## What the layers cover

- **Script modes** (`verify_script_modes` in `scripts/lib/common.sh`) — both
  tiers first check that every tracked hook and `.sh` is committed 100755.
  Windows runs everything through `bash` and never notices a wrong exec bit;
  a Linux/macOS clone gets "Permission denied" — or, for `.githooks/pre-push`,
  a silently disabled hook. `./scripts/install-hooks.sh` repairs what this
  reports. The same step checks that every test plan references only this
  plugin's classes (`verify_test_plan_classes`), and that no script runs a
  bare `docker run` (`verify_docker_path_handling`): under Git Bash its
  container paths get rewritten into Windows paths.
- **Java unit** (`src/test/java`) — aggregation, bucketing, percentiles, the
  HTTP server and its lifecycle, config precedence, and the GUI's
  element↔field mapping.
- **Playwright** (`tests/playwright`) — the dashboard UI against a mock SSE
  server that serves the *real* `index.html`. The specs cover every tab, plus
  what the page does between runs (`run-transitions.spec.js`).
  `timeline-grouping.spec.js` holds the definition of a grouped Timeline point
  as a small reference implementation and checks every point against it, on
  fixed runs and on seeded random ones.
- **Docker E2E** (`scripts/run-docker-tests.sh`) — the only layer with real
  JMeter and the real jar: runs `docker-smoke-test.jmx` in a container and
  asserts D1–D15 against the live dashboard.
  - **The plan:** two thread groups simulate an API. A third feeds the paths a
    real run has to get right:
    - a transaction that generates a parent sample, so its steps arrive as
      sub-results, and one that does not;
    - an assertion that fails every third payment;
    - a Debug Sampler, which the default hide patterns hide;
    - labels with quotes, markup, backslashes and a comma.
  - **D1–D6** are plumbing: reachable through the published port, samples
    flowing, HTML serves, SSE catch-up delivered, clean container exit, results
    written. D1b checks that `/api/status` reports the pom's version as
    `pluginVersion`, which only a real jar's manifest can supply.
  - **D7–D8** are the aggregation itself, which every other layer only ever sees
    against mock data: all 15 samplers registered (`knownSamplers`, polled
    because the plan ramps over 5s), and a non-zero percentile in
    `/api/history`. D8 requires non-zero rather than non-null on purpose — a
    percentile calculation that silently failed would report 0, not an error.
  - **D9–D11** are a distributed run: two `jmeter-server` engines and a
    controller on a private Docker network, the same plan driven with `-R`.
    The dashboard runs on the controller, where JMeter gives each engine its
    own copy of the listener — the code once assumed a single copy, and the
    dashboard silently counted one engine's samples. D9 checks that both engines
    ran (otherwise D10 passes trivially), D10 that the dashboard's final total
    equals the JTL's top-level rows across engines, D11 that the thread peak is
    the sum (24), not one engine's share (12). Transaction steps are rows of
    their own in the JTL, but they count once, inside their transaction. Verified
    to fail on the pre-fix jar, back when the plan had two thread groups (307 of
    615 samples, 10 threads).
  - **D12** checks that the dashboard shows the plan's own name. Non-GUI JMeter
    knows the plan only as the script it loaded, which no unit test can stand in
    for; the name used to come from a property JMeter never sets, so every run
    read "JMeter Load Test".
  - **D13** compares the dashboard's numbers with JMeter's own. After the run
    ends it captures the dashboard's final catch-up. Then JMeter's Aggregate
    Report calculator (`SamplingStatCalculator`, via
    `scripts/perf/jmeter-aggregate.groovy`) runs over the same `results.jtl`
    inside the image, and `scripts/lib/dashboard-vs-jmeter.groovy` compares
    them field by field: 208 values in 16 rows.
    - TOTAL is compared with JMeter's live view: top-level samples, so each
      transaction counts once.
    - Every row is compared with JMeter's figures for that label in the JTL,
      the transaction's steps included.

    Samples, errors, min, max and p50/p90/p95/p99 must be equal; mean, error %,
    throughput and KB/s must agree within the wire's 7 significant digits. The
    failure names the first wrong value. Shown to fail on a build whose
    per-sampler mean was off by 1 ms, and on one that counted transaction steps
    in TOTAL.
  - **D14** checks the two lists a real run builds from JMeter's own results.
    The Debug Sampler must be hidden by the default patterns. The hierarchy must
    map exactly the two steps of the parent-sample transaction to it; the other
    transaction's steps arrive on their own and must not appear. Shown to fail
    on a build that lost the default hide patterns.
  - **D15** compares the Timeline's grouped points with JMeter's numbers for the
    same clock period. At 6s/10s/30s/1m a point is one period [kG, (k+1)G) of
    sample end times, which is how JMeter's own over-time graphs define a point
    (`TimeStampKeysSelector`). JMeter's calculator computes every period from
    `results.jtl` inside the image (`scripts/lib/jmeter-buckets.groovy`).
    `scripts/lib/chart-vs-jmeter.mjs` then loads the real `index.html` with the
    run's final catch-up and compares every plotted point: Mean, Max, throughput
    and error % for each sampler and the total, and P95 at 1m, where the page
    draws the server's exact minute values.
    - The end time is the sample's real end, start + elapsed + idle. A
      transaction that excludes its timers reports them as idle time; JMeter's
      HTML report, which rebuilds the end as start + elapsed, places it earlier
      by its timers.
    - Throughput is computed as JMeter's report computes it: the period's count
      over the period's length, the run's partial first and last periods
      included. A period without samples has no point, as in JMeter's report,
      and any value drawn there is a mismatch. Transaction steps are not
      compared: they are charted in their parent's slot, by decision.

    Shown to fail on the pre-fix page, whose points were groups counted from the
    window's start: 578 values differed. Needs Node and the Playwright install in
    `tests/playwright`.

## Design notes

**Playwright runs fully parallel.** It used to be pinned to `workers: 1`
because the mock server broadcast every `/test/send-*` to *all* connected SSE
clients, so concurrent specs fed each other data. Each worker now starts its
own server on its own OS-assigned port
([`fixtures.js`](../tests/playwright/fixtures.js)) and `baseURL` points at it.
Specs needed no changes: they navigate to `/` and drive the server through
page-relative `fetch`, so every request lands on that page's own origin. The
suite went from ~2.9 min to ~26s.

**Every Playwright test fails on an error in the page**, whatever it asserts:
`fixtures.js` collects uncaught exceptions and the page's own `console.error`
lines from every tab in the test's context. The dashboard catches a failed
chart redraw and only logs it, so before this a broken Throughput chart passed
every test that looked for the chart's element: 20 tests caught an injected
fault, and 32 do now. The browser's own `Failed to load resource` lines don't
count, because the server-gone tests expect them. Warnings never count.
Before it checks, it lets each page finish the work its last click put off
(see `settled` below), so a redraw that fails after a test's last step still
fails the test. `page-errors.spec.js` pins both kinds of error, the failed loads
that don't count, and that wait. Pages opened in a separate
`browser.newContext()`, the exported-snapshot views, aren't watched.

**Open the dashboard with `openDashboard(page)`** ([`setup/dashboard.js`](../tests/playwright/setup/dashboard.js)),
not `goto` plus a sleep. It waits until the mock server's catch-up is in the page, about 10 ms.
The fixed 300-800 ms sleeps it replaced were 90 s of the suite's ~400 s, and on a busy
machine they were too short: a regression once timed out there.
Send data the same way: `sendToPage(page, type, body)` (`'bucket'`, `'catchup'` or `'event'`)
returns once the page has handled the message and drawn it, about 12 ms, where specs slept
120-1000 ms; a check that something did *not* change then runs after the message, not before.
After a click, wait with `settled(page)` where the next step needs the page's put-off work done:
a tab draws its charts 50 ms after it is shown, a filter or display option redraws 150 ms after
the click. Every test's pages count those short timers, so it returns as soon as the work is
done, 70-200 ms measured. A check that retries by itself needs no wait at all. Under
`page.clock` the page's timers are the clock's and can't be counted, and `settled` says so
rather than wait. No spec sleeps a fixed time, not even to show that nothing happens. The
no-reconnect test drops the connection the way the real server does when it stops after
`serverClosing` (the mock's `/test/end-clients`), then runs the page's clock past the 3 s
reconnect. Before that, the mock never dropped the connection, so the test passed with the
reconnect bug in place. The cancelled-Stop test records the page's requests in the page itself:
the stop is sent in the click handler, so the record is complete once the click returns.

`snapshot-export.spec.js` opts out with `describe.configure({ mode: 'default' })`
— its tests share one exported snapshot built once in `beforeAll`, which
only makes sense within a single worker.

**`@Tag("slow")`** marks six tests in `DashboardServerTest`, five of them
idle-timeout tests.
Their waits *are* the assertion — the behaviour under test is how long the
server takes to give up — so they cannot be mocked away, and they cost ~28s.
Smoke skips them; regression runs them. They guard the path that decides
whether a headless JMeter run ever exits, so don't delete them to save time.

**Long `.jmx` plans are out of automation** because they are load scenarios,
not pass/fail tests. Note that `full-load-test.jmx` and `report-stress-test.jmx`
contain no `LiveDashboardListener` at all — they never exercised this plugin.

## Platform notes

- **`docker -v` needs care under Git Bash.** MSYS rewrites POSIX-looking
  arguments into Windows paths before `docker.exe` sees them, mangling the
  *container* side of a mount — JMeter ends up handed
  `/test/C:/Program Files/Git/test/docker-smoke-test.jmx`. Disabling the
  rewriting wholesale is not an option either: Maven's launcher needs it and
  dies without. `scripts/lib/common.sh` handles this with `docker_run()`
  (scopes `MSYS_NO_PATHCONV` to the docker process alone) and
  `docker_host_path()` (`cygpath -m` for the host side). Use those helpers —
  a bare `docker run` with mounts will break on Windows again.
- **Quote `-J` arguments in PowerShell.** Unquoted, PowerShell splits
  `-Jlive.dashboard.bind=0.0.0.0` at the first dot and JMeter reads the property
  name as just `live` — the setting is then silently ignored.
- **Config precedence is not uniform.** Saved element values beat `-J` for
  `port`/`interval` and most other settings, but `-J` beats the saved value for
  `bind` and `open.browser` (deliberately — see `LiveDashboardListener.getBindConfig()`
  and `shouldOpenBrowser()`). Both describe the machine, and the GUI writes both into
  every saved plan, so automation never has to rewrite a `.jmx` to stop a browser
  opening or to make the dashboard reachable from another host.
- **A dashboard left open in a browser keeps the server alive.** Its `EventSource`
  holds the server past its idle timeout. When a new run in the same JMeter starts,
  the tab gets `serverClosing` and stops reconnecting. When the connection drops
  without it (JMeter killed), the tab retries every 3 s and attaches to whatever
  serves that port next, and holds that server too. That is intended behaviour, but
  it can look like a hang. The Docker E2E sidesteps it by defaulting to 7599 and
  refusing to start when its port is already answering.
