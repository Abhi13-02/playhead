# Project state

**The single source of truth for where construction has reached.** Read this immediately after
[SPEC.md](SPEC.md) and [ROADMAP.md](ROADMAP.md). Updated at the end of every chunk, without
exception — this file is what lets a new session or a different agent continue.

**Last updated:** 2026-09-01
**Current phase:** 0 — Core Java and the domain model
**Progress in phase:** 0 of 8 items
**Repository:** `docs/` only. No code. No commits.

---

## Next step

**The first Java file.** A single `.java` file at the top level of `playhead/`, runnable directly
with `java Foo.java` — no Gradle, no Spring, no build configuration. Gradle appears only when
JUnit forces it (DECISIONS.md P-007).

Proposed and awaiting Abhinav's go-ahead. Nothing else gets created first — not a `.gitignore`,
not a `build.gradle`, not a directory tree. One file, agreed, then built.

---

## The shape of the project, in three lines

A **playback-state backend for OTT streaming that survives a tentpole event.** Heartbeats land in
Kafka, fold asynchronously into Postgres and Redis, and are served through a cached read path.
When a premiere takes traffic from idle to peak in 60 seconds, capacity is pre-scaled from an
event schedule and admission control sheds by priority.

**The headline result is an A/B pair:** the same 60-second load ramp, once without the guard layer
and once with it. That result lands in **phase 2** — before Kafka, Postgres, Redis or Kubernetes
exist — so there is something distinctive to show from very early on.

---

## What exists right now

| Path | What it is |
|---|---|
| `playhead/.git` | Fresh repo, branch `main`, **zero commits**, no remote |
| `playhead/docs/SPEC.md` | v2 — requirements, the bar, the end state, the demo |
| `playhead/docs/ROADMAP.md` | Nine phases (0–8), each one technology, each with a gate |
| `playhead/docs/DECISIONS.md` | 15 decisions; D-012 and D-013 are the load-bearing recent ones |
| `playhead/docs/PROJECT_STATE.md` | This file |
| `study/WORKING_AGREEMENT.md` | How an agent must work with Abhinav. Binding. |
| `study/LEARNING_PLAN.md` | Build order = syllabus order. ~110 banked interview questions. |
| `study/CV_POINTS.md` | Three CV bullets, earned incrementally from phase 2 |
| `study/notes/01-*.md`, `02-*.md` | **Stale** — from the deleted first attempt |
| `study/interview-prep.md` | Superseded by `LEARNING_PLAN.md` + the coming `qbank/` |
| `study/cheatsheet.md` | Java ↔ Python/Node. Still valid. |
| `study/confusions.md` | Sticking points |

**No source code exists.** Deliberate — see D-010.

---

## How we got here

Two full restarts on 2026-09-01, both correct:

1. **Restart one (D-010).** A first attempt produced a Gradle skeleton, a Spring Boot service and
   a hand-built cache module faster than Abhinav could absorb them, then committed and pushed it
   public. Deleted in full. The working agreement tightened to one file per turn, explained and
   approved before it exists.

2. **Thesis change (D-012).** The spec had differentiated on hand-built data structures
   (W-TinyLFU, Count-Min, HyperLogLog). Abhinav identified that this invites algorithm questions
   instead of engineering ones, and reinforces a strength he already has. The system stayed; the
   differentiator became **surge survival**. Roughly half the planned work was cut.

Research during the second change also established D-014: **Netflix is acquiring Warner Bros.,
including HBO Max**, closing 12–18 months after the Q3 2026 Discovery Global separation. The
project now targets *large-scale streaming reliability* generally rather than WBD-specific trivia.

---

## Standing constraints

- **Three unit tests in the whole project**, all in phase 0, all on the fold function. Everything
  else is verified under load or by chaos drill. This is a position, not a gap — see ROADMAP.
- **Three CV bullets, not five.**
- **Cut anything expensive that is not strictly necessary.** The roadmap's "Explicitly cut" table
  records what was dropped and why; do not re-add.
- **Kubernetes is optional.** Compose scaling tells the same story.

---

## Known gaps

- **The old public GitHub repo still exists** at `github.com/Abhi13-02/playhead` and must be
  deleted by Abhinav in a browser — the stored credential lacks `delete_repo`. Settings → Danger
  Zone. A fresh remote is created when there is something worth pushing.
- **`study/notes/01` and `02` are stale**, and `interview-prep.md` is superseded by
  `LEARNING_PLAN.md`. Delete or rewrite when phase 0 produces its first real note.
- **Every number in SPEC §5 is a target, not a measurement.** Revise once real numbers exist and
  record the revision in DECISIONS.md.
- **`BENCHMARKS.md` does not exist yet** — phase 2 creates it. `ENGINEERING_LOG.md` — phase 6.
  Do not create them empty.
- **The 12 GB VM envelope is untested.** Kafka + Postgres + Redis + services + Prometheus is
  tight. If it does not fit, cut Prometheus before cutting anything in phases 0–7.

---

## Chunk log

Newest first.

| Date | Phase | What was done |
|---|---|---|
| 2026-09-01 | — | Thesis changed to surge survival; SPEC/ROADMAP/CV_POINTS rewritten; LEARNING_PLAN added; testing cut to 3 tests; CV cut to 3 bullets |
| 2026-09-01 | — | Documentation system created (SPEC, ROADMAP, DECISIONS, WORKING_AGREEMENT, CLAUDE/AGENTS) |
| 2026-09-01 | — | **Full restart** — first attempt deleted, `playhead/` emptied and re-initialised |
