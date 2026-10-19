---
name: _perf_release
description: Cut a release of the JMeter Live Insight Dashboard plugin — bump the version, rebuild and deploy the jar, run the full regression, merge through a PR, tag, and publish a GitHub release with the shaded jar attached. Use when asked to release, cut a release, publish a version, ship a build, or "push a jar to releases".
---

# Cutting a release

```
/_perf_release          ->  derive the level from history, then confirm it (step 0)
/_perf_release patch    ->  force  1.2.0 -> 1.2.1
/_perf_release minor    ->  force  1.2.0 -> 1.3.0
/_perf_release major    ->  force  1.2.0 -> 2.0.0
```

**There is no patch default.** Bare `/_perf_release` reads what has been merged since
the last tag, derives a level from it, and asks the user to confirm with that evidence
on the table. A blind patch default would have cut the eleven-merge v1.2.0 as v1.1.1.

Runs to completion once the level is settled: bumps, builds, tests, merges, tags,
and **publishes live**. Budget ~10 minutes. The regression tier runs once, on the bump
commit, and takes ~3.5m. That run satisfies the push gate, and `merge-pr.sh` reuses it.
The release check (`scripts/test-release.sh`, ~5 min) runs once after it.

Stop on the **first** failing layer. Never release around a red suite, and never
"note it in the release notes instead". There is no CI here: the local run is the
only verification that will ever happen, so a layer that does not pass locally does
not pass anywhere.

## 0. Read the current version, derive the level, confirm it

**An explicit argument always wins** — with `patch` / `minor` / `major`, take it and
skip straight to step 1 without asking.

**Current version:** `<version>` in `pom.xml` (see step 2). Read it, never assume it.

**With no argument, derive the level from what has actually been merged**, then
confirm. Never a fixed default.

```bash
git fetch --tags -q
LAST=$(git describe --tags --abbrev=0 2>/dev/null)      # empty on a first release
RANGE=${LAST:+$LAST..}master

# source branch of each merged PR, minus the release PR itself
git log --format=%s --merges "$RANGE" \
  | sed -n 's|^Merge pull request #[0-9]* from [^/]*/||p' \
  | grep -v '^release/'

# breaking markers
git log --format=%B "$RANGE" | grep -nE 'BREAKING CHANGE|^[a-z]+(\([^)]*\))?!:'
```

Only **two** merge-subject shapes exist in this repo, and one of them is noise:

```
Merge pull request #14 from aharon890/fix/post-test-paint-and-stop-button   <- the signal
Merge master into ci/pr-regression-and-windows-smoke                        <- IGNORE
```

The second is an integration merge pulling master *into* a feature branch. Its
subject names the **target**, so a naive parse counts the same branch twice and can
manufacture a signal that was never there. The `sed` above only matches the PR shape,
which is why it is written that way rather than as a loose grep for a branch name.
Drop `release/*` too — the release PR carries the bump, not a change.

Then apply, in order:

| Signal | Level |
|---|---|
| `BREAKING CHANGE` in any body, or a `type!:` subject | **major** |
| any `feat/*` or `feature/*` branch | **minor** |
| otherwise (only `fix/*`, `chore/*`, `docs/*`, `ci/*`) | **patch** |

**Never infer major from branch names.** Only an explicit breaking marker or an
explicit `major` argument produces one — silently doubling the major version is worse
than under-calling a release.

**This repo writes no conventional-commit prefixes.** Subjects are prose ("Latch
test-ended state against stale buffered buckets"), so `git log --format=%s
--no-merges` yields no `feat:`/`fix:` signal at all — verified, it returns nothing
across the whole v1.1.0..v1.2.0 range. **Branch names are the only signal here.**
That is a thin basis, which is why this step confirms rather than committing
silently.

Then use `AskUserQuestion` with the derived level as the recommended option, showing:
current → new version, the level, **the evidence** ("`feat/test-automation-suite` +
`feat/cross-platform-scripts` since v1.1.0 → minor"), and the merge count. An
explicit answer overrides the derivation.

**Honest calibration.** Run against `v1.1.0..v1.2.0` the rule returns **minor**, the
same call made by hand — but off two `feat/*` branches that were both *test
automation*, not user-facing features. It reached the right answer for a shaky
reason. Treat the derivation as a prompt for judgement, not a verdict, and say so
when the `feat/*` branches are internal.

**Known blind spot.** The signal lives entirely in branch names, so a squash-merged
range, or one merged straight from `master`, carries none and will read as patch.
If the range collapses to a few squashed commits, say the level is low-confidence —
that is exactly when to pass it explicitly.

## 1. Preflight — abort if any of these is false

- **Working tree clean** (`git status --porcelain`, untracked ignored).
- **On `master`**, and `master` equals `origin/master` after `git fetch`.
- **`gh auth status` succeeds.**
- **`git config user.email` is the GitHub noreply address**
  (`127844558+aharon890@users.noreply.github.com`). With a private address the push
  is rejected by `GH007: Your push would publish a private email address` — at the
  very end, after the test time has already been spent.
- **Docker is up** (`docker info`). A regression with the container layer skipped
  records no green run, so neither the push gate nor `merge-pr.sh` will accept it. A
  skipped container layer is a gap, not a deferral: it is the only layer that runs
  real JMeter against the real jar.
- **No tag or release already exists for the target version.** `gh release create`
  is not idempotent; if `vX.Y.Z` is taken, stop and report rather than deleting and
  recreating a published release.

## 2. The version has one functional home, and several cosmetic ones

**Functional — the only edit that changes behaviour:**

`pom.xml` line 9, `<version>`. Everything under `scripts/` reads it back out via
`detect_plugin_version()` in `scripts/lib/common.sh`, which derives `PLUGIN_JAR`
from it, so **no script ever needs a hand-edit on a bump.** (It used to: three
`.bat` files pinned the jar name and every release had to touch all three. Do not
reintroduce a hardcoded version anywhere.)

**Cosmetic — docs that spell the jar filename out by hand and must follow:**

- `README.md`
- `docs/Instructions.md`
- `docs/TESTING.md`
- `docs/Tutorial/01-Overview.md` (two lines), then regenerate
  `docs/Tutorial/01-Overview-preview.html` from it (`node tests/playwright/preview-md.js`)
  instead of editing it

Find them rather than trusting this list:

```bash
grep -rn "<old-version>" --include="*.md" --include="*.xml" --include="*.html" . | grep -v node_modules
```

The release check (step 5) fails on any jar name under `README.md` or `docs/` that
isn't the new version.

**No `-SNAPSHOT` on a release version.** Releases are plain `X.Y.Z`. `v1.1.0` had to
be flagged pre-release purely because it shipped a `1.1.0-SNAPSHOT` jar; from
`v1.2.0` on, releases are published as full releases.

## 3. Branch and bump

Branch `release/vX.Y.Z`. Direct pushes to `master` are blocked by
`.githooks/pre-push` — everything lands through a PR.

## 4. Rebuild and deploy to every JMeter install you launch

`build.sh` deploys to the project-local `apache-jmeter-*` by default. If JMeter is
also launched from another install, that one is **not** updated by it:

```bash
./scripts/build.sh                                            # project-local apache-jmeter-5.6.3
JMETER_HOME=/path/to/other/apache-jmeter ./scripts/build.sh   # any other install you launch
```

`deploy_plugin` purges older `jmeter-live-dashboard-*.jar` files from each `lib/ext`
before copying — verify it said so. **Exactly one plugin jar belongs in `lib/ext`:**
JMeter loads every jar in that flat directory, so a leftover copy puts a second set
of the plugin's classes on the classpath and which one wins is not deterministic.
The first `build.sh` also prints a `WARNING: another JMeter install has a plugin jar
OLDER than src/` — that warning is what the second command answers.

Close JMeter first if it is open; Windows locks jars a running JVM has loaded.

## 5. Commit, then run the full regression

Commit the bump, then:

```bash
./scripts/test-regression.sh
```

Every layer must PASS with zero SKIP. A fully green run writes HEAD's **tree** hash
to `test-results/.regression-green`, which is also what unblocks `git push` through
the PreToolUse gate in `.claude/hooks/pre-push-gate.sh`. If the push is blocked, the
answer is to re-run regression — `PUSH_GATE_BYPASS=1` is the user's to invoke, never
yours.

On failure: report the failing layer with its real output, leave the branch in place
for inspection, and stop. Do not push, tag or release. Java failures are in
`test-results/surefire-reports/`, **not** `target/` — the Docker layer runs
`mvn clean package` and deletes `target/` partway through the run.

Then the release check, once, on the same commit:

```bash
./scripts/test-release.sh
```

~5 min. It checks the version in the pom and the docs, builds and checks the jar,
and runs what the tiers never do: Playwright `@smoke` on Firefox and WebKit, and the
Docker gate with JMeter on Java 11, the oldest Java the jar is built for. Every check must PASS; it reports an
unavailable layer as FAIL, not SKIP. On failure, stop as for regression. It writes
no marker and changes no files, so the regression marker stays valid.

## 6. Merge through the gate — never `gh pr merge` directly

```bash
git push -u origin release/vX.Y.Z
gh pr create --title "Release X.Y.Z" --body-file - <<'BODY'
...
BODY
./scripts/merge-pr.sh <pr-number>
```

`merge-pr.sh` checks that the release branch is the actual merge result, then merges
on the green regression recorded for it. It runs the regression again only if the
content changed after that run, for example because `master` had to be merged in.
Then:

```bash
git switch master && git pull --ff-only
```

## 7. Tag and push the tag

```bash
git tag -a vX.Y.Z -m "vX.Y.Z"
git push origin vX.Y.Z
```

The push gate exempts `--tags` but **not** a named tag ref like this one — it still
compares HEAD's tree against the green marker. That passes here because the merge
commit's tree is identical to the tested branch head's tree (the gate keys on tree,
not sha, exactly for this). If it blocks, something really did change; do not bypass.

## 8. Rebuild the artifact from merged master, then publish

```bash
mvn clean package -DskipTests      # -> target/jmeter-live-dashboard-X.Y.Z.jar
```

Rebuild rather than reusing whatever is sitting in `target/`: the regression run's
Docker layer does its own `mvn clean package` mid-run, so the file there is not
necessarily the tagged content.

The release asset is the **shaded** jar, `target/jmeter-live-dashboard-X.Y.Z.jar`.
Never attach `original-jmeter-live-dashboard-*.jar`, the pre-shading build.

Check the exact file you are about to upload, and record its checksum for the notes:

```bash
bash -c '. scripts/lib/common.sh && assert_jar_sane target/jmeter-live-dashboard-X.Y.Z.jar' \
  && sha256sum target/jmeter-live-dashboard-X.Y.Z.jar
```

`assert_jar_sane` also checks the manifest's `Implementation-Version` against the pom,
which is what the start log and `/api/status` report.

```bash
gh release create vX.Y.Z target/jmeter-live-dashboard-X.Y.Z.jar \
  --title "vX.Y.Z — <one-line summary>" \
  --notes-file - <<'NOTES'
...
NOTES
```

Published, not draft, not prerelease.

Then download the published asset back and compare it with the file you uploaded.
That proves what users get is what was checked:

```bash
d=$(mktemp -d) && gh release download vX.Y.Z -p 'jmeter-live-dashboard-X.Y.Z.jar' -D "$d" \
  && sha256sum "$d"/jmeter-live-dashboard-X.Y.Z.jar target/jmeter-live-dashboard-X.Y.Z.jar
```

The two sums must be equal. Put the sum in the notes (`SHA-256: <sum>` under the
install line). If you add it after publishing, use `gh release edit vX.Y.Z --notes-file`.

**Compose the notes from what changed for a *user*, not from the raw log.** Start
from `git log --oneline --merges <last-tag>..master`, then read the bodies of the
PRs that touched `src/` — this repo writes long, specific commit messages and they
are the raw material. Group into fixes / features / internals; put behaviour the
operator would actually notice first, and keep automation-only PRs to a short
"Internals" paragraph. Match the shape of `v1.2.0`.

Always include the install line, with the purge warning:

> Drop `jmeter-live-dashboard-X.Y.Z.jar` into `$JMETER_HOME/lib/ext/`, **removing
> any older `jmeter-live-dashboard-*.jar` first** — JMeter loads every jar in that
> directory and a leftover copy can shadow the new one.

And carry forward any still-open limitation from `docs/TODO.md` that a user would hit
(currently: the Stop buttons act on the local engine only, so a distributed `-R` run
is not stoppable from the dashboard).

## 9. Report

State the released version, the release URL, the per-layer regression results with
real numbers read off the run, and the merge count the release covers.

Restate the bump level **and the evidence step 0 derived it from** — and if that
derivation was flagged low-confidence (squashed history, or `feat/*` branches that
were really internal), say so. The version is the one thing a published release
cannot take back. If the level used differs from the one derived, repeat both, so a
re-cut is an informed choice.

## Traps

- **Use Bash-tool heredocs, not PowerShell here-strings.** The Bash tool is Git
  Bash: `@'...'@` is not syntax there, it lands in the commit message as a literal
  `@` line. Use `<<'MSG' ... MSG`. (This has already happened once and needed an
  amend.)
- **Run the tiers from Git Bash, never PowerShell.** PowerShell splits unquoted `-J`
  arguments at the first dot, so `-Jlive.dashboard.bind=0.0.0.0` silently becomes
  the property `live`.
- **A green regression does not prove `lib/ext` is current.** The Docker layer
  builds its own jar, so the deployed one can still be stale. That is why step 4
  runs before the tests, not after.
- **`gh release create` is not idempotent.** See preflight.
- **Line-ending churn is not a change.** If `git status` shows a text file as wholly
  modified, check `git diff --ignore-cr-at-eol --stat <file>` before acting.
