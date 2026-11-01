-- Dev/demo data only, enabled by iam.demo-tenants=true (the backend's dev profile). Never in production:
-- these ids are public. They match the organizations in infra/keycloak/zk-spring-realm.json.
INSERT INTO iam.tenant (id, alias, name, status) VALUES
    ('7f3a0c4e-1b2d-4e5f-8a9b-0c1d2e3f4a5b', 'demo', 'Demo Company',      'ACTIVE'),
    ('b2c4e6a8-0d1f-4a3b-9c5d-7e8f9a0b1c2d', 'acme', 'PT Acme Indonesia', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;
