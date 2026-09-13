# SLO report — SilkRoute SG hub (order-mediation platform)

Maple Retail Group is a **fictional** company; this is a design artifact of a **self-directed reference implementation**. Framing per MASTER_PROMPT §9: self-directed project, never presented as professional delivery experience.

**Honest measurement boundary, stated once and applying to every number below:**

- All runtime measurements are **sim mode** (docker-compose: MySQL/Kafka/Redis/MinIO stand-ins, the real Camel ESB and frozen-ERP SOAP services) on a shared consumer desktop — Intel i5-10400F (6C/12T, 2.9 GHz), 16 GB RAM, rootless Docker, with **other load-producing stacks running on the same host** (Airflow/warehousing stacks are up during these runs; load average was 4.4–4.6 at capture time). Every measured number is contended-host context, not dedicated hardware, and every evidence row says so.
- The AliCloud observability layer (SLS dashboards, `alicloud_sls_alert` alert rules, indexes, `pipeline-metrics` store) is **validated IaC — plan-proven (E-019: `Plan: 87` SG / `118` CN), never applied** (ADR-0002). No SLS query has ever executed against real ingest; the latency *measurements* cited here come from k6 against the sim ESB, not from SLS.
- **No number in this report is asserted without an evidence row.** The "Measured (S7)" column names its row and artifact; rows live in `evidence/EVIDENCE.md` with 1:1 reproduce commands.

---

## SLO-1 — Sync SOAP-mediation latency (C3)

| | |
|---|---|
| **Target** | p95 < **300 ms** for `POST /api/v1/orders` (REST façade → canonical → SOAP saga order→reserve→pricing→confirm, WSS-authenticated ERP calls through the mediation path) sustained at moderate load. |
| **Error budget** | With p95 as the quantifier: **5% of requests may exceed 300 ms** in the sustained window while the SLO holds. Additionally tracked: p95 computed over **201-only** responses, so fast business rejections (422 stock exhaustion) cannot flatter the number. |
| **How measured** | k6 constant-arrival-rate profile (`tests/load/k6-orders.js`, the E-010 lineage, unmodified) at a sustained RPS; `--summary-export` JSON is the artifact; percentiles over all responses AND over 201-only (tag-split). |
| **Where the number comes from** | Measured: evidence row (S7 sustained run). Historical baseline: E-010 (p95 44.25 ms @ 18.63 RPS, 2026-09-11, same sim, same contention caveats). |
| **Measured (S7)** | Pending — filled from the S7 sustained run (E-020) below. |

**Measured (S7):** **E-020 (canonical, 2026-09-13):** p95(all) **131.6 ms** / **p99(all) 236.2 ms** @ 19.49 RPS achieved (20 target), 3 704 iterations / 190 s wall (10 s ramp + 180 s hold), 0 network failures — the 352 non-201s verified 1:1 as 422 INV-OUT-OF-STOCK stock-exhaustion business rejections (log-grep = 352 exactly; E-010 convention). The sustained profile tracks 201-only correctness via the exact 1:1 422 accounting (tag-split percentiles are the stress profile's instrument: its 201-only r20 step measured p95 20.4 ms). Budget consumption: **44% at p95, 79% at p99**. Honest variance note: an identical-shape 20 RPS window earlier the same session measured p95 31.8 ms — a 4× swing from shared-host contention alone (E-010 precedent for the hardware note). Sim mode, k6 local (not CI). → evidence **E-020**.

## SLO-2 — Order-flow availability under dependency failure

| | |
|---|---|
| **Target** | When a dependency (ERP) is degraded or hard-down, every façade request receives a **definitive, bounded** outcome: explicit canonical success/business-rejection/compensation/fail-fast error — never an unbounded hang. On hard-down the breaker fails fast (target < 100 ms, the E-009-proven CIRCUIT-OPEN path). Under sub-timeout degradation the flow **holds** (degraded-but-holding, not cascading failure). |
| **Error budget** | **Zero hangs tolerated.** Any façade response exceeding 30 s is a budget-violating event (hard invariant — a hung request is worse than a fast failure for a sync mediation path). Fail-fast target: p95 of CIRCUIT-OPEN responses < 100 ms. |
| **How measured** | Chaos recipes with a probe loop (`tests/chaos/chaos-run.sh probe-loop`): 10-second curl cap per probe; the analyzer (`analyze`) exits non-zero on any probe > 30 s or on unbounded network failures outside the declared fault window; status/error-code histograms per window (steady → fault → recovery). |
| **Where the number comes from** | Measured: chaos transcripts (S7 runs); design lineage: E-009 (CIRCUIT-OPEN fail-fast 4 ms, DLQ, recovery 201). |
| **Measured (S7)** | Pending — filled from the S7 chaos runs (E-021/E-022/E-023). |

**Measured (S7):** **E-022/E-023/E-024 (canonical, 2026-09-13):** zero hangs across all three recipes (max completed probe 4.1 s under 800 ms injected latency; the 30 s invariant never approached post-fix); kill-erp kill-window: 35/35 probes answered in **1.9 ms** (fail-fast signature); every fault window ended in recovery without operator intervention beyond the heal itself. TWO STRUCTURAL FINDINGS, honestly recorded: (a) H1 was first REFUTED — the default Spring task executor (8 workers, unbounded queue) turned sub-timeout ERP latency into queue-then-30s-timeout (485 async timeouts, k6 p95 pinned at its 30 s cap) while the ERP calls themselves succeeded — the breaker and retry ladder are structurally blind to this failure mode; fixed by explicit bounded pool sizing (core 32/64/200), re-proved bounded. (b) H3 was first REFUTED — the Redis allow-through design was unreachable behind Lettuce's 60 s default command timeout (first allow-through log 61 s into a 40 s outage); fixed with 500 ms client timeouts, re-proved (allow-through at 0.9 s). → evidence **E-022, E-023, E-024**; fixes in `apps/esb/src/main/resources/application.yml` (ESB unit 33/33 post-change).

## SLO-3 — Zero duplicate commits (idempotency)

| | |
|---|---|
| **Target** | **0 duplicate ERP orders** for any `Idempotency-Key`/`externalOrderRef` across retries, client replays, and chaos conditions. Semantics: replay of a completed order → **409 DUPLICATE carrying the original orderId**; retry after ambiguous timeout → the frozen ERP's ORD-DUP-REF guard holds; Redis degraded → allow-through with the ERP guard as backstop (the designed defense-in-depth, `RedisIdempotencyStore`). |
| **Error budget** | **Zero.** Duplicate commits are a data-correctness invariant, not a percentage (money-affecting, C5-adjacent). |
| **How measured** | The chaos probe loop replays a pinned key every 5th probe during EVERY recipe; the analyzer fails the run on any replay returning 201 with a new orderId. Cross-checks: ERP-side duplicate-ref 422s during retry windows (the guard visibly holding), 409-after-recovery (claims resume). |
| **Where the number comes from** | Measured: chaos transcripts (S7); design/behavior lineage: E-008 (409 replay verbatim), E-009 (ORD-DUP-REF under retry, claim release regression). |
| **Measured (S7)** | Pending — filled from the S7 chaos runs. |

**Measured (S7):** **E-022/E-023/E-024 (canonical, 2026-09-13):** 0 duplicate commits across all three recipes — every replayed key surfaced as 409 (status verified; the DUPLICATE code + original-orderId carry are E-008's wire proof — the chaos probe parser records no response bodies), 422 ORD-DUP-REF (E-024, the ERP guard visibly holding DURING the Redis outage — exactly the designed defense-in-depth), or died pre-commit (pre-fix runs; analyzed and counted, never silent). No replay ever produced a second 201 (the analyzer's duplicate-commit check: 0 violations in all three transcripts). Documented consequence: during a Redis outage, done-keys for outage-window completions are not stored (`storeCompleted` degrades silently), so post-heal replays of those orders fall through to the ERP guard — the guard held. → evidence **E-022, E-023, E-024**.

## SLO-4 — DLQ drained / recoverable

| | |
|---|---|
| **Target** | Every saga-exhausted order lands in the DLQ with a **complete, replayable envelope** (correlationId, canonical order payload, failure reason, compensation flag, pseudonymized customerRef per C1); DLQ depth is visible (dashboard panel, IaC) and alerted (>100/10 min, IaC); after a dependency heals the flow returns to normal WITHOUT operator data-surgery. |
| **Error budget** | Zero lost messages: any exhausted order NOT in the DLQ with a replayable envelope is a violation. ("Drained": redrive is by-construction from the envelope — an automated redrive pipeline is a designed follow-up, not a built one; the sim demo proves envelope completeness, honestly labeled.) |
| **How measured** | kill-erp chaos recipe: DLQ message count delta across the fault window (kafka-console-consumer before/after), plus envelope field inspection on captured DLQ messages. |
| **Where the number comes from** | Measured: chaos transcript (S7); design lineage: E-009 (DLQ carrying exhausted correlationIds + compensated:true + msk-* pseudonymization). |
| **Measured (S7)** | Pending — filled from the S7 kill-erp run (E-022). |

**Measured (S7):** **E-023 (canonical, 2026-09-13):** DLQ 0 → **59** during the hard-down window — every exhausted order landed with a COMPLETE replayable envelope, verified by field inspection: eventType, externalOrderRef, sourceSystem, storeId, region, customerRef, correlationId, failedStep, per-step attempts {order: 3}, errorCode UPSTREAM-UNAVAILABLE, category, compensated, releasedReservationIds, occurredAt. The count matches requests that exhausted retries while the ERP was down (retry ladder: 177 logged attempt failures). Honest label: redrive is by-construction from these envelopes; no automated redrive pipeline exists (designed follow-up). Dashboard panel + >100/10 min alert for DLQ depth: IaC (E-019). → evidence **E-023**.

## SLO-5 — Breaker recovery time

| | |
|---|---|
| **Target** | After the dependency heals, the order flow returns to **201 within ≤ 30 s** without operator intervention beyond the heal itself. Config-derived expectation: breaker wait-in-open 2 s + 3 half-open probes + one successful saga ⇒ flow-level recovery in seconds, bounded at 30 s for measurement. |
| **Error budget** | One recovery event per heal is the test; a recovery exceeding 30 s (or requiring an ESB restart) is a violation. |
| **How measured** | Chaos recipes timestamp the heal (toxic removal / ERP health-up / redis PONG) and the analyzer reports time-of-heal → first-201-probe. |
| **Where the number comes from** | Measured: chaos transcripts (S7); design lineage: E-009 (half-open recovery 201 after proxy heals). |
| **Measured (S7)** | Pending — filled from the S7 chaos runs. |

**Measured (S7):** **E-022/E-023/E-024 (canonical, 2026-09-13):** E-022 (toxic removed): probes returned to baseline within the recovery window (post-window statuses all 201-class); E-023 (ERP restarted): **first 201 probe 2 s after /actuator/health UP** (28 s Spring Boot boot excluded by definition — the breaker wait-in-open is 2 s, matching the config-derived expectation); E-024 (Redis PONG): flow never lost (allow-through continuation), claims resumed. All within the 30 s bound. → evidence **E-022, E-023, E-024**.

## SLO-6 — CDC freshness (C4) — DESIGN-ONLY, NOT MEASURED

| | |
|---|---|
| **Target (defined)** | CDC freshness ≤ **15 min** (C4); T+1 batch complete by **06:00 Asia/Singapore** (C4/C5). |
| **Measured** | **NOT MEASURED — and no number will be claimed.** The producer does not exist: Phase 3 (Debezium CDC → Kafka → lake, Spark batch) is TODO. Faking a freshness metric here would be exactly the fabrication the constitution forbids. |
| **Designed receiving end (honest label: design-only)** | `pipeline-metrics` log store + freshness dashboard panel + the producer contract `{"metric":"cdc_freshness_seconds","value":N,"pipeline":"cdc"|"batch"}` are shipped in IaC (plan-validated, E-019); the panel title reads "awaiting Phase 3 producer (C4, design-only)". When Phase 3 lands, this SLO's measured column is filled from that pipeline's emitted metric. |
| **Where the number comes from** | Nothing yet. The dashboard/alert IaC and this contract are the only Phase-6 artifacts touching C4. |

## Capacity characteristic (not an SLO — measured context for SLO-1)

The stepped stress profile (`tests/load/k6-orders-stress.js`) finds where the budget **actually breaks**: the first step where infra failures (non-422/409) exceed 1% of the step's requests or 201-only p95 exceeds 300 ms. The breaking-point number is the honest headline — the comfortable sustained number (SLO-1) means little without knowing how far the headroom extends.

**Measured (S7):** **E-021 (2026-09-13):** the C3 budget **holds through 90 RPS** (segment A: every step's 201-only p95 ≤ ~40 ms and p99 ≤ ~87 ms — the cold-start r10 step the worst at 39.2/86.2 ms, warm steps ≤ 20.5/24.8 ms — zero infra failures across ~11.7 k segment-A requests) and at **110 RPS on a warm JVM** p95 = 266 ms (89% of budget; p99 349 ms — the first p99 breach) while sustaining **78.9 completed sagas/s**; on a cold JVM 110 RPS breaches outright (p95 658 ms) — cold-start + budget-rate spike is a distinct, real risk signature. Higher rungs are unmeasurable on this estate: the frozen ERP's seeded stock pool (~7.9 k units) drains within the breach window, starving the 201-only trend (200 RPS step: zero 201 samples). Infra integrity never broke at ANY rung (~43.9 k requests across segments A+B+warm-110, zero 5xx/timeouts/resets): the saturation failure mode on this host is latency blowout, not errors. Honest headline: **budget holds to ≥110 RPS warm (p95) / ~90 RPS (p99), bounded by the stock-pool ceiling beyond that** — measured, with the ceiling named. → evidence **E-021**.

---

## Measurement context (applies to every measured number)

- **Mode:** sim (docker-compose + JVM ERP/ESB on the host). Nothing was measured against AliCloud-managed services — no account exists (ADR-0002).
- **Hardware / contention:** i5-10400F 6C/12T @ 2.9 GHz, 16 GB RAM (~8.7 GB already used by the shared desktop stacks), rootless Docker; Airflow/warehouse stacks running concurrently; load average 4.4–4.6 at capture. Numbers are contended-host results — reproducible in shape, not in absolute value, on idle hardware.
- **Tooling:** k6 v0.57 local binary (`~/tools/k6/k6`, not in CI — load rows are local runs, honestly labeled); ERP+ESB booted via `make esb-run` (PID-file hygiene).
- **Falsifiability:** every load row has its `--summary-export` JSON; every chaos row has a single-run transcript with timestamps, PID-file kills only, and an analyzer that exits non-zero on steady-state violations (negative-control proven via `chaos-run.sh --selftest`).

## Evidence row map

| SLO | Row(s) |
|---|---|
| SLO-1 latency (C3) | E-020 (sustained), E-010 (historical baseline) |
| SLO-2 availability under failure | E-022, E-023, E-024 (chaos) |
| SLO-3 zero duplicate commits | E-022, E-023, E-024 (replay probes in every recipe) |
| SLO-4 DLQ recoverability | E-023 (kill-erp DLQ delta + envelopes) |
| SLO-5 breaker recovery | E-022, E-023, E-024 |
| SLO-6 freshness (C4) | none — design-only until Phase 3 (E-019 is the receiving-end IaC) |
| Capacity characteristic | E-021 (stress/breaking point) |
