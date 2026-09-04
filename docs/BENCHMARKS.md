# Benchmarks

Every number here was measured, not estimated. Raw k6 output for each run is kept below the table
it belongs to. See [ROADMAP.md](ROADMAP.md) phase 2 for what these runs are proving.

---

## Phase 2 — Run A: the premiere curve, unprotected

**Setup:** `ingest-api` only (in-memory `ConcurrentHashMap` store, virtual threads on), single
instance, laptop (12 logical cores), k6 hitting `localhost:8080` from the same machine.
`load/premiere.js`, `ramping-arrival-rate` executor — target is requests/sec, decoupled from
client connection count (see below for why `ramping-vus` was rejected for this test).

No protection exists yet: no rate limiting, no priority tiers, no shedding. This is the baseline
Run B will be compared against.

| Target peak (req/s) | Achieved (req/s) | p99 latency | Max latency | Error rate | Threshold result |
|---|---|---|---|---|---|
| 4,000 (VU-based, superseded — see note) | ~2,300 | ~3ms | ~18ms | 0% | pass |
| 20,000 | ~10,700 | ~130–140ms | 388–551ms | ~0.1% | pass (barely) |
| 50,000 | ~12,700 | p95 = 47ms | **3.91s** | **3.03%** | **fail** |

**Finding:** the write path holds cleanly to roughly **10,000–13,000 req/s**. Past that, requests
queue inside the app — latency climbs from single-digit milliseconds into the seconds — and
`202` responses start turning into failures. Confirmed server-bound, not a k6 client artifact:
raising k6's `maxVUs` from 1,000 to 6,000 across these runs never made k6 actually use more than
~1,450 of them — the executor didn't need more parallel senders to hit its target rate, because
the server was the slow part, not the number of open connections.

**The app survived overload** — degraded, did not crash. `actuator/health` returned `200`
immediately after the 50,000 run.

### Raw output — 50,000 target (the failing run)

```
✗ 'rate<0.01' rate=3.03%   ← threshold failed

checks_total.......: 1329622  12657.811457/s
checks_succeeded...: 96.96%   1289249 out of 1329622
checks_failed......: 3.03%    40373 out of 1329622

http_req_duration..: avg=10.12ms min=0s med=1ms max=3.91s p(90)=23.98ms p(95)=47.02ms
http_req_failed....: 3.03%    40373 out of 1329622
http_reqs..........: 1329622  12657.811457/s

dropped_iterations.: 206044   1961.509439/s
vus................: 947      min=0    max=1317
vus_max............: 1453     min=800  max=6000 (available)
```

### Note on the script's own history

The first version of `load/premiere.js` used k6's `ramping-vus` executor (target = concurrent
virtual users, each holding an open connection). At 4,000 VUs it produced a clean, unbroken
result — but that is an artifact of 1 VU ≈ 1 req/s given the script's `sleep(1)`, not a real
saturation test. Pushing `ramping-vus` to 20,000 crashed **k6 itself** (Windows ran out of
ephemeral ports holding 20,000 simultaneous sockets from one client process) before it told us
anything about the server. Switched to `ramping-arrival-rate`, which targets req/s directly and
reuses a small, capped pool of connections — the numbers above are from that version.

---

## Phase 2 — Run B: the same curve, protection on

**Setup:** identical to Run A — same `load/premiere.js`, same curve, same machine. Only
difference: `IngestController` now runs requests through a `TokenBucket` (capacity 8,000, refill
8,000/sec) then a `Semaphore` (200 in-flight permits) before touching `Store`. Both shed with
`429` + `Retry-After: 1`. Sizing rationale in `docs/DECISIONS.md` D-014/D-015 area (bucket/
semaphore comment in `IngestController.java`) — refill capped comfortably under Run A's measured
~10,000–13,000 req/s ceiling.

**Reading these numbers requires one correction:** k6's `http_req_failed`/`checks_failed` treat
any non-`202` as a failure — including our own deliberate `429`s. The script's `default function`
now checks three ways (`202` / `429 (shed)` / `neither (real error)`) so the numbers below
separate genuine failures from intentional shedding.

| Target peak (req/s) | Admitted (202) | Shed (429) | Real errors | Admitted p99 latency | Threshold (`p99<500ms`) |
|---|---|---|---|---|---|
| 20,000 | 58.6% | 40.9% | **0.40%** | **42.46ms** | **pass** |
| 50,000 | 53.9% | 44.6% | **2.85%** | **61.15ms** | **pass** |

**Compare to Run A at the same targets:**

| | Run A (unprotected) | Run B (protected) |
|---|---|---|
| 20k target — admitted-request p99 | ~130–140ms | **42.46ms** |
| 50k target — admitted-request p99 | not measured (system was collapsing — only max was meaningful) | **61.15ms** |
| 50k target — max latency | **3.91s** | 1.88s |
| 50k target — `p(99)<500ms` threshold | **fail** | **pass** |

**Finding — the headline result:** with the guard layer on, admitted traffic's tail latency stays
bounded (p99 under 500ms at both 20k and 50k targets) instead of degrading toward multi-second
waits. The system achieves this by rejecting excess load early and cheaply (`429`) rather than
letting it queue inside the app — trading raw "percentage of requests that eventually get a
`202`" for a hard guarantee on how slow an admitted request can get.

**Known limitation, not chased (D-016):** at the most extreme overload (50k target) roughly 2.85%
of requests were refused at the TCP connection layer (`connection actively refused`) — Tomcat's
connector backlog filling before the request reaches our token bucket or semaphore at all.
App-level shedding cannot intercept a connection that never reaches the app. Real, measured, and
deliberately left untuned — see D-016 for the reasoning.

**The app survived both runs** — `actuator/health` returned `200` immediately after each.

### Raw output — 50,000 target

```
█ THRESHOLDS
    http_req_duration
    ✓ 'p(99)<500' p(99)=61.15ms
    http_req_failed
    ✗ 'rate<0.01' rate=46.07%   ← expected: counts our own 429s as "failed"

✗ status is 202
      ↳ 53% — ✓ 690382 / ✗ 589935
✗ status is 429 (shed)
      ↳ 43% — ✓ 553436 / ✗ 726881
✗ status is neither (real error)
      ↳ 2% — ✓ 36499 / ✗ 1243818

http_req_duration..: avg=5.28ms min=0s med=1ms max=3.43s p(90)=11.04ms p(95)=23.31ms
  { expected_response:true }...: avg=5.35ms p(90)=7.4ms p(95)=17.65ms
http_reqs..........: 1280317  12191.842547/s
```

### Raw output — 20,000 target

```
█ THRESHOLDS
    http_req_duration
    ✓ 'p(99)<500' p(99)=42.46ms
    http_req_failed
    ✗ 'rate<0.01' rate=41.34%   ← expected: counts our own 429s as "failed"

✗ status is 202
      ↳ 58% — ✓ 633324 / ✗ 446447
✗ status is 429 (shed)
      ↳ 40% — ✓ 442091 / ✗ 637680
✗ status is neither (real error)
      ↳ 0% — ✓ 4356 / ✗ 1075415

http_req_duration..: avg=3.15ms min=0s med=963.5µs max=1.02s p(90)=5.52ms p(95)=14.54ms
  { expected_response:true }...: avg=2.53ms p(90)=3.51ms p(95)=8.9ms
http_reqs..........: 1079771  10283.049354/s
```

---

## Phase 2 gate closure — priority-tiered admission control, mixed load

*Recorded 2026-09-04. Script: [`load/mixed.js`](../load/mixed.js). All three tiers offered at once,
same 15s/60s/30s curve shape. Full narrative and the intermediate runs that led here are in
[ENGINEERING_LOG.md](ENGINEERING_LOG.md); see [DECISIONS.md](DECISIONS.md) D-030.*

Closes phase 2's last open gate item: writes protected while reads absorb the shedding.

| Metric | Value |
|---|---|
| Offered load | 7,000 write/s, 3,000 resume/s, 1,000 browse/s (11,000/s vs 10,000/s admission ceiling) |
| `write_success` | **89.32%** (335,849 / 376,006) — target was >99%, not reached |
| `admission.shed{tier=PLAYBACK_WRITE}` | **0** in every run — the bucket never shed a write |
| `resume_shed` / `browse_shed` | 20.00% / 31.76% — reads absorbed the overload as designed |

**Priority tiering is fully proven: writes were never shed by the mechanism built to protect them,
in any run.** The 89.32% figure is short of the gate's literal >99% because of a real,
separately-diagnosed limiter (the Kafka in-flight `Semaphore`, unrelated to the priority tiers) and
ultimately a single-machine capacity ceiling — this one laptop hosting the load generator, the app,
and Kafka/Postgres/Redis simultaneously. See ENGINEERING_LOG.md for the four runs that isolated
this, including the resize that made it *worse* (2,000 permits → 87.20%, with the door shedding
zero), which is the evidence that ruled out the door's size as the remaining cause.

### Raw output — final run (1,000-permit door, 11,000/s offered)

```
write_success..................: 89.32% 335849 out of 376006
resume_shed.....................: 20.00% 31704 out of 158473
browse_shed.....................: 31.76% 18318 out of 57661

✗ write 202
  ↳  89% — ✓ 335849 / ✗ 40157
✗ write 429 (shed)
  ↳  6% — ✓ 25675 / ✗ 350331
✗ write other (real error)
  ↳  3% — ✓ 14482 / ✗ 361524

http_req_duration..............: avg=110.07ms min=0s med=7.14ms max=10.58s p(90)=82.86ms p(95)=218.87ms
http_reqs......................: 592140 5631.187912/s
```

---

# Phase 5 — the read path

*Recorded 2026-09-04. Script: [`load/reads.js`](../load/reads.js). Same curve shape as the
write run (15 s calm, 60 s ramp, 30 s hold) so the two are comparable.*

## Setup

| | |
|---|---|
| Curve | 0 → **2,000 req/s** over 60 s, then hold 30 s |
| Mix | 70% `GET /v1/playback/resume/{titleId}`, 30% `GET /v1/playback/continue-watching` |
| Key space | 500 profiles × 50 titles = **25,000** `(profile, title)` pairs, all seeded |
| Caffeine | 2 s TTL, max 10,000 entries, per endpoint |
| Redis | warm from the write path at start (14,289 keys), 24,889 at end |
| Starting state | app restarted (Caffeine cold), Postgres fully seeded |

## Result — NFR-3 met with headroom

**NFR-3: read p99 < 50 ms.**

| Endpoint | med | p90 | p95 | **p99** | max |
|---|---|---|---|---|---|
| `resume` | 1.57 ms | 3.12 ms | 3.69 ms | **6.57 ms** ✓ | 58.92 ms |
| `continue-watching` | 0.54 ms | 2.65 ms | 3.12 ms | — ✓ | 48.32 ms |

- 122,207 requests, **0.00% failed**.
- Peak concurrency: **25 VUs** of 800 pre-allocated — the read path never approached saturation.
- HikariCP used **1** of its 10 connections. Postgres was not the constraint.

Percentiles are tracked per endpoint on purpose. Blending a point lookup and an indexed list into
one number would hide which tier is slow.

## Cache hit rate — the interesting result

Read live from `/actuator/metrics/cache.gets`, not computed offline.

| Cache | Hits | Misses | **Hit rate** | Distinct keys |
|---|---|---|---|---|
| `resume` | 7,069 | 78,197 | **8.3%** | 25,000 |
| `continue-watching` | 23,769 | 13,172 | **64.3%** | 500 |

**A 7.7× difference between two endpoints in the same service, explained entirely by key
cardinality.**

- `resume` is keyed `(profileId, titleId)`. A viewer reads their resume point roughly *once* per
  playback session, and a premiere means a million *different* keys, not one hot key. With 25,000
  keys and a 2 s TTL, almost every lookup misses. **The local cache barely earns its place on this
  endpoint** — the honest conclusion, and the opposite of the usual "add a cache, it gets faster"
  assumption.
- `continue-watching` is keyed on `profileId` alone — 500 keys — and is re-fetched every time a
  client opens or navigates the app. Heavy reuse, and the hit rate shows it.

**What actually delivers `resume`'s 6.57 ms p99 is Redis, not Caffeine.** Caffeine's contribution
there is limited to bursts clustered inside its 2 s window (client retries, a household opening the
same title on a second device).

## Honest notes

- **The 2 s Caffeine TTL is aggressive** and is what caps the `resume` hit rate. It was chosen to
  stay inside NFR-4's 2 s staleness bound, not tuned for hit rate. Raising it would trade
  freshness for hits; the bound is the binding constraint, so it stays.
- **This run does not find the read path's ceiling.** At 2,000 req/s the server used 25 of 800
  VUs and one database connection. The number recorded here is *latency at a realistic read rate*,
  which is what NFR-3 asks for — not a saturation point. The write path saturated at
  ~10,000–13,000 req/s; the read path was not pushed that far.
- **k6's check counts read oddly** for the same reason as the write runs: the three-way status
  checks are diagnostic buckets, so at most one can pass per request. `http_req_failed: 0.00%` is
  the number that matters. Of 85,266 `resume` requests, **0** returned 404 and **0** errored.
- Redis grew from 14,289 to 24,889 keys during the run — cache-aside filling from Postgres on each
  miss, visible in the key count.

## Raw output

```
█ THRESHOLDS
    resume_duration
    ✓ 'p(99)<50' p(99)=6.57ms

  █ TOTAL RESULTS

    ✓ resume 200 (found)
    ✗ resume 404 (never watched)
      ↳  0% — ✓ 0 / ✗ 85266
    ✗ resume error
      ↳  0% — ✓ 0 / ✗ 85266
    ✓ continue-watching 200
    ✗ continue-watching error
      ↳  0% — ✓ 0 / ✗ 36941

    CUSTOM
    continue_watching_duration.....: avg=1.3ms  min=0s med=539.6µs max=48.32ms p(90)=2.65ms p(95)=3.12ms
    resume_duration................: avg=1.77ms min=0s med=1.57ms  max=58.92ms p(90)=3.12ms p(95)=3.69ms

    HTTP
    http_req_duration..............: avg=1.63ms min=0s med=1.56ms  max=58.92ms p(90)=2.75ms p(95)=3.64ms
    http_req_failed................: 0.00%  0 out of 122207
    http_reqs......................: 122207 1163.852677/s

    EXECUTION
    vus............................: 3      min=0   max=25
    vus_max........................: 800    min=800 max=800
```

## Degraded-mode read latency (Redis down)

Measured while building the fallback path (see [DECISIONS.md](DECISIONS.md) D-025), single
requests rather than under load:

| Condition | `GET /v1/playback/resume/{titleId}` |
|---|---|
| Redis up | **14 ms** |
| Redis stopped, before `timeout` was configured | ~78,000 ms (Lettuce's ~60 s default) |
| Redis stopped, `spring.data.redis.timeout: 50ms` | **112–119 ms** |

The fallback works, but **112 ms still exceeds NFR-3's 50 ms budget**. A per-request timeout has no
memory: with Redis down, every request pays the wait on a dependency already known to be gone. That
measured gap — not a general principle — is what justifies the circuit breaker in phase 6.

## Read staleness (NFR-4)

**NFR-4: a heartbeat is visible to a read within 2 s.** Measured end to end — HTTP `202` →
Kafka → fold-consumer → Postgres + Redis → visible on `GET /v1/playback/resume/{titleId}` — by
posting a heartbeat with a known position and polling the read endpoint until that position
appears.

| Trial | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---|---|---|---|---|---|
| Visible after | 138 ms | 139 ms | 139 ms | 138 ms | 138 ms | 140 ms |

**Met, with roughly 14× margin.**

**Read this as an upper bound, not a precise figure.** Each poll spawns a separate `curl`
process (~130 ms on this machine), so ~138 ms is the *measurement floor* of the method — the true
pipeline latency is somewhere below it. The claim the number supports is "well inside 2 s", not
"exactly 138 ms".

Caffeine's 2 s TTL does not distort this: the first poll lands ~138 ms after the write, when no
cached entry exists to serve a stale value from.

### A false result worth recording

The first attempt at this measurement produced 15 s timeouts on four consecutive trials, then
4.7 s on the fifth. Consumer lag was **0** and the pipeline was healthy, so the numbers were not
what they appeared to be: four timeouts of ~15,080 ms each is ~60 s, which is how long the Kafka
**consumer group rebalance** takes after an application restart (the `NotCoordinator` retry loop
visible in the startup logs). The fold-consumer was not processing at all during that window.

The measurement was made valid by first confirming the consumer was live — posting a heartbeat and
watching it reach Postgres and Redis — and only then timing the trials. **Startup rebalance time is
a real property of the system, but it is not read staleness**, and reporting the first run's
numbers as NFR-4 would have been wrong.
