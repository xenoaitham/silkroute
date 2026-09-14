# apps/modern-oms - the OMS event store (CDC source)

Maple Retail Group is a **fictional** company; this is a self-directed
reference implementation (Project SILKROUTE, data plane, ADR-0006 decision 2).

Consumes ESB success events (topic `silkroute.orders.events`, the
OrderSubmissionResponse body **plus** `customerRef` and `lines[]` - the
event-copy-only fields) into MySQL database `silkroute_oms`:

- `oms_order` - one row per accepted order + `raw_event` JSON (lineage) +
 Kafka partition/offset lineage + `ingested_at` (Kafka record ts, UTC).
- `oms_order_line` - one row per line; `line_total_minor = quantity x
 unit_price_minor` in **integer** math (C5), region denormalized for tagging.

Semantics: **at-least-once** - the offset is acked only after the writes
commit; idempotency is belt-and-braces:

1. code-level business-key guard (`source_system`, `external_order_ref`):
 duplicates log `REPLAY-SKIP ...` and are skipped **including their lines**
 (no partial dupes);
2. `INSERT ... ON DUPLICATE KEY UPDATE <pk>=<pk>` - a deliberate no-op.

Poison (malformed) events are logged `OMS-POISON ...` and acked - the loop
never dies on them. DB failures are retried forever (fixed backoff, loud) and
never skipped. `customerRef` is stored **verbatim** - for CN stores it is
ALREADY the `msk-*` pseudonym applied at the ESB egress (C1); nothing in this
app can unmask. **No HTTP port** (`web-application-type=none`).

Schema is ensured on boot (`CREATE TABLE IF NOT EXISTS`, plain JDBC, no JPA).

## Run

```bash
make etl-setup # once: DB + user + grants
make oms-run # builds if needed; waits for OMS-CONSUMER-START; /tmp/silkroute-oms.log
make oms-stop
```

Boot = `java -jar apps/modern-oms/target/oms-1.0.0-SNAPSHOT.jar`
(fat jar via spring-boot-maven-plugin - same style as apps/esb; plain shade
would silently drop duplicate AutoConfiguration.imports files).

## Knobs (every one is `${VAR:default}` - ADR-0001)

| Env var | Default | Meaning |
|---|---|---|
| `KAFKA_BOOTSTRAP` | `127.0.0.1:39092` | Kafka bootstrap (sim host listener) |
| `KAFKA_ORDERS_TOPIC` | `silkroute.orders.events` | input topic |
| `OMS_CONSUMER_GROUP` | `silkroute-oms` | consumer group id |
| `OMS_AUTO_OFFSET_RESET` | `earliest` | first-run position (earliest backfills history) |
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://127.0.0.1:3306/silkroute_oms?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true` | MySQL JDBC URL (UTC session per C5) |
| `SPRING_DATASOURCE_USERNAME` | `silkroute_oms` | DB user (created by `make etl-setup`) |
| `SPRING_DATASOURCE_PASSWORD` | `oms-pass-2026` | DB password (sim dummy) |

## Log lines worth grepping (evidence)

- `OMS-CONSUMER-START group=... topic=...` - subscription confirmed (Makefile waits for this)
- `OMS-SCHEMA-READY ...` - schema ensured
- `OMS-ORDER-STORED orderId=... lines=... totalMinor=... currency=...` - one per stored order
- `REPLAY-SKIP sourceSystem=... externalOrderRef=...` - replay-safety proof
- `OMS-POISON ...` - malformed event skipped without killing the loop

## Cloud parity (ADR-0001)

- Kafka swap (ApsaraMQ for Kafka): change `KAFKA_BOOTSTRAP` (+ topic names via
 `KAFKA_ORDERS_TOPIC` - the managed broker forbids dots; the ESB documents the
 same indirection). Client API is identical.
- MySQL swap (ApsaraDB RDS): change the three `SPRING_DATASOURCE_*` vars. The
 RDS-equivalent binlog/replication config for the CDC side is documented in
 ADR-0006.
- Honest gap: sim runs the consumer as a host JVM with a PID file; the cloud
 shape would be a container/SAE deployment - configuration-only, no code
 change, but the process supervision is out of scope for the sim.

## Tests

`./mvnw -f apps/modern-oms/pom.xml package` - plain-JUnit (10 tests): event ->
rows mapping incl. C5 integer line-total math, replay-skip flow, CN masked
customerRef stored verbatim, poison handling that never kills the loop,
tombstone skip, no-ack-on-store-failure.
