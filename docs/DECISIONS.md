# Decision log

Every non-obvious engineering choice, with the reasoning and what was rejected. Append-only: to
change a decision, add a new entry that supersedes the old one rather than editing history.

Do not re-open a decision marked `SETTLED` without a new entry explaining what changed.

---

## D-001 · The stack · `SETTLED`

**Java 25, Spring Boot 4, Apache Kafka, PostgreSQL 16, Redis 7, Docker.**

The JVM is where this problem's ecosystem lives: Kafka, Flink and Spark are all JVM-native, and
Kafka is the backbone of the write path. Java 25 specifically for virtual threads, which suit an
ingest workload of many concurrent, mostly-blocked requests.

*Rejected:* **Go** — genuinely well suited to this workload and lighter on memory, but no
first-class Kafka Streams / Flink story. **Node** — the single-threaded event loop makes the
high-throughput write path harder to reason about and defend.

---

## D-002 · `202 Accepted`, not `200 OK`, for heartbeat ingest · `SETTLED`

`200` means the work is done. `202` means responsibility is accepted but processing has not
happened yet. Once Kafka sits in front of storage that is literally true — the response is sent
before the heartbeat is folded into state. Writing the contract this way from the first commit
means player clients never have to change when the async path lands.

---

## D-003 · Heartbeats go to Kafka, never synchronously to PostgreSQL · `SETTLED`

100,000 concurrent streams ÷ one heartbeat per 10s ≈ **10,000 writes/sec**, sustained — and every
one is an update to the *same* row for that profile and title, so lock contention compounds the
write volume.

Meanwhile the data is cheap to lose: a viewer never notices losing the last ten seconds of
progress. Durable log, asynchronous fold, player-facing latency decoupled from storage.

---

## D-004 · Carry `deviceId` and `sequence` before anything reads them · `SETTLED`

Cross-device resume needs both. Adding fields to a contract that player clients already depend on
is expensive; carrying two unused fields costs nothing.

---

## D-005 · The fold compares sequence numbers, and is a pure function · `SETTLED`

A heartbeat is applied only if its `sequence` is strictly greater than the one that produced the
current state. Out-of-order and duplicate delivery are both normal over a network; blind overwrite
would let a late-arriving older heartbeat rewind a viewer's position.

`Fold` is a separate class rather than a method on `PlaybackState`: the operation needs both types
and belongs to neither, and keeping it separate means `PlaybackState` has exactly one reason to
change (its data shape) rather than two.

*Rejected:* hybrid logical clocks — correct, but the sequence comparison achieves the same
guarantee for this workload at a fraction of the complexity.

---

## D-006 · The fold never receives a `null` current state · `SETTLED`

The caller supplies a real starting `PlaybackState` (position 0, sequence 0) for a key with no
history, so `Fold` has exactly one job — compare two sequence numbers — rather than also branching
on absence.

*Rejected:* `Optional<PlaybackState>` — allocates on a path called on every heartbeat, and pushes
the same branch to the caller anyway.

---

## D-007 · Use Caffeine rather than hand-writing a cache · `SETTLED`

Caffeine implements W-TinyLFU, written by the authors of the paper. Reimplementing a well-solved
library problem adds risk and maintenance cost without adding capability.

The engineering effort this frees goes into the behaviour that actually distinguishes this system:
surge absorption, pre-scaling, and documented degradation under dependency failure.

The same reasoning applies to sketch data structures — Count-Min, HyperLogLog, Bloom filters — if
approximate analytics are ever added.

---

## D-008 · Kafka topic partitioned by `profileId` · `SETTLED`

Kafka guarantees ordering only *within* a partition. Keying by `profileId` keeps one profile's
heartbeats on one partition and therefore in order, which is what makes the fold's sequence
comparison meaningful. A random or round-robin key would scatter a single viewer's events across
partitions and destroy the ordering guarantee the correctness model depends on.

---

## D-009 · Testing is deliberately minimal · `SETTLED`

Unit tests cover the fold — a pure function, where a test is the cheapest and most direct proof of
correctness. Everything downstream (throughput, latency, cache behaviour, failure recovery) is
verified by running the system under real load and by killing dependencies while traffic flows.

For a distributed system, a measured p99 under a real load curve and a recorded failure drill say
more than a mocked unit test does.

*Rejected:* mocking frameworks, Testcontainers-based integration tests, property-based testing —
all reasonable in a larger codebase; here they would cost more than the confidence they add.

---

## D-010 · No time component in the roadmap · `SETTLED`

Phases, not weeks. Ordering is by dependency and by what cannot be blocked by infrastructure. A
calendar in a roadmap invites cutting corners to hit a date.

---

## D-011 · The differentiator is surge survival · `SETTLED`

An earlier revision of the spec centred on hand-built data structures. Superseded by D-007.

What distinguishes this system is behaviour under a **tentpole event** — a premiere or live match
taking traffic from idle to peak in under a minute:

- capacity pre-scaled from an event schedule, because reactive autoscaling cannot catch a
  60-second ramp (metric delay + scrape interval + stabilisation window + pod start time)
- priority-tiered admission control, shedding browse traffic before playback writes
- a measured before/after under the same load curve — the control run is what makes the result
  mean anything

*Supporting evidence:* WBD's engineering blog reports that for simultaneous linear + digital
premieres, "pre-scaling the platform was essential as autoscaling couldn't always keep up with the
acceleration in RPS."

---

## D-012 · Standard `src/main/java` / `src/test/java` layout · `SETTLED`

The conventional Gradle/Maven source layout. Files sat flat at the repository root during early
development, when everything ran through single-file source execution (`java Foo.java`) and no
build tool was involved. Moved once Gradle needed to locate sources and tests by convention.

---

## D-013 · Kubernetes is optional, not required · `SETTLED`

Pre-scaling is demonstrated with `docker compose up --scale` driven by a controller. The
engineering argument — reactive autoscaling cannot catch a fast ramp, so capacity is raised ahead
of a scheduled event — is identical whether the orchestrator is Compose or Kubernetes.

A k3s + HPA deployment is the production-shaped version and remains optional polish.

---

## D-014 · `ConcurrentHashMap.compute`, not `synchronized`, for `Store` · `SETTLED`

`Store.applyHeartbeat` is a read-modify-write on shared state (look up the current
`PlaybackState`, fold the heartbeat in, save it back) — unsafe under concurrent callers without
some form of mutual exclusion.

`synchronized` on the whole method was the obvious first fix, but it takes one lock for the
*entire map*: two threads updating completely unrelated `(profileId, titleId)` pairs would still
block each other, for no correctness benefit — unrelated profiles never actually conflict.

`ConcurrentHashMap` locks per-key internally. `compute(key, fn)` performs the same
read-modify-write as one atomic step, but scoped to that key's bucket only — different profiles
proceed fully in parallel.

Chosen because phase 2 measures throughput under a surge across thousands of *different*
profiles at once (DIFF-1) — a single coarse lock would visibly cap that number in the project's
own load test.

*Rejected:* `synchronized` — correct, but serialises all profiles through one lock regardless of
whether they conflict.

---

## D-015 · k6 `ramping-arrival-rate`, not `ramping-vus`, for the premiere curve · `SETTLED`

`ramping-vus` controls **concurrency** (how many virtual users are alive) and lets req/s fall out
of that as a side effect — 1 VU ≈ 1 open connection. For a fast, near-instant write endpoint this
misrepresents the workload: modelling "5,000 concurrent viewers" as 5,000 permanently-open,
mostly-idle connections wildly overstates the client resources needed, and on Windows it is the
client that runs out of ephemeral ports first (confirmed: `ramping-vus` at 20,000 VUs crashed k6
itself with a socket-exhaustion panic, before the app was meaningfully loaded at all).

`ramping-arrival-rate` targets **requests/sec directly** and reuses a small, capped pool of
connections (`preAllocatedVUs`/`maxVUs`) across iterations instead of one connection per simulated
user. Confirmed server-bound, not client-bound, by raising `maxVUs` 1,000 → 3,000 → 6,000 at the
same target rate: k6 never used more than ~1,450 of them — throughput was capped by the server's
own processing time, not by available client connections.

*Rejected:* `ramping-vus` — correct tool for modelling genuinely long-lived, mostly-idle
connections (e.g. websockets), wrong tool for measuring a single fast HTTP endpoint's saturation
point.

---

## D-016 · App-level shedding only; Tomcat connector limits left untuned · `SETTLED`

Run B exposed a real gap: under the most extreme overload (50,000 req/s target), roughly 2.85%
of requests were refused at the TCP layer (`connection actively refused`) — Tomcat's connector
backlog filling before the request ever reaches the token bucket / semaphore. App-level shedding
cannot intercept a connection that never reaches the app.

**Decision: document this as a known limitation, do not tune Tomcat's connector settings
(`accept-count`, `max-connections`) to close it.** The mechanism being demonstrated (DIFF-1) is
application-level admission control — priority tiers, rate limiting, backlog shedding. Connector
tuning is a real, separate lever, but chasing it goes past what this phase needs to prove and adds
scope with limited return, given the project's fixed time budget (S-012).

*Rejected:* tuning `server.tomcat.accept-count`/`max-connections` to eliminate the connection
refusals — would likely narrow the gap, but the headline result (admitted requests stay fast and
bounded — p99 threshold passes at both 20k and 50k targets) does not depend on it.

---

## D-017 · `JacksonJsonSerializer` for Kafka message values, not the legacy `JsonSerializer` · `SETTLED`

Spring Boot 4.1.1 ships **Jackson 3** (new `tools.jackson.*` packages/coordinates, a genuine
recent ecosystem migration) as its default JSON library. `spring-kafka`'s legacy `JsonSerializer`
is built against Jackson 2's classes (`com.fasterxml.jackson.databind.JavaType`) — the two don't
coexist, confirmed by a real `NoClassDefFoundError` at publish time.

**First fix tried (and reverted): manual JSON-string serialization.** Serialize `Heartbeat` to a
string by hand (via the autoconfigured Jackson 3 `ObjectMapper`) and publish with plain
`StringSerializer`, sidestepping the clash entirely. This *worked* and was verified end-to-end —
but it was a workaround chosen without first checking for a proper fix, built and only labelled
as a workaround after the fact. Corrected per his direct feedback (2026-09-03,
`WORKING_AGREEMENT.md`): workarounds get flagged and discussed *before* being built, not after.

**Actual fix, on checking:** `spring-kafka-4.1.1.jar` ships **both** a legacy `JsonSerializer`
(Jackson 2) and a Jackson-3-native `JacksonJsonSerializer`, side by side — confirmed by listing
the jar's contents. Using `JacksonJsonSerializer` as the producer's `value-serializer` is the
real, convention-matching fix: `IngestController` sends the `Heartbeat` object directly again, no
manual `ObjectMapper` call, no `StringSerializer`.

*Rejected:* the manual JSON-string route (works, but skips a genuine one-step serializer that
exists for exactly this case) and adding classic Jackson 2 (`com.fasterxml.jackson.core`)
alongside Jackson 3 (would resolve `JsonSerializer` too, but leaves two Jackson major versions on
the classpath indefinitely, for no benefit over `JacksonJsonSerializer`).

**Related environment finding, not itself a decision but worth recording:** Spring Boot 4.1.1 also
moved Kafka autoconfiguration out of the core `spring-boot-autoconfigure` module into a new,
separate `spring-boot-starter-kafka` artifact — `spring-kafka` alone is not enough to get an
autoconfigured `KafkaTemplate` bean on this version. Every Boot 3.x-era tutorial online misses this
(`WORKING_AGREEMENT.md` already flags Boot 3.x examples as unreliable for this project generally).

---

## D-018 · `auto-offset-reset: earliest` for `fold-consumer` · `SETTLED`

Found running the Replay gate test: Kafka's client default for a consumer group with no committed
offset is `latest` — skip straight to the newest message, ignore everything before. For a system
built around replaying history (FR-10) and surviving crashes, that default is wrong: a fresh or
reset consumer group would silently skip all existing data instead of processing it.

Set `spring.kafka.consumer.auto-offset-reset: earliest`. Verified for real: reset the consumer
group's offset to 0, restarted, watched all 4 backlog messages for one profile replay in order
and rebuild the exact final state.
