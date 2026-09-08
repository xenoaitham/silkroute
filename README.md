# SilkRoute — Multi-Region Alibaba Cloud Integration Platform

> **Self-directed reference implementation (2026).** The scenario company **Maple Retail Group** is **fictional** — see [docs/scenario-charter.md](docs/scenario-charter.md) and MASTER_PROMPT §9 integrity rules. This project lives under "Projects," never "Experience."

A multi-region enterprise integration platform for a fictional Canadian retailer expanding into **Singapore** (international hub) and **mainland China** (PIPL data-residency partition), built to demonstrate — with measured evidence:

- **AliCloud environments** — Terraform (`alicloud`) landing zone: VPC, RAM least-privilege + STS, KMS, SLS, OSS, RDS, SAE/ACK, API Gateway, budget alarms
- **ESB-pattern integration hub** — Apache Camel: WSDL-first SOAP ↔ REST mediation, XSLT, canonical data model, saga w/ compensation, retry/circuit-breaker/DLQ, idempotent consumption, PII-masking egress
- **ETL pipelines** — Debezium CDC → Kafka → bronze/silver/gold lake with DQ gates, 100% reconciliation, freshness SLAs
- **Asia-market compliance** — CI-provable data residency (PIPL/PDPA/PIPEDA), cross-border transfer memo, ICP runbook, STRIDE, audit trail

## Quickstart (sim mode — default, zero cloud spend)

Prerequisites: **Docker** (with compose), **jq** (`make up` health-wait), **curl** (`make smoke`). If the system daemon isn't available but rootless Docker is:

```bash
docker context use rootless   # if applicable
cp .env.example .env          # optional — sim-only dummy credentials
make up        # starts sim network: MySQL 8, Kafka (KRaft), Redis, MinIO, Toxiproxy
make ps        # all services should show Up; 4/5 report (healthy), toxiproxy is distroless (no healthcheck)
make smoke     # asserts connectivity to ALL five services (fails non-zero on any failure)
make down      # stop and wipe volumes
```

Host ports (what a client on your machine connects to — all bound to 127.0.0.1 only):

| Service | Host port | Why not the default |
|---|---|---|
| MySQL | 3306 | default |
| Kafka | **39092** | 9092 stays internal: bootstrap metadata advertises the in-compose name `kafka:9092`; host clients use the HOST listener advertised as `localhost:39092` |
| Redis | **16379** | 6379 often collides with a pre-existing local Redis; container port stays 6379 |
| MinIO | 9000 (API) / 9001 (console) | defaults |
| Toxiproxy | 8474 (API) | default |

## Repository map

| Path | Contents |
|---|---|
| [MASTER_PROMPT.md](MASTER_PROMPT.md) | Project constitution (mission, scenario, phases, protocols) |
| [ROADMAP.md](ROADMAP.md) | Phase status board + critic gate log |
| [STATE.md](STATE.md) | Current state, session log, war stories |
| [evidence/EVIDENCE.md](evidence/EVIDENCE.md) | Claim → artifact → reproduce command → measured result |
| [decisions/](decisions/) | ADRs |
| apps/ | legacy-erp (SOAP), esb (Camel), modern-oms, cdc, batch — *lands Phases 1–3* |
| infra/ | Terraform alicloud modules (network/security/data/compute/observability) — *lands Phase 4* |
| compliance/ | PII masking, residency tests, control matrix, ICP runbook, STRIDE — *lands Phase 5* |
| tests/ | Karate contract, Testcontainers, k6 load, chaos — *lands Phases 1–6* |
| docs/ | HLD, LLD, runbooks, delivery model, demo script |

Full architecture docs land in Phase 7; this README grows with the build.
