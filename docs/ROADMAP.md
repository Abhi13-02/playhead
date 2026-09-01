# Playhead — Roadmap

Construction order. [SPEC.md](SPEC.md) says what to build; this says in what order and how to know
a phase is finished.

**No time component.** Ordering follows three rules:

1. **Every phase ends with something demonstrable.** No capability is deferred to the end; each
   phase leaves the system in a state that can be run and shown.
2. **One technology per phase**, introduced only when the build genuinely needs it.
3. **Cut anything expensive that is not strictly necessary.** Crisp beats complete.

---

## The gate rule

Every phase ends with a **Gate**: checkable facts. Work does not begin on phase N+1 until every
gate item for phase N is true.

A gate is never "the code exists" — it is "the behaviour is proven". Building something whose
behaviour has not been demonstrated does not close a phase.

## Testing policy — deliberately minimal

**Three unit tests in the entire project**, all in phase 0, all on the fold function — a pure
function with no framework, no mocks, no fixtures. `assertEquals` only.

Everything else is verified by running it under load and recording real output. This is a
**position, not a gap**:

> *"The fold is a pure function, so it has unit tests. Everything else I verified under real load
> with k6 and by killing dependencies while traffic was flowing — for a distributed system that
> tells you more than a mock does."*

## What is demonstrable at each phase

| After phase | What can be run and shown |
|---|---|
| 0 | The domain model and fold logic, with tests |
| 1 | A live API accepting heartbeats |
| **2** | **The headline result: the same load curve, unprotected vs protected** |
| 3 | Durable ingest; survives a broker restart |
| 4 | Real schema, indexes, query plans |
| **5** | **The complete system end to end, with measured read latency** |
| **6** | **Redis killed under load; the system stays up** |
| 7 | Pre-scaling from a schedule, unattended |
| 8 | Grafana dashboards under surge |

---

## Phase 0 — Core Java and the domain model

**Technology from zero: Java.** No Spring, no Docker, no network. Single files run with
`java Foo.java` until JUnit forces a build tool.

- [x] A single runnable `.java` file
- [x] `Heartbeat` — the event, as a record
- [x] `PlaybackState` — the folded current state
- [x] `fold(state, heartbeat) -> state`, including the **anti-rewind rule**: a heartbeat with a
      lower `sequence` than the one already applied is ignored. Five lines, no clock algorithm.
- [x] An in-memory `Map` store
- [x] Gradle wrapper (introduced when JUnit is needed)
- [x] **3 tests on the fold** — applies a heartbeat; ignores a stale one; applying twice changes
      nothing
- [x] `.gitignore`, first commit

**Gate**
- [x] `./gradlew build` passes
- [x] All three fold tests green, including the one that distinguishes the sequence check from a
      blind overwrite

## Phase 1 — Spring Boot and the ingest API · *FR-1, FR-2*

**Technology from zero: Spring Boot**, kept deliberately thin.

- [ ] `ingest-api` module
- [ ] `POST /v1/playback/heartbeat` -> `202`
- [ ] Validation; `400` naming every failed field
- [ ] `@ControllerAdvice` error handling
- [ ] Actuator health

**Gate**
- [ ] `curl` shows 202 valid / 400 with field names invalid
- [ ] Startup logs show the embedded server bound to a port with no manually-written server code

## Phase 2 — Load, saturation, and the surge result · *FR-9, NFR-5, NFR-7, D-1, D-3* · **HEADLINE**

**Technology from zero: JVM concurrency and load testing.** The differentiator lands here, before
any infrastructure exists. Everything after this makes the same result bigger.

- [ ] Make the store thread-safe — demonstrate the race first, then fix it
- [ ] Virtual threads for request handling
- [ ] k6 with a premiere curve: 0 -> saturation in 60 s
- [ ] **Run A** — no protection. Record where it breaks
- [ ] Priority tiers (playback write > resume read > browse), token bucket, queue-depth shedding,
      `429` + `Retry-After`
- [ ] **Run B** — same curve, protection on
- [ ] `BENCHMARKS.md` created with the A/B table

**Gate**
- [ ] Both runs recorded with real k6 output; the delta is real and explainable
- [ ] Under overload, playback writes succeed > 99% while browse absorbs the shedding
- [ ] Shed responses carry `429` + `Retry-After`; rejection reason is visible in metrics
- [ ] The surge result is reproducible on demand.

## Phase 3 — Kafka · *FR-1, NFR-2, NFR-8*

**Technology from zero: Kafka.** First container.

- [ ] `docker-compose.yml` with Kafka (KRaft)
- [ ] `ingest-api` publishes instead of storing in memory
- [ ] Topic design: partitions, key = `profileId`
- [ ] `fold-consumer` module
- [ ] Manual offset commit

**Gate**
- [ ] A heartbeat posted before a broker restart is still processed after it
- [ ] One profile's heartbeats are provably confined to a single partition

## Phase 4 — PostgreSQL · *NFR-8*

**Technology from zero: Postgres and real SQL.**

- [ ] Postgres in Compose
- [ ] Events table (partitioned) and current-state table
- [ ] Covering index for continue-watching
- [ ] `fold-consumer` writes durably
- [ ] Connection pool sized deliberately
- [ ] `EXPLAIN ANALYZE` output saved

**Gate**
- [ ] The continue-watching query uses an index scan, proven by a saved plan
- [ ] Data survives `docker compose down && up`
- [ ] `EXPLAIN ANALYZE` output for the continue-watching query is committed

## Phase 5 — Redis, cache, and the read path · *FR-3, FR-4, FR-5, NFR-3, NFR-4* · **STOP LINE**

**Technology from zero: Redis and caching strategy.**

- [ ] Redis in Compose; fold writes hot state
- [ ] `read-api` module
- [ ] `GET /v1/playback/resume/{titleId}`, `GET /v1/playback/continue-watching`
- [ ] Completion threshold removes a title
- [ ] **Caffeine** cache, cache-aside, hit-rate metric
- [ ] k6 read profile; p50/p95/p99 recorded

**Gate**
- [ ] Read p99 recorded; staleness measured
- [ ] Cache hit rate exposed as a runtime metric, not only in a benchmark
- [ ] `README.md` opens with numbers
- [ ] **The system is complete end to end and measured.**

## Phase 6 — Chaos drills · *NFR-9, D-4*

**Cheap, high impact.** No new technology — `docker kill` and a notepad.

- [ ] Drill 1 — kill Redis under load; reads fall back to Postgres
- [ ] Drill 2 — kill Postgres; writes still accepted, buffered in Kafka
- [ ] Drill 3 — stall the consumer; lag recovers, no duplicate application
- [ ] Circuit breakers and timeouts where the drills show they are needed
- [ ] `ENGINEERING_LOG.md` — each drill with **real** output

**Gate**
- [ ] Three drills run, real terminal output pasted, error rates recorded truthfully
- [ ] At least one genuine unplanned failure written up
- [ ] Degraded-mode behaviour is documented from real drills, not asserted.

## Phase 7 — Pre-scaling from a schedule · *FR-7, FR-8, NFR-6, D-2*

**No Kubernetes.** `docker compose up --scale` driven by a controller gives the same engineering
story far more cheaply. The k8s version is phase 8, and optional.

- [ ] `event-schedule` service — register an upcoming tentpole
- [ ] `scaling-controller` — reads the schedule, scales instances up ahead of the event, back down
      after, unattended
- [ ] Pre-scale lead time derived from **measured** container start time
- [ ] Re-run the phase-2 A/B with pre-scaling in the mix

**Gate**
- [ ] A scheduled event raises capacity **before** its start time, with no human action
- [ ] The pre-scale lead time is derived from **measured** container start time, and the
      arithmetic (metric delay + scrape interval + stabilisation window + start time) is recorded
- [ ] The cost is stated: idle instance-minutes spent buying the headroom

## Phase 8 — Polish · *NFR-11*

Everything here is optional. Do it if there is time; skip it without guilt.

- [ ] Prometheus + Grafana, one dashboard: latency percentiles, errors, consumer lag, instance
      count, cache hit rate
- [ ] Dashboard screenshot under surge, in the README
- [ ] Final README: numbers first, then the demo script from SPEC §10
- [ ] *Optional:* k3s manifests + HPA, as the production-shaped version of phase 7

**Gate**
- [ ] Someone who has never seen the repo can run it from the README alone

---

## Explicitly cut

Recorded so nobody re-adds them. See [DECISIONS.md](DECISIONS.md) D-012.

| Cut | Why |
|---|---|
| Hand-built W-TinyLFU, Count-Min, HyperLogLog, Bloom filter | Well-solved library problems; reimplementing adds risk without capability. Use Caffeine (DECISIONS.md D-007). |
| Trending / top-K / unique-viewer analytics lane | A whole third subsystem for one capability; out of scope (SPEC §3). |
| Hybrid logical clocks | The anti-rewind *behaviour* is kept as a 5-line `sequence` rule. The clock algorithm is not worth the concept cost. |
| Property-based testing, Testcontainers, mocks | Testing is minimal by policy — 3 unit tests total. |
| OpenTelemetry distributed tracing | Prometheus + Grafana is enough to show the result. |
| `ARCHITECTURE.md` as a separate document | Folds into the README. |
| Kubernetes as a required phase | Compose scaling tells the same story; k8s is optional polish in phase 8. |
