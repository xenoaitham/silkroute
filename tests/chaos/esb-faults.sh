#!/usr/bin/env bash
# SILKROUTE Phase 2 — toxiproxy fault recipes for the ERP path.
#
# All ERP traffic from the ESB flows through the toxiproxy proxy "erp"
# (listen 127.0.0.1:18180 -> upstream 127.0.0.1:18080 on the HOST network via
# container sim-esb-toxiproxy (API 18474, network_mode: host);
# REST API http://127.0.0.1:18474). This is the single human/CI entrypoint for
# fault injection; tests/esb-int uses the identical calls via its Toxi helper.
#
# Pure curl + jq. Usage:
#   ./tests/chaos/esb-faults.sh ensure                 # create proxy if missing
#   ./tests/chaos/esb-faults.sh status                 # show proxy + toxics
#   ./tests/chaos/esb-faults.sh latency 600            # add latency toxic "lat"
#   ./tests/chaos/esb-faults.sh timeout 500            # connection_timeout toxic "tmo"
#   ./tests/chaos/esb-faults.sh reset 100 75           # bandwidth toxic "rst" (rate, %)
#   ./tests/chaos/esb-faults.sh remove lat             # delete a toxic
#   ./tests/chaos/esb-faults.sh disable                # hard down (connection refused)
#   ./tests/chaos/esb-faults.sh enable                 # heal
#   ./tests/chaos/esb-faults.sh clean                  # remove all toxics + enable
set -euo pipefail

API="${TOXIPROXY_API:-http://127.0.0.1:18474}"
PROXY="${TOXIPROXY_PROXY:-erp}"
LISTEN="${ERP_PROXY_LISTEN:-127.0.0.1:18180}"
UPSTREAM="${ERP_UPSTREAM:-127.0.0.1:18080}"

command -v jq >/dev/null || { echo "ERROR: jq is required"; exit 1; }

say() { printf '[esb-faults] %s\n' "$*" >&2; }  # stderr: keeps stdout pipe-clean (status | jq)

proxy_url() { echo "$API/proxies/$PROXY"; }

ensure() {
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' "$API/version" || true)
  [ "$code" = "200" ] || { echo "ERROR: toxiproxy API not reachable at $API (HTTP $code) — run: make up"; exit 1; }
  code=$(curl -s -o /dev/null -w '%{http_code}' "$(proxy_url)" || true)
  if [ "$code" = "404" ]; then
    curl -s -X POST "$API/proxies" -H 'Content-Type: application/json' \
      -d "{\"name\":\"$PROXY\",\"listen\":\"$LISTEN\",\"upstream\":\"$UPSTREAM\",\"enabled\":true}" >/dev/null
    say "created proxy $PROXY $LISTEN -> $UPSTREAM"
  elif [ "$code" = "200" ]; then
    say "proxy $PROXY already exists"
  else
    echo "ERROR: unexpected HTTP $code from $(proxy_url)"; exit 1
  fi
}

status() {
  ensure
  curl -s "$(proxy_url)" | jq '{name, listen, upstream, enabled,
    toxics: [.toxics[] | {name, type, stream, attributes}]}'
}

latency() { ensure; toxic_create "lat" "latency" "{\"latency\": $1}"; }
timeout_toxic() { ensure; toxic_create "tmo" "timeout" "{\"timeout\": $1}"; }

toxic_create() {
  local name="$1" type="$2" attrs="$3"
  remove "$name" || true
  local code
  code=$(curl -s -o /tmp/esb-faults.$$ -w '%{http_code}' -X POST "$(proxy_url)/toxics" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"$name\",\"type\":\"$type\",\"attributes\":$attrs}")
  [ "$code" = "200" ] || { echo "ERROR: toxic create $type failed (HTTP $code): $(cat /tmp/esb-faults.$$)"; rm -f /tmp/esb-faults.$$; exit 1; }
  rm -f /tmp/esb-faults.$$
  say "toxic $name ($type, $attrs) active on $PROXY"
}

remove() {
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$(proxy_url)/toxics/$1")
  case "$code" in
    204) say "toxic $1 removed" ;;
    404) say "toxic $1 absent" ;;
    *)   echo "ERROR: DELETE toxic $1 -> HTTP $code"; exit 1 ;;
  esac
}

disable() { ensure; curl -s -X POST "$(proxy_url)" -H 'Content-Type: application/json' -d '{"enabled":false}' >/dev/null; say "proxy $PROXY DISABLED (ERP hard down)"; }
enable()  { ensure; curl -s -X POST "$(proxy_url)" -H 'Content-Type: application/json' -d '{"enabled":true}'  >/dev/null; say "proxy $PROXY enabled"; }

clean() {
  ensure
  for t in $(curl -s "$(proxy_url)" | jq -r '.toxics[]?.name'); do
    remove "$t"
  done
  enable
}

case "${1:-help}" in
  ensure)  ensure ;;
  status)  status ;;
  latency) [ $# -ge 2 ] || { echo "usage: $0 latency <ms>"; exit 1; }; latency "$2" ;;
  timeout) [ $# -ge 2 ] || { echo "usage: $0 timeout <ms>"; exit 1; }; timeout_toxic "$2" ;;
  reset|clean) clean ;;
  remove)  [ $# -ge 2 ] || { echo "usage: $0 remove <toxic-name>"; exit 1; }; remove "$2" ;;
  disable) disable ;;
  enable)  enable ;;
  *) sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//' ;;
esac
