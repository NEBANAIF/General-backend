package com.company.erp.warehouse.dto;

import java.util.List;

public record SettleIssueResponse(WarehouseEntryResponse entry, List<String> saleNumbers) {
}
