-- 只可在已核对名称的 IAM local/test Schema 执行；仅清理固定 seed 身份，不得用于生产数据。
DELETE FROM login_audit WHERE account IN ('student_test_01', 'teacher_test_01', 'admin_test_01');
DELETE FROM refresh_session WHERE user_id IN ('00000000-0000-4000-8000-000000000101', '00000000-0000-4000-8000-000000000102', '00000000-0000-4000-8000-000000000103');
DELETE FROM user_role WHERE user_id IN ('00000000-0000-4000-8000-000000000101', '00000000-0000-4000-8000-000000000102', '00000000-0000-4000-8000-000000000103');
DELETE FROM `user` WHERE id IN ('00000000-0000-4000-8000-000000000101', '00000000-0000-4000-8000-000000000102', '00000000-0000-4000-8000-000000000103');
DELETE FROM role_permission
WHERE role_id IN (
    SELECT role_id FROM (
        SELECT r.id AS role_id
        FROM `role` r
        LEFT JOIN user_role ur ON ur.role_id = r.id
        WHERE r.code IN ('STUDENT', 'TEACHER', 'ADMIN') AND ur.user_id IS NULL
    ) removable_roles
);
DELETE FROM permission
WHERE code IN ('learning:access', 'teaching:access', 'administration:access')
  AND NOT EXISTS (SELECT 1 FROM role_permission rp WHERE rp.permission_id = permission.id);
DELETE FROM `role`
WHERE code IN ('STUDENT', 'TEACHER', 'ADMIN')
  AND NOT EXISTS (SELECT 1 FROM user_role ur WHERE ur.role_id = `role`.id);
