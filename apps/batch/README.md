# apps/batch — Spark bronze→silver→gold + DQ + reconciliation

Maple Retail Group is a **fictional** company; this is a self-directed
reference implementation (Project SILKROUTE, Phase 3, MASTER_PROMPT §3
component 3, ADR-0006 decisions 3+4).

CLI (one fat jar, `local[*]`, UI disabled, all binds loopback — zero new
listening ports):

```bash
make batch-run ARGS="full --business-date 2026-09-13"
make batch-run ARGS="recon-only"          # negative-control instrument (no rebuild)
make batch-run ARGS="selftest"            # DQ negative control, pure JVM, no services
# or: make batch-run full | recon-only | selftest
```

Exit codes: `0` = success/recon-allMatch; `2` = recon mismatch or a missed
selftest rule; `1` = any other error (loud stack trace).

## `full` pipeline

1. **Bronze read** — JSONL objects under
   `s3a://${LAKE_BRONZE_BUCKET}/region=*/table=*/dt=<yyyyMMdd>/*`. The dt=
   partition is the **UTC date of the source-commit ts** (ADR-0006 key
   format). Bronze is never modified.
2. **Silver** — parse envelopes (ops c/r/u with an after-image), latest-per-PK
   dedup keyed (table, PK, source.ts_ms) with a deterministic tiebreak, typed
   columns: money = **BIGINT minor units + currency STRING** (C5 — no
   FloatType/DoubleType anywhere in this module), lineage column
   `bronze_object = input_file_name()`. Written as Parquet to
   `s3a://${LAKE_SILVER_BUCKET}/silver/orders` and `/silver/order_lines`,
   partitioned `dt=<date>`.
3. **DQ (before gold)** — pure-Java predicates over a minimal `RowView`
   interface (unit-testable WITHOUT Spark; the Spark layer only adapts rows):
   - `DQ-COMPLETE` orders: order_id, store_id, region, total_amount_minor,
     currency non-null; lines: sku_id, unit_price_minor, currency non-null
     and quantity > 0;
   - `DQ-UNIQUE`: order_id in fact_orders; (order_id, line_no) in
     fact_order_lines;
   - `DQ-REFERENTIAL`: every fact_order_lines.order_id exists in
     fact_orders; every fact_orders.store_id exists in dim_store.
   Violations are EXCLUDED from gold and written to MySQL
   `silkroute_lake.dq_quarantine` (run_id, rule_id, table_name, row_key,
   reason, violating_row_json, `quaranted_at` — column name verbatim from the
   build brief) + `silkroute_lake.dq_results`. **A non-empty quarantine turns
   the reconciliation red by design** — investigate before shipping gold.
4. **Gold** — star schema Parquet under `s3a://${LAKE_GOLD_BUCKET}/gold/`:
   `fact_orders`, `fact_order_lines`, `dim_sku`, `dim_store`, `dim_date`
   (date_key INT yyyyMMdd, cal_date, day_of_week ISO 1–7), all partitioned
   `dt=<date>`. Writes are **idempotent per business date**: dynamic-partition
   overwrite replaces ONLY `dt=<date>` (bronze untouched by construction).
5. **Reconciliation (the AC — must be 100%)** — source = OMS MySQL
   (`silkroute_oms`) via Spark JDBC, scoped to the business date by the UTC
   calendar date of `ingested_at`; target = the gold Parquet just built,
   re-read from the bucket. Aggregates: orders count, lines count,
   SUM(total_amount_minor) and SUM(line_total_minor) per currency (C5: never
   summed across currencies). Report JSON to
   `${RECON_REPORT_PATH:/tmp/silkroute-recon-report.json}`:

   ```json
   {"runId":"run-...","businessDate":"2026-09-13",
    "orders":{"source":12,"gold":12,"match":true},
    "lines":{"source":12,"gold":12,"match":true},
    "totalsByCurrency":[{"currency":"CAD","sourceMinor":25000,"goldMinor":25000,"match":true}],
    "lineTotalsByCurrency":[...],
    "allMatch":true,"generatedAt":"..."}
   ```

   (`totalsByCurrency` carries the ORDER money — the pinned shape;
   `lineTotalsByCurrency` is an ADDITIVE key carrying the line money so both
   SUMs from the brief are actually reconciled.) Also appended to
   `silkroute_lake.recon_report`.

## `recon-only` — the negative control

Loads gold from the bucket + reads the source, produces the report, same
exit semantics. Drop a gold row → recon-only goes red (exit 2).

## `selftest` — the DQ negative control

ONE JVM process, no MySQL/MinIO/Spark: fixtures with KNOWN violations run
through the SAME predicate classes. Prints
`DQ-SELFTEST rule=DQ-... table=... checked=N violations=1 caught=true` per
rule-table combo and exits 0 iff EVERY rule caught its fixture — a check that
cannot fail is not a check.

## Metrics (C4/C5 — exact contract shapes)

At the end of full/recon-only:

```
{"metric":"batch_completion","value":37,"pipeline":"batch"}
BATCH-WINDOW businessDate=2026-09-13 completedAtSGT=2026-09-14T05:52:10 Asia/Singapore windowEndSGT=06:00 withinWindow=true
```

- `batch_completion` = end-to-end wall seconds (integer), printed + appended
  to `${METRICS_FILE}` when set.
- `BatchWindowEvaluator` — `ZoneId "Asia/Singapore"` hardcoded, window end
  06:00, injectable `java.time.Clock`; unit tests include the negative case
  (a fixed clock past 06:00 → `withinWindow=false`). Honest labeling: in sim
  the job runs on demand, so `withinWindow` reports the ACTUAL run time vs
  the boundary; the unit tests prove the window logic itself.

## Business-date semantics (C4/C5, stated plainly)

- Default business date = **yesterday Asia/Singapore** (ZoneId pinned in
  `BusinessDates`); a `--business-date YYYY-MM-DD` arg selects one day.
- The selected date maps to the bronze/silver/gold `dt=` partition with the
  SAME yyyyMMdd digits — i.e. the UTC date of the source-commit ts (ADR-0006
  key format). In the T+1 flow (run ~05:00 SGT on D+1 for date D) the
  `dt=D` partition covers the source-commits of UTC day D; the SGT window
  evaluator is the scheduling layer on top. Both facts are pinned in code,
  not left to the JVM default zone.

## Knobs (every one is `${VAR:default}` — ADR-0001)

| Env var | Default | Meaning |
|---|---|---|
| `SPARK_MASTER` | `local[*]` | Spark master override |
| `S3_ENDPOINT` | `http://127.0.0.1:9000` | S3 API endpoint (s3a wiring) |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` | `silkroute` / `silkroute-secret` | sim dummies |
| `LAKE_BRONZE_BUCKET` | `silkroute-sg-bronze` | bronze (read-only here) |
| `LAKE_SILVER_BUCKET` | `silkroute-sg-silver` | silver Parquet |
| `LAKE_GOLD_BUCKET` | `silkroute-sg-gold` | gold Parquet + recon target |
| `RECON_JDBC_URL` | `jdbc:mysql://127.0.0.1:3306/silkroute_oms?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true` | source-side read |
| `RECON_DB_USER` / `RECON_DB_PASSWORD` | `silkroute_oms` / `oms-pass-2026` | source-side creds |
| `DQ_JDBC_URL` | `jdbc:mysql://127.0.0.1:3306/silkroute_lake?...` (same params) | DQ/recon bookkeeping DB |
| `DQ_DB_USER` / `DQ_DB_PASSWORD` | `silkroute_oms` / `oms-pass-2026` | writer creds (granted by etl-setup) |
| `RECON_REPORT_PATH` | `/tmp/silkroute-recon-report.json` | report JSON artifact |
| `METRICS_FILE` | (unset) | if set, APPEND metric lines (evidence artifact) |

Versions: Spark 3.5.1 (Scala 2.13 artifacts, scope compile), hadoop-aws
3.3.4 + aws-java-sdk-bundle 1.12.262, mysql-connector-j 8.4.0. Fat jar via
maven-shade + ServicesResourceTransformer (the classic `java -jar` Spark
failure is service-file first-wins) + manifest `Add-Opens` (JEP 261) so the
jar runs on JDK 17 without spark-submit flags. Run with JDK 17 when present
(`make batch-run` picks `/usr/lib/jvm/java-17-openjdk-amd64` automatically).

Driver-side DQ scale note (honest): the predicates run driver-side over the
day's silver rows — sized for the sim's volumes; the cloud path would push
uniqueness/referential down to Spark joins using the same rule definitions
(config, not predicate, changes).

## Cloud parity (ADR-0001)

- OSS swap: `S3_ENDPOINT` → OSS S3-compat endpoint + the three bucket vars.
  **Honest gap:** sim proves the s3a/S3 surface; OSS's S3-compat layer differs
  (multipart, listing semantics) — verify-at-activation for the native OSS
  SDK path (ADR-0002).
- RDS swap: `RECON_JDBC_URL` + `DQ_JDBC_URL` (+ creds).
- Scheduler: in cloud, a Scheduler/FunctionCompute trigger sets
  `--business-date` and calls the same jar; the window evaluator stays the
  source of truth for the 06:00 SGT boundary.

## Tests

`./mvnw -f apps/batch/pom.xml package` — plain JUnit (22, no cluster):
dedup-latest reference semantics, line-total integer math, window evaluator
(incl. the 06:00 negative case), recon-aggregate math on in-memory rows (per
currency, one-side currency, line-totals array), DQ predicates (each rule
catches its fixture), business-date SGT math.
