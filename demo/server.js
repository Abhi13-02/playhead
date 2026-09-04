'use strict';

/**
 * Demo control panel — the backend.
 *
 * Deliberately separate from the Java service and deliberately dependency-free (Node's built-ins
 * only, no npm install). It is a demo harness, not part of the system under test: nothing in
 * `src/` knows this exists, and deleting this folder leaves the service untouched.
 *
 * It does two things the service cannot do for itself:
 *
 *   1. Generates load at a rate that can be changed live. k6 can generate far more traffic, but its
 *      arrival rate is fixed for the run — changing it means restarting. Driving requests from here
 *      instead means a slider can move and the graphs respond within a second, which is the whole
 *      point of the panel. k6 stays the tool for real benchmarks (BENCHMARKS.md); this is for
 *      showing behaviour, not for measuring it.
 *
 *   2. Kills and restarts dependencies, so the phase-6 chaos drills can be re-run by clicking
 *      rather than by typing `docker kill` in a second terminal.
 *
 * Runs on the host, not in a container, for the same reason ScalingController does: its job is to
 * drive `docker`, and a container that stops its own siblings is a needless complication.
 *
 *   node demo/server.js     then open http://localhost:4000
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const { execFile } = require('child_process');

const PANEL_PORT = 4000;
const TARGET = { host: 'localhost', port: 8080 };

// Only these containers can be touched, and only in these ways. The panel takes a name from the
// browser, so it picks from this map rather than passing anything through to a shell.
const CONTAINERS = {
  redis: 'playhead-redis',
  postgres: 'playhead-postgres',
  kafka: 'playhead-kafka',
};

// One keep-alive pool. Without it every request pays a fresh TCP handshake and the panel measures
// its own connection setup instead of the service.
const agent = new http.Agent({ keepAlive: true, maxSockets: 512 });

const PROFILES = Array.from({ length: 500 }, (_, i) => `p-${i}`);
const TITLES = Array.from({ length: 50 }, (_, i) => `t-${i}`);
const pick = (a) => a[Math.floor(Math.random() * a.length)];

/** Live target rates, in requests/sec. Changed by the slider, read by the tickers. */
const rates = { write: 0, resume: 0, browse: 0 };

/** Rolling counts, reset each second, so the panel can show what it is actually achieving. */
let counters = { sent: 0, ok: 0, shed: 0, failed: 0 };
let lastSecond = { sent: 0, ok: 0, shed: 0, failed: 0 };

function fire(options, body) {
  counters.sent++;
  const req = http.request({ ...options, agent, host: TARGET.host, port: TARGET.port }, (res) => {
    if (res.statusCode === 429) counters.shed++;
    else if (res.statusCode >= 200 && res.statusCode < 400) counters.ok++;
    else counters.failed++;
    res.resume(); // drain, or sockets are never released back to the pool
  });
  req.on('error', () => counters.failed++);
  req.setTimeout(10000, () => req.destroy());
  if (body) req.write(body);
  req.end();
}

function sendHeartbeat() {
  const body = JSON.stringify({
    profileId: pick(PROFILES),
    titleId: pick(TITLES),
    deviceId: 'demo-panel',
    positionSeconds: Math.floor(Math.random() * 3600),
    durationSeconds: 3600,
    clientTimestamp: Date.now(),
    sequence: Date.now(),
  });
  fire({
    method: 'POST',
    path: '/v1/playback/heartbeat',
    headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
  }, body);
}

const sendResume = () => fire({
  method: 'GET',
  path: `/v1/playback/resume/${pick(TITLES)}`,
  headers: { 'X-Profile-Id': pick(PROFILES) },
});

const sendBrowse = () => fire({
  method: 'GET',
  path: '/v1/playback/continue-watching',
  headers: { 'X-Profile-Id': pick(PROFILES) },
});

/**
 * Spreads each second's requests over 10 slices rather than firing them all on the second. A
 * thousand simultaneous sockets every 1000 ms would measure the burst, not the rate.
 */
function startTicker(kind, send) {
  const SLICES_PER_SECOND = 10;
  setInterval(() => {
    const perSlice = rates[kind] / SLICES_PER_SECOND;
    let whole = Math.floor(perSlice);
    if (Math.random() < perSlice - whole) whole++; // keep fractional rates honest over time
    for (let i = 0; i < whole; i++) send();
  }, 1000 / SLICES_PER_SECOND);
}

startTicker('write', sendHeartbeat);
startTicker('resume', sendResume);
startTicker('browse', sendBrowse);

setInterval(() => {
  lastSecond = counters;
  counters = { sent: 0, ok: 0, shed: 0, failed: 0 };
}, 1000);

function docker(args) {
  return new Promise((resolve) => {
    execFile('docker', args, { timeout: 20000 }, (err, stdout, stderr) => {
      resolve({ ok: !err, output: (stdout || '') + (stderr || '') });
    });
  });
}

async function containerStates() {
  const { output } = await docker(['ps', '-a', '--format', '{{.Names}}\t{{.State}}']);
  const states = {};
  for (const line of output.trim().split('\n')) {
    const [name, state] = line.split('\t');
    states[name] = state;
  }
  return Object.fromEntries(
    Object.entries(CONTAINERS).map(([key, name]) => [key, states[name] || 'missing']),
  );
}

function json(res, code, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve) => {
    let raw = '';
    req.on('data', (c) => { raw += c; });
    req.on('end', () => {
      try { resolve(JSON.parse(raw || '{}')); } catch { resolve({}); }
    });
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');

  if (url.pathname === '/' || url.pathname === '/index.html') {
    const file = fs.readFileSync(path.join(__dirname, 'public', 'index.html'));
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    return res.end(file);
  }

  if (url.pathname === '/api/status') {
    return json(res, 200, { rates, lastSecond, containers: await containerStates() });
  }

  if (url.pathname === '/api/load' && req.method === 'POST') {
    const body = await readBody(req);
    for (const kind of ['write', 'resume', 'browse']) {
      if (typeof body[kind] === 'number' && body[kind] >= 0) {
        rates[kind] = Math.min(body[kind], 5000); // a ceiling, so the panel cannot wedge the laptop
      }
    }
    return json(res, 200, { rates });
  }

  if (url.pathname === '/api/chaos' && req.method === 'POST') {
    const { target, action } = await readBody(req);
    const container = CONTAINERS[target];
    if (!container || !['kill', 'start'].includes(action)) {
      return json(res, 400, { error: 'unknown target or action' });
    }
    const result = await docker([action, container]);
    return json(res, result.ok ? 200 : 500, { target, action, ...result });
  }

  res.writeHead(404);
  res.end('not found');
});

server.listen(PANEL_PORT, () => {
  console.log(`demo control panel:  http://localhost:${PANEL_PORT}`);
  console.log(`driving traffic at:  http://${TARGET.host}:${TARGET.port}`);
});
