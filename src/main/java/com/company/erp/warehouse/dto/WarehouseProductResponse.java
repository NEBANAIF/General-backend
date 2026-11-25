package com.company.erp.warehouse.dto;

import java.util.UUID;

/** What the warehouse operator sees of a product: its name and how many units are on the shelf. Never a price or cost. */
public record WarehouseProductResponse(UUID id, String name, String sku, String unit, int quantity) {
}
