#!/usr/bin/env bash
# SILKROUTE Phase 3 — idempotent data-plane bootstrap (sim mode).
#
# Creates, IF NOT EXISTS (re-running must exit 0 and change nothing):
#   - MySQL databases     silkroute_oms (OMS event store, CDC source)
#                         silkroute_lake (DQ quarantine/results + recon_report)
#   - MySQL users         silkroute_oms (ALL on both DBs — event store + DQ writer)
#                         silkroute_cdc (SELECT on silkroute_oms + REPLICATION SLAVE/CLIENT — the Debezium requirements)
#   - MySQL lake tables   dq_quarantine, dq_results, recon_report
#   - Kafka topic         silkroute.cdc.oms (partitions 1, replication 1)
#   - MinIO buckets       silkroute-sg-bronze / -silver / -gold (IaC bucket-family names)
#
# Verifies binlog preconditions FIRST and REFUSES to continue if the server
# is not CDC-capable (log_bin=ON, binlog_format=ROW, binlog_row_image=FULL).
#
# Credentials come from the repo .env if present (sim-only dummies, see
# .env.example); never hardcoded beyond the documented defaults.
#
# Maple Retail Group is fictional; self-directed reference implementation.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# ---- credentials from .env (if present) + documented defaults ---------------
if [ -f "$REPO_ROOT/.env" ]; then
  # shellcheck disable=SC1091
  set -a; source "$REPO_ROOT/.env"; set +a
fi
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-silkroute}"
MINIO_ROOT_USER="${MINIO_ROOT_USER:-silkroute}"
MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:-silkroute-secret}"
OMS_DB_PASSWORD="${OMS_DB_PASSWORD:-oms-pass-2026}"
CDC_DB_PASSWORD="${CDC_DB_PASSWORD:-cdc-pass-2026}"

MYSQL_CONTAINER="${MYSQL_CONTAINER:-sim-mysql}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-sim-kafka}"
MINIO_CONTAINER="${MINIO_CONTAINER:-sim-minio}"
KAFKA_BOOTSTRAP_INNER="${KAFKA_BOOTSTRAP_INNER:-127.0.0.1:39092}"

mysql_exec() {
  docker exec "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B "$@" 2>/dev/null
}

pass() { echo "ETL-SETUP PASS: $*"; }
fail() { echo "ETL-SETUP FAIL: $*" >&2; exit 1; }

# ---- step 0: the sim stack must be up --------------------------------------
for c in "$MYSQL_CONTAINER" "$KAFKA_CONTAINER" "$MINIO_CONTAINER"; do
  docker ps --format '{{.Names}}' | grep -qx "$c" || fail "container $c is not running — boot the sim stack first: make up"
done
pass "sim containers running ($MYSQL_CONTAINER, $KAFKA_CONTAINER, $MINIO_CONTAINER)"

# ---- step 1: binlog preconditions (the CDC gate) ---------------------------
log_bin=$(mysql_exec -e "SHOW VARIABLES LIKE 'log_bin'" | awk '{print $2}')
binlog_format=$(mysql_exec -e "SHOW VARIABLES LIKE 'binlog_format'" | awk '{print $2}')
binlog_row_image=$(mysql_exec -e "SHOW VARIABLES LIKE 'binlog_row_image'" | awk '{print $2}')
[ "$log_bin" = "ON" ] || fail "MySQL log_bin=$log_bin (must be ON) — the CDC engine cannot capture without the binlog"
[ "$binlog_format" = "ROW" ] || fail "MySQL binlog_format=$binlog_format (must be ROW) — Debezium requires row-based binlog"
[ "$binlog_row_image" = "FULL" ] || fail "MySQL binlog_row_image=$binlog_row_image (must be FULL) — the lake needs complete before/after images"
pass "binlog preconditions: log_bin=ON binlog_format=ROW binlog_row_image=FULL"

# ---- step 2: databases ------------------------------------------------------
mysql_exec -e "CREATE DATABASE IF NOT EXISTS silkroute_oms CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
mysql_exec -e "CREATE DATABASE IF NOT EXISTS silkroute_lake CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
pass "databases ensured: silkroute_oms, silkroute_lake"

# ---- step 3: users + grants (idempotent; password re-asserted every run) ----
mysql_exec -e "CREATE USER IF NOT EXISTS 'silkroute_oms'@'%' IDENTIFIED BY '$OMS_DB_PASSWORD'"
mysql_exec -e "ALTER USER 'silkroute_oms'@'%' IDENTIFIED BY '$OMS_DB_PASSWORD'"
mysql_exec -e "GRANT ALL PRIVILEGES ON silkroute_oms.* TO 'silkroute_oms'@'%'"
mysql_exec -e "GRANT ALL PRIVILEGES ON silkroute_lake.* TO 'silkroute_oms'@'%'"
mysql_exec -e "CREATE USER IF NOT EXISTS 'silkroute_cdc'@'%' IDENTIFIED BY '$CDC_DB_PASSWORD'"
mysql_exec -e "ALTER USER 'silkroute_cdc'@'%' IDENTIFIED BY '$CDC_DB_PASSWORD'"
mysql_exec -e "GRANT SELECT ON silkroute_oms.* TO 'silkroute_cdc'@'%'"
# LOCK TABLES + RELOAD are Debezium's documented MySQL snapshot privileges
# (consistent-snapshot step: LOCK TABLES + FLUSH TABLES WITH READ LOCK); the
# engine failed twice on these until granted (S9 war story). RDS-parity note:
# managed MySQL that cannot grant RELOAD uses snapshot.locking.mode=none —
# an env-indirected knob on the engine, not a code change.
mysql_exec -e "GRANT LOCK TABLES ON silkroute_oms.* TO 'silkroute_cdc'@'%'"
mysql_exec -e "GRANT RELOAD ON *.* TO 'silkroute_cdc'@'%'"
mysql_exec -e "GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'silkroute_cdc'@'%'"
pass "users ensured: silkroute_oms (ALL on both DBs), silkroute_cdc (SELECT + LOCK TABLES + RELOAD + REPLICATION SLAVE/CLIENT)"

# ---- step 4: lake tables (written by the batch job via JDBC) ----------------
mysql_exec silkroute_lake -e "
CREATE TABLE IF NOT EXISTS dq_quarantine (
  run_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(32) NOT NULL,
  table_name VARCHAR(64) NOT NULL,
  row_key VARCHAR(255) NOT NULL,
  reason VARCHAR(512) NOT NULL,
  violating_row_json JSON NULL,
  quaranted_at TIMESTAMP(3) NOT NULL,
  KEY idx_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS dq_results (
  run_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(32) NOT NULL,
  table_name VARCHAR(64) NOT NULL,
  checked BIGINT NOT NULL,
  violations BIGINT NOT NULL,
  status VARCHAR(8) NOT NULL,
  run_at TIMESTAMP(3) NOT NULL,
  KEY idx_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS recon_report (
  run_id VARCHAR(64) NOT NULL,
  business_date DATE NOT NULL,
  orders_source BIGINT NOT NULL,
  orders_gold BIGINT NOT NULL,
  lines_source BIGINT NOT NULL,
  lines_gold BIGINT NOT NULL,
  totals_json JSON NULL,
  all_match TINYINT NOT NULL,
  run_at TIMESTAMP(3) NOT NULL,
  KEY idx_date (business_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;"
pass "lake tables ensured: dq_quarantine, dq_results, recon_report (silkroute_lake)"

# ---- step 5: Kafka topic for the CDC stream ---------------------------------
docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server "$KAFKA_BOOTSTRAP_INNER" \
  --create --if-not-exists --topic silkroute.cdc.oms \
  --partitions 1 --replication-factor 1 >/dev/null
topics=$(docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$KAFKA_BOOTSTRAP_INNER" --list)
echo "$topics" | grep -qx "silkroute.cdc.oms" || fail "topic silkroute.cdc.oms still missing after creation attempt"
pass "kafka topic ensured: silkroute.cdc.oms (1 partition, RF 1)"
if ! echo "$topics" | grep -qx "silkroute.orders.events"; then
  echo "ETL-SETUP WARN: silkroute.orders.events (the ESB success topic) is missing — boot the ESB once (make esb-run) so its topic is created" >&2
else
  pass "kafka topic present: silkroute.orders.events (ESB success events — the OMS input)"
fi

# ---- step 6: MinIO buckets (mirror the IaC bucket-family names) -------------
docker exec "$MINIO_CONTAINER" mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null 2>&1
for bucket in silkroute-sg-bronze silkroute-sg-silver silkroute-sg-gold; do
  docker exec "$MINIO_CONTAINER" mc mb --ignore-existing "local/$bucket" >/dev/null
  docker exec "$MINIO_CONTAINER" mc ls "local/$bucket" >/dev/null 2>&1 || fail "bucket $bucket not accessible after creation"
  pass "minio bucket ensured: $bucket"
done

echo "ETL-SETUP PASS: data plane ready (idempotent re-run: nothing changed)"
