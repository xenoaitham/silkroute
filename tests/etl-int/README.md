# tests/etl-int - data-plane ETL data-plane integration suite

the test pass suite for the SILKROUTE ETL data plane (apps/modern-oms, apps/cdc,
apps/batch). It is a bash orchestrator in the `scripts/smoke.sh` house style:
loud, falsifiable, explicit `|| fail` assertions (no `&&`-lists that can
silently pass, no command substitutions that swallow exit codes). It drives
the REAL pipeline - orders go through the live ESB (`make seed-day`), CDC is
the real embedded Debezium engine, bronze is real MinIO, batch is the real
Spark local job. No synthetic SQL is used to create data; the only direct SQL
is the recon negative control, which injects a phantom row ON PURPOSE and
deletes it again (falsifiability instrument, same one as
`evidence/runs/E-030-seeded-day-pipeline.txt`).

## Run

One command, from the repo root:

```bash
bash tests/etl-int/run.sh
```

Exit code 0 only if ALL six scenarios print their `PASS <name>` line; the
first failed assertion prints `FAIL: ...` and exits non-zero immediately.

The full transcript is tee'd to `/tmp/s9-etl-int-run.txt` (deliberately NOT
committed; copy what you need into `evidence/`).

Approximate runtime: 6-10 minutes (Spark local batch runs dominate).

## What each scenario proves

1. **`1-clean-slate-pipeline`** - from `make etl-reset`, boots
 esb/oms/cdc via the PID-file targets, seeds 12 real orders (asserts
 12/12 HTTP 201 in the seed summary), waits for CDC quiescence, runs
 `make batch-run ARGS="full --business-date <today-UTC>"` and jq-asserts
 the recon report: orders source==gold==12, lines source==gold==12,
 `allMatch==true`.
 *Proves:* the seeded-order-day acceptance - gold tables with 100%
 reconciliation .

2. **`2-replay-safety`** - with scenario 1's state (no reset): stops the
 OMS, resets its Kafka consumer-group offsets to earliest so the SAME
 `silkroute.orders.events` envelopes are re-consumed, restarts the OMS.
 Asserts `REPLAY-SKIP` lines appeared, zero new `OMS-ORDER-STORED` lines,
 and OMS row counts still 12/12.
 *Proves:* re-execution is a no-op (ON-DUPLICATE-KEY business-key guard,
 ADR-0006 decision 2) - replays never duplicate.

3. **`3-cdc-kill-mid-stream`** - chaos-lite: reset, boot, seed 6 orders,
 `make cdc-stop`, seed 6 MORE while CDC is DOWN (they land in the OMS but
 are not captured), assert OMS has 12/12, restart CDC. The engine resumes
 from its saved binlog offset file (cdc-stop does NOT delete it) and must
 capture the 6 missed inserts. Asserts the catch-up CDC-CAPTURE lines,
 that bronze record counts per table equal the OMS rows (12/12) with no
 duplicate topic-partition-offset (parsed from BRONZE-LAND object names),
 and `batch-run full` recon `allMatch=true`.
 *Proves:* no loss and no duplication end-to-end across a mid-stream CDC
 outage (C4 durability).

4. **`4-freshness-metric-contract`** - from the still-running engine
 (scenario 3's restart window), asserts >=2 `CDC-METRIC` freshness lines
 with >=2 DISTINCT values, and that every payload matches the EXACT
 contract regex
 `^\{"metric":"cdc_freshness_seconds","value":[0-9]+(\.[0-9]+)?,"pipeline":"cdc"\}$`.
 *Proves:* the freshness metric is a real measurement, not a constant, and
 honors the emitted contract (C4 monitoring input).

5. **`5-dq-selftest-and-recon-falsifiability`** - `make batch-run
 ARGS=selftest` must exit 0 with `DQ-SELFTEST-OK`; then the phantom-row
 recon negative control: INSERT a `NEG-CONTROL` row into `oms_order` via
 docker exec (delta matches the E-030 instrument: CAD +9999 minor; E-030
 does not carry the literal INSERT, so it is reconstructed against the
 `oms_order` DDL) - `recon-only` MUST exit 2 with `allMatch=false`;
 DELETE it - `recon-only` exits 0 and the report is green again.
 *Proves:* DQ catches violations, and the reconciliation actually FAILS
 when source and gold disagree (a recon that cannot go red proves nothing).

6. **`6-c1-cn-masking-bronze`** - greps (host-side, never inside the minio
 container) every bronze object: 0 occurrences of plaintext `cust-cn-`
 anywhere in bronze, and >=1 `"customer_ref":"msk-` in `region=CN`
 objects (scenario 3's run seeds 3 CN orders).
 *Proves:* C1 residency/masking - the lake never sees plaintext CN
 customer refs.

## Hygiene guarantees

- Processes are started/stopped ONLY via the make PID-file targets
 (`esb-run/oms-run/cdc-run`, `esb-stop/oms-stop/cdc-stop`); NEVER pkill.
- A trap stops esb+oms+cdc on ANY exit path (success, failure, interrupt).
- On success the suite ends with `make etl-reset` so the next run and the
 review always start from a known clean slate.
- Only ever touches the sim compose stack (sim-mysql / sim-kafka /
 sim-minio / sim-* proxies); never busforge/helios; no new listening ports.
- C1 inside the suite itself: the suite never prints or stores plaintext CN
 refs - it only asserts their ABSENCE (`cust-cn-` count == 0).

## Notes

- The business date is computed as `date -u +%F` (bronze `dt` partition =
 UTC date of the seed - C5 timezone-explicit).
- No Makefile target is wired for this suite yet: `make etl-int` (or
 similar) is requested; until then run the script directly.
