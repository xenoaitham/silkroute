#!/usr/bin/env bash
# ORCH-LEAD independent verification of apps/legacy-erp — runtime probe (E-006).
# Exercises happy + typed-fault + auth paths over SOAP 1.2 with UsernameToken.
# This is NOT the Karate contract suite (tests/contract/) — it is a thin curl probe.
# Prereq: the ERP is running, e.g.
#   SERVER_PORT=18080 java -jar apps/legacy-erp/target/legacy-erp-1.0.0-SNAPSHOT.jar
# Usage: bash scripts/erp-verify.sh   (override base URL with ERP_BASE_URL)
# Exits non-zero if any check fails.
set -u
BASE="${ERP_BASE_URL:-http://127.0.0.1:18080}"
WSS="$(cd "$(dirname "$0")" && pwd)/wss-header.sh"
U=esb-client; P=erp-wss-pass-2026
pass=0; failed=0
sec() { bash "$WSS" "$U" "$P"; }
check() { # name, expected_grep_in_body, actual_body
  local name="$1" expect="$2" out="$3"
  if [[ "$out" == *"$expect"* ]]; then echo "PASS  $name"; pass=$((pass+1));
  else echo "FAIL  $name (expected '$expect')"; echo "$out" | head -3 | sed 's/^/      /'; failed=$((failed+1)); fi
}
soap_post() { # url action body(with security header embedded)
  local url="$1" action="$2" body="$3"
  curl -sS -H "Content-Type: application/soap+xml; charset=utf-8; action=\"$action\"" \
       --data-binary "$body" "$url" 2>&1
}
env() { echo '<?xml version="1.0"?><env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope"'"$1"'><env:Header>'"$2"'</env:Header><env:Body>'"$3"'</env:Body></env:Envelope>'; }

# --- pricing happy path (SGD): 500 CAD minor x0.98 = 490 SGD minor (C5: integer minor units) ---
R=$(soap_post $BASE/ws/pricing/v1 'urn:maple:erp:pricing:v1:priceForSku' "$(env ' xmlns:mprc="urn:maple:erp:pricing:v1"' "$(sec)" '<mprc:priceForSkuRequest><mprc:skuId>SKU-0001</mprc:skuId><mprc:currency>SGD</mprc:currency></mprc:priceForSkuRequest>')")
check "pricing happy: SKU-0001 SGD" 'priceForSkuResponse' "$R"
[[ "$R" == *"amountMinor>490<"* ]] && { echo "PASS  pricing amountMinor=490 SGD (500 CAD x0.98)"; pass=$((pass+1)); } || { echo "FAIL  pricing amountMinor: got: $(echo "$R" | grep -o 'amountMinor>[0-9]*' | head -1)"; failed=$((failed+1)); }

# --- unknown SKU -> UnknownSkuFault ---
R=$(soap_post $BASE/ws/pricing/v1 'urn:maple:erp:pricing:v1:priceForSku' "$(env ' xmlns:mprc="urn:maple:erp:pricing:v1"' "$(sec)" '<mprc:priceForSkuRequest><mprc:skuId>SKU-9999</mprc:skuId><mprc:currency>SGD</mprc:currency></mprc:priceForSkuRequest>')")
check "pricing unknown SKU -> PRC-SKU-UNKNOWN" 'PRC-SKU-UNKNOWN' "$R"
check "  ... fault element present" 'UnknownSkuFault' "$R"

# --- orders happy path ---
R=$(soap_post $BASE/ws/orders/v1 'urn:maple:erp:orders:v1:submitOrder' "$(env ' xmlns:mord="urn:maple:erp:orders:v1" xmlns:mcom="urn:maple:erp:common:v1"' "$(sec)" '<mord:submitOrderRequest><mord:externalOrderRef>ORCH-LEAD-0001</mord:externalOrderRef><mord:storeId>ST-CA-01</mord:storeId><mord:orderChannel>WEB_STORE</mord:orderChannel><mord:lines><mord:skuId>SKU-0001</mord:skuId><mord:quantity>2</mord:quantity></mord:lines><mord:audit><mcom:sourceSystem>ORCH-LEAD</mcom:sourceSystem><mcom:receivedAt>2026-09-09T18:00:00Z</mcom:receivedAt><mcom:correlationId>orch-0001</mcom:correlationId></mord:audit></mord:submitOrderRequest>')")
check "orders happy: submitOrder SUBMITTED" 'SUBMITTED' "$R"
[[ "$R" == *"currency>CAD<"* ]] && { echo "PASS  order total priced in store-region currency (CAD)"; pass=$((pass+1)); } || { echo "FAIL  order currency"; failed=$((failed+1)); }
ORDER_ID=$(echo "$R" | grep -o 'ORD-2026-[0-9]*' | head -1)
[[ -n "$ORDER_ID" ]] && { echo "PASS  order id assigned: $ORDER_ID"; pass=$((pass+1)); } || { echo "FAIL  no order id"; failed=$((failed+1)); }

# --- invalid order (quantity 0) -> InvalidOrderFault ---
R=$(soap_post $BASE/ws/orders/v1 'urn:maple:erp:orders:v1:submitOrder' "$(env ' xmlns:mord="urn:maple:erp:orders:v1" xmlns:mcom="urn:maple:erp:common:v1"' "$(sec)" '<mord:submitOrderRequest><mord:externalOrderRef>ORCH-LEAD-BAD-1</mord:externalOrderRef><mord:storeId>ST-CA-01</mord:storeId><mord:orderChannel>WEB_STORE</mord:orderChannel><mord:lines><mord:skuId>SKU-0001</mord:skuId><mord:quantity>0</mord:quantity></mord:lines><mord:audit><mcom:sourceSystem>ORCH-LEAD</mcom:sourceSystem><mcom:receivedAt>2026-09-09T18:00:00Z</mcom:receivedAt><mcom:correlationId>orch-0002</mcom:correlationId></mord:audit></mord:submitOrderRequest>')")
check "orders qty=0 -> InvalidOrderFault/ORD-BAD-QUANTITY" 'ORD-BAD-QUANTITY' "$R"

# --- inventory: getStock, oversell reserve, unknown reservation release ---
R=$(soap_post $BASE/ws/inventory/v1 'urn:maple:erp:inventory:v1:getStock' "$(env ' xmlns:minv="urn:maple:erp:inventory:v1"' "$(sec)" '<minv:getStockRequest><minv:storeId>ST-SG-01</minv:storeId><minv:skuId>SKU-0007</minv:skuId></minv:getStockRequest>')")
check "inventory getStock happy (SG region)" 'region>SG<' "$R"
AVAIL=$(echo "$R" | grep -o 'availableQuantity>[0-9]*<' | head -1 | grep -o '[0-9]*')
OVER=$((AVAIL+5))
R=$(soap_post $BASE/ws/inventory/v1 'urn:maple:erp:inventory:v1:reserve' "$(env ' xmlns:minv="urn:maple:erp:inventory:v1" xmlns:mcom="urn:maple:erp:common:v1"' "$(sec)" "<minv:reserveRequest><minv:reservationRef>ORCH-OVERS-1</minv:reservationRef><minv:storeId>ST-SG-01</minv:storeId><minv:skuId>SKU-0007</minv:skuId><minv:quantity>$OVER</minv:quantity><minv:audit><mcom:sourceSystem>ORCH-LEAD</mcom:sourceSystem><mcom:receivedAt>2026-09-09T18:00:00Z</mcom:receivedAt><mcom:correlationId>orch-0003</mcom:correlationId></minv:audit></minv:reserveRequest>")")
check "inventory oversell ($OVER of $AVAIL) -> OutOfStockFault" 'OutOfStockFault' "$R"
[[ "$R" == *"availableQuantity>$AVAIL<"* ]] && { echo "PASS  fault carries availableQuantity=$AVAIL (never over-allocated)"; pass=$((pass+1)); } || { echo "FAIL  oversell fault detail"; failed=$((failed+1)); }
R=$(soap_post $BASE/ws/inventory/v1 'urn:maple:erp:inventory:v1:release' "$(env ' xmlns:minv="urn:maple:erp:inventory:v1" xmlns:mcom="urn:maple:erp:common:v1"' "$(sec)" '<minv:releaseRequest><minv:reservationId>RES-2026-999999</minv:reservationId><minv:audit><mcom:sourceSystem>ORCH-LEAD</mcom:sourceSystem><mcom:receivedAt>2026-09-09T18:00:00Z</mcom:receivedAt><mcom:correlationId>orch-0004</mcom:correlationId></minv:audit></minv:releaseRequest>')")
check "release bogus reservation -> UnknownReservationFault" 'UnknownReservationFault' "$R"

# --- auth failure: no security header ---
R=$(soap_post $BASE/ws/pricing/v1 'urn:maple:erp:pricing:v1:priceForSku' "$(env ' xmlns:mprc="urn:maple:erp:pricing:v1"' '' '<mprc:priceForSkuRequest><mprc:skuId>SKU-0001</mprc:skuId><mprc:currency>SGD</mprc:currency></mprc:priceForSkuRequest>')")
check "no security header -> rejected" 'security error' "$R"
# --- auth failure: wrong password ---
R=$(soap_post $BASE/ws/pricing/v1 'urn:maple:erp:pricing:v1:priceForSku' "$(env ' xmlns:mprc="urn:maple:erp:pricing:v1"' "$(bash "$WSS" "$U" 'totally-wrong-password')" '<mprc:priceForSkuRequest><mprc:skuId>SKU-0001</mprc:skuId><mprc:currency>SGD</mprc:currency></mprc:priceForSkuRequest>')")
check "wrong password -> rejected" 'security error' "$R"

# --- frozen WSDL served ---
R=$(curl -sS $BASE/ws/orders/v1?wsdl 2>&1)
check "?wsdl serves frozen contract ns" 'urn:maple:erp:orders:v1' "$R"
check "  ... soap12 binding" 'soap12' "$R"

echo "----"
echo "ORCH-LEAD verify: PASS=$pass FAIL=$failed"
exit $failed
