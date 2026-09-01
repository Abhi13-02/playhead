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
