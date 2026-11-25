package com.company.erp.warehouse;

import com.company.erp.branch.Branch;
import com.company.erp.common.exception.BusinessRuleViolationException;
import com.company.erp.customer.CustomerRepository;
import com.company.erp.pos.PosCheckoutService;
import com.company.erp.sales.SaleRepository;
import com.company.erp.security.BranchAccessService;
import com.company.erp.user.UserRepository;
import com.company.erp.warehouse.dto.SettleIssueRequest;
import com.company.erp.warehouse.dto.SettlementLineRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WarehouseSettlementServiceTest {

    @Mock private WarehouseEntryRepository entryRepository;
    @Mock private WarehouseIssueSettlementRepository settlementRepository;
    @Mock private PosCheckoutService posCheckoutService;
    @Mock private CustomerRepository customerRepository;
    @Mock private SaleRepository saleRepository;
    @Mock private UserRepository userRepository;
    @Mock private BranchAccessService branchAccessService;
    @Mock private com.company.erp.notification.NotificationService notificationService;
    @InjectMocks private WarehouseSettlementService service;

    private static SettleIssueRequest sell(int quantity) {
        return new SettleIssueRequest(List.of(new SettlementLineRequest(
                null, "Abebe", quantity, new BigDecimal("10.00"), null, null, null, null)), null);
    }

    private WarehouseEntry entry(WarehouseEntryType type, int unsettled) {
        WarehouseEntry e = new WarehouseEntry();
        e.setEntryType(type);
        e.setBranch(mock(Branch.class));
        e.setQuantity(10);
        e.setUnsettledQuantity(unsettled);
        return e;
    }

    @Test
    void refusesToSettleMoreThanIsLeft() {
        UUID id = UUID.randomUUID();
        when(entryRepository.findById(id)).thenReturn(Optional.of(entry(WarehouseEntryType.ISSUE, 5)));

        assertThatThrownBy(() -> service.settle(id, sell(6)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only 5 units");
        verifyNoInteractions(posCheckoutService);   // no sale may be created when the quantities do not fit
    }

    @Test
    void aReceiptCannotBeSettled() {
        UUID id = UUID.randomUUID();
        when(entryRepository.findById(id)).thenReturn(Optional.of(entry(WarehouseEntryType.RECEIVE, 0)));

        assertThatThrownBy(() -> service.settle(id, sell(1)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only stock that was given out");
        verifyNoInteractions(posCheckoutService);
    }
}
