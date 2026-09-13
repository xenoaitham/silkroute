# ADR-0006: Phase-3 ETL data plane — CDC engine choice, lake shape, and metric definitions

- **Status:** Accepted (2026-09-13, S9)
- **Deciders:** ORCH-LEAD (Haitham)
- **Implements:** MASTER_PROMPT §3 component 3 (the ETL blueprint), constraints C1/C4/C5; the Phase-6 receiving ends (E-019 pipeline-metrics store, SLO-6, runbook 5).

## Context

Phase 3 is the last build phase: ESB success events → OMS MySQL → Debezium CDC → Kafka → bronze/silver/gold with DQ gates, 100% reconciliation, and the C4 freshness producer. Four decisions had to be recorded BEFORE any measurement exists (war story 32: the measured column wants to be born full) and before any code commits to a shape.

## Decision 1 — CDC engine: embedded Debezium Engine in `apps/cdc` (not a Kafka Connect cluster)

Options considered:

1. **Kafka Connect (Connect-style container, Debezium MySQL connector plugin)** — operationally closest to a cloud deploy (ApsaraMQ for Kafka offers a managed Connect experience); but it drags a worker process + REST config API (port 8083) into the sim stack, its offsets live inside container state (harder to kill/restart deterministically in chaos tests), and the sim already runs 6 containers on a contended shared host.
2. **Embedded Debezium Engine** (`debezium-embedded` + `debezium-connector-mysql`) inside a small host-JVM app — one plain Java process, offsets in a configurable file store (deterministic kill/restart for the chaos beat), zero new containers, code-level control of exactly what is published where.

**Decision: option 2.** The interface contract — Debezium envelope messages on a Kafka topic — is IDENTICAL under both engines, so the ADR-0001 cloud swap stays configuration-only: in cloud mode the same bronze-writer consumer reads a topic fed either by a self-managed Connect cluster or the managed Connect offering, with the connector name/topic prefix selected by env vars. The engine is a producer-side deployment detail, not an interface change. Recorded trade-off honestly: sim's embedded engine is NOT the cloud deploy shape; cloud parity is at the envelope + topic interface, not the runtime.

## Decision 2 — the OMS event store is the CDC source; the ESB event copy carries lines

`apps/modern-oms` consumes `silkroute.orders.events` into MySQL `silkroute_oms` (`oms_order` + `oms_order_line`). The success-event copy previously carried no per-line quantity, so the ESB event copy now (S9, additive) also carries `lines[]` = `{skuId, quantity, unitPriceMinor, currency}` — event-copy-only injection (REST 201 body unchanged), mirroring the customerRef precedent. Money stays integer minor units + currency everywhere (C5); line totals in the lake are integer multiplication, never float.

Idempotency: the consumer is replay-safe by (a) Kafka consumer-group offsets and (b) a business-key guard — unique `external_order_ref` + `source_system` in `oms_order`, inserts as `INSERT ... ON DUPLICATE KEY UPDATE id=id` (replay re-consumes, never double-inserts).

## Decision 3 — lake shape and region tagging (C1)

- **Bronze** (MinIO bucket `silkroute-sg-bronze` in sim; OSS family in cloud): raw Debezium envelopes landed write-once as JSONL objects keyed `region=<REGION>/table=<table>/dt=<YYYYMMDD>/<topic>-<partition>-<offset>-<uuid>.jsonl`. Region tag lives in the object key prefix AND as a record field; objects are never updated or deleted (no delete/overwrite code paths — gate-checked). Immutability is by construction: keys are unique per (topic, partition, offset, uuid) and a re-run writes new keys.
- **Silver** (bucket `silkroute-sg-silver`): cleansed/conformed Parquet (typed columns, integer money as BIGINT + currency string).
- **Gold** (bucket `silkroute-sg-gold`): star schema `fact_orders`, `fact_order_lines`, `dim_sku`, `dim_store`, `dim_date` as Parquet, plus the reconciliation report.
- **DQ quarantine** (MASTER_PROMPT §3: "quarantine table"): MySQL database `silkroute_lake`, table `dq_quarantine` (+ `dq_results`, `recon_report`), written by the batch job. The CDC connector captures ONLY `silkroute_oms` — quarantine/DQ bookkeeping does not feed back into the lake.

CN PII: the lake inherits the ESB's masked `customerRef` (`msk-*`) — CN plaintext never enters the OMS and therefore never enters the lake (the masking happens at the ESB egress chokepoint, E-009/E-016 lineage). Bronze objects carrying CN rows are tagged `region=CN` in the key; no sink outside CN receives them in cloud mode (bucket families per IaC; R5).

## Decision 4 — metric definitions (committed before any number exists)

- **`cdc_freshness_seconds`** = `now − ts_ms` where `ts_ms` is the source-commit timestamp of the latest Debezium record the CDC app successfully published to the Kafka CDC topic, measured by the producer (apps/cdc) at emission time. Emitted as `{"metric":"cdc_freshness_seconds","value":N,"pipeline":"cdc"}` — the exact contract shape of the Phase-6 `pipeline-metrics` store (E-019). In sim it lands in the app log + a run artifact file; SLS ingest is the cloud receiving end (verify-at-activation, ADR-0002).
- **`batch_completion`** = the batch job's end-to-end wall-clock runtime in seconds for one bronze→gold run, emitted as `{"metric":"batch_completion","value":N,"pipeline":"batch"}`. The T+1 window (C4/C5: complete by 06:00 Asia/Singapore) is evaluated in CODE by a unit-testable evaluator using `ZoneId "Asia/Singapore"` and a fixed-clock negative control (a clock past 06:00 must evaluate `withinWindow=false`); the sim run records actual completion time and the evaluator's verdict honestly.

## Consequences

- (+) Zero new containers; the sim stack stays at 6 services (smoke unchanged); the whole data plane is host JVMs with PID-file hygiene like the ERP/ESB.
- (+) Definitions are auditable in git BEFORE any measured value (this commit precedes every freshness/batch number).
- (−) The embedded engine means sim does not demonstrate Connect operations (connector task rebalancing, REST config); honestly scoped to the interface.
- (−) `binlog_row_metadata=MINIMAL` on sim MySQL (verified 2026-09-13: `log_bin=ON`, `binlog_format=ROW`, `binlog_row_image=FULL`); Debezium resolves schema from information_schema, so MINIMAL suffices for these tables; a parity note in docs/infra-env-parity.md records the replication-user requirement (REPLICATION SLAVE/CLIENT + SELECT) as RDS-equivalent config.
