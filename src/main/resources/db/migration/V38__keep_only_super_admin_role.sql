-- Start with ONE role: the Super Admin. The four other built-in roles (Store Manager, Store Staff, Warehouse Manager,
-- Warehouse Staff) are removed so that every other role is created on purpose, on the Roles & Permissions page.
-- A built-in role that still has users is left alone (it cannot be removed while people hold it); move those users to
-- a role of your own and delete it on the Roles page.
DELETE FROM access_role_permissions
WHERE access_role_id IN (
    SELECT r.id FROM access_roles r
    WHERE r.system_role AND r.base_role <> 'SUPER_ADMIN'
      AND NOT EXISTS (SELECT 1 FROM app_users u WHERE u.access_role_id = r.id));

DELETE FROM access_roles r
WHERE r.system_role AND r.base_role <> 'SUPER_ADMIN'
  AND NOT EXISTS (SELECT 1 FROM app_users u WHERE u.access_role_id = r.id);
