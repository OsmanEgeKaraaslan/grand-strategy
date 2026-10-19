-- Phase 2: player profiles and two-player lobbies (docs/DATA_MODEL.md §3.2–3.3).
-- No gameplay tables yet. Identity comes from Supabase Auth; no passwords are stored here.

CREATE TABLE player (
    id               uuid         PRIMARY KEY,
    supabase_user_id uuid         NOT NULL,
    display_name     varchar(24)  NOT NULL,
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL,
    CONSTRAINT player_supabase_user_id_uq UNIQUE (supabase_user_id),
    CONSTRAINT player_display_name_length_ck CHECK (char_length(display_name) BETWEEN 3 AND 24)
);

-- Display names are unique regardless of case ("Aldric" and "aldric" are the same name).
CREATE UNIQUE INDEX player_display_name_lower_uq ON player (lower(display_name));

CREATE TABLE game (
    id             uuid         PRIMARY KEY,
    name           varchar(40)  NOT NULL,
    host_player_id uuid         NOT NULL REFERENCES player (id),
    status         varchar(32)  NOT NULL,
    max_players    integer      NOT NULL,
    player_count   integer      NOT NULL,
    invite_code    varchar(12)  NOT NULL,
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    CONSTRAINT game_invite_code_uq UNIQUE (invite_code),
    CONSTRAINT game_invite_code_format_ck CHECK (invite_code ~ '^[A-HJ-NP-Z2-9]{12}$'),
    CONSTRAINT game_name_length_ck CHECK (char_length(name) BETWEEN 3 AND 40),
    CONSTRAINT game_max_players_ck CHECK (max_players BETWEEN 2 AND 8),
    -- Capacity backstop: even a code path that skipped the row lock cannot overfill a lobby.
    CONSTRAINT game_player_count_ck CHECK (player_count BETWEEN 1 AND max_players),
    -- Lobby states only in Phase 2; Phase 3 extends this list (IN_PROGRESS, ...).
    CONSTRAINT game_status_ck CHECK (status IN ('WAITING_FOR_PLAYERS', 'READY')),
    -- A lobby is READY exactly when every slot is taken.
    CONSTRAINT game_lobby_status_matches_count_ck CHECK ((status = 'READY') = (player_count = max_players))
);

CREATE INDEX game_host_player_idx ON game (host_player_id);

CREATE TABLE game_player (
    id         uuid        PRIMARY KEY,
    game_id    uuid        NOT NULL REFERENCES game (id),
    player_id  uuid        NOT NULL REFERENCES player (id),
    seat_order integer     NOT NULL,
    joined_at  timestamptz NOT NULL,
    -- Target for future composite foreign keys (game_id, game_player_id) from per-game tables.
    CONSTRAINT game_player_game_id_id_uq UNIQUE (game_id, id),
    CONSTRAINT game_player_membership_uq UNIQUE (game_id, player_id),
    CONSTRAINT game_player_seat_uq UNIQUE (game_id, seat_order),
    CONSTRAINT game_player_seat_ck CHECK (seat_order >= 0)
);

CREATE INDEX game_player_player_idx ON game_player (player_id);

-- Supabase exposes the `public` schema through its Data API; these tables live in `game`,
-- which is not exposed. Row Level Security with no policies additionally denies the
-- anon/authenticated roles if the schema were ever exposed. The backend connects as the
-- table owner, which RLS does not restrict.
ALTER TABLE player ENABLE ROW LEVEL SECURITY;
ALTER TABLE game ENABLE ROW LEVEL SECURITY;
ALTER TABLE game_player ENABLE ROW LEVEL SECURITY;
