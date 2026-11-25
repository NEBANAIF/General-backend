package com.company.erp.warehouse;

import com.company.erp.common.dto.PageResponse;
import com.company.erp.security.BranchAccessService;
import com.company.erp.warehouse.dto.SettleIssueRequest;
import com.company.erp.warehouse.dto.SettleIssueResponse;
import com.company.erp.warehouse.dto.WarehouseEntryResponse;
import com.company.erp.warehouse.dto.WarehouseMovementRequest;
import com.company.erp.warehouse.dto.WarehouseProductResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The warehouse operator records stock in and out (WAREHOUSE_LOG: quantities only, nothing priced); the admin settles
 * given-out stock into sales (WAREHOUSE_SETTLE).
 */
@RestController
@RequestMapping("/api/warehouse")
@RequiredArgsConstructor
public class WarehouseController {

    private final WarehouseService warehouseService;
    private final WarehouseSettlementService settlementService;

    @GetMapping("/products")
    @PreAuthorize("hasAuthority('WAREHOUSE_LOG')")
    public PageResponse<WarehouseProductResponse> products(@RequestParam(required = false) UUID branchId,
                                                           @RequestParam(required = false) String search,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        return warehouseService.products(branchId, search, page, size);
    }

    @PostMapping("/receive")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('WAREHOUSE_LOG')")
    public WarehouseEntryResponse receive(@Valid @RequestBody WarehouseMovementRequest request) {
        return warehouseService.receive(request);
    }

    @PostMapping("/issue")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('WAREHOUSE_LOG')")
    public WarehouseEntryResponse issue(@Valid @RequestBody WarehouseMovementRequest request) {
        return warehouseService.issue(request);
    }

    @GetMapping("/entries")
    @PreAuthorize("hasAnyAuthority('WAREHOUSE_LOG', 'WAREHOUSE_SETTLE')")
    public PageResponse<WarehouseEntryResponse> entries(
            @RequestParam(defaultValue = "ALL") BranchAccessService.BranchScope scope,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) WarehouseEntryType type,
            @RequestParam(required = false) String settlement,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return warehouseService.entries(scope, branchId, type, settlement, search, from, to, page, size);
    }

    @GetMapping("/entries/{id}")
    @PreAuthorize("hasAnyAuthority('WAREHOUSE_LOG', 'WAREHOUSE_SETTLE')")
    public WarehouseEntryResponse entry(@PathVariable UUID id) {
        return warehouseService.getEntry(id);
    }

    @PostMapping("/entries/{id}/settle")
    @PreAuthorize("hasAuthority('WAREHOUSE_SETTLE')")
    public SettleIssueResponse settle(@PathVariable UUID id, @Valid @RequestBody SettleIssueRequest request) {
        return settlementService.settle(id, request);
    }
}
