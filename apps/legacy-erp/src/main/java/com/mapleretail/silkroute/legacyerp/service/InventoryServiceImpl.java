package com.mapleretail.silkroute.legacyerp.service;

import org.apache.cxf.annotations.SchemaValidation;
import org.apache.cxf.annotations.SchemaValidation.SchemaValidationType;
import org.springframework.stereotype.Service;

import com.mapleretail.silkroute.legacyerp.contract.common.StoreRegionCodeType;
import com.mapleretail.silkroute.legacyerp.contract.inventory.GetStockRequest;
import com.mapleretail.silkroute.legacyerp.contract.inventory.GetStockResponse;
import com.mapleretail.silkroute.legacyerp.contract.inventory.ReleaseRequest;
import com.mapleretail.silkroute.legacyerp.contract.inventory.ReleaseResponse;
import com.mapleretail.silkroute.legacyerp.contract.inventory.ReserveRequest;
import com.mapleretail.silkroute.legacyerp.contract.inventory.ReserveResponse;
import com.mapleretail.silkroute.legacyerp.domain.StoreRecord;
import com.mapleretail.silkroute.legacyerp.fault.ErpFaults;
import com.mapleretail.silkroute.legacyerp.ledger.InventoryLedger;
import com.mapleretail.silkroute.legacyerp.ledger.StoreDirectory;
import com.mapleretail.silkroute.legacyerp.time.XmlDateTimes;

import maple.erp.inventory.v1.InventoryServicePortType;
import maple.erp.inventory.v1.OutOfStockFault;
import maple.erp.inventory.v1.UnknownReservationFault;

/**
 * InventoryService implementation. reserve() validates store/sku/quantity and
 * availability; every reserve() rejection rides the operation's only declared
 * typed fault (OutOfStockFault) with a distinct errorCode. getStock() declares no
 * fault in the frozen contract, so unknown store/SKU references there surface as
 * a generic SOAP server fault (returning fabricated zeros would mislead the
 * Phase 2 region routing).
 */
@Service
@SchemaValidation(type = SchemaValidationType.IN)
public class InventoryServiceImpl implements InventoryServicePortType {

    private final InventoryLedger inventoryLedger;
    private final StoreDirectory storeDirectory;

    public InventoryServiceImpl(InventoryLedger inventoryLedger, StoreDirectory storeDirectory) {
        this.inventoryLedger = inventoryLedger;
        this.storeDirectory = storeDirectory;
    }

    @Override
    public ReserveResponse reserve(ReserveRequest request) throws OutOfStockFault {
        String storeId = request.getStoreId();
        String skuId = request.getSkuId();
        int quantity = request.getQuantity();
        if (quantity < 1) {
            InventoryLedger.StockSnapshot snapshot = inventoryLedger.snapshot(storeId, skuId);
            throw ErpFaults.outOfStock(
                    ErpFaults.CODE_INVENTORY_BAD_QUANTITY,
                    "Reserve quantity must be >= 1",
                    storeId, skuId, quantity, snapshot == null ? 0 : snapshot.available());
        }

        InventoryLedger.ReserveAttempt attempt = inventoryLedger.tryReserve(storeId, skuId, quantity);
        switch (attempt.outcome()) {
            case UNKNOWN_STORE -> throw ErpFaults.outOfStock(
                    ErpFaults.CODE_INVENTORY_UNKNOWN_STORE,
                    "Unknown storeId: " + storeId,
                    storeId, skuId, quantity, attempt.availableQuantity());
            case UNKNOWN_SKU -> throw ErpFaults.outOfStock(
                    ErpFaults.CODE_INVENTORY_UNKNOWN_SKU,
                    "Unknown skuId: " + skuId,
                    storeId, skuId, quantity, attempt.availableQuantity());
            case INSUFFICIENT -> throw ErpFaults.outOfStock(
                    ErpFaults.CODE_INVENTORY_OUT_OF_STOCK,
                    "Insufficient stock at " + storeId + " for " + skuId
                            + ": requested " + quantity + ", available " + attempt.availableQuantity(),
                    storeId, skuId, quantity, attempt.availableQuantity());
            case RESERVED -> {
                ReserveResponse response = new ReserveResponse();
                response.setReservationId(attempt.reservationId());
                response.setStoreId(storeId);
                response.setSkuId(skuId);
                response.setReservedQuantity(quantity);
                response.setRemainingAvailableQuantity(attempt.remainingAvailableQuantity());
                response.setReservedUntil(XmlDateTimes.utcTimestamp(attempt.reservedUntil()));
                return response;
            }
        }
        throw new IllegalStateException("Unreachable reserve outcome: " + attempt.outcome());
    }

    @Override
    public ReleaseResponse release(ReleaseRequest request) throws UnknownReservationFault {
        InventoryLedger.ReleaseAttempt attempt = inventoryLedger.release(
                request.getReservationId(), request.getQuantity());
        if (attempt.outcome() == InventoryLedger.ReleaseOutcome.UNKNOWN_RESERVATION) {
            throw ErpFaults.reservationUnknown(request.getReservationId());
        }

        ReleaseResponse response = new ReleaseResponse();
        response.setReservationId(request.getReservationId());
        response.setReleasedQuantity(attempt.releasedQuantity());
        response.setRemainingAvailableQuantity(attempt.remainingAvailableQuantity());
        response.setFullyReleased(attempt.fullyReleased());
        return response;
    }

    @Override
    public GetStockResponse getStock(GetStockRequest request) {
        StoreRecord store = storeDirectory.find(request.getStoreId());
        if (store == null) {
            throw new IllegalArgumentException("Unknown storeId: " + request.getStoreId());
        }
        InventoryLedger.StockSnapshot snapshot = inventoryLedger.snapshot(request.getStoreId(), request.getSkuId());
        if (snapshot == null) {
            throw new IllegalArgumentException("Unknown skuId " + request.getSkuId()
                    + " at store " + request.getStoreId());
        }

        GetStockResponse response = new GetStockResponse();
        // Region stays prominent in the response: Phase 2 routes on it.
        response.setRegion(store.region());
        response.setStoreId(store.storeId());
        response.setSkuId(request.getSkuId());
        response.setOnHandQuantity(snapshot.onHand());
        response.setReservedQuantity(snapshot.reserved());
        response.setAvailableQuantity(snapshot.available());
        response.setAsOf(XmlDateTimes.nowUtc());
        return response;
    }
}
