package com.company.erp.warehouse;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WarehouseIssueSettlementRepository extends JpaRepository<WarehouseIssueSettlement, UUID> {
}
