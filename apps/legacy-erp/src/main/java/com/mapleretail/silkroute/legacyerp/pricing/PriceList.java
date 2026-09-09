package com.mapleretail.silkroute.legacyerp.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;

import com.mapleretail.silkroute.legacyerp.contract.common.CurrencyCodeType;
import com.mapleretail.silkroute.legacyerp.contract.common.MoneyType;

/**
 * The single simulated price list and the CAD-based currency conversion.
 *
 * <p>Money math (constraint C5): every amount is an integer minor unit count.
 * Conversion applies a fixed factor to the CAD minor amount and rounds HALF_UP to
 * a whole minor unit via BigDecimal with scale 0 — no float/double is involved
 * anywhere in the estate. Example: CAD base 12999 minor x SGD 0.98 = 12739.02 ->
 * 12739 SGD minor.
 */
public final class PriceList {

    public static final String DEFAULT_CODE = "STD-2026";
    public static final LocalDate EFFECTIVE_FROM = LocalDate.of(2026, 1, 1);
    public static final LocalDate VALID_UNTIL = LocalDate.of(2026, 12, 31);

    /** Fixed sim factors from the CAD base, applied to minor units. */
    private static final Map<CurrencyCodeType, BigDecimal> FACTORS_FROM_CAD_MINOR = Map.of(
            CurrencyCodeType.CAD, new BigDecimal("1.00"),
            CurrencyCodeType.SGD, new BigDecimal("0.98"),
            CurrencyCodeType.CNY, new BigDecimal("5.13"));

    private PriceList() {
    }

    public static long convertFromCadMinor(long cadMinor, CurrencyCodeType target) {
        BigDecimal factor = FACTORS_FROM_CAD_MINOR.get(target);
        if (factor == null) {
            throw new IllegalArgumentException("Unsupported currency: " + target);
        }
        return BigDecimal.valueOf(cadMinor)
                .multiply(factor)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    public static MoneyType money(long amountMinor, CurrencyCodeType currency) {
        MoneyType money = new MoneyType();
        money.setAmountMinor(amountMinor);
        money.setCurrency(currency);
        return money;
    }
}
