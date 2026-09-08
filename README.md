# SilkRoute — Multi-Region Alibaba Cloud Integration Platform

> **Self-directed reference implementation (2026).** The scenario company **Maple Retail Group** is **fictional** — see [docs/scenario-charter.md](docs/scenario-charter.md) and MASTER_PROMPT §9 integrity rules. This project lives under "Projects," never "Experience."

A multi-region enterprise integration platform for a fictional Canadian retailer expanding into **Singapore** (international hub) and **mainland China** (PIPL data-residency partition), built to demonstrate — with measured evidence:

- **AliCloud environments** — Terraform (`alicloud`) landing zone: VPC, RAM least-privilege + STS, KMS, SLS, OSS, RDS, SAE/ACK, API Gateway, budget alarms
- **ESB-pattern integration hub** — Apache Camel: WSDL-first SOAP ↔ REST mediation, XSLT, canonical data model, saga w/ compensation, retry/circuit-breaker/DLQ, idempotent consumption, PII-masking egress
- **ETL pipelines** — Debezium CDC → Kafka → bronze/silver/gold lake with DQ gates, 100% reconciliation, freshness SLAs
- **Asia-market compliance** — CI-provable data residency (PIPL/PDPA/PIPEDA), cross-border transfer memo, ICP runbook, STRIDE, audit trail

## Quickstart (sim mode — default, zero cloud spend)

Requires Docker. If the system daemon isn't available but rootless Docker is:

```bash
docker context use rootless   # if applicable
make up        # starts sim network: MySQL 8, Kafka (KRaft), Redis, MinIO, Toxiproxy
make ps        # all services should show (healthy)
make smoke     # connectivity check
make down      # stop and wipe volumes
```

## Repository map

| Path | Contents |
|---|---|
| [MASTER_PROMPT.md](MASTER_PROMPT.md) | Project constitution (mission, scenario, phases, protocols) |
| [ROADMAP.md](ROADMAP.md) | Phase status board + critic gate log |
| [STATE.md](STATE.md) | Current state, session log, war stories |
| [evidence/EVIDENCE.md](evidence/EVIDENCE.md) | Claim → artifact → reproduce command → measured result |
| [decisions/](decisions/) | ADRs |
| apps/ | legacy-erp (SOAP), esb (Camel), modern-oms, cdc, batch |
| infra/ | Terraform alicloud modules (network/security/data/compute/observability) |
| compliance/ | PII masking, residency tests, control matrix, ICP runbook, STRIDE |
| tests/ | Karate contract, Testcontainers, k6 load, chaos |
| docs/ | HLD, LLD, runbooks, delivery model, demo script |

Full architecture docs land in Phase 7; this README grows with the build.
