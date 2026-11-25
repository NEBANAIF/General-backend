package com.company.erp.dashboard;

import com.company.erp.batch.ProductBatchRepository;
import com.company.erp.expense.ExpenseRepository;
import com.company.erp.finance.FinanceReportService;
import com.company.erp.finance.dto.BranchPnLResponse;
import com.company.erp.branch.BranchRepository;
import com.company.erp.inventory.ProductStockService;
import com.company.erp.inventory.StockStatus;
import com.company.erp.inventory.dto.StockAttentionRow;
import com.company.erp.inventory.dto.StockStatusReportResponse;
import com.company.erp.inventory.dto.StockSummaryResponse;
import com.company.erp.report.ReportService;
import com.company.erp.sales.Sale;
import com.company.erp.sales.SaleRepository;
import com.company.erp.security.BranchAccessService;
import com.company.erp.transfer.StockTransfer;
import com.company.erp.transfer.StockTransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock private FinanceReportService financeReportService;
    @Mock private ReportService reportService;
    @Mock private BranchAccessService branchAccessService;
    @Mock private SaleRepository saleRepository;
    @Mock private StockTransferRepository stockTransferRepository;
    @Mock private ExpenseRepository expenseRepository;
    @Mock private ProductStockService productStockService;
    @Mock private BranchRepository branchRepository;
    @Mock private ProductBatchRepository productBatchRepository;

    private DashboardService service;

    private final UUID branchId = UUID.randomUUID();
    private final LocalDate from = LocalDate.of(2026, 9, 16);
    private final LocalDate to = LocalDate.of(2026, 9, 16);

    @BeforeEach
    void setUp() {
        service = new DashboardService(financeReportService, reportService, branchAccessService, saleRepository,
                stockTransferRepository, expenseRepository, productStockService, branchRepository, productBatchRepository);

        lenient().when(branchAccessService.resolveReadableBranchIds(any(), any())).thenReturn(List.of(branchId));
        lenient().when(financeReportService.getPnL(any(), any(), any(), any())).thenReturn(
                new BranchPnLResponse(from, to, BigDecimal.valueOf(3000), BigDecimal.valueOf(2200),
                        BigDecimal.valueOf(800), BigDecimal.valueOf(100), BigDecimal.valueOf(700), BigDecimal.valueOf(23.33)));
        lenient().when(productBatchRepository.sumInventoryValueForBranches(any())).thenReturn(BigDecimal.valueOf(67000));
        lenient().when(productStockService.report(any(), any())).thenReturn(
                new StockStatusReportResponse(new StockSummaryResponse(44, 40, 3, 1), List.of()));
        Page<Sale> emptySales = new PageImpl<>(List.of());
        lenient().when(saleRepository.search(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(emptySales);
        Page<StockTransfer> emptyTransfers = new PageImpl<>(List.of());
        lenient().when(stockTransferRepository.search(any(), any(), any(), any())).thenReturn(emptyTransfers);
        lenient().when(expenseRepository.search(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        lenient().when(reportService.getBranchComparison(any(), any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void summaryCombinesPnlWithInventoryAndStockLevelKpis() {
        var result = service.getSummary(BranchAccessService.BranchScope.ALL, null, from, to);

        assertThat(result.revenue()).isEqualByComparingTo("3000");
        assertThat(result.cogs()).isEqualByComparingTo("2200");
        assertThat(result.grossProfit()).isEqualByComparingTo("800");
        assertThat(result.inventoryValue()).isEqualByComparingTo("67000");
        assertThat(result.lowStockCount()).isEqualTo(3L);
        assertThat(result.outOfStockCount()).isEqualTo(1L);
        assertThat(result.inStockCount()).isEqualTo(40L);
    }

    @Test
    void neverStockedProductsCountAsOutOfStockAndAppearInTheAlerts() {
        // A product that was created but never received any stock has NO inventory row. It must still be
        // out of stock on the dashboard (the old row-counting query reported 0 for exactly this case).
        UUID productId = UUID.randomUUID();
        when(productStockService.report(any(), any())).thenReturn(new StockStatusReportResponse(
                new StockSummaryResponse(5, 2, 1, 2),
                List.of(new StockAttentionRow(productId, "Pro 5s Earphone", "PRO5S", "pcs", 0, 10, StockStatus.OUT_OF_STOCK))));

        var result = service.getSummary(BranchAccessService.BranchScope.ALL, null, from, to);

        assertThat(result.outOfStockCount()).isEqualTo(2L);
        assertThat(result.lowStockAlerts()).hasSize(1);
        assertThat(result.lowStockAlerts().get(0).productName()).isEqualTo("Pro 5s Earphone");
        assertThat(result.lowStockAlerts().get(0).availableQuantity()).isZero();
        assertThat(result.lowStockAlerts().get(0).branchName()).isEqualTo("All branches");
    }

    @Test
    void lowStockAndOutOfStockAreDistinctCounts() {
        var result = service.getSummary(BranchAccessService.BranchScope.ALL, null, from, to);

        // low and out are separate buckets of the same per-product report - no overlap.
        assertThat(result.lowStockCount()).isNotEqualTo(result.outOfStockCount());
    }
}
