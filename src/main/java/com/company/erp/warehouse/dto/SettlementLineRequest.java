package com.company.erp.warehouse.dto;

import com.company.erp.payment.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One part of a given-out entry that became a sale: "10 of the cartons went to this customer at this price, paid in full".
 * Customer: an existing customerId, or a customerName (created if new), or neither for a walk-in who paid in full.
 * amountPaid left empty = paid in full; 0 = all on loan; in between = partly paid (the rest becomes a customer loan).
 */
public record SettlementLineRequest(
        UUID customerId,
        String customerName,
        @Min(value = 1, message = "must be at least 1") int quantity,
        @NotNull @DecimalMin(value = "0.0", message = "must be 0 or more") BigDecimal unitPrice,
        @DecimalMin(value = "0.0", message = "must be 0 or more") BigDecimal discountAmount,
        @DecimalMin(value = "0.0", message = "must be 0 or more") BigDecimal amountPaid,
        PaymentMethod paymentMethod,
        UUID bankId
) {
}
