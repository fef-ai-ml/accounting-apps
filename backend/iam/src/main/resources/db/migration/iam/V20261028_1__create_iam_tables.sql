CREATE SCHEMA IF NOT EXISTS iam;

-- Permission codes are '<module>:<action>', e.g. 'task:read'. Modules add theirs with a migration in this folder.
CREATE TABLE iam.permission (
    code        VARCHAR(100) PRIMARY KEY,
    description VARCHAR(500) NOT NULL
);

-- Roles are Keycloak realm role names; there is no role table.
CREATE TABLE iam.role_permission (
    role            VARCHAR(100) NOT NULL,
    permission_code VARCHAR(100) NOT NULL REFERENCES iam.permission (code),
    PRIMARY KEY (role, permission_code)
);
