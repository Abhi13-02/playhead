import http from 'k6/http';
import { check } from 'k6';

const PEAK = Number(__ENV.PEAK || 4000);

export const options = {
  scenarios: {
    premiere: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: 800,   // connections k6 keeps ready, reused
      maxVUs: 6000,           // hard ceiling if the server slows down
      stages: [
        { duration: '15s', target: 50 },     // calm before
        { duration: '60s', target: PEAK },   // the tentpole ramp: 0 -> peak req/s in 60s
        { duration: '30s', target: PEAK },   // hold at peak
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],     // fewer than 1% failed requests
    http_req_duration: ['p(99)<500'],   // p99 latency under 500ms
  },
};

const PROFILES = Array.from({ length: 500 }, (_, i) => `p-${i}`);
const TITLES = Array.from({ length: 50 }, (_, i) => `t-${i}`);
const pick = (a) => a[Math.floor(Math.random() * a.length)];

export default function () {
  const body = JSON.stringify({
    profileId: pick(PROFILES),
    titleId: pick(TITLES),
    deviceId: 'dev-1',
    positionSeconds: Math.floor(Math.random() * 3600),
    durationSeconds: 3600,
    clientTimestamp: Date.now(),
    sequence: Date.now(),
  });

  const res = http.post('http://localhost:8080/v1/playback/heartbeat', body, {
    headers: { 'Content-Type': 'application/json' },
  });

  check(res, {
    'status is 202': (r) => r.status === 202,
    'status is 429 (shed)': (r) => r.status === 429,
    'status is neither (real error)': (r) => r.status !== 202 && r.status !== 429,
  });
}
