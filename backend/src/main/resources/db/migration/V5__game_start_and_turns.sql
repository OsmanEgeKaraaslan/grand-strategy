-- Phase 3: map choice, nation selection, game start, per-game world state and sequential turns.

-- ---------------------------------------------------------------------------------------------
-- Games play on a map template. Existing lobbies get the only published map.
ALTER TABLE game ADD COLUMN map_template_id uuid REFERENCES map_template (id);
UPDATE game SET map_template_id = (SELECT id FROM map_template WHERE key = 'sundered-reach' AND version = 1);
ALTER TABLE game ALTER COLUMN map_template_id SET NOT NULL;
CREATE INDEX game_map_template_idx ON game (map_template_id);

-- Turn state (docs/TURN_SYSTEM.md §2). Null until the game starts.
ALTER TABLE game
    ADD COLUMN current_round  integer,
    ADD COLUMN active_seat    integer,
    ADD COLUMN turn_number    integer NOT NULL DEFAULT 0,
    ADD COLUMN state_version  bigint  NOT NULL DEFAULT 0,
    ADD COLUMN started_at     timestamptz,
    ADD COLUMN last_action_at timestamptz;

-- Replace the Phase 2 lobby-only status rules with lifecycle rules that include IN_PROGRESS.
ALTER TABLE game DROP CONSTRAINT game_status_ck;
ALTER TABLE game DROP CONSTRAINT game_lobby_status_matches_count_ck;
ALTER TABLE game
    ADD CONSTRAINT game_status_ck CHECK (status IN ('WAITING_FOR_PLAYERS', 'READY', 'IN_PROGRESS')),
    -- Only a lobby with a free slot is waiting; READY and IN_PROGRESS games are full.
    ADD CONSTRAINT game_status_matches_count_ck
        CHECK ((status = 'WAITING_FOR_PLAYERS') = (player_count < max_players)),
    -- Turn fields exist exactly when the game is running.
    ADD CONSTRAINT game_turn_state_ck CHECK (
        (status = 'IN_PROGRESS') = (current_round IS NOT NULL AND active_seat IS NOT NULL AND started_at IS NOT NULL)),
    ADD CONSTRAINT game_round_ck CHECK (current_round IS NULL OR current_round >= 1),
    ADD CONSTRAINT game_active_seat_ck CHECK (active_seat IS NULL OR (active_seat >= 0 AND active_seat < max_players)),
    ADD CONSTRAINT game_turn_number_ck CHECK (turn_number >= 0),
    ADD CONSTRAINT game_state_version_ck CHECK (state_version >= 0);

-- ---------------------------------------------------------------------------------------------
-- Nation choice in the lobby: one nation per player, one player per nation (per game).
ALTER TABLE game_player ADD COLUMN nation_template_id uuid REFERENCES nation_template (id);
ALTER TABLE game_player ADD CONSTRAINT game_player_nation_uq UNIQUE (game_id, nation_template_id);

-- ---------------------------------------------------------------------------------------------
-- Per-game mutable world state, instantiated from the templates when the game starts.
-- Every row carries game_id; composite foreign keys (game_id, x_id) make it impossible to
-- reference another game's rows.

CREATE TABLE nation (
    id                 uuid        PRIMARY KEY,
    game_id            uuid        NOT NULL REFERENCES game (id),
    nation_template_id uuid        NOT NULL REFERENCES nation_template (id),
    game_player_id     uuid        NOT NULL,
    name               varchar(80) NOT NULL,
    color              varchar(7)  NOT NULL,
    CONSTRAINT nation_game_id_uq UNIQUE (game_id, id),
    CONSTRAINT nation_template_uq UNIQUE (game_id, nation_template_id),
    CONSTRAINT nation_player_uq UNIQUE (game_id, game_player_id),
    FOREIGN KEY (game_id, game_player_id) REFERENCES game_player (game_id, id)
);

-- Legal owner and military controller are separate (Q-W1). Occupation changes only the
-- controller; ownership changes only through peace treaties (Phase 9).
CREATE TABLE province_state (
    id                   uuid NOT NULL PRIMARY KEY,
    game_id              uuid NOT NULL REFERENCES game (id),
    province_template_id uuid NOT NULL REFERENCES province_template (id),
    owner_nation_id      uuid NOT NULL,
    controller_nation_id uuid NOT NULL,
    CONSTRAINT province_state_game_id_uq UNIQUE (game_id, id),
    CONSTRAINT province_state_template_uq UNIQUE (game_id, province_template_id),
    FOREIGN KEY (game_id, owner_nation_id) REFERENCES nation (game_id, id),
    FOREIGN KEY (game_id, controller_nation_id) REFERENCES nation (game_id, id)
);

CREATE INDEX province_state_owner_idx ON province_state (game_id, owner_nation_id);
CREATE INDEX province_state_controller_idx ON province_state (game_id, controller_nation_id);

-- One primary city per province in the MVP.
CREATE TABLE city (
    id                uuid        PRIMARY KEY,
    game_id           uuid        NOT NULL REFERENCES game (id),
    province_state_id uuid        NOT NULL,
    name              varchar(80) NOT NULL,
    population        integer     NOT NULL,
    CONSTRAINT city_game_id_uq UNIQUE (game_id, id),
    CONSTRAINT city_province_uq UNIQUE (province_state_id),
    CONSTRAINT city_population_ck CHECK (population >= 0),
    FOREIGN KEY (game_id, province_state_id) REFERENCES province_state (game_id, id)
);

-- ---------------------------------------------------------------------------------------------
-- Executed gameplay commands, for idempotent retries (docs/TURN_SYSTEM.md §4.2).
CREATE TABLE game_command (
    id               uuid        PRIMARY KEY,
    game_id          uuid        NOT NULL REFERENCES game (id),
    game_player_id   uuid        NOT NULL,
    idempotency_key  uuid        NOT NULL,
    command_type     varchar(32) NOT NULL,
    turn_number      integer     NOT NULL,
    created_at       timestamptz NOT NULL,
    CONSTRAINT game_command_idempotency_uq UNIQUE (game_id, game_player_id, idempotency_key),
    CONSTRAINT game_command_type_ck CHECK (command_type IN ('END_TURN')),
    FOREIGN KEY (game_id, game_player_id) REFERENCES game_player (game_id, id)
);

CREATE INDEX game_command_game_idx ON game_command (game_id, created_at);

-- One row per completed global round: the primary key makes processing a round twice impossible
-- (docs/TURN_SYSTEM.md §5.1).
CREATE TABLE round_resolution (
    game_id      uuid        NOT NULL REFERENCES game (id),
    round_number integer     NOT NULL,
    completed_at timestamptz NOT NULL,
    PRIMARY KEY (game_id, round_number),
    CONSTRAINT round_resolution_round_ck CHECK (round_number >= 1)
);

ALTER TABLE nation ENABLE ROW LEVEL SECURITY;
ALTER TABLE province_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE city ENABLE ROW LEVEL SECURITY;
ALTER TABLE game_command ENABLE ROW LEVEL SECURITY;
ALTER TABLE round_resolution ENABLE ROW LEVEL SECURITY;
