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

## What it does

A player emits a heartbeat roughly every ten seconds while something is playing — *this profile is
at this position in this title*. At 100,000 concurrent streams that is ~10,000 writes/sec, all of
them updates to the same row per viewer.

```
WRITE   player --heartbeat/10s--> ingest-api --> Kafka --> fold-consumer --> state
READ    client --> read-api --> cache --> Redis --> Postgres          (phase 5)
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
| 4 | PostgreSQL — schema, indexes, durable fold | Not started |
| 5 | Redis, read path, Caffeine cache | Not started |
| 6 | Chaos drills — kill dependencies under load | Not started |

Detailed scope and exit criteria: [docs/ROADMAP.md](docs/ROADMAP.md).

---

## Running it

Requires Docker and JDK 25.

```bash
docker compose up -d          # Kafka (KRaft mode, no Zookeeper)
./gradlew bootRun             # starts on :8080
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

### Reproducing the surge result

```bash
PEAK=50000 k6 run load/premiere.js
```

The script ramps from 50 to `PEAK` requests/sec over 60 seconds, then holds. It reports the split
between served requests (`202`), deliberately shed requests (`429`), and genuine failures
separately — necessary, because a load generator counts a correct `429` as a failure by default.

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
- **Caffeine, not a hand-written cache** (phase 5) — it *is* W-TinyLFU, implemented by the people
  who published the paper.

---

## Known limitations, measured rather than glossed over

- Under the most extreme overload (50,000 req/s target), ~2.85% of requests are refused at the TCP
  connection layer — Tomcat's connector backlog filling before a request reaches the application's
  own shedding logic. App-level admission control cannot intercept a connection that never arrives.
  Left untuned deliberately; see D-016.
- A Kafka publish has no explicit timeout configured, so a broker outage causes a write request to
  hang for roughly 60 seconds before failing, rather than failing fast.
- Priority tiers are designed for three classes of traffic (playback write > resume read > browse),
  but only the write tier exists until phase 5 builds the read path.

---

## Testing approach

**Three unit tests in the entire project**, all on the fold function — a pure function with no
framework and no mocks. Everything else is verified by running it under real load and recording
actual output.

This is a deliberate position. For a distributed system, a measured p99 under a real traffic curve
and a recorded failure drill demonstrate more than a mocked unit test does. See D-009.
