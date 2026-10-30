-- Runs only when the Postgres volume is empty. Existing installs run this statement once by hand.
-- The API's login: owns nothing and cannot bypass row-level security. Change the password outside development.
CREATE ROLE app_runtime LOGIN PASSWORD 'app_runtime' NOSUPERUSER NOBYPASSRLS;
