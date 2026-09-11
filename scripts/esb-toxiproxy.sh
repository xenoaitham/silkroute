#!/usr/bin/env bash
# SILKROUTE Phase 2 — BUILD-ESB helper (apps/esb).
#
# Idempotent management of the toxiproxy proxy "erp" on the ESB fault-injection
# toxiproxy (127.0.0.1:18474, host-network container sim-esb-toxiproxy). ALL ERP
# calls made by the ESB must go through this proxy so that network faults
# (latency/timeout/blackhole) are injectable for the saga/retry/circuit-breaker
# acceptance flows.
#
#   listen   127.0.0.1:18180   (ESB ERP_BASEURL default: http://127.0.0.1:18180)
#   upstream 127.0.0.1:18080   (legacy ERP sim, app port)
#
# Why a SECOND toxiproxy (and not the sim stack's 8474 one): the ERP jar binds
# host 127.0.0.1:18080. sim-toxiproxy is bridge-mode — from inside it, 127.0.0.1
# is the container itself and this rootless setup has no slirp host alias, so it
# cannot upstream to the host. sim-esb-toxiproxy runs network_mode: host, so its
# proxy binds REAL host loopback (127.0.0.1:18180, loopback-only).
#
# NOTE: unrelated toxiproxies exist too (sim stack 8474, busforge 8475) — never touch those.
# NEVER restart the docker daemon or other stacks from here.
#
# usage:
#   esb-toxiproxy.sh            # ensure the proxy exists, print its state
#   esb-toxiproxy.sh reset      # remove ALL toxics from proxy "erp"
#   esb-toxiproxy.sh remove     # delete proxy "erp" entirely
#
# Env: TOXIPROXY_API (default http://127.0.0.1:18474),
#      ESB_PROXY_NAME (default erp), ESB_PROXY_LISTEN (default 127.0.0.1:18180),
#      ESB_PROXY_UPSTREAM (default 127.0.0.1:18080).
set -euo pipefail

API="${TOXIPROXY_API:-http://127.0.0.1:18474}"
PROXY="${ESB_PROXY_NAME:-erp}"
LISTEN="${ESB_PROXY_LISTEN:-127.0.0.1:18180}"
UPSTREAM="${ESB_PROXY_UPSTREAM:-127.0.0.1:18080}"

curl_json() {
  # quiet curl with a short timeout; body on stdout, http code in $CURL_CODE
  curl -sS -m 5 -o /tmp/esb-toxiproxy.out -w '%{http_code}' "$@"
}

proxy_exists() {
  local code
  code="$(curl_json "${API}/proxies/${PROXY}")" || return 1
  [ "$code" = "200" ]
}

ensure_proxy() {
  if proxy_exists; then
    echo "toxiproxy proxy '${PROXY}' already present"
    return 0
  fi
  local code
  code="$(curl_json -X POST -H 'Content-Type: application/json' \
    -d "{\"name\":\"${PROXY}\",\"listen\":\"${LISTEN}\",\"upstream\":\"${UPSTREAM}\",\"enabled\":true}" \
    "${API}/proxies")" || code="000"
  case "$code" in
    201|200) echo "toxiproxy proxy '${PROXY}' created: listen=${LISTEN} upstream=${UPSTREAM}" ;;
    409)     echo "toxiproxy proxy '${PROXY}' already present (race)";;
    *) echo "ERROR: creating proxy failed (HTTP $code): $(cat /tmp/esb-toxiproxy.out 2>/dev/null)" >&2
       exit 1 ;;
  esac
}

print_state() {
  local code
  code="$(curl_json "${API}/proxies/${PROXY}")" || code="000"
  if [ "$code" != "200" ]; then
    echo "ERROR: cannot read proxy state (HTTP $code)" >&2
    exit 1
  fi
  # print compactly; python3 -m json.tool when available, raw otherwise
  if command -v python3 >/dev/null 2>&1; then
    python3 -m json.tool < /tmp/esb-toxiproxy.out
  else
    cat /tmp/esb-toxiproxy.out
  fi
}

reset_toxics() {
  # remove every toxic attached to the proxy (idempotent: none -> ok)
  local code body toxic names
  code="$(curl_json "${API}/proxies/${PROXY}/toxics")" || code="000"
  if [ "$code" != "200" ]; then
    echo "ERROR: cannot list toxics (HTTP $code)" >&2
    exit 1
  fi
  body="$(cat /tmp/esb-toxiproxy.out)"
  names="$(printf '%s' "$body" | python3 -c 'import json,sys; print("\n".join(t["name"] for t in json.load(sys.stdin)))' 2>/dev/null || true)"
  if [ -z "${names}" ]; then
    echo "proxy '${PROXY}': no toxics present (already clean)"
    return 0
  fi
  while IFS= read -r toxic; do
    [ -n "$toxic" ] || continue
    code="$(curl_json -X DELETE "${API}/proxies/${PROXY}/toxics/${toxic}")" || code="000"
    echo "removed toxic '${toxic}' (HTTP $code)"
  done <<< "$names"
}

remove_proxy() {
  local code
  code="$(curl_json -X DELETE "${API}/proxies/${PROXY}")" || code="000"
  case "$code" in
    204|200) echo "proxy '${PROXY}' removed" ;;
    404)     echo "proxy '${PROXY}' absent (nothing to remove)" ;;
    *) echo "ERROR: removing proxy failed (HTTP $code): $(cat /tmp/esb-toxiproxy.out 2>/dev/null)" >&2; exit 1 ;;
  esac
}

case "${1:-ensure}" in
  ensure|"")
    ensure_proxy
    print_state
    ;;
  reset)
    ensure_proxy
    reset_toxics
    print_state
    ;;
  remove)
    remove_proxy
    ;;
  *)
    echo "usage: $0 [ensure|reset|remove]" >&2
    exit 2
    ;;
esac
