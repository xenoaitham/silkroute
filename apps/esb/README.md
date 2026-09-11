# SILKROUTE ESB (apps/esb) — Phase 2 integration hub

Maple Retail Group (**fictional**) Camel ESB. It mediates modern REST/JSON into the
**frozen** on-prem SOAP ERP estate (`apps/legacy-erp`, freeze tag
`contract-freeze-erp-v1`) — per constraint **C6** ALL impedance mismatch lives here:
canonical model `urn:maple:canonical:order:v1` + XSLT legs. Money is **integer minor
units + CAD/SGD/CNY** everywhere (C5, never floats). Chinese-customer PII (C1/PIPL)
is **masked before any egress to shared Kafka topics** (`PiiMaskingPolicy`).

## Architecture

```
POST /api/v1/orders (platform-http, loopback 18081)
        |
        v
OrderRequestProcessor ----------------------- 400 SCHEMA-VIOLATION (SENDER)
  |-- CanonicalOrderValidator (networknt,      409 DUPLICATE (Redis SETNX idem)
  |   draft 2020-12, additionalProperties:false)
  |-- IdempotencyStore (Redis SETNX esb:idem:<key>, TTL 24h;
  |   key = Idempotency-Key header, else sha256(sourceSystem+externalOrderRef);
  |   final 201 JSON stored at esb:idem:done:<key>)
  v
SagaOrchestrator  (HAND-ROLLED saga — NOT camelSaga EIP)
  |
  | direct:erp  (single funnel)
  v
[circuitBreaker camel-resilience4j: 50% / window 10 / minCalls 6 /
 open 2s / half-open probes 3; records ErpInfraException ONLY;
 OPEN -> fail fast, 503 CIRCUIT-OPEN, wire untouched]
  |
  v
ErpGateway  (the ONLY ERP talker; CXF SOAP 1.2 + WSS4J UsernameToken)
  (1) order   submitOrder      canonical XML --canonical-to-submit-request.xsl--> ERP
                              ERP --submit-response-to-canonical.xsl--> canonical
  (2) reserve inventory reserve PER LINE (typed calls; ids collected)
  (3) pricing priceForSku per line (canonical-to-price-request.xsl / price-response-to-canonical.xsl)
  (4) confirm getOrderStatus asserting SUBMITTED
  retry ladder on INFRA failures only (timeout/connect): 3 attempts, 200ms x2.0;
  ERP business faults -> immediate 422 BUSINESS (ADR-0003: wire code soap:Receiver
  is IGNORED, the typed Detail element decides Sender class)
  |
  +-> failure after reserve => compensate: release every collected reservationId
      (best-effort), 503/422 + compensated:true + releasedReservationId
  +-> DLQ: retries exhausted (infra) or compensated saga =>
      kafka:silkroute.esb.dlq  (customerRef MASKED for CN — C1)
  +-> success => kafka:silkroute.orders.events (customerRef masked for CN — C1)
```

### HTTP outcomes (shared contract)

| case | status | error.code |
|---|---|---|
| schema violation | 400 | `SCHEMA-VIOLATION` (SENDER) |
| duplicate (idempotency) | 409 | `DUPLICATE` (+ original `orderId` when recorded) |
| ERP business fault (ADR-0003 Sender class) | 422 | ERP `errorCode` (e.g. `INV-OUT-OF-STOCK`), category `BUSINESS` |
| infra failure after retries | 503 | `UPSTREAM-UNAVAILABLE`, or `SAGA-COMPENSATED` (+`compensated:true`,`releasedReservationId`) when compensation released |
| circuit breaker open | 503 | `CIRCUIT-OPEN` (fail fast) |

Success (201) body: `orderId,status,externalOrderRef,storeId,region,channel,
reservationId,totalAmount{amountMinor,currency},unitPrices[],saga[],attempts{},
route{region,customerRefMasked},audit{sourceSystem,correlationId}`.
`attempts` is ALWAYS present and counts real per-ERP-call attempts per step
(`reserve`/`pricing` count one per order line).

## What lives where (ESB pattern → class)

| ESB pattern | class(es) |
|---|---|
| REST facade / CBR entry (region from storeId) | `api/OrderRequestProcessor`, `saga/Region` |
| canonical model v1 (JAXB+Jackson dual) | `canonical/order/v1/*`, `canonical/CanonicalXmlCodec` |
| JSON Schema validation (networknt 2020-12) | `api/CanonicalOrderValidator`, `resources/canonical/order/v1/order.schema.json` |
| XSLT mediation legs | `resources/xslt/*.xsl`, `xslt/XsltTransformer` |
| SOAP client + WS-Security UsernameToken | `config/ErpClientConfiguration`, `erp/WssPasswordCallbackHandler` |
| saga + compensation (hand-rolled) | `saga/SagaOrchestrator`, `saga/SagaContext` |
| ERP gateway, retry ladder, attempt counting | `erp/ErpGateway` |
| ADR-0003 fault mapping (Receiver wire code → Sender class) | `erp/ErpErrorClassifier` |
| circuit breaker | `config/ResilienceConfiguration` (resilience4j bean `erpGatewayCircuitBreaker`, route `direct:erp`) |
| idempotent consumer | `idem/IdempotencyStore`, `idem/RedisIdempotencyStore`, `idem/IdempotencyKeys` |
| PII masking egress (C1/PIPL) | `events/PiiMaskingPolicy` — applied by `saga/SagaOrchestrator` + `api/OrderResponseAssembler` before EVERY shared-topic publish |
| Kafka success events + DLQ | `events/EventPublisher`, `events/DlqPayload` |
| fault-injection test hook | `erp/FaultInjectionFeature` |

## Run

```bash
# 0) ERP sim must be up on 18080 (apps/legacy-erp jar, bound to 127.0.0.1).

# 1) toxiproxy proxy "erp": ESB ERP_BASEURL default = http://127.0.0.1:18180
#    (ALL ERP calls go through toxiproxy so faults are injectable).
#    The proxy lives on the host-network toxiproxy (container sim-esb-toxiproxy,
#    API 127.0.0.1:18474, started by `make up`): bridge-mode sim-toxiproxy (8474)
#    cannot upstream to a host-loopback service, and the ERP jar binds host
#    127.0.0.1:18080 — so the ESB path gets its own host-network toxiproxy whose
#    proxy "erp" binds real host loopback 127.0.0.1:18180 -> 127.0.0.1:18080.
./scripts/esb-toxiproxy.sh           # ensure + print state
./scripts/esb-toxiproxy.sh reset     # remove all toxics from proxy "erp"
./scripts/esb-toxiproxy.sh remove    # delete the proxy
# Env overrides: TOXIPROXY_API (default http://127.0.0.1:18474),
# ESB_PROXY_NAME/ESB_PROXY_LISTEN/ESB_PROXY_UPSTREAM.

# 2) Kafka topics (sim-kafka, host listener 127.0.0.1:39092):
docker exec sim-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server 127.0.0.1:39092 \
  --create --if-not-exists --topic silkroute.esb.dlq        --partitions 1 --replication-factor 1
docker exec sim-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server 127.0.0.1:39092 \
  --create --if-not-exists --topic silkroute.orders.events  --partitions 1 --replication-factor 1

# 3) build + boot
./mvnw -f apps/esb/pom.xml clean package
java -jar apps/esb/target/esb-1.0.0-SNAPSHOT.jar      # REST on 127.0.0.1:18081
curl http://127.0.0.1:18082/actuator/health            # -> {"status":"UP"}

# 4) happy path
curl -s -X POST http://127.0.0.1:18081/api/v1/orders -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-1' -d '{
    "externalOrderRef":"WEB-1","sourceSystem":"WEB_STORE_CA","storeId":"ST-CA-01",
    "channel":"WEB_STORE","customerRef":"cust-x",
    "lines":[{"skuId":"SKU-0001","quantity":2}],
    "audit":{"sourceSystem":"WEB_STORE_CA","receivedAt":"2026-09-11T10:00:00Z","correlationId":"corr-1"}}'
```

## Config (application.yml, all `${VAR:default}`)

| var | default | meaning |
|---|---|---|
| `SERVER_PORT` | `18081` | REST (binds 127.0.0.1 only via `server.address`) |
| `MANAGEMENT_PORT` | `18082` | actuator health/info (binds 127.0.0.1 only) |
| `ERP_BASEURL` | `http://127.0.0.1:18180` | ERP via toxiproxy proxy "erp" |
| `ERP_WSS_USERNAME` / `ERP_WSS_PASSWORD` | `esb-client` / `erp-wss-pass-2026` | sim dummies (documented) |
| ERP timeouts | connect `500ms` / read `2000ms` | per-call, chosen for the C3 budget (retry ladder adds 200+400ms worst case) |
| retry | `3` attempts, `200ms` initial, `x2.0` | INFRA failures only (timeout/connect), never business faults |
| `KAFKA_BOOTSTRAP` | `127.0.0.1:39092` | topics `silkroute.orders.events`, `silkroute.esb.dlq` (`maxBlockMs 3000` bounded publish) |
| `REDIS_HOST`/`REDIS_PORT` | `127.0.0.1:16379` | idempotency (SETNX + TTL 24h); Redis outage degrades to allow-through |
| `ESB_FAULT_INJECTION` | `false` | enables the test hook below |

## WS-Security wire facts (ERP rejects otherwise — hard-won in S2)

`WSS4JOutInterceptor` with `action=UsernameToken, passwordType=PasswordText,
addNonce=true, addCreated=true` (verified live against the ERP):
Password `Type` = the OASIS **profile URI**
`.../oasis-200401-wss-username-token-profile-1.0#PasswordText`; fresh Base64
nonce per request with `EncodingType=...#Base64Binary` (BSP:R4220); `wsu:Created`
UTC. Fallback (not needed): build the Security header DOM exactly like
`scripts/wss-header.sh`.

## Fault-injection test hook (QA)

Start the ESB with `ESB_FAULT_INJECTION=true`; then request header
`X-Fault-Injection: fail-pricing` (or `fail-confirm`; generically `fail-<step>`)
makes that saga step throw a **synthetic infra timeout on EVERY attempt** →
retries exhaust (attempts.step=3) → compensation → 503
`SAGA-COMPENSATED` → DLQ event with `compensated:true` (customerRef masked for
CN). When the flag is absent/false the header is **ignored**.

## Deviations / notes (deliberate, documented)

- **Reserve-step business faults** (OutOfStock) → 422, NO compensation, NO DLQ —
  literally per contract. Consequence: if an earlier line's reservation was
  already collected before the failing line, it is not released by design
  (uncompensated holds expire in the ERP).
- `reservationId` in the 201 body = FIRST reservation id (multi-line orders
  collect one id per line; all of them are compensated and reported in
  `releasedReservationId(s)`).
- Retry is implemented inside `ErpGateway` (hand-rolled ladder, exact attempt
  counting); the circuit breaker is the camel-resilience4j EIP. Business faults
  are RETURNED from the gateway bean so they can never trip the breaker.
- Success/DLQ publishes are synchronous with `maxBlockMs=3000` and never fail
  the HTTP outcome; the REST 201 body never carries `customerRef` (only the
  Kafka event copy does, masked for CN).
