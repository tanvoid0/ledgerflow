import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// Closed model, one hot wallet: fifty VUs hammer a wallet holding exactly 100.00
// with 80.00 transfers. Correct behaviour is one 201 and forty-nine 422s.
const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const created = new Counter('transfers_created');

export const options = {
  scenarios: { race: { executor: 'constant-vus', vus: 50, duration: '5s' } },
};

const headers = { 'Content-Type': 'application/json' };
if (__ENV.TOKEN) headers['Authorization'] = `Bearer ${__ENV.TOKEN}`;

export default function () {
  const res = http.post(`${BASE}/api/v1/transfers`, JSON.stringify({
      fromWalletId: __ENV.FROM, toWalletId: __ENV.TO, amountMinor: 8000, currency: 'GBP', description: 'race' }),
    { headers: { ...headers, 'Idempotency-Key': `race-${__VU}-${__ITER}-${Date.now()}` } });
  if (res.status === 201) created.add(1);
  check(res, { '201 or 422': r => r.status === 201 || r.status === 422 });
}

export function handleSummary(data) {
  const n = data.metrics.transfers_created ? data.metrics.transfers_created.values.count : 0;
  return { stdout: `\ntransfers created against a 100.00 wallet with 80.00 transfers: ${n} (correct answer: 1)\n` };
}
