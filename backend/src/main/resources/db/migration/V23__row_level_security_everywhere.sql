-- Security review 2026-11-03 (docs/SECURITY_AUDIT.md §7, P3): row-level security on EVERY table of
-- the game schema, with no policies — deny-all for any role that is not the tables' owner and has no
-- BYPASSRLS. Flyway's own history table is left alone: Flyway holds a lock on it while this script
-- runs, so altering it would wait forever, and it holds no game data. Tables of V11/V14 (wars, alliances, treaties, conferences, calls to arms, results, ...)
-- had been created without it. The schema is not exposed through Supabase's Data API and V1 revoked
-- its privileges; this is the second line of defence. Future migrations must enable it on new tables
-- (ApplicationIT checks that none is missing).
--
-- The backend is unaffected when it connects as the owner (local and test databases) or with a role
-- that has BYPASSRLS (production: deploy/supabase/app-role.sql).

DO $$
DECLARE
    t record;
BEGIN
    FOR t IN SELECT tablename FROM pg_tables WHERE schemaname = 'game' AND tablename <> 'flyway_schema_history' LOOP
        EXECUTE format('ALTER TABLE game.%I ENABLE ROW LEVEL SECURITY', t.tablename);
    END LOOP;
END
$$;
