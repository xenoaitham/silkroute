# SilkRoute — Multi-Region Alibaba Cloud Integration Platform

[![ci](https://github.com/xenoaitham/silkroute/actions/workflows/ci.yml/badge.svg)](https://github.com/xenoaitham/silkroute/actions/workflows/ci.yml)

> **Self-directed reference implementation (2026).** The scenario company **Maple Retail Group** is **fictional** — see [docs/scenario-charter.md](docs/scenario-charter.md) and MASTER_PROMPT §9 integrity rules. This project lives under "Projects," never "Experience."

A multi-region enterprise integration platform for a fictional Canadian retailer expanding into **Singapore** (international hub) and **mainland China** (PIPL data-residency partition), built to demonstrate — with measured evidence:

- **AliCloud environments** — Terraform (`alicloud`) landing zone: VPC, RAM least-privilege + STS, KMS, SLS, OSS, RDS, SAE/ACK, API Gateway, budget alarms
- **ESB-pattern integration hub** — Apache Camel: WSDL-first SOAP ↔ REST mediation, XSLT, canonical data model, saga w/ compensation, retry/circuit-breaker/DLQ, idempotent consumption, PII-masking egress
- **ETL pipelines** — Debezium CDC → Kafka → bronze/silver/gold lake with DQ gates, 100% reconciliation, freshness SLAs
- **Asia-market compliance** — CI-provable data residency (PIPL/PDPA/PIPEDA), cross-border transfer memo, ICP runbook, STRIDE, audit trail

## Quickstart (sim mode — default, zero cloud spend)

Prerequisites: **Docker** (with compose), **jq** (`make up` health-wait), **curl** (`make smoke`), and for the Java services **JDK 21** — the repo carries a Maven Wrapper pinned to Maven 3.9.9 (`./mvnw`), so no Maven install is ever needed. If the system daemon isn't available but rootless Docker is:

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
| legacy-erp (SOAP) | **18080** | 8080 is taken by an unrelated stack on this shared host; loopback-only, env-overridable via `SERVER_PORT` |
| legacy-erp (contract tests) | **18090** | test-run app instance so the suite never collides with a manually started ERP |

## Phase 1 — legacy ERP (SOAP 1.2, WSDL-first, frozen per C6)

The "untouchable legacy estate": OrderService, InventoryService, PricingService — SOAP 1.2 document/literal, WS-Security UsernameToken (BSP-strict), typed faults, in-memory seeded estate (50 SKUs, 8 stores across CA/SG/CN regions, 400 stock rows). The WSDLs/XSDs in `apps/legacy-erp/src/main/resources/{wsdl,xsd}/` are **frozen** (ADR-0003): the code is generated *from* them (`cxf-codegen` wsdl2java) and must never be edited again.

```bash
./mvnw -f apps/legacy-erp/pom.xml clean package        # build + unit tests
SERVER_PORT=18080 java -jar apps/legacy-erp/target/legacy-erp-*.jar &
#   ERP SEED: skus=50 stores=8 stockRows=400   (boot log line)
#   optional demo data: ERP_DEMO_GENERATE_ORDERS=5 at boot
curl http://127.0.0.1:18080/actuator/health             # {"status":"UP"}
./mvnw -f tests/contract/pom.xml verify                 # Karate contract tests (boots its own app on 18090; 16 scenarios incl. fault + auth paths)
```

Sim WSS credentials (env-indirected dummies, never real secrets): username `esb-client`, password `erp-wss-pass-2026` (override with `ERP_WSS_USERNAME` / `ERP_WSS_PASSWORD`). Header reference: `scripts/wss-header.sh`.

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
