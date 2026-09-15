#!/usr/bin/env bash
# Start the whole SilkRoute live demo inside one container (HF Space).
# Order: redis -> kafka (KRaft) -> toxiproxy -> topics -> ERP -> ESB -> relay.
# Every component binds container-local ports that match the apps' defaults,
# so the jars boot with zero configuration overrides.
set -euo pipefail

LOG=/tmp/demo-start.log
say() { echo "[start] $*" | tee -a "$LOG"; }

# --- redis (ESB idempotency; default port matches application.yml) ----------
redis-server --port 16379 --dir /data/redis --daemonize yes \
  || { echo "redis failed"; exit 1; }
say "redis up on 16379"

# --- kafka (KRaft single node) ----------------------------------------------
export CLUSTER_ID=CU-RSDF8RAWhB7Bh7dufAw
mkdir -p /data/kafka
cat > /tmp/kafka.properties <<EOF
process.roles=broker,controller
node.id=1
controller.quorum.voters=1@127.0.0.1:9093
listeners=PLAINTEXT://:39092,CONTROLLER://:9093
advertised.listeners=PLAINTEXT://127.0.0.1:39092
controller.listener.names=CONTROLLER
listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
inter.broker.listener.name=PLAINTEXT
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
group.initial.rebalance.delay.ms=0
log.dirs=/data/kafka
EOF
/opt/kafka/bin/kafka-storage.sh format --ignore-formatted \
  -t "$CLUSTER_ID" -c /tmp/kafka.properties >> "$LOG" 2>&1
/opt/kafka/bin/kafka-server-start.sh -daemon /tmp/kafka.properties
say "kafka starting on 39092"

# --- toxiproxy (fault-injection path for the ERP) ---------------------------
/usr/local/bin/toxiproxy-server --host 127.0.0.1 --port 18474 >> "$LOG" 2>&1 &
sleep 1
say "toxiproxy up on 18474"

# --- wait for kafka, create the topics --------------------------------------
for i in $(seq 1 60); do
  if /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server 127.0.0.1:39092 >/dev/null 2>&1; then break; fi
  sleep 2
done
/opt/kafka/bin/kafka-topics.sh --bootstrap-server 127.0.0.1:39092 --create --if-not-exists \
  --topic silkroute.orders.events --partitions 1 --replication-factor 1 >> "$LOG" 2>&1
/opt/kafka/bin/kafka-topics.sh --bootstrap-server 127.0.0.1:39092 --create --if-not-exists \
  --topic silkroute.esb.dlq --partitions 1 --replication-factor 1 >> "$LOG" 2>&1
say "kafka ready, topics created"

# --- the simulated legacy SOAP ERP ------------------------------------------
java $ERP_JAVA_OPTS -jar /opt/app/legacy-erp.jar > /tmp/erp.log 2>&1 &
for i in $(seq 1 60); do
  curl -sf http://127.0.0.1:18080/actuator/health 2>/dev/null | grep -q UP && break
  sleep 2
done
say "erp up on 18080 (seeded stock + prices)"

# --- the erp proxy for the ESB's calls (fault injection path) ---------------
curl -s -X POST http://127.0.0.1:18474/proxies -H 'Content-Type: application/json' \
  -d '{"name":"erp","listen":"127.0.0.1:18180","upstream":"127.0.0.1:18080","enabled":true}' >/dev/null
say "toxiproxy erp proxy 18180 -> 18080"

# --- the ESB -----------------------------------------------------------------
java $ESB_JAVA_OPTS -jar /opt/app/esb.jar > /tmp/esb.log 2>&1 &
for i in $(seq 1 60); do
  curl -sf http://127.0.0.1:18082/actuator/health 2>/dev/null | grep -q UP && break
  sleep 2
done
say "esb up on 18081 (saga + breaker + retry + idempotency)"

# --- the demo relay (foreground = the container's live process) --------------
say "relay serving the playground on ${DEMO_RELAY_HOST}:${DEMO_RELAY_PORT}"
exec python3 /opt/app/relay.py
