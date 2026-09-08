#!/usr/bin/env bash
# SILKROUTE sim smoke — asserts connectivity to ALL five sim services.
# Exits non-zero on ANY failure (every assertion is `|| fail`, immune to set -e &&-list exemptions).
set -euo pipefail
cd "$(dirname "$0")/.."

command -v curl >/dev/null || { echo "ERROR: curl is required by smoke"; exit 1; }

fail() { echo "FAIL: $1" >&2; exit 1; }
ok()   { echo "$1"; }

# 1. redis — PING
docker compose exec -T redis redis-cli ping | grep -q PONG \
  || fail "redis PING did not return PONG"
ok "redis     OK (PING->PONG)"

# 2. mysql — liveness + real authenticated version query (password from container env, never hardcoded)
docker compose exec -T mysql sh -c 'mysqladmin ping -h 127.0.0.1 -p"$MYSQL_ROOT_PASSWORD" 2>/dev/null' | grep -q alive \
  || fail "mysqladmin ping did not report alive"
ver="$(docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "SELECT VERSION();"' 2>/dev/null | tail -1)"
[[ "$ver" == 8.* ]] || fail "mysql VERSION() expected 8.x, got: '$ver'"
ok "mysql     OK (VERSION()=$ver)"

# 3. kafka — broker API reachable on the internal listener
docker compose exec -T kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1 \
  || fail "kafka broker API unreachable on localhost:9092 (in-container)"
ok "kafka     OK (broker-api)"

# 4. minio — health endpoint on the published host port
curl -fsS http://127.0.0.1:9000/minio/health/live >/dev/null \
  || fail "minio health endpoint not live on 127.0.0.1:9000"
ok "minio     OK (health/live)"

# 5. toxiproxy — API version on the published host port
curl -fsS http://127.0.0.1:8474/version | grep -q 2.11.0 \
  || fail "toxiproxy /version did not report 2.11.0"
ok "toxiproxy OK (/version=2.11.0)"

echo "smoke OK (all 5 services) in ${SECONDS}s"
