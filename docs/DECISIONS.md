# Decision log

Every non-obvious choice, with the reasoning and what was rejected. Append-only: to change a
decision, add a new entry that supersedes the old one rather than editing history.

**Agents: do not re-open a decision marked `SETTLED`.** If you believe one is wrong, say so to
Abhinav and let him decide — do not quietly implement something different.

Interview value: an interviewer who asks "why did you choose X?" is asking for exactly this file.
The answer "here is what I chose and here is what I rejected" is a much stronger answer than a
justification invented on the spot.

---

## D-001 · The stack · `SETTLED`

**Java 25, Spring Boot 4, Kafka, PostgreSQL, Redis, Kubernetes, AWS-shaped.**

Chosen to mirror WBD India's real backend stack, taken from their job postings: Java, Kafka,
Kubernetes/EKS, PostgreSQL, strong SQL. They run a dedicated Kafka team (AMS). The project is a
deliberate match to the interviewer's daily vocabulary.

*Rejected:* Python/FastAPI or Node — Abhinav already knows both, so neither demonstrates growth,
and neither matches the target stack. Go — closer in spirit, still not their stack.

---

## D-002 · The project idea · `SETTLED`

**A playback-state and live-engagement platform, not a recommender or a clone of Max.**

Research into WBD's interview loop (first-hand accounts on GeeksforGeeks and Taro) shows the
technical round leaves DSA quickly and becomes an OTT system-design conversation — one candidate
was asked to design a show recommendation system and pushed on *space complexity, caching strategy,
memory utilisation and database choice*. The managerial round re-opens the same design.

The project is chosen so **their hardest round becomes a demo of already-shipped work.**

*Rejected:* a recommendation engine — the interesting parts are ML, not systems, and it invites
questions about model quality rather than engineering. A video transcoding pipeline — impressive
but unbuildable on a 12 GB VM and unrelated to their backend roles.

---

## D-003 · `202 Accepted`, not `200 OK`, for heartbeat ingest · `SETTLED`

`200` means the work is done. `202` means responsibility is accepted but processing has not
happened. Once Kafka sits in front of storage that is literally true. Writing the contract this way
from the first commit means player clients never have to change.

---

## D-004 · Heartbeats go to Kafka, never synchronously to Postgres · `SETTLED`

100,000 concurrent streams ÷ one heartbeat per 10 s ≈ **10,000 writes/sec**, forever, and every one
is an update to the *same* row for that profile and title — so lock contention on top of write
volume. Meanwhile the data is nearly worthless: losing the last ten seconds of progress is
invisible to a viewer. Durable log, asynchronous fold, player latency decoupled from storage.

---

## D-005 · Carry `deviceId` and `sequence` before they are used · `SETTLED`

Cross-device resume (phase 9) needs both. Adding fields to a contract that player clients already
depend on is expensive; carrying two unused fields is free.

---

## D-006 · `study/` lives outside the git repository · `SETTLED`

A `study/` folder inside the repo signals to a WBD reviewer that the author was learning as he went.
The repository is a work product. Learning material sits beside it, never in it, and is never
committed.

**Corollary:** agent instruction files (`CLAUDE.md`, `AGENTS.md`, `WORKING_AGREEMENT.md`) also live
outside the repo. Engineering documents (`SPEC.md`, `ROADMAP.md`, `ARCHITECTURE.md`, this file,
`BENCHMARKS.md`, `ENGINEERING_LOG.md`) live **inside** it — they make the repo more impressive, not
less, because writing them is what a senior engineer does.

---

## D-007 · Build the cache lab before the write path · ~~SETTLED~~ **SUPERSEDED by D-012**

Originally: phase 1 is the cache lab, not Kafka and Postgres.

**No longer applies.** There is no cache lab — the hand-built cache was cut entirely when the
differentiator changed to surge survival. The one principle worth carrying forward is the reason it
was ordered first: *start with what no infrastructure can block*. That survives as phase 0, which
is plain Java with no Docker and no network.

---

## D-008 · No time component in the roadmap · `SETTLED` *(2026-09-01)*

Phases, not weeks. No dates, no ship-by. Ordering is by interview value and by what cannot be
blocked. A calendar in the roadmap invites cutting corners to hit a date, which is the exact
failure mode this project cannot afford.

---

## D-009 · The repository is public · `SETTLED` *(2026-09-01)*

It is a portfolio artifact; a reviewer must be able to read it without being granted access.

---

## D-010 · Restart from an empty directory · `SETTLED` *(2026-09-01)*

The first attempt produced a Gradle skeleton, an `ingest-api` service and a `cache-lab` module
faster than Abhinav could absorb them. He could not have defended the code, which makes it worse
than useless — it creates false confidence going into an interview.

Everything was deleted and the project restarted from an empty directory under a stricter working
agreement: **one file per turn, explained before it is written, approved before it exists.**

This is the single most important entry in this file. The failure it records is the one most likely
to recur, because moving fast always feels like progress.

---

## D-011 · No `Co-Authored-By` trailer on commits · `SETTLED` *(2026-09-01)*

The repo is public and its purpose is to show a reviewer Abhinav's engineering.

---

## D-012 · The differentiator is surge survival, not hand-built data structures · `SETTLED` *(2026-09-01)*

v1 of the spec differentiated on hand-written W-TinyLFU, Count-Min Sketch and HyperLogLog. That is
now **rejected**, for two reasons Abhinav identified himself:

1. **It invites the wrong interview.** An algorithm-heavy project makes an interviewer ask him to
   implement algorithms on a whiteboard. He wants the conversation to be about engineering and
   scaling.
2. **It reinforces an existing strength.** His DSA is already strong. Spending half the project
   there buys almost nothing; he cannot currently claim to have built and operated a distributed
   system, and that is what the effort should buy.

The system is unchanged. The differentiator is now **surviving a tentpole event** — pre-scaling
from an event schedule, priority-tiered admission control, and a measured before/after under a
premiere-shaped load curve.

**Use Caffeine for caching.** "I used the library and here is why" is the senior answer;
reimplementing it proves nothing about engineering judgment.

*Evidence this is the right target:* WBD's own engineering blog states that for premieres,
"pre-scaling the platform was essential as autoscaling couldn't always keep up with the
acceleration in RPS."

---

## D-013 · Java, not Go or Node · `SETTLED` *(2026-09-01)*

**Java 25 + Spring Boot**, despite Abhinav having zero Java experience.

- **Recognition.** WBD postings list Java; the project only works as an interview-steering device
  if the interviewer engages with it rather than skipping past an unfamiliar language.
- **Ecosystem.** Kafka, Flink and Spark are JVM-native. Their Principal role asks for Kafka+Flink.
- **Netflix is a JVM shop** (Hystrix, Zuul, Eureka, Spring Cloud Netflix) and is acquiring Warner
  Bros. including HBO Max — see D-014.
- **Transferability.** Java is the highest-value backend language in the Indian product market.
- **The Java questions asked in Indian campus interviews are disguised DSA and OS questions**
  (HashMap internals, GC, concurrency). Java turns the language round into a home game.

*Rejected:* **Go** — genuinely better suited to the workload and far easier to learn, with a much
smaller memory footprint on a 12 GB VM, but a weaker keyword match and no Flink/Spark. **Node** —
he already knows it, so it demonstrates no growth, and the event-loop model makes the throughput
story harder to defend.

**The real risk is Spring's magic, not Java the language.** Mitigation: keep Spring's surface
deliberately thin, teach every annotation at the moment it appears, and bank the Spring questions
in `study/qbank/01-spring.md`.

---

## D-014 · Optimise for streaming reliability, not for one employer · `SETTLED` *(2026-09-01)*

Netflix announced (5 Dec 2025) it is acquiring Warner Bros. — including HBO Max and HBO — for
~$82.7B enterprise value, closing 12–18 months after the Discovery Global separation slated for
Q3 2026, subject to regulatory approval. WBD itself splits into Streaming & Studios and Discovery
Global.

The Hyderabad centre (~1,500 engineers, targeting 2,500 by 2027) works on the Max streaming
platform, recommendation AI and ad-tech analytics — so that org is on a path into Netflix.

**Consequence for the project:** target *large-scale streaming reliability*, which is the
intersection of WBD, Netflix, JioHotstar, Disney+ and Amazon — not WBD-specific trivia. Hiring
freezes during acquisitions are a real risk entirely outside Abhinav's control; the project should
be valuable regardless of which entity is hiring.

---

## D-015 · The build order is also the syllabus · `SETTLED` *(2026-09-01)*

Abhinav knows no Java, Spring, Kafka, Redis, Postgres internals, Docker or Kubernetes. Rather than
studying separately, **each phase introduces exactly one technology from zero, builds one part of
the system with it, and banks that technology's most-asked interview questions.**

Every phase gate includes a comprehension condition: the phase's `study/qbank/` file exists and he
can answer five of its questions aloud, without notes. Building something he cannot explain is a
failed phase, not a passed one. Full design in `study/LEARNING_PLAN.md`.

---

## Provisional — agreed direction, re-derived with Abhinav during the build

These are design intentions, not yet built. They exist so an agent knows the destination. **Teach
them when the code is written; do not simply implement them silently.**

| ID | Direction | Why |
|---|---|---|
| ~~P-001..P-004~~ | *Retired by D-012* — these concerned the hand-built cache, which is no longer being built. | |
| **P-008** | Priority tiers: playback write > resume read > browse | Under overload the system must protect the write that loses a viewer's place, and shed the request they will simply retry. |
| **P-009** | Pre-scale lead time derived from measured pod start time, not guessed | The whole thesis is that reactive scaling is too slow; the lead time must come from measurement or the argument is circular. |
| **P-005** | Kafka topic partitioned by `profileId` | Keeps one profile's events ordered on a single partition, which is what makes the fold correct. |
| **P-006** | Postgres events table is partitioned, with a covering index for continue-watching | The table grows without bound; continue-watching is the only latency-critical query against it. |
| **P-007** | No build tool until JUnit forces one | `java Foo.java` runs a single file since Java 11. Gradle should arrive when a downloaded dependency makes it necessary, so its purpose is understood rather than inherited. |
