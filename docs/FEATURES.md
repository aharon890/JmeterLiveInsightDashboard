# JMeter Live Insight Dashboard -- Feature Guide

> This is the user-facing feature *guide*. Known issues, current limitations
> and deliberate design constraints are in [`TODO.md`](TODO.md).

A zero-infrastructure live monitoring dashboard for JMeter load tests. Add the listener to your test plan, run the test, and a real-time dashboard opens in your browser -- no Grafana, no InfluxDB, no external tooling. All data streams directly from JMeter via Server-Sent Events (SSE).

---

## Dashboard Structure

The dashboard is organized into **4 tabs**:

| Tab | Purpose |
|-----|---------|
| **Summary** | At-a-glance metric cards with trend arrows, Apdex badge, response time chart |
| **Timeline** | Full time-series charts -- response time, throughput, error rate, bytes, connect/latency |
| **Samplers** | Detailed statistics table with sorting, filtering, column control, inline detail expansion |
| **Errors** | Error breakdown charts (pie and bar), error details table |

### Header
A sticky header displays (ordered left to right):
- **Test name** -- the Test Plan's name: from the GUI tree, or in non-GUI mode from the `.jmx` being run (its file name if the plan has no name). `-JtestPlanName=...` overrides it
- **Elapsed time** -- live timer that freezes when the test ends
- **Test status** -- Running / Stopping / Ended
- **Thread count** -- active/max threads
- **SLA badge** -- SLA PASS / SLA WARN / SLA FAIL (hidden when no SLA configured)
- **Apdex badge** -- color-coded score (hidden until data arrives)
- **Colorblind mode toggle** -- eye icon
- **Dark mode toggle** -- crescent/sun icon
- **Live indicator badge** -- LIVE, ENDED, EXPLORING, DISCONNECTED, CONNECTING, or CLOSED

### Footer
A fixed footer with test controls and export actions. All buttons have hover tooltips:
- Stop Test, Shutdown, Pause Updates
- Copy Summary, Export Snapshot

### Filter Bar
A persistent filter bar between the tab navigation and content area with two rows:
1. **Settings row** -- Palette, Line thickness, Metric selector, Granularity selector, Time display toggle, Window selector
2. **Samplers row** -- All/None toggles, Preset controls, sampler checkbox pills

---

## Summary Tab

### Metric Cards
Eight cards arranged in a 4x2 grid, each updating in real-time from cumulative server statistics:

| Card | Value |
|------|-------|
| Total Samples | Running count, exact and grouped (12,345). Every number on the page prints this way on any machine, whatever its language, as in JMeter's report |
| Error Rate | Percentage with 2 decimal places |
| Duration | HH:MM:SS elapsed time |
| Max Threads | Peak concurrent thread count |
| Avg Response Time | Mean response time in ms/s/min |
| P95 | The exact 95th percentile of all samples (the Aggregate Report's TOTAL row) |
| Throughput | Overall requests per second |
| Total Errors | Running error count |

**Duration and Throughput calculation:** Throughput divides by the time from the first sample's start to the last sample's end, as JMeter's Aggregate Report does. Duration counts from the first sample: up to now while the test runs, and to the last sample's end once it has ended.

**Trend arrows:** Six cards (Total Samples, Error Rate, Avg Response Time, P95, Throughput, Total Errors) show directional trend arrows comparing the current cumulative value to the previous update. Arrows are color-coded: green for favorable change, red for unfavorable change. The "good direction" varies by metric -- increasing throughput is good, increasing error rate is bad.

**Error card highlight:** The Error Rate card switches to a red-tinted error style when the error rate exceeds 0%.

### Response Time Chart
A duplicated copy of the Timeline response time chart for quick reference without switching tabs. Supports the same metric selector, zoom, legend toggle, and fullscreen as the Timeline version.

---

## Timeline Tab

Five time-series charts, each with a drag grip handle for reordering:

### Response Time Over Time
Per-sampler response time lines with the configurable metric (Mean, P50, P90, P95, P99, or Max). When the P95 SLA threshold is set, a horizontal red dashed markline shows it while the metric is P95 or P99.

### Throughput Over Time
Per-sampler throughput lines (req/s) on the primary Y-axis. An "Active Threads" dashed line on a secondary Y-axis shows thread ramp-up and ramp-down: the mean active threads per interval, as JMeter's HTML report computes it.

### Error Rate Over Time
Per-sampler error rate percentage with semi-transparent area fill beneath each line. When SLA error rate thresholds are configured, a horizontal red dashed markline shows the SLA limit.

### Bytes Throughput Over Time
Per-sampler received bytes per second, displayed in KB/s.

### Connect Time and Latency Over Time
Per-sampler latency (solid lines) and connect time (dashed lines with reduced thickness), both in milliseconds. Uses the same color for both latency and connect of the same sampler.

### Common Chart Features
All Timeline charts share:
- **Hover tooltips** -- exact values at any data point with sampler name truncation and color dots
- **Zoom and pan** -- mouse wheel to zoom, drag inside the chart to pan, via dataZoom
- **Time range slider** -- draggable slider at the bottom of each chart
- **Legend toggle** -- click legend items to show/hide individual series
- **Legend truncation** -- sampler names capped at 30 characters with hover tooltip for the full name
- **Smooth lines** -- all series use smooth interpolation
- **Fullscreen** -- expand any chart to full viewport; header with Exit button stays visible on top
- **PNG export** -- save-as-image button in the chart toolbox (2x pixel ratio for retina)
- **Cross-chart zoom sync** -- all five Timeline charts are connected via `echarts.connect()`; zooming or panning one chart synchronizes all others
- **Drag-and-drop reorder** -- drag charts by the grip handle to rearrange; order persisted in localStorage
- **Adaptive time formatting** -- X-axis labels use wall-clock (HH:MM:SS) or elapsed (+HH:MM:SS) depending on the time display toggle
- **Smart Y-axis formatting** -- response time axes auto-adapt between ms, s, and min depending on magnitude
- **connectNulls** -- all chart series connect across null/missing data points, handling sparse data from Open Model Thread Groups
- **Dark mode support** -- chart text colors, grid lines, and backgrounds adapt to dark mode

---

## Samplers Tab

### Statistics Table
Full table with the following columns:

| Column | Description |
|--------|-------------|
| Sampler | Name with color dot and SLA badge |
| Samples | Total request count |
| Errors | Total error count |
| Error % | Error rate as percentage |
| Mean (ms) | Average response time |
| P90 (ms) | 90th percentile response time |
| P95 (ms) | 95th percentile response time |
| P99 (ms) | 99th percentile response time |
| Min (ms) | Minimum response time |
| Max (ms) | Maximum response time |
| Throughput | Requests per second |
| Recv KB/s | Received kilobytes per second |
| Sent KB/s | Sent kilobytes per second |

**Cumulative statistics:** The table always shows full-test cumulative values, not windowed data. This ensures consistent reporting regardless of the selected time window.

### Sortable Columns
Click any column header to sort ascending; click again to sort descending. Sort indicators (triangles) appear next to the active sort column.

### Searchable
A text filter box above the table filters rows by sampler name as you type.

### Column Visibility Toggle
A "Columns" dropdown with checkboxes for each data column. Hidden columns are removed from both the header and all data rows. State persists in localStorage.

### Sigma Total Row
A synthetic aggregate row pinned at the top of the table with bold styling:
- **Exact, not derived from the rows:** every value is the server's cumulative statistic over all top-level samples, as JMeter's Aggregate Report computes its TOTAL row. Mean, P90, P95 and P99 are over all samples (averaging the samplers' percentiles would not give a percentile), throughput and KB/s divide by the run's duration, and transaction children are not counted twice.

### SLA Badges
Each sampler row shows an inline SLA badge:
- **OK** -- no SLA configured
- **PASS** -- all metrics under 80% of threshold
- **WARN** -- at least one metric between 80% and 100% of threshold
- **FAIL** -- at least one metric exceeds threshold

Badges are color-coded: green (PASS), amber (WARN), red (FAIL).

### Per-Sampler Detail Expansion
Click any sampler row to expand an inline detail panel with **3 mini-charts**:
- **Response Time** -- per-sampler response time using the selected metric
- **Throughput** -- per-sampler throughput over time
- **Error Rate** -- per-sampler error rate with area fill

Detail charts update in real-time alongside the main charts. Any number of samplers can be expanded at once. Charts are arranged in a 3-column grid below the sampler row. Mini-chart time labels count from the start of the test (30s, 2:15) matching WebInsightReport style; after two hours they switch to the main charts' Clock/Elapsed format. Zooming one mini-chart zooms all of them, every expanded sampler and all three KPIs, as the Timeline's charts zoom together. The zoom is not reset by live data updates: while zoomed the mini-charts hold still, and zoomed back out to the whole run they follow live data again.

### Transaction Controller Hierarchy
Parent and child rows come from JMeter's own results, not from sampler names: when a sample carries sub-results, as a Transaction Controller with "Generate parent sample" produces, each sub-result is a child of that sample. Children are sorted with the other rows, not grouped under their parent:
- **Default view:** Both parent transactions and child samplers are shown
- **"Show Children" / "Hide Children" toggle:** Button in the toolbar to show or hide child samplers. When hidden, child samplers are removed from the table.
- **Child indentation:** Child samplers appear with left padding (24px indent) and a tooltip indicating the parent name

### Name Truncation
Long sampler names are truncated with ellipsis at 350px width. The full name appears in a hover tooltip.

### Color Dots
Each sampler row includes a small colored square matching the chart series color, providing visual correlation between the table and charts.

---

## Errors Tab

### Error Breakdown Charts
Two charts displayed side by side:

**Errors by Response Code (Pie/Donut):**
- Donut chart showing error distribution by HTTP status code
- Labels shown when 8 or fewer unique codes exist
- Hover tooltip shows count and percentage

**Errors by Sampler (Bar):**
- Vertical bar chart showing the top 15 samplers by error count with per-sampler colors
- Sorted by count descending
- Sampler names truncated at 25 characters in axis labels
- Charts refresh automatically on tab switch

### Error Details Table
A table of individual error records with columns: Time, Sampler, Code, Message, URL.
- **Error count** displayed in both the section header and the Errors tab label (e.g., "Errors (42)")
- **Error code badges** -- colored badges for response codes
- **Sampler name truncation** -- names capped at 250px with hover tooltip
- **Message and URL truncation** -- capped at 60 characters
- **CSV export** -- "Export CSV" button downloads every error record the server keeps (up to 100 by default), not only the 50 shown

The server retains up to 100 recent error samples (configurable). The table displays up to 50 at a time.

---

## Sampler Filtering

A persistent filter bar. The sampler pills choose which samplers the charts draw; the Samplers table, the Errors tab and the metric cards always show every sampler.

### Checkbox Pills
Each sampler appears as a pill-shaped label with:
- A checkbox (hidden native input, styled custom box with checkmark)
- A color dot matching the chart series color
- A truncated sampler name (max 200px) with hover tooltip

Checked pills appear at full opacity with the checkbox filled in the primary color. Unchecked pills appear at 45% opacity.

### Select All / None
Quick toggle links to check or uncheck all samplers at once, including the Sigma Total.

### Auto-Hide Patterns
Samplers matching configured patterns (default: `JSR223*`, `Debug*`, `BeanShell*`) are unchecked by default on discovery. They remain in the filter bar and can be manually enabled. In a pattern, `*` matches any characters and `?` exactly one; everything else, brackets and parentheses included, matches literally.

### Sigma Total Virtual Sampler
A synthetic "Sigma Total" entry appears first in the filter bar, separated by a vertical divider:
- Off by default
- When enabled, appears as a dashed line in the Response Time and Throughput charts. Its values are the server's overall figures over all top-level samples, not an average or sum of the sampler lines
- The bold top row of the sampler statistics table is always shown, whether or not this entry is checked
- Uses a fixed dark color (#333333) distinct from the sampler palette

### Filter Presets
Save and restore sampler visibility configurations:
- **Save** -- prompts for a name and stores the current visibility map in localStorage
- **Load** -- select from a dropdown of saved presets; applying a preset restores visibility settings and refreshes all views
- **Delete** -- removes the currently selected preset
- **Preset dropdown** -- lists all saved presets by name

---

## Color and Style Customization

### Palette Selection
Three built-in palettes available via toggle buttons in the filter bar:
- **Default** -- standard ECharts palette (20 colors)
- **Okabe-Ito** -- colorblind-friendly palette optimized for deuteranopia and protanopia (10 colors)
- **High Contrast** -- maximum visual separation using primary colors (10 colors)

### Per-Sampler Color Override
Click the color dot next to any sampler name in the filter bar to open a color picker:
- Grid of swatches from all three palettes plus additional standard colors
- Native color input (`<input type="color">`) for any custom color
- Custom colors persist in localStorage across page reloads
- Color dot updates immediately; charts refresh after a debounce

### Line Thickness
Toggle between three widths for all chart lines:
- **Thin** -- 1px
- **Medium** -- 2px (default)
- **Thick** -- 3px

The Sigma Total line is always 1px thicker than the selected thickness.

### Percentile Metric Toggle
Switch the response time charts between metrics:
- **Mean** -- average response time
- **P50** -- 50th percentile (median)
- **P90** -- 90th percentile
- **P95** -- 95th percentile (default)
- **P99** -- 99th percentile
- **Max** -- maximum response time

The selected metric applies to the Summary chart, Timeline response time chart, and per-sampler detail response time charts.

Palette, line thickness and custom colors persist in localStorage. The metric does not: it is P95 again after a reload.

---

## Dark Mode and Accessibility

### Dark Mode
Toggle via the crescent/sun icon in the header. Full dark theme for all components:
- CSS variables switch to dark palette (dark backgrounds, light text, adjusted borders)
- Charts reinitialize with dark-appropriate text, axis and grid line colors (the Summary and Timeline chart tooltips are dark in both modes, the other charts' tooltips light)
- All UI elements -- cards, tables, badges, filter bar, footer -- adapt automatically
- Persists in localStorage

### Colorblind Mode
Toggle via the eye icon in the header. Swaps semantic green/red colors to blue/orange:
- Pass/OK badges become blue, Fail badges become orange
- Error cards and error code badges switch to orange tones
- Works independently with dark mode, producing **4 combinations**: light, dark, light+colorblind, dark+colorblind
- Persists in localStorage
- Chart line palettes are not affected -- use the Okabe-Ito palette option separately for colorblind-friendly chart colors

---

## SLA and Apdex

### SLA Configuration
SLA thresholds are configured via JMeter properties:
- `live.dashboard.sla.default.p95` -- P95 response time threshold in milliseconds (default: -1, disabled)
- `live.dashboard.sla.default.errorRate` -- error rate threshold as percentage (default: -1, disabled)

Set to -1 to disable a metric. When both are disabled, the header SLA badge is hidden and every row in the Samplers table shows OK.

### SLA Evaluation
Three-level evaluation against thresholds:
- **PASS** -- metric under 80% of threshold
- **WARN** -- metric between 80% and 100% of threshold
- **FAIL** -- metric exceeds threshold

### SLA Display
- **Header badge** -- overall SLA status across all samplers: SLA PASS (green), SLA WARN (amber), SLA FAIL (red)
- **Sampler table badges** -- per-sampler PASS/WARN/FAIL text badge inline with the sampler name
- **Chart threshold marklines** -- horizontal red dashed lines at the SLA limit: on the response time charts while the metric is P95 or P99, and on the error rate chart

### Browser Notifications
When SLA transitions to FAIL status during a running test, a browser notification is triggered:
- Lists up to 3 failing samplers with the violated metric
- The title is "SLA FAIL - " followed by the test name
- Permission is asked for on your first click on the dashboard (Firefox and Safari allow the request only from a click)

### Apdex Score
Apdex (Application Performance Index) is computed from cumulative data exactly as JMeter's HTML report computes it, with the same thresholds, so the badge and the report read the same number:

**Apdex = (Satisfied + Tolerating/2) / Total**
- Satisfied: a successful sample with response time <= T
- Tolerating: a successful sample with response time > T and <= F
- Frustrated: everything else, including every failed sample

Total is every sample the report counts for its Apdex Total: a transaction's steps, redirect hops and embedded resources as well as the samples that hold them, so it covers more samples than the Σ row. A transaction that sampled nothing in an iteration is left out, as the report leaves it out.

T and F are JMeter's report thresholds, `jmeter.reportgenerator.apdex_satisfied_threshold` (500 ms) and `jmeter.reportgenerator.apdex_tolerated_threshold` (1500 ms). F is deliberately not the textbook 4T: that would count samples of 1501-2000 ms as tolerating while JMeter's report counts them as frustrated, and the two scores would disagree. Hovering the badge shows the thresholds in use.

Score from 0 to 1, displayed as a badge in the header, color-coded:
- > 0.94 = green (Excellent)
- > 0.85 = green (Good)
- > 0.70 = amber (Fair)
- <= 0.70 = red (Poor/Unacceptable)

**Configuration:** set JMeter's two report properties (in `user.properties` or with `-J`) and the report and the dashboard both use them. `-Jlive.dashboard.apdex.threshold=300` overrides T for the dashboard only.

---

## Live vs Explore Mode

### Live Mode (Default)
Charts auto-scroll with incoming data. The indicator badge shows "LIVE" with a pulsing green dot.

### Explore Mode
When a user zooms or pans any chart (via mouse wheel, click-drag, or slider), the dashboard enters explore mode:
- All charts freeze at the user's zoom position
- Incoming data continues to accumulate in memory
- Metric cards and the sampler table continue updating in real-time
- The indicator badge changes to "EXPLORING" (blue)
- A floating "Return to Live" button appears at the bottom-right

### Return to Live
Clicking the floating button snaps all charts back to the latest data and re-enables auto-scroll. The badge returns to "LIVE" and the button disappears.

---

## Time Window

A rolling window selector in the filter bar controls how much time-series data is displayed in charts. The Window and Granularity controls have hover tooltips describing their function.

| Option | Window Size |
|--------|-------------|
| 1m | Last 60 seconds |
| 5m | Last 5 minutes |
| 15m | Last 15 minutes |
| 30m | Last 30 minutes |
| Full | The whole run since test start (default) |

**On a finished run:** the window is the last minutes *of the run*. While the test runs it ends now; once it has ended it ends where the run ended, so 1m on a finished run shows its last minute however long ago that was.

**Default is Full:** The window defaults to Full, showing all data since the test started. This changed from the previous default of 5m to provide a complete view by default.

**Whole run, two resolutions:** The live 2-second slots are kept for `live.dashboard.window.max` (1 hour by default). The plugin also keeps the whole run in 1-minute slots, the HTML report's default resolution. In Full, the part of the run older than the live window is charted from the 1-minute slots, so a long test still shows from its first minute, including after a page reload. Those minutes are drawn as they are at every granularity, and so is the minute the 2-second slots begin in once the oldest of its slots have been dropped. Rates are per second at either resolution.

**Nothing is discarded:** the window only slices what is drawn, so switching back to Full loses nothing. At a granularity coarser than 2s the window begins at the start of the period it falls in, so its first point is a whole period. Browser memory is bounded the way the server's is: the page keeps the live slots for `live.dashboard.window.max`.

**Scope:** The window only affects chart data. Metric cards and the sampler statistics table always show full-test cumulative values regardless of the selected window.

---

## Granularity Control

A granularity selector in the filter bar sets how much time each chart point stands for, smoothing chart lines for long-running tests. Coarser than 2s, a point is one fixed period of clock time (10:00:00 to 10:00:30, then 10:00:30 to 10:01:00), which is how JMeter's own report graphs define a point. It is plotted at the start of its period, and once the period is over it never changes, however the window slides.

The buttons stand for 1, 3, 5, 15 or 30 updates, and each is labelled with the time that gives at the run's update interval (`live.dashboard.update.interval`). At the default 2 s:

| Option | One point per |
|--------|---------------|
| 2s | Update: the raw data (finest) |
| 6s | 6 s of clock time |
| 10s | 10 s of clock time |
| 30s | 30 s of clock time |
| 1m | Minute of clock time (coarsest) |
| Auto | Period chosen from the number of points in the window |

At a 5 s interval the same buttons read 5s, 15s, 25s, 1m15s and 2m30s.

**What a point holds:** rates (throughput, KB/s), means, error % and max are exact for its period. A rate is the period's samples over its length, as JMeter's report divides, the run's partial first and last periods included; a sampler that ran in only some of the period's 2 s slots counts as having no samples in the others. A mean is weighted by sample count. A percentile cannot be rebuilt from 2 s percentiles: at 1m the chart draws each closed minute's exact value from the server's 1-minute slots, and at 6s, 10s and 30s it is the 2 s values averaged by sample count, an approximation. While the test runs, the newest point covers its period so far, and at 1m its percentiles are the average until that minute closes.

The Summary tab's Response Time chart and the per-sampler charts in the Samplers tab are grouped the same way.

**Auto mode:** picks the period from the number of points in the window: the raw data below 150 points, then 6s, 10s, 30s and 1m as the window holds more. Changing the period regroups every point.

**Use case:** For tests running longer than a few minutes, coarser granularity produces smoother chart lines and reduces the number of data points the browser must render.

---

## Time Display

A toggle button in the filter bar switches between two time formats on the Summary and Timeline chart X-axes and tooltips, and in the Errors table's Time column (the Samplers tab's mini-charts show short elapsed labels for a run's first two hours either way):

- **Clock** (default) -- wall-clock time in the browser's local time zone, HH:MM:SS (e.g., 14:30:50)
- **Elapsed** -- time since test start with a + prefix (e.g., +00:05:30)

The selection persists in localStorage.

---

## Test Controls

The footer has two control buttons. Both require a confirmation dialog, and both
requests carry a per-server token so a foreign page cannot trigger them.

### Stop Test
Sends a graceful stop request (`POST /api/stop`): currently running samplers
finish before the test ends. Once the request is accepted the button is disabled
and the header status shows "Stopping" until the test actually ends -- data
updates that arrive during the wind-down do not re-enable it.

### Shutdown
Sends `POST /api/stop-now`, which stops the test immediately (in-flight samplers
are aborted) and then terminates the JMeter process itself. Use this to reclaim
the machine; the dashboard server dies with the process.

### Pause Updates
Pauses chart and table updates. When paused:
- Incoming bucket events are buffered (stored in memory)
- Charts and tables freeze at their current state
- Button text changes to "Resume Updates"
- The button displays an amber pulsing animation to indicate the paused state
- On resume, all buffered data is replayed so no data is lost
- The server continues sending events; they are buffered client-side until resumed
- Lifecycle changes are not deferred: test end still updates the status header,
  and a reconnect's catch-up repaints from the server's full history (which
  already contains everything the buffer held)

---

## Export and Integration

### Export Snapshot
Downloads a self-contained HTML file with all current dashboard data embedded as JSON:
- Includes the same tabs: Summary, Timeline, Samplers, Errors
- Charts are fully interactive (zoom, pan, legend toggle) via embedded ECharts
- Sampler statistics table and error table are fully rendered
- Works offline with no server and no network connection — the charting library is embedded, not linked
- Filename includes a timestamp: `live-dashboard-snapshot_2026-03-31T14-30-50.html`
- Preserves the current dark mode setting

### CSV Export
Two CSV export buttons:
- **Sampler Statistics CSV** -- exports all samplers with 13 columns (header row + data rows); the Σ Total row is not included
- **Error Details CSV** -- exports the error records the server keeps (up to `live.dashboard.error.samples.max`, default 100) with 5 columns (Time, Sampler, Code, Message, URL)

CSV files use UTF-8 encoding with quoted fields and escaped double quotes.

### PNG Chart Export
The Summary response time chart and the five Timeline charts include a save-as-image button in the ECharts toolbox (camera icon). Downloads a PNG at 2x pixel ratio for retina clarity. The error charts and the per-sampler mini-charts have none.

### Copy Summary as HTML
The "Copy Summary" button in the footer copies an HTML table to the clipboard containing all 8 metric card values, formatted for pasting into:
- Confluence pages
- SharePoint documents
- Email reports
- Wiki pages

The clipboard contains both `text/html` (formatted table with the test name as caption) and `text/plain` (simple key-value pairs) representations.

---

## Server Configuration

Settings come from three places: the listener's GUI panel (saved into the `.jmx`), `-J`
properties on the command line, and `jmeter.properties`.

**The element value wins; `-J` is the fallback.** A value saved in the plan cannot be
overridden from the command line — if a `-J` property seems to be ignored, that is why.
Two deliberate exceptions:

- **`live.dashboard.open.browser`** and **`live.dashboard.bind`** invert the rule — `-J`
  beats the saved value. Both describe the machine rather than the test, and the GUI writes
  both into every plan it saves, so a headless or shared machine can override them without
  rewriting the `.jmx`.
- **`live.dashboard.enabled`** and **`live.dashboard.error.samples.max`** have no element value
  at all, so they are always driven by property. The SLA, Apdex and idle-timeout settings
  have no GUI field either: set them with `-J`, or by hand in the `.jmx`, where the element
  value still wins.

See [Configuration](Tutorial/10-Configuration.md) for the full precedence rules.

### Server Properties

| Property | Default | Description |
|----------|---------|-------------|
| `live.dashboard.enabled` | `true` | Enable or disable the dashboard listener entirely |
| `live.dashboard.port` | `7566` | HTTP server port (auto-detects next available port up to 7575 if in use) |
| `live.dashboard.bind` | `localhost` | Bind address (use `0.0.0.0` for remote access) |
| `live.dashboard.update.interval` | `2` | Data push interval in seconds |
| `live.dashboard.open.browser` | `true` | Auto-open the dashboard in the default browser on test start |

### Window and Retention

| Property | Default | Description |
|----------|---------|-------------|
| `live.dashboard.window.max` | `3600` | Retention of the 2-second slots, in seconds (1 hour). The 1-minute slots cover the whole run regardless |

### Sampler Filtering

| Property | Default | Description |
|----------|---------|-------------|
| `live.dashboard.hide.patterns` | `JSR223*,Debug*,BeanShell*` | Comma-separated glob patterns for samplers hidden by default |

### SLA and Apdex

| Property | Default | Description |
|----------|---------|-------------|
| `live.dashboard.sla.default.p95` | `-1` | P95 threshold in ms (-1 = disabled) |
| `live.dashboard.sla.default.errorRate` | `-1` | Error rate threshold as % (-1 = disabled) |
| `live.dashboard.apdex.threshold` | JMeter's `apdex_satisfied_threshold` (`500`) | Apdex T in milliseconds. F is always JMeter's `jmeter.reportgenerator.apdex_tolerated_threshold` (`1500`) |

### Error Sampling

| Property | Default | Description |
|----------|---------|-------------|
| `live.dashboard.error.samples.max` | `100` | Maximum number of recent error samples retained |

### Idle Timeout

| Property | Default | Description |
|----------|---------|-------------|
| `live.dashboard.idle.timeout` | `600` | Seconds the server stays up after the test ends with no browser connected (0 = disabled) |

### Server Lifecycle — what to expect

Three behaviours that surprise people because nothing on screen announces them.

**The dashboard outlives the test.** After the run finishes the server keeps serving so you
can still read the results, and stops only on the idle timeout above — by default ten
minutes after the last browser disconnects. A JMeter process that will not exit for ten
minutes after `... end of run` is doing this, not hanging. An open dashboard tab holds the
connection, so the countdown does not start until you close it; `-Jlive.dashboard.idle.timeout=0`
keeps the server up until JMeter exits.

**The port moves when it is taken.** If 7566 is busy the server tries 7567, 7568 … up to
7575, and logs `Port 7566 was in use, using port 7567 instead`. So running several JMeter
instances at once gives each its own dashboard on its own port — and the **log is the only
place that records which**. If you run more than ten at once, the later ones get no
dashboard at all; that failure is printed to stderr as `Live Dashboard: NOT started`.

**A tab from a previous run does not follow the new one.** Starting another test closes the
old dashboard; the tab keeps showing the finished run's results and offers a reload link
rather than silently switching. If instead JMeter was killed outright, the tab reconnects
on its own to whatever now holds that port — possibly a different JMeter process — and says
so when it notices.

### One listener per plan

Add exactly one **Live Insight Dashboard** listener, at test-plan level. The dashboard is
shared process-wide, so with more than one element the first to start owns it and the
others' samples are **not counted** — they are dropped, with a warning in `jmeter.log`:

```
Live Insight Dashboard: another listener element owns the dashboard; samples seen
only by this element are NOT counted. Use a single Live Insight Dashboard listener
at test-plan level.
```

A listener scoped inside a thread group only ever sees that group's samples, so this shows
up as a dashboard reporting less load than the test is generating.

### Example CLI Usage

```bash
jmeter -n -t test.jmx \
  -Jlive.dashboard.port=8080 \
  -Jlive.dashboard.bind=0.0.0.0 \
  -Jlive.dashboard.update.interval=1 \
  -Jlive.dashboard.sla.default.p95=2000 \
  -Jlive.dashboard.sla.default.errorRate=5 \
  -Jlive.dashboard.apdex.threshold=300 \
  -Jlive.dashboard.hide.patterns="JSR223*,Debug*,BeanShell*,Setup*"
```

---

## Connection Resilience

### Server-Sent Events (SSE)
The dashboard communicates with the JMeter server via a single SSE connection (`/api/events`). Three event types are handled:
- **catchup** -- full state payload sent on initial connection or reconnect
- **bucket** -- incremental time-series data pushed at the configured interval
- **event** -- lifecycle events (testStarted, testEnded, serverClosing)

### Auto-Reconnect
When the SSE connection drops (server restart, network interruption), the dashboard:
1. Shows "DISCONNECTED" status in the header badge
2. Waits 3 seconds
3. Automatically reconnects

**Exception:** When a new test starts, the server broadcasts a `serverClosing` event before shutting down. The browser receives this and permanently disables auto-reconnect, showing "CLOSED" status. This prevents the old tab from connecting to the new test's server. The old tab preserves all data from the previous test.

### Catch-Up Payload
On reconnect, the server sends a full `catchup` event containing:
- Test name and start time
- All known sampler names
- Cumulative statistics
- Per-sampler statistics
- Time-series buckets: the 2-second slots kept for `live.dashboard.window.max`, and the whole run in 1-minute slots
- Recent errors and error summary
- SLA configuration and current SLA status
- Transaction hierarchy
- The samplers the hide patterns uncheck (their names, not the patterns)

This ensures the dashboard fully recovers after any disconnection without data loss.

### Connection Status
Connection state is reflected in two places:
- **Header badge** -- LIVE / DISCONNECTED / ENDED / EXPLORING / CONNECTING / CLOSED
- **Header dot** -- pulsing animation when live, static when disconnected or ended

Status meanings:
- **LIVE** -- connected and test is running
- **ENDED** -- connected, test has finished
- **EXPLORING** -- connected, test running, user has zoomed into charts
- **DISCONNECTED** -- SSE connection lost, auto-reconnecting every 3s
- **CONNECTING** -- initial connection attempt
- **CLOSED** -- server shut down because a new test started; this tab preserves the old test data and will not reconnect

---

## Server Lifecycle

### Keep Alive After Test
When the JMeter test ends, the dashboard server remains running:
- The elapsed timer freezes at the final duration
- Charts render one final update and then stop refreshing
- The header badge changes to "ENDED"
- The Stop Test button is disabled
- All dashboard features (zoom, export, filtering) remain fully functional for post-test review

### Auto-Stop on Next Test Start
When a new test is started in JMeter:
- The previous dashboard server is automatically stopped and the port is released
- A new server starts with fresh state
- The static server reference ensures no port conflicts across JMeter GUI re-runs

### Auto-Detect Port
If the configured port (default 7566) is already in use, the server automatically tries the next port (7567, 7568, ...) up to 7575. This enables running multiple JMeter instances simultaneously, each with its own dashboard on a different port.

### Idle Timeout
Once the test has ended, the server auto-stops when no browser has been connected for a configurable idle timeout. While the test runs it never stops on its own:
- Default: 600 seconds (10 minutes)
- Configure via `-Jlive.dashboard.idle.timeout=600` (seconds)
- Set to `0` to disable (server stays alive indefinitely)
- The timeout resets whenever a browser connects
- When the last browser tab closes and no new connection arrives within the timeout, the server shuts down and releases the port

---

## Technology

| Component | Technology |
|-----------|-----------|
| Plugin | Java (JMeter listener) |
| Percentiles | Exact, computed as JMeter's Aggregate Report does (0.1% above 10 s) |
| Charts | Apache ECharts 5.5.0 |
| Communication | Server-Sent Events (SSE) |
| Output | Self-contained HTML dashboard |
| Persistence | localStorage: palette, line thickness, custom colors, clock/elapsed, dark and colorblind mode, column visibility, chart order and presets. Metric, window, granularity and the sampler selection reset on reload |
