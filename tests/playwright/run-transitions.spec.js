// @ts-check
const { test, expect } = require('./fixtures');
const { openDashboard, sendToPage } = require('./setup/dashboard');

/**
 * What the page does when a run ENDS and another one takes its place.
 *
 * The CLOSED latch on serverClosing is deliberate and is pinned by
 * server-closing.spec.js — a tab must keep showing run 1's finished results rather
 * than silently switching to run 2. These tests cover the two things that were
 * missing around it: telling the operator a new run exists, and noticing when the
 * server on the other end of a reconnect is a different one.
 */
test.describe('Run transitions', () => {
  test.beforeEach(async ({ page }) => {
    await openDashboard(page);
  });

  const sendCatchup = (page, extra) => sendToPage(page, 'catchup', extra);

  test('idle tooltip states the configured timeout, not a hardcoded 10 minutes', async ({ page }) => {
    // A value that does not divide into whole minutes, so it must be reported verbatim.
    await sendCatchup(page, {
      type: 'catchup', testName: 'Idle Test', testStartTime: Date.now(),
      testRunning: false, knownSamplers: [], samplerStats: {},
      idleTimeoutSeconds: 90,
    });

    let title = await page.locator('#idleInfo').getAttribute('title');
    expect(title).toContain('90 seconds');
    expect(title).not.toContain('10 minutes');

    // And a round value must read naturally rather than as a raw second count — pinning
    // the formatting so it cannot regress into "3600 seconds".
    await sendCatchup(page, {
      type: 'catchup', testName: 'Idle Test', testStartTime: Date.now(),
      testRunning: false, knownSamplers: [], samplerStats: {},
      idleTimeoutSeconds: 600,
    });

    title = await page.locator('#idleInfo').getAttribute('title');
    expect(title).toContain('10 minutes');
  });

  test('an idle timeout of 0 does not promise a shutdown that never happens', async ({ page }) => {
    await sendCatchup(page, {
      type: 'catchup', testName: 'No Idle Test', testStartTime: Date.now(),
      testRunning: false, knownSamplers: [], samplerStats: {},
      idleTimeoutSeconds: 0,
    });

    // idle.timeout=0 is documented as "stay up indefinitely", and the server schedules
    // no timer at all — so the tooltip must not claim it will close automatically.
    const title = await page.locator('#idleInfo').getAttribute('title');
    // Assert the POSITIVE wording, not merely the absence of a digit: the shipped
    // static title has no digits either, so a negative-only assertion also passes
    // when the catchup never arrives at all.
    expect(title).toContain('stay open until JMeter exits');
    expect(title).not.toMatch(/close automatically after \d/);
  });

  test('a new test starting offers a way to follow it', async ({ page }) => {
    await sendToPage(page, 'event', { type: 'event', event: 'serverClosing', data: { reason: 'newTestStarting' } });

    // The latch itself stays — server-closing.spec.js pins that. What is added is a
    // visible route to the new run; without it the tab just says CLOSED forever and
    // the operator has no reason to think a reload would help.
    await expect(page.locator('#liveLabel')).toHaveText('CLOSED');
    await expect(page.locator('#newRunNotice')).toBeVisible();
    await expect(page.locator('#newRunNotice')).toContainText(/reload/i);
  });

  test('an idle shutdown does not offer a reload', async ({ page }) => {
    await sendToPage(page, 'event', { type: 'event', event: 'serverClosing', data: { reason: 'idleTimeout' } });

    // The negative case: there is nothing to reload to, so do not invite it.
    await expect(page.locator('#liveLabel')).toHaveText('CLOSED');
    // toBeHidden() alone is satisfied by a locator matching no node at all, which is
    // why this passed before the element existed. Require present AND hidden.
    await expect(page.locator('#newRunNotice')).toHaveCount(1);
    await expect(page.locator('#newRunNotice')).toBeHidden();
  });

  test('a catchup from a different server warns instead of silently switching', async ({ page }) => {
    await sendCatchup(page, {
      type: 'catchup', testName: 'Run One', testStartTime: Date.now(),
      testRunning: true, knownSamplers: [], samplerStats: {},
      serverInstanceId: 'server-A',
    });
    // toBeHidden() alone is satisfied by a locator matching no node at all, which is
    // why this passed before the element existed. Require present AND hidden.
    await expect(page.locator('#differentRunNotice')).toHaveCount(1);
    await expect(page.locator('#differentRunNotice')).toBeHidden();

    // A reconnect that lands on another JMeter process which took this port: same URL,
    // different run, and today nothing on screen changes except the numbers.
    await sendCatchup(page, {
      type: 'catchup', testName: 'Run Two', testStartTime: Date.now(),
      testRunning: true, knownSamplers: [], samplerStats: {},
      serverInstanceId: 'server-B',
    });

    await expect(page.locator('#differentRunNotice')).toBeVisible();
  });

  test('a reconnect to the same server does not warn', async ({ page }) => {
    for (let i = 0; i < 2; i++) {
      await sendCatchup(page, {
        type: 'catchup', testName: 'Run One', testStartTime: Date.now(),
        testRunning: true, knownSamplers: [], samplerStats: {},
        serverInstanceId: 'server-A',
      });
    }
    // toBeHidden() alone is satisfied by a locator matching no node at all, which is
    // why this passed before the element existed. Require present AND hidden.
    await expect(page.locator('#differentRunNotice')).toHaveCount(1);
    await expect(page.locator('#differentRunNotice')).toBeHidden();
  });
});
