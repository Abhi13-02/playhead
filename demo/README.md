# Demo harness

Everything in this folder exists to **show** the system's behaviour. None of it is part of the
system. Nothing under `src/` imports it, nothing here runs in production, and deleting this folder
leaves the service exactly as it was.

That separation is the point: the service is the work product, and this is the thing that points a
camera at it.

```
demo/
  server.js              the control panel's backend (Node, no dependencies)
  public/index.html      the control panel itself
  monitoring/
    prometheus.yml       what Prometheus scrapes
    grafana/provisioning/  the datasource and the dashboard, auto-loaded
```

## Running it

```bash
docker compose up -d        # service + Prometheus + Grafana
node demo/server.js         # the control panel
```

Then open **http://localhost:4000**.

| | |
|---|---|
| Control panel | http://localhost:4000 |
| Grafana | http://localhost:3000 (anonymous viewing enabled) |
| Prometheus | http://localhost:9090 |

## What it does

**Traffic sliders.** Three of them, one per priority tier, adjustable live in requests/sec. The
graphs respond within a second or two.

**Chaos buttons.** Kill or restart Redis, PostgreSQL, or Kafka while traffic is flowing — the
phase-6 drills, without a second terminal. Only those three containers can be touched, and only
`kill` and `start`: the panel picks the container name from a fixed map rather than passing anything
from the browser to a shell.

**Live counters.** Sent, OK, `429` shed, failed — per second, so the effect of a slider or a kill is
visible immediately, before the Grafana graphs catch up on their 5-second scrape.

## Three things worth demonstrating

1. **Priority shedding.** Push the surge preset. The `429` count climbs, and the shed-rate panel
   shows it landing on the read tiers, not on playback writes.
2. **Degraded reads.** Kill Redis while traffic runs. Requests keep succeeding — the circuit breaker
   opens and reads fall back to Postgres — and read latency steps up rather than collapsing.
3. **Buffered writes.** Kill Postgres. Writes still return `202`, because ingest only touches Kafka.
   Consumer lag climbs on the dashboard, then drains once Postgres is back.

## Why traffic is generated here and not with k6

k6 generates far more load and is the right tool for a benchmark — every number in
[BENCHMARKS.md](../docs/BENCHMARKS.md) came from it. But a k6 run's arrival rate is fixed for the
duration: changing it means stopping and starting a new run, which is exactly what a live demo
cannot do.

So this panel drives requests itself, from Node, on a rate that can change mid-flight. It reaches a
few thousand requests/sec, which is enough to show behaviour but well short of the service's real
ceiling. **Use k6 for numbers, use this for showing.**

## The Redis panel

One panel, **Redis hit rate**, sitting directly under the Caffeine one so the two cache tiers can be
read against each other. Redis only sees the reads Caffeine missed, so the two rates are genuinely
different numbers rather than the same thing measured twice.

It is fed by `redis-exporter` (in `docker-compose.yml`), scraped as its own Prometheus job — Redis's
own view of itself, rather than the app's. The app does emit Lettuce client metrics, but they were
not trustworthy enough to plot: `lettuce_active_seconds` reported 4 GETs totalling 9,804 seconds.

Measured during a kill and restart at 1,620 req/s: **0 failed requests**, and read latency stepping
up rather than the service failing.
