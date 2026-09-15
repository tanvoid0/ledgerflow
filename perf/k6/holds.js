import http from 'k6/http';
import { check } from 'k6';

// The cross-service path: ledger asks account before writing. Open model.
const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '30s';
const BASE = __ENV.BASE_URL || 'http://localhost:8081';

export const options = {
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    main: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION,
            preAllocatedVUs: 50, maxVUs: 1000 },     // maxVUs high on purpose: a hang must not starve the generator
  },
  thresholds: { http_req_failed: ['rate<0.05'] },
};

const headers = { 'Content-Type': 'application/json' };
if (__ENV.TOKEN) headers['Authorization'] = `Bearer ${__ENV.TOKEN}`;

export default function () {
  const res = http.post(`${BASE}/api/v1/holds`, JSON.stringify({
      accountId: '11111111-1111-1111-1111-111111111111', wallets: ['A-12'], amountMinor: 100, currency: 'GBP' }),
    { headers, timeout: '60s' });
  check(res, { '2xx': r => r.status >= 200 && r.status < 300 });
}

export function handleSummary(data) {
  data.meta = { rate: RATE, duration: DURATION };   // run.sh adds commit, date and host
  return { [__ENV.SUMMARY || 'summary.json']: JSON.stringify(data, null, 1) };
}
