# Engineering log

Real failures, drilled or stumbled into, with the terminal output behind them. This is not a
changelog — it is the record of what broke, what the numbers were, and what changed as a result.

Phase 6 is three deliberate dependency failures under load (ROADMAP phase 6, NFR-9). Each is run
with a control (the same load, dependency healthy) so the delta is the result.

---

## Drill 1 — Redis killed under load

**Hypothesis:** with Redis gone, the read path falls back to Postgres. Error rate stays flat;
latency steps up but stays bounded.

**Setup**

- 25,000 `playback_state` rows seeded to match `load/reads.js` exactly (500 profiles × 50 titles).
- Redis warmed to ~24,000 keys by a full control run first.
- `k6 run load/reads.js` — ramping-arrival-rate, 2,000 rps target, 70/30 resume/continue-watching.
- `docker kill playhead-redis` at t+73 s, during the sustained-peak stage.
- k6 CSV output split at the kill timestamp for before/after percentiles.

### Run 1 — 50 ms Lettuce timeout only (the D-025 state)

Control run (Redis healthy throughout): 122,203 requests, **0.00 % failed**, `resume` p99 **22.96 ms**.

Drill run, `resume` latency, all values ms:

| | before kill (n=39,830) | after kill (n=16,614) |
|---|---|---|
| p50 | 1.58 | **12,881.80** |
| p95 | 3.71 | 15,687.24 |
| p99 | 9.01 | 15,814.64 |
| max | 120.70 | 15,878.59 |

`continue-watching` (never touches Redis): p99 7.06 → **58.70 ms**, max 69 → 102 ms.

```
http_req_failed ...: 0.00%  0 out of 80267
dropped_iterations : 41948  363.124093/s
     resume_duration: avg=3.19s  p(90)=14.9s  p(95)=15.41s  max=15.87s
time="..." level=error msg="thresholds on metrics 'resume_duration' have been crossed"
```

App log during the outage:

```
  30336  RedisCommandTimeoutException: Command timed out after 50 millisecond(s)
```

`/actuator/health` while Redis was down: `{"status":"DOWN"}` HTTP 503.

**What the drill established**

- **Correctness held.** 0.00 % HTTP failures. Every read that lost Redis was answered by Postgres.
- **Latency did not.** A single request with Redis down cost **114 ms** (the 50 ms timeout on the
  GET plus 50 ms on the cache-repopulate SET, plus overhead) — exactly D-025's prediction. Under
  load the *same call* reached **12.8 s**: ~113× amplification purely from concurrency.
- **Not Postgres.** Every query stayed under 1 ms; zero Hikari pool-exhaustion in the log.
- The cause: a per-call timeout bounds one call and does nothing about making ~4,000 doomed calls
  per second. Lettuce routes a connection's I/O, its reconnect attempts, and every command's
  timeout timer through one event-loop worker; the flood buried it, so timeouts fired far later
  than 50 ms. Full analysis in DECISIONS.md D-027.

### The fix — circuit breaker (D-027)

`service/CircuitBreaker.java`, one instance in `PlaybackReadService` guarding both Redis calls.
Trips after 5 consecutive failures → OPEN 5 s → one probe → close on success.

### Run 2 — with the circuit breaker

Control run (Redis healthy): `resume` p95 **4.27 ms**, 0.00 % failed — no measurable overhead from
the breaker on the healthy path.

Drill run, `resume` latency after the same kill, all values ms:

| | Run 1 (timeout only) | Run 2 (+ breaker) |
|---|---|---|
| p50 | 12,881.80 | **1.57** |
| p95 | 15,687.24 | 3.71 |
| p99 | 15,814.64 | 72.46 |
| max | 15,878.59 | 367.17 |

```
http_req_failed after kill : 0 / 64400
RedisCommandTimeoutException (whole outage) : 71     (was 30,336)
single request, Redis down  : 3.7 ms                 (was 114 ms)
```

Breaker transitions, from the app log:

```
CircuitBreaker : Circuit breaker 'redis' -> OPEN after 5 consecutive failures, skipping 'redis' for 5000 ms
CircuitBreaker : Circuit breaker 'redis' OPEN -> HALF_OPEN, letting one probe through
CircuitBreaker : Circuit breaker 'redis' -> OPEN after 65 consecutive failures, skipping 'redis' for 5000 ms
... (one probe every 5 s, each fails while Redis is genuinely down) ...
```

p99 is still 72 ms — above NFR-3's 50 ms — because the ~65 requests in flight when the breaker
trips, plus one probe every 5 s, each still pay the 50 ms timeout. p95 (3.71 ms) is unaffected.
The breaker bounds the trip window; it does not remove it.

### Unplanned finding 1 — breaker recovery is gated by Lettuce's reconnect backoff

After a ~30 s outage, `docker start playhead-redis`. The breaker closed on its own with no app
restart — but **~36 s after Redis returned, not 5 s**:

```
22:46:10  redis restarted
22:46:18  Circuit breaker 'redis' OPEN -> HALF_OPEN, letting one probe through
22:46:18  Circuit breaker 'redis' -> OPEN after 71 consecutive failures      <- probe FAILED, Redis is up
22:46:28  OPEN -> HALF_OPEN ... -> OPEN after 72                             <- probe FAILED again
22:46:46  OPEN -> HALF_OPEN
22:46:46  Circuit breaker 'redis' HALF_OPEN -> CLOSED
```

The first two probes fired against a Redis that was healthy but that **Lettuce had not finished
reconnecting to** — Lettuce's own reconnect backoff had grown during the outage, so the probe
command still timed out at 50 ms. The breaker's 5 s cooldown is a floor on recovery time, not the
whole story; the client's reconnect schedule can dominate. Not worth tuning for this project —
recovery is still automatic and unattended — but it is the honest picture.

### Unplanned finding 2 — Redis down dragged whole-app health to DOWN — FIXED

`/actuator/health` returned `503 {"status":"DOWN"}` for the entire outage in both runs. The
autoconfigured Redis health indicator is wired into the top-level health group, so a dead
*accelerator* made the service report itself unfit — the opposite of the degradation story. A
load balancer would pull this instance out while it is still serving reads correctly from Postgres.

**Fix:** `management.health.redis.enabled: false` in `application.yml`. Redis is a best-effort
accelerator (D-025/D-027); its liveness is observed through the cache metrics and the breaker's
logged state, not through the service health check. Postgres's `db` indicator is left gating — it
is a real dependency.

Verified: with the fix, `docker kill playhead-redis` → `/actuator/health` stays `200 {"status":
"UP"}`, `redis` no longer listed as a component, a `resume` read still returns `200`.

**Drill 1 status:** fallback proven, circuit breaker added and measured, health-indicator fix
applied and verified, one finding (Lettuce reconnect backoff) recorded.

---

## Drill 2 — Postgres killed under load

**Hypothesis:** with Postgres gone, heartbeat writes are still accepted (`202`) and buffered in
Kafka. When Postgres returns, the consumer drains the backlog with no loss and no double-application.

**Setup**

- `k6 run load/premiere.js` with `PEAK=500` — ~300 heartbeats/sec average over the curve.
- A sampler recording committed offset, log-end offset and `playback_events` count every 2 s.
- `docker kill playhead-postgres` at t+42 s; `docker start` 42 s later.

**Writes during the outage**

```
5 direct heartbeat POSTs, Postgres down:
  202 in 0.009s   202 in 0.007s   202 in 0.010s   202 in 0.006s   202 in 0.008s
k6 over the whole run: http_req_failed 0.00%  (every request 202, none shed, none errored)
```

`IngestController` only appends to Kafka — it never touches Postgres — so the write path is
unaffected. `/actuator/health` correctly went `503 {"db":"DOWN"}` (Postgres *is* a dependency).

**Consumer during the outage**

Committed offset froze at **77,283**. The producer kept appending (log-end offset climbed to
~98,000). Every `onHeartbeat` attempt hit `PSQLException: Connection refused` → `@Transactional`
rolled back → offset not committed → Spring Kafka's `DefaultErrorHandler` retried and logged
`Seeking to offset 77282` / `Record in retry and not yet recovered`. Each attempt paid Hikari's
30 s connection timeout. The `db` health indicator also blocked a Tomcat handler thread for 30 s
per call (`Health contributor ... (db) took 30007ms to respond`).

**Recovery**

Committed offset unfroze within one sampler tick of Postgres accepting connections, then drained:

```
t+43s  committed 77,283   lag 22,915      <- Postgres back
t+91s  committed ~86,600   ...
t+179s committed 101,998  lag 0           <- fully caught up
```

~24,700 backlogged messages in ~137 s ≈ **180–215 msg/s** — the single-partition,
one-transaction-per-message ceiling. No hot loop, no crash.

**Integrity — exactly what the gate needs**

| check | result |
|---|---|
| `playback_events` count | **32,247** = 2 baseline + 32,240 k6 + 5 manual — exact |
| duplicate `(profile_id,title_id,sequence)` rows | **0** |
| anti-rewind violations (`state.sequence` ≠ max event sequence) | **0** |
| the 5 heartbeats sent *during* the outage | all landed — `drill2-p` → seq 1005 |

Writes survived, nothing was lost, nothing was double-applied, the anti-rewind rule held across
the redelivery. Recovery is correct but slow — see finding 3.

### Unplanned finding 3 — backlog drain is capped at ~200 msg/s

One partition → one consumer thread → one `INSERT + UPSERT + commit` at a time. Pre-outage intake
(~300/s) already ran slightly ahead of drain, and recovery drained at ~200/s. A longer or busier
outage would take proportionally longer to clear. The fix is more partitions + `@KafkaListener`
concurrency (or batch consumption); it is scaling work (phase 7 territory), recorded here rather
than done reactively.

---

## Drill 3 — consumer crashed mid-backlog (`kill -9`)

**Hypothesis:** a consumer that stops with a backlog outstanding resumes from its last committed
offset, drains the backlog, and applies nothing twice.

**Setup**

- `k6 run load/premiere.js` with `PEAK=2500`.
- At t+48 s, `Stop-Process -Force` (SIGKILL) the app — no graceful shutdown. State at kill:
  committed offset 110,715, log-end 143,948, **lag 33,233**, `playback_events` 41,461.
- 15 s down, then restart.

**Recovery**

```
04:46:43  committed 111,715  lag 51,111   <- app back, k6 still producing
04:51:00  committed 167,034  lag 0        <- caught up
```

~55,000 messages in ~257 s ≈ **215 msg/s**, same ceiling as Drill 2. Clean, unattended.

**Integrity — and a real bug**

| check | result |
|---|---|
| committed offset advance | 101,998 → 167,034 = 65,036 messages consumed |
| `playback_events` count | 97,570 — expected exactly-once 97,283 → **287 extra** |
| duplicate `(profile_id,title_id,sequence)` rows | **288** (all count = 2) |
| those dupes: same `client_timestamp`, different `received_at` | yes — true redelivery, not a client collision |
| distinct `(profile_id,title_id,sequence)` | 97,282 ≈ expected |
| **anti-rewind violations** | **0** — `playback_state` is exactly-once correct |

### Unplanned finding 4 — SIGKILL in the offset-commit window duplicates event-log rows

`onHeartbeat` is `@Transactional`: the `playback_events` INSERT and the `playback_state` UPSERT
commit together. But `ack.acknowledge()` commits the **Kafka offset in a separate step** — Kafka
and Postgres are not in one transaction. `kill -9` in the window *after* the DB commit and *before*
the offset commit means that message is redelivered on restart. Its state UPSERT is a no-op (the
`WHERE excluded.sequence > playback_state.sequence` guard), but its event INSERT is unconditional
and runs again → a duplicate row. 288 of ~65,000 messages (0.4%) hit that window.

**Impact: bounded and harmless by design.** `playback_state` is provably unaffected (0 anti-rewind
violations). FR-10 replay folds `playback_events` through `Fold`, whose sequence check turns a
duplicate event into a no-op — so a replay from the duplicated log produces byte-identical state.
NFR-10 holds. The only cost is 0.4% redundant rows in the log after a hard crash.

**Fix, if the log must be literally exactly-once:** `UNIQUE (profile_id, title_id, sequence)` on
`playback_events` + `ON CONFLICT DO NOTHING` on the INSERT (a V2 migration + one line in
`FoldConsumer`). Deferred — flagged for a decision rather than applied, because the design already
tolerates the duplication and the constraint would need care around the load test's
`sequence = Date.now()` collisions.

---

## Phase 6 gate

| gate item | status |
|---|---|
| Three drills run, real terminal output, error rates truthful | ✅ Drills 1–3 above |
| At least one genuine unplanned failure written up | ✅ four (findings 1–4) |
| Degraded-mode behaviour documented from real drills, not asserted | ✅ every claim has a number |

---

## Phase 2 gate closure — priority-tiered admission control under a mixed load

Phase 2's last open gate item: *"under overload, playback writes succeed > 99% while browse
absorbs the shedding — not verified."* The write path had its own `TokenBucket`; the read
endpoints added in phase 5 had no limit at all, so nothing could prove writes were shielded by
letting reads take the loss instead. Built `AdmissionControl` (3 tiers — `PLAYBACK_WRITE` 8000/s,
`RESUME_READ` 1500/s, `BROWSE_READ` 500/s — sized 80/15/5 against the phase-2 write-only ceiling),
put both read endpoints behind it, and wrote `load/mixed.js` to offer all three tiers at once.

### Run 1 — first mixed run, 20,000/s offered (2x the 10,000/s admission ceiling)

```
write_success..................: 49.65% 99182 out of 199761
resume_shed.....................: 24.93%
browse_shed.....................: 39.42%
```

`admission.shed{tier=PLAYBACK_WRITE}` was **0** — the priority logic worked exactly as designed,
every shed landed on a read tier. But write success was nowhere near 99%. Root cause was not the
new bucket: `IngestController`'s pre-existing 200-permit `Semaphore` (bounding in-flight Kafka
sends, unrelated to the new tiers) was shedding on its own. Measured why: a write-only k6 run at
7,000/s (the mixed run's write target) showed a real Kafka confirm wait of **p95 26 ms**
(`kafka.producer.record.queue.time` + `kafka.producer.request.latency`, actuator metrics). By
Little's Law that is ~182 requests in flight at once even with nothing else competing for CPU — the
old value of 200 had effectively zero headroom.

### Run 2 — semaphore resized 200 → 1,000, same 20,000/s offered

```
write_success..................: 63.90% 120240 out of 188169
```

Real improvement, still short. The new limiter was CPU contention itself: Redis calls were missing
their 50 ms budget from **slowness under shared CPU, not an outage** (2,645
`RedisCommandTimeoutException`s; `docker ps` showed Redis healthy throughout), and the OS began
refusing new TCP connections outright during the busiest seconds — k6, the app, and
Kafka/Postgres/Redis in Docker were all contending for the same cores on one machine.

### Run 3 — offered load lowered to a level this one machine can honestly generate and serve

Read peaks cut from 9,000/1,000 down to 3,000/1,000 (writes left at 7,000, unchanged — they were
never the problem). Total offered 11,000/s, a modest overload against the 10,000/s ceiling instead
of 2x.

```
write_success..................: 89.32% 335849 out of 376006
```

### Run 4 — one more resize attempt, semaphore 1,000 → 2,000

```
write_success..................: 87.20% 327752 out of 375850   (worse)
write 429 (shed).................: 0 out of 375850              (the door shed nothing)
```

Widening the door further did not help — it made the result slightly worse, and the door itself
stopped rejecting anything. That is the tell: every remaining loss had moved to the OS refusing the
connection outright, which is a **single-machine capacity ceiling**, not a limiter sized wrong.
Reverted to 1,000, the better of the two measured values.

### Result

**Priority tiering fully proven:** `admission.shed{tier=PLAYBACK_WRITE}` was **0** in every run —
writes were never shed by the mechanism built to protect them. **The phase-2 gate's literal number
(>99%) was not reached — 89.32% is the honest, final, measured result** on a single machine
simultaneously hosting the load generator, the app, and Kafka/Postgres/Redis. See D-030.

---

## Drill 2 — Postgres killed under load

See the full write-up above.

## Drill 3 — consumer stalled, lag recovery

See the full write-up above.
