// @ts-check
const { test, expect } = require('./fixtures');
const { openDashboard, sendToPage } = require('./setup/dashboard');
const path = require('path');
const fs = require('fs');

/**
 * The exported snapshot embeds the whole dataset in a <script> block. JSON.stringify
 * escapes for JSON, not for HTML, and the HTML tokenizer ends a <script> element at the
 * first `</script` regardless of JS string context.
 *
 * Everything in that payload comes from sample data: sampler labels from the plan, but
 * responseCode/responseMessage/requestUrl from the SYSTEM UNDER TEST. So a broken or
 * hostile endpoint — not a hostile operator — can reach it, and the snapshot is the
 * artifact people email and open from file://.
 */
test.describe('Snapshot script injection', () => {
  const PAYLOAD = '</script><script>window.__PWNED=1;<\/script>';

  const catchupWith = (responseMessage, sampler) => {
    const base = JSON.parse(fs.readFileSync(
      path.join(__dirname, 'fixtures', 'catchup-basic.json'), 'utf8'));
    const proto = Object.values(base.samplerStats)[0];
    base.testName = 'Injection Test';
    base.knownSamplers = [sampler];
    base.samplerStats = { [sampler]: { ...proto, name: sampler } };
    base.recentErrors = [{
      timestamp: 1711900020500, sampler, responseCode: '500',
      responseMessage, requestUrl: 'http://example.com/x',
    }];
    base.errorSummary = { byCode: { '500': 1 }, bySampler: { [sampler]: 1 } };
    return base;
  };

  async function exportAndOpen(browser, payload, sampler, tmpName, testInfo) {
    const ctx = await browser.newContext({ acceptDownloads: true });
    const page = await ctx.newPage();
    await openDashboard(page);
    await sendToPage(page, 'catchup', catchupWith(payload, sampler));

    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.click('button:has-text("Export Snapshot")'),
    ]);
    // Worker-scoped path: a fixed one lets parallel workers unlink a file another is reading.
    const file = path.join(__dirname, 'tmp', `inj-w${testInfo.workerIndex}-${tmpName}.html`);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    await download.saveAs(file);
    await page.close();

    const view = await ctx.newPage();
    const pageErrors = [];
    view.on('pageerror', e => pageErrors.push(e.message));
    await view.goto('file://' + file);
    return { ctx, view, pageErrors, html: fs.readFileSync(file, 'utf8'), file };
  }

  test('a </script> in a response message cannot execute or break the snapshot @smoke',
    async ({ browser }, testInfo) => {
      const { ctx, view, pageErrors, html, file } =
        await exportAndOpen(browser, PAYLOAD, 'GET /ok', 'msg', testInfo);

      // The three things that went wrong before: the script ran, the data blob was
      // destroyed, and every table went empty while the page still looked finished.
      expect(await view.evaluate(() => window.__PWNED === 1)).toBe(false);
      expect(pageErrors).toEqual([]);
      expect(await view.evaluate(() => typeof window.D)).toBe('object');
      await expect(view.locator('#errorTableBody tr')).toHaveCount(1);

      // The raw sequence must not survive into the file at all.
      expect(html).not.toContain('</script><script>');

      await ctx.close();
      fs.unlinkSync(file);
    });

  test('a </script> in a sampler label is equally contained', async ({ browser }, testInfo) => {
    const { ctx, view, pageErrors, file } =
      await exportAndOpen(browser, 'plain message', PAYLOAD, 'label', testInfo);

    expect(await view.evaluate(() => window.__PWNED === 1)).toBe(false);
    expect(pageErrors).toEqual([]);
    expect(await view.evaluate(() => typeof window.D)).toBe('object');

    await ctx.close();
    fs.unlinkSync(file);
  });

  test('the escaped payload still round-trips to the original text', async ({ browser }, testInfo) => {
    // Escaping < as < is valid JSON *and* valid JS and must parse back identically —
    // otherwise the fix would silently corrupt error messages instead of the page.
    const { ctx, view, file } = await exportAndOpen(browser, PAYLOAD, 'GET /ok', 'roundtrip', testInfo);

    const recovered = await view.evaluate(() => window.D.recentErrors[0].responseMessage);
    expect(recovered).toBe('</script><script>window.__PWNED=1;</script>');

    await ctx.close();
    fs.unlinkSync(file);
  });
});
