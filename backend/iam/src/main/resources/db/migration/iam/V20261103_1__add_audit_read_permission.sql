INSERT INTO iam.permission (code, description) VALUES
    ('accounting:audit-read', 'Read the accounting audit log');

INSERT INTO iam.role_permission (role, permission_code) VALUES
    ('ACCOUNTANT', 'accounting:audit-read'),
    ('ADMIN',      'accounting:audit-read');
