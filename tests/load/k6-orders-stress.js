/*
 * SILKROUTE Phase 6 (S7) — ESB order-mediation STEPPED STRESS profile (constraint C3).
 *
 * Sibling of tests/load/k6-orders.js (the Phase-2 constant-rate profile, evidence
 * row E-010 — do not modify that file; this one reuses its payload conventions and
 * deliberately diverges in purpose): k6-orders.js re-proves comfort at a fixed
 * 20 RPS, THIS profile exists to find where the C3 budget actually BREAKS —
 * the saturation point — by stepping the arrival rate UP until latency or
 * infra integrity degrades.
 *
 * Shape (env-tunable):
 *   STEPS="10,20,35,50,70,90"  STEP_DURATION=45 (seconds per step)
 *   STEP_RAMP=15 (seconds of ramp inside each step; the remaining
 *   STEP_DURATION-STEP_RAMP=30s is a HOLD at the step target, so each step has a
 *   measurable steady state instead of one continuous ramp)  START_RATE=1
 *   PREALLOCATED_VUS=50  MAX_VUS=600
 * Each step is its OWN k6 scenario (executor ramping-arrival-rate, sequenced via
 * startTime, no gaps). Two reasons:
 *   1. ramping-arrival-rate semantics: step 1 ramps startRate(1) -> 10, later
 *      steps ramp from the PREVIOUS step's target to their own target — the
 *      "ramp within steps" — then hold at the target for the rest of the step.
 *   2. every k6 metric is tagged `scenario:<name>`; per-step 201-only
 *      percentiles are surfaced via REPORTING thresholds on those tagged
 *      sub-metrics (see options.thresholds) because --summary-export carries
 *      AGGREGATE trend stats only — verified live during the S7 integration
 *      run (the original assumption that the export emits per-tag sub-metrics
 *      was wrong; the thresholds mechanism is the k6-blessed way to export
 *      them).
 *
 * BREAKING-POINT DEFINITION (evaluated per step from the summary export):
 *   The saturation point is the FIRST step where EITHER
 *     (a) infra failures exceed 1% of that step's requests — infra failures are
 *         everything that is NOT a business rejection: HTTP 5xx, timeouts,
 *         connection resets / network errors (status 0) — OR
 *     (b) p95 of the 201-ONLY duration trend (order_req_duration_201) for that
 *         step exceeds the 300 ms C3 budget.
 *   Business rejections (422 INV-OUT-OF-STOCK stock exhaustion, 422/409 dup-ref
 *   paths) are FAST rejections that inflate raw throughput and MUST NOT be
 *   counted as saturation — hence metric (c) below counts them separately and
 *   the 201-only trend (b) keeps the latency percentile honest under heavy 422
 *   rates (ERP seeds stock 5..100/row, so at 70-90 RPS most rows legitimately
 *   exhaust mid-run; that is expected here, not a failure).
 *
 * Metrics discipline (the key design point) — three separate records:
 *   order_req_duration_all    Trend of ALL request durations (raw view)
 *   order_req_duration_201    Trend of ONLY 201-response durations (C3 view)
 *   order_status_201/422/409/503/5xx/other + order_network_errors
 *                             Counters per status class (503 is also included
 *                             in the 5xx counter, so infra% = (5xx + network) / total)
 * Threshold discipline: the options.thresholds block holds REPORTING-only
 * thresholds at the 300 ms C3 boundary (no abortOnFail — nothing aborts; exit
 * 99 means some step breached). k6-orders.js itself keeps zero thresholds.
 *
 * Run (ERP 18080 + toxiproxy 18180 + ESB 18081 must be up; k6 is NOT on PATH):
 *   /home/potato/tools/k6/k6 run tests/load/k6-orders-stress.js \
 *     --summary-export=tests/load/results/k6-orders-stress-summary.json
 *   STEPS=10,20,35,50,70,90,120 STEP_DURATION=60 MAX_VUS=800 /home/potato/tools/k6/k6 run ...
 */
import http from 'k6/http';
import { Trend, Counter } from 'k6/metrics';
import exec from 'k6/execution';

const STEPS = (__ENV.STEPS || '10,20,35,50,70,90')
  .split(',')
  .map((s) => parseInt(s.trim(), 10))
  .filter((n) => !isNaN(n) && n > 0);
const STEP_DURATION = parseInt(__ENV.STEP_DURATION || '45', 10); // seconds per step
const STEP_RAMP = Math.min(parseInt(__ENV.STEP_RAMP || '15', 10), STEP_DURATION);
const START_RATE = parseInt(__ENV.START_RATE || '1', 10); // small, per k6-orders.js convention
const PREALLOCATED_VUS = parseInt(__ENV.PREALLOCATED_VUS || '50', 10);
const MAX_VUS = parseInt(__ENV.MAX_VUS || '600', 10);
const BASE = (__ENV.ESB_BASEURL || 'http://127.0.0.1:18081').replace(/\/$/, '');

// 50 SKUs x 3 SG stores — SG stores ONLY, deliberately: the C3 mediation path
// under stress is the SG flow (same combo set as k6-orders.js).
const COMBOS = 150;

function stepScenario(rps, i, startTimeS) {
  return {
    executor: 'ramping-arrival-rate',
    startRate: i === 0 ? START_RATE : STEPS[i - 1], // continuity: ramp from previous plateau
    timeUnit: '1s',
    startTime: `${startTimeS}s`,
    stages: [
      { target: rps, duration: `${STEP_RAMP}s` }, // ramp within the step
      { target: rps, duration: `${STEP_DURATION - STEP_RAMP}s` }, // hold at the step target
    ],
    preAllocatedVUs: PREALLOCATED_VUS,
    maxVUs: MAX_VUS,
    exec: 'orderPost',
  };
}

const scenarios = {};
let t = 0;
STEPS.forEach((rps, i) => {
  scenarios[`step${i + 1}_r${rps}`] = stepScenario(rps, i, t);
  t += STEP_DURATION;
});

export const options = {
  scenarios,
  // Reporting thresholds at the C3 boundary (evolved during the first S7
  // integration run): k6's --summary-export carries AGGREGATE trend stats only
  // (verified live — the original header's per-tag-sub-metrics assumption was
  // wrong), so per-step 201-only percentiles are made machine-readable via
  // thresholds on the scenario-tagged sub-metric. They are REPORTING
  // instruments, not assertions: no abortOnFail (a breach never aborts the
  // run), each step's p(95)/p(99) against the 300 ms budget lands in the
  // export under metrics[].thresholds, and exit code 99 = "some step breached
  // the budget" — the falsifiable breaking-point signal. The breaking point
  // itself is still ANALYZED per the rule above, never asserted-pass here.
  thresholds: Object.fromEntries(
    STEPS.map((rps, i) => [
      `order_req_duration_201{scenario:step${i + 1}_r${rps}}`,
      ['p(95)<300', 'p(99)<300'],
    ])
  ),
  discardResponseBodies: true, // keep the generator light at 90+ RPS
};

// --- metrics discipline: (a) all durations, (b) 201-only durations, (c) counters
const durAll = new Trend('order_req_duration_all', true);
const dur201 = new Trend('order_req_duration_201', true);
const c201 = new Counter('order_status_201');
const c422 = new Counter('order_status_422');
const c409 = new Counter('order_status_409');
const c503 = new Counter('order_status_503'); // CIRCUIT-OPEN / SAGA-COMPENSATED surface here
const c5xx = new Counter('order_status_5xx'); // ALL 5xx including 503 (infra bucket)
const cOther = new Counter('order_status_other'); // anything else (400/415/... client misuse)
const cNet = new Counter('order_network_errors'); // status 0: timeout / reset / refused

export function orderPost() {
  // Per-SCENARIO iteration index (iterationInTest resets per scenario): each
  // step cycles all 150 combos, spreading stock pressure evenly within a step.
  const it = exec.scenario.iterationInTest;
  const idx = it % COMBOS;
  const sku = 'SKU-' + String((idx % 50) + 1).padStart(4, '0');
  const store = 'ST-SG-0' + (Math.floor(idx / 50) + 1);
  const tok = `${exec.vu.idInTest}-${it}-${Date.now()}`;
  const correlationId = `stress-corr-${tok}`;
  const externalOrderRef = `ESB-STRESS-${tok}`;

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

  // (a) EVERY request lands in the raw trend ...
  durAll.add(res.timings.duration);

  if (res.status === 201) {
    dur201.add(res.timings.duration); // (b) 201-only trend — the honest C3 percentile
    c201.add(1);
  } else if (res.status === 422) {
    c422.add(1); // business rejection (stock exhausted / dup-ref guard) — NEVER saturation
  } else if (res.status === 409) {
    c409.add(1); // idempotent replay semantics
  } else if (res.status === 503) {
    c503.add(1); // CIRCUIT-OPEN / SAGA-COMPENSATED
    c5xx.add(1);
  } else if (res.status >= 500) {
    c5xx.add(1); // other 5xx — infra
  } else if (res.status === 0) {
    cNet.add(1); // timeout / connection reset / refused — infra
  } else {
    cOther.add(1);
  }
}

// Convenience default so `k6 run` without exec wiring still works.
export default function () {
  orderPost();
}

// NOTE: no handleSummary on purpose — same reasoning as k6-orders.js: k6's
// --summary-export writes the complete metric values (p(95) per scenario tag
// included) only when no custom summary handler is defined. That JSON is the
// evidence artifact the orchestrator copies into evidence/ with hardware context.
