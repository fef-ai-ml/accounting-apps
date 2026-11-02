CREATE SCHEMA tenancy_test;

CREATE TABLE tenancy_test.note (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id UUID         NOT NULL,
    text      VARCHAR(100) NOT NULL
);

ALTER TABLE tenancy_test.note ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy_test.note
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT USAGE ON SCHEMA tenancy_test TO app_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenancy_test.note TO app_runtime;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA tenancy_test TO app_runtime;
