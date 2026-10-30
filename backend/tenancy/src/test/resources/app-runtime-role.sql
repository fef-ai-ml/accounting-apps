-- Same role as infra/postgres/init/02-app-runtime.sql: the API's login, subject to row-level security.
CREATE ROLE app_runtime LOGIN PASSWORD 'app_runtime' NOSUPERUSER NOBYPASSRLS;
