-- Ruleset v8 (playtest feedback, 2026-10-29): a nation declaring war may invite its normal (full)
-- allies to join the attack (docs/DIPLOMACY.md §12a). A call to arms now records the side the called
-- ally would join: DEFENDER for the existing defensive calls (every row before this migration) and
-- ATTACKER for offensive invitations.

ALTER TABLE call_to_arms
    ADD COLUMN side varchar(8) NOT NULL DEFAULT 'DEFENDER',
    ADD CONSTRAINT call_to_arms_side_ck CHECK (side IN ('ATTACKER', 'DEFENDER'));
