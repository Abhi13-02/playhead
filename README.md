# Playhead

**A playback-state backend for OTT streaming, built to survive a tentpole event** — a premiere or
live match taking traffic from idle to peak in under a minute.

Every number below was measured on this machine, not estimated. Raw load-test output lives in
[docs/BENCHMARKS.md](docs/BENCHMARKS.md); real failures and their fixes in
[docs/ENGINEERING_LOG.md](docs/ENGINEERING_LOG.md).

---

## The headline result

The same 60-second traffic ramp, run twice against the same code — once with the admission-control
layer disabled, once with it on.

| Target load | | Unprotected | Protected |
|---|---|---|---|
| **20,000 req/s** | admitted-request p99 | ~135 ms | **42 ms** |
| **50,000 req/s** | admitted-request p99 | *(system collapsing — only max was meaningful)* | **61 ms** |
| **50,000 req/s** | worst-case latency | **3.91 s** | 1.88 s |
| **50,000 req/s** | `p99 < 500ms` threshold | **failed** | **passed** |

Unprotected, the write path holds cleanly to roughly **10,000–13,000 req/s**, then latency climbs
from single-digit milliseconds into whole seconds as requests queue inside the application.

With the guard layer on, excess load is rejected early and cheaply (`429` + `Retry-After`) instead
of being accepted and left to rot in a queue. The trade is deliberate: **fewer requests served, but
a hard ceiling on how slow a served request can get.**

---

## What it does

A player emits a heartbeat roughly every ten seconds while something is playing — *this profile is
at this position in this title*. At 100,000 concurrent streams that is ~10,000 writes/sec, all of
them updates to the same row per viewer.

```
GUARD   nginx limit_req (fleet ceiling, per tier)  -->  429 + Retry-After
                 |                                        ^
                 v                                        |
        per-container token bucket + in-flight semaphore --+

WRITE   player --heartbeat/10s--> ingest-api --> Kafka --> fold-consumer --> Postgres + Redis
READ    resume            client --> read-api --> Caffeine --> Redis --> Postgres
        continue-watching client --> read-api --> Caffeine --> Postgres (covering index)
        popular-now       client --> read-api --> local snapshot --> shared Redis --> ZUNION
SCALE   event schedule --> scaling-controller --> replicas raised before the event starts
```

The write path never waits on durable storage. `ingest-api` publishes to Kafka and returns `202
Accepted`; a separate consumer folds heartbeats into state at its own pace. Losing a heartbeat is
cheap — another one arrives in ten seconds — which is what makes the asynchronous design safe.

### The fold, and why it is replay-safe

Every heartbeat carries a per-device `sequence`. The fold applies a heartbeat only if its sequence
is newer than the state's, and ignores it otherwise. That single rule buys three things at once:

- **Anti-rewind** — a late-arriving old heartbeat can never move a viewer's position backwards.
- **Idempotency** — applying the same heartbeat twice changes nothing, so at-least-once delivery
  from Kafka is safe without deduplication.
- **Replay** — consuming the topic from offset 0 into an empty store reproduces identical state.

All three are verified, not asserted. Replay was tested by resetting the consumer group to offset
0, restarting with an empty store, and confirming the rebuilt state matched exactly.

---

## Where a limit lives

The guard layer is split across two tiers on one rule: **a limit belongs at the layer that owns the
resource it protects.**

| Limit | Where | Protects |
|---|---|---|
| `limit_req` per tier | nginx | total ingress and shared downstream — the **system** ceiling |
| Token bucket per tier | each container | that **container's** own capped CPU |
| In-flight semaphore | each container | that **JVM's** threads and heap while waiting on Kafka |

This matters because per-instance state does not survive replication. A limit sized against the
whole system but stored inside each replica gets multiplied by the replica count — scaling out
silently switches the protection off. nginx is the only component of which exactly one exists, so a
ceiling placed there cannot multiply, with no shared counter, no coordination and no extra network
hop on the request path.

Verified at both replica counts, browse tier, 1,500 req/s offered against a 500 req/s ceiling:

| | 1 replica | 3 replicas |
|---|---|---|
| admitted through nginx | 532 req/s | **519 req/s** |
| served `200` | 90 req/s | 271 req/s |

**The ceiling holds as replicas are added; served capacity scales with them.** That is the correct
split — per-container limits *should* sum as containers are added, only the system ceiling must not.
Cross-checked against nginx's own error log, not just client status codes.

Shedding at the edge is also far cheaper than shedding inside the application, because a request
rejected at nginx never occupies a Tomcat thread, a connection or a JVM:

| shed at | avg | p95 |
|---|---|---|
| application | 8.6 ms | 48.58 ms |
| nginx | **0.9 ms** | **1.6 ms** |

Full reasoning and the rejected alternatives — a shared Redis counter, and replica-count discovery —
are in [D-035](docs/DECISIONS.md).

---

## Pre-scaling, because autoscaling is too slow

A reactive autoscaler pays four delays in series: the metric lags reality, the scrape samples it
periodically, a stabilisation window confirms the spike is real, and only then does a replica boot.
Against a ramp that peaks in 60 seconds, that chain *is* the event.

The scaling controller reads a schedule instead of a metric, so it skips the first three delays
entirely and pays only the boot:

```
lead = poll interval 10 s + measured container start 10 s + safety margin 10 s = 30 s
```

Container start is measured, not guessed — 9,377 / 8,982 / 8,736 ms across three trials, rounded up
from the worst.

**Result, unattended:** an event at `09:57:48` expecting 21,000 rps scaled 1 → 3 replicas, **healthy
12 seconds before the event began**, and returned to baseline afterwards. Capacity bought early is
capacity paid for while idle, so that is counted too: **41.7 instance-seconds** before the event
started.

**Stated honestly:** on this hardware, tripling replicas did *not* triple throughput — three JVMs
plus nginx, k6 and three datastores share one laptop's cores. What phase 7 proves is the pre-scaling
mechanism itself: schedule-driven, correctly timed, unattended, with its cost counted. A throughput
gain is not something one machine can demonstrate.

---

## Failure drills

Dependencies killed while traffic flowed. Every claim below came from a drill, not a design
document. Full transcripts in [docs/ENGINEERING_LOG.md](docs/ENGINEERING_LOG.md).

**Redis killed under read load.** A 50 ms command timeout bounds *one* call but does nothing about
thousands of doomed calls per second at a dependency already known to be gone — the failing work
buried Lettuce's event loop and latency reached whole seconds. A circuit breaker fixed it:

| `resume` latency, Redis down | timeout only | **+ circuit breaker** |
|---|---|---|
| p50 | 12,881 ms | **1.57 ms** |
| p99 | 15,814 ms | **72 ms** |
| `RedisCommandTimeoutException` | 30,336 | **71** |

**Postgres killed under write load.** Writes kept returning `202` (0.00% failed) and buffered in
Kafka; the consumer drained the backlog on recovery with no loss and no double-application — the
fold's sequence check makes reprocessing a no-op.

**Consumer `kill -9`'d mid-backlog.** Produced **288 duplicate rows in the event log out of ~65,000
messages (0.4%)** — genuine post-crash redelivery. The event log is at-least-once by design; the
`playback_state` upsert is what makes duplicates harmless.

Four unplanned findings came out of these drills and are written up rather than quietly fixed,
including a Redis outage dragging whole-app health to `DOWN`, and a backlog drain capped at
~200 msg/s.

---

## The read path, measured

122,207 requests against the same curve shape, ramping to 2,000 req/s. **0.00% failed.**

| Endpoint | median | p95 | **p99** |
|---|---|---|---|
| `GET /resume/{titleId}` | 1.57 ms | 3.69 ms | **6.57 ms** |
| `GET /continue-watching` | 0.54 ms | 3.12 ms | — |

Target was p99 < 50 ms. Met with roughly 7× headroom, using 25 of 800 available load-generator
connections and **one** of ten database connections — the read path was never the constraint.

**Write-to-read visibility: ≤ 138 ms** end to end (HTTP → Kafka → fold → Postgres + Redis →
readable), against a 2-second budget.

### The cache result worth reading

Hit rates come from `/actuator/metrics/cache.gets` at runtime, not from a spreadsheet.

| Local cache | Hit rate | Distinct keys |
|---|---|---|
| `resume` | **8.3%** | 25,000 |
| `continue-watching` | **64.3%** | 500 |

**A 7.7× difference between two endpoints in the same service, caused entirely by key
cardinality.** `resume` is keyed `(profileId, titleId)` and each viewer reads their own resume
point roughly once per session — a premiere produces a million *different* keys, not one hot key.
`continue-watching` is keyed on `profileId` alone and is re-fetched on every app open.

The honest conclusion: **the local cache barely earns its place in front of `resume`.** What
delivers that endpoint's 6.57 ms p99 is Redis, not Caffeine. This is recorded rather than
smoothed over, because "we added a cache and it got faster" is the claim that usually goes
unchecked.

---

## Driving it live

A demo control panel ([`demo/`](demo/)) drives load and breaks dependencies while the graphs
respond, so the behaviour above can be reproduced by moving a slider rather than by reading a
benchmark table:

![Demo control panel — traffic sliders per priority tier, kill/restart buttons for Redis,
PostgreSQL and Kafka, live per-second counters, and the Grafana dashboard responding alongside
them](docs/images/demo-control-panel.png)

```bash
docker compose up -d     # service + Prometheus + Grafana
node demo/server.js      # then open http://localhost:4000
```

Three things it makes visible in about thirty seconds each: **priority shedding** (push the surge
preset — the `429`s land on reads, not on playback writes), **degraded reads** (kill Redis — traffic
keeps succeeding via the Postgres fallback), and **buffered writes** (kill Postgres — writes still
`202`, consumer lag climbs, then drains on recovery).

Measured across a Redis kill and restart driven from the panel: **1,620 requests/sec sustained,
0 failed** — reads fell back to PostgreSQL and the degradation showed up as a latency step, not an
outage.

It is deliberately separate from the service: nothing in `src/` knows it exists, and deleting
`demo/` changes nothing about the system. See [demo/README.md](demo/README.md).

## Watching it happen

A Grafana dashboard, backed by Prometheus scraping Actuator, screenshotted mid-surge:

![Playhead under a live surge — request rate, read/write latency, cache hit rate, and Kafka
consumer lag, all climbing and recovering in real time](docs/images/grafana-surge.png)

Request rate ramps to **1.25K req/s** and back; p95 read/write latency rises under the load and
falls as it clears; cache hit rate settles near **40%**; Kafka consumer lag builds to ~4,000 and
drains once the surge passes.

---

## Running it

Requires Docker and JDK 25.

```bash
./gradlew bootJar             # build the jar (the image packages it — see Dockerfile)
docker compose up -d          # app + nginx + Kafka (KRaft) + PostgreSQL 16 + Redis 7 + monitoring
```

The service is behind nginx on **:8080**, Grafana on **:3000**, Prometheus on **:9090**. Flyway
applies the schema on first start.

**The production shape** adds per-container CPU/memory caps and correspondingly resized per-tier
rates:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

The base file is left uncapped deliberately, because that is the configuration every number in
`BENCHMARKS.md` was measured on. The overlay carries **no published figures**: measuring a capped,
multi-replica deployment honestly requires the load generator on separate hardware from the service,
which this single machine cannot provide (D-033, D-035).

To run the app on the host instead of in a container — useful when iterating on code:

```bash
docker compose up -d kafka postgres redis
./gradlew bootRun             # binds :8080 directly, no nginx
```

### The API

```bash
# write
curl -X POST http://localhost:8080/v1/playback/heartbeat \
  -H "Content-Type: application/json" \
  -d '{"profileId":"p-1","titleId":"t-1","deviceId":"dev-1",
       "positionSeconds":42,"durationSeconds":3600,
       "clientTimestamp":1,"sequence":1}'
# -> 202 Accepted

# read: where was this viewer in this title
curl -H "X-Profile-Id: p-1" http://localhost:8080/v1/playback/resume/t-1
# -> {"profileId":"p-1","titleId":"t-1","positionSeconds":42,...}

# read: this viewer's in-progress titles, newest first, anything past 95% excluded
curl -H "X-Profile-Id: p-1" "http://localhost:8080/v1/playback/continue-watching?page=0&size=20"

# read: the 20 most-watched titles over the last 15 minutes
curl http://localhost:8080/v1/browse/popular-now
# -> ["got","euphoria","succession", ...]

# schedule an event the scaling controller will pre-scale for
curl -X POST http://localhost:8080/v1/events \
  -H "Content-Type: application/json" \
  -d '{"name":"Premiere","startsAt":"2026-01-01T20:00:00Z","expectedPeakRps":21000}'
```

`popular-now` counts heartbeats per title into one Redis sorted set per minute and unions the last
15 to form a sliding window. It is a **browse-tier demo endpoint, not analytics**: the count is
heartbeats rather than distinct viewers (every title is off by the same factor, so the *ranking*
holds), and nothing is persisted — the list rebuilds itself from live traffic within 15 minutes of
any outage.

### Reproducing the benchmarks

```bash
PEAK=50000 k6 run load/premiere.js          # the surge A/B (write path)
PEAK=2000  k6 run load/reads.js             # the read path
k6 run load/mixed.js                        # all three tiers at once
k6 run load/nginx_limit_check.js            # fleet-wide ceiling, shed attributed per layer
```

`premiere.js` reports served (`202`), deliberately shed (`429`) and genuine failures separately —
necessary, because a load generator counts a correct `429` as a failure by default. `reads.js`
tracks each endpoint as its own metric, so a point lookup and an indexed list are never blended into
one misleading percentile.

---

## What is built

| Phase | | Status |
|---|---|---|
| 0 | Domain model, fold function, in-memory store, 3 unit tests | Complete |
| 1 | Spring Boot ingest API, validation, error handling, health | Complete |
| 2 | Load testing, priority-tiered admission control, **the surge A/B** | Complete |
| 3 | Kafka — producer, partitioning, consumer group, manual offset commit | Complete |
| 4 | PostgreSQL — partitioned events table, covering index, durable fold | Complete |
| 5 | Redis, read path, Caffeine cache, read benchmarks | Complete |
| 6 | Chaos drills — dependencies killed under live load | Complete |
| 7 | Containerisation, nginx, schedule-driven pre-scaling | Complete |
| 8 | Prometheus + Grafana dashboard, resource envelope, docs | Complete |

Detailed scope and exit criteria: [docs/ROADMAP.md](docs/ROADMAP.md). One optional phase-8 item —
k3s manifests with a Horizontal Pod Autoscaler — was deliberately skipped: the pre-scaling argument
is identical whether the orchestrator is Compose or Kubernetes (D-013).

The whole system runs inside 12 GB with a declared memory limit per service, measured with
`docker stats` under load rather than assumed.

---

## Design decisions worth knowing

Full log with rejected alternatives: [docs/DECISIONS.md](docs/DECISIONS.md).

- **A limit belongs at the layer that owns the resource it protects** — the system ceiling at nginx,
  per-container limits in the container, per-JVM limits in the JVM. Rejected a shared Redis counter
  (a network hop on every request, and it must fail either open or closed — both worse than the
  problem) and replica-count discovery (machinery to keep a global limit correct while leaving it in
  the wrong place) (D-035).
- **`202`, not `200`** — the response is sent before the heartbeat is durably folded. `200` would
  overclaim.
- **Kafka before storage** — 10,000 writes/sec of contended single-row updates, for data that is
  cheap to lose. A durable log decouples player-facing latency from storage.
- **Partition key is `profileId`** — one profile's heartbeats always land in one partition, so one
  consumer processes them in order. The same guarantee `ConcurrentHashMap.compute` gives in memory,
  solved architecturally instead of with a lock.
- **Manual offset commit** — the offset advances only after a heartbeat is folded. A crash between
  the two causes redelivery, which the fold's sequence check makes harmless.
- **Caffeine, not a hand-written cache** — it *is* W-TinyLFU, implemented by the people who
  published the paper.
- **The fold runs inside the SQL upsert**, not as read-modify-write in Java. `INSERT ... ON
  CONFLICT ... WHERE excluded.sequence > playback_state.sequence` is one atomic statement, so
  Postgres row-locks the key and concurrent consumers cannot clobber each other — no application
  locking (D-019).
- **`continue-watching` is served from Postgres, not Redis** — it is a sorted, filtered, paginated
  list, which a covering index already answers in ~0.09 ms. A Redis copy would need invalidating on
  every heartbeat, for latency the index does not cost (D-023).
- **"Completed" is computed, not stored** — a title past 95% is excluded by the query's `WHERE`
  clause. No `completed` column to keep in sync, and `resume` still answers for a finished title.
- **Redis is best-effort everywhere** — a failed call is logged and treated as a miss, so an outage
  degrades to Postgres rather than returning `500`, and Redis is deliberately excluded from the
  health indicator so a cache outage cannot fail a readiness probe (D-025, D-027, D-028).

---

## Known limitations, measured rather than glossed over

- **The phase-2 gate was not fully met, and is recorded unticked.** Under a mixed surge, playback
  writes were never shed by admission control — `admission.shed{tier=PLAYBACK_WRITE}` was **0 in
  every run**, so the priority mechanism did its job — but end-to-end write success peaked at
  **89.32%**, not the >99% the gate asked for. Four runs isolated the cause as an unrelated in-flight
  limiter and then a single-machine CPU ceiling; enlarging the limiter further measured *worse*
  (87.20%). The gate is left open with the real number rather than reworded to fit (D-030).
- Under the most extreme overload (50,000 req/s target), ~2.85% of requests are refused at the TCP
  connection layer — Tomcat's connector backlog filling before a request reaches the application's
  own shedding logic. Left untuned deliberately; see D-016.
- A Kafka publish has no explicit timeout configured, so a broker outage causes a write request to
  hang for roughly 60 seconds before failing, rather than failing fast.
- **The read benchmark does not find the read path's ceiling.** At 2,000 req/s the server used 25
  of 800 connections and one of ten in the database pool. The recorded figure is latency at a
  realistic read rate, not a saturation point.
- **Kafka consumer rebalance after a restart takes ~60 s**, during which the fold-consumer
  processes nothing. Writes are still accepted and buffered in Kafka, but reads serve stale state
  for that window.
- **Backlog drain is capped at ~200 msg/s**, so recovery from a long outage is slower than the
  ingest rate that caused it.
- **The capped production overlay has no measured numbers.** Its per-tier rates are the measured
  defaults scaled by CPU ratio — an estimate that assumes throughput is CPU-bound and scales
  linearly. Producing honest figures needs the load generator on separate hardware from the service.

---

## Testing approach

**Three unit tests in the entire project**, all on the fold function — a pure function with no
framework and no mocks. Everything else is verified by running it under real load and recording
actual output.

This is a deliberate position. For a distributed system, a measured p99 under a real traffic curve
and a recorded failure drill demonstrate more than a mocked unit test does. See D-009.
