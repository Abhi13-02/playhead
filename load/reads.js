import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

// The read path under the same curve shape as premiere.js, so read latency is comparable to the
// write run. Key space matches premiere.js: seed by running that first, then run this.
const PEAK = Number(__ENV.PEAK || 2000);

// Per-endpoint latency, kept apart deliberately. resume is a point lookup through Caffeine ->
// Redis -> Postgres; continue-watching is an indexed list through Caffeine -> Postgres. Blending
// them into one percentile would hide which tier is actually slow.
const resumeDuration = new Trend('resume_duration', true);
const continueWatchingDuration = new Trend('continue_watching_duration', true);

export const options = {
  scenarios: {
    reads: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: 800,
      maxVUs: 6000,
      stages: [
        { duration: '15s', target: 50 },
        { duration: '60s', target: PEAK },
        { duration: '30s', target: PEAK },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    // NFR-3: read p99 < 50 ms. Asserted per endpoint so a regression in one is not averaged away.
    resume_duration: ['p(99)<50'],
    continue_watching_duration: ['p(99)<50'],
  },
};

const PROFILES = Array.from({ length: 500 }, (_, i) => `p-${i}`);
const TITLES = Array.from({ length: 50 }, (_, i) => `t-${i}`);
const pick = (a) => a[Math.floor(Math.random() * a.length)];

export default function () {
  const profileId = pick(PROFILES);
  const params = { headers: { 'X-Profile-Id': profileId } };

  // 70/30: a viewer hits resume on every playback start, continue-watching once per app open.
  if (Math.random() < 0.7) {
    const res = http.get(
      `http://localhost:8080/v1/playback/resume/${pick(TITLES)}`,
      params,
    );
    resumeDuration.add(res.timings.duration);
    check(res, {
      'resume 200 (found)': (r) => r.status === 200,
      'resume 404 (never watched)': (r) => r.status === 404,
      'resume error': (r) => r.status !== 200 && r.status !== 404,
    });
  } else {
    const res = http.get(
      'http://localhost:8080/v1/playback/continue-watching',
      params,
    );
    continueWatchingDuration.add(res.timings.duration);
    check(res, {
      'continue-watching 200': (r) => r.status === 200,
      'continue-watching error': (r) => r.status !== 200,
    });
  }
}
