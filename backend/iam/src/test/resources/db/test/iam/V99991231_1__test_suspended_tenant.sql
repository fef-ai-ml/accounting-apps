-- Test-only: a suspended tenant next to the seeded active ones.
INSERT INTO iam.tenant (id, alias, name, status)
VALUES ('c0c0c0c0-0000-4000-8000-000000000003', 'closed', 'Closed Ltd', 'SUSPENDED');
