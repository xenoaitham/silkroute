# Infra environment parity - sim (docker-compose) vs managed AliCloud

Maple Retail Group is a fictional Canadian retailer; this doc maps the Phase 3
local simulation to the Phase 4 managed landing zone so either environment can
run the same application artifacts.

**ADR-0001 rule, verbatim: "the swap is configuration/Terraform only, NO app
code changes."** All application code (ESB, ERP, OMS, CDC, batch) speaks only
to interfaces (JDBC, Kafka clients, S3 SDK), never to sim or cloud specifics
directly.

## MySQL (OMS database)

| sim (docker-compose) | managed AliCloud | terraform source | app env var | notes |
|---|---|---|---|---|
| `sim-mysql` on 3306, MySQL 8.0.46 | ApsaraDB RDS MySQL 8.0 | `infra/data` -> `alicloud_db_instance` (+ `alicloud_db_database` `silkroute_oms`) | `SPRING_DATASOURCE_URL` (OMS, Phase 3) | Engine pinned `8.0` both sides; `utf8mb4` charset; RDS stores UTC (`time_zone=+00:00` parameter, C5 multi-timezone rule); access via `sg-data` (ESB/ERP SGs only). Validated IaC, sim runtime. |

## Kafka (order events)

| sim (docker-compose) | managed AliCloud | terraform source | app env var | notes |
|---|---|---|---|---|
| `sim-kafka`, Kafka 3.8 KRaft, `KAFKA_BOOTSTRAP=127.0.0.1:39092` | Message Queue for Apache Kafka (VPC instance) | `infra/compute` -> `alicloud_alikafka_instance` + `alicloud_alikafka_topic` (`silkroute.orders.events`, `silkroute.esb.dlq` - identical names) + `alicloud_alikafka_sasl_user` | `KAFKA_BOOTSTRAP` | Managed brokers speak the Kafka wire protocol, so 3.x clients interoperate (3.x-compatible topic protocol); topic names byte-identical to the sim so no reconfiguration beyond the bootstrap URL; SASL PLAIN `esb-client` replaces sim's no-auth. Validated IaC, sim runtime. |

## Redis (cache/idempotency)

| sim (docker-compose) | managed AliCloud | terraform source | app env var | notes |
|---|---|---|---|---|
| `sim-redis` on 16379, Redis 7 | ApsaraDB for Redis | landing-zone SG: endpoint supplied via `var.redis_host` / `var.redis_port` (instance lands with the SG hub data tier; CN partition pins its own endpoint) | `REDIS_HOST`, `REDIS_PORT` | Sim port 16379 vs managed 6379 - both flow through the same two env vars; reachable from `sg-esb` only. Validated IaC, sim runtime. |

## Object storage (data lake + artifacts)

| sim (docker-compose) | managed AliCloud | terraform source | app env var | notes |
|---|---|---|---|---|
| `sim-minio` on 9000 (S3 API) | OSS + SSE-KMS | `infra/data` -> `alicloud_oss_bucket` (+ server-side-encryption, versioning, public-access block, bucket policy) for `silkroute-sg-{artifacts,bronze,silver,gold}` | Phase 3 lake buckets | **Known OSS S3-compat gap (ADR-0001):** MinIO exposes the full S3 API, OSS's S3-compatible surface does not - the native OSS SDK path is Phase 3 work. Every bucket denies non-TLS transport and blocks public access; no cross-region replication (C1). Validated IaC, sim runtime. |

## Legacy SOAP ERP

| sim (docker-compose) | managed AliCloud | terraform source | app env var | notes |
|---|---|---|---|---|
| `erp` jar on 18080; `sim-toxiproxy` on 18180 injects latency/faults | Serverless App Engine (SAE) app `silkroute-erp` | `infra/compute` -> `alicloud_sae_application` (`sg-esb` ingress only, port 18080) | `ERP_BASEURL` | Fault injection moves from toxiproxy to the `ESB_FAULT_INJECTION` env flag in the ESB - same chaos experiments, no code delta. Validated IaC, sim runtime. |

## WSS credentials (ESB <-> ERP auth)

| sim (docker-compose) | managed AliCloud | terraform source | app env var | notes |
|---|---|---|---|---|
| dummy values in `.env` | injected from secret store at deploy (KMS-backed; no secret material in IaC or state) | env wiring in `infra/compute` -> `alicloud_sae_application` `envs` | `ERP_WSS_USERNAME`, `ERP_WSS_PASSWORD` | Values are variable placeholders in Terraform purely so credential-free plans run (ADR-0002); the pipeline replaces them at deploy. Validated IaC, sim runtime. |
