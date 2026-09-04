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

---

## D-019 · The anti-rewind fold runs in the SQL upsert, not read-modify-write in Java · `SETTLED`

When `fold-consumer` starts writing folded state to the `playback_state` table, the anti-rewind
rule ("a heartbeat is applied only if its `sequence` is strictly newer") needs a home. Two options:

- **Fold in Java:** read the current row, call `Fold.fold(current, heartbeat)`, write the result
  back. One definition of the rule, already unit-tested — but a read-modify-write across the
  network, and racy: during a Kafka consumer-group rebalance the same profile's partition can
  briefly be processed by two consumers, and two read-modify-writes can clobber each other. Same
  TOCTOU problem as D-014, moved from a `HashMap` to a table.
- **Fold in SQL:** `INSERT ... ON CONFLICT (profile_id, title_id) DO UPDATE SET ... WHERE
  excluded.sequence > playback_state.sequence`. One atomic statement, one round trip. Postgres
  takes a row lock on the primary key, so concurrent upserts on the same `(profile_id, title_id)`
  are serialised by the database.

**Chosen: fold in SQL**, for the same reason as D-014 — prefer one atomic operation over
read-modify-write, and let the storage engine serialise concurrent writers on a key.

`Fold.java` is *not* removed: it stays as the readable, unit-tested statement of the rule and is
reused on the Redis write path in phase 5. The rule is therefore expressed twice (the `Fold`
`sequence` check and the SQL `WHERE`); the duplication is one comparison and is accepted, with a
cross-referencing comment in each place.

*Consequence:* the in-memory `Store` (`ConcurrentHashMap`, D-014) retires once `fold-consumer`
writes durably — Postgres becomes the store of record, Redis the hot cache (phase 5).

*Rejected:* fold in Java against the DB — correct in the single-consumer case, but reintroduces
the D-014 race across a network round trip, for no benefit.

---

## D-020 · `JdbcClient` for Postgres access, not JPA/Hibernate · `SETTLED`

`fold-consumer` (and later `read-api`) needs a data-access layer. Spring offers a ladder: raw
JDBC → `JdbcClient` (thin fluent wrapper, hand-written SQL) → Spring Data JDBC → JPA/Hibernate
(full ORM).

**Chosen: `JdbcClient`.**

- The write path is a hand-written `INSERT ... ON CONFLICT (profile_id, title_id) DO UPDATE ...
  WHERE excluded.sequence > playback_state.sequence` upsert — Postgres-specific SQL an ORM cannot
  express without dropping to a native-query string anyway.
- The reads are two simple queries (one PK lookup, one indexed sorted query with `LIMIT`).
- Phase 4's learning goal is reading the SQL and its `EXPLAIN ANALYZE` plan. `JdbcClient` keeps
  the SQL in the code identical to the SQL that runs; an ORM hides it.
- SPEC §9 mandates a deliberately thin Spring. Hibernate brings a persistence context, flush
  timing, lazy loading and N+1 surprises — concept load with no benefit on this workload.

*Rejected:* JPA/Hibernate (hides SQL, heavy, needs a native query for the upsert regardless);
`JdbcTemplate` (older, more boilerplate — `JdbcClient` supersedes it); raw JDBC (manual resource
and result-set handling for no gain over `JdbcClient`).

---

## D-021 · The service runs in UTC (`-Duser.timezone=UTC`) · `SETTLED`

Bringing up the Postgres datasource, the JDBC connection was refused:
`FATAL: invalid value for parameter "TimeZone": "Asia/Calcutta"`. The pgjdbc driver sends the
JVM's default zone to Postgres on connect; this Windows JVM reports the legacy 1980s name
`Asia/Calcutta`, which Postgres 16's tzdata no longer recognises (only `Asia/Kolkata`).

**Decision: run the JVM in UTC** — `bootRun { jvmArgs('-Duser.timezone=UTC') }`, and the same in
the container image later. This is standard practice for a backend service: DB session, logs, and
every stored `timestamptz` agree on one canonical zone, and display-time localisation is a
client-side concern. The stale-zone-name quirk just forced the decision earlier than it would
otherwise have come up.

*Rejected:* `-Duser.timezone=Asia/Kolkata` — fixes the connection error with the canonical name
but leaves the service running in local time, which is the thing you don't want in a system whose
whole job is ordering timestamped events.

**Related environment finding (not a decision):** Spring Boot 4 split autoconfiguration into
per-technology modules. Flyway's autoconfiguration is no longer in `spring-boot-autoconfigure` —
it lives in a dedicated `org.springframework.boot:spring-boot-flyway` module, which `flyway-core`
alone does not pull in. Symptom: Flyway silently does not run, no log lines, no
`flyway_schema_history` table. Same class of Boot-4 modularisation gotcha as
`spring-boot-starter-kafka` in D-017. Fixed by depending on `spring-boot-flyway` (it brings
`flyway-core` transitively).

---

## D-022 · The Redis mirror write is post-transaction and best-effort · `SETTLED`

`FoldConsumer` writes each fold to Postgres (two statements, one transaction) and then mirrors
the new `playback_state` into Redis under `state:{profileId}:{titleId}` with a 1-hour TTL.

**The Redis write is deliberately outside the transaction and its failure is swallowed:**

- Redis is not transactional; a `SET` cannot be rolled back with the database transaction. Doing
  it before commit risks Redis holding a value the database then rejected.
- It runs only when the state upsert actually changed a row (`stateRowsApplied > 0`). A stale or
  duplicate heartbeat that the anti-rewind `WHERE` clause rejected must not overwrite a fresher
  cached value — the same D-019 rule, applied to the cache.
- A Redis failure is logged, not rethrown, and the Kafka message is still acknowledged. Failing
  the message would redeliver it and re-run the Postgres writes for nothing. A missed mirror
  self-heals: the next heartbeat for that key rewrites it, or a cache-aside read reloads it from
  Postgres, and the TTL bounds how long any stale entry can live.

Net: the cache can lag Postgres, never lead it. Postgres is the source of truth; Redis is a
disposable accelerator.

*Honest limitation:* the mirror write sits at the end of the `@Transactional` method, so it is
still technically inside the transaction window (the proxy commits on method return). A strictly
correct version uses an after-commit hook (`TransactionSynchronization.afterCommit`); judged not
worth the complexity for a TTL'd cache-aside cache whose staleness is already bounded. Recorded
here rather than left undocumented.

## D-023 · Read list (continue-watching) is served from Postgres, not a Redis structure · `SETTLED`

`resume` (point lookup by `(profileId, titleId)`) goes through Redis cache-aside.
`continue-watching` (a profile's in-progress titles, newest first, paginated, filtered by the
95% completion rule) is served straight from Postgres via the `idx_continue_watching` covering
index — **not** mirrored into a Redis sorted set.

- The query is a sorted, filtered, paginated range — what a B-tree index is for. Phase 4 measured
  it at ~0.09 ms with no sort step.
- A Redis ZSET equivalent would need a write on every heartbeat to a per-profile structure, an
  explicit removal when a title crosses 95%, and a batch rebuild after any Redis restart (no lazy
  reload path like cache-aside has). Three consistency problems for latency the index scan does
  not cost.
- Rule of thumb applied: point-lookup-by-key → cache in Redis; query an index already serves →
  leave it in Postgres.

## D-024 · Package layout: flat now, package-by-layer after phase 5 · `SETTLED`

All classes currently sit in the single `com.playhead` package. This is fine at ~11 files and is
how Spring Boot guides start, but does not scale.

**Decision: refactor to package-by-layer** once the phase-5 read path is complete, as one
mechanical repackaging step before the phase-5 commit:

```
com.playhead
├── controller   IngestController, ReadController
├── service      PlaybackReadService, TokenBucket
├── consumer     FoldConsumer
├── config       CaffeineConfig
├── domain       Heartbeat, PlaybackState, Fold
└── web          ValidationExceptionHandler
```

Package-by-layer is the conventional Spring Boot layout and the one most reviewers expect to
see; the codebase is small enough (one bounded domain, ~11 classes) that by-layer's usual
drawback — one feature's code scattered across several folders — does not bite. Spring's
component scan finds `@Component`/`@Service`/`@RestController`/`@Configuration` in any
sub-package under the application class, so no wiring changes are needed.

*Rejected:* package-by-feature (`ingest`, `fold`, `read`, `domain`). It keeps a feature's code
together and ages better in a large codebase, but this project has one small domain and a
by-layer tree communicates the request→service→data structure at a glance.

---

## D-025 · Redis is best-effort on the read path; command timeout 50 ms · `SETTLED`

The first three-tier read path had a hard dependency on Redis by accident: `resume` called
`redis.opsForValue().get(...)` with no error handling, so with Redis stopped the endpoint
returned `500` instead of falling through to Postgres. Cache-aside is only cache-aside if the
cache being unavailable is survivable.

**Two changes:**

1. **Redis reads and writes are wrapped and swallowed.** A failure is logged at WARN and treated
   as a cache miss; the request continues to Postgres. Measured: with Redis stopped the endpoint
   went from `500` to `200`.

2. **`spring.data.redis.timeout: 50ms`.** Lettuce's default command timeout is ~60 s, so the
   fallback in (1) only fired after the request had already hung for ~78 s — a correct answer at
   an unusable latency.

**Why 50 ms and not 10 ms.** The timeout is derived from the latency budget (NFR-3: read
p99 < 50 ms) and cross-checked against normal Redis latency (sub-millisecond), not guessed. A
very tight timeout produces **false positives** — a JVM GC pause, Redis's single thread blocked
behind another client's slow command, an RDB/AOF background fork, network jitter, or container
CPU throttling all cause legitimate multi-millisecond spikes. Those spikes cluster under load,
which is when a false timeout is most damaging: the cache is bypassed exactly when it is needed,
the full read load lands on Postgres, and the system can fail metastably — unable to recover even
after the original trigger passes.

**Measured result:** Redis up, `GET /v1/playback/resume/{titleId}` completes in ~14 ms. Redis
stopped, the same call completes in ~112–119 ms (the 50 ms command timeout plus connection-attempt
overhead), down from ~78,000 ms.

**Known limitation, and the justification for phase 6.** ~112 ms still exceeds the NFR-3 budget,
and a timeout has no memory — with Redis down, every request pays the wait on a dependency already
known to be gone. A **circuit breaker** is the correct tool for a dead dependency: after N
consecutive failures it stops calling Redis entirely (0 ms fallback), and a half-open probe
restores service automatically. That is ROADMAP phase 6 work, and this measurement — not a
general principle — is what justifies it. The 50 ms value is a defensible starting point to be
validated against the phase-5 k6 read profile and revised here if the data disagrees.

---

## D-026 · The consumer ignores the producer's type header · `SETTLED`

Moving `Heartbeat` from `com.playhead` to `com.playhead.domain` during the package refactor
(D-024) broke the fold-consumer at runtime while the build stayed green:

```
The class 'com.playhead.domain.Heartbeat' is not in the trusted packages:
  [java.util, java.lang, com.playhead]
```

`spring.json.trusted.packages` matches a package **exactly**, not as a prefix, and it lives in a
YAML string the compiler never sees. The consumer then retried the same unreadable record in a
tight loop until the JVM's garbage collector thrashed and the Gradle daemon was killed.

Two distinct faults were involved:

1. The moved class was no longer in the trusted list.
2. Records already on the topic carry a type header naming `com.playhead.Heartbeat`, a class that
   no longer exists — so even a corrected trust list would fail those with `ClassNotFound`.

**Decision: turn type headers off and name the type explicitly.**

```yaml
spring.json.use.type.headers: false
spring.json.value.default.type: com.playhead.domain.Heartbeat
```

The `heartbeats` topic carries exactly one message type, so there is nothing for a type header to
disambiguate. Deserializing directly to a named class decouples the consumer from the producer's
class name and package — the coupling that caused this failure — and removes the need for
`trusted.packages`, which exists only to make type headers safe to honour.

*Rejected:* widening the trust list to `com.playhead.*` — fixes fault 1 but not fault 2, and
leaves the consumer coupled to the producer's class naming. Wiping the topic — hides the problem
rather than fixing it, and would not be available in production.

**Operational note:** a poison-pill record blocks a partition indefinitely and the retry loop is
hot, not idle. The consumer has no `ErrorHandlingDeserializer` or dead-letter topic, so any record
it cannot deserialize still halts that partition. Left as a known gap for the phase-6 drills
rather than fixed speculatively.

---

## D-027 · A circuit breaker in front of Redis on the read path · `SETTLED`

D-025 set a 50 ms Lettuce command timeout and predicted a circuit breaker would be needed. Phase-6
Drill 1 measured why. With Redis killed under the k6 read profile (2,000 rps target):

| `resume` latency after the kill | timeout only (D-025) | + circuit breaker |
|---|---|---|
| p50 | 12,881 ms | 1.57 ms |
| p99 | 15,814 ms | 72 ms |
| `RedisCommandTimeoutException` during the outage | 30,336 | 71 |

A single request with Redis down cost ~114 ms both ways — the timeout worked. Under load that same
call reached ~12.8 s. The 50 ms timeout bounds *one* call; it does nothing about a caller making
~4,000 doomed calls/sec (one GET + one SET per read) at a dependency already known to be down.
Lettuce funnels a connection's I/O, its reconnect attempts, and every command's timeout timer
through one event-loop worker; the flood of failing work buried it, so timeouts fired far later
than 50 ms. Postgres was never the bottleneck — every query stayed under 1 ms, no Hikari pool
exhaustion.

**Decision: a small hand-rolled circuit breaker (`service/CircuitBreaker.java`), one instance
guarding both Redis calls in `PlaybackReadService`.**

- **Trip:** 5 consecutive failures → OPEN. The threshold is low because the drill is direct
  evidence that continuing to call is catastrophic, not merely slow.
- **Open duration:** 5 s, then one probe (HALF_OPEN). Redis restarts in well under a second; a
  probe costs one request the ~50 ms timeout, so probing every 5 s is cheap.
- **Close:** the probe succeeds → CLOSED, counter reset.

*Hand-rolled, not Resilience4j:* the logic is an enum, a counter and a timestamp — not a subtle
algorithm like W-TinyLFU, so D-007's "don't reimplement a library" rule does not apply.
Resilience4j's Spring Boot starter targets Boot 3, and this project has already been bitten three
times by Boot 4 module changes (D-017 Kafka starter, D-021 Flyway module, Jackson 3). Same
reasoning as the phase-2 `TokenBucket`.

*Verified recovery, and a genuine finding:* after a ~30 s outage the breaker closed on its own with
no app restart, but ~36 s after Redis returned, not 5 s. Two probes fired and failed first because
Lettuce had not finished its own reconnect backoff — breaker recovery is gated by the client's
reconnect schedule, not only the breaker's cooldown. Written up in `ENGINEERING_LOG.md`.

*Known limitation:* `continue-watching` still degraded at p99 (8 ms, from ~5 ms) during the outage
even though it never touches Redis — collateral from the brief flood before the breaker tripped.
The breaker does not eliminate the trip window, only bounds it.

*Rejected:* raising the timeout instead (makes the jam worse); `bulkhead`/bounded Redis connection
pool only (caps concurrency but every permitted call still pays the full wait); Resilience4j (Boot
4 starter risk for ~40 lines of avoidable logic).

---

## D-028 · The Redis health contributor does not gate service health · `SETTLED`

Phase-6 Drill 1: with Redis killed under load, `/actuator/health` returned `503 {"status":"DOWN"}`
for the whole outage, even though every read was being served correctly from Postgres. The
autoconfigured Redis health indicator (from `spring-boot-starter-data-redis` + actuator) aggregates
into the top-level status. Behind a load balancer that probes `/actuator/health`, the instance
would be evicted precisely while it is still doing its job.

**Decision: `management.health.redis.enabled: false`.** Redis is a best-effort accelerator
(D-025, D-027), not a dependency. Its liveness is already observable — cache hit-rate metrics
(`/actuator/metrics/cache.gets`) and the circuit breaker's logged state transitions. The Postgres
`db` indicator is deliberately left gating, because Postgres genuinely is a dependency: Drill 2
confirmed `/actuator/health` correctly reports `DOWN` when Postgres is killed.

*Rejected:* a custom health group that keeps `redis` visible but out of the readiness probe —
cleaner in principle, but a load balancer here probes the top-level endpoint, and the metric-based
visibility is enough for this project.

---

## D-029 · The event log is at-least-once; the fold makes duplicates harmless · `SETTLED`

Phase-6 Drill 3 (`kill -9` the consumer mid-backlog) produced **288 duplicate
`(profile_id, title_id, sequence)` rows in `playback_events`** out of ~65,000 messages (0.4%),
each with a distinct `received_at` — genuine post-crash redelivery.

Cause: `onHeartbeat` commits the `playback_events` INSERT and the `playback_state` UPSERT in one
`@Transactional` unit, but `ack.acknowledge()` commits the Kafka offset **separately** — Kafka and
Postgres are not in a shared transaction. SIGKILL after the DB commit and before the offset commit
redelivers that message; the state UPSERT is a no-op (the D-019 sequence guard), the unconditional
event INSERT runs a second time.

**Decision: accept it.** `playback_state` is provably exactly-once (Drill 3: 0 anti-rewind
violations). FR-10 replay folds `playback_events` through `Fold`, whose sequence check turns a
duplicate event into a no-op — a replay from the duplicated log rebuilds byte-identical state
(NFR-10 holds). The cost is 0.4% redundant log rows after a hard crash, which no consumer of the
log is sensitive to.

*Fix if it is ever needed:* `UNIQUE (profile_id, title_id, sequence)` on `playback_events` +
`ON CONFLICT DO NOTHING` on the INSERT — a V2 migration plus one line. Not applied now: it adds a
schema constraint and migration for a duplication the design already absorbs, and it would need
care around the load generator's `sequence = Date.now()` which can collide under high concurrency.

*Rejected:* an idempotency/dedup table keyed by message offset (heavier than the problem);
transactional outbox / exactly-once Kafka semantics (large architectural change for a 0.4%
cosmetic effect on an append-only log).

---

## D-030 · Priority tiering proven; the >99% write-success gate stays open, honestly · `SETTLED`

Phase 2 shipped a `TokenBucket` on the write path only; the read endpoints phase 5 added had no
limit at all, so the roadmap's central claim — writes protected, reads shed first — had never been
demonstrated. Built `AdmissionControl` (3 tiers, sized 80/15/5 against the phase-2 write ceiling)
and `load/mixed.js` to offer all three at once. Full run-by-run narrative in
[ENGINEERING_LOG.md](ENGINEERING_LOG.md).

**What was proven, unambiguously:** `admission.shed{tier=PLAYBACK_WRITE}` was **0** across every
run. Writes were never shed by the priority mechanism, in any of the four runs, at any offered
load. The design works exactly as intended.

**What was not reached:** the gate's literal `>99%` write success. Best measured result was
**89.32%**. Diagnosis, in order:
1. First failure mode: a pre-existing, unrelated 200-permit `Semaphore` (bounding in-flight Kafka
   sends) was shedding on its own — not the new bucket. Resized to 1,000 using a real measurement
   (Little's Law against a measured p95 Kafka-confirm wait), which raised write success from
   49.65% to 63.90%.
2. Second failure mode, found after lowering offered load to something the test rig could
   honestly sustain: real CPU contention on one machine simultaneously running the load generator,
   the app, and Kafka/Postgres/Redis in Docker — surfaced as Redis calls missing their 50 ms
   budget from slowness (not an outage) and the OS refusing TCP connections outright at peak.
3. Resizing the semaphore further (1,000 → 2,000) was tried and made the result *worse* (87.20%,
   with the door shedding zero) — proof the remaining ceiling is machine capacity, not limiter
   sizing.

**Decision: leave the gate item honestly unticked rather than mark it done at a number the system
did not reach.** ROADMAP.md phase 2 records 89.32% with the reasoning above, not a false
checkmark. The mechanism this gate exists to prove (priority-based shedding under overload) is
proven; the specific throughput number is bounded by test-rig capacity, not application logic, and
would need either a smaller offered load (the measurement, not the system, would then be the thing
that changed) or infrastructure beyond one laptop to close cleanly.

*Rejected:* keep resizing the semaphore in hope of a passing run — run 4 already showed more
permits make the result worse, not better, once the bottleneck moved to the OS/CPU layer.
