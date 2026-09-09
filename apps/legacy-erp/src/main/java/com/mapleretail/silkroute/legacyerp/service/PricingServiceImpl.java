package com.mapleretail.silkroute.legacyerp.service;

import org.apache.cxf.annotations.SchemaValidation;
import org.apache.cxf.annotations.SchemaValidation.SchemaValidationType;
import org.springframework.stereotype.Service;

import com.mapleretail.silkroute.legacyerp.contract.pricing.PriceForSkuRequest;
import com.mapleretail.silkroute.legacyerp.contract.pricing.PriceForSkuResponse;
import com.mapleretail.silkroute.legacyerp.domain.SkuRecord;
import com.mapleretail.silkroute.legacyerp.fault.ErpFaults;
import com.mapleretail.silkroute.legacyerp.ledger.SkuCatalog;
import com.mapleretail.silkroute.legacyerp.pricing.PriceList;
import com.mapleretail.silkroute.legacyerp.time.XmlDateTimes;

import maple.erp.pricing.v1.PricingServicePortType;
import maple.erp.pricing.v1.UnknownSkuFault;

/**
 * PricingService implementation. Serves the default price list only: the frozen
 * contract declares no fault for an unknown priceListCode, so an unrecognized
 * requested list falls back to STD-2026 and the response's priceListCode field
 * tells the caller which list was actually applied.
 */
@Service
@SchemaValidation(type = SchemaValidationType.IN)
public class PricingServiceImpl implements PricingServicePortType {

    private final SkuCatalog skuCatalog;

    public PricingServiceImpl(SkuCatalog skuCatalog) {
        this.skuCatalog = skuCatalog;
    }

    @Override
    public PriceForSkuResponse priceForSku(PriceForSkuRequest request) throws UnknownSkuFault {
        SkuRecord sku = skuCatalog.find(request.getSkuId());
        if (sku == null) {
            throw ErpFaults.skuUnknown(request.getSkuId());
        }
        long unitPriceMinor = PriceList.convertFromCadMinor(sku.basePriceCadMinor(), request.getCurrency());

        PriceForSkuResponse response = new PriceForSkuResponse();
        response.setSkuId(sku.skuId());
        response.setUnitPrice(PriceList.money(unitPriceMinor, request.getCurrency()));
        response.setPriceListCode(PriceList.DEFAULT_CODE);
        response.setEffectiveFrom(XmlDateTimes.utcDate(PriceList.EFFECTIVE_FROM));
        response.setValidUntil(XmlDateTimes.utcDate(PriceList.VALID_UNTIL));
        response.setDiscountEligible(sku.discountEligible());
        return response;
    }
}
