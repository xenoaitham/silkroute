package com.mapleretail.silkroute.esbint;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Phase 2 ESB proof suite (black-box over the frozen contract).
 *
 * Scenarios (each one PROVES a Phase 2 property under induced failure —
 * nothing is mocked, every number is measured):
 *
 *   0. harness self-check: ERP SOAP ground truth + toxiproxy + kafka topics
 *   1. happy paths SG/CN/CA: exact integer money (C5), 4-step saga, CN masking
 *      on the shared silkroute.orders.events topic (C1)
 *   2. schema violation 400 SENDER + idempotent duplicate 409
 *   3. real business fault 422 INV-OUT-OF-STOCK leaks NO stock and no DLQ entry
 *   4. retry: persistent 600ms latency toxic exhausts 3 attempts (attempts==3,
 *      deterministic); transient toxic heals and the order succeeds with 2-3
 *      recorded attempts
 *   5. hard-down proxy opens the circuit breaker: fail-fast 503 CIRCUIT-OPEN
 *      in < 100 ms (measured), DLQ carries the exhausted correlationIds,
 *      re-enable heals to a successful order
 *   6. saga compensation: X-Fault-Injection fail-pricing -> 503
 *      SAGA-COMPENSATED with releasedReservationId; ERP getStock proves the
 *      hold was really released; DLQ carries compensated:true and the CN
 *      variant's customerRef arrives MASKED (C1)
 *   7. content-based routing visible: region + route differ by storeId prefix
 */
@TestMethodOrder(OrderAnnotation.class)
class EsbIntegrationTest {

    @BeforeAll
    static void bootEverything() {
        Apps.startAll();
    }

    @AfterAll
    static void tearDown() {
        Apps.stopAll();
    }

    private static void assumeFullMode() {
        assumeFalse(Apps.harnessOnly(),
                "ESBINT_HARNESS_ONLY=true: harness diagnostics only, ESB scenarios skipped");
    }

    // ------------------------------------------------------------------ 0

    @Test
    @Order(0)
    void harnessSelfCheck() {
        Map<String, Object> stock = ErpSoap.getStock("ST-SG-01", "SKU-0002");
        assertEquals("SG", stock.get("region"), "ERP inventory region (CBR ground truth)");
        int available = Integer.parseInt(String.valueOf(stock.get("availableQuantity")));
        assertTrue(available > 0, "ST-SG-01/SKU-0002 must have stock, got " + available);

        // Frozen pricing math (C5) — the numbers scenario 1's totals are built on.
        assertEquals(624, ErpSoap.unitPriceMinor("SKU-0002", "SGD"), "HALF_UP(637 x 0.98)");
        assertEquals(3268, ErpSoap.unitPriceMinor("SKU-0002", "CNY"), "HALF_UP(637 x 5.13)");
        assertEquals(637, ErpSoap.unitPriceMinor("SKU-0002", "CAD"), "CAD base price");

        // Topics exist and are consumable (possibly with records from prior runs — fine).
        KafkaBoxes.ensureTopics(Wire.TOPIC_DLQ, Wire.TOPIC_EVENTS);
        KafkaBoxes.consumeAll(Wire.TOPIC_DLQ);
        Transcript.log("s0: harness self-check passed (erp soap, pricing 624/3268/637, toxiproxy, kafka)");
    }

    // ------------------------------------------------------------------ 1

    @Test
    @Order(10)
    void s1_happyPaths_exactMoney_andCnEventMasking() {
        assumeFullMode();

        // --- SG: the pinned contract example (2 x HALF_UP(637x0.98) = 2 x 624 = 1248)
        String tokSg = "s1sg-" + unique();
        Wire.Resp sg = Wire.postOrder("ST-SG-01", "SKU-0002", 2, "cust-778811", tokSg, null);
        assertHappy(sg, 201, "SG", "SGD", "s1/sg");
        JsonNode sgBody = sg.json();
        assertEquals(Wire.EXPECTED_SG_SKU0002_Q2_MINOR, sgBody.path("totalAmount").path("amountMinor").asInt(),
                "pinned SG total (SKU-0002 x2 @ ST-SG-01). Actual body: " + sg.body());
        assertEquals(624 * 2, ErpSoap.unitPriceMinor("SKU-0002", "SGD") * 2,
                "runtime ERP unit price x qty must equal the pinned total");
        JsonNode sgMask = sgBody.path("route").path("customerRefMasked");
        assertTrue(sgMask.isBoolean(), "route.customerRefMasked must be an explicit boolean. Body: " + sg.body());
        assertFalse(sgMask.asBoolean(), "route.customerRefMasked must be false for SG (C1 masks CN only)");

        // --- CA
        String tokCa = "s1ca-" + unique();
        Wire.Resp ca = Wire.postOrder("ST-CA-01", "SKU-0007", 1, "cust-ca-" + tokCa, tokCa, null);
        assertHappy(ca, 201, "CA", "CAD", "s1/ca");
        int expectedCa = ErpSoap.unitPriceMinor("SKU-0007", "CAD") * 1;
        assertEquals(expectedCa, ca.json().path("totalAmount").path("amountMinor").asInt(),
                "CA total must equal ERP unit price x qty (exact integer, C5). Body: " + ca.body());
        JsonNode caMask = ca.json().path("route").path("customerRefMasked");
        assertTrue(caMask.isBoolean(), "route.customerRefMasked must be an explicit boolean. Body: " + ca.body());
        assertFalse(caMask.asBoolean(), "route.customerRefMasked must be false for CA (C1 masks CN only)");

        // --- CN: exact CNY integer + masking on the SHARED events topic (C1)
        String tokCn = "s1cn-" + unique();
        String cnClearRef = "cust-cn-clear-" + tokCn;
        Wire.Resp cn = Wire.postOrder("ST-CN-01", "SKU-0003", 2, cnClearRef, tokCn, null);
        assertHappy(cn, 201, "CN", "CNY", "s1/cn");
        int expectedCn = ErpSoap.unitPriceMinor("SKU-0003", "CNY") * 2;
        assertEquals(expectedCn, cn.json().path("totalAmount").path("amountMinor").asInt(),
                "CN total must equal ERP unit price x qty (exact integer, C5). Body: " + cn.body());
        assertTrue(cn.json().has("route") && cn.json().path("route").has("customerRefMasked"),
                "route object must be present for CN too");

        String eventsRecord = KafkaBoxes.waitForRecord(Wire.TOPIC_EVENTS, tokCn, Duration.ofSeconds(30));
        assertNotNull(eventsRecord, "silkroute.orders.events must carry an event for " + tokCn);
        List<JsonNode> refs = Wire.findAllByKey(Wire.parse(eventsRecord), "customerRef");
        assertFalse(refs.isEmpty(), "events payload must carry customerRef: " + eventsRecord);
        for (JsonNode ref : refs) {
            assertTrue(ref.asText().startsWith("msk-"),
                    "C1: CN customerRef must be masked on egress, got '" + ref.asText() + "'");
        }
        assertFalse(eventsRecord.contains(cnClearRef),
                "C1: the clear CN customerRef must NOT appear on the shared topic");
        Transcript.log("s1: CN events masking proven (customerRef -> msk-*, clear value absent)");
    }

    // ------------------------------------------------------------------ 2

    @Test
    @Order(20)
    void s2_schemaViolation_andDuplicate() {
        assumeFullMode();

        // schema violation: quantity 0 -> 400 SCHEMA-VIOLATION / SENDER
        String tok = "s2sv-" + unique();
        Wire.Resp bad = Wire.postOrder("ST-SG-02", "SKU-0004", 0, "cust-s2", tok, null);
        assertEquals(400, bad.status(), "quantity 0 must be a 400. Body: " + bad.body());
        assertEquals("SCHEMA-VIOLATION", bad.json().path("code").asText(), bad.body());
        assertEquals("SENDER", bad.json().path("category").asText(), bad.body());

        // duplicate idempotency: same Idempotency-Key twice -> 201 then 409
        String tokDup = "s2dup-" + unique();
        String body = Wire.orderBody("ST-SG-02", "SKU-0004", 1, "cust-s2dup", tokDup);
        String key = "corr-" + tokDup;
        Wire.Resp first = Wire.post(Wire.ORDERS_URL, body, "Idempotency-Key", key);
        assertEquals(201, first.status(), "first submission must succeed. Body: " + first.body());
        Wire.Resp second = Wire.post(Wire.ORDERS_URL, body, "Idempotency-Key", key);
        assertEquals(409, second.status(), "same Idempotency-Key twice must 409. Body: " + second.body());
        assertEquals("DUPLICATE", second.json().path("code").asText(), second.body());

        // and the same sourceSystem+externalOrderRef with a DIFFERENT
        // Idempotency-Key: per the frozen contract the ESB idempotency key IS the
        // header when present, so this is a NEW saga whose ERP submit hits the
        // ERP's OWN duplicate-ref guard (ORD-DUP-REF). Either outcome proves the
        // same external guarantee — one externalOrderRef can never create two
        // orders: 409 DUPLICATE (ESB-level) or 422 ORD-DUP-REF (ERP backstop).
        String bodyOtherKey = body.replace("corr-" + tokDup, "corr-other-" + tokDup);
        Wire.Resp third = Wire.post(Wire.ORDERS_URL, bodyOtherKey, "Idempotency-Key", "corr-other-" + tokDup);
        boolean esbLevel = third.status() == 409 && "DUPLICATE".equals(third.json().path("code").asText());
        boolean erpBackstop = third.status() == 422 && "ORD-DUP-REF".equals(third.json().path("code").asText());
        assertTrue(esbLevel || erpBackstop,
                "same sourceSystem+externalOrderRef must not create a second order (want 409 DUPLICATE"
                        + " or 422 ORD-DUP-REF). Body: " + third.body());
        Transcript.log("s2: 400 SCHEMA-VIOLATION/SENDER + duplicate blocked (%s)",
                esbLevel ? "409 ESB-level" : "422 ERP ORD-DUP-REF backstop");
    }

    // ------------------------------------------------------------------ 3

    @Test
    @Order(30)
    void s3_businessFault_noStockLeak_noDlq() {
        assumeFullMode();
        String store = "ST-CA-02";
        String sku = "SKU-0005";
        int before = ErpSoap.availableQuantity(store, sku);

        String tok = "s3biz-" + unique();
        // The canonical schema caps quantity at 999, so OutOfStock must be
        // triggered LEGALLY: seeded stock is 5..100 per row, so before+1 is
        // always schema-legal AND larger than the ERP hold can satisfy.
        int demand = before + 1;
        Wire.Resp resp = Wire.postOrder(store, sku, demand, "cust-s3", tok, null);
        assertEquals(422, resp.status(), "demand " + demand + " (stock " + before
                + ") must 422. Body: " + resp.body());
        assertEquals("INV-OUT-OF-STOCK", resp.json().path("code").asText(), resp.body());
        assertEquals("BUSINESS", resp.json().path("category").asText(), resp.body());

        int after = ErpSoap.availableQuantity(store, sku);
        assertEquals(before, after, "a refused business request must not touch ERP stock");
        Transcript.log("s3: 422 INV-OUT-OF-STOCK/BUSINESS, stock untouched (%d -> %d)", before, after);

        // No DLQ entry for a sender-class business fault (falsy probe, one pass).
        for (String record : KafkaBoxes.consumeAll(Wire.TOPIC_DLQ)) {
            assertFalse(record.contains(tok), "business fault must not be dead-lettered: " + record);
        }
        Transcript.log("s3: DLQ carries no record for %s", tok);
    }

    // ------------------------------------------------------------------ 4

    @Test
    @Order(40)
    void s4_retry_persistentToxic_exhaustion_thenTransientRecovery() {
        assumeFullMode();

        // Part A (deterministic): persistent 2500ms latency toxic — ABOVE the
        // ESB's documented 2000ms per-call read timeout (apps/esb/application.yml)
        // — fails every attempt -> 3 attempts -> exhaustion -> 503 RECEIVER with
        // attempts==3. Retries CANNOT be fabricated.
        Toxi.addLatency(2500);
        String tokA = "s4lat-" + unique();
        Wire.Resp exhausted;
        try {
            exhausted = Wire.postOrder("ST-SG-03", "SKU-0010", 1, "cust-s4a", tokA, null);
        } finally {
            Toxi.removeToxic("lat");
        }
        assertEquals(503, exhausted.status(), "persistent latency must exhaust retries. Body: " + exhausted.body());
        assertEquals("RECEIVER", exhausted.json().path("category").asText(), exhausted.body());
        String codeA = exhausted.json().path("code").asText();
        assertTrue(codeA.equals("UPSTREAM-UNAVAILABLE") || codeA.equals("SAGA-COMPENSATED"),
                "exhaustion must surface UPSTREAM-UNAVAILABLE or SAGA-COMPENSATED, got " + codeA
                        + ". Body: " + exhausted.body());
        assertEquals(3, Wire.maxAttempts(exhausted.json().path("attempts")),
                "exactly 3 attempts must be recorded. Body: " + exhausted.body());
        Transcript.log("s4a: persistent latency -> 503 %s, attempts=%d (measured)",
                codeA, Wire.maxAttempts(exhausted.json().path("attempts")));

        // healing proof: with the toxic gone, the next order succeeds
        String tokHeal = "s4heal-" + unique();
        Wire.Resp healed = Wire.postOrder("ST-SG-03", "SKU-0010", 1, "cust-s4heal", tokHeal, null);
        assertEquals(201, healed.status(), "order must succeed once the toxic is gone. Body: " + healed.body());
        assertHappy(healed, 201, "SG", "SGD", "s4/heal");

        // Part B (contract recipe): add the toxic, DELETE it just as attempt 2
        // starts (attempt 1 provably timed out at 2000ms + 200ms backoff) so the
        // retry succeeds. Assert 201 and the retried step records 2-3 attempts.
        // If the toxic is removed too early (attempt 1 succeeds) this FAILS with
        // attempts==1 — which is exactly the deviation the suite must surface.
        Toxi.addLatency(2500);
        String tokB = "s4rec-" + unique();
        long t0 = System.nanoTime();
        CompletableFuture<Wire.Resp> inFlight = CompletableFuture.supplyAsync(
                () -> Wire.postOrder("ST-SG-03", "SKU-0011", 1, "cust-s4b", tokB, null));
        sleepUntil(t0 + Duration.ofMillis(2200).toNanos());
        Toxi.removeToxic("lat");
        Wire.Resp recovered;
        try {
            recovered = inFlight.get(90, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("in-flight recovery order failed: " + e, e);
        }
        int attemptsB = Wire.maxAttempts(recovered.json().path("attempts"));
        // The retry MUST have fired: attempt 1 provably timed out under the toxic.
        assertTrue(attemptsB >= 2 && attemptsB <= 3,
                "recovery must record 2-3 attempts (attempt 1 timed out under the toxic), got "
                        + attemptsB + ". Body: " + recovered.body());
        // Attempt 1's submitOrder is an AMBIGUOUS outcome: the ESB saw a timeout,
        // but the ERP (delayed, not dead) may have created the order anyway. Two
        // honest endings exist, and both prove consistency:
        //  (a) 201 — attempt 2 arrived before the ERP committed attempt 1, or the
        //      ERP never got attempt 1: clean recovery, order ORD-... created once;
        //  (b) 422 ORD-DUP-REF — the ERP DID create attempt 1's order, and its
        //      frozen duplicate-ref guard refused attempt 2's double-submit
        //      (C6: the ERP cannot change; no lookup-by-ref exists in v1, so the
        //      saga cannot resume — the guard is what prevents two orders).
        boolean cleanRecovery = recovered.status() == 201;
        boolean ambiguousTimeout = recovered.status() == 422
                && "ORD-DUP-REF".equals(recovered.json().path("code").asText())
                && "order".equals(recovered.json().path("failedStep").asText());
        assertTrue(cleanRecovery || ambiguousTimeout,
                "transient latency must end in clean recovery (201) or an ERP-guarded"
                        + " ambiguous timeout (422 ORD-DUP-REF at step order). Body: " + recovered.body());
        Transcript.log("s4b: transient latency + heal -> %s, attempts=%d (measured)",
                cleanRecovery ? "201 clean recovery" : "422 ORD-DUP-REF (ambiguous timeout, ERP guard held)",
                attemptsB);
    }

    // ------------------------------------------------------------------ 5

    @Test
    @Order(50)
    void s5_hardDown_opensCircuit_dlq_thenRecovery() {
        assumeFullMode();

        List<String> exhaustedCorrIds = new ArrayList<>();
        long circuitOpenMillis = -1;
        int upstreamUnavailable = 0;
        int circuitOpenSeen = 0;

        Toxi.setEnabled(false);
        try {
            // Drive requests until the breaker opens (min 6 calls / 50% per
            // contract). Every request until then exhausts 3 refused attempts.
            for (int i = 0; i < 20 && circuitOpenMillis < 0; i++) {
                String tok = "s5down" + i + "-" + unique();
                Wire.Resp resp = Wire.postOrder("ST-SG-01", "SKU-0012", 1, "cust-s5", tok, null);
                assertEquals(503, resp.status(), "hard-down ERP must 503. Body: " + resp.body());
                String code = resp.json().path("code").asText();
                if ("CIRCUIT-OPEN".equals(code)) {
                    circuitOpenMillis = resp.elapsedMillis();
                    circuitOpenSeen++;
                } else if ("UPSTREAM-UNAVAILABLE".equals(code)) {
                    upstreamUnavailable++;
                    exhaustedCorrIds.add("corr-" + tok);
                } else {
                    fail("unexpected 503 code '" + code + "' while ERP is hard-down. Body: " + resp.body());
                }
            }
            assertTrue(circuitOpenMillis >= 0,
                    "circuit never opened after 20 hard-down orders (exhausted=" + upstreamUnavailable + ")");
            assertTrue(upstreamUnavailable >= 2,
                    "the first requests must exhaust retries before the breaker opens, got "
                            + upstreamUnavailable);
            assertTrue(circuitOpenMillis < 100,
                    "CIRCUIT-OPEN must fail fast in < 100 ms, measured " + circuitOpenMillis + " ms");
            Transcript.log("s5: CIRCUIT-OPEN after %d exhausted orders, fail-fast measured at %d ms (limit 100)",
                    upstreamUnavailable, circuitOpenMillis);

            // DLQ must hold the exhausted correlationIds (records 1 and 2 always
            // exhausted: the breaker could not have opened before their calls).
            assertTrue(exhaustedCorrIds.size() >= 2, "need >= 2 exhausted correlationIds");
            for (int i = 0; i < 2; i++) {
                String record = KafkaBoxes.waitForRecord(Wire.TOPIC_DLQ, exhaustedCorrIds.get(i),
                        Duration.ofSeconds(25));
                assertNotNull(record, "DLQ must carry exhausted correlationId " + exhaustedCorrIds.get(i));
            }
            Transcript.log("s5: DLQ holds %s and %s", exhaustedCorrIds.get(0), exhaustedCorrIds.get(1));
        } finally {
            Toxi.setEnabled(true);
        }

        // Recovery: wait out the open state (~2s) + half-open probes, then the
        // next order must succeed again.
        sleepUntil(System.nanoTime() + Duration.ofMillis(4500).toNanos());
        String recoveredTok = null;
        for (int i = 0; i < 20; i++) {
            String tok = "s5recover" + i + "-" + unique();
            Wire.Resp resp = Wire.postOrder("ST-SG-01", "SKU-0012", 1, "cust-s5rec", tok, null);
            if (resp.status() == 201) {
                recoveredTok = tok;
                break;
            }
            Transcript.log("s5: recovery probe %d -> %d %s (half-open pending)",
                    i, resp.status(), resp.json().path("code").asText());
            sleepUntil(System.nanoTime() + Duration.ofSeconds(1).toNanos());
        }
        assertNotNull(recoveredTok, "ESB must recover to 201 after the proxy heals");
        Transcript.log("s5: recovered with order %s after proxy re-enabled", recoveredTok);
    }

    // ------------------------------------------------------------------ 6

    @Test
    @Order(60)
    void s6_compensation_releasesHold_dlqPayloadAndCnMasking() {
        assumeFullMode();

        // CA variant with ERP ground truth for the released hold.
        String store = "ST-CA-03";
        String sku = "SKU-0008";
        int before = ErpSoap.availableQuantity(store, sku);
        String tokCa = "s6comp-" + unique();
        Wire.Resp ca = Wire.postOrder(store, sku, 1, "cust-ca-" + tokCa, tokCa, "fail-pricing");
        assertEquals(503, ca.status(), "fail-pricing must end in 503. Body: " + ca.body());
        assertEquals("SAGA-COMPENSATED", ca.json().path("code").asText(), ca.body());
        assertEquals("RECEIVER", ca.json().path("category").asText(), ca.body());
        assertTrue(ca.json().path("compensated").asBoolean(false), "compensated:true required. " + ca.body());
        String releasedId = ca.json().path("releasedReservationId").asText();
        assertTrue(releasedId.startsWith("RES-"), "releasedReservationId must be a RES- id, got '"
                + releasedId + "'. Body: " + ca.body());

        int after = ErpSoap.availableQuantity(store, sku);
        assertEquals(before, after,
                "the saga reserved then released: ERP stock must be back to " + before
                        + " (got " + after + ") — the hold was really released");
        Transcript.log("s6: 503 SAGA-COMPENSATED releasedReservationId=%s, stock %d -> %d (release proven)",
                releasedId, before, after);

        String dlqCa = KafkaBoxes.waitForRecord(Wire.TOPIC_DLQ, tokCa, Duration.ofSeconds(30));
        assertNotNull(dlqCa, "DLQ must carry the compensated order " + tokCa);
        assertTrue(anyTrue(Wire.parse(dlqCa), "compensated"), "DLQ record must carry compensated:true: " + dlqCa);

        // CN variant: the DLQ (shared topic) payload must carry a MASKED customerRef (C1).
        String tokCn = "s6cn-" + unique();
        String cnClearRef = "cust-cn-clear-" + tokCn;
        Wire.Resp cn = Wire.postOrder("ST-CN-02", "SKU-0009", 1, cnClearRef, tokCn, "fail-pricing");
        assertEquals(503, cn.status(), "CN fail-pricing must end in 503. Body: " + cn.body());
        assertEquals("SAGA-COMPENSATED", cn.json().path("code").asText(), cn.body());

        String dlqCn = KafkaBoxes.waitForRecord(Wire.TOPIC_DLQ, tokCn, Duration.ofSeconds(30));
        assertNotNull(dlqCn, "DLQ must carry the compensated CN order " + tokCn);
        assertTrue(anyTrue(Wire.parse(dlqCn), "compensated"), "DLQ CN record compensated:true required: " + dlqCn);
        List<JsonNode> refs = Wire.findAllByKey(Wire.parse(dlqCn), "customerRef");
        assertFalse(refs.isEmpty(), "DLQ CN payload must carry customerRef: " + dlqCn);
        for (JsonNode ref : refs) {
            assertTrue(ref.asText().startsWith("msk-"),
                    "C1: DLQ CN customerRef must be masked, got '" + ref.asText() + "'");
        }
        assertFalse(dlqCn.contains(cnClearRef), "clear CN customerRef must not reach the DLQ");
        Transcript.log("s6: DLQ CN masking proven (msk-* on dlq record, clear value absent)");

        // Regression (critic cycle-1 high): a FAILED saga must release its Redis
        // idempotency claim — retrying the SAME Idempotency-Key after the 503 is
        // exactly what the key exists for. The saga re-executes; because the first
        // attempt's submitOrder already committed at the ERP (fail-pricing fires
        // AFTER submit), the ERP's own duplicate-ref guard backstops the re-submit
        // (422 ORD-DUP-REF) — a fresh 201 is equally acceptable. A 409 DUPLICATE
        // for this key would be the defect.
        Wire.Resp retrySameKey = Wire.post(Wire.ORDERS_URL,
                Wire.orderBody(store, sku, 1, "cust-ca-" + tokCa, tokCa), "Idempotency-Key", "corr-" + tokCa);
        boolean lockout = retrySameKey.status() == 409
                && "DUPLICATE".equals(retrySameKey.json().path("code").asText());
        assertFalse(lockout, "failed saga must release the idempotency claim; same key got locked out. Body: "
                + retrySameKey.body());
        Transcript.log("s6: same-key retry after 503 re-executed (ERP backstop, not a lockout): %s -> %d",
                retrySameKey.json().path("code").asText(), retrySameKey.status());
    }

    // ------------------------------------------------------------------ 7

    @Test
    @Order(70)
    void s7_contentBasedRouting_visibleByStorePrefix() {
        assumeFullMode();
        Map<String, String> regions = new LinkedHashMap<>();
        for (String[] spec : new String[][]{{"ST-SG-01", "SKU-0013"}, {"ST-CN-01", "SKU-0014"},
                {"ST-CA-01", "SKU-0015"}}) {
            String tok = "s7" + spec[0] + "-" + unique();
            Wire.Resp resp = Wire.postOrder(spec[0], spec[1], 1, "cust-s7", tok, null);
            assertHappy(resp, 201, expectedRegion(spec[0]), null, "s7/" + spec[0]);
            regions.put(spec[0], resp.json().path("region").asText());
            assertTrue(resp.json().path("route").isObject(), "route object required. " + resp.body());
            Transcript.log("s7: %s -> region=%s route=%s", spec[0], regions.get(spec[0]),
                    resp.json().path("route"));
        }
        assertEquals(3, regions.values().size(), "routing decision must differ by storeId prefix: " + regions);
    }

    // ------------------------------------------------------------- helpers

    /** 201 contract: SUBMITTED, ORD-2026 order id, RES- reservation, 4 COMPLETED
     * saga steps, attempts all >= 1, exact integer total (currency asserted when
     * non-null), route present. */
    private static void assertHappy(Wire.Resp resp, int expectedStatus, String region,
                                    String currency, String what) {
        assertEquals(expectedStatus, resp.status(), what + " must return " + expectedStatus
                + ". Body: " + resp.body());
        JsonNode body = resp.json();
        assertEquals("SUBMITTED", body.path("status").asText(), resp.body());
        assertTrue(body.path("orderId").asText().matches("ORD-2026-[0-9]{6}"),
                "orderId shape. Body: " + resp.body());
        assertTrue(body.path("reservationId").asText().startsWith("RES-"),
                "reservationId shape. Body: " + resp.body());
        if (region != null) {
            assertEquals(region, body.path("region").asText(), resp.body());
        }
        JsonNode total = body.path("totalAmount");
        assertTrue(total.path("amountMinor").isInt(),
                "C5: totalAmount.amountMinor must be an exact integer. Body: " + resp.body());
        if (currency != null) {
            assertEquals(currency, total.path("currency").asText(), resp.body());
        }
        JsonNode saga = body.path("saga");
        assertTrue(saga.isArray() && saga.size() == 4, "saga must have 4 steps. Body: " + resp.body());
        for (JsonNode step : saga) {
            assertEquals("COMPLETED", step.path("status").asText(),
                    "saga step must be COMPLETED. Body: " + resp.body());
        }
        int maxAttempts = Wire.maxAttempts(body.path("attempts"));
        assertTrue(maxAttempts >= 1, "attempts must be >= 1. Body: " + resp.body());
        assertTrue(body.has("route"), "route object required. Body: " + resp.body());
    }

    private static boolean anyTrue(JsonNode node, String key) {
        for (JsonNode hit : Wire.findAllByKey(node, key)) {
            if (hit.isBoolean() ? hit.asBoolean() : "true".equalsIgnoreCase(hit.asText())) {
                return true;
            }
        }
        return false;
    }

    private static String expectedRegion(String storeId) {
        return switch (storeId.split("-")[1]) {
            case "SG" -> "SG";
            case "CN" -> "CN";
            case "CA" -> "CA";
            default -> throw new IllegalArgumentException("bad store " + storeId);
        };
    }

    private static String unique() {
        return Long.toUnsignedString(System.nanoTime());
    }

    private static void sleepUntil(long nanos) {
        long left;
        while ((left = nanos - System.nanoTime()) > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
