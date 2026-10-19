---
name: run-tests
description: Run this project's local test automation (smoke or regression tier) and report what passed or failed. Use after finishing a feature, change, or bug fix, when the user asks to verify/test/check their work, before pushing a branch, and before merging a PR to main/master. Also use when the user asks to run "the automation", "the suite", or "the tests".
---

# Run the local test automation

**There is no CI. This repo deliberately runs all of its automation locally.**
Nothing on GitHub re-checks a branch — no workflows, by design. So a local run
is not a preview of a verdict handed
down later; it *is* the verdict, and a layer that does not run locally does not
run anywhere.

Two consequences that shape everything below:

- **A skipped layer is a gap, not a caveat.** There is no second chance to catch
  it. Never describe a run with a SKIP as green.
- **You must run the automation at the two points below without being asked.**
  Nothing else will.

## When to run it

| Trigger | Tier | Why |
|---|---|---|
| A feature is finished, or a bug is fixed | **regression** | The change is done; verify all of it, including real JMeter |
| Before merging a PR to `main`/`master` | **`scripts/merge-pr.sh`** | The merge gate — merges only on a fully green regression of this exact content (reuses the pre-push run) |
| Mid-change, want fast feedback | smoke | Cheap signal while iterating |
| User says "run the tests" with no qualifier | regression | Assume they mean the real gate |
| Touched only `tests/playwright` | `npm test` in that dir | Nothing else can be affected |
| Touched only one Java class | `mvn test -Dtest=<Class>` then smoke | Narrow, then confirm |

Never invent ad-hoc test commands when a tier covers it.

## 0. Preflight

**Run the tiers through the Bash tool, not PowerShell.** The `.sh` scripts are
the single source of truth (`scripts/*.bat` are thin wrappers), and PowerShell
splits unquoted `-J` arguments at the first dot, so a JMeter property silently
becomes `live`.

**Line-ending churn is not a change.** If `git status` shows a `.jmx` (or any
text file) as wholly modified, check before acting on it:

```bash
git diff --ignore-cr-at-eol --stat <file>     # empty => line endings only
```

An empty result means there is no content change and therefore no reason to
rebuild anything. Don't report it as a pending edit either.

## 1. Rebuild the jar first if `src/main` changed

```bash
git status --short src/main
```

If anything under `src/main` is genuinely modified, run `./scripts/build.sh`
**before** testing. The Docker E2E builds its own jar, but a stale
`$JMETER_HOME/lib/ext/*.jar` makes manual JMeter runs silently exercise old
code. Skip this step if only tests/docs/scripts changed.

**`build.sh` deploys to one JMeter install, the project-local one by
default.** If JMeter is also launched from another install, `deploy_plugin`
prints a `WARNING: another JMeter install has a plugin jar OLDER than src/`
when they have drifted; answer it with a second deploy:

```bash
JMETER_HOME=/path/to/other/apache-jmeter ./scripts/build.sh
```

If a dashboard ever looks like it is running pre-fix code, the `jmeter.log`
line `ClassFinder: Will scan jar ...` names the jar actually loaded — that
path and version is the truth, whatever the source tree says.

This cuts both ways at the end of a run: **a green regression does not mean the
deployed jar is current.** The Docker layer builds its own, so
build → edit `src/main` → regression goes green while `lib/ext` still holds the
pre-edit jar. `warn_stale_deploy()` now prints a warning when that has happened —
if you see it, run `./scripts/build.sh` and say so in your report.

## 2. Run it

```bash
./scripts/test-smoke.sh          # ~35s  : Java fast tier + shaded jar + Playwright @smoke
./scripts/test-regression.sh     # ~3.5m : full Java + full Playwright + Docker E2E
```

Both print a PASS/FAIL/SKIP summary and exit non-zero on failure. Run from the
repo root, through the **Bash** tool.

If Docker isn't running, regression records the container layer as **SKIP**, not
PASS. Report that honestly — it means the only layer exercising real JMeter did
not run, and with no CI it will not run later either. Offer to start Docker
Desktop and re-run. `SKIP_DOCKER=1` exists for when you knowingly want the other
two layers, not for getting to a green-looking summary.

**Commit before running regression.** A fully green regression also records the
tree hash of HEAD in `test-results/.regression-green`. That needs every layer to
PASS, nothing skipped, and a working tree with no uncommitted or untracked changes.
A dirty-tree run tested content no commit holds, so it records nothing. The push gate
(`.claude/hooks/pre-push-gate.sh`, a PreToolUse hook wired in
`.claude/settings.json`) blocks `git push` until that marker matches the
content being pushed — tree, not commit sha, so a merge commit with an
identical tree stays green. If a push is blocked, the fix is to run
regression — never `PUSH_GATE_BYPASS=1`, which exists for the user to invoke,
not you. `merge-pr.sh` accepts the same marker, so the commit you pushed is not
tested a second time at merge.

## 2b. Merging a PR to main/master

Use the gate, not `gh pr merge` directly:

```bash
./scripts/merge-pr.sh            # infers the PR from the current branch
./scripts/merge-pr.sh 11         # or name it
```

It refuses to start unless the tree is clean, local `HEAD` matches the PR head,
and the branch already contains `master`. Together those make `HEAD`'s tree the
merge result.
- If a fully green regression is already recorded for that tree (the one run
  before pushing), it merges on that.
- Otherwise it runs the full regression itself, with Docker required, and merges
  only if every layer passed with no skips.

If it blocks because the branch is behind `master`, merge master in and re-run;
don't work around it. Testing a branch that lacks recent master commits verifies
the branch, not the merge, which is the whole point of the gate.

Direct pushes to `main`/`master` are still blocked by `.githooks/pre-push`.

## 3. Report results accurately

State what actually ran. Give counts, not vibes — read them off the run's own
output, never from memory or from this file, and phrase it like
"<N> Java + <N> Playwright + D1–D15, all green in <N>s". Each tier prints a
per-layer time; include those when something looks slow. If a layer was skipped,
say so explicitly and say what it would have covered.

Do not claim the suite passed if any layer was skipped or errored.

The tiers warn (without failing) when they exceed a soft time budget — smoke 90s,
regression 300s. Mention it as drift worth noting; it is not a failure and not
something to fix on the spot.

## Diagnosing failures

Work out which layer failed from the summary, then:

**Java** — read `test-results/surefire-reports/*.txt`, **not**
`target/surefire-reports`. The Docker layer runs `mvn clean package`, which
deletes `target/` partway through a regression run, so the tiers copy the reports
aside first (`archive_java_reports`). The copy is the one that still exists by
the time you read it. Re-run just that class:
`mvn test -Dtest=DashboardServerTest`.

**Playwright** — `cd tests/playwright && npm run report` opens the HTML report
with screenshots/video for failures (artifacts are retained on failure only).
Re-run one spec: `npx playwright test live-updates.spec.js`. Use `--headed` to
watch it.

If failures move around between runs, suspect isolation rather than the feature:
each worker owns its own mock server via `tests/playwright/fixtures.js`, and a
spec importing from `@playwright/test` instead of `./fixtures` loses that.

**Docker E2E** — `test-results/docker/jmeter.log` is the JMeter-side log;
`docker logs <container>` for the container side. Common non-bugs:

- **"something is already serving a dashboard on port N"** — the E2E refuses to
  start rather than produce a confusing failure later. Close whatever holds the
  port, or run elsewhere: `DASHBOARD_PORT=7601 ./scripts/test-regression.sh`.
- **D5 (clean exit) fails but everything else passes** — a dashboard tab open on
  that port. `EventSource` reconnects and holds the server alive past its idle
  timeout, which is correct behaviour. The E2E defaults to 7599 (not the
  dashboard's 7566) and preflights the port, so this should now be rare; if it
  happens anyway, check `connectedClients` in `/api/status`.
- **Dashboard unreachable through the published port** — the server bound
  `localhost` inside the container instead of `0.0.0.0`, i.e. a `-J` flag did
  not apply.

## Do not run these as part of verification

The long `.jmx` plans in `test-plans/` (`omtg-test`, `spike-load-test-30min`,
`5min-comparison-test`, `full-load-test`, `report-stress-test`,
`duration-pause-test`) are load scenarios, not pass/fail tests. They take 20+
minutes each and are deliberately excluded from automation. Only run one if the
user explicitly asks for load signal — then use the `docker run` invocation in
`docs/TESTING.md`, and warn about the runtime first. (There used to be a
`load-plans` GitHub workflow; it was removed along with the rest of the CI, so
load plans run locally too.)

Note `full-load-test.jmx` and `report-stress-test.jmx` contain no dashboard
listener at all, so they never exercise this plugin.

## Adding coverage

If the change added behaviour, add a test at the cheapest layer that can see it:
logic → Java unit; dashboard UI → a Playwright spec; anything involving real
JMeter wiring or the jar → the Docker E2E assertions in
`scripts/run-docker-tests.sh`. Tag a new Playwright test `@smoke` only if it
guards a critical path — the smoke tier is a fast gate, not a second full suite.

Then update the inventory table in `docs/TESTING.md`, which is the only place
counts are recorded. Do not add counts anywhere else — they were previously
duplicated across four files and went stale twice.
