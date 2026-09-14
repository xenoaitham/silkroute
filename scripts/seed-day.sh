#!/usr/bin/env bash
# SILKROUTE the data plane - the REAL seeded order day: drive N orders through the
# LIVE ESB REST facade (no synthetic SQL anywhere).
#
# Maple Retail Group is a fictional company; self-directed reference
# implementation. Talks to the sim ESB only (127.0.0.1:18081, loopback).
#
# Store mix (deliberate, multi-currency C5 + masking C1):
#   ~half CA (ST-CA-01, customerRef cust-ca-<i>, CAD)
#   ~a quarter SG (ST-SG-01, customerRef cust-sg-<i>, SGD)
#   ~a quarter CN (ST-CN-01, customerRef cust-cn-<i> - the ESB masks it to
#     msk-* at egress; the lake inherits ONLY the msk-* value)
# Quantities 1-3, SKUs rotating over SKU-0001..SKU-0050 (stays well inside the
# ERP per-SKU stock pool 5..100: ~30 orders x <=3 qty spread over 50 SKUs).
#
# Every accepted (201) order produces exactly ONE ESB success event on
# silkroute.orders.events - the expected-event count is derivable from the
# summary (SEED-DAY expectedEvents=<ok201>).
#
# Usage: scripts/seed-day.sh [N]      # default 30
# Requires ERP (18080) + ESB (18081/18082) running: make esb-run
set -euo pipefail

ERP_URL="${DEMO_ERP_URL:-http://127.0.0.1:18080}"
ESB_URL="${DEMO_ESB_URL:-http://127.0.0.1:18081}"
ESB_HEALTH_URL="${DEMO_ESB_HEALTH_URL:-http://127.0.0.1:18082}"
N="${1:-30}"

command -v curl >/dev/null || { echo "SEED-DAY ERROR: curl is required"; exit 1; }
command -v jq >/dev/null    || { echo "SEED-DAY ERROR: jq is required"; exit 1; }

# ---- prereq: ERP + ESB must be up (REFUSE with instructions otherwise) ------
if ! curl -sf "$ERP_URL/actuator/health" | grep -q UP; then
  echo "SEED-DAY ERROR: ERP not healthy at $ERP_URL - boot it with: make esb-run" >&2
  exit 1
fi
if ! curl -sf "$ESB_HEALTH_URL/actuator/health" | grep -q UP; then
  echo "SEED-DAY ERROR: ESB not healthy at $ESB_HEALTH_URL (management port; facade $ESB_URL) - boot it with: make esb-run" >&2
  exit 1
fi

EPOCH="$(date +%s)"
posted=0; ok201=0; non201=0
declare -a failures=()

post_order() {
  local i="$1"
  local key="day-${EPOCH}-${i}"
  local r=$(( i % 4 ))
  local store source customer
  case "$r" in
    0|1) store="ST-CA-01"; source="WEB_STORE_CA"; customer="cust-ca-${i}" ;; # ~half
    2)   store="ST-SG-01"; source="WEB_STORE_SG"; customer="cust-sg-${i}" ;; # ~quarter
    3)   store="ST-CN-01"; source="WEB_STORE_CN"; customer="cust-cn-${i}" ;; # ~quarter (masked at egress)
  esac
  local sku
  sku=$(printf 'SKU-%04d' $(( (i % 50) + 1 )))
  local qty=$(( (i % 3) + 1 ))
  local received
  received="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

  local body code json
  body=$(curl -s -X POST "$ESB_URL/api/v1/orders" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $key" \
    -d "{
      \"externalOrderRef\":\"WEB-$key\",\"sourceSystem\":\"$source\",\"storeId\":\"$store\",
      \"channel\":\"WEB_STORE\",\"customerRef\":\"$customer\",
      \"lines\":[{\"skuId\":\"$sku\",\"quantity\":$qty}],
      \"audit\":{\"sourceSystem\":\"$source\",\"receivedAt\":\"$received\",\"correlationId\":\"$key\"}
    }" -w '\n%{http_code}') || {
      echo "SEED-DAY ERROR: could not reach the ESB at $ESB_URL mid-run (was healthy at start)" >&2
      exit 1
    }
  code=$(printf '%s' "$body" | tail -n1)
  json=$(printf '%s' "$body" | sed '$d')
  posted=$((posted + 1))
  if [ "$code" = "201" ]; then
    ok201=$((ok201 + 1))
  else
    non201=$((non201 + 1))
    # a 422 out-of-stock is a legitimate business outcome - counted, not hidden
    local err
    err=$(printf '%s' "$json" | jq -r '.error.code // .error // "unknown"' 2>/dev/null || echo "unparseable")
    failures+=("key=$key http=$code error=$err")
  fi
}

for i in $(seq 1 "$N"); do
  post_order "$i"
done

echo "SEED-DAY posted=$posted ok201=$ok201 non201=$non201"
echo "SEED-DAY expectedEvents=$ok201   (each 201 -> exactly ONE success event on silkroute.orders.events)"
if [ "$non201" -gt 0 ]; then
  echo "SEED-DAY non-201 list:"
  for f in "${failures[@]}"; do echo "  $f"; done
fi
# >10% non-201 => the run is visibly bad
if [ "$non201" -gt $(( posted / 10 )) ]; then
  echo "SEED-DAY FAIL: more than 10% of orders rejected ($non201/$posted)" >&2
  exit 2
fi
echo "SEED-DAY OK"
