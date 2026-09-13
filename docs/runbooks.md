# SilkRoute — Runbooks

Maple Retail Group is a **fictional** Canadian retailer; these runbooks belong to a **self-directed reference implementation** (2026; MASTER_PROMPT §9 framing). Runbooks 1–2 are the honest cloud-side path — the landing zone is **validated IaC** and has never been applied (ADR-0002), so anything touching a real account is written for the day one exists. Runbooks 4–5 are proven in sim (4 = the chaos playbook, E-022/E-023/E-024; 5 = the freshness playbook, E-030/E-031); SLS panel/alert behavior in 5 stays verify-at-activation. Every trigger, prerequisite, and command below points at a real repo artifact; reference material is linked, not duplicated.

---

## 1. ACTIVATION — the day an AliCloud account exists

**Trigger:** an international AliCloud account with billing is available (and, separately for the CN partition, a CN-registered account with real-name verification — ADR-0005). This runbook is the enriched form of the checklist in [infra/README.md](../infra/README.md) ("Activation checklist").

**Prerequisites:** account credentials in the environment (`ALICLOUD_ACCESS_KEY`/`ALICLOUD_SECRET_KEY` — the `tf-placeholder-*` variables in [infra/providers.tf](../infra/providers.tf) are then removed); terraform 1.16.x + `alicloud` provider 1.285.0; [docs/cost-model.md](cost-model.md) re-fetched (prices drift — a stale fetch date is treated like an unfetched price).

**Steps, in this order:**

1. **KMS-instance cost decision FIRST.** The design's 2 customer-managed software keys ride a **USD 500/month Software KMS Instance** (no per-key pricing exists below its 1,000-key quota) — the single largest cost line at ~53% of the designed bill (E-025). Decide **customer-managed vs default keys** before applying anything: default cloud-product keys are free but the security control matrix currently mandates customer-managed keys for OSS SSE-KMS + RDS TDE (`compliance/control-matrix.md` EC rows). If default keys are acceptable, amend the control matrix as an ADR before applying.
2. **Budget alarm LIVE=1 first.** `make budget-alarm LIVE=1` (or `LIVE=1 ALICLOUD_ACCESS_KEY_ID=... ALICLOUD_ACCESS_KEY_SECRET=... bash scripts/budget-alarm.sh` — LIVE is an environment variable, not an argument) — the BssOpenApi `CreateBudget` call, ~$20 cap, warn at 100%. The dry-run payload is proven (E-014); the live CLI flag conventions for array args confirm on first live use (recorded as an open risk in STATE.md).
3. **SLS action policy (console — no provider resource).** Provider 1.285.0 ships no action-policy resource (schema-verified): create the policy in the SLS console, then set `var.sls_action_policy_id`. Condition-expression semantics are confirmed against the live alert editor — plans prove schema, not SLS behavior.
4. **ActionTrail service-linked role.** The trail references the role by ARN placeholder; it is created out-of-band on first trail activation.
5. **RDS class availability check.** The configured class `mysql.n2.small.v65` is **console-priced only** (the L6 honest gap in the cost sheet — no fetchable unit price; only a smaller sibling's "From $5.52/Month" floor was citable). Confirm the class exists in ap-southeast-1 and record its real price; expect the true RDS line ≥ the sheet's 5.52 plus unquantified storage.
6. **Topic-name env vars.** Cloud topics are dot-free (`silkroute-orders-events` / `silkroute-esb-dlq` — ApsaraMQ `CreateTopic` forbids dots); the ESB selects them via `KAFKA_ORDERS_TOPIC` / `KAFKA_DLQ_TOPIC`. Set both at deploy; [scripts/tf-apply-validity.sh](../scripts/tf-apply-validity.sh) guards the naming rule in CI.
7. **Deploy → capture → destroy** per the §8 guardrails (next runbook): `make deploy-sg` → capture plan/apply output + runtime verifications from the checklist step 5 of infra/README.md (SSE-KMS upload with `kms:GenerateDataKey`, SLS ingest, STS assume-role, TDE on the SG RDS instance — the CN instance belongs to the separate CN-account activation) → `make destroy`. New evidence rows say `cloud` mode; existing rows keep their sim numbers.

**Expected output:** a real landing zone at SG, evidence rows in cloud mode, a bill measured against E-025's 949.64 designed figure — and nothing left running.

**Rollback / escape hatch:** `make destroy` (guarded) at any point; the CN partition is never in scope here (needs its own CN account — do not enable `var.enable_cn_region` on the international account).

---

## 2. DEPLOY / DESTROY (guarded)

**Trigger:** evidence-capture windows only (per §8: never leave billable resources running overnight).

**Prerequisites:** activation runbook completed through step 6; `SILKROUTE_CLOUD_CONFIRM=YES` **and** `ALICLOUD_ACCESS_KEY(_ID)`/`SECRET` in the environment. Without both, the targets refuse — the double guard is by design (E-014).

**Steps:**

```bash
make deploy-sg     # guarded apply (enable_cn_region=false — the CN flag stays off)
make destroy       # guarded destroy after capture
```

**Expected output:** without the confirmation/credentials both targets print `REFUSING: this target touches a real AliCloud account (billable).` and exit 2 — that refusal is the proven behavior (E-014). With them, the apply proceeds; **capture-then-destroy discipline:** every apply/destroy is captured as evidence before the next step.

**Rollback / escape hatch:** `make destroy` is itself the escape hatch; if a destroy stalls, the account console is the fallback — record what was deleted manually in the evidence row. Plans (`make plan-sg`) remain the zero-API dry run at any time.

---

## 3. DLQ REDRIVE — by construction only (HONEST)

**Trigger:** `dlq-depth-alert` fires (DLQ count > 100 in a 10 min window, evaluated on a 5 min schedule) — designed threshold; in sim, an elevated count after a fault recipe (E-023's kill-erp moved the DLQ 0 → 59).

**Prerequisites:** none beyond the sim stack. **Honest label: no automated redriver exists.** Redrive is by-construction — reading the envelope and re-publishing deliberately — and an automated redrive pipeline is a designed follow-up, not a built one (stated identically in docs/slo-report.md SLO-4).

**Steps (manual, from the envelope fields verified in E-023):**

1. Read the DLQ envelopes (`kafka-console-consumer.sh` on `silkroute.esb.dlq`, from-beginning with a timeout, inside sim-kafka — the same instrument the chaos recipe uses).
2. Map fields → action:

| Envelope field | Action |
|---|---|
| `failedStep` + `errorCode` (`UPSTREAM-UNAVAILABLE`) | the dependency failed mid-saga — verify the dependency is healthy, then re-post the order with the SAME `Idempotency-Key` (a released claim re-executes; a done-key returns the original 201). Re-publishing the envelope to `silkroute.orders.events` targets the BUILT event consumer (the OMS event store, `make oms-run` — `REPLAY-SKIP`/`OMS-ORDER-STORED` lines in `/tmp/silkroute-oms.log`, E-030/E-034); re-post + consumer replay are both live paths in sim |
| `category` = `BUSINESS` | a business fault reached the DLQ path (compensated saga) — fix the data/request, not the wire |
| `compensated` = `true` + `releasedReservationIds` | holds were already released; the replay is safe, no manual inventory surgery |
| `customerRef` (CN store, `msk-*`) | **pseudonymized, not plaintext** — re-identification happens only inside the CN boundary with the key (`PII_MASK_SECRET` / its activation-time secret store); a redrive never needs the plaintext value, by design |
| `correlationId`, `occurredAt` | correlate against the ESB log (`/tmp/silkroute-esb-app.log`) before deciding anything |

3. After redrive, confirm no duplicate commits: replays surface as 409 DUPLICATE (done-key) or 422 ORD-DUP-REF (ERP guard) — never a second 201 for the same key.

**Expected output:** exhausted orders replayed exactly once; zero duplicate commits (the invariant proven across E-008/E-009/E-022/023/024).

**Rollback / escape hatch:** don't re-publish anything whose dependency isn't healthy — the DLQ is the durable record; leaving messages there is safe and alerted, blind redrive is not.

---

## 4. DEPENDENCY-DEGRADATION TRIAGE — the E-022/E-024 playbook

**Trigger:** the façade is slow or timing out under load, but the dependency (ERP) looks fine — or an outage shows no degradation machinery firing at all.

**Prerequisites:** `make esb-run` stack; the chaos harness ([tests/chaos/README.md](../tests/chaos/README.md) — THE authority for recipes and analyzer semantics).

**Symptom → diagnosis → fix, from the two measured refutations:**

- **Symptom: "facade queue-then-timeout with zero breaker/retry activity"** (probes hang to the 30 s cap, k6 p95 pinned at its client cap, 4xx/5xx histogram quiet, no attempt counters incrementing). **Suspect executor saturation, NOT the dependency** — this is exactly E-022's refutation: 800 ms ERP latency saturated Spring's default 8-worker task executor (unbounded queue), the ERP calls themselves kept succeeding, so the breaker and retry ladder never saw a failure. **Diagnostic:** thread dump under load — look for the `task-N` workers (the `applicationTaskExecutor` pool) all busy; a Camel thread-pool override will NOT fix it (wrong pool — measured twice). **Fix:** bounded `spring.task.execution.pool` core 32 / max 64 / queue 200 (already applied); verify with `tests/chaos/chaos-run.sh latency-under-load 10 120 800` → probes bounded (~3.6 s med = 4 proxied calls × 800 ms), `analyze: 0 violations`.
- **Symptom: "outage with no allow-through lines"** (Redis down or unreachable; requests block instead of degrading). **Suspect client timeouts** — E-024's refutation: the allow-through catch was exception-correct but unreachable behind Lettuce's 60 s default command timeout (first allow-through warn 61 s into a 40 s outage, i.e. after the heal). **Fix:** `spring.data.redis.timeout` / `connect-timeout` = 500 ms (already applied); verify with `tests/chaos/chaos-run.sh stop-redis 10 120` → allow-through ~0.9 s into the outage, outage replays 422 ORD-DUP-REF (ERP guard backstop), post-heal replays of outage-window orders also 422 (the measured done-key loss window — expected, not a defect).
- **The probe-loop instrument is the diagnostic.** Both findings were invisible to unit tests and the fault-injection suite — only sustained load + a per-second probe transcript through the fault exposed them. Recipes, assertions, and exit-code semantics live in [tests/chaos/README.md](../tests/chaos/README.md); the stable toxiproxy wrapper is [tests/chaos/esb-faults.sh](../tests/chaos/esb-faults.sh).

**Expected output:** `analyze: OK — 0 violations` post-fix; a bounded-degradation transcript, not a hang histogram.

**Rollback / escape hatch:** every recipe restores the world on exit (toxics cleaned, redis started, ERP/ESB left running); `esb-faults.sh clean` + `chaos-run.sh stop-all` force it.

---

## 5. FRESHNESS-BREACH RESPONSE

> **Label: producer BUILT and measured in sim (E-030/E-031).** The CDC/batch pipeline runs here — the freshness metric is emitted by the real producer in the exact Phase-6 contract shape. What stays verify-at-activation: SLS ingest, panel rendering, and alert behavior on the `pipeline-metrics` store (no AliCloud account exists, ADR-0002) — and sustained production lag in a continuously-running deployment (the sim proves the metric and the pipeline, not a 24/7 schedule).

**Trigger:** the freshness panel/alert on the `pipeline-metrics` store breaches the C4 target (CDC freshness ≤ 15 min; T+1 batch complete by 06:00 Asia/Singapore). In sim today the equivalent trigger is operator-visible: `cdc_freshness_seconds` heartbeat values growing past budget in `/tmp/silkroute-cdc-engine.log`, or a `BATCH-WINDOW ... withinWindow=false` line in the batch log.

**The contract (shipped in IaC, plan-proven — E-019; emitted and measured — E-031):** the producer emits `{"metric":"cdc_freshness_seconds"|"batch_completion","value":N,"pipeline":"cdc"|"batch"}`; the `pipeline-metrics` store (30 d TTL, `metric`/`value` indexed) and the dashboard panel (titled "CDC freshness (s) — cdc_freshness_seconds (C4; producer live in sim, E-030/E-031)") are the cloud receiving end.

**Steps (what the operator checks — real commands against the sim producer):**

1. Alert SQL on `pipeline-metrics` fires on the threshold — which alert rule/threshold is a [infra/observability/README.md](../infra/observability/README.md) question (verify-at-activation). In sim, check the producer is emitting at all (metric absence ≠ freshness breach — no fresh metric lines means the pipeline is DOWN, not slow).
2. Producer-side triage, in order (all greps verified in E-030/E-031):
   - Is capture alive: `grep -c CDC-CAPTURE /tmp/silkroute-cdc-engine.log` — per-record `op=c table=... sourceTsMs=...` lines only come from the MySQL binlog stream (the engine has no poll fallback; E-031).
   - Is freshness real lag or pipeline death: `grep '"metric":"cdc_freshness_seconds"' /tmp/silkroute-cdc-engine.log` — `trigger=batch` lines landing at value=0 with heartbeats growing between them is normal lag under source silence (E-031); heartbeat lines STOPPING entirely is a dead engine (restart with `make cdc-stop && make cdc-run` — offsets are a file store, so it resumes from its binlog position, the E-034 restart scenario).
   - Which pipeline: `pipeline` tag in the metric — `cdc` (streaming lag) vs `batch` (the T+1 window miss, `batch_completion` seconds + `BATCH-WINDOW ... withinWindow=` lines).
   - Did the data land correctly end to end: `make etl-check` — asserts OMS rows exist, bronze objects landed, and `/tmp/silkroute-recon-report.json` reads `allMatch=true`.
   - OMS-side replay health: `OMS-ORDER-STORED` / `REPLAY-SKIP` lines in `/tmp/silkroute-oms.log`; bronze landings: `BRONZE-LAND region=... object=... records=N` in `/tmp/silkroute-cdc-bronze.log`.
3. Resolution paths follow the consumer-lag → connector → source triage order; in sim the measured failure paths are the E-034 scenarios (CDC killed mid-stream → restart → catch-up from binlog offsets, no loss no dup; OMS offsets reset → 12 `REPLAY-SKIP`s, no duplicates). A full clean-slate re-run is `make etl-reset` followed by the E-030 chain verbatim (esb/oms/cdc up → `make seed-day N=30` → cdc-stop → `make batch-run ARGS="full --business-date <UTC date of the seed>"` → `jq . /tmp/silkroute-recon-report.json` — the exact command lives in the ledger row); the 6-scenario regression suite is `make etl-int`.

**Expected output:** freshness back under budget (heartbeat values reset after the next batch lands — E-031: trigger=batch at value=0) and a recon report that still reads `allMatch=true`.

**Rollback / escape hatch:** `make etl-reset` is the clean-slate instrument (stops oms/cdc/esb, truncates tables, purges topics/groups/buckets/offsets) — bronze is rebuilt from the binlog/topic; never hand-edit lake objects (write-once by construction, E-033). SLS-side alert tuning stays verify-at-activation.

---

## Reference, don't duplicate

| Topic | THE authority |
|---|---|
| Chaos recipes, hypotheses, analyzer semantics | [tests/chaos/README.md](../tests/chaos/README.md) |
| Alert thresholds, store schemas, observability trade-offs | [infra/observability/README.md](../infra/observability/README.md) |
| Sim↔cloud environment matrix | [docs/infra-env-parity.md](infra-env-parity.md) |
