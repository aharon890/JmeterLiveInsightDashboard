# Live Features

The dashboard communicates with JMeter in real-time via Server-Sent Events (SSE). This page covers the streaming architecture, connection states, test controls, and multi-run behavior.

## SSE Streaming

The dashboard connects to the JMeter server via a single SSE connection (`/api/events`). Three event types:

| Event | Purpose | When Sent |
|-------|---------|-----------|
| **catchup** | Full state payload — test name, all samplers, cumulative stats, recent buckets, errors, SLA config | On initial connection or reconnect |
| **bucket** | Incremental time-series data point | Every update interval (default 2 seconds) |
| **event** | Lifecycle signal | `testStarted`, `testEnded`, `serverClosing` |

The catchup event ensures the dashboard fully recovers after any disconnection without data loss. It contains:
- Test name and start time
- All known sampler names
- Cumulative and per-sampler statistics
- Time-series buckets: the 2-second slots kept for `live.dashboard.window.max`, and the whole run in 1-minute slots
- Recent errors and error summary
- SLA configuration and current status
- Transaction hierarchy
- The samplers the hide patterns uncheck (their names, not the patterns)

## Connection States

The header badge reflects the current connection state:

| State | Badge Color | Dot Animation | Meaning |
|-------|-------------|---------------|---------|
| **CONNECTING** | Grey | Static | Initial connection attempt |
| **LIVE** | Green | Pulsing | Connected, test is running |
| **EXPLORING** | Blue | Static | Connected, test running, user has zoomed into charts |
| **ENDED** | Amber | Static | Connected, test has finished |
| **DISCONNECTED** | Grey | Static | SSE connection lost, auto-reconnecting every 3 seconds |
| **CLOSED** | Grey | Static | Server shut down for new test — no reconnect |

Connection state is reflected in two places:
- **Header badge** — text label with color
- **Header dot** — pulsing animation when live, static otherwise

### LIVE

Normal operating state. Charts auto-scroll with incoming data. Metric cards, tables, and charts all update in real-time.

### EXPLORING

When a user zooms or pans any chart (mouse wheel, click-drag, or slider), the dashboard enters Explore mode:
- All charts freeze at the user's zoom position
- Incoming data continues accumulating in memory
- Metric cards and the Samplers table continue updating
- A floating **"Return to Live"** button appears at the bottom-right

Click the button to snap all charts back to the latest data and re-enable auto-scroll.

### ENDED

When the test finishes:
- The elapsed timer freezes at the final duration
- Charts render one final update and stop refreshing
- The Stop Test button is disabled
- All dashboard features (zoom, export, filtering) remain fully functional for post-test review
- The server stays alive for continued browsing

### DISCONNECTED

When the SSE connection drops (network interruption, server restart):
1. Badge shows "DISCONNECTED"
2. Dashboard waits 3 seconds
3. Automatically reconnects
4. On reconnect, receives a full catchup event — no data loss

### CLOSED

When a **new test starts** in JMeter:
1. The server broadcasts a `serverClosing` event to all connected browsers
2. The old tab receives this and permanently disables auto-reconnect
3. Badge shows "CLOSED"
4. The old tab preserves all data from the previous test
5. A new server starts on the same port for the new test

This prevents stale tabs from connecting to a different test's server.

## Test Controls

The footer provides buttons for controlling the running test:

### Stop Test (Graceful)

Sends `POST /api/stop` to JMeter. Running samplers finish before the test ends. After clicking, the button is disabled and the header status reads "Stopping" until the test has ended.

### Shutdown

Sends `POST /api/stop-now`: stops the test immediately (in-flight samplers are aborted) and then terminates the JMeter process itself. The dashboard server ends with it.

Both buttons require confirmation dialogs before executing.

### Pause / Resume Updates

Toggles SSE event processing:

| State | Behavior |
|-------|----------|
| **Normal** | Incoming events processed immediately |
| **Paused** | Events buffered in memory; charts and tables freeze |
| **Resumed** | All buffered data replayed — no data loss |

When paused:
- Button text changes to "Resume Updates"
- Button displays an amber pulsing animation
- The server continues sending events; they are buffered client-side

## Idle Timeout

Once the test has ended, the server auto-stops when no browser has been connected for a configurable idle timeout. While the test runs it never stops on its own.

| Property | Default | Description |
|----------|---------|-------------|
| `live.dashboard.idle.timeout` | `600` | Idle timeout in seconds (10 minutes) |

Set to `0` to disable idle timeout (server stays alive indefinitely).

The timeout resets whenever a browser connects. If the last browser tab closes and no new connection arrives within the timeout, the server shuts down and releases the port.

## Multi-Run Isolation

Each test run gets its own server instance with fresh state:

1. **First test** — server starts on port 7566, browser opens
2. **Second test** — previous server sends `serverClosing` to all browsers, shuts down, new server starts on port 7566
3. **Old tab** — shows CLOSED, preserves previous test data, will not reconnect
4. **New tab** — opens automatically, connects to the fresh server

This ensures:
- No data mixing between test runs
- Old tabs remain browsable for comparison
- Port is released cleanly between runs

## Browser Notifications

When SLA transitions to FAIL status during a running test, a browser notification is triggered:
- Lists up to 3 failing samplers with the violated metric
- The title is "SLA FAIL - " followed by the test name
- Permission is asked for on your first click on the dashboard (Firefox and Safari allow the request only from a click)

## Tips

- Keep old tabs open after a test ends — you can still zoom, filter, export, and compare.
- Use Pause during presentations to freeze the dashboard at an interesting point.
- The EXPLORING state lets you investigate a time window while the test continues — data keeps accumulating and is available when you return to LIVE.
- If the dashboard shows DISCONNECTED, wait 3 seconds — it will auto-reconnect and recover all data.
