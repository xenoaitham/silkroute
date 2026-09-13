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
| `sim-mysql` on 3306, MySQL 8.0.46 | ApsaraDB RDS MySQL 8.0 | `infra/data` -> `alicloud_db_instance` (+ `alicloud_db_database` `silkroute_oms`) | `SPRING_DATASOURCE_URL` (OMS — the consumer app is BUILT and sim-measured, E-030) | Engine pinned `8.0` both sides; `utf8mb4` charset; RDS stores UTC (`time_zone=+00:00` parameter, C5 multi-timezone rule); access via `sg-data` (ESB/ERP SGs only). Validated IaC, sim runtime. |

## Kafka (order events)

| sim (docker-compose) | managed AliCloud | terraform source | app env var | notes |
|---|---|---|---|---|
| `sim-kafka`, Kafka 3.8 KRaft, `KAFKA_BOOTSTRAP=127.0.0.1:39092` | Message Queue for Apache Kafka (VPC instance) | `infra/compute` -> `alicloud_alikafka_instance` + `alicloud_alikafka_topic` (`silkroute-orders-events`, `silkroute-esb-dlq`) + `alicloud_alikafka_sasl_user` | `KAFKA_BOOTSTRAP`, `KAFKA_ORDERS_TOPIC`, `KAFKA_DLQ_TOPIC` | Managed brokers speak the Kafka wire protocol, so 3.x clients interoperate. CORRECTED per critic cycle 1: ApsaraMQ for Kafka CreateTopic FORBIDS dots in topic names (letters/digits/`_`/`-` only), so cloud topics are dot-free and the ESB selects them via the two topic env vars (`${KAFKA_ORDERS_TOPIC:...}` indirection in application.yml); the sim keeps its dotted defaults — the swap stays configuration-only via env indirection, NOT name identity. Guarded by scripts/tf-apply-validity.sh in CI. SASL PLAIN `esb-client` replaces sim's no-auth. Validated IaC, sim runtime. |

## Redis (cache/idempotency)

| sim (docker-compose) | managed AliCloud | terraform source | app env var | notes |
|---|---|---|---|---|
| `sim-redis` on 16379, Redis 7 | ApsaraDB for Redis | NOT MODELED in IaC (honest gap, critic cycle 1): the ESB's idempotency backend has no `alicloud_kvstore_instance` yet — network path exists (`sg-data` 6379 rules) but the instance shape is region-availability-dependent, so it is chosen at activation and its endpoint flows via `var.redis_host` / `var.redis_port` | `REDIS_HOST`, `REDIS_PORT` | Sim port 16379 vs managed 6379 - both flow through the same two env vars; reachable from `sg-esb` only. Validated IaC, sim runtime. |

## Object storage (data lake + artifacts)

| sim (docker-compose) | managed AliCloud | terraform source | app env var | notes |
|---|---|---|---|---|
| `sim-minio` on 9000 (S3 API) | OSS + SSE-KMS | `infra/data` -> `alicloud_oss_bucket` (+ server-side-encryption, versioning, public-access block, bucket policy) for `silkroute-sg-{artifacts,bronze,silver,gold}` | `LAKE_BRONZE_BUCKET` / `LAKE_SILVER_BUCKET` / `LAKE_GOLD_BUCKET` (Phase 3 lake buckets — built in sim, E-030) | **Known OSS S3-compat gap (ADR-0001):** MinIO exposes the full S3 API; the sim's bronze/silver/gold writers prove the S3 API surface they use (path-style, sigv4, putObject/s3a), but OSS's S3-compatible surface differs in parts — the native OSS SDK path is verify-at-activation (ADR-0002, ADR-0006). Every bucket denies non-TLS transport and blocks public access; no cross-region replication (C1). Validated IaC, sim runtime. |

## Legacy SOAP ERP

| sim (docker-compose) | managed AliCloud | terraform source | app env var | notes |
|---|---|---|---|---|
| `erp` jar on 18080; `sim-toxiproxy` on 18180 injects latency/faults | Serverless App Engine (SAE) app `silkroute-erp` | `infra/compute` -> `alicloud_sae_application` (`sg-esb` ingress only, port 18080) | `ERP_BASEURL` | Fault injection moves from toxiproxy to the `ESB_FAULT_INJECTION` env flag in the ESB - same chaos experiments, no code delta. Validated IaC, sim runtime. |

## WSS credentials (ESB <-> ERP auth)

| sim (docker-compose) | managed AliCloud | terraform source | app env var | notes |
|---|---|---|---|---|
| dummy values in `.env` | injected from secret store at deploy (KMS-backed; no secret material in IaC or state) | env wiring in `infra/compute` -> `alicloud_sae_application` `envs` | `ERP_WSS_USERNAME`, `ERP_WSS_PASSWORD` | Values are variable placeholders in Terraform purely so credential-free plans run (ADR-0002); the pipeline replaces them at deploy. Validated IaC, sim runtime. |

## Data plane (Phase 3) — OMS event store, Debezium CDC, lake (BUILT, sim-measured E-030..E-034)

The phase-3 apps are built and run in sim today (OMS: `silkroute.orders.events` -> MySQL `silkroute_oms`; CDC: embedded Debezium engine, MySQL binlog -> Kafka `silkroute.cdc.oms` -> bronze; batch: Spark bronze->silver->gold + DQ + reconciliation — ADR-0006). Parity per component:

| sim (docker-compose / host JVMs) | managed AliCloud | terraform source | app env var | notes |
|---|---|---|---|---|
| OMS event store: host JVM, `silkroute.orders.events` -> MySQL `silkroute_oms` (`oms_order` + `oms_order_line`) | Same jar against ApsaraMQ for Kafka + RDS `silkroute_oms` | `infra/compute` (Kafka) + `infra/data` (RDS) | `SPRING_DATASOURCE_URL`/`USERNAME`/`PASSWORD`, `KAFKA_BOOTSTRAP`, `KAFKA_ORDERS_TOPIC`, `OMS_CONSUMER_GROUP` | NOW REAL — the consumer app is built and sim-measured (E-030: 30 orders + 30 lines stored, `REPLAY-SKIP` replay guard exercised); the swap is the two env-var groups only. Validated IaC, sim runtime. |
| CDC replication user: `silkroute_cdc` with SELECT + REPLICATION SLAVE + REPLICATION CLIENT, nothing else (created by `make etl-setup`) | RDS-equivalent replication config at activation — an RDS account with the same three grants + binlog settings (`binlog_format=ROW`, retention) | NOT MODELED in IaC (an activation-time RDS account, like the OMS DB account pattern) | `CDC_MYSQL_HOST`/`PORT`/`USER`/`PASSWORD` | The connector user is least-privilege by construction (apps/cdc README); RDS equivalent is console/API config, verify at activation. Validated IaC, sim runtime. |
| CDC topic: `silkroute.cdc.oms` (dotted sim name) | dot-free cloud topic (e.g. `silkroute-cdc-oms`) selected via `KAFKA_CDC_TOPIC` — same env indirection as the ESB topics, per the ApsaraMQ no-dots rule | **ACTIVATION ITEM — NOT in current IaC:** the 87/118 plan counts do NOT include this topic; it joins the landing zone's `alicloud_alikafka_topic` set at activation (apply-time API rule, tf-apply-validity pattern) | `KAFKA_CDC_TOPIC` | Stated honestly: the CDC topic is a designed cloud topic with no planned resource yet — adding it is an activation/IaC change, not a doc claim. The env indirection keeps the swap configuration-only (ADR-0001/0006). |
| Lake buckets: sim-created MinIO buckets `silkroute-sg-{bronze,silver,gold}` mirroring the IaC family names (`make etl-setup`) | OSS buckets `silkroute-sg-{artifacts,bronze,silver,gold}` (SSE-KMS, TLS-deny, public-access block) | `infra/data` -> `alicloud_oss_bucket` x4 | `LAKE_BRONZE_BUCKET` / `LAKE_SILVER_BUCKET` / `LAKE_GOLD_BUCKET` | Sim names intentionally mirror the IaC family so the env vars do not change at the swap. **OSS S3-compat gap:** the sim proves the S3 API surface the writers use (path-style, sigv4, putObject, s3a reads); OSS's S3-compatible layer differs in parts (multipart/listing semantics, storage-class headers) — the native OSS SDK path is verify-at-activation (ADR-0001/0002/0006). |
