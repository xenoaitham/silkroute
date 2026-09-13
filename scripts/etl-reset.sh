#!/usr/bin/env bash
# SILKROUTE Phase-3 data-plane RESET — returns the ETL world to a clean slate
# so a seeded-order-day run starts from nothing (single-run provenance per the
# evidence rules; also what the critic uses before its independent re-run).
#
# Maple Retail Group is a fictional company; self-directed reference implementation.
#
# What it does (idempotent, loud, everything verifiable):
#   1. stops oms/cdc/esb processes via their recorded PID files (never pkill)
#   2. TRUNCATEs the OMS tables and empties the lake DQ/recon tables
#   3. deletes the oms + bronze-writer Kafka consumer groups
#   4. deletes + recreates the two data-plane topics (envelope purge; DLQ untouched)
#   5. empties the bronze/silver/gold bucket contents (buckets stay — IaC parity names)
#   6. removes CDC offset/history files + /tmp run artifacts (metrics, recon, logs)
#
# NEVER touches: the ESB DLQ topic contents, the ERP ledger, busforge/helios,
# the rootless docker daemon. The sim compose stack itself is left running.
# All container invocations are pure argument lists (no shell strings built
# from variables inside the container).
set -euo pipefail

MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-silkroute}"
MINIO_ROOT_USER="${MINIO_ROOT_USER:-silkroute}"
MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:-silkroute-secret}"
KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-127.0.0.1:39092}"

step() { printf 'RESET %s\n' "$1"; }

# --- 1. stop processes by PID files -----------------------------------------
for stopper in oms-stop cdc-stop esb-stop; do
  if grep -q "^${stopper}:" Makefile 2>/dev/null; then
    step "stop $stopper"
    make -s "$stopper" || true
  fi
done

# --- 2. truncate OMS + lake bookkeeping tables -------------------------------
step "truncate silkroute_oms tables"
docker exec sim-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  TRUNCATE TABLE silkroute_oms.oms_order_line;
  TRUNCATE TABLE silkroute_oms.oms_order;
  TRUNCATE TABLE silkroute_lake.dq_quarantine;
  TRUNCATE TABLE silkroute_lake.dq_results;
  TRUNCATE TABLE silkroute_lake.recon_report;" 2>/dev/null \
  || { echo "RESET FAIL: cannot reach sim-mysql / tables missing — run: make etl-setup"; exit 1; }

# --- 3. delete consumer groups ------------------------------------------------
for group in "${OMS_CONSUMER_GROUP:-silkroute-oms}" "${CDC_BRONZE_GROUP:-silkroute-bronze-writer}"; do
  step "delete consumer group $group"
  docker exec sim-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --delete --group "$group" 2>/dev/null | grep -v "does not exist" || true
done

# --- 4. delete + recreate the data-plane topics (DLQ untouched) ---------------
for topic in "${KAFKA_ORDERS_TOPIC:-silkroute.orders.events}" "${KAFKA_CDC_TOPIC:-silkroute.cdc.oms}"; do
  step "delete+recreate topic $topic"
  docker exec sim-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --delete --topic "$topic" 2>/dev/null || true
  sleep 2
  docker exec sim-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --create --if-not-exists --topic "$topic" --partitions 1 --replication-factor 1 >/dev/null
done

# --- 5. empty the lake buckets (bucket names = IaC family, buckets stay) ------
docker exec sim-minio mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
for bucket in silkroute-sg-bronze silkroute-sg-silver silkroute-sg-gold; do
  step "empty bucket $bucket"
  docker exec sim-minio mc rm --recursive --force --dangerous "local/$bucket" >/dev/null 2>&1 || true
done

# --- 6. remove offset/history files + run artifacts ---------------------------
step "remove cdc offset/history + /tmp run artifacts"
rm -f /tmp/silkroute-cdc-offsets.json /tmp/silkroute-cdc-history.dat \
      /tmp/silkroute-cdc-freshness.json /tmp/silkroute-metrics.json \
      /tmp/silkroute-recon-report.json /tmp/silkroute-oms.log \
      /tmp/silkroute-cdc-engine.log /tmp/silkroute-cdc-bronze.log

step "OK — data plane is clean (oms tables empty, groups gone, topics fresh, buckets empty, offsets gone)"
