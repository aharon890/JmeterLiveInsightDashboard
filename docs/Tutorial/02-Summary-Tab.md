# Summary Tab

The default view when opening the dashboard. Provides at-a-glance test metrics that update in real-time.

Screenshot: Summary tab showing 8 metric cards and response time chart.

## Metric Cards

Eight cards arranged in a 4x2 grid. All update in real-time from cumulative server statistics.

### Row 1 — Primary Metrics

| Card | Value | Format |
|------|-------|--------|
| **Total Samples** | Running request count | Exact integer, grouped (12,345) |
| **Error Rate** | Percentage of failed requests | 2 decimal places (e.g., "4.05%") |
| **Duration** | Test execution time | HH:MM:SS format |
| **Max Threads** | Peak concurrent thread count | Integer |

### Row 2 — Statistical Metrics

| Card | Value | Format |
|------|-------|--------|
| **Avg Response Time** | Mean response time | ms/s/min auto-adapted |
| **P95** | The exact 95th percentile of all samples (the Aggregate Report's TOTAL row) | ms/s/min auto-adapted |
| **Throughput** | Overall requests per second | One decimal (e.g., "49.8/s") |
| **Total Errors** | Running error count | Integer |

### Duration and Throughput Calculation

Throughput divides by the time from the first sample's start to the last sample's end, as JMeter's Aggregate Report does. Duration counts from the first sample: up to now while the test runs, and to the last sample's end once it has ended.

### Trend Arrows

Six cards show directional trend arrows comparing the current cumulative value to the previous update:

| Card | Good Direction |
|------|---------------|
| Total Samples | Up |
| Error Rate | Down |
| Avg Response Time | Down |
| P95 | Down |
| Throughput | Up |
| Total Errors | Down |

Arrows are color-coded: green for favorable change, red for unfavorable change.

### Error Card Highlight

The Error Rate card switches to a red-tinted style when the error rate exceeds 0%.

## Apdex Badge

The header displays an Apdex (Application Performance Index) badge computed from cumulative data, exactly as JMeter's HTML report computes it:

**Apdex = (Satisfied + Tolerating/2) / Total**

| Condition | Definition |
|-----------|-----------|
| Satisfied | Successful sample, response time <= T |
| Tolerating | Successful sample, response time > T and <= F |
| Frustrated | Everything else, including every failed sample |

Total is every sample the report counts for its Apdex Total: a transaction's steps, redirect hops and embedded resources as well as the samples that hold them, so it covers more samples than the Σ row. A transaction that sampled nothing in an iteration is left out, as the report leaves it out.

T and F are JMeter's report thresholds (`jmeter.reportgenerator.apdex_satisfied_threshold`, 500 ms, and `jmeter.reportgenerator.apdex_tolerated_threshold`, 1500 ms), so the badge and the HTML report agree. Hover the badge to see the thresholds in use.

Score from 0.00 to 1.00 with color coding:

| Range | Color | Label |
|-------|-------|-------|
| > 0.94 | Green | Excellent |
| 0.85 - 0.94 | Green | Good |
| 0.70 - 0.85 | Amber | Fair |
| <= 0.70 | Red | Poor |

Change both thresholds through JMeter's report properties, or override T for the dashboard only with `-Jlive.dashboard.apdex.threshold=300`.

## Response Time Chart

A line chart duplicated from the Timeline tab for quick reference without switching tabs.

**Default metric:** P95 response time (configurable via the Metric toggle in the filter bar).

**Controls:**
- **Fullscreen** — expand to full viewport; click Exit Fullscreen to return
- **Legend toggle** — click a sampler name to hide/show its line
- **Zoom and pan** — mouse wheel to zoom; drag inside the chart to pan
- **Time range slider** — draggable slider at the bottom
- **PNG export** — camera icon in the chart toolbox (top-right)

The chart updates in real-time as data streams in. When the selected metric changes in the filter bar, both the Summary chart and the Timeline response time chart update.

## Tips

- Keep the Summary tab open during tests for a quick health check — the 8 cards cover the most important signals.
- The trend arrows help spot regressions mid-test without watching chart lines.
- Use [Copy Summary](09-Export-Snapshot.md) to capture card values for reporting.
