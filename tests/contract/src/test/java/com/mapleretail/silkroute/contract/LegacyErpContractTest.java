package com.mapleretail.silkroute.contract;

import com.intuit.karate.junit5.Karate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * Entry point for the legacy ERP SOAP 1.2 contract suite (Karate + JUnit 5).
 *
 * <p>Lifecycle contract: the app under test is booted once for the whole suite
 * in {@code @BeforeAll} (the launcher runs all four {@code @Karate.Test} methods
 * inside this class) and destroyed in {@code @AfterAll}. If the jar is missing
 * or the app fails to boot, {@link LegacyErpTestApp#ensureStarted()} throws and
 * every test method FAILS — the suite is falsifiable by construction and can
 * never pass against a service that is not actually there.
 *
 * <p>The features speak raw document/literal-wrapped SOAP 1.2 XML against the
 * FROZEN WSDLs (constraint C6) — no client library, no shared types.
 */
class LegacyErpContractTest {

    @BeforeAll
    static void bootAppUnderTest() {
        LegacyErpTestApp.ensureStarted();
    }

    @AfterAll
    static void shutdownAppUnderTest() {
        LegacyErpTestApp.destroy();
    }

    @Karate.Test
    Karate pricing() {
        return Karate.run("classpath:features/pricing.feature");
    }

    @Karate.Test
    Karate orders() {
        return Karate.run("classpath:features/orders.feature");
    }

    @Karate.Test
    Karate inventory() {
        return Karate.run("classpath:features/inventory.feature");
    }

    @Karate.Test
    Karate security() {
        return Karate.run("classpath:features/security.feature");
    }
}
