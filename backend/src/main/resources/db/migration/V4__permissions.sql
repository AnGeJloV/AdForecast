DELETE FROM permissions WHERE role_id = 2 AND permission_code = 'dashboard.view';

INSERT INTO permissions (role_id, permission_code) VALUES
    (2, 'admin.view'),
    (2, 'audit.view'),
    (2, 'models.manage'),
    (2, 'backup.manage');
