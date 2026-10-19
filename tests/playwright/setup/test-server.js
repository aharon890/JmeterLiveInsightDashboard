/**
 * Mock SSE server for Playwright tests.
 * Serves the actual dashboard HTML and provides controllable SSE endpoints.
 *
 * Exported as a factory rather than a module-level listen() so each Playwright
 * worker can own an isolated instance. The /test/send-* endpoints broadcast to
 * every client of THEIR OWN server only, which is what makes running the suite
 * in parallel safe — see fixtures.js.
 */
const express = require('express');
const path = require('path');
const fs = require('fs');

const dashboardPath = path.resolve(__dirname, '../../../src/main/resources/dashboard/index.html');
const echartsPath = path.resolve(__dirname, '../../../src/main/resources/dashboard/echarts.min.js');
const catchupFixturePath = path.resolve(__dirname, '../fixtures/catchup-basic.json');

function createApp() {
  const app = express();

  // Per-instance, NOT module-level: two servers in one process must not share
  // a client list, or the isolation the workers rely on silently disappears.
  let sseClients = [];

  app.get('/', (req, res) => {
    res.sendFile(dashboardPath);
  });

  app.get('/echarts.min.js', (req, res) => {
    res.setHeader('Content-Type', 'application/javascript');
    res.sendFile(echartsPath);
  });

  // SSE endpoint
  app.get('/api/events', (req, res) => {
    res.setHeader('Content-Type', 'text/event-stream');
    res.setHeader('Cache-Control', 'no-cache');
    res.setHeader('Connection', 'keep-alive');
    res.flushHeaders();

    sseClients.push(res);

    // Send default catchup immediately. Read from disk rather than require():
    // require() caches on first load, so a long-lived server process would keep
    // serving the OLD fixture after it was edited — silently testing against
    // stale data. Reading per connection keeps fixture edits effective.
    const catchup = JSON.parse(fs.readFileSync(catchupFixturePath, 'utf8'));
    res.write(`event: catchup\ndata: ${JSON.stringify(catchup)}\n\n`);

    req.on('close', () => {
      sseClients = sseClients.filter(c => c !== res);
    });
  });

  // API endpoints
  app.get('/api/status', (req, res) => {
    res.json({ testName: 'Test', testRunning: true, totalSamples: 100, totalErrors: 2 });
  });

  app.get('/api/config', (req, res) => {
    res.json({ updateIntervalMs: 2000, maxRetentionMs: 60000 });
  });

  app.get('/api/history', (req, res) => {
    res.json([]);
  });

  // Record what the stop buttons actually send (path + token header) so specs
  // can assert a request was — or was NOT — issued. Per-instance for the same
  // isolation reason as sseClients; workers share a server across their specs,
  // so specs reset before acting rather than assuming an empty log.
  let stopRequests = [];

  app.post('/api/stop', (req, res) => {
    stopRequests.push({ path: '/api/stop', token: req.headers['x-stop-token'] || null });
    res.json({ status: 'stopping' });
  });

  app.post('/api/stop-now', (req, res) => {
    stopRequests.push({ path: '/api/stop-now', token: req.headers['x-stop-token'] || null });
    res.json({ status: 'stopped' });
  });

  app.get('/test/stop-requests', (req, res) => {
    res.json(stopRequests);
  });

  app.post('/test/reset-stop-requests', (req, res) => {
    stopRequests = [];
    res.json({ ok: true });
  });

  // Test control endpoints.
  //
  // A spec can POST here before the page under test has finished registering its
  // SSE connection — page.goto() resolves on document load, but the EventSource
  // handshake completes a moment later. Broadcasting into an empty client list
  // drops the message silently and the assertion then fails on stale UI, which is
  // what made bucket/event tests flaky and order-dependent. Wait briefly for a
  // client so a send is never delivered to nobody.
  function waitForClient(timeoutMs = 3000) {
    if (sseClients.length > 0) return Promise.resolve();
    return new Promise(resolve => {
      const deadline = Date.now() + timeoutMs;
      const tick = () => {
        if (sseClients.length > 0 || Date.now() >= deadline) resolve();
        else setTimeout(tick, 20);
      };
      tick();
    });
  }

  function broadcast(name) {
    return async (req, res) => {
      await waitForClient();
      const payload = `event: ${name}\ndata: ${JSON.stringify(req.body)}\n\n`;
      sseClients.forEach(c => c.write(payload));
      res.json({ sent: sseClients.length });
    };
  }

  // 20 MB, not express's default 100 KB: a spec that checks the Timeline's grouping needs
  // runs long enough to hold several 1-minute points, and the older minutes beside them.
  const body = express.json({ limit: '20mb' });
  app.post('/test/send-bucket', body, broadcast('bucket'));
  app.post('/test/send-event', body, broadcast('event'));
  app.post('/test/send-catchup', body, broadcast('catchup'));

  function endClients() {
    sseClients.forEach(c => { try { c.end(); } catch (e) { /* already gone */ } });
    sseClients = [];
  }

  // Drop every SSE connection, as the real server does when it stops after
  // sending serverClosing (LiveDashboardListener.startDashboard). Without the
  // drop the page has nothing to reconnect from, so a "no reconnect" check
  // can't fail.
  app.post('/test/end-clients', (req, res) => {
    endClients();
    res.json({ ok: true });
  });

  return { app, endClients };
}

/**
 * Start a server and resolve once it is accepting connections.
 *
 * @param {number} port  0 (the default) asks the OS for a free ephemeral port,
 *                       which removes port collisions entirely — including with
 *                       a stale server left over from an earlier run.
 * @returns {Promise<{port: number, close: () => Promise<void>}>}
 */
function startServer(port = 0) {
  const { app, endClients } = createApp();
  return new Promise((resolve, reject) => {
    const server = app.listen(port, '127.0.0.1');
    server.on('error', reject);
    server.on('listening', () => {
      resolve({
        port: server.address().port,
        close: () => new Promise(res => {
          // Open SSE responses keep the socket alive, so close() would hang
          // forever without ending them first.
          endClients();
          server.close(() => res());
        }),
      });
    });
  });
}

module.exports = { createApp, startServer };

// Manual use: `node setup/test-server.js` still serves on the historical port
// so the dashboard can be poked at by hand without Playwright.
if (require.main === module) {
  startServer(7577).then(({ port }) => {
    console.log(`Test server running on http://localhost:${port}`);
  });
}
