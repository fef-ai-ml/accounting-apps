INSERT INTO iam.permission (code, description) VALUES
    ('task:read',   'List and view tasks'),
    ('task:write',  'Create and edit tasks'),
    ('task:delete', 'Delete tasks'),
    ('iam:read',    'View roles and their permissions');

INSERT INTO iam.role_permission (role, permission_code) VALUES
    ('VIEWER', 'task:read'),
    ('EDITOR', 'task:read'),
    ('EDITOR', 'task:write'),
    ('ADMIN',  'task:read'),
    ('ADMIN',  'task:write'),
    ('ADMIN',  'task:delete'),
    ('ADMIN',  'iam:read');
