// @ts-check
const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: '.',
  testMatch: '*.spec.js',
  timeout: 30000,
  retries: process.env.CI ? 1 : 0,

  // Parallel is safe here. Each worker starts its own mock server on its own
  // OS-assigned port (see fixtures.js) and baseURL points at it, so the
  // /test/send-* fan-out can no longer leak between specs. Before that
  // isolation existed this had to run at workers:1 (~2.9m); it now runs in
  // roughly 40s. If specs ever interfere again, suspect something reaching a
  // server other than its own worker's — not the worker count.
  fullyParallel: true,

  reporter: [
    // open:'always' launches a browser when the run finishes, which blocks
    // scripted and CI runs. Generate the report; let the caller open it.
    ['html', { open: 'never', outputFolder: 'playwright-report' }],
    ['list'],
  ],
  use: {
    // baseURL is supplied per-worker by fixtures.js.

    // Recording all ~125 passing tests costs real time and disk for artifacts
    // nobody reads. Keep evidence only where it helps: failures and retries.
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    trace: 'on-first-retry',
  },
  // Chromium only by default. PW_BROWSERS=firefox,webkit runs those instead; the
  // release check (scripts/test-release.sh) does it once with the @smoke subset.
  projects: (process.env.PW_BROWSERS || 'chromium').split(',')
    .map((b) => ({ name: b.trim(), use: { browserName: b.trim() } })),

  // No `webServer`: the server is a per-worker fixture, not a shared singleton.
});
