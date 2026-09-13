# SILKROUTE Phase 6 (S7) — Chaos runbook: ESB order-mediation steady-state harness

Companion of `tests/chaos/chaos-run.sh` (the executable) and `tests/chaos/esb-faults.sh`
(the stable Phase-2 toxiproxy wrapper — called, never modified). Company context:
"Maple Retail Group" is a **fictional** Canadian retailer used as the reference
scenario; nothing here touches a real retailer, real customers, or real money.

---

## 1. Purpose and honest scope

The ESB's resilience configuration (timeouts, retry ladder, circuit breaker,
idempotency) is *claimed* in `apps/esb/src/main/resources/application.yml` and was
proven point-wise in Phase 2 (evidence E-009). This harness does something those
point proofs cannot: it runs a **sustained load** (k6, `tests/load/k6-orders.js`)
against the live saga while a fault is injected, records a per-second probe
transcript **through** the fault, and then **falsifiably analyzes** the transcript.
A hypothesis that survives means something; a violated hypothesis is a recorded
finding, never a silent pass.

Honest scope labels (apply to every result produced by this harness):

- **Sim mode.** ERP = Spring Boot jar on 127.0.0.1:18080 behind toxiproxy
  (`sim-esb-toxiproxy`, proxy `erp` 18180 → 18080, API 18474); ESB = Spring Boot
  jar 18081/18082; sim stack = docker compose (sim-mysql, sim-kafka, sim-redis,
  sim-minio, sim-toxiproxy). Everything is loopback on one laptop.
- **"Pod kill" = process kill in sim.** The kill-erp recipe kills the ERP java
  process by its recorded PID file. In the cloud design this maps to an SAE
  instance/pod crash; in sim it is a process kill. The recovery motion
  (health-gated restart, breaker half-open probes, done-key idempotency) is what
  is under test — not process-supervision machinery, which sim does not model.
- **In-memory ERP ledger.** The sim ERP keeps orders in memory and re-seeds on
  restart. Cross-restart idempotency is carried by the ESB's Redis done-keys
  (`esb:idem:*`, TTL 24h), not the ERP ledger. The ERP-side duplicate-ref guard
  (ORD-DUP-REF) only holds *within* one ERP process lifetime.
- **Shared-host contention.** This laptop concurrently runs other stacks
  (busforge, helios) and the sim network. Absolute latency numbers include that
  contention; each OBSERVED row below carries its own contention note. Compare
  numbers across runs only against runs taken under similar conditions.
- **No freshness scope.** C4 (CDC freshness / T+1 batch) has no Phase-6 surface
  here; this runbook covers C3's mediation path only.

The harness never touches anything outside its own scope: toxiproxy toxics via
`esb-faults.sh`, the ERP/ESB java processes via their PID files
(`/tmp/silkroute-esb-erp.pid`, `/tmp/silkroute-esb-app.pid`), the sim-redis
container via `docker compose stop/start redis`. Never `pkill -f`; never docker
daemon restarts; other stacks on the host are never touched.

---

## 2. STEADY-STATE HYPOTHESES (authored BEFORE any injection)

Written before any S7 execution, each traced to the design configuration it is
derived from. The OBSERVED sections in §4 only get filled after executions; if a
hypothesis breaks, the runbook records the finding — it does not get rewritten.

Design config under test (from `apps/esb/src/main/resources/application.yml`):
ERP connect-timeout 500 ms, receive-timeout 2000 ms; retry max-attempts 3,
backoff 200 ms × 2.0 (INFRA failures only, never business faults); resilience4j
breaker: failure-rate 50 %, sliding window 10, minimum-number-of-calls 6,
wait-in-open 2000 ms, 3 half-open probes; Kafka publish bounded max.block 3000 ms;
idempotency claims TTL 24 h, and **every Redis operation catches ALL exceptions
and degrades gracefully** — `claim()` degrades to allow-through, the ERP-side
ORD-DUP-REF guard is the backstop; a completed key replays 409 DUPLICATE with the
original orderId.

### H1 — latency-under-load: degraded but holding under 800 ms ERP latency

- **During the fault (800 ms toxic on the ERP path):** every probe returns a
  bounded response — no probe may exceed the 10 s curl cap, i.e. no `status 0`
  timeout records in the transcript. 800 ms of injected latency is well under the
  2000 ms receive-timeout, so the retry ladder should NOT trip: requests stay
  201/422/409 with durations inflated by roughly the injected latency, not by
  retry accumulation. Derivation: connect 500 ms / receive 2000 ms budgets vs
  ~800 ms added per ERP hop.
- **No duplicate commits:** replays during the fault must be 409 DUPLICATE
  (Redis done-key) — never a fresh 201 (analyzer enforces).
- **Recovery:** after the toxic is removed, probes return to pre-fault duration
  levels; recovery time (heal → first 201) is measured and expected to be ≤ one
  probe interval (~seconds), because nothing was broken — no breaker state to
  clear.
- **Severe variant (operator choice, 2500 ms injection):** at 2500 ms the
  receive-timeout (2000 ms) trips on every ERP hop, the retry ladder accumulates
  (2 s × 3 attempts + 200/400 ms backoffs), the breaker (50 % over ≥ 6 calls in a
  10-call window) is EXPECTED to open — probes then surface fast CIRCUIT-OPEN
  503s instead of slow timeouts. That is still "bounded": the hypothesis is no
  hang at any injection level, and full recovery (breaker half-open probes
  succeed) after heal without operator intervention.

### H2 — kill-erp: fail fast, land exhausted work in the DLQ, recover by restart

- **During the failure window (ERP process killed):** probes show explicit fast
  failures — CIRCUIT-OPEN 503 (once ≥ 6 calls fill the sliding window with infra
  failures) and/or 5xx — and/or connection-refused `status 0` records bounded to
  the declared kill window. NO 30 s hangs: the retry ladder caps a doomed call at
  ~2 s × 3 + 600 ms of backoff, and the open breaker turns later calls into
  fail-fast. This is the sim stand-in for a pod crash.
- **No duplicate commits across the restart:** a replay of a pre-kill committed
  order must return 409 DUPLICATE carrying the ORIGINAL orderId (the ESB's Redis
  done-key, not the re-seeded ERP ledger, carries idempotency across the restart).
  A replay of a probe that failed during the window may legitimately re-execute
  and 201 after recovery (failed sagas release their claim, E-009) — the analyzer
  counts that separately and does not fail it.
- **DLQ:** exhausted work (sagas that could not complete or compensate during the
  window) lands on `silkroute.esb.dlq` with correlationId; the DLQ message-count
  delta across the fault is the measured quantity.
- **Recovery:** bringing ERP back (same boot command as `make esb-run`) is the
  ONLY intervention; the breaker must self-heal via half-open probes (2000 ms
  wait-in-open, 3 probes) and probes return 201 without any ESB restart. Recovery
  is measured twice: health-UP → first 201 (≤ 60 s watch), and the transcript's
  heal → first-201 delta.

### H3 — stop-redis: allow-through degradation, bounded, backstopped by the ERP guard

- **During the outage (sim-redis container stopped):** requests DEGRADE but stay
  BOUNDED — the idempotency layer's claim() fails, is caught, degrades to
  allow-through (logged warning), and the saga proceeds; the ERP-side ORD-DUP-REF
  guard backstops duplicates. Expected probe outcomes during the outage: 201
  (first execution), 422 ORD-DUP-REF (replays — the ERP ledger still holds the
  ref), plus ordinary 422 stock exhaustion. Derivation: Redis is on
  127.0.0.1:16379; a stopped container yields instant connection-refused, so
  degradation should be fast, not a timeout wait.
- **No hangs — the falsifiable edge:** if probes HANG (the ESB's Redis client
  blocking unboundedly instead of failing fast), probes hit the 10 s curl cap and
  record `status 0` — the analyzer exits 2 on ANY status 0 in this recipe (no
  fault window is declared for network failures here, deliberately). That exit 2
  is a **measured finding about the ESB** ("allow-through is not actually
  bounded"), recorded in §4; it is not a harness failure.
- **No duplicate commits:** replays during the outage must NOT produce fresh
  201s (ORD-DUP-REF 422 or 409); after Redis returns, done-key replays return
  409 DUPLICATE with the original orderId again (claims resume after heal).

---

## 3. Recipes — preconditions, commands, assertions, measurements

Common preconditions (all recipes run `precheck` first, which exits non-zero on
the first missing dependency, exit 3 specifically when the apps are not booted):

```bash
make up            # sim stack (or verify already up)
make esb-run       # boots ERP(18080) + ESB(18081/18082) with PID files, ensures proxy "erp"
```

Artifacts every recipe produces:

- probe transcript: `tests/chaos/transcripts/<recipe>-<UTCstamp>.jsonl`
  (one JSON record per probe: `ts, n, kind probe|replay, ref, status, time_ms,
  error_code, order_id[, error]`; curl failures recorded as
  `{"status":0,"error":"curl:<exit>"}` — never discarded);
- k6 summary: `tests/load/results/chaos-<recipe>-summary.json`
  (latency recipe: `chaos-latency-<MS>ms-summary.json`);
- console transcript of the whole run → the orchestrator copies to
  `evidence/runs/` with hardware context.

Exit codes: `0` clean · `1` usage/dependency error · `2` analyzer found
violations (a measured finding — the transcript shows unbounded behavior or an
idempotency breach) · `3` ERP/ESB not booted. The analyzer fails on: any probe
over 30 000 ms (`HANG_MS`); any `status 0` probe outside a recipe-declared fault
window; any replay that returns 201 after a committed original (duplicate commit)
or a 409 carrying a different orderId than the original, or an unpaired replay.
Falsifiability of the analyzer itself is proven by `--selftest` (no services
needed):

```bash
bash tests/chaos/chaos-run.sh --selftest
```

### 3.1 latency-under-load — `tests/chaos/chaos-run.sh latency-under-load [RPS] [DURATION_s] [MS]` (default 10 120 800)

- Precondition: precheck; nothing else.
- Flow: k6 sustained load (`tests/load/k6-orders.js`, RPS/DURATION env, summary
  `tests/load/results/chaos-latency-<MS>ms-summary.json`) in background → 15 s
  steady state → probe-loop (1/s) → inject `MS` ms latency via
  `tests/chaos/esb-faults.sh latency MS` (timestamped) → hold DURATION_s →
  `esb-faults.sh remove lat` (heal timestamp) → probe 30 s (recovery) → analyze
  with the affected window → per-window status split + heal→first-201 recovery.
- Asserted (analyzer, exit 2 if violated): no status 0 anywhere (bounded under
  800 ms); no duplicate commits; no hangs.
- Measured: per-window status histograms (before / affected / after), error-code
  histogram, max/median durations (all requests AND 201-only), recovery = heal →
  first 201; k6 summary gives the p95 under load.
- Optional severe variant: `latency-under-load 10 120 2500` (breaker expected to
  open and recover — see H1).
- Known limitations: 422 INV-OUT-OF-STOCK counts grow with RPS (seeded stock
  5..100/row) — business rejections, never saturation, never infra failure; the
  toxic adds latency to BOTH probe traffic and k6 traffic (that is the point);
  shared-host contention inflates absolute numbers.

### 3.2 kill-erp — `tests/chaos/chaos-run.sh kill-erp [RPS] [DURATION_s]` (default 10 120)

- Precondition: precheck AND an ERP PID file (refuses without one — the recipe
  kills only by that recorded PID).
- Flow: k6 background → 20 s steady → probe-loop → record pre-kill DLQ count
  (`kafka-console-consumer.sh --from-beginning --timeout-ms 3000 | wc -l` inside
  sim-kafka) → kill ERP by PID file, verify dead via `kill -0` (10 s TERM grace,
  then -9 on the same PID; abort + restore if still alive) → 20 s failure window
  → restart ERP exactly as `make esb-run` does
  (`SERVER_PORT=18080 ERP_DEMO_GENERATE_ORDERS=0 nohup java -jar
  apps/legacy-erp/target/legacy-erp-1.0.0-SNAPSHOT.jar …`, PID file rewritten) →
  wait `/actuator/health` UP (120 s max) → probe until first 201 (60 s max) →
  post-kill DLQ count → analyze with the kill window declared as BOTH the
  status-0 allowance and the affected window → breaker-visibility summary.
- Asserted (analyzer): status 0 records only inside the declared kill window;
  no hangs; no duplicate commits (replay of a pre-kill 201 → 409 with the SAME
  orderId — the Redis done-key, not the re-seeded ledger).
- Measured: kill-window status histogram (CIRCUIT-OPEN present?), DLQ delta =
  exhausted-work messages during the fault, recovery = ERP-health-UP → first 201
  (≤ 60 s), transcript heal→first-201 delta.
- Known limitations: sim process kill ≠ pod kill (supervision not modeled — the
  recipe performs the restart, which IS the operator intervention under test);
  the in-memory ERP ledger re-seeds (see H2 honest note, printed by the recipe);
  the post-restart ERP is a fresh process, so ORD-DUP-REF cannot backstop
  cross-restart duplicates — that burden sits on the Redis done-keys, which is
  exactly what the replay assertions check.

### 3.3 stop-redis — `tests/chaos/chaos-run.sh stop-redis [RPS] [DURATION_s]` (default 10 120)

- Precondition: precheck (includes redis PING→PONG).
- Flow: k6 background → 20 s steady → probe-loop → `docker compose stop redis`
  (timestamped) → 40 s outage → `docker compose start redis` + wait PONG (60 s) →
  probe 20 s → analyze with the affected window → verdict line.
- Asserted (analyzer): NO status 0 anywhere — deliberately NO status-0 window is
  declared, because with Redis down a status 0 means the ESB blocked past the
  10 s probe cap (unbounded client timeout); no duplicate commits (outage replays
  → 422 ORD-DUP-REF; post-heal replays → 409 with the original orderId); no hangs.
- Measured: per-window histograms showing the degradation shape (bounded 201/422
  during the outage vs the baseline), duration inflation, post-heal 409 resumption.
- Interpretation printed by the recipe: analyze exit 0 → degradation stayed
  bounded (H3 held); analyze exit 2 → MEASURED FINDING — either an unbounded
  Redis-client timeout (hang/status 0) or a duplicate commit; the VIOLATION lines
  say which, and §4 records it.
- Known limitations: connection-refused (stopped container) is the "nice" Redis
  failure mode; a black-holed Redis (toxiproxy on 16379) would stress the
  client's connect timeout instead and is NOT covered by this recipe; the ESB is
  not restarted by this recipe (its reconnect behavior after heal is part of the
  measurement).

### Housekeeping

- `tests/chaos/chaos-run.sh stop-all` — stops the ERP/ESB java processes via
  `make esb-stop` (PID files only); the sim stack is untouched.
- Every recipe ends by restoring the world (toxics cleaned via
  `esb-faults.sh clean`, redis started, ERP/ESB left RUNNING with valid PID
  files, and an EXIT-trap best-effort restore if a recipe aborts) and prints
  `CHAOS RUN COMPLETE — stack state: …`.
- No Makefile targets are needed; the raw commands above are the interface.

---

## 4. OBSERVED RESULTS (S7 executions, 2026-09-13)

Filled from the orchestrator's evidence-gated runs (sim mode, shared contended
host — see each artifact's hardware note). H1 and H3 were first REFUTED as
designed, root-caused, fixed in `apps/esb/src/main/resources/application.yml`,
and re-proved — the refutations are the findings, recorded here as executed,
not rewritten. Artifacts: evidence/runs/E-022 (latency), E-023 (kill-erp),
E-024 (stop-redis) with full transcripts in tests/chaos/transcripts/.

### 4.1 H1 — latency-under-load — REFUTED as designed, then held after fix

- Pre-fix (default Spring task executor): H1 FAILED — k6 p95 pinned at its 30 s
  client cap, 44 % checks, 485 `AsyncRequestTimeoutException`, achieved rate
  collapsed 10 → 6.3/s, while the ERP calls THEMSELVES succeeded (zero retry
  attempts, zero breaker activity). Root cause: MVC-async dispatch runs sagas on
  Spring Boot's default `applicationTaskExecutor` (core 8, UNBOUNDED queue);
  slowed sagas (~3.2 s = 4 proxied calls × 800 ms; solo probe 3.22 s vs 15.6 ms
  clean) saturated the 8 workers and arrivals queued to the 30 s async timeout.
  A Camel thread-pool override did NOT fix it (wrong pool — measured again).
- Fix: `spring.task.execution.pool` core 32 / max 64 / queue 200 (bounded).
- Post-fix (canonical, E-022): in-window probes n=25, med 3.6 s, max 4.1 s —
  bounded, zero hangs, zero async timeouts; replays 409 throughout; k6 checks
  98.7 %. H1 HOLDS: degraded-but-holding means bounded-and-continuing, and the
  latency inflation (15 ms → ~3.6 s under 800 ms × 4 calls) is the honest shape.
- Harness note: with the default 10 s probe cap, a cap-timeout records as
  status 0 and this recipe (deliberately) declares no status-0 window — a cap-
  timeout IS a finding here, not a harness error (runs 1-2 proved it twice).

### 4.2 H2 — kill-erp — HELD (first try)

E-023: ERP killed by PID file (verified dead), 20 s failure window, restart,
health-up at 28 s, FIRST 201 2 s AFTER HEALTH (SLO-5 bound: 30 s). Kill-window:
35/35 probes answered with INSTANT 503s (measured 1.9 ms — the fail-fast
signature; body code not captured by the probe parser), retry ladder fired to
exhaustion (177 log lines, attempts 1/3..3/3), DLQ 0 → 59 with COMPLETE replay
envelopes (eventType/externalOrderRef/correlationId/failedStep/attempts/
errorCode UPSTREAM-UNAVAILABLE/compensated/releasedReservationIds). Zero hangs,
zero duplicate commits (analyze: 0 violations). Honest limitation: the ERP
ledger is in-memory — the restart re-seeded stock; cross-restart idempotency
rode the Redis done-keys as documented.

### 4.3 H3 — stop-redis — REFUTED as designed, then held after fix

- Pre-fix: H3 FAILED — every outage probe blocked past the 10 s cap; k6 39.65 %
  failed at ~30 s; the FIRST "allowing through" warn appeared 61 s after the
  outage began — AFTER the heal. `RedisIdempotencyStore`'s catch is correct but
  unreachable in time: Lettuce's default 60 s command timeout swallows the
  window. Post-heal 15× 500 s = the queued backlog dying at the async timeout.
- Fix: `spring.data.redis.timeout` / `connect-timeout` = 500 ms.
- Post-fix (canonical, E-024): allow-through engaged 0.9 s into the outage
  (667 warns); outage-window probes 18/18 completed 201 at med 1.03 s; replays
  during the outage returned 422 ORD-DUP-REF — the ERP backstop visibly held.
  Honest nuance: post-heal replays of OUTAGE-window orders also return 422
  (not 409) — `storeCompleted()` degrades silently during the outage, so those
  done-keys were never stored and replays fall through to the ERP guard. The
  done-key loss window is the documented cost of allow-through; no duplicate
  commit occurred at any point (analyze: 0 violations).
