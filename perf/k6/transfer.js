import http from 'k6/http';
import { check } from 'k6';

// Open model: RATE requests per second arrive whatever the server is doing.
// A stall shows up in the percentiles instead of throttling the load.
const RATE = Number(__ENV.RATE || 100);
const DURATION = __ENV.DURATION || '60s';
const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    warmup: { executor: 'constant-arrival-rate', exec: 'transfer', rate: 20, timeUnit: '1s',
              duration: '15s', preAllocatedVUs: 10, maxVUs: 50 },
    main:   { executor: 'constant-arrival-rate', exec: 'transfer', startTime: '15s',
              rate: RATE, timeUnit: '1s', duration: DURATION, preAllocatedVUs: 50, maxVUs: 500 },
  },
  thresholds: {
    'http_req_failed{scenario:main}': ['rate<0.01'],
    'http_req_duration{scenario:main}': [`p(99)<${__ENV.P99_MS || 1000}`],
  },
};

const headers = { 'Content-Type': 'application/json' };
if (__ENV.TOKEN) headers['Authorization'] = `Bearer ${__ENV.TOKEN}`;

export function setup() {
  // every wallet except treasury, so the load spreads across all of them
  const res = http.get(`${BASE}/api/v1/accounts`, { headers });
  const wallets = res.json()[0].wallets.filter(w => w.label !== 'TREASURY').map(w => w.id);
  return { wallets };
}

export function transfer(data) {
  const w = data.wallets;
  const from = w[Math.floor(Math.random() * w.length)];
  let to = w[Math.floor(Math.random() * w.length)];
  if (to === from) to = w[(w.indexOf(from) + 1) % w.length];

  const res = http.post(`${BASE}/api/v1/transfers`, JSON.stringify({
      fromWalletId: from, toWalletId: to, amountMinor: 1, currency: 'GBP', description: 'k6' }),
    { headers: { ...headers, 'Idempotency-Key': `k6-${__VU}-${__ITER}-${Date.now()}` } });

  check(res, { 'created or refused': r => r.status === 201 || r.status === 422 });
}

export function handleSummary(data) {
  data.meta = { rate: RATE, duration: DURATION };   // run.sh adds commit, date and host
  return { [__ENV.SUMMARY || 'summary.json']: JSON.stringify(data, null, 1) };
}
