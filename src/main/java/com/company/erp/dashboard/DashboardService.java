package com.company.erp.dashboard;

import com.company.erp.batch.ProductBatchRepository;
import com.company.erp.branch.BranchRepository;
import com.company.erp.dashboard.dto.DashboardSummaryResponse;
import com.company.erp.dashboard.dto.LowStockAlertResponse;
import com.company.erp.expense.ExpenseRepository;
import com.company.erp.expense.dto.ExpenseResponse;
import com.company.erp.finance.FinanceReportService;
import com.company.erp.inventory.ProductStockService;
import com.company.erp.inventory.dto.StockStatusReportResponse;
import com.company.erp.report.ReportService;
import com.company.erp.sales.SaleRepository;
import com.company.erp.sales.SaleStatus;
import com.company.erp.sales.dto.SaleSummaryResponse;
import com.company.erp.security.BranchAccessService;
import com.company.erp.transfer.StockTransferRepository;
import com.company.erp.transfer.dto.TransferSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Backs the dashboard's first screen (spec section 26) in one call: the
 * compact KPI strip, a handful of recent-activity rows per module, low
 * stock alerts, and branch performance. Everything here is read directly
 * from source tables / FinanceReportService / ReportService - nothing is
 * cached or precomputed, so the numbers are always exactly as current as
 * the database.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final int RECENT_ACTIVITY_LIMIT = 5;
    private static final int LOW_STOCK_ALERT_LIMIT = 20;

    private final FinanceReportService financeReportService;
    private final ReportService reportService;
    private final BranchAccessService branchAccessService;
    private final SaleRepository saleRepository;
    private final StockTransferRepository stockTransferRepository;
    private final ExpenseRepository expenseRepository;
    private final ProductStockService productStockService;
    private final BranchRepository branchRepository;
    private final ProductBatchRepository productBatchRepository;

    @Transactional(readOnly = true)
    public DashboardSummaryResponse getSummary(BranchAccessService.BranchScope scope, UUID specificBranchId,
                                                LocalDate from, LocalDate to) {
        List<UUID> branchIds = branchAccessService.resolveReadableBranchIds(scope, specificBranchId);

        var pnl = financeReportService.getPnL(scope, specificBranchId, from, to);

        ZoneId zone = ZoneId.systemDefault();
        Instant fromInstant = from.atStartOfDay(zone).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1);
        long totalSalesCount = saleRepository.search(branchIds, null, null, SaleStatus.COMPLETED,
                fromInstant, toInstant, null, PageRequest.of(0, 1)).getTotalElements();

        // Inventory value/stock levels are a CURRENT snapshot, not scoped
        // to [from, to] - "how much stock is on hand right now" doesn't
        // have a date range, unlike the P&L figures above it.
        var inventoryValue = productBatchRepository.sumInventoryValueForBranches(branchIds);
        //
        // Stock status is counted PER PRODUCT over the selected branches - the same figures as the Reports page and
        // the Products page's "Out of stock" filter. A product that has never been stocked has no inventory row at
        // all, and counts as out of stock (0 available); counting stock rows alone missed exactly those products.
        StockStatusReportResponse stock = productStockService.report(scope, specificBranchId);
        long lowStockCount = stock.summary().lowStock();
        long outOfStockCount = stock.summary().outOfStock();
        long inStockCount = stock.summary().inStock();
        String stockScopeLabel = stockScopeLabel(branchIds);
        List<LowStockAlertResponse> lowStockAlerts = stock.needsAttention().stream()
                .limit(LOW_STOCK_ALERT_LIMIT)
                .map(r -> new LowStockAlertResponse(r.productId(), r.productName(), r.sku(), null,
                        stockScopeLabel, r.available(), r.reorderLevel()))
                .toList();

        List<SaleSummaryResponse> recentSales = saleRepository
                .search(branchIds, null, null, null, null, null, null, PageRequest.of(0, RECENT_ACTIVITY_LIMIT))
                .map(SaleSummaryResponse::from).getContent();
        List<TransferSummaryResponse> recentTransfers = stockTransferRepository
                .search(branchIds, null, null, PageRequest.of(0, RECENT_ACTIVITY_LIMIT))
                .map(TransferSummaryResponse::from).getContent();
        List<ExpenseResponse> recentExpenses = expenseRepository
                .search(branchIds, null, null, null, PageRequest.of(0, RECENT_ACTIVITY_LIMIT))
                .map(ExpenseResponse::from).getContent();

        var branchPerformance = reportService.getBranchComparison(scope, specificBranchId, from, to);

        return new DashboardSummaryResponse(from, to, totalSalesCount, pnl.revenue(), pnl.cogs(), pnl.grossProfit(),
                pnl.totalExpenses(), pnl.netProfit(), pnl.profitMarginPercent(), inventoryValue,
                lowStockCount, outOfStockCount, inStockCount, recentSales, recentTransfers, recentExpenses,
                lowStockAlerts, branchPerformance);
    }

    /** "Bole Store" when exactly one branch is in view, otherwise "All branches" (quantities are summed across them). */
    private String stockScopeLabel(List<UUID> branchIds) {
        if (branchIds.size() == 1) {
            return branchRepository.findById(branchIds.get(0)).map(b -> b.getName()).orElse("All branches");
        }
        return "All branches";
    }
}
