-- WAREHOUSE LOG: the warehouse operator records only QUANTITIES - stock received and stock given out.
-- He sees no prices and no customers. The admin later "settles" each given-out entry into real sales
-- (customer, price, payment, loan), so one handover of 15 cartons can become 10 cartons paid for one customer and
-- 5 cartons on loan for another.

-- Two new kinds of stock movement and a new kind of reference for the stock ledger.
ALTER TABLE stock_history DROP CONSTRAINT stock_history_movement_type_check;
ALTER TABLE stock_history ADD CONSTRAINT stock_history_movement_type_check CHECK (movement_type IN
    ('PURCHASE', 'SALE', 'TRANSFER_OUT', 'TRANSFER_IN',
     'CUSTOMER_RETURN', 'SUPPLIER_RETURN', 'ADJUSTMENT', 'DAMAGED', 'LOST', 'SALE_VOID',
     'BATCH_ADDED', 'WAREHOUSE_RECEIPT', 'WAREHOUSE_ISSUE'));
ALTER TABLE stock_history DROP CONSTRAINT stock_history_reference_type_check;
ALTER TABLE stock_history ADD CONSTRAINT stock_history_reference_type_check CHECK (reference_type IN
    ('PURCHASE_ORDER', 'SALE', 'TRANSFER', 'CUSTOMER_RETURN',
     'SUPPLIER_RETURN', 'MANUAL_ADJUSTMENT', 'SALE_VOID', 'WAREHOUSE_ENTRY'));

CREATE TABLE warehouse_entries (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_type         VARCHAR(10)  NOT NULL CHECK (entry_type IN ('RECEIVE', 'ISSUE')),
    branch_id          UUID         NOT NULL REFERENCES branches (id),
    product_id         UUID         NOT NULL REFERENCES products (id),
    quantity           INTEGER      NOT NULL CHECK (quantity > 0),
    -- ISSUE only: how many of the given-out units have not yet been turned into sales by the admin.
    unsettled_quantity INTEGER      NOT NULL DEFAULT 0 CHECK (unsettled_quantity >= 0 AND unsettled_quantity <= quantity),
    note               VARCHAR(500),
    created_by         UUID         NOT NULL REFERENCES app_users (id),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version            BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_warehouse_entries_branch_created ON warehouse_entries (branch_id, created_at DESC);
CREATE INDEX idx_warehouse_entries_open ON warehouse_entries (branch_id)
    WHERE entry_type = 'ISSUE' AND unsettled_quantity > 0;

-- Which batches (and at what cost) a given-out entry took its units from, remembered so that the sales made
-- later from this entry carry the REAL cost of goods without taking the stock out a second time.
CREATE TABLE warehouse_issue_allocations (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_id           UUID           NOT NULL REFERENCES warehouse_entries (id),
    batch_id           UUID           NOT NULL REFERENCES product_batches (id),
    position           INTEGER        NOT NULL,
    quantity           INTEGER        NOT NULL CHECK (quantity > 0),
    remaining_quantity INTEGER        NOT NULL CHECK (remaining_quantity >= 0),
    unit_cost          NUMERIC(14, 4) NOT NULL CHECK (unit_cost >= 0),
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version            BIGINT         NOT NULL DEFAULT 0
);
CREATE INDEX idx_warehouse_alloc_entry ON warehouse_issue_allocations (entry_id, position);

-- Which sales were made from which given-out entry.
CREATE TABLE warehouse_issue_settlements (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_id    UUID        NOT NULL REFERENCES warehouse_entries (id),
    sale_id     UUID        NOT NULL REFERENCES sales (id),
    quantity    INTEGER     NOT NULL CHECK (quantity > 0),
    created_by  UUID        NOT NULL REFERENCES app_users (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version     BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX idx_warehouse_settlements_entry ON warehouse_issue_settlements (entry_id);

-- The two new switches. Roles live in the database, so grant them here: warehouse staff may record, warehouse
-- managers may record and settle, the Super Admin has both. Everyone else gets them on the Roles & Permissions page.
INSERT INTO access_role_permissions (access_role_id, permission)
SELECT r.id, p.permission
FROM access_roles r
JOIN (VALUES
    ('SUPER_ADMIN', 'WAREHOUSE_LOG'),
    ('SUPER_ADMIN', 'WAREHOUSE_SETTLE'),
    ('WAREHOUSE_MANAGER', 'WAREHOUSE_LOG'),
    ('WAREHOUSE_MANAGER', 'WAREHOUSE_SETTLE'),
    ('WAREHOUSE_STAFF', 'WAREHOUSE_LOG')
) AS p (base_role, permission) ON p.base_role = r.base_role
WHERE r.system_role
ON CONFLICT DO NOTHING;
