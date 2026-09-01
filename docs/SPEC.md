# Playhead — Specification

**Status:** v2 · **Last revised:** 2026-09-02

This document defines *what* Playhead must do and *how well*. [ROADMAP.md](ROADMAP.md) defines the
order of construction.

Read this completely before writing code. A phase that satisfies its features but misses its
non-functional requirements is not finished.

---

## 1. What Playhead is

A **playback-state platform for an OTT streaming service, engineered to survive a tentpole event.**

Two halves, and both matter:

**The system.** Every playing device reports its position every ~10 seconds. At scale that is the
highest-volume write path in a streaming backend, and every app launch reads it back to answer
"where was I?". Heartbeats land in Kafka, are folded asynchronously into Redis and PostgreSQL, and
served back through a cached read path.

**The thesis.** A *tentpole* — a premiere, a live match — takes that traffic from idle to peak in
under a minute. Playhead is built to absorb that: capacity is pre-scaled from an event schedule
before the spike arrives, and admission control sheds load by priority when it does.

```
                  TENTPOLE: 0 -> peak RPS in 60 seconds
                                  |
WRITE   player --heartbeat/10s--> ingest-api --> KAFKA --> fold-consumer --> Redis => Postgres
READ    client --> read-api --> cache --> Redis --> Postgres
GUARD   event-schedule --> scaling-controller --> pre-scale | admission control | load shedding
PROVE   k6 premiere curve --> Prometheus/Grafana --> run A collapses, run B holds
```

## 2. Why this problem

It is a documented problem in production streaming platforms. On premiere traffic, Warner Bros.
Discovery's engineering team reports:

> *"Premier content, like Game of Thrones, Succession, and Euphoria, is released simultaneously on
> linear and digital platforms which causes a significant increase in requests per second (RPS) to
> core APIs. **Pre-scaling the platform was essential as autoscaling couldn't always keep up with
> the acceleration in RPS.**"*

They cite House of the Dragon S2 RPS curves and describe forcing portfolio-wide scale-out ahead of
anticipated events. Elsewhere they name **graceful degradation during outages** as a core
challenge, across 125M+ subscribers and three AWS regions per continent.

This system exists to make that problem tractable and, crucially, **measurable** — the surge is
reproduced under controlled load rather than argued about.

## 3. What Playhead is not

- **Not a video player, transcoder, packager or CDN.** No media bytes pass through it.
- **Not a recommendation engine.** It answers *where were you*, nothing more.
- **Not an analytics platform.** No trending, top-K, or unique-viewer counting.
- **Not an auth or entitlement service.** Profiles are opaque identifiers.
- **Not an algorithms showcase.** Where a good library exists, use it and say why. Caffeine is
  W-TinyLFU written by the people who published the paper; reimplementing it proves nothing about
  engineering judgment.

## 4. Functional requirements

| ID | Requirement |
|---|---|
| **FR-1** | **Heartbeat ingest.** Accept `profileId`, `titleId`, `deviceId`, `positionSeconds`, `durationSeconds`, `clientTimestamp`, `sequence`. Acknowledge `202` without waiting on durable storage. |
| **FR-2** | **Validation.** Reject malformed heartbeats with `400` naming every failed field. |
| **FR-3** | **Resume position.** Return the exact resume point for `(profileId, titleId)` within the staleness bound in NFR-4. |
| **FR-4** | **Continue-watching.** A profile's in-progress titles, most recent first, paginated. |
| **FR-5** | **Completion.** Past a threshold (default 95%) a title leaves continue-watching. |
| **FR-6** | **Convergence.** Heartbeats from several devices for one `(profile, title)` converge correctly. Out-of-order and duplicate delivery must never rewind a viewer. |
| **FR-7** | **Event schedule.** Register an upcoming tentpole: start time, expected peak RPS, titles involved. |
| **FR-8** | **Pre-scaling.** The controller raises capacity ahead of a scheduled event and returns it afterwards, without human action. |
| **FR-9** | **Admission control.** Under overload, shed by priority — playback writes protected, browse shed first — with `429` and `Retry-After`. |
| **FR-10** | **Replay.** Reprocessing the whole event log reproduces current state exactly. |

## 5. Non-functional requirements

**Every number below is a target, not a measurement.** When one is revised, record the old value,
the new value and the reason in [DECISIONS.md](DECISIONS.md). Never silently move a goalpost.

| Tier | Meaning |
|---|---|
| **Design target** | What the architecture is *sized* for. Justified by arithmetic. |
| **Demonstrated** | What is actually proven on the 12 GB VM, with the k6 output behind it. |

| ID | Requirement | Design target | Demonstrated |
|---|---|---|---|
| **NFR-1** | Ingest throughput | 10,000 heartbeats/sec (100k streams / 10s) | >= 2,000/sec sustained |
| **NFR-2** | Ingest latency | p99 < 25 ms — the endpoint only appends to Kafka | measured |
| **NFR-3** | Read latency | p99 < 50 ms | measured |
| **NFR-4** | Read staleness | a heartbeat is visible to a read within 2 s | measured |
| **NFR-5** | **Surge absorption** | **0 -> peak RPS in 60 s.** With the guard layer: p99 within NFR-3, errors < 1%. Without it (control run): both degrade measurably. **The delta between the two runs is the headline result.** | both runs recorded |
| **NFR-6** | Pre-scale lead time | Capacity in place before the event, not during it. Measured as seconds-ready-before-start. | measured |
| **NFR-7** | Shedding fairness | Under overload, playback writes succeed at > 99% while lower-priority traffic absorbs the shedding. | measured |
| **NFR-8** | Durability | No acknowledged heartbeat lost across a broker or consumer restart. | drill |
| **NFR-9** | **Degradation** | Redis down -> reads fall back to Postgres. Postgres down -> writes still accepted, buffered in Kafka. Consumer stalled -> lag recovers with no duplicate application. | three drills |
| **NFR-10** | Idempotency | Replaying the log yields identical final state. | test |
| **NFR-11** | Observability | p50/p95/p99, error rate, consumer lag, instance count, cache hit rate all visible in Grafana during a surge. | dashboard |
| **NFR-12** | Cost honesty | Pre-scaling wastes capacity. Measure the idle instance-minutes and state the trade-off. | recorded |
| **NFR-13** | Resource envelope | Everything runs inside the 12 GB VM with declared limits per service. | `docker stats` |

## 6. The differentiators — non-negotiable

*Referenced as `DIFF-n` throughout. Distinct from `D-nnn` in [DECISIONS.md](DECISIONS.md), which
are engineering decisions.*

| # | Differentiator | What "done" means |
|---|---|---|
| **DIFF-1** | **Surge survival, measured** | Two runs of one k6 premiere curve — guard layer off, then on — and the numbers between them. This is the headline. |
| **DIFF-2** | **Pre-scaling from a schedule** | A controller that reads upcoming events and raises capacity *before* the spike, because reactive autoscaling cannot catch a 60-second ramp. |
| **DIFF-3** | **Priority-tiered admission control** | Under overload the system chooses what to drop, and playback writes are never what it drops. |
| **DIFF-4** | **Degradation, not failure** | Redis, Postgres and the consumer each killed deliberately under load. Documented degraded modes, real output. |
| **DIFF-5** | **Evidence** | k6 tables, Grafana dashboards, and an `ENGINEERING_LOG.md` of failures that really happened. |

## 7. The bar

1. **Measured, not asserted.** Every performance claim has a number in `BENCHMARKS.md`.
2. **Every result has a control run.** The surge numbers mean nothing without the run that
   collapsed. The comparison *is* the contribution.
3. **Failure is demonstrated, not described.**
4. **Costs are stated.** Pre-scaling wastes capacity. The waste is measured and reported alongside
   the benefit, not omitted.

## 8. Forbidden shortcuts

- Hand-writing a cache eviction policy, sketch, or Bloom filter. **Use Caffeine.**
- Reporting a surge run without its control run.
- Claiming a number that was not measured.
- Re-litigating the stack in section 9.
- Adding a feature outside section 4 without first adding it to section 4.

## 9. Stack — decided, do not re-open

| Layer | Choice |
|---|---|
| Language | Java 25 (virtual threads for the concurrent ingest story) |
| Framework | Spring Boot 4.1.1, kept deliberately thin |
| Build | Gradle, via the wrapper |
| Event log | Apache Kafka (KRaft) |
| Hot state | Redis 7 |
| Durable state | PostgreSQL 16 |
| Cache library | **Caffeine** — not hand-built |
| Orchestration | Docker Compose (k3s + HPA optional — D-013) |
| Metrics | Prometheus + Grafana |
| Load testing | k6 |
| Target | A 12 GB Oracle Cloud VM |

Rationale in [DECISIONS.md](DECISIONS.md) D-001 and D-013.

## 10. End state

**The repository contains:** `ingest-api`, `read-api`, `fold-consumer`, `event-schedule`,
`scaling-controller`; a shared admission-control module; `docker-compose.yml`; k6 spike profiles;
Grafana dashboards; and a `README.md` opening with the surge table. Optionally, k3s manifests with
an HPA (D-013).

**The demo, in order:**

1. State the problem: a tentpole event takes traffic from idle to peak in under a minute.
2. `docker compose up`. Simulated players emit heartbeats; the dashboard is calm.
3. Register a tentpole event starting in two minutes. Watch the controller pre-scale.
4. **Run A — guard layer off.** k6 drives 0 -> peak in 60s. p99 blows out, errors climb, and
   reactive scaling is still catching up when the spike has already passed.
5. **Run B — guard layer on.** Same curve. p99 holds, errors near zero, and what got shed was
   browse traffic, not playback.
6. `docker kill redis` mid-run. Latency steps up. Error rate stays flat.
7. Open `ENGINEERING_LOG.md` and walk through a failure that occurred during development.

**What the demo establishes:** that reactive autoscaling cannot catch a 60-second ramp; that
pre-scaling from a known event schedule can; that under overload the system sheds by priority
rather than failing indiscriminately; and that the cost of that headroom is measured rather than
hidden.
