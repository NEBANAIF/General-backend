package com.company.erp.warehouse.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * "N units of this product were received" / "were given out" (name: optional, who took it - used for given-out only). branchId only matters for a user who is not tied to one
 * warehouse (a Super Admin); everyone else always works on their own warehouse.
 */
public record WarehouseMovementRequest(
        UUID branchId,
        @NotNull UUID productId,
        @Min(value = 1, message = "must be at least 1") int quantity,
        @Size(max = 500, message = "must be at most 500 characters") String note,
        @Size(max = 200, message = "must be at most 200 characters") String name
) {
}
