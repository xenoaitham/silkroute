# apps/cdc — embedded Debezium capture + bronze landing

Maple Retail Group is a **fictional** company; this is a self-directed
reference implementation (Project SILKROUTE, Phase 3, ADR-0006 decisions 1+3).

ONE plain-Java module, TWO shaded jars (two mains):

| Jar | Main | Role |
|---|---|---|
| `cdc-1.0.0-SNAPSHOT.jar` | `CaptureEngine` | embedded Debezium engine: MySQL binlog → Kafka `silkroute.cdc.oms` (FULL envelopes, JSON, schemas disabled) + the C4 freshness metric |
| `cdc-1.0.0-SNAPSHOT-bronze.jar` | `BronzeWriter` | Kafka CDC topic → MinIO/OSS bronze JSONL objects, write-once, manual offset commit after landing |

ADR-0006 decision 1: the embedded engine is the SIM deployment shape — the
interface contract (Debezium envelope messages on a Kafka topic) is IDENTICAL
under Kafka Connect, so the cloud swap stays configuration-only.

## Run

```bash
make etl-setup   # once: silkroute_cdc user (SELECT + REPLICATION SLAVE/CLIENT), topic, buckets
make cdc-run     # boots BOTH jars; waits for CDC-ENGINE-START; /tmp/silkroute-cdc-engine.log + /tmp/silkroute-cdc-bronze.log
make cdc-stop
```

Binlog PROOF: every captured record logs

```
CDC-CAPTURE op=c table=oms_order sourceTsMs=1789296000123 server=silkroute-oms
```

These lines can only originate from the MySQL binlog stream — the engine has
NO poll/table-scan fallback; the connector user holds SELECT +
REPLICATION SLAVE + REPLICATION CLIENT and nothing else. Grep
`CDC-CAPTURE` in `/tmp/silkroute-cdc-engine.log` to prove real binlog capture.

## Bronze object keys (ADR-0006, exact)

```
region=<REGION>/table=<table>/dt=<YYYYMMDD UTC of source.ts_ms>/<topic>-<partition>-<offset>-<uuid4>.jsonl
```

- one JSONL object per (region, table, dt) group per poll batch; one raw
  envelope per line;
- region from `after.region` falling back to `before.region`; a record whose
  region is missing/outside `${BRONZE_ALLOWED_REGIONS}` REJECTS the writer
  (fail-fast — an untagged object can never land, C1);
- immutability by construction: putObject only — no delete/copy/overwrite
  code paths exist; keys embed (topic, partition, offset, uuid4);
- offsets commit ONLY after every object of the batch landed;
- every landing logs `BRONZE-LAND region=... table=... object=... records=N firstTsMs=... lastTsMs=...`.

## Freshness metric (C4 — ADR-0006 definition verbatim)

`CaptureEngine` measures, at emission time,
`cdc_freshness_seconds = now − source.ts_ms` of the latest record it
successfully published to the CDC topic (synchronous `send().get()`), and
emits exactly:

```
{"metric":"cdc_freshness_seconds","value":12,"pipeline":"cdc"}
```

once per published batch AND every `${METRIC_HEARTBEAT_SECONDS}` while idle
(a growing value under source silence is CORRECT measured lag, not an error).
The same line appends to `${METRICS_FILE}` when set (evidence artifact).
Value is an integer number of seconds (the brief allows 1 decimal; the sim
emits integers to keep every number in this repo non-floating-point).

## Knobs (every one is `${VAR:default}` — ADR-0001)

| Env var | Default | Applies to | Meaning |
|---|---|---|---|
| `KAFKA_BOOTSTRAP` | `127.0.0.1:39092` | both | Kafka bootstrap |
| `KAFKA_CDC_TOPIC` | `silkroute.cdc.oms` | both | CDC topic |
| `CDC_SERVER_NAME` | `silkroute-oms` | engine | connector `topic.prefix` |
| `CDC_ENGINE_NAME` | `silkroute-cdc` | engine | engine name |
| `CDC_MYSQL_HOST` / `CDC_MYSQL_PORT` | `127.0.0.1` / `3306` | engine | MySQL location |
| `CDC_MYSQL_USER` / `CDC_MYSQL_PASSWORD` | `silkroute_cdc` / `cdc-pass-2026` | engine | replication user |
| `CDC_SERVER_ID` | `17234001` | engine | replication client id (MUST differ from the MySQL server_id, 1 on sim) |
| `CDC_DATABASE_INCLUDE` | `silkroute_oms` | engine | database whitelist |
| `CDC_TABLE_INCLUDE` | `silkroute_oms.oms_order,silkroute_oms.oms_order_line` | engine | table whitelist |
| `CDC_SNAPSHOT_MODE` | `initial` | engine | Debezium snapshot mode |
| `CDC_OFFSET_FILE` | `/tmp/silkroute-cdc-offsets.json` | engine | file offset store |
| `CDC_SCHEMA_HISTORY_FILE` | `/tmp/silkroute-cdc-history.dat` | engine | file schema-history store |
| `METRIC_HEARTBEAT_SECONDS` | `30` | engine | idle heartbeat cadence |
| `METRICS_FILE` | (unset) | engine | if set, APPEND metric lines to this file |
| `CDC_BRONZE_GROUP` | `silkroute-bronze-writer` | bronze | consumer group |
| `CDC_BRONZE_AUTO_OFFSET_RESET` | `earliest` | bronze | land everything on the topic |
| `CDC_BRONZE_MAX_POLL_RECORDS` | `500` | bronze | poll batch size |
| `S3_ENDPOINT` | `http://127.0.0.1:9000` | bronze | S3 API endpoint (MinIO sim) |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` | `silkroute` / `silkroute-secret` | bronze | sim dummies (from repo .env in cloud) |
| `LAKE_BRONZE_BUCKET` | `silkroute-sg-bronze` | bronze | bucket (IaC family name) |
| `BRONZE_ALLOWED_REGIONS` | `CA,SG,CN` | bronze | region allowlist for the bucket |

Versions (pinned): Debezium `2.7.3.Final` (embedded + MySQL connector; Kafka
clients 3.7.x — wire-compatible with the sim broker 3.8.0), AWS SDK v1
`1.12.772`, MySQL 8.0.46 target.

## Cloud parity (ADR-0001)

- Kafka swap (ApsaraMQ for Kafka): `KAFKA_BOOTSTRAP` + `KAFKA_CDC_TOPIC` (dot
  rule) only. In cloud mode the same BronzeWriter consumer reads a topic fed
  by a self-managed Connect cluster or the managed Connect offering — the
  engine is a producer-side deployment detail (ADR-0006).
- OSS swap: `S3_ENDPOINT` to the OSS S3-compat endpoint + `LAKE_BRONZE_BUCKET`
  to the region-pinned bucket family. **Honest gap:** sim proves the S3 API
  surface (path-style, sigv4, putObject); Alibaba OSS's S3-compat layer
  differs in parts (multipart/listing semantics, storage-class headers) — the
  native OSS SDK path stays verify-at-activation (ADR-0002).
- Cloud deploy shape: both mains are ordinary JVM processes; containers/SAE
  packaging is configuration, not code.
- `binlog_row_metadata=MINIMAL` on sim MySQL (verified 2026-09-13): Debezium
  resolves the schema from information_schema, so MINIMAL suffices for these
  tables (recorded in ADR-0006).

## Tests

`./mvnw -f apps/cdc/pom.xml package` — plain JUnit (10): envelope JSON
round-trip, region/table extraction incl. null-before (delete) and null-after
cases, freshness value computed from fixed timestamps (two different source
ts_ms → different values; never a constant), exact 3-key metric-line shape,
metrics-file append, bronze key regex + UTC dt bucketing.
