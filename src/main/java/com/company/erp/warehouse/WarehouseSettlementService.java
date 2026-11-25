package com.company.erp.warehouse;

import com.company.erp.batch.BatchConsumption;
import com.company.erp.common.exception.BusinessRuleViolationException;
import com.company.erp.common.exception.ResourceNotFoundException;
import com.company.erp.customer.Customer;
import com.company.erp.customer.CustomerRepository;
import com.company.erp.payment.PaymentMethod;
import com.company.erp.notification.NotificationService;
import com.company.erp.pos.PosCheckoutService;
import com.company.erp.pos.dto.CheckoutItemRequest;
import com.company.erp.pos.dto.CheckoutRequest;
import com.company.erp.sales.SaleRepository;
import com.company.erp.sales.dto.SaleResponse;
import com.company.erp.security.BranchAccessService;
import com.company.erp.user.User;
import com.company.erp.user.UserRepository;
import com.company.erp.warehouse.dto.SettleIssueRequest;
import com.company.erp.warehouse.dto.SettleIssueResponse;
import com.company.erp.warehouse.dto.SettlementLineRequest;
import com.company.erp.warehouse.dto.WarehouseEntryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns stock the warehouse gave out into real sales, once the admin knows who got what: "of the 15 cartons, 10 went to
 * Abebe (paid), 5 to Kebede (on loan)". Each part becomes an ordinary sale (so payments, loans, the ledger, profit and
 * reports all work as usual) - but the units already left the shelf when they were given out, so the sale claims them,
 * with their real batch costs, instead of taking stock out a second time.
 * Everything in one call succeeds or fails together.
 */
@Service
@RequiredArgsConstructor
public class WarehouseSettlementService {

    private static final int DEFAULT_LOAN_DAYS = 30;

    private final WarehouseEntryRepository entryRepository;
    private final WarehouseIssueSettlementRepository settlementRepository;
    private final PosCheckoutService posCheckoutService;
    private final CustomerRepository customerRepository;
    private final SaleRepository saleRepository;
    private final UserRepository userRepository;
    private final BranchAccessService branchAccessService;
    private final NotificationService notificationService;

    @Transactional
    public SettleIssueResponse settle(UUID entryId, SettleIssueRequest request) {
        WarehouseEntry entry = entryRepository.findById(entryId)
                .orElseThrow(() -> ResourceNotFoundException.of("Warehouse entry", entryId));
        if (entry.getEntryType() != WarehouseEntryType.ISSUE) {
            throw new BusinessRuleViolationException("Only stock that was given out can be settled into sales");
        }
        branchAccessService.assertCanWriteToBranch(entry.getBranch().getId());

        int total = request.lines().stream().mapToInt(SettlementLineRequest::quantity).sum();
        if (total > entry.getUnsettledQuantity()) {
            throw new BusinessRuleViolationException("Only " + entry.getUnsettledQuantity()
                    + " units of this entry are left to settle, but " + total + " were entered");
        }

        LocalDate saleDate = request.saleDate() != null ? request.saleDate()
                : LocalDate.ofInstant(entry.getCreatedAt(), ZoneId.systemDefault());
        if (saleDate.isAfter(LocalDate.now())) {
            throw new BusinessRuleViolationException("The date cannot be in the future");
        }
        User admin = userRepository.findById(branchAccessService.currentUser().getUserId()).orElseThrow();

        // The sales take their units (and costs) from what this entry already took off the shelf, oldest first.
        PosCheckoutService.StockSource source = (product, quantity) -> claim(entry, quantity);

        List<String> saleNumbers = new ArrayList<>();
        for (SettlementLineRequest line : request.lines()) {
            BigDecimal gross = line.unitPrice().multiply(BigDecimal.valueOf(line.quantity()));
            BigDecimal discount = line.discountAmount() != null ? line.discountAmount() : BigDecimal.ZERO;
            BigDecimal due = gross.subtract(discount).max(BigDecimal.ZERO);
            BigDecimal paid = line.amountPaid() != null ? line.amountPaid() : due;   // blank = paid in full
            PaymentMethod method = line.paymentMethod() != null ? line.paymentMethod() : PaymentMethod.CASH;
            if (method == PaymentMethod.BANK && paid.signum() > 0 && line.bankId() == null) {
                throw new BusinessRuleViolationException("Choose the bank this payment went to");
            }
            Customer customer = resolveCustomer(line);

            CheckoutRequest checkout = new CheckoutRequest(
                    entry.getBranch().getId(),
                    customer != null ? customer.getId() : null,
                    List.of(new CheckoutItemRequest(entry.getProduct().getId(), line.quantity(), line.unitPrice(), null)),
                    discount, method, paid, null, null,
                    saleDate.plusDays(DEFAULT_LOAN_DAYS),
                    method == PaymentMethod.BANK ? line.bankId() : null);

            SaleResponse sale = posCheckoutService.checkout(checkout, PosCheckoutService.SaleContext.fromIssuedStock(saleDate, source));
            saleNumbers.add(sale.saleNumber());

            WarehouseIssueSettlement link = new WarehouseIssueSettlement();
            link.setEntry(entry);
            link.setSale(saleRepository.getReferenceById(sale.id()));
            link.setQuantity(line.quantity());
            link.setCreatedBy(admin);
            settlementRepository.save(link);
        }

        entry.setUnsettledQuantity(entry.getUnsettledQuantity() - total);
        entryRepository.save(entry);
        if (entry.getUnsettledQuantity() == 0) {
            // fully settled: the "Warehouse gave out stock" notification no longer waits for anybody's review
            notificationService.approveByReference(NotificationService.WAREHOUSE_ENTRY, entry.getId(), admin);
        }
        return new SettleIssueResponse(WarehouseEntryResponse.from(entry), saleNumbers);
    }

    /** Takes {@code quantity} units from the entry's remembered batches, oldest first, and returns their real costs. */
    private List<BatchConsumption> claim(WarehouseEntry entry, int quantity) {
        List<BatchConsumption> taken = new ArrayList<>();
        int needed = quantity;
        for (WarehouseIssueAllocation a : entry.getAllocations()) {
            if (needed == 0) {
                break;
            }
            int take = Math.min(needed, a.getRemainingQuantity());
            if (take <= 0) {
                continue;
            }
            a.setRemainingQuantity(a.getRemainingQuantity() - take);
            taken.add(new BatchConsumption(a.getBatch().getId(), a.getBatch().getBatchNumber(), take, a.getUnitCost()));
            needed -= take;
        }
        if (needed > 0) {
            throw new BusinessRuleViolationException("Not enough given-out stock is left on this entry");
        }
        return taken;
    }

    private Customer resolveCustomer(SettlementLineRequest line) {
        if (line.customerId() != null) {
            return customerRepository.findById(line.customerId())
                    .orElseThrow(() -> ResourceNotFoundException.of("Customer", line.customerId()));
        }
        if (line.customerName() == null || line.customerName().isBlank()) {
            return null;
        }
        String name = line.customerName().trim().replaceAll("\\s+", " ");
        return customerRepository.findFirstByNameIgnoreCase(name).orElseGet(() -> {
            Customer c = new Customer();
            c.setName(name);
            c.setActive(true);
            return customerRepository.save(c);
        });
    }
}
