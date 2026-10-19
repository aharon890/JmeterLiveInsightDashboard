// @ts-check
/**
 * Per-worker test-server isolation.
 *
 * The suite used to run with `workers: 1`. The reason was real: the mock
 * server's /test/send-* endpoints broadcast to EVERY connected SSE client, so
 * with a single shared server the specs fed each other data — a 'testEnded'
 * from idle-timeout.spec flipped another spec's badge to ENDED, and a live
 * bucket flipped it back. That produced ~8 failures in a shifting,
 * order-dependent set.
 *
 * Rather than route sends to one client, each worker now gets its OWN server on
 * its own OS-assigned port, and baseURL is pointed at it. Specs are unchanged:
 * they all navigate to '/' and drive the server via
 * page.evaluate(() => fetch('/test/send-bucket', ...)) — a page-relative URL, so
 * every request automatically lands on that page's own origin. Cross-talk is
 * impossible because the servers share no state.
 *
 * Every test also fails when the page throws, or logs an error of its own (see pageErrors).
 *
 * Import { test, expect } from here instead of '@playwright/test'.
 */
const base = require('@playwright/test');
const { startServer } = require('./setup/test-server');
const { countShortTimers } = require('./setup/dashboard');

const test = base.test.extend({
  // Worker-scoped: one server per worker process, reused by every test in it,
  // torn down when the worker exits.
  testServer: [async ({}, use) => {
    const server = await startServer(0);
    await use(server);
    await server.close();
  }, { scope: 'worker' }],

  // Override Playwright's built-in baseURL so page.goto('/') and every
  // page-relative fetch resolve to this worker's server.
  baseURL: async ({ testServer }, use) => {
    await use(`http://127.0.0.1:${testServer.port}`);
  },

  // Fails the test when a page in its context throws, or logs an error of its own: the
  // dashboard catches a failed chart redraw and logs 'refreshCharts:', so a broken chart
  // left the page running and passed every test that only looked for its element. The
  // browser's own "Failed to load resource" lines don't count: a server-gone test expects
  // them. Warnings never count. Pinned by page-errors.spec.js.
  //
  // Before checking, it lets each page finish the work its last click put off (see settled() in
  // setup/dashboard.js): a test that ended right after a tab click never saw that tab's charts
  // drawn, so an error drawing them could not fail it.
  pageErrors: [async ({ context }, use) => {
    const errors = [];
    await context.addInitScript(countShortTimers);
    context.on('weberror', (e) => errors.push(`uncaught: ${e.error().message}`));
    context.on('console', (m) => {
      if (m.type() === 'error' && !m.text().startsWith('Failed to load resource')) errors.push(`console.error: ${m.text()}`);
    });
    await use(errors);
    for (const page of context.pages()) {
      // no count (not the dashboard, or page.clock runs its timers): nothing to wait for
      await page.waitForFunction(() => !(window.__pendingShortTimers && window.__pendingShortTimers()), null, { timeout: 5_000 })
        .catch((e) => { if (!page.isClosed()) errors.push(`still busy when the test ended: ${e.message.split('\n')[0]}`); });
    }
    base.expect(errors, 'errors in the page').toEqual([]);
  }, { auto: true }],
});

module.exports = { test, expect: base.expect };
