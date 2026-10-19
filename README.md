# JMeter Live Insight Dashboard

Real-time browser-based dashboard for monitoring JMeter test execution. Zero infrastructure — just add the listener to your test plan and open `http://localhost:7566`.

## Quick Start

1. Download `jmeter-live-dashboard-1.6.2.jar` from [GitHub Releases](https://github.com/aharon890/JmeterLiveInsightDashboard/releases)
2. Copy it to JMeter's `lib/ext/`, removing any older `jmeter-live-dashboard-*.jar` first
3. Add **Live Insight Dashboard** listener to your test plan
4. Run test — dashboard opens automatically at `http://localhost:7566`

To build from source instead, `mvn package -DskipTests` writes the jar to `target/`; with a local JMeter install, `./scripts/build.sh` builds it and copies it to `lib/ext/` for you
(`scripts/build.bat` on Windows). No JMeter installed? Run everything in Docker:

```bash
./scripts/run-docker-dashboard.sh    # build, run the smoke plan in a container,
                                     # then browse http://localhost:7566
```

## What It Does

- Streams live test metrics to a browser dashboard via Server-Sent Events (SSE)
- Shows response times, throughput, error rates, and bytes throughput as real-time charts
- Computes percentiles exactly, the way JMeter's Aggregate Report does
- Charts the whole run: 2-second detail for the last hour, plus 1-minute slots back to the first minute of a long test
- Displays per-sampler statistics table with cumulative metrics
- Groups and tracks errors with summary and detail views
- Exports a self-contained HTML snapshot, CSV, or PNG charts
- Keeps serving after the test ends, so you can still read the results; it shuts down once no dashboard tab has been open for 10 minutes
- Works in both GUI and non-GUI mode
- Needs no database or Grafana: everything runs inside the plugin

## Architecture

```
JMeter Process                          Browser
  LiveDashboardListener                   Dashboard (index.html)
    -> LiveAggregator                       <- SSE  /api/events
      -> 2s slots (last hour)               <- REST /api/status
      -> 1-min slots (whole run)            <- REST /api/config, /api/history
      -> exact percentiles                  -> POST /api/stop, /api/stop-now
    -> DashboardServer (JDK HttpServer)
      -> SSE broadcast every 2s
```

- **Embedded HTTP server** using JDK's `com.sun.net.httpserver` — no Jetty conflicts
- **Exact percentiles**, computed as JMeter's Aggregate Report computes them: exact up to 10 s; above that, at most 0.1% low
- **SSE** for data push + REST for commands — single port, no WebSocket dependency
- **ECharts 5.5.0** for charting — bundled in the jar and served from the plugin, never from a CDN

## Configuration

Six settings have a field in the listener's GUI panel: `port`, `bind`, `open.browser`, `update.interval`, `window.max` and `hide.patterns`. Every setting can be set as a JMeter property (`-Jlive.dashboard.port=7600`).
A value saved in the test plan wins over the property, except for `bind` and
`open.browser`, where the `-J` property wins so automation can override them
without editing the `.jmx`.

| Property | Default | Description |
|----------|---------|-------------|
| `live.dashboard.port` | `7566` | HTTP server port; if it's busy, the next free one up to 7575 |
| `live.dashboard.bind` | `localhost` | Bind address (`0.0.0.0` to reach the dashboard from another machine) |
| `live.dashboard.open.browser` | `true` | Auto-open browser on test start |
| `live.dashboard.update.interval` | `2` | Update and chart slot interval, in seconds |
| `live.dashboard.window.max` | `3600` | How long 2-second slots are kept (seconds); 1-minute slots cover the whole run |
| `live.dashboard.hide.patterns` | `JSR223*,Debug*,BeanShell*` | Wildcard patterns for samplers left unchecked in the charts by default |
| `live.dashboard.sla.default.p95` | `-1` (off) | P95 SLA threshold (ms) |
| `live.dashboard.sla.default.errorRate` | `-1` (off) | Error rate SLA threshold (%) |
| `live.dashboard.apdex.threshold` | `500` | Apdex T (ms); defaults to JMeter's report threshold, and F is JMeter's `apdex_tolerated_threshold` |
| `live.dashboard.error.samples.max` | `100` | Recent error samples kept (property only) |
| `live.dashboard.idle.timeout` | `600` | Seconds without a dashboard tab before the server stops; `0` = never |
| `live.dashboard.enabled` | `true` | `false` turns the listener off (property only) |

Full reference: [docs/Tutorial/10-Configuration.md](docs/Tutorial/10-Configuration.md).

## Documentation

All documentation is in [`docs/`](docs/):

- [Tutorial](docs/Tutorial/README.md): a page per tab and feature, starting from installation
- [Feature guide](docs/FEATURES.md): every chart, control and interaction
- [Build / test / develop guide](docs/Instructions.md) and [testing](docs/TESTING.md)
- [Known issues and limitations](docs/TODO.md). The main one: distributed (`-R`) runs are counted correctly, but the Stop and Shutdown buttons act on the local engine only.

## Testing

```bash
./scripts/test-smoke.sh          # ~35s  — fast gate while developing
./scripts/test-regression.sh     # ~3.5m — full gate when a feature or fix is done
./scripts/merge-pr.sh            # merge the PR on a green regression of that exact content
```

(`.bat` wrappers of the same names on Windows.)

**All automation runs locally — there is no CI.** Nothing on GitHub re-checks a
branch, so these runs are the only verification there is, and a skipped layer is
a gap rather than a deferral. `merge-pr.sh` makes a full green regression of the
exact content being merged a precondition of merging to `master`. It reuses the run
made before pushing rather than testing the same content twice. See [docs/TESTING.md](docs/TESTING.md) for
the layers, the flow, and platform notes.

## Contributing

Work on a branch and open a PR — direct pushes to `main`/`master` are rejected
by a versioned pre-push hook. Install it once per clone:

```bash
./scripts/install-hooks.sh
```

It points git at `.githooks/`, sets `origin/HEAD` (the hook reads it), and
repairs the executable bit on the scripts.

## Relationship to Web Insight Report Plugin

This plugin is the **real-time complement** to the JMeter Web Insight Report plugin. Same design language, same color palette, same chart library. Monitor live, then generate the permanent report when the test ends.

## Technology

| Component | Technology |
|-----------|-----------|
| Server | Java (JDK HttpServer + SSE) |
| Percentiles | Exact histogram, as JMeter's Aggregate Report (no third-party library) |
| Charts | Apache ECharts 5.5.0 |
| Build | Maven; nothing is bundled — JMeter provides every dependency at runtime |
| Target | JMeter 5.6.3+, Java 11+ |

## License

[Apache License 2.0](LICENSE), like JMeter. The bundled Apache ECharts is credited in [NOTICE](NOTICE).
