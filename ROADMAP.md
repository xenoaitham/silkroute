# SILKROUTE ROADMAP — Phase Status Board

Legend: `TODO` → not started · `IN_PROGRESS` → in flight · `REVIEW` → built, awaiting Critic Gate · `DONE` → Critic PASS recorded · `DE-SCOPED` → honestly cut (ADR required)

Fast path (MVCP): 0 → 1 → 2 → 4-lite → 8. Phases 3, 5, 6, 7 amplify depth; Phase 5 is what makes the Asia-rollout story credible.

| Phase | Goal | Status | Key evidence IDs | Notes |
|---|---|---|---|---|
| 0 — Foundation | Scaffolding, state machinery, sim/cloud decision, compose skeleton starts | **REVIEW** (S1) | E-001, E-002, E-003 | cycles 1–2 FAIL (7.80/7.75) fixed; cycle 3 OPEN — critic tooling unavailable (quota), self-audit recorded; fresh critic required at S2 start |
| 1 — Legacy ERP (SOAP, WSDL-first) | Untouchable legacy estate: WSDLs + XSDs first, Spring Boot + CXF, WS-Security, typed faults, seed data, Karate SOAP contract tests, WSDL freeze (C6) | TODO | — | Java toolchain: JDK 17 + Maven wrapper required |
| 2 — ESB core | Camel hub: REST→canonical→SOAP saga w/ compensation, XSLT, CBR by store region, retry/CB/DLQ, idempotent consumer, PII-masking egress; toxiproxy fault tests; measured p95 (C3) | TODO | — | The heart of the resume story |
| 3 — ETL pipelines | Debezium→Kafka→bronze; Spark silver/gold; 100% reconciliation; DQ quarantine; freshness ≤15min (C4), T+1 by 06:00 SGT (C5) | TODO | — | |
| 4 — AliCloud IaC & cloud deploy | Terraform alicloud modules, remote state, validate+plan clean, RAM least-privilege, budget alarm; deploy or validated-plans mode per ADR-0002 | TODO | — | See ADR-0002 for honest mode |
| 5 — Compliance & security | CI-provable residency tests (C1), PIPL/PDPA/PIPEDA matrix w/ article citations, cross-border memo (PIPL 38–40), ICP runbook, STRIDE, ActionTrail→SLS demo | TODO | — | Asia-rollout credibility layer |
| 6 — Observability, load, chaos, cost | SLS dashboards/alerts/SLOs, k6 measured numbers, chaos runbook executed, cost sheet from real pricing pages | TODO | — | |
| 7 — Docs & demo | Stranger-testable README, HLD/LLD, ADR set, runbooks, delivery model (RACI), demo video | TODO | — | |
| 8 — Resume & interview pack | Resume bullets w/ evidence IDs, 40-Q deep-drill Q&A, 25-min whiteboard script, final gate: 3-round hostile mock interview | TODO | — | Final gate = project complete |

## Critic Gate log

| Date | Phase | Cycle | Weighted score | Verdict | Findings summary |
|---|---|---|---|---|---|
| 2026-09-08 | 0 | 1 | 7.80 | **FAIL** | 1 high (E-002 row not bound to its command/artifact) + 6 med (§3 tree not in git, STATE over-claims, smoke 2/5, hardcoded creds/0.0.0.0 binds, undeclared jq dep, CI never executed) + 5 low. All fixed. |
| 2026-09-08 | 0 | 2 | 7.75 | **FAIL** | 1 crit proven live (`make smoke` could not fail — `set -e` exempts non-final `&&`-list members), 1 high (hardcoded `-psilkroute` broke credential indirection), 2 med (kafka host listener unusable, README port omissions), 2 low (curl undeclared, CI re-implements wait). All fixed incl. negative-control evidence E-003. |
| 2026-09-09 | 0 | 3 | **PENDING** | **OPEN** | Critic subagent spawn failed twice (quota limit / model request failed) — no fresh-agent verdict exists. ORCH-LEAD self-audit executed instead (secrets scan clean; compose config OK; negative path re-proven: smoke exit 2 down / 0 restored; git clean; §3 tree in git; stack recovered after daemon restart). **Phase 0 stays REVIEW. Fresh CRITIC required at S2 start before Phase 1 work.** |
| — | — | — | — | — | — |

## De-scope log

| Date | Phase | What was cut | ADR |
|---|---|---|---|
| — | — | — | — |
