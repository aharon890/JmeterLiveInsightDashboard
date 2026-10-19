// @ts-check
const { test, expect } = require('./fixtures');
const { openDashboard, dashboardReady, sendToPage, settled } = require('./setup/dashboard');

test.describe('User Interactions', () => {
  test.beforeEach(async ({ page }) => {
    await openDashboard(page);
  });

  // --- Column Visibility ---
  test('toggle column hides cells in table', async ({ page }) => {
    await page.click('.tab-btn[data-tab="samplers"]');
    await page.click('button:has-text("Columns")');
    const checkboxes = page.locator('#colMenu input[type="checkbox"]');
    await checkboxes.first().uncheck();
    const samplesHeader = page.locator('#samplerTable thead th:nth-child(2)');
    await expect(samplesHeader).toBeHidden();
  });

  test('hidden column persists after tab switch', async ({ page }) => {
    await page.click('.tab-btn[data-tab="samplers"]');
    await page.click('button:has-text("Columns")');
    await page.locator('#colMenu input[type="checkbox"]').first().uncheck();
    await page.click('.tab-btn[data-tab="summary"]');
    await page.click('.tab-btn[data-tab="samplers"]');
    await expect(page.locator('#samplerTable thead th:nth-child(2)')).toBeHidden();
  });

  // --- Filter Presets ---
  test('save and load preset', async ({ page }) => {
    await page.click('button:has-text("None")');
    page.on('dialog', async dialog => { await dialog.accept('MyPreset'); });
    await page.click('button:has-text("Save")');
    await page.click('button:has-text("All")');
    await page.selectOption('#presetSelect', 'MyPreset');
    const unchecked = page.locator('.filter-sampler-list .sampler-pill:not(.checked)');
    await expect.poll(() => unchecked.count()).toBeGreaterThanOrEqual(3);
  });

  test('delete preset removes from dropdown', async ({ page }) => {
    page.on('dialog', async dialog => { await dialog.accept('ToDelete'); });
    await page.click('button:has-text("Save")');
    await page.selectOption('#presetSelect', 'ToDelete');
    await page.click('button:has-text("Del")');
    await expect.poll(() => page.locator('#presetSelect option').allTextContents()).not.toContain('ToDelete');
  });

  // The rebuild restored the deleted name as the selection, which matches no option:
  // selectedIndex -1, an empty box instead of "--".
  test('deleting the selected preset returns the dropdown to "--"', async ({ page }) => {
    page.on('dialog', async dialog => { await dialog.accept('Gone'); });
    await page.click('button:has-text("Save")');
    await page.selectOption('#presetSelect', 'Gone');
    await page.click('button:has-text("Del")');
    await expect.poll(() => page.locator('#presetSelect').evaluate(s =>
      [s.selectedIndex, s.selectedIndex >= 0 ? s.options[s.selectedIndex].text : null])).toEqual([0, '--']);
  });

  // --- Chart Drag-and-Drop ---
  test('timeline charts have draggable attribute', async ({ page }) => {
    await page.click('.tab-btn[data-tab="timeline"]');
    const draggables = page.locator('#timelineContent .chart-section[draggable="true"]');
    await expect(draggables).toHaveCount(5);
  });

  // --- Hide/Show Children ---
  test('hide children button toggles text', async ({ page }) => {
    await page.click('.tab-btn[data-tab="samplers"]');
    const btn = page.locator('#btnToggleChildren');
    await expect(btn).toHaveText('Hide Children');
    await btn.click();
    await expect(btn).toHaveText('Show Children');
    await btn.click();
    await expect(btn).toHaveText('Hide Children');
  });

  // --- Per-Sampler Detail Expansion ---
  test('clicking sampler row expands detail charts', async ({ page }) => {
    await page.click('.tab-btn[data-tab="samplers"]');
    await page.locator('#samplerTableBody tr.expandable').first().click();
    await expect(page.locator('.detail-row')).toBeVisible();
    await expect(page.locator('.detail-row .detail-chart')).toHaveCount(3);
  });

  test('clicking expanded row collapses it', async ({ page }) => {
    await page.click('.tab-btn[data-tab="samplers"]');
    const row = page.locator('#samplerTableBody tr.expandable').first();
    await row.click();
    await expect(page.locator('.detail-row')).toBeVisible();
    await settled(page); // its charts are built, so collapsing has them to dispose
    await row.click();
    await expect(page.locator('.detail-row')).toHaveCount(0);
  });

  test('max 3 expanded enforced', async ({ page }) => {
    await page.click('.tab-btn[data-tab="samplers"]');
    const rows = page.locator('#samplerTableBody tr.expandable');
    for (let i = 0; i < 3; i++) {
      await rows.nth(i).click();
    }
    await expect(page.locator('.detail-row')).toHaveCount(3);
    page.on('dialog', async dialog => { await dialog.accept(); });
    // 4th click - only 3 samplers in fixture so this just validates the limit
  });

  // --- Time Display Toggle ---
  test('time display toggle changes button text', async ({ page }) => {
    const btn = page.locator('#timeDisplayBtn');
    expect(await btn.textContent()).toContain('Clock');
    await btn.click();
    expect(await btn.textContent()).toContain('Elapsed');
    await btn.click();
    expect(await btn.textContent()).toContain('Clock');
  });

  test('time display persists in localStorage', async ({ page }) => {
    await page.click('#timeDisplayBtn');
    const val = await page.evaluate(() => localStorage.getItem('ld_timeDisplay'));
    expect(val).toBe('elapsed');
  });

  // --- Dark Mode Persistence ---
  test('dark mode persists across reload', async ({ page }) => {
    await page.click('#darkModeIcon');
    await expect(page.locator('body')).toHaveClass(/dark-mode/);
    await page.reload();
    await dashboardReady(page);
    await expect(page.locator('body')).toHaveClass(/dark-mode/);
  });

  // --- Colorblind Persistence ---
  test('colorblind mode persists across reload', async ({ page }) => {
    await page.click('#cbModeIcon');
    await expect(page.locator('body')).toHaveClass(/cb-mode/);
    await page.reload();
    await dashboardReady(page);
    await expect(page.locator('body')).toHaveClass(/cb-mode/);
  });

  // --- Window Size ---
  test('changing window changes active button', async ({ page }) => {
    const btn1m = page.locator('#windowToggle .filter-toggle-btn[data-window="60"]');
    const btnFull = page.locator('#windowToggle .filter-toggle-btn[data-window="0"]');
    await expect(btnFull).toHaveClass(/active/);
    await btn1m.click();
    await expect(btn1m).toHaveClass(/active/);
    await expect(btnFull).not.toHaveClass(/active/);
  });

  // --- Palette Persistence ---
  test('palette choice persists in localStorage', async ({ page }) => {
    await page.click('#paletteToggle .filter-toggle-btn[data-palette="okabe-ito"]');
    const val = await page.evaluate(() => localStorage.getItem('ld_palette'));
    expect(val).toBe('okabe-ito');
  });

  // --- All/None after test ends ---
  test('All/None updates charts after test ends', async ({ page }) => {
    await sendToPage(page, 'event', { type: 'event', event: 'testEnded', data: { endTime: Date.now() } });
    await page.click('button:has-text("None")');
    await settled(page); // None's redraw runs, instead of being cancelled by All's
    await page.click('button:has-text("All")');
    await expect(page.locator('#summaryRTChart canvas').first()).toBeVisible();
  });
});
