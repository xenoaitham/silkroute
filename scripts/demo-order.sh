#!/usr/bin/env bash
# SILKROUTE quickstart — post ONE demo order to the ESB REST facade (sim mode).
#
# Maple Retail Group is a fictional company; this is a self-directed reference
# implementation. Talks to the sim ESB only (127.0.0.1:18081, loopback) using
# the documented sim dummies (see apps/esb/README.md). Prereqs: curl + jq, and
# the ESB booted via `make esb-run` (README quickstart).
#
# Usage: scripts/demo-order.sh [idempotency-key]    # default: demo-<epoch>
#   Re-posting the SAME key after a 201 returns 409 DUPLICATE carrying the
#   original orderId — the idempotent-consumer pattern, by design.
set -euo pipefail

ESB_URL="${DEMO_ESB_URL:-http://127.0.0.1:18081}"
KEY="${1:-demo-$(date +%s)}"

command -v curl >/dev/null || { echo "ERROR: curl is required"; exit 1; }
command -v jq >/dev/null    || { echo "ERROR: jq is required"; exit 1; }

body=$(curl -s -X POST "$ESB_URL/api/v1/orders" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" \
  -d "{
    \"externalOrderRef\":\"WEB-$KEY\",\"sourceSystem\":\"WEB_STORE_CA\",\"storeId\":\"ST-CA-01\",
    \"channel\":\"WEB_STORE\",\"customerRef\":\"cust-x\",
    \"lines\":[{\"skuId\":\"SKU-0001\",\"quantity\":2}],
    \"audit\":{\"sourceSystem\":\"WEB_STORE_CA\",\"receivedAt\":\"2026-09-11T10:00:00Z\",\"correlationId\":\"$KEY\"}
  }" -w '\n%{http_code}') || { echo "ERROR: could not reach the ESB at $ESB_URL — boot it with: make esb-run"; exit 1; }

code=$(printf '%s' "$body" | tail -n1)
json=$(printf '%s' "$body" | sed '$d')

echo "HTTP $code  (Idempotency-Key: $KEY)"
printf '%s' "$json" | jq .

if [ "$code" = "201" ]; then
  echo "OK: order accepted — the saga completed (steps + per-call attempts above)."
else
  echo "NOTE: non-201 (see error.code above). A same-key replay after a 201 is 409 DUPLICATE by design."
  exit 1
fi
