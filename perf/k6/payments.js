import http from 'k6/http';
import { check } from 'k6';

// The whole authorisation path. The POST answers 202 in a few milliseconds; the payment is done
// seconds later, three services and seven broker hops away. k6 measures the POST. perf/settled.sh
// reads the rest from the saga table afterwards and adds it to this run's summary as payment_settled.
const RATE = Number(__ENV.RATE || 200);
const DURATION = __ENV.DURATION || '180s';          // seconds only: settled.sh and setup() parse it
const BASE = __ENV.BASE_URL || 'http://localhost:8085';
const ACCOUNT_URL = __ENV.ACCOUNT_URL || 'http://localhost:8080';
const ACCOUNT = '11111111-1111-1111-1111-111111111111';
const AMOUNT = Number(__ENV.AMOUNT || 100);         // 1.00 per payment
const WALLETS = Array.from({ length: 20 }, (_, i) => `A-${i + 1}`);

export const options = {
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    warmup: { executor: 'constant-arrival-rate', exec: 'pay', rate: 20, timeUnit: '1s', duration: '30s',
              preAllocatedVUs: 20, maxVUs: 100 },
    main:   { executor: 'constant-arrival-rate', exec: 'pay', startTime: '30s', rate: RATE, timeUnit: '1s',
              duration: DURATION, preAllocatedVUs: 100, maxVUs: 1000 },
  },
  thresholds: {
    'http_req_failed{scenario:main}': ['rate<0.01'],
    'http_req_duration{scenario:main}': [`p(99)<${__ENV.P99_MS || 1000}`],
  },
};

const headers = { 'Content-Type': 'application/json' };
if (__ENV.TOKEN) headers['Authorization'] = `Bearer ${__ENV.TOKEN}`;

// Every capture debits its wallet, so a long run would start failing on funds halfway through and
// measure the failure path instead. Fund the run up front from treasury, the one wallet allowed below zero.
export function setup() {
  const wallets = http.get(`${ACCOUNT_URL}/api/v1/accounts/${ACCOUNT}`, { headers }).json('wallets');
  const treasury = wallets.find(w => w.label === 'TREASURY').id;
  const payments = 20 * 30 + RATE * parseInt(DURATION);
  const perWallet = Math.ceil(payments * AMOUNT / WALLETS.length * 1.1);
  const run = Date.now();
  for (const w of wallets.filter(w => w.label !== 'TREASURY')) {
    const res = http.post(`${ACCOUNT_URL}/api/v1/transfers`, JSON.stringify({
        fromWalletId: treasury, toWalletId: w.id, amountMinor: perWallet, currency: 'GBP', description: 'k6 funding' }),
      { headers: { ...headers, 'Idempotency-Key': `k6-fund-${run}-${w.label}` } });
    if (res.status !== 201) throw new Error(`funding ${w.label} failed: ${res.status} ${res.body}`);
  }
}

export function pay() {
  const res = http.post(`${BASE}/api/v1/payments`, JSON.stringify({
      accountId: ACCOUNT, wallets: [WALLETS[Math.floor(Math.random() * WALLETS.length)]],
      amountMinor: AMOUNT, currency: 'GBP' }),
    { headers });
  check(res, { 'accepted': r => r.status === 202 });
}

export function handleSummary(data) {
  data.meta = { rate: RATE, duration: DURATION };   // run.sh adds commit, date and host
  return { [__ENV.SUMMARY || 'summary.json']: JSON.stringify(data, null, 1) };
}
