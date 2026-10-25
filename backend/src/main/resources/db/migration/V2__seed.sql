-- Справочники и базовые учётные записи.

INSERT INTO organizations (organization_id, organization_name) VALUES (1, 'Демо-организация');
SELECT setval('organizations_organization_id_seq', 1);

INSERT INTO roles (role_id, role_name) VALUES
    (1, 'маркетолог'),
    (2, 'администратор');
SELECT setval('roles_role_id_seq', 2);

INSERT INTO permissions (permission_id, role_id, permission_code) VALUES
    (1, 1, 'dashboard.view'),
    (2, 1, 'campaign.manage'),
    (3, 1, 'upload.manage'),
    (4, 1, 'forecast.run'),
    (5, 1, 'export.run'),
    (6, 2, 'user.manage'),
    (7, 2, 'admin.view'),
    (8, 2, 'audit.view'),
    (9, 2, 'models.manage');
SELECT setval('permissions_permission_id_seq', 9);

INSERT INTO users (user_id, role_id, login, email, full_name, password_hash, organization_id) VALUES
    (1, 2, 'admin', 'admin@adforecast.by', 'admin', '$2a$06$F6HAzhjhA7nHnpWXIcQQNe4WTsLg0f5yAg1gj/Uik/avMPiIiuFm.', 1),
    (2, 1, 'angejlov', 'angejlov@adforecast.by', 'Станислав Ангелов', '$2a$06$MlVSPvtno61e7qNdSVpOkeDAoCXrr8sfKElJsa.R0uFkkEWuCZ1f2', 1);
SELECT setval('users_user_id_seq', 2);
