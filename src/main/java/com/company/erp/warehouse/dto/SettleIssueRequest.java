package com.company.erp.warehouse.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.time.LocalDate;
import java.util.List;

/** The admin's breakdown of a given-out entry. saleDate defaults to the day the stock was given out. */
public record SettleIssueRequest(
        @NotEmpty @Valid List<SettlementLineRequest> lines,
        LocalDate saleDate
) {
}
