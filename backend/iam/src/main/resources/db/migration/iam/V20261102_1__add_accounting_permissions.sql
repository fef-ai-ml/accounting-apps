INSERT INTO iam.permission (code, description) VALUES
    ('accounting:read',           'View accounts, periods, journal entries and reports'),
    ('accounting:write',          'Create, edit and delete draft journal entries'),
    ('accounting:post',           'Post and reverse journal entries'),
    ('accounting:period-manage',  'Open, close and reopen fiscal periods'),
    ('accounting:account-manage', 'Set up accounting, manage accounts and accounting settings');

INSERT INTO iam.role_permission (role, permission_code) VALUES
    ('VIEWER',     'accounting:read'),
    ('BOOKKEEPER', 'accounting:read'),
    ('BOOKKEEPER', 'accounting:write'),
    ('ACCOUNTANT', 'accounting:read'),
    ('ACCOUNTANT', 'accounting:write'),
    ('ACCOUNTANT', 'accounting:post'),
    ('ACCOUNTANT', 'accounting:period-manage'),
    ('ADMIN',      'accounting:read'),
    ('ADMIN',      'accounting:write'),
    ('ADMIN',      'accounting:post'),
    ('ADMIN',      'accounting:period-manage'),
    ('ADMIN',      'accounting:account-manage');
