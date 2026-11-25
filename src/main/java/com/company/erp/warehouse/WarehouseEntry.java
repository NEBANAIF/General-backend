package com.company.erp.warehouse;

import com.company.erp.branch.Branch;
import com.company.erp.common.audit.BaseEntity;
import com.company.erp.product.Product;
import com.company.erp.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * One line of the warehouse log: "N units of product P were received (or given out) at this warehouse".
 * The operator records nothing else - no prices, no customers. For a given-out entry,
 * {@code unsettledQuantity} counts the units the admin has not yet turned into sales.
 */
@Getter
@Setter
@Entity
@Table(name = "warehouse_entries")
public class WarehouseEntry extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 10)
    private WarehouseEntryType entryType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unsettled_quantity", nullable = false)
    private int unsettledQuantity;

    @Column(length = 500)
    private String note;

    /** Optional: who the stock was given to (given-out entries only). */
    @Column(name = "recipient_name", length = 200)
    private String recipientName;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    /** Which batches (and costs) a given-out entry took its units from, in the order they were taken. */
    @OneToMany(mappedBy = "entry", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<WarehouseIssueAllocation> allocations = new ArrayList<>();

    public void addAllocation(WarehouseIssueAllocation allocation) {
        allocation.setEntry(this);
        allocations.add(allocation);
    }
}
