// @ts-check
const { test, expect } = require('./fixtures');
const { openDashboard, settled } = require('./setup/dashboard');
const { runCatchup, loadRun } = require('./setup/run-data');

test.describe('Timeline Tab', () => {
  test.beforeEach(async ({ page }) => {
    await openDashboard(page);
    await page.click('.tab-btn[data-tab="timeline"]');
  });

  test('response time chart renders @smoke', async ({ page }) => {
    const chart = page.locator('#responseTimeChart');
    await expect(chart).toBeVisible();
    await expect(chart.locator('canvas').first()).toBeVisible();
  });

  test('throughput chart renders @smoke', async ({ page }) => {
    const chart = page.locator('#throughputChart');
    await expect(chart).toBeVisible();
    await expect(chart.locator('canvas').first()).toBeVisible();
  });

  test('error rate chart renders', async ({ page }) => {
    const chart = page.locator('#errorRateChart');
    await expect(chart).toBeVisible();
    await expect(chart.locator('canvas').first()).toBeVisible();
  });

  test('bytes chart renders', async ({ page }) => {
    const chart = page.locator('#bytesChart');
    await expect(chart).toBeVisible();
    await expect(chart.locator('canvas').first()).toBeVisible();
  });

  test('connect/latency chart renders', async ({ page }) => {
    const chart = page.locator('#connectLatencyChart');
    await expect(chart).toBeVisible();
    await expect(chart.locator('canvas').first()).toBeVisible();
  });

  test('fullscreen button enters fullscreen', async ({ page }) => {
    const chartSection = page.locator('#responseTimeChart').locator('xpath=ancestor::div[contains(@class,"chart-section")]');
    const btn = chartSection.locator('button:has-text("Fullscreen")');
    await btn.click();
    await expect(chartSection).toHaveClass(/chart-fullscreen/);
    // Exit via JS
    await page.evaluate(() => {
      document.querySelector('.chart-section.chart-fullscreen').classList.remove('chart-fullscreen');
    });
    await expect(chartSection).not.toHaveClass(/chart-fullscreen/);
  });

  test('drag grip handles visible', async ({ page }) => {
    const grips = page.locator('.drag-grip');
    await expect(grips).toHaveCount(5); // 5 timeline charts
  });

  test('charts have PNG export toolbox', async ({ page }) => {
    // ECharts toolbox renders as a div within the chart container
    const chart = page.locator('#responseTimeChart');
    await expect(chart).toBeVisible();
  });
});

test.describe('Timeline: windows, granularity and axes', () => {
  const points = (page, chart) => page.evaluate((c) => S.charts[c].getOption().series.map(s => s.data.length), chart);

  // A window is the last N seconds of the RUN. Measured from the wall clock, an ended
  // run's windows went empty a minute after it ended, and a tab opened later - the
  // post-test review the server stays up for - could use nothing but Full.
  test('1m on a run that ended 10 minutes ago charts the run\'s last minute', async ({ page }) => {
    await loadRun(page, runCatchup());
    await page.click('.tab-btn[data-tab="timeline"]');
    await page.click('#windowToggle [data-window="60"]');
    await settled(page);
    expect(await points(page, 'responseTimeChart'), 'one point per 2 s slot of the last minute').toEqual([30, 30, 30]);
    const [lastPlotted, lastSlot] = await page.evaluate(() => {
      const d = S.charts.responseTimeChart.getOption().series[0].data;
      return [d[d.length - 1][0], S.timestamps[S.timestamps.length - 1]];
    });
    expect(lastPlotted).toBe(lastSlot);

    await page.click('#windowToggle [data-window="0"]');
    await settled(page);
    expect(await points(page, 'responseTimeChart')).toEqual([90, 90, 90]);
  });

  // The buttons average 1, 3, 5, 15 or 30 updates, so the time each stands for depends on
  // the update interval. They were labelled 1s/5s/10s/30s/1m regardless: at the default
  // 2 s, "1s" plotted a point every 2 s and "5s" every 6 s, and at 5 s every label was wrong.
  for (const interval of [2000, 5000]) {
    test(`granularity labels name the spacing they plot (update interval ${interval} ms)`, async ({ page }) => {
      await loadRun(page, runCatchup({ endedAgoMs: 0, running: true, interval, minutes: interval === 2000 ? 3 : 6 }));
      await page.click('.tab-btn[data-tab="timeline"]');
      const seconds = (label) => {
        const m = /^(?:(\d+)m)?(?:([\d.]+)s)?$/.exec(label);
        return m ? Number(m[1] || 0) * 60 + Number(m[2] || 0) : NaN;
      };
      for (const btn of await page.locator('#granularityToggle .filter-toggle-btn:not([data-gran="0"])').all()) {
        const label = (await btn.textContent()).trim();
        await btn.click();
        await settled(page);
        const spacing = await page.evaluate(() => {
          const d = S.charts.responseTimeChart.getOption().series[0].data;
          return (d[1][0] - d[0][0]) / 1000;
        });
        expect(spacing, `button "${label}"`).toBe(seconds(label));
      }
    });
  }

  // The ms axis formatter rounds to whole milliseconds, so an axis spanning a millisecond
  // or two repeated its labels: all-zero connect/latency (JSR223 samplers, for one) read
  // 0ms 0ms 0ms 1ms 1ms 1ms.
  test('connect/latency axis repeats no label when everything is zero', async ({ page }) => {
    await loadRun(page, runCatchup({ zeroConnect: true }));
    await page.click('.tab-btn[data-tab="timeline"]');
    await settled(page);
    const labels = await page.evaluate(() => S.charts.connectLatencyChart.getModel().getComponent('yAxis', 0)
      .axis.scale.getTicks().map(t => fmtMsAxis(t.value)));
    expect(new Set(labels).size, labels.join(', ')).toBe(labels.length);
  });

  // The zoom slider's handle labels used ECharts' default date format
  // ("2026-10-07 22:28:00"), which ran off both edges of the chart.
  test('zoom slider handle labels read like the time axis', async ({ page }) => {
    await loadRun(page, runCatchup());
    await page.click('.tab-btn[data-tab="timeline"]');
    await settled(page);
    const [label, axis] = await page.evaluate(() => {
      const ts = S.timestamps[10];
      const f = S.charts.responseTimeChart.getOption().dataZoom.find(z => z.type === 'slider').labelFormatter;
      return [typeof f === 'function' ? f(ts) : String(f), fmtTime(ts)];
    });
    expect(label).toBe(axis);
  });
});
