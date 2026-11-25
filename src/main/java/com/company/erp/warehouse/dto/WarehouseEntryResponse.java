package com.company.erp.warehouse.dto;

import com.company.erp.warehouse.WarehouseEntry;

import java.time.Instant;
import java.util.UUID;

/** One line of the warehouse log. Quantities only: no price, cost or customer appears here. */
public record WarehouseEntryResponse(
        UUID id,
        String type,
        UUID branchId,
        String branchName,
        UUID productId,
        String productName,
        String sku,
        String unit,
        int quantity,
        int unsettledQuantity,
        String status,
        String note,
        String recipientName,
        String createdByName,
        Instant createdAt
) {
    public static WarehouseEntryResponse from(WarehouseEntry e) {
        String status = switch (e.getEntryType()) {
            case RECEIVE -> "RECEIVED";
            case ISSUE -> e.getUnsettledQuantity() > 0 ? "OPEN" : "SETTLED";
        };
        return new WarehouseEntryResponse(
                e.getId(), e.getEntryType().name(),
                e.getBranch().getId(), e.getBranch().getName(),
                e.getProduct().getId(), e.getProduct().getName(), e.getProduct().getSku(), e.getProduct().getUnit(),
                e.getQuantity(), e.getUnsettledQuantity(), status, e.getNote(), e.getRecipientName(),
                e.getCreatedBy().getName(), e.getCreatedAt());
    }
}
