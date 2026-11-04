-- Database roles for production (docs/PRODUCTION_READINESS.md §5.5), for the Supabase SQL editor.
-- Replace the three passwords with long random values; put them in .env.production
-- (FLYWAY_USER/FLYWAY_PASSWORD, DB_USERNAME/DB_PASSWORD) and in the backup job — never commit them.
--
-- Every table of schema "game" has row-level security with no policies (V23): roles that do not own
-- the tables and have no BYPASSRLS see nothing. So: the migration role owns everything, the
-- application and backup roles bypass row-level security but only get the privileges they need, and
-- Supabase's public API roles (anon, authenticated) get nothing.

-- ===== STEP 1 — before the backend starts for the first time ==================================

-- Runs Flyway and owns the schema and its tables (FLYWAY_USER / FLYWAY_PASSWORD).
CREATE ROLE strategy_migrator LOGIN PASSWORD 'replace-with-a-long-random-password';
GRANT CREATE ON DATABASE postgres TO strategy_migrator;

-- The backend at runtime: reads and writes game data only (DB_USERNAME / DB_PASSWORD).
CREATE ROLE strategy_app LOGIN BYPASSRLS PASSWORD 'replace-with-another-long-random-password';

-- deploy/backup/backup.sh: read only (pg_dump must see every row, hence BYPASSRLS).
CREATE ROLE strategy_backup LOGIN BYPASSRLS PASSWORD 'replace-with-a-third-long-random-password';

-- Everything the migration role creates from now on is granted automatically.
ALTER DEFAULT PRIVILEGES FOR ROLE strategy_migrator GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO strategy_app;
ALTER DEFAULT PRIVILEGES FOR ROLE strategy_migrator GRANT USAGE, SELECT ON SEQUENCES TO strategy_app;
ALTER DEFAULT PRIVILEGES FOR ROLE strategy_migrator GRANT SELECT ON TABLES TO strategy_backup;
ALTER DEFAULT PRIVILEGES FOR ROLE strategy_migrator GRANT USAGE, SELECT ON SEQUENCES TO strategy_backup;

-- Now start the backend once (FLYWAY_USER=strategy_migrator): Flyway creates schema "game" and
-- all tables, owned by strategy_migrator.

-- ===== STEP 2 — after the first start =========================================================

GRANT USAGE ON SCHEMA game TO strategy_app, strategy_backup;
REVOKE ALL ON SCHEMA game FROM anon, authenticated;
REVOKE ALL ON ALL TABLES IN SCHEMA game FROM anon, authenticated;

-- Check (should list every game table with rowsecurity = true and owner strategy_migrator):
-- SELECT tablename, tableowner, rowsecurity FROM pg_tables WHERE schemaname = 'game' ORDER BY 1;
