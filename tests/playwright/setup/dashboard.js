/**
 * Opening the live dashboard, and knowing when it is ready.
 *
 * The mock server sends its catch-up the moment the page connects, and the page takes it in
 * one go: about 10 ms after the header shows (measured 2026-10-15, median 9 ms, worst 12 ms).
 * Specs slept 300-800 ms here instead. Those sleeps were 90 s of the suite's ~400 s, too long
 * on a quiet machine and too short on a busy one, where a regression timed out in this step.
 * Waiting for the data itself is both.
 */

/** Resolves once the page is connected and the catch-up's slots are in it. */
async function dashboardReady(page) {
  await page.waitForFunction(() => window.S && S.connected && S.timestamps.length > 0, null, { timeout: 10_000 });
}

/** Opens the dashboard on this worker's mock server and waits until its data is in the page. */
async function openDashboard(page) {
  await page.goto('/');
  await dashboardReady(page);
}

/**
 * Sends a message through the mock server ('bucket', 'catchup' or 'event', as the page's listeners
 * name them) and resolves once this page has handled it and drawn the result. A listener added
 * here runs after the page's own for the same message, and a zero timeout lets the chart redraw
 * the page queued (scheduleChartRefresh) go first: about 12 ms (measured, worst 19 ms). Specs
 * slept 120-1000 ms instead, too long on a quiet machine and too short on a busy one, where a
 * check that something did NOT change could run before the message arrived and pass for nothing.
 */
async function sendToPage(page, type, body) {
  await page.waitForFunction(() => window.S && S.connected, null, { timeout: 10_000 });
  await page.evaluate(async ([t, b]) => {
    const handled = new Promise((done) => eventSource.addEventListener(t, () => setTimeout(done, 0), { once: true }));
    await fetch(`/test/send-${t}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b) });
    await Promise.race([handled, new Promise((_, no) => setTimeout(() => no(new Error(`the page never handled the '${t}' message`)), 10_000))]);
  }, [type, body]);
}

/**
 * The page puts off some of its work after a click: a tab draws its charts 50 ms later, a filter
 * or display option redraws 150 ms later, an expanded row builds its charts 50 ms later. Specs
 * slept 200-1000 ms after such clicks. Ended there instead, a test would finish before that work
 * ran and never see it fail; clicking on, it would cancel or mask the work it meant to test.
 *
 * Every test's pages count those timers (fixtures.js installs this), so a test can wait for
 * exactly that work. Only timers of 200 ms or less count: the page's longer ones (the 3 s
 * reconnect, the 2 s 'Copied!') are not work a click waits for. Runs in the page, so it must
 * stay self-contained.
 *
 * page.clock replaces the page's timers with its own, which then never pass through here, and
 * keeps one of ours always pending to drive them. The count is null then: nothing to go by.
 */
function countShortTimers() {
  const set = window.setTimeout, clear = window.clearTimeout, pending = new Set();
  const counted = function (fn, ms, ...args) {
    if (typeof fn !== 'function' || (ms || 0) > 200) return set.call(this, fn, ms, ...args);
    const id = set.call(this, (...a) => { try { fn(...a); } finally { pending.delete(id); } }, ms, ...args);
    pending.add(id);
    return id;
  };
  window.setTimeout = counted;
  window.clearTimeout = function (id) { pending.delete(id); return clear.call(this, id); };
  window.__pendingShortTimers = () => (window.setTimeout === counted ? pending.size : null);
}

/** Resolves once the page has run every short timer it set: what the last click put off is done. */
async function settled(page) {
  await page.waitForFunction(() => {
    const n = window.__pendingShortTimers();
    if (n === null) throw new Error("settled() can't see the page's timers while page.clock runs them");
    return n === 0;
  }, null, { timeout: 10_000 });
}

module.exports = { openDashboard, dashboardReady, sendToPage, countShortTimers, settled };
