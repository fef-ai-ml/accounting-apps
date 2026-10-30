ALTER TABLE task.task ADD COLUMN tenant_id UUID;

-- Rows from before multi-tenancy belong to the demo tenant (organization "demo" in the dev realm).
UPDATE task.task SET tenant_id = '7f3a0c4e-1b2d-4e5f-8a9b-0c1d2e3f4a5b';

ALTER TABLE task.task ALTER COLUMN tenant_id SET NOT NULL;
CREATE INDEX task_tenant_idx ON task.task (tenant_id);

ALTER TABLE task.task ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON task.task
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- The API connects as app_runtime (created outside Flyway); RLS applies to it because it owns nothing.
GRANT USAGE ON SCHEMA task TO app_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA task TO app_runtime;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA task TO app_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA task GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA task GRANT USAGE ON SEQUENCES TO app_runtime;
