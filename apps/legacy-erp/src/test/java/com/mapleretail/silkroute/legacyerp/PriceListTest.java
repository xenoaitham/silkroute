package com.mapleretail.silkroute.legacyerp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.mapleretail.silkroute.legacyerp.contract.common.CurrencyCodeType;
import com.mapleretail.silkroute.legacyerp.pricing.PriceList;

/**
 * Constraint C5 money math: integer minor units only, deterministic per-currency
 * conversion from the CAD base, HALF_UP rounding on converted minor units.
 */
class PriceListTest {

    @Test
    void cadBaseIsIdentity() {
        assertEquals(12999L, PriceList.convertFromCadMinor(12999L, CurrencyCodeType.CAD));
    }

    @Test
    void sgdAppliesFixedFactorOnMinorUnits() {
        // 500 CAD minor ($5.00) x 0.98 = 490.00 -> 490 SGD minor exactly.
        assertEquals(490L, PriceList.convertFromCadMinor(500L, CurrencyCodeType.SGD));
        // 12999 CAD minor x 0.98 = 12739.02 -> 12739.
        assertEquals(12739L, PriceList.convertFromCadMinor(12999L, CurrencyCodeType.SGD));
    }

    @Test
    void cnyAppliesFixedFactorOnMinorUnits() {
        // 500 CAD minor x 5.13 = 2565.00 -> 2565 CNY minor exactly.
        assertEquals(2565L, PriceList.convertFromCadMinor(500L, CurrencyCodeType.CNY));
        // 513 x 5.13 = 2631.69 -> 2632 (HALF_UP).
        assertEquals(2632L, PriceList.convertFromCadMinor(513L, CurrencyCodeType.CNY));
    }

    @Test
    void halfUpRoundsExactHalvesAwayFromZero() {
        // 25 CAD minor x 0.98 = 24.5 -> 25 under HALF_UP.
        assertEquals(25L, PriceList.convertFromCadMinor(25L, CurrencyCodeType.SGD));
    }
}
