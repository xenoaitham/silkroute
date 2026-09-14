package com.mapleretail.silkroute.oms.event;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * Parses one ESB success event (JSON string) into {@link OrderEventData}.
 *
 * The event JSON = the OrderSubmissionResponse body (orderId, status,
 * externalOrderRef, storeId, region, channel, reservationId,
 * totalAmount{amountMinor,currency}, unitPrices[], saga[], attempts{}, route{},
 * audit{sourceSystem,correlationId}) PLUS customerRef (clear for CA/SG,
 * msk-* pseudonym for CN - stored verbatim, C1) PLUS lines[] =
 * {skuId, quantity, unitPriceMinor, currency}.
 *
 * Poison rules (OrderEventParseException): invalid JSON; missing orderId /
 * externalOrderRef / sourceSystem (the business-key triad the idempotency
 * guard is built on); a line missing skuId or price; quantity < 1.
 * unitPrices[] is a per-SKU fallback for lines that lack an explicit
 * unitPriceMinor. A missing/empty lines[] yields zero lines (order without
 * lines is storable; DQ and reconciliation downstream stay consistent).
 */
@Component
public final class OrderEventParser {

    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public OrderEventData parse(String json) throws OrderEventParseException {
        if (json == null || json.isBlank()) {
            throw new OrderEventParseException("event payload is null or blank");
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new OrderEventParseException("event payload is not valid JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new OrderEventParseException("event payload is not a JSON object");
        }

        String orderId = textOrNull(root, "orderId");
        String externalOrderRef = textOrNull(root, "externalOrderRef");
        String sourceSystem = textOrNull(path(root, "audit", "sourceSystem"));
        if (isBlank(orderId) || isBlank(externalOrderRef) || isBlank(sourceSystem)) {
            throw new OrderEventParseException("event is missing required business key(s): orderId, externalOrderRef, audit.sourceSystem");
        }

        Long totalMinor = null;
        String currency = null;
        JsonNode total = path(root, "totalAmount");
        if (total != null && total.isObject()) {
            JsonNode amount = total.get("amountMinor");
            if (amount != null && amount.canConvertToLong()) {
                totalMinor = amount.asLong();
            }
            currency = textOrNull(total, "currency");
        }

        // unitPrices[] fallback map: skuId -> [amountMinor, currency]
        java.util.Map<String, JsonNode> unitPrices = new java.util.HashMap<>();
        JsonNode prices = root.get("unitPrices");
        if (prices != null && prices.isArray()) {
            for (JsonNode p : prices) {
                String sku = textOrNull(p, "skuId");
                if (sku != null) {
                    unitPrices.put(sku, p);
                }
            }
        }

        java.util.List<OrderEventData.Line> lines = new java.util.ArrayList<>();
        JsonNode linesNode = root.get("lines");
        if (linesNode != null && linesNode.isArray()) {
            int index = 0;
            for (JsonNode l : linesNode) {
                String skuId = textOrNull(l, "skuId");
                JsonNode qtyNode = l.get("quantity");
                if (isBlank(skuId) || qtyNode == null || !qtyNode.canConvertToInt()) {
                    throw new OrderEventParseException("lines[" + index + "] is missing skuId or quantity");
                }
                int quantity = qtyNode.asInt();
                if (quantity < 1) {
                    throw new OrderEventParseException("lines[" + index + "] quantity must be >= 1, got " + quantity);
                }
                JsonNode priceNode = l.get("unitPriceMinor");
                JsonNode priceCurrency = textNode(l, "currency");
                if (priceNode == null || !priceNode.canConvertToLong() || priceCurrency == null) {
                    JsonNode fallback = unitPrices.get(skuId);
                    if (fallback != null) {
                        if (priceNode == null) {
                            priceNode = fallback.get("amountMinor");
                        }
                        if (priceCurrency == null) {
                            priceCurrency = textNode(fallback, "currency");
                        }
                    }
                }
                if (priceNode == null || !priceNode.canConvertToLong() || priceCurrency == null) {
                    throw new OrderEventParseException("lines[" + index + "] is missing unitPriceMinor/currency (and no unitPrices fallback)");
                }
                lines.add(OrderEventData.Line.of(skuId, quantity, priceNode.asLong(), priceCurrency.asText()));
                index++;
            }
        }

        return new OrderEventData(
                orderId,
                externalOrderRef,
                sourceSystem,
                textOrNull(root, "storeId"),
                textOrNull(root, "region"),
                textOrNull(root, "customerRef"), // C1: verbatim; msk-* for CN already applied at the ESB egress
                textOrNull(root, "channel"),
                textOrNull(root, "status"),
                textOrNull(root, "reservationId"),
                totalMinor,
                currency,
                textOrNull(path(root, "audit", "correlationId")),
                lines);
    }

    private static JsonNode path(JsonNode root, String field) {
        return root == null ? null : root.get(field);
    }

    private static JsonNode path(JsonNode root, String field, String sub) {
        JsonNode node = root.get(field);
        return node == null ? null : node.get(sub);
    }

    private static String textOrNull(JsonNode valueNode) {
        return valueNode == null || valueNode.isNull() ? null : valueNode.asText();
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static JsonNode textNode(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
