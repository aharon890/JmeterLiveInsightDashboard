# TODO — Known Issues & Future Work

## Port Management (needs careful design)

- Server MUST stay alive after JMeter exits so user can review the report in browser
- Server MUST be stopped when a new test starts (port reuse)
- JVM shutdown hook approach was tried and reverted — it kills the server when JMeter exits, preventing post-exit review
- The correct approach: only stop the server when starting a new test, NOT on JVM shutdown
- Port auto-increment (7566-7575) is the fallback for stale ports from killed processes
- Windows TIME_WAIT may hold port for 30-240s after server.stop() — auto-increment covers this
- Consider adding brief delay or port-probe between stop and start in testStarted()

## Known Bugs

### An Apdex threshold above 1500 ms leaves no tolerating band

Found in the 1.6.0 review, not fixed. `live.dashboard.apdex.threshold` overrides T, but
F stays JMeter's `jmeter.reportgenerator.apdex_tolerated_threshold` (1500 ms by default),
so T above 1500 makes F < T and every sample over T counts as frustrated, with no
warning. Before 1.6.0 the band was 4T. Candidate: warn at start when T > F, as JMeter's
report generator would need the same pair set consistently.

### Each catch-up adds another window resize listener

Found in the 1.6.0 review, older than 1.6.0. `initCharts()` adds a `resize` listener on
every call (each catch-up, reconnect and theme toggle) and never removes the old one, so
a long-lived tab resizes its charts several times per window resize. Harmless until a tab
has reconnected many times.

### Open mini-charts ignore Palette, Metric and Granularity changes

Found 2026-10-19 in 1.6.2, reproduced, not fixed. The filter controls redraw the main
charts and the Samplers table (`debouncedRefresh`), not an expanded sampler's mini-charts,
and switching tabs doesn't redraw them either:
- Metric and Granularity: while the test runs they reach the mini-charts at the next
  update; after it ends, never (`refreshDetailCharts` skips an ended run), until the row
  is collapsed and re-expanded.
- Palette and custom colours: applied only when a mini-chart is built, so an open one
  keeps its old colour.

FEATURES.md and the Timeline tutorial describe the intended behaviour. Candidate: redraw
the expanded rows from `debouncedRefresh`, forced past the ended-run guard as
`refreshAfterBucket` does, and set the series colour on each redraw.

### The Clock/Elapsed toggle doesn't redraw the Errors table

Found 2026-10-19 in 1.6.2, reproduced, not fixed. `toggleTimeDisplay` redraws the charts
only. The Recent Errors table's Time column is rebuilt at the next update, and after the
test ends there is none, so the column keeps the old format until reload. FEATURES.md
describes the intended behaviour. Candidate: call `renderErrorTable` from the toggle.

### A snapshot's Throughput chart always draws Σ Total

Found 2026-10-19 in 1.6.2, reproduced, not fixed. With more than one sampler, the exported
snapshot adds the dashed Σ Total line to Throughput even when Σ Total was unchecked on the
live page, which leaves it out. The snapshot's Response Time chart follows the setting.
Candidate: give the snapshot's Throughput builder the same `samplerVisibility` check its
Response Time builder has.

### Dragging inside a Timeline chart moves the card instead of panning

Found 2026-10-19 in 1.6.2, reproduced in Chromium and Firefox, not fixed. The whole
Timeline card is `draggable` so cards can be reordered, and the browser starts that drag
as soon as the mouse moves inside the chart, so a zoomed chart never pans. The same drag
pans the Summary chart, whose card is not draggable. The Timeline tutorial describes
drag-to-pan. Candidate: let only the drag grip (left of the title) start the reorder.

### Distributed runs: the timeline is distorted under JMeter's default sample sender
- Totals, percentiles, errors and thread counts are correct across engines
  (Docker D9–D11). The *per-interval* charts are not, by default: remote engines
  send samples in batches of 100 (`mode=StrippedBatch`). Measured before slots
  followed sample end times, with two engines at ~45 samples/2s, samples per
  bucket read `2 0 0 0 0 0 200 0 0 0 100 100 0 0 0 100 115`.
- Slots now follow each sample's end time and stay open for one slot of grace,
  so a batch arriving within that lands in its own slots. A batch held longer
  goes into the oldest open slot and is counted in `lateSamples`. Not
  re-measured on a distributed run.
- `-Jmode=StrippedAsynch` on the controller sends samples as they happen and
  gives a true timeline (`4 18 34 45 40 44 50 41 …`), same totals.

## Current Limitations (measured 2026-10-02)

What the perf harness (`scripts/perf/`) measured on the 1.4.0 jar (not re-measured on
later versions), and what to
optimize next. Each scenario writes its report under `test-results/perf/`.

### Browser at many samplers
- Opening the Timeline tab at 1,000 samplers draws 5 charts x 1,000 series at once
  (~1.4 s long task). The Samplers table is 18,000 DOM nodes at that size.
- Fine at 30 samplers: worst long task 95 ms over a 1 h run.

### Reload after a long run at many samplers
- Reloading after 1 h at 1,000 samplers takes ~34 s in the browser (one 4.5 s long
  task, 666 MB browser heap): the catch-up carries 1,800 slots x 1,000 samplers.
  Serving it no longer costs JMeter heap (streamed). Options: send the 2 s tier for
  a shorter recent span and the 1-minute tier for the rest, or load history lazily.
- At 30 samplers a reload after 1 h is 2.8 MB on the wire and takes ~1 s.

### JMeter heap at many samplers
- At 1,000 samplers the plugin holds ~150 MB after 1 h (live set measured with
  mixed GCs; JMeter alone ~40 MB): at the budget, with the default 1 GB heap.
  Whether it stays flat after the 1 h window fills is not proven (only 10 min of
  data past the hour). The 1-minute tier adds ~3 MB per hour at 1,000 samplers.

### Not measured yet
- Runs longer than 3 h (12, 24 h). Memory is bounded by design (fixed 1 h live
  window, 1-minute whole-run tier); 3 h runs at 30 and 100 samplers met every
  criterion (2026-10-03), but nothing longer has completed.
- Update size at 1,000 samplers is ~10.5% of the old size at p50 (84 KB on the
  wire), just above the 10% target; max 130 KB.

## Design Constraints (deliberate)

### Percentiles are exact only up to 10 s — fixed, not configurable
- `ExactHistogram` keeps one counter per millisecond up to 10,000 ms, so every
  percentile under 10 s matches the Aggregate Report exactly. Above that,
  counters are 0.1% wide and a percentile reads at most 0.1% low (a true p99 of
  23,456 ms shows somewhere in 23,433-23,456).
- The bound caps memory, and raising it costs most in an overloaded test, where
  slow responses spread widely — while heap at 1,000 samplers is already at its
  budget. A 60 s bound would cost up to ~3x per histogram that reaches it.
- No setting: nobody would know to change it, and 0.1% above 10 s does not
  change what anyone reads off the dashboard.

### Stop and Shutdown do not work on distributed runs — not fixed, by decision
- Verified with a real two-engine run (Docker): `/api/stop` returns
  `200 {"status":"stopping"}` and the engines run to their scheduled end.
  `StandardJMeterEngine.stopEngine()` acts on a static singleton that only a
  *local* engine sets; on a controller driving `-R` engines it is null.
- `/api/stop-now` would `System.exit(0)` the controller. Not run; from reading
  the code, the remote engines would keep generating load.
- Deliberately left alone: the plugin is a reporter, and distributed test
  control is documented as unsupported in the release notes. The dashboard's
  job on a distributed run is correct numbers, which it now gives.

### SLA stays two global thresholds — no rule engine, by decision
- The dashboard is a live view for a person watching a test, not a pass/fail
  gate. The two optional thresholds (`sla.default.p95`, `sla.default.errorRate`,
  both off by default) give the reference lines and badges that viewer needs.
- A per-sampler rule language was planned (a parser, per-sampler rules,
  sustained-violation timers, WARN-progress display) and dropped as over-engineering.
  The existing browser notification on an SLA FAIL stays.
  Pass/fail gating belongs to the post-test report or CI, not the live view.

## Ideas (not started)

### Filter samplers by type, not only by name
- Raised 2026-10-11. Let the operator show or hide samplers by kind ("HTTP
  samplers only", "hide every JSR223") alongside the name-based hide patterns.
- To check first: an HTTP sampler's result is an `HTTPSampleResult`, but JSR223,
  Debug and JDBC results are all plain `SampleResult`. The type would have to
  come from the sampler that produced the result (the sampling thread's current
  sampler at `sampleOccurred`), not from the result itself.

### Check the charts' numbers against JMeter, not only the table
- Raised 2026-10-11. D13 compares the table: TOTAL and every sampler row at the
  end of the run. D15 now compares the Timeline's points at 6s/10s/30s/1m with
  JMeter's numbers per clock period: Mean, Max, throughput and error % for each
  sampler and the total, and P95 at 1m, which the page takes from the 1-minute
  slots. Transaction steps are left out. No check compares the single 2 s slots,
  their percentiles, or the error summary behind the pie and bar. Extend the
  checks to them. Group the JTL rows by the slot holding their
  end time (start + elapsed + idle), run JMeter's `SamplingStatCalculator` over
  each group, and compare TOTAL and every sampler field by field. Rates per slot
  are count or bytes divided by the slot width, as JMeter's over-time graphs do.
- Measured 2026-10-11 on a run with transactions, an assertion failure and a
  hidden sampler: 3,375 values in 17 slots, all equal to JMeter's except 37, all
  on one transaction step. Not a bug: two deliberate rules the check has to
  expect.
  - A step of a transaction that generates a parent sample is charted in the
    slot where its transaction ended (`LiveAggregator.addSample`), so a
    transaction and its steps share an interval. JMeter's HTML report draws each
    step at its own end time. Kept by decision: run-level numbers are unaffected,
    and JMeter hands over the steps only when the transaction finishes. A slot
    closes one slot after it ends, so a step that ended 2-4 s before its
    transaction could not go back into its own slot anyway.
  - The error pie counts a failed step and its failed transaction both (14 in
    the pie against 8 in the headline in that run), as JMeter's report does when
    it loads the JTL.
- The 1-minute slots matched too, but that run fit in one minute; checking that
  tier needs a run of a few minutes.

### Security hardening: host names, CORS, framing, CSV formulas
- Raised 2026-10-11 by the pre-public review. More research is needed before
  a design is chosen.
- Measured 2026-10-11 against a real run on the default `localhost` bind,
  sending the requests a hostile page would send:
  - **DNS rebinding.** No handler reads the `Host` header. `GET /` with
    `Host: attacker.example:<port>` was served, stop token included. A
    `POST /api/stop-now` with that host and the token answered 200, and JMeter
    exited a second later with exit code 0. The log said only "Shutdown
    requested from dashboard". The stop token blocks ordinary cross-origin
    pages, as designed. A rebound page is same-origin to the browser, so the
    token does not help.
  - **Cross-origin reads.** Every JSON response, the SSE stream and the error
    responses carry `Access-Control-Allow-Origin: *`. Any page open in the
    operator's browser can read the test name, sampler names (often internal
    URLs) and error messages. The dashboard is same-origin and does not need
    the header. `testCorsHeaders` pins the wildcard, and the comment on
    `serverInstanceId` relies on it.
- Not verified: the browser half. Chrome's Local Network Access should block a
  public page reaching loopback, or ask first; Firefox and Safari were not
  checked.
- Candidate design, from the review:
  - On a loopback bind only, accept `localhost`, `127.x.x.x` and `[::1]` on
    any port, so SSH tunnels keep working, and answer 403 otherwise.
    webpack-dev-server and Vite defend the same way. On `0.0.0.0` or a named
    interface there is no check, so `http://loadgen01:7566` keeps working; the
    docs should say that anyone who can reach that port can read and stop the
    run.
  - Drop the wildcard everywhere. curl and scripts are unaffected, since only
    browsers enforce CORS. Only a page on another origin reading the API in a
    browser would break, and no such use is documented.
- To research:
  - Reverse proxies in front of a loopback-bound dashboard would have to send
    `Host: localhost`.
  - Whether an opt-in `live.dashboard.allowed.hosts` is needed.
  - Whether non-loopback binds need rebinding protection too. An internet
    attacker cannot reach a load generator on a private network directly, but
    can through the operator's browser.
  - What each browser blocks today.
- Two more items from the same review, recorded as the review states them.
  Neither was verified.
  - **Framing.** The page sends neither `X-Frame-Options` nor a
    `frame-ancestors` policy, so another site could load it in a hidden frame
    and trick the operator into clicking Stop or Stop Now. The `confirm()`
    dialog is the only safeguard. Candidate: `frame-ancestors 'none'` plus
    `X-Frame-Options: DENY` on the page. To research: whether anyone embeds
    the dashboard in a page of their own, which this would break.
  - **CSV formulas.** Neither CSV export (samplers, errors) neutralises a cell
    that starts with `=`, `+`, `-` or `@`. An error message from the system
    under test can then run as a formula when the file is opened in a
    spreadsheet. Candidate: prefix such text cells with `'`, as OWASP
    recommends. To research: the prefix changes the text a developer reads,
    and numbers must be left alone.

### Playwright checks that pass when the page is broken

Measured 2026-10-17: each test below passed 3 of 3 runs with the page broken in
exactly the way it claims to check (`tmp/waits/breaks5.py`, not committed). Each
needs a check of the actual effect instead.

- `idle-timeout`: "shows DISCONNECTED when SSE connection drops" closes the connection
  quietly and accepts LIVE. Drop it with `/test/end-clients`, expect DISCONNECTED, run
  the page clock past 3 s, expect LIVE again.
- `live-updates`: "resume applies pending data" re-sends the data after Resume; "error
  shows in error table" looks for a 500 the opening data already has.
- `samplers`: "search filters rows" checks text that is there before searching. The
  snapshot's sort test only counts rows.
- `timeline`: "charts have PNG export toolbox" only checks the chart is visible.
- `sla` / `samplers`: the per-row SLA badge tests run on data with no SLA results, so
  every row says OK (the "fixture has WARN" comment is wrong).
- `filters`: "sampler filter pills rendered" expects 3 or more where there are 4.
- `interactions`: "All/None updates charts after test ends" passes without a redraw;
  "max 3 expanded enforced" tests a limit removed in PR #56.

The plan also listed exact card and Σ-row values, the 403 stop path, and fixtures
generated from a real Java run.
