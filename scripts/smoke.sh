#!/usr/bin/env bash
# SILKROUTE sim smoke — asserts connectivity to ALL six sim services
# (incl. the Phase-2 esb-toxiproxy), both in-container AND from the host
# (published 127.0.0.1 forwards).
# The host-forward probes exist because rootlesskit forwards are the one
# failure mode this host has produced repeatedly (war stories: dead 8474,
# leaked 29092) — a forward can die while the container stays healthy.
# Exits non-zero on ANY failure (every assertion is `|| fail`, immune to
# set -e &&-list exemptions).
set -euo pipefail
cd "$(dirname "$0")/.."

command -v curl >/dev/null || { echo "ERROR: curl is required by smoke"; exit 1; }

fail() { echo "FAIL: $1" >&2; exit 1; }
ok()   { echo "$1"; }
host_tcp() { timeout 3 bash -c "</dev/tcp/127.0.0.1/$1" 2>/dev/null; }

# 1. redis — host forward + PING
host_tcp 16379 || fail "redis host forward 127.0.0.1:16379 not reachable"
docker compose exec -T redis redis-cli ping | grep -q PONG \
  || fail "redis PING did not return PONG"
ok "redis     OK (host:16379, PING->PONG)"

# 2. mysql — host forward + liveness + real authenticated version query (password from container env, never hardcoded)
host_tcp 3306 || fail "mysql host forward 127.0.0.1:3306 not reachable"
docker compose exec -T mysql sh -c 'mysqladmin ping -h 127.0.0.1 -p"$MYSQL_ROOT_PASSWORD" 2>/dev/null' | grep -q alive \
  || fail "mysqladmin ping did not report alive"
ver="$(docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "SELECT VERSION();"' 2>/dev/null || true | tail -1)"
[[ "$ver" == 8.* ]] || fail "mysql VERSION() expected 8.x, got: '$ver'"
ok "mysql     OK (host:3306, VERSION()=$ver)"

# 3. kafka — HOST listener (the one the ESB will actually use) + broker API in-container
host_tcp 39092 || fail "kafka HOST listener 127.0.0.1:39092 not reachable"
docker compose exec -T kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1 \
  || fail "kafka broker API unreachable on localhost:9092 (in-container)"
ok "kafka     OK (host:39092, broker-api)"

# 4. minio — host forward + health endpoint on the published host port
host_tcp 9000 || fail "minio host forward 127.0.0.1:9000 not reachable"
curl -fsS http://127.0.0.1:9000/minio/health/live >/dev/null \
  || fail "minio health endpoint not live on 127.0.0.1:9000"
ok "minio     OK (host:9000, health/live)"

# 5. toxiproxy — host forward + API version on the published host port
host_tcp 8474 || fail "toxiproxy host forward 127.0.0.1:8474 not reachable"
curl -fsS http://127.0.0.1:8474/version | grep -q 2.11.0 \
  || fail "toxiproxy /version did not report 2.11.0"
ok "toxiproxy OK (host:8474, /version=2.11.0)"

# 6. esb-toxiproxy (Phase 2) — host-network container: the API port IS a host port.
# ALL Phase-2 fault injection binds through it, and a silent death here is exactly
# the rootlesskit failure mode the host-forward probes exist for.
host_tcp 18474 || fail "esb-toxiproxy API 127.0.0.1:18474 not reachable"
curl -fsS http://127.0.0.1:18474/version | grep -q 2.11.0 \
  || fail "esb-toxiproxy /version did not report 2.11.0"
ok "esb-toxiproxy OK (host:18474, /version=2.11.0)"

echo "smoke OK (all 6 services, host forwards + in-container) in ${SECONDS}s"
