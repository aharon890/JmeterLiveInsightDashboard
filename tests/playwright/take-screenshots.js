const { chromium } = require('playwright');
const path = require('path');

const IMG_DIR = path.resolve(__dirname, '../../docs/images');
// Point at a real run for the docs: BASE_URL=http://localhost:7566 while a
// plan is running. The default is the mock server (`node setup/test-server.js`),
// whose two-bucket fixture draws flat lines and a nonsense elapsed time.
const BASE = process.env.BASE_URL || 'http://localhost:7577';

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });

  // The footer is position:fixed, so a fullPage capture paints it where the
  // 800px viewport ended — across the middle of the charts. Unpin it for those.
  const fullPageShot = async (file) => {
    const unpin = await page.addStyleTag({ content: '.dashboard-footer{position:static!important}' });
    await page.screenshot({ path: path.join(IMG_DIR, file), fullPage: true });
    await unpin.evaluate(el => el.remove());
  };

  await page.goto(BASE);
  // The header's elapsed time stays "--:--:--" until the first live update
  // after the catch-up (up to one update interval), so wait for it rather than
  // for a fixed delay.
  await page.waitForFunction(() => !document.getElementById('elapsed').textContent.includes('--'),
    null, { timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(500);

  // 1. Full dashboard - Summary tab (light mode)
  await page.screenshot({ path: path.join(IMG_DIR, 'summary-light.png'), fullPage: false });

  // 2. Header close-up
  const header = page.locator('.report-header');
  await header.screenshot({ path: path.join(IMG_DIR, 'header.png') });

  // 3. Filter bar
  const filterBar = page.locator('.filter-bar');
  await filterBar.screenshot({ path: path.join(IMG_DIR, 'filter-bar.png') });

  // 4. Summary tab - dark mode
  await page.click('button[title="Toggle dark mode"]');
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(IMG_DIR, 'summary-dark.png'), fullPage: false });
  // Switch back to light
  await page.click('button[title="Toggle dark mode"]');
  await page.waitForTimeout(300);

  // 5. Timeline tab
  await page.click('.tab-btn[data-tab="timeline"]');
  await page.waitForTimeout(500);
  await fullPageShot('timeline-light.png');

  // 6. Timeline - dark mode
  await page.click('button[title="Toggle dark mode"]');
  await page.waitForTimeout(500);
  await fullPageShot('timeline-dark.png');
  await page.click('button[title="Toggle dark mode"]');
  await page.waitForTimeout(300);

  // 7. Samplers tab
  await page.click('.tab-btn[data-tab="samplers"]');
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(IMG_DIR, 'samplers-light.png'), fullPage: false });

  // 8. Samplers - expanded detail row
  const firstRow = page.locator('#samplerTableBody tr.expandable').first();
  await firstRow.click();
  await page.waitForTimeout(500);
  await fullPageShot('samplers-detail.png');

  // 9. Errors tab
  await page.click('.tab-btn[data-tab="errors"]');
  await page.waitForTimeout(500);
  await fullPageShot('errors-light.png');

  // 10. Footer
  const footer = page.locator('footer.dashboard-footer');
  await footer.screenshot({ path: path.join(IMG_DIR, 'footer.png') });

  await browser.close();
  console.log('Screenshots saved to docs/images/');
})();
