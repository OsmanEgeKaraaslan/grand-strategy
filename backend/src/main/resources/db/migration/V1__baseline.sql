-- Phase 1 baseline. Flyway creates the `game` schema itself (spring.flyway.create-schemas).
-- Gameplay tables arrive with the phases that need them (docs/DATA_MODEL.md).

-- Defence in depth for Supabase: no implicit privileges on the game schema for other roles.
REVOKE ALL ON SCHEMA game FROM PUBLIC;

COMMENT ON SCHEMA game IS 'Authoritative game state. Accessed only by the Spring backend.';
