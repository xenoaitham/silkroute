package com.mapleretail.silkroute.legacyerp.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.cxf.annotations.SchemaValidation;
import org.apache.cxf.annotations.SchemaValidation.SchemaValidationType;
import org.springframework.stereotype.Service;

import com.mapleretail.silkroute.legacyerp.contract.common.CurrencyCodeType;
import com.mapleretail.silkroute.legacyerp.contract.common.LegacyAuditType;
import com.mapleretail.silkroute.legacyerp.contract.common.StoreRegionCodeType;
import com.mapleretail.silkroute.legacyerp.contract.orders.OrderLineInputType;
import com.mapleretail.silkroute.legacyerp.contract.orders.OrderLineStatusRecordType;
import com.mapleretail.silkroute.legacyerp.contract.orders.OrderLineStatusType;
import com.mapleretail.silkroute.legacyerp.contract.orders.OrderStatusType;
import com.mapleretail.silkroute.legacyerp.contract.orders.SubmitOrderRequest;
import com.mapleretail.silkroute.legacyerp.contract.orders.SubmitOrderResponse;
import com.mapleretail.silkroute.legacyerp.contract.orders.GetOrderStatusRequest;
import com.mapleretail.silkroute.legacyerp.contract.orders.GetOrderStatusResponse;
import com.mapleretail.silkroute.legacyerp.domain.OrderLineRecord;
import com.mapleretail.silkroute.legacyerp.domain.OrderRecord;
import com.mapleretail.silkroute.legacyerp.domain.StoreRecord;
import com.mapleretail.silkroute.legacyerp.domain.SkuRecord;
import com.mapleretail.silkroute.legacyerp.fault.ErpFaults;
import com.mapleretail.silkroute.legacyerp.ledger.OrderLedger;
import com.mapleretail.silkroute.legacyerp.ledger.SkuCatalog;
import com.mapleretail.silkroute.legacyerp.ledger.StoreDirectory;
import com.mapleretail.silkroute.legacyerp.pricing.PriceList;
import com.mapleretail.silkroute.legacyerp.time.XmlDateTimes;

import maple.erp.orders.v1.InvalidOrderFault;
import maple.erp.orders.v1.OrderServicePortType;

/**
 * OrderService implementation. Validation order is deliberate and stable (the ESB
 * contract tests rely on it): audit block, store, then every line (sku, quantity),
 * then duplicate externalOrderRef at commit time. Totals are priced in the store's
 * region currency — CA->CAD, SG->SGD, CN->CNY — through the same CAD-based
 * conversion as priceForSku (constraint C5: integer minor units only).
 */
@Service
@SchemaValidation(type = SchemaValidationType.IN)
public class OrderServiceImpl implements OrderServicePortType {

    private final StoreDirectory storeDirectory;
    private final SkuCatalog skuCatalog;
    private final OrderLedger orderLedger;

    public OrderServiceImpl(StoreDirectory storeDirectory, SkuCatalog skuCatalog, OrderLedger orderLedger) {
        this.storeDirectory = storeDirectory;
        this.skuCatalog = skuCatalog;
        this.orderLedger = orderLedger;
    }

    @Override
    public SubmitOrderResponse submitOrder(SubmitOrderRequest request) throws InvalidOrderFault {
        validateAudit(request.getAudit());
        validateExternalOrderRef(request.getExternalOrderRef());
        StoreRecord store = requireStore(request.getStoreId());
        requireValidLines(request.getLines());

        CurrencyCodeType currency = currencyForRegion(store.region());
        long totalAmountMinor = 0;
        List<OrderLineRecord> lines = new ArrayList<>(request.getLines().size());
        for (OrderLineInputType line : request.getLines()) {
            SkuRecord sku = skuCatalog.find(line.getSkuId());
            long unitPriceMinor = PriceList.convertFromCadMinor(sku.basePriceCadMinor(), currency);
            totalAmountMinor += unitPriceMinor * line.getQuantity();
            lines.add(new OrderLineRecord(sku.skuId(), line.getQuantity(), OrderLineStatusType.ACCEPTED));
        }

        OrderRecord record = new OrderRecord(
                orderLedger.nextOrderId(),
                request.getExternalOrderRef(),
                request.getAudit().getSourceSystem(),
                store.storeId(),
                request.getOrderChannel(),
                OrderStatusType.SUBMITTED,
                Instant.now(),
                Instant.now(),
                totalAmountMinor,
                currency,
                lines);

        Optional<String> duplicateOrderId = orderLedger.tryRegister(record);
        if (duplicateOrderId.isPresent()) {
            throw ErpFaults.invalidOrder(
                    ErpFaults.CODE_ORDER_DUPLICATE_REF,
                    "externalOrderRef '" + request.getExternalOrderRef() + "' already exists for sourceSystem '"
                            + record.sourceSystem() + "' (orderId " + duplicateOrderId.get() + ")",
                    "externalOrderRef",
                    request.getExternalOrderRef(),
                    request.getExternalOrderRef());
        }

        SubmitOrderResponse response = new SubmitOrderResponse();
        response.setOrderId(record.orderId());
        response.setExternalOrderRef(record.externalOrderRef());
        response.setStatus(record.status());
        response.setSubmittedAt(XmlDateTimes.utcTimestamp(record.placedAt()));
        response.setTotalAmount(PriceList.money(totalAmountMinor, currency));
        response.setLineCount(lines.size());
        return response;
    }

    @Override
    public GetOrderStatusResponse getOrderStatus(GetOrderStatusRequest request) throws InvalidOrderFault {
        OrderRecord record = orderLedger.find(request.getOrderId());
        if (record == null) {
            throw ErpFaults.orderUnknown(request.getOrderId());
        }

        GetOrderStatusResponse response = new GetOrderStatusResponse();
        response.setOrderId(record.orderId());
        response.setExternalOrderRef(record.externalOrderRef());
        response.setStoreId(record.storeId());
        response.setStatus(record.status());
        response.setPlacedAt(XmlDateTimes.utcTimestamp(record.placedAt()));
        response.setLastUpdatedAt(XmlDateTimes.utcTimestamp(record.lastUpdatedAt()));
        response.setTotalAmount(PriceList.money(record.totalAmountMinor(), record.currency()));
        for (OrderLineRecord line : record.lines()) {
            OrderLineStatusRecordType lineRecord = new OrderLineStatusRecordType();
            lineRecord.setSkuId(line.skuId());
            lineRecord.setQuantity(line.quantity());
            lineRecord.setLineStatus(line.lineStatus());
            response.getLines().add(lineRecord);
        }
        return response;
    }

    private void validateAudit(LegacyAuditType audit) throws InvalidOrderFault {
        if (audit == null) {
            throw ErpFaults.invalidOrder(ErpFaults.CODE_ORDER_MISSING_AUDIT,
                    "audit block is required", "audit", null, null);
        }
        if (audit.getSourceSystem() == null || audit.getSourceSystem().isBlank()) {
            throw ErpFaults.invalidOrder(ErpFaults.CODE_ORDER_MISSING_AUDIT,
                    "audit.sourceSystem is required", "audit.sourceSystem", audit.getSourceSystem(), null);
        }
        if (audit.getCorrelationId() == null || audit.getCorrelationId().isBlank()) {
            throw ErpFaults.invalidOrder(ErpFaults.CODE_ORDER_MISSING_AUDIT,
                    "audit.correlationId is required", "audit.correlationId", audit.getCorrelationId(), null);
        }
        if (audit.getReceivedAt() == null) {
            throw ErpFaults.invalidOrder(ErpFaults.CODE_ORDER_MISSING_AUDIT,
                    "audit.receivedAt is required", "audit.receivedAt", null, null);
        }
    }

    private void validateExternalOrderRef(String externalOrderRef) throws InvalidOrderFault {
        if (externalOrderRef == null || externalOrderRef.isBlank()) {
            throw ErpFaults.invalidOrder(ErpFaults.CODE_ORDER_BAD_REF,
                    "externalOrderRef is required", "externalOrderRef", externalOrderRef, externalOrderRef);
        }
    }

    private StoreRecord requireStore(String storeId) throws InvalidOrderFault {
        StoreRecord store = storeDirectory.find(storeId);
        if (store == null) {
            throw ErpFaults.invalidOrder(ErpFaults.CODE_ORDER_UNKNOWN_STORE,
                    "Unknown storeId: " + storeId, "storeId", storeId, null);
        }
        return store;
    }

    private void requireValidLines(List<OrderLineInputType> lines) throws InvalidOrderFault {
        // Schema validation guarantees at least one line; this guards the
        // unvalidated path (schema validation could be disabled operationally).
        if (lines == null || lines.isEmpty()) {
            throw ErpFaults.invalidOrder(ErpFaults.CODE_ORDER_BAD_QUANTITY,
                    "At least one order line is required", "lines", null, null);
        }
        for (OrderLineInputType line : lines) {
            if (skuCatalog.find(line.getSkuId()) == null) {
                throw ErpFaults.invalidOrder(ErpFaults.CODE_ORDER_UNKNOWN_SKU,
                        "Unknown skuId: " + line.getSkuId(), "lines.skuId", line.getSkuId(), null);
            }
            if (line.getQuantity() < 1) {
                throw ErpFaults.invalidOrder(ErpFaults.CODE_ORDER_BAD_QUANTITY,
                        "Order line quantity must be >= 1", "lines.quantity", String.valueOf(line.getQuantity()), null);
            }
        }
    }

    static CurrencyCodeType currencyForRegion(StoreRegionCodeType region) {
        return switch (region) {
            case CA -> CurrencyCodeType.CAD;
            case SG -> CurrencyCodeType.SGD;
            case CN -> CurrencyCodeType.CNY;
        };
    }
}
