# Filter Bar

A persistent control bar below the tabs. Its settings apply to the charts on every tab. The sampler pills filter the charts only: the Samplers table, the Errors tab and the metric cards always show every sampler.

Palette, line thickness, custom colors, Clock/Elapsed and presets are saved in localStorage and survive a page reload. Metric, window, granularity and the sampler selection go back to their defaults on reload; save a preset to keep a selection.

Screenshot: Filter bar with two rows of controls.

## Layout

The filter bar has 2 rows:

| Row | Contents |
|-----|----------|
| **Row 1** | Palette, Line thickness, Metric, Granularity, Clock/Elapsed toggle, Window |
| **Row 2** | All/None toggles, Preset controls, sampler checkbox pills |

## Row 1: Settings

### Palette Selection

Three built-in color palettes for chart lines:

| Palette | Colors | Description | Default |
|---------|--------|-------------|---------|
| **Default** | 20 | Standard ECharts palette (blue, green, gold, red, ...) | Yes |
| **Okabe-Ito** | 10 | Colorblind-friendly (deuteranopia/protanopia safe) | No |
| **High Contrast** | 10 | Maximum visual separation using primary colors | No |

Click a palette button to update all chart line colors immediately. Sampler color dots in the filter bar also update.

### Line Thickness

| Option | Width | Default |
|--------|-------|---------|
| **Thin** | 1px | No |
| **Medium** | 2px | Yes |
| **Thick** | 3px | No |

The Sigma Total line is always 1px thicker than the selected thickness.

### Metric Toggle

Switches the response time metric displayed on charts:

| Option | Data Shown | Default |
|--------|------------|---------|
| **Mean** | Average response time | No |
| **P50** | 50th percentile (median) | No |
| **P90** | 90th percentile | No |
| **P95** | 95th percentile | Yes |
| **P99** | 99th percentile | No |
| **Max** | Maximum response time | No |

**Affects:** Summary chart, Timeline response time chart, per-sampler detail charts. Other charts (Throughput, Error Rate, etc.) are unaffected.

### Clock / Elapsed Toggle

Switches the time format on the chart X-axes and tooltips and in the Errors table's Time column. Clock time is the browser's local time. For the first two hours of a test, the Samplers tab's mini-charts count from the start whatever this is set to.

| Mode | Format | Example |
|------|--------|---------|
| **Clock** (default) | Wall-clock time | 14:30:50 |
| **Elapsed** | Time since test start | +00:05:30 |

### Window Selector

Controls how much time-series data is visible in charts:

| Option | Window Size |
|--------|-------------|
| **1m** | Last 60 seconds |
| **5m** | Last 5 minutes |
| **15m** | Last 15 minutes |
| **30m** | Last 30 minutes |
| **Full** | All data since test start (default) |

**Finished runs:** once the test has ended, the window ends where the run ended rather than now, so a tab opened later still shows the run's last minute.

**Non-destructive:** Window only affects chart display. Metric cards and the Samplers table always show full-test cumulative values.

**Nothing is discarded:** the window only slices what is drawn, so switching back to Full loses nothing. At a granularity coarser than 2s the window begins at the start of the period it falls in.

### Granularity Selector

Sets how much time each chart point stands for. Coarser than 2s, a point is one fixed period of clock time, as in JMeter's own report graphs:

| Option | One point per |
|--------|---------------|
| **2s** | Update: the raw data (finest) |
| **6s** | 6 s of clock time |
| **10s** | 10 s of clock time |
| **30s** | 30 s of clock time |
| **1m** | Minute of clock time (coarsest) |
| **Auto** | Period chosen from the number of points in the window |

The labels are for the default 2 s update interval; each button shows the time it gives at the run's actual interval.

Rates, means, error % and max are exact for each period; a rate is the period's samples over its length, as JMeter's report divides. Percentiles are exact at 1m for each closed minute and an average of the 2 s values at 6s, 10s and 30s; each button's tooltip says which.

**Auto mode:** the raw data below 150 points in the window, then 6s, 10s, 30s and 1m as it holds more. The Window and Granularity controls have hover tooltips describing their function.

## Row 2: Samplers

### Sampler Checkbox Pills

Each sampler appears as a pill-shaped label with:
- A checkbox (styled custom box with checkmark)
- A color dot matching the chart series color
- A truncated sampler name (max 200px, hover for full name)

**Checked pills** appear at full opacity. **Unchecked pills** appear at 45% opacity.

**Uncheck a sampler** to remove its line from the time-series charts. The pills filter the charts only: the Samplers table, the Errors tab and the metric cards always show every sampler.

Screenshot: Sampler pills with checkboxes and color dots.

### All / None Buttons

Quick toggle links to check or uncheck all samplers at once, including the Sigma Total.

### Auto-Hide Patterns

Samplers matching configured patterns (default: `JSR223*`, `Debug*`, `BeanShell*`) are unchecked by default on discovery. They remain in the filter bar and can be manually enabled. In a pattern, `*` matches any characters and `?` exactly one; everything else, brackets and parentheses included, matches literally.

Configure via: `-Jlive.dashboard.hide.patterns="JSR223*,Debug*,BeanShell*,Setup*"`

### Sigma Total Virtual Sampler

A synthetic "Sigma Total" entry appears first in the filter bar, separated by a vertical divider:
- **Off by default**
- When enabled: a dashed line in the Response Time and Throughput charts
- The bold Σ Total row at the top of the Samplers table is always shown, checked or not
- Uses a fixed dark color (#333333)

### Per-Sampler Color Override (Color Picker)

Click the **color dot** next to any sampler name to open a color picker:

| Component | Description |
|-----------|-------------|
| **Swatch grid** | 8-column grid with colors from all 3 palettes + standard colors |
| **Custom hex input** | Native `<input type="color">` for any custom color |

**Behavior:**
- Select a color — dot updates immediately, chart line updates after debounce
- Custom colors persist in localStorage across page reloads
- Color picker closes on selection or clicking outside

Screenshot: Color picker popup with swatch grid and hex input.

### Filter Presets

Save and restore complete filter configurations:

| Action | Behavior |
|--------|----------|
| **Save** | Prompts for a name; stores current visibility map in localStorage |
| **Load** | Select from dropdown — the saved sampler selection is restored and the charts refresh |
| **Delete** | Removes the currently selected preset |

Presets save the sampler selection only, including the Σ Total entry. Metric, palette and line thickness are not part of a preset.

Presets are stored in localStorage under the key `ld_presets`.

## Tips

- Use the **Okabe-Ito** palette when sharing dashboards with colorblind colleagues.
- **Thick** lines work well for presentations and screen sharing.
- Set a **5m window** for long-running tests to keep charts responsive. It changes only what is drawn, not what the page keeps.
- Save a **preset** for your most common sampler selection — e.g., "API Only" that hides static assets.
- The color picker lets you assign consistent colors to specific endpoints across test runs.
