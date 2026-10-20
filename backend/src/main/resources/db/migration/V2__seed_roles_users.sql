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
    (6, 2, 'dashboard.view'),
    (7, 2, 'user.manage');
SELECT setval('permissions_permission_id_seq', 7);

INSERT INTO users (user_id, role_id, login, email, password_hash) VALUES
    (1, 2, 'admin', 'admin@example.com', '$2a$10$UCxchdZdMgJjDHnjlKNot.Ra9LuyUc5KV6BVQp72KSAHWC2NmtP7K'),
    (2, 1, 'marketer', 'marketer@example.com', '$2a$10$w4Vh4G9UKg9VFb63r1Nk5O.jK6pyNl2q5dolJ8ZyeKxSWuKcCFyC.');
SELECT setval('users_user_id_seq', 2);
