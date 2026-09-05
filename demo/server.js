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

/**
 * The generator needs the same backpressure the service has, for the same reason.
 *
 * Without this the tickers keep firing at the requested rate regardless of whether responses are
 * coming back, so when the target saturates, pending requests pile up in Node's heap until it dies.
 * That is exactly what happened at 15,000 req/s: the panel process aborted with "JavaScript heap
 * out of memory" after climbing to a 4 GB heap, and took the demo down with it.
 *
 * Requests beyond the cap are dropped and counted as `skipped` rather than queued. Counting them
 * separately matters: `skipped` means *this panel* could not send it, `shed` means the *service*
 * refused it. Merging the two would make generator saturation look like admission control working.
 */
/*
 * 3,000 was the first value and it was too high to be useful: every slot filled with a request the
 * saturated service would take seconds to answer, so slots never freed, throughput fell to zero and
 * latency pinned at the timeout. The panel showed a jammed system rather than a shedding one.
 *
 * 500 is sized from Little's Law against what this machine actually serves — a few thousand
 * requests/sec at tens of milliseconds — so slots turn over fast enough that traffic keeps flowing
 * and the service's own 429s stay visible, which is the behaviour worth watching.
 */
const MAX_IN_FLIGHT = 500;
let inFlight = 0;

/**
 * Rolling counts, reset each second, so the panel can show what it is actually achieving.
 *
 * `shed` is split by which layer refused the request, because since D-035 they are different
 * layers with different meanings and a single number hides the more interesting one. nginx enforces
 * the fleet-wide ceiling and cannot multiply with replica count; the application enforces what one
 * container can serve. Stock nginx cannot export its `limit_req` rejections to Prometheus at all,
 * so this panel is the only place the split is visible — see demo/monitoring/prometheus.yml.
 */
const EMPTY = { sent: 0, ok: 0, shedNginx: 0, shedApp: 0, failed: 0, skipped: 0 };
let counters = { ...EMPTY };
let lastSecond = { ...EMPTY };

function fire(options, body) {
  if (inFlight >= MAX_IN_FLIGHT) {
    counters.skipped++;
    return;
  }
  counters.sent++;
  inFlight++;

  let settled = false;
  const done = (bucket) => {
    if (settled) return; // an error after a response would double-count and desync inFlight
    settled = true;
    inFlight--;
    counters[bucket]++;
  };

  const req = http.request({ ...options, agent, host: TARGET.host, port: TARGET.port }, (res) => {
    if (res.statusCode === 429) {
      // Which layer refused it. nginx serves its own HTML error page; the Spring controller returns
      // a zero-length body. Reading the body is the only way to tell them apart from out here, and
      // the distinction is the whole point of the two-tier design, so it is worth the read.
      let body = '';
      res.on('data', (c) => { if (body.length < 200) body += c; });
      res.on('end', () => done(body.includes('nginx') ? 'shedNginx' : 'shedApp'));
      return;
    }
    if (res.statusCode >= 200 && res.statusCode < 400) done('ok');
    else done('failed');
    res.resume(); // drain, or sockets are never released back to the pool
  });
  req.on('error', () => done('failed'));
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
  counters = { ...EMPTY };
}, 1000);

function docker(args) {
  return new Promise((resolve) => {
    execFile('docker', args, { timeout: 20000 }, (err, stdout, stderr) => {
      resolve({ ok: !err, output: (stdout || '') + (stderr || '') });
    });
  });
}

/**
 * How many app replicas are actually running, counted from Docker rather than from Prometheus.
 *
 * Prometheus is the wrong source for this in the panel: it reports what it can *scrape*, which is a
 * question about service discovery, and it was reporting 1 while 3 replicas ran. Docker is the
 * authority on how many containers exist, so the panel asks it directly and the two numbers can be
 * compared — if they ever disagree again, that is itself the bug worth seeing.
 */
async function appReplicas() {
  const { output } = await docker([
    'ps', '--filter', 'name=playhead-app', '--filter', 'status=running', '--format', '{{.Names}}',
  ]);
  return output.trim() ? output.trim().split('\n').filter(Boolean).length : 0;
}

/**
 * Scales the app service, which is the same `docker compose up --scale` the pre-scaling controller
 * runs — this button is a manual hand on the same lever, not a second mechanism.
 *
 * COMPOSE_FILES matters for the same reason it does in ScalingController (D-035): omitting `-f`
 * scales the base definition, so replicas started from the capped production overlay would come up
 * uncapped and silently discard their limits at the moment the fleet grows.
 */
const COMPOSE_FILES = (process.env.COMPOSE_FILES || 'docker-compose.yml').split(',');
const REPO_ROOT = path.join(__dirname, '..');
const MAX_REPLICAS = 4;

async function scaleTo(n) {
  const args = [];
  for (const f of COMPOSE_FILES) args.push('-f', f.trim());
  args.push('up', '-d', '--no-recreate', '--scale', `app=${n}`);
  return new Promise((resolve) => {
    execFile('docker', ['compose', ...args], { cwd: REPO_ROOT, timeout: 120000 },
      (err, stdout, stderr) => resolve({ ok: !err, output: (stdout || '') + (stderr || '') }));
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
    const [containers, replicas] = await Promise.all([containerStates(), appReplicas()]);
    return json(res, 200, { rates, lastSecond, inFlight, containers, replicas, maxReplicas: MAX_REPLICAS });
  }

  if (url.pathname === '/api/scale' && req.method === 'POST') {
    const { replicas } = await readBody(req);
    if (!Number.isInteger(replicas) || replicas < 1 || replicas > MAX_REPLICAS) {
      return json(res, 400, { error: `replicas must be an integer 1..${MAX_REPLICAS}` });
    }
    const result = await scaleTo(replicas);
    return json(res, result.ok ? 200 : 500, { replicas, ...result });
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
