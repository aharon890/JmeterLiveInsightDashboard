// @ts-check
const { test, expect } = require('./fixtures');
const path = require('path');
const fs = require('fs');

/**
 * The plan's name is operator text, and `&` or `<` in it is legitimate. The header sets it
 * as textContent, but Copy Summary's caption and the exported snapshot's <title> and <h1>
 * build it into markup by string, so they depend on escaping. Every other spec uses a
 * plain name like "Smoke Test", where broken escaping changes nothing visible.
 */
const NAME = 'Checkout & <Peak> — Ünïcode';

test.describe('Test name', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await page.waitForSelector('#liveLabel');
    const catchup = JSON.parse(fs.readFileSync(
      path.join(__dirname, 'fixtures', 'catchup-basic.json'), 'utf8'));
    catchup.testName = NAME;
    await page.evaluate(p => fetch('/test/send-catchup', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(p),
    }), catchup);
    await expect(page.locator('#testName')).toHaveText(NAME);
  });

  /** The text/html flavour of the clipboard: the plain-text one carries no name. Null
   *  until there is one, so expect.poll waits for the write instead of failing. */
  const copiedCaption = page => page.evaluate(async () => {
    const [item] = await navigator.clipboard.read();
    if (!item || !item.types.includes('text/html')) return null;
    const html = await (await item.getType('text/html')).text();
    return new DOMParser().parseFromString(html, 'text/html').querySelector('caption')?.textContent;
  });

  test('the browser tab carries the name', async ({ page }) => {
    await expect(page).toHaveTitle(NAME + ' — Live Dashboard');
  });

  // Also pins that the table survives at all: the success handler read window.event after
  // the write resolved, threw, and the .catch fallback overwrote the HTML with plain text.
  test('Copy Summary captions the table with the name, escaped', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await page.click('button:has-text("Copy Summary")');
    await expect(page.locator('button:has-text("Copied!")')).toBeVisible();
    expect(await copiedCaption(page)).toBe(NAME + ' — Live Dashboard Summary');
  });

  test('the exported snapshot carries the name, escaped', async ({ page, context }, testInfo) => {
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.click('button:has-text("Export Snapshot")'),
    ]);
    // Worker-scoped path: a fixed one lets parallel workers unlink a file another is reading.
    const file = path.join(__dirname, 'tmp', `name-w${testInfo.workerIndex}.html`);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    await download.saveAs(file);
    try {
      const view = await context.newPage();
      await view.goto('file://' + file);
      await expect(view).toHaveTitle(NAME + ' — Dashboard Snapshot');
      await expect(view.locator('h1')).toHaveText(NAME);

      await context.grantPermissions(['clipboard-read', 'clipboard-write']);
      await view.click('.tab-btn[data-tab="samplers"]');
      await view.click('button:has-text("Copy Summary")');
      await expect.poll(() => copiedCaption(view)).toBe(NAME + ' — Dashboard Summary');
    } finally {
      fs.unlinkSync(file);
    }
  });
});
