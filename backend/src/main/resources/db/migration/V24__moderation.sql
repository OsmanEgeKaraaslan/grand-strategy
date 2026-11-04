-- Moderation (2026-11-03, OPEN_QUESTIONS Q-MOD1): administrators can suspend (ban) accounts, reset
-- offensive names and abandon stuck games. A suspended account is refused by the API; its seats in
-- running games are handed to computer accounts, its lobbies are abandoned.

ALTER TABLE player
    ADD COLUMN banned_at  timestamptz,
    ADD COLUMN ban_reason varchar(200),
    ADD CONSTRAINT player_ban_ck CHECK ((banned_at IS NULL) = (ban_reason IS NULL)),
    ADD CONSTRAINT player_ban_human_ck CHECK (banned_at IS NULL OR kind = 'HUMAN');

-- ABANDONED: closed by an administrator (TURN_SYSTEM §3.1, Q-T3); final like FINISHED, but without a result.
ALTER TABLE game DROP CONSTRAINT game_status_ck;
-- An abandoned game keeps the turn fields and player count it had: a lobby may have free slots and no
-- turn state, a started game keeps both. The other statuses are constrained as before (V5, V11).
ALTER TABLE game
    DROP CONSTRAINT game_turn_state_ck,
    DROP CONSTRAINT game_status_matches_count_ck;
ALTER TABLE game
    ADD CONSTRAINT game_status_ck CHECK (status IN ('WAITING_FOR_PLAYERS', 'READY', 'IN_PROGRESS', 'FINISHED', 'ABANDONED')),
    ADD CONSTRAINT game_turn_state_ck CHECK (status = 'ABANDONED'
        OR (status IN ('IN_PROGRESS', 'FINISHED')) = (current_round IS NOT NULL AND active_seat IS NOT NULL AND started_at IS NOT NULL)),
    ADD CONSTRAINT game_status_matches_count_ck CHECK (status = 'ABANDONED'
        OR (status = 'WAITING_FOR_PLAYERS') = (player_count < max_players));

-- Who did what, for accountability. Kept while the game is run (Privacy Policy §5).
CREATE TABLE moderation_action (
    id               uuid         PRIMARY KEY,
    admin_player_id  uuid         NOT NULL REFERENCES player (id),
    action           varchar(16)  NOT NULL,
    target_player_id uuid         REFERENCES player (id),
    target_game_id   uuid         REFERENCES game (id),
    reason           varchar(200),
    created_at       timestamptz  NOT NULL,
    CONSTRAINT moderation_action_kind_ck CHECK (action IN ('BAN', 'UNBAN', 'RESET_NAME', 'ABANDON_GAME')),
    CONSTRAINT moderation_action_target_ck CHECK ((action = 'ABANDON_GAME') = (target_game_id IS NOT NULL)
        AND (action = 'ABANDON_GAME') = (target_player_id IS NULL))
);

CREATE INDEX moderation_action_created_idx ON moderation_action (created_at DESC);
CREATE INDEX moderation_action_target_player_idx ON moderation_action (target_player_id);

-- Every table of the schema has row-level security (V23).
ALTER TABLE moderation_action ENABLE ROW LEVEL SECURITY;
