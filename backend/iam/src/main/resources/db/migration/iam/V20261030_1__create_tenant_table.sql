CREATE TABLE iam.tenant (
    id         UUID PRIMARY KEY,               -- the Keycloak organization id
    alias      VARCHAR(100) NOT NULL UNIQUE,   -- the Keycloak organization alias (cannot change in Keycloak)
    name       VARCHAR(200) NOT NULL,
    status     VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Ids match the organizations in infra/keycloak/zk-spring-realm.json.
INSERT INTO iam.tenant (id, alias, name, status) VALUES
    ('7f3a0c4e-1b2d-4e5f-8a9b-0c1d2e3f4a5b', 'demo', 'Demo Company',      'ACTIVE'),
    ('b2c4e6a8-0d1f-4a3b-9c5d-7e8f9a0b1c2d', 'acme', 'PT Acme Indonesia', 'ACTIVE');

-- The API connects as app_runtime (created outside Flyway) and only reads iam.
GRANT USAGE ON SCHEMA iam TO app_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA iam TO app_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA iam GRANT SELECT ON TABLES TO app_runtime;
