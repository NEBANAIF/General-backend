-- New switch "View products" (PRODUCT_VIEW): see/search the product list and pick products in forms, without needing
-- the Inventory, Batches or Stock History pages. Roles are stored in the database, so give it to every role that could
-- already see or pick products - after this migration nothing changes for them, and new roles can be given it on its own.
INSERT INTO access_role_permissions (access_role_id, permission)
SELECT DISTINCT access_role_id, 'PRODUCT_VIEW'
FROM access_role_permissions
WHERE permission IN ('INVENTORY_VIEW', 'BATCH_VIEW', 'STOCK_HISTORY_VIEW', 'POS_ACCESS', 'PURCHASE_CREATE',
                     'TRANSFER_CREATE', 'BATCH_CREATE', 'STOCK_ADJUST')
ON CONFLICT DO NOTHING;
