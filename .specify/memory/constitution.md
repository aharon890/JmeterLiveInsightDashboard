# JMeter Live Insight Dashboard Constitution

## Core Principles

### I. The Numbers Match JMeter's Own

- JMeter's Aggregate Report and HTML report are the reference for every metric. Where the
  dashboard disagrees with them, the dashboard is wrong.
- Slots follow JMeter's timing model: a sample belongs to the slot holding its end time, slots
  are aligned to the clock, and rates divide by the nominal slot width.
- Percentiles are exact, computed the way JMeter's `StatCalculator` computes them. No
  approximating sketch is used.
- New reporting logic MUST NOT be invented where JMeter already defines the behaviour.

Rationale: the plugin is a reporter; testers compare it with the tools they already trust.

### II. One Jar, No Infrastructure

- The deliverable is one shaded jar dropped into `$JMETER_HOME/lib/ext/`. Nothing else is
  installed and no external service is required.
- Nothing is bundled: every dependency comes from JMeter at runtime. A class outside
  `com/jmeterinsight/livedashboard/` in the jar fails the test tiers.
- The front end stays a single file served from the jar; Java 11 is the target.
- The jar in `lib/ext/` MUST match `src/` whenever the plugin is exercised.

Rationale: a listener that needs setup beyond copying one file does not get used.

### III. Verification Is Local and Complete

- There is no CI by deliberate decision. A local run is the only verification that happens.
- A change is finished only on a green regression of its exact content. A skipped layer is a gap,
  not a deferral; a run with a skip records nothing.
- Pushing requires the green marker for HEAD; merging goes through `scripts/merge-pr.sh`. The
  gate is never bypassed by the assistant.
- Results are read as counts of tests run, passed, failed and skipped, never as an exit code
  alone.
- Behaviour that only real JMeter can show (cloned listeners, distributed runs) is pinned in the
  Docker layer.

Rationale: nothing on the server re-checks a branch, so anything not run locally was not run.

### IV. Branch-First, One Version, Bash as the Source of Truth

- Direct pushes to `main`/`master` are blocked. Work happens on a branch and lands through a pull
  request.
- The version has one functional home, `<version>` in `pom.xml`. Releases go through the
  `_perf_release` skill and carry no `-SNAPSHOT` suffix.
- Bash scripts hold the logic; every `.bat` is a thin wrapper.

Rationale: each rule removes a hand-edit that has gone wrong before.

### V. Deliberate Behaviour Stays Deliberate

- Behaviour that looks wrong at first glance and is pinned by a test or recorded in
  `docs/TODO.md` MUST NOT be "fixed" without the owner's decision. Current examples: the server
  outliving the test, the `open.browser` and `bind` precedence exceptions, static lifecycle
  state, the 10-second exact-percentile bound.
- Comments explain why, and are updated when the reasoning changes.
- A spec that proposes changing such behaviour MUST name the decision it overturns.

Rationale: these choices each cost a real failure to learn; a tidy-up that undoes one repeats it.

### VI. Every Setting Reachable by the User

- Every dashboard property MUST be settable per run through a JMeter property, following the
  documented precedence.
- Names of properties, statuses and files are long and self-explaining.
- User-visible behaviour is documented under `docs/` in the same change that introduces it.

Rationale: testers run this on plans and machines the authors never see.

## Technical Constraints

- Embedded JDK `com.sun.net.httpserver`, metrics pushed over SSE, a REST surface under `/api`.
- Two aggregation tiers fed as samples arrive: a live tier at the update interval and a
  one-minute tier for the whole run. Closed slots are frozen to plain numbers.
- Distributed runs are aggregated correctly and otherwise unsupported.
- All documentation lives in `docs/`; the repository root holds only `README.md`, `CLAUDE.md`,
  `LICENSE`, `NOTICE` and build and git files.

## Development Workflow

1. `/speckit-specify` describes what is wanted and why, in `specs/<number>-<name>/`;
   `/speckit-clarify` when anything is open.
2. `/speckit-plan`, checked against this constitution, then `/speckit-tasks`.
3. `/speckit-implement` on a feature branch, then `/speckit-converge`.
4. Rebuild and deploy with `scripts/build.sh`, run `scripts/test-smoke.sh` while working and
   `scripts/test-regression.sh` on the finished, committed content; merge with
   `scripts/merge-pr.sh`.

`specs/` is the one place where a feature's specification, plan and tasks are committed. Other
plans, reviews and session notes are still not committed, and no archive folder is made for them.

## Governance

- This constitution takes precedence over other working habits in this repository. `CLAUDE.md`
  holds the day-to-day guidance and the reasoning behind each rule, and MUST stay consistent
  with it.
- Amendments are made through `/speckit-constitution`, on a branch, with the owner's approval.
- Versioning follows semantic versioning: MAJOR for a removed or redefined principle, MINOR for a
  new principle or section, PATCH for wording.
- Every plan MUST include a constitution check; a deviation is written down with its reason in
  the plan.

**Version**: 1.0.0 | **Ratified**: 2026-11-19 | **Last Amended**: 2026-11-19
