# Playhead

**A playback-state backend for OTT streaming, built to survive a tentpole event** — a premiere or
live match taking traffic from idle to peak in under a minute.

Every number below was measured on this machine, not estimated. Raw load-test output lives in
[docs/BENCHMARKS.md](docs/BENCHMARKS.md).

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

## Watching it happen

A Grafana dashboard, backed by Prometheus scraping Actuator, screenshotted mid-surge:

![Playhead under a live surge — request rate, read/write latency, cache hit rate, and Kafka
consumer lag, all climbing and recovering in real time](docs/images/grafana-surge.png)

Request rate ramps to **1.25K req/s** and back; p95 read/write latency rises under the load and
falls as it clears; cache hit rate settles near **40%**; Kafka consumer lag builds to ~4,000 and
drains once the surge passes. Run it yourself:

```
docker compose up -d
# generate load, e.g.:
k6 run load/mixed.js
# then open:
http://localhost:3000/d/playhead-surge   # Grafana — anonymous viewer access enabled
http://localhost:9090                    # Prometheus
```

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

## What it does

A player emits a heartbeat roughly every ten seconds while something is playing — *this profile is
at this position in this title*. At 100,000 concurrent streams that is ~10,000 writes/sec, all of
them updates to the same row per viewer.

```
WRITE   player --heartbeat/10s--> ingest-api --> Kafka --> fold-consumer --> Postgres + Redis
READ    resume            client --> read-api --> Caffeine --> Redis --> Postgres
        continue-watching client --> read-api --> Caffeine --> Postgres (covering index)
GUARD   token bucket + in-flight limit --> 429 + Retry-After
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

## What is built today

| Phase | | Status |
|---|---|---|
| 0 | Domain model, fold function, in-memory store, 3 unit tests | Complete |
| 1 | Spring Boot ingest API, validation, error handling, health | Complete |
| 2 | Load testing, priority-tiered admission control, **the surge A/B** | Complete |
| 3 | Kafka — producer, partitioning, consumer group, manual offset commit | Complete |
| 4 | PostgreSQL — partitioned events table, covering index, durable fold | Complete |
| 5 | Redis, read path, Caffeine cache, read benchmarks | Complete |
| 6 | Chaos drills — kill dependencies under load | Not started |

Detailed scope and exit criteria: [docs/ROADMAP.md](docs/ROADMAP.md).

---

## Running it

Requires Docker and JDK 25.

```bash
docker compose up -d          # Kafka (KRaft), PostgreSQL 16, Redis 7
./gradlew bootRun             # starts on :8080, Flyway applies the schema
```

Post a heartbeat:

```bash
curl -X POST http://localhost:8080/v1/playback/heartbeat \
  -H "Content-Type: application/json" \
  -d '{"profileId":"p-1","titleId":"t-1","deviceId":"dev-1",
       "positionSeconds":42,"durationSeconds":3600,
       "clientTimestamp":1,"sequence":1}'
# -> 202 Accepted
```

Confirm it reached Kafka:

```bash
docker exec playhead-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic heartbeats \
  --from-beginning --property print.key=true
```

Read it back:

```bash
curl -H "X-Profile-Id: p-1" http://localhost:8080/v1/playback/resume/t-1
# -> {"profileId":"p-1","titleId":"t-1","positionSeconds":42,...}

curl -H "X-Profile-Id: p-1" "http://localhost:8080/v1/playback/continue-watching?page=0&size=20"
# -> [ ... in-progress titles, newest first, anything past 95% excluded ]
```

Cache hit rates, live:

```bash
curl "http://localhost:8080/actuator/metrics/cache.gets?tag=cache:resume&tag=result:hit"
```

### Reproducing the benchmarks

```bash
PEAK=50000 k6 run load/premiere.js   # the surge A/B (write path)
PEAK=2000  k6 run load/reads.js      # the read path
```

Both ramp from 50 to `PEAK` requests/sec over 60 seconds, then hold. `premiere.js` reports the
split between served requests (`202`), deliberately shed requests (`429`), and genuine failures
separately — necessary, because a load generator counts a correct `429` as a failure by default.
`reads.js` tracks each endpoint's latency as its own metric, so a point lookup and an indexed list
are never blended into one misleading percentile.

---

## Design decisions worth knowing

Full log with rejected alternatives: [docs/DECISIONS.md](docs/DECISIONS.md).

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
- **Redis is best-effort on the read path** — a failed Redis call is logged and treated as a cache
  miss, so an outage degrades to Postgres rather than returning `500` (D-025).

---

## Known limitations, measured rather than glossed over

- Under the most extreme overload (50,000 req/s target), ~2.85% of requests are refused at the TCP
  connection layer — Tomcat's connector backlog filling before a request reaches the application's
  own shedding logic. App-level admission control cannot intercept a connection that never arrives.
  Left untuned deliberately; see D-016.
- A Kafka publish has no explicit timeout configured, so a broker outage causes a write request to
  hang for roughly 60 seconds before failing, rather than failing fast.
- Priority tiers are designed for three classes of traffic (playback write > resume read > browse).
  The read endpoints now exist but are **not yet wired into admission control**, so the shedding
  fairness claim in NFR-7 remains unverified.
- **With Redis down, a read costs ~112 ms** — the 50 ms command timeout plus connection-attempt
  overhead. It succeeds (falling back to Postgres) but exceeds the 50 ms p99 budget. A per-request
  timeout has no memory: every request pays the wait on a dependency already known to be gone.
  A circuit breaker is the fix, and this measurement is what justifies it (D-025).
- **The read benchmark does not find the read path's ceiling.** At 2,000 req/s the server used 25
  of 800 connections and one of ten in the database pool. The recorded figure is latency at a
  realistic read rate, not a saturation point.
- **Kafka consumer rebalance after a restart takes ~60 s**, during which the fold-consumer
  processes nothing. Writes are still accepted and buffered in Kafka, but reads serve stale state
  for that window. Found while measuring staleness, where it initially produced 15-second results
  that had nothing to do with read latency.

---

## Testing approach

**Three unit tests in the entire project**, all on the fold function — a pure function with no
framework and no mocks. Everything else is verified by running it under real load and recording
actual output.

This is a deliberate position. For a distributed system, a measured p99 under a real traffic curve
and a recorded failure drill demonstrate more than a mocked unit test does. See D-009.
