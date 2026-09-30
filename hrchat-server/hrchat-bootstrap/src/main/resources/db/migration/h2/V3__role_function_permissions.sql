-- 角色功能授权：迁移既有默认角色权限，运行时仅从此表读取。
CREATE TABLE sec_role_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_code VARCHAR(64) NOT NULL,
    permission_code VARCHAR(128) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64),
    CONSTRAINT uk_role_permission UNIQUE (role_code, permission_code)
);
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('HRBP', 'chat:ask', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('HRBP', 'chat:view_sql', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('HRBP', 'report:view', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('HRBP', 'report:create', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('HRBP', 'report:manage', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('HRBP', 'report:subscribe', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('HRBP', 'export:apply', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('HR_SPECIALIST', 'chat:ask', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('HR_SPECIALIST', 'report:view', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('HR_SPECIALIST', 'report:create', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('HR_SPECIALIST', 'report:manage', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('HR_SPECIALIST', 'report:subscribe', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('HR_SPECIALIST', 'export:apply', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('HRD', 'chat:ask', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('HRD', 'chat:view_sql', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('HRD', 'chat:attribution', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('HRD', 'report:view', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('HRD', 'report:create', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('HRD', 'report:manage', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('HRD', 'report:subscribe', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('HRD', 'export:apply', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('PAYROLL', 'chat:ask', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('PAYROLL', 'payroll:plain_view', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('PAYROLL', 'report:view', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('PAYROLL', 'report:subscribe', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('CHO', 'chat:ask', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('CHO', 'chat:view_sql', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('CHO', 'chat:attribution', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('CHO', 'report:view', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('CHO', 'report:create', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('CHO', 'report:manage', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('CHO', 'report:subscribe', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('ADMIN', 'chat:ask', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('ADMIN', 'chat:view_sql', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('ADMIN', 'report:view', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('ADMIN', 'report:create', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('ADMIN', 'report:manage', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('ADMIN', 'report:subscribe', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('ADMIN', 'admin:*', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('ADMIN', 'export:apply', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('DATA_ADMIN', 'chat:ask', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('DATA_ADMIN', 'chat:view_sql', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('DATA_ADMIN', 'semantic:manage', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('DATA_ADMIN', 'admin:semantic', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('DATA_ADMIN', 'admin:audit:read', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('DATA_ADMIN', 'admin:data:read', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('DATA_ADMIN', 'report:view', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('DATA_ADMIN', 'report:subscribe', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('DATA_ADMIN', 'export:apply', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('TENANT_ADMIN', 'chat:ask', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('TENANT_ADMIN', 'report:view', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('TENANT_ADMIN', 'admin:view', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('TENANT_ADMIN', 'admin:user:manage', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('TENANT_ADMIN', 'admin:llm:view', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('TENANT_ADMIN', 'admin:llm:manage', 'migration');
INSERT INTO sec_role_permission (role_code, permission_code, created_by) VALUES ('TENANT_ADMIN', 'admin:audit:read', 'migration');
