/*
 * SILKROUTE Phase 2 — ESB order-mediation load profile (constraint C3).
 *
 * Constant-arrival-rate happy-path POSTs to the ESB (POST /api/v1/orders):
 * 10 s ramp-up, then 60 s at 20 req/s (env-tunable: RPS, DURATION).
 *
 * C3 discipline: MEASURE, never assert a passing number. There are deliberately
 * NO thresholds in this script — nothing aborts, the real p95 is recorded into
 * the JSON summary (k6 --summary-export, see the Makefile `load-orders` target)
 * and the orchestrator copies it into evidence/ together with the hardware
 * context. Stock safety: iterations round-robin over 150 sku/store combos
 * (SKU-0001..0050 x ST-SG-01..03), so a 70 s run at 20 RPS puts ~9 orders on
 * each seeded stock row and cannot exhaust the estate.
 *
 * Run (ERP 18080 + toxiproxy 18180 + ESB 18081 must be up):
 *   k6 run tests/load/k6-orders.js --summary-export=tests/load/results/k6-orders-summary.json
 *   RPS=50 DURATION=120s k6 run ...
 */
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

const RPS = parseInt(__ENV.RPS || '20', 10);
const HOLD = __ENV.DURATION || '60s';
const BASE = (__ENV.ESB_BASEURL || 'http://127.0.0.1:18081').replace(/\/$/, '');

// 50 SKUs x 3 SG stores; per-iteration index keeps every stock row in budget.
const COMBOS = 150;

export const options = {
  scenarios: {
    orders: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: 20,
      maxVUs: 200,
      stages: [
        { target: RPS, duration: '10s' }, // ramp-up
        { target: RPS, duration: HOLD },  // hold
      ],
    },
  },
  // No thresholds on purpose: the suite records the measured p95 (C3) instead
  // of passing/failing against one.
  discardResponseBodies: true,
};

export default function () {
  const it = exec.scenario.iterationInTest;
  const idx = it % COMBOS;
  const sku = 'SKU-' + String((idx % 50) + 1).padStart(4, '0');
  const store = 'ST-SG-0' + (Math.floor(idx / 50) + 1);
  const tok = `${exec.vu.idInTest}-${it}-${Date.now()}`;
  const correlationId = `load-corr-${tok}`;
  const externalOrderRef = `ESB-LOAD-${tok}`;

  const payload = JSON.stringify({
    externalOrderRef,
    sourceSystem: 'ESB-INT-TEST',
    storeId: store,
    channel: 'WEB_STORE',
    customerRef: 'cust-load',
    lines: [{ skuId: sku, quantity: 1 }],
    audit: {
      sourceSystem: 'ESB-INT-TEST',
      receivedAt: new Date().toISOString(),
      correlationId,
    },
  });

  const res = http.post(`${BASE}/api/v1/orders`, payload, {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': correlationId },
    timeout: '30s',
  });
  // Visibility only — a failed check never aborts the run.
  check(res, {
    'status is 201': (r) => r.status === 201,
  });
}

// NOTE: no handleSummary on purpose — k6's --summary-export (see the Makefile
// `load-orders` target) writes the complete metric values (p(95) included)
// only when no custom summary handler is defined. The end-of-test summary k6
// prints already shows the measured p95; the exported JSON is the evidence
// artifact the orchestrator copies into evidence/ with hardware context.

