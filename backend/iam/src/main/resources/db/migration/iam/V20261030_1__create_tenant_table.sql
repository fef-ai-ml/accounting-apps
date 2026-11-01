CREATE TABLE iam.tenant (
    id         UUID PRIMARY KEY,               -- the Keycloak organization id
    alias      VARCHAR(100) NOT NULL UNIQUE,   -- the Keycloak organization alias (cannot change in Keycloak)
    name       VARCHAR(200) NOT NULL,
    status     VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The API connects as app_runtime (created outside Flyway) and only reads iam.
GRANT USAGE ON SCHEMA iam TO app_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA iam TO app_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA iam GRANT SELECT ON TABLES TO app_runtime;
