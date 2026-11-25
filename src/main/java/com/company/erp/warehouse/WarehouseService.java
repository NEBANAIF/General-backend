package com.company.erp.warehouse;

import com.company.erp.batch.BatchConsumption;
import com.company.erp.batch.ProductBatchRepository;
import com.company.erp.branch.Branch;
import com.company.erp.branch.BranchRepository;
import com.company.erp.branch.BranchType;
import com.company.erp.common.dto.PageResponse;
import com.company.erp.common.exception.BusinessRuleViolationException;
import com.company.erp.common.exception.ResourceNotFoundException;
import com.company.erp.inventory.Inventory;
import com.company.erp.inventory.InventoryRepository;
import com.company.erp.inventory.StockMutationService;
import com.company.erp.product.Product;
import com.company.erp.product.ProductRepository;
import com.company.erp.productbranch.ProductBranchAvailabilityService;
import com.company.erp.security.BranchAccessService;
import com.company.erp.stockhistory.StockMovementType;
import com.company.erp.stockhistory.StockReferenceType;
import com.company.erp.user.User;
import com.company.erp.user.UserRepository;
import com.company.erp.warehouse.dto.WarehouseEntryResponse;
import com.company.erp.warehouse.dto.WarehouseMovementRequest;
import com.company.erp.warehouse.dto.WarehouseProductResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the warehouse operator does: record stock received and stock given out - quantities only.
 * <ul>
 *   <li>Received: a new batch is created with cost 0 (the operator does not know the price); a manager corrects the
 *       cost later (Batches > Edit batch). The units are on the shelf at once.</li>
 *   <li>Given out: the units leave the shelf at once, first-in-first-out, and the batches/costs they came from are
 *       remembered on the entry. Who they were for and at what price is filled in later by the admin
 *       ({@link WarehouseSettlementService}).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class WarehouseService {

    static final int MAX_PAGE_SIZE = 100;

    private final WarehouseEntryRepository entryRepository;
    private final BranchRepository branchRepository;
    private final ProductRepository productRepository;
    private final ProductBatchRepository productBatchRepository;
    private final UserRepository userRepository;
    private final InventoryRepository inventoryRepository;
    private final StockMutationService stockMutationService;
    private final BranchAccessService branchAccessService;
    private final ProductBranchAvailabilityService productBranchAvailabilityService;

    // ------------------------------------------------------------------ the operator's product list

    /** Products available at the warehouse with the units on the shelf. Search by name or SKU. */
    @Transactional(readOnly = true)
    public PageResponse<WarehouseProductResponse> products(UUID requestedBranchId, String search, int page, int size) {
        UUID branchId = resolveBranch(requestedBranchId);
        Set<UUID> allowed = productBranchAvailabilityService.allowedProductIds(branchId);
        Page<Product> products = productRepository.search(true, search, allowed, PageRequest.of(
                Math.max(page, 0), clamp(size),
                Sort.by(Sort.Order.asc("name").ignoreCase(), Sort.Order.asc("id"))));

        List<UUID> ids = products.getContent().stream().map(Product::getId).toList();
        Map<UUID, Integer> onShelf = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Inventory inv : inventoryRepository.findByBranchIdInAndProductIdIn(List.of(branchId), ids)) {
                onShelf.put(inv.getProduct().getId(), inv.getQuantity());
            }
        }
        return PageResponse.of(products.map(p -> new WarehouseProductResponse(
                p.getId(), p.getName(), p.getSku(), p.getUnit(), onShelf.getOrDefault(p.getId(), 0))));
    }

    // ------------------------------------------------------------------ recording

    @Transactional
    public WarehouseEntryResponse receive(WarehouseMovementRequest request) {
        Movement m = prepare(request, WarehouseEntryType.RECEIVE);
        stockMutationService.receiveStock(m.product, m.branch, request.quantity(), BigDecimal.ZERO, LocalDate.now(),
                StockMovementType.WAREHOUSE_RECEIPT, reason("Warehouse received", null, request.note()), m.user,
                StockReferenceType.WAREHOUSE_ENTRY, m.entry.getId());
        return WarehouseEntryResponse.from(m.entry);
    }

    @Transactional
    public WarehouseEntryResponse issue(WarehouseMovementRequest request) {
        Movement m = prepare(request, WarehouseEntryType.ISSUE);
        List<BatchConsumption> consumptions = stockMutationService.issueStock(m.product, m.branch, request.quantity(),
                StockMovementType.WAREHOUSE_ISSUE, reason("Warehouse gave out", clean(request.name()), request.note()), m.user,
                StockReferenceType.WAREHOUSE_ENTRY, m.entry.getId());

        int position = 0;
        for (BatchConsumption c : consumptions) {
            WarehouseIssueAllocation a = new WarehouseIssueAllocation();
            a.setBatch(productBatchRepository.getReferenceById(c.batchId()));
            a.setPosition(position++);
            a.setQuantity(c.quantityConsumed());
            a.setRemainingQuantity(c.quantityConsumed());
            a.setUnitCost(c.unitCost());
            m.entry.addAllocation(a);
        }
        m.entry.setUnsettledQuantity(request.quantity());
        entryRepository.save(m.entry);
        return WarehouseEntryResponse.from(m.entry);
    }

    // ------------------------------------------------------------------ the log

    /**
     * The log, newest first. {@code settlement}: null/"ALL" everything, "OPEN" given-out entries with units still to
     * settle, "SETTLED" given-out entries that are fully done.
     */
    @Transactional(readOnly = true)
    public PageResponse<WarehouseEntryResponse> entries(BranchAccessService.BranchScope scope, UUID branchId,
                                                        WarehouseEntryType type, String settlement, String search,
                                                        LocalDate from, LocalDate to, int page, int size) {
        // Only warehouse branches have a warehouse log: a store branch (even one picked in the admin's branch filter) has none.
        List<UUID> branchIds = branchRepository.findAllById(branchAccessService.resolveReadableBranchIds(scope, branchId))
                .stream().filter(b -> b.getType() == BranchType.WAREHOUSE).map(Branch::getId).toList();
        Boolean open = "OPEN".equalsIgnoreCase(settlement) ? Boolean.TRUE
                : "SETTLED".equalsIgnoreCase(settlement) ? Boolean.FALSE : null;
        ZoneId zone = ZoneId.systemDefault();
        Instant fromInstant = from != null ? from.atStartOfDay(zone).toInstant() : null;
        Instant toInstant = to != null ? to.plusDays(1).atStartOfDay(zone).toInstant() : null;
        Page<WarehouseEntry> result = entryRepository.search(branchIds, type, open, search, fromInstant, toInstant,
                PageRequest.of(Math.max(page, 0), clamp(size)));
        return PageResponse.of(result.map(WarehouseEntryResponse::from));
    }

    // ------------------------------------------------------------------ helpers

    private record Movement(WarehouseEntry entry, Product product, Branch branch, User user) { }

    /** Validates the request and saves the (still empty) entry so its id can be written to the stock ledger. */
    private Movement prepare(WarehouseMovementRequest request, WarehouseEntryType type) {
        UUID branchId = resolveBranch(request.branchId());
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> ResourceNotFoundException.of("Branch", branchId));
        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> ResourceNotFoundException.of("Product", request.productId()));
        if (!productBranchAvailabilityService.allowedProductIds(branchId).contains(product.getId())) {
            throw new BusinessRuleViolationException("That product is not available at this warehouse");
        }
        User user = userRepository.findById(branchAccessService.currentUser().getUserId()).orElseThrow();

        WarehouseEntry entry = new WarehouseEntry();
        entry.setEntryType(type);
        entry.setBranch(branch);
        entry.setProduct(product);
        entry.setQuantity(request.quantity());
        entry.setUnsettledQuantity(0);
        entry.setNote(request.note() == null || request.note().isBlank() ? null : request.note().trim());
        entry.setRecipientName(type == WarehouseEntryType.ISSUE ? clean(request.name()) : null);
        entry.setCreatedBy(user);
        entry = entryRepository.save(entry);
        return new Movement(entry, product, branch, user);
    }

    /** The operator works on his own warehouse; a user not tied to one (Super Admin) names it. */
    private UUID resolveBranch(UUID requested) {
        UUID id = requested != null ? requested : branchAccessService.currentUser().getBranchId();
        if (id == null) {
            throw new BusinessRuleViolationException("Choose a warehouse first");
        }
        branchAccessService.assertCanWriteToBranch(id);
        Branch branch = branchRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("Branch", id));
        if (branch.getType() != BranchType.WAREHOUSE) {
            throw new BusinessRuleViolationException("The Warehouse page is only for warehouse branches");
        }
        return id;
    }

    /** One log line (the notification's "settle" button opens this entry). */
    @Transactional(readOnly = true)
    public WarehouseEntryResponse getEntry(UUID id) {
        WarehouseEntry entry = entryRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Warehouse entry", id));
        UUID branchId = entry.getBranch().getId();
        if (!branchAccessService.canReadEitherBranch(branchId, branchId)) {
            throw new com.company.erp.common.exception.ForbiddenException("You do not have access to this branch");
        }
        return WarehouseEntryResponse.from(entry);
    }

    private static String clean(String text) {
        return text == null || text.isBlank() ? null : text.trim().replaceAll("\\s+", " ");
    }

    private static String reason(String label, String name, String note) {
        String head = name == null ? label : label + " to " + name;
        String text = note == null || note.isBlank() ? head : head + " — " + note.trim();
        return text.length() > 250 ? text.substring(0, 250) : text;
    }

    private static int clamp(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
