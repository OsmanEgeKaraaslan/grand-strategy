-- Phase 12A: public player profiles (docs/DATA_MODEL.md §3.2). The player row stays the one
-- account record linked to Supabase Auth; no credentials are added.
--
-- username: the unique public handle (lowercase a–z, digits, '_', 3–20, starting with a letter).
--   Human accounts always have one; computer accounts never do.
-- username_chosen: false until the player picks a handle themselves (the dashboard then suggests it).
-- emblem: one of the predefined profile emblems (no uploaded images).
-- match_history_public: whether other players see the recent completed matches on the profile.

ALTER TABLE player
    ADD COLUMN username             varchar(20),
    ADD COLUMN username_chosen      boolean     NOT NULL DEFAULT false,
    ADD COLUMN emblem               varchar(16) NOT NULL DEFAULT 'crown',
    ADD COLUMN match_history_public boolean     NOT NULL DEFAULT true;

-- Existing human accounts get a handle derived from their display name (lowercased, other characters
-- turned into '_'). The oldest account keeps a contested or reserved-free candidate; every other
-- account — and every name that cannot become a valid handle or is reserved — gets 'player_' plus
-- 12 hex digits of its id, which is unique by construction. Players can change it afterwards.
WITH candidate AS (
    SELECT id, created_at,
           left(regexp_replace(regexp_replace(lower(display_name), '[^a-z0-9_]+', '_', 'g'), '^_+', ''), 20) AS name
    FROM player
    WHERE kind = 'HUMAN'
), ranked AS (
    SELECT id, name, row_number() OVER (PARTITION BY name ORDER BY created_at, id) AS rn
    FROM candidate
)
UPDATE player p
SET username = CASE
        WHEN r.rn = 1
             AND r.name ~ '^[a-z][a-z0-9_]{2,19}$'
             AND r.name NOT LIKE 'player\_%'
             AND r.name NOT IN ('admin', 'administrator', 'system', 'root', 'support', 'moderator', 'mod', 'staff',
                                'official', 'ai', 'computer', 'npc', 'null', 'undefined', 'anonymous', 'me', 'you',
                                'everyone', 'api', 'help', 'guest', 'server', 'game', 'sundered_reach')
            THEN r.name
        ELSE 'player_' || substr(replace(p.id::text, '-', ''), 1, 12)
    END
FROM ranked r
WHERE p.id = r.id;

ALTER TABLE player
    ADD CONSTRAINT player_username_ck CHECK (username ~ '^[a-z][a-z0-9_]{2,19}$'),
    ADD CONSTRAINT player_username_human_ck CHECK ((kind = 'HUMAN') = (username IS NOT NULL)),
    ADD CONSTRAINT player_emblem_ck CHECK (emblem IN ('crown', 'shield', 'swords', 'tower', 'flag', 'scholar',
                                                      'mountain', 'wheat'));

-- Handles are stored lowercase, so this index enforces case-insensitive uniqueness; text_pattern_ops
-- also serves the bounded prefix search (username LIKE 'abc%').
CREATE UNIQUE INDEX player_username_uq ON player (username text_pattern_ops);
