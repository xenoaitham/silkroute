# ADR-0001: Dual-mode architecture — sim mode (default) and cloud mode

- **Status:** Accepted (2026-09-08)
- **Deciders:** ORCH-LEAD (Haitham)

## Context

Project SILKROUTE must demonstrate a multi-region AliCloud integration platform with real, measured evidence, while being a self-directed project with no corporate budget (MASTER_PROMPT §8 guardrails: default to sim while building; cloud only for evidence capture; destroy after evidence). Several scenario constraints shape how the platform must run:

- **C1** — Chinese PII residency must be provable: the same residency tests must run identically in sim and cloud modes so the control is demonstrated by code, not by where it happens to run.
- **C3/C4** — latency and freshness SLAs must be *measured*; measurements need a reproducible environment that can run offline and cost nothing.
- **C5** — multi-timezone batch windows must be schedulable in sim (TZ-explicit) to be testable before any cloud deploy.
- §8 — never leave billable resources running; no load balancers/nodepools overnight.

## Options considered

1. **Cloud-only** (deploy everything to AliCloud Singapore): highest authenticity; but every build/test cycle costs money, violates the "never leave running" guardrail, and makes the project undemoable offline. Rejected as the default.
2. **Sim-only** (docker-compose only): zero spend, fully reproducible; but AliCloud environment skill (Terraform alicloud, RAM/KMS/SLS/OSS semantics) would remain theoretical. Rejected alone.
3. **Dual-mode**: identical interfaces (Kafka API, MySQL wire protocol, S3 API, etc.) behind docker-compose locally, AliCloud managed services in cloud mode. Sim is the default build/test/demo target; cloud mode is brought up only to capture evidence, then destroyed.

## Decision

Adopt **dual-mode** (option 3). Concretely:

- `docker-compose.yml` at repo root is the **sim runtime**: MySQL 8 (RDS stand-in), Kafka KRaft (Message Queue for Apache Kafka stand-in), Redis (idempotency store stand-in), MinIO (OSS stand-in), Toxiproxy (fault injection).
- All application code (ESB, ERP, OMS, CDC, batch) speaks only to *interfaces* (JDBC, Kafka clients, S3 SDK), never to sim or cloud specifics directly — cloud swap happens via configuration/Terraform, not code changes.
- Cloud mode = Terraform modules under `infra/` (alicloud provider) + the same apps deployed to SAE/ACK, used only for Phase 4/6 evidence capture, then `make destroy`.
- Every EVIDENCE row states its mode (`sim` or `cloud`) and hardware context.

## Consequences

- (+) Zero-spend development; offline demo; identical residency/latency test suites in both modes.
- (+) Terraform work still exercises real alicloud provider semantics via `validate`/`plan`.
- (−) Sim mode cannot prove AliCloud-managed-service behavior (SLS ingest, KMS envelopes, RAM STS); those claims must either be cloud-evidenced or honestly labeled as designed/validated-only (see ADR-0002).
- (−) Parity drift risk: image versions in sim can diverge from managed-service engine versions; mitigated by pinning MySQL 8.x and Kafka 3.x and noting versions in evidence rows.
- (−) OSS access in cloud mode via the S3-compat layer has known gaps vs the native OSS SDK (region endpoints, STS token flow, SSE-KMS header semantics); native ossutil/oss2 paths to be validated at Phase 4 before any cloud evidence relies on them.
