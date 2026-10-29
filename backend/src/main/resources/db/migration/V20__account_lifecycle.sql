-- Phase 15A: terms acceptance and account deletion (docs/DATA_MODEL.md §3.2, docs/DATA_PRIVACY_INVENTORY.md).
-- Credentials stay with Supabase Auth; nothing here stores an email address or password.
--
-- terms_version / privacy_version / terms_accepted_at: the latest legal document versions the player
--   accepted. NULL for every account created before this migration (and for computer accounts):
--   existing players are asked to accept on their next visit instead of being treated as having agreed.
-- deleted_at: set when the player deleted their account. The row is kept, anonymized, so finished
--   matches, results and statistics of other players stay intact (foreign keys keep pointing at it).

ALTER TABLE player
    ADD COLUMN terms_version     varchar(32),
    ADD COLUMN privacy_version   varchar(32),
    ADD COLUMN terms_accepted_at timestamptz,
    ADD COLUMN deleted_at        timestamptz,
    ADD CONSTRAINT player_terms_accepted_ck CHECK ((terms_version IS NULL) = (terms_accepted_at IS NULL)),
    ADD CONSTRAINT player_terms_human_ck CHECK (kind = 'HUMAN' OR terms_version IS NULL);

-- Every acceptance, so the operator can show which version a player agreed to and when.
CREATE TABLE terms_acceptance (
    player_id       uuid        NOT NULL REFERENCES player (id),
    terms_version   varchar(32) NOT NULL,
    privacy_version varchar(32) NOT NULL,
    accepted_at     timestamptz NOT NULL,
    PRIMARY KEY (player_id, terms_version, privacy_version),
    CONSTRAINT terms_acceptance_version_ck CHECK (terms_version ~ '^[A-Za-z0-9._-]{1,32}$'
        AND privacy_version ~ '^[A-Za-z0-9._-]{1,32}$')
);

-- Operators list deleted accounts whose Supabase Auth user still has to be removed.
CREATE INDEX player_deleted_idx ON player (deleted_at) WHERE deleted_at IS NOT NULL;

ALTER TABLE terms_acceptance ENABLE ROW LEVEL SECURITY;
