# SILKROUTE — k6 load profiles (C3 evidence instruments)

Both profiles POST happy-path orders at the ESB (`POST /api/v1/orders`,
ERP 18080 + toxiproxy `erp` 18180 + ESB 18081 must be up — `make esb-run`).
k6 is NOT on PATH: `/home/potato/tools/k6/k6` (override with `K6_BIN=`).
Neither profile defines thresholds (C3 discipline: MEASURE, never assert-pass);
`--summary-export` writes the evidence JSON into `tests/load/results/`, which the
orchestrator copies into `evidence/` with hardware context. No Makefile targets
are required — raw commands below are the interface.

| profile | purpose | shape |
|---|---|---|
| `k6-orders.js` | Phase-2 comfort proof at fixed rate (evidence E-010 — **do not modify**, proven artifact) | ramping-arrival-rate: 10 s ramp-up, then 60 s at `RPS` (default 20); `DURATION` env |
| `k6-orders-stress.js` | S7 stepped stress: find where the C3 300 ms budget BREAKS (saturation point) | per-step `ramping-arrival-rate` scenarios, sequenced via `startTime`: 10→20→35→50→70→90 RPS, 45 s per step (15 s ramp + 30 s hold), startRate 1, later steps ramp from the previous plateau |

Run:

```bash
/home/potato/tools/k6/k6 run tests/load/k6-orders.js \
  --summary-export=tests/load/results/k6-orders-summary.json

/home/potato/tools/k6/k6 run tests/load/k6-orders-stress.js \
  --summary-export=tests/load/results/k6-orders-stress-summary.json
```

Env knobs (`k6-orders-stress.js`): `STEPS` (comma list of plateau RPS values,
default `10,20,35,50,70,90`), `STEP_DURATION` (seconds per step, default 45),
`STEP_RAMP` (ramp seconds inside each step, default 15; remainder is a hold at
the step target), `START_RATE` (default 1), `PREALLOCATED_VUS` (default 50),
`MAX_VUS` (default 600), `ESB_BASEURL`. Each step is its own scenario, so every
metric in the summary export carries a `scenario:stepN_r<RPS>` tag — per-step
p95 and counters are read straight from the export.

Stress metrics discipline: `order_req_duration_all` (all requests) ·
`order_req_duration_201` (201-only — the honest C3 percentile) · counters
`order_status_201/422/409/503/5xx/other` + `order_network_errors`.
Breaking-point rule (per step): first step where infra failures (non-422/409:
5xx, timeouts, resets — `(5xx + network)/total`) exceed 1% OR the step's p95 of
201-only durations exceeds 300 ms. Business rejections (422 stock exhaustion on
the seeded 5..100-unit rows, 422/409 dup paths) are fast rejections: they inflate
raw throughput and are never counted as saturation. Payload conventions are the
same as `k6-orders.js` (150 SG combos round-robin, unique externalOrderRef +
matching Idempotency-Key per iteration) — SG stores only, deliberately: the C3
mediation path is the SG flow.

The chaos recipes reuse `k6-orders.js` as their sustained background load — see
`tests/chaos/README.md`.
