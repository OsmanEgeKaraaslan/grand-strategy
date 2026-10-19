-- Phase 5: battalions, organisational armies and battle reports.
-- Location and movement eligibility belong to each battalion (Q-M1); an army is only a label
-- for battalions of one nation that stand together. Running games are not changed: they have
-- no battalions until their players recruit some.

ALTER TABLE game_command DROP CONSTRAINT game_command_type_ck;
ALTER TABLE game_command ADD CONSTRAINT game_command_type_ck
    CHECK (command_type IN ('END_TURN', 'BUILD_WORKSHOP', 'RECRUIT', 'MOVE', 'REGROUP'));

CREATE TABLE army (
    id         uuid        PRIMARY KEY,
    game_id    uuid        NOT NULL REFERENCES game (id),
    nation_id  uuid        NOT NULL,
    name       varchar(40) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT army_game_id_uq UNIQUE (game_id, id),
    -- Target of the battalion FK below: a battalion can only join an army of its own nation.
    CONSTRAINT army_game_id_nation_uq UNIQUE (game_id, id, nation_id),
    CONSTRAINT army_name_ck CHECK (char_length(name) BETWEEN 1 AND 40),
    FOREIGN KEY (game_id, nation_id) REFERENCES nation (game_id, id)
);

CREATE TABLE battalion (
    id                     uuid        PRIMARY KEY,
    game_id                uuid        NOT NULL REFERENCES game (id),
    nation_id              uuid        NOT NULL,
    unit_type              varchar(16) NOT NULL,
    manpower               integer     NOT NULL,
    max_manpower           integer     NOT NULL,
    origin_city_id         uuid        NOT NULL,
    province_state_id      uuid        NOT NULL,
    army_id                uuid,
    equipment_level        integer     NOT NULL DEFAULT 0,
    recruitment_cost_mg    bigint      NOT NULL,
    recruited_round        integer     NOT NULL,
    -- May not initiate a move or attack while game.turn_number equals this (Q-M3); always defends.
    recruited_turn_number  integer     NOT NULL,
    -- Set when the battalion moves; a second move in the same player turn is rejected (Q-M1).
    last_moved_turn_number integer,
    created_at             timestamptz NOT NULL,
    CONSTRAINT battalion_game_id_uq UNIQUE (game_id, id),
    CONSTRAINT battalion_unit_type_ck CHECK (unit_type IN ('INFANTRY', 'CAVALRY', 'ARTILLERY')),
    -- Zero manpower means destroyed: the row is deleted, never kept at 0.
    CONSTRAINT battalion_manpower_ck CHECK (manpower > 0 AND manpower <= max_manpower),
    CONSTRAINT battalion_max_manpower_ck CHECK (max_manpower > 0),
    CONSTRAINT battalion_cost_ck CHECK (recruitment_cost_mg >= 0),
    CONSTRAINT battalion_rounds_ck CHECK (recruited_round >= 0 AND recruited_turn_number >= 0),
    FOREIGN KEY (game_id, nation_id) REFERENCES nation (game_id, id),
    FOREIGN KEY (game_id, origin_city_id) REFERENCES city (game_id, id),
    FOREIGN KEY (game_id, province_state_id) REFERENCES province_state (game_id, id),
    FOREIGN KEY (game_id, army_id, nation_id) REFERENCES army (game_id, id, nation_id)
);

CREATE INDEX battalion_location_idx ON battalion (game_id, province_state_id);
CREATE INDEX battalion_nation_idx ON battalion (game_id, nation_id);
CREATE INDEX battalion_army_idx ON battalion (game_id, army_id) WHERE army_id IS NOT NULL;

CREATE TABLE battle_report (
    id                 uuid        PRIMARY KEY,
    game_id            uuid        NOT NULL REFERENCES game (id),
    round_number       integer     NOT NULL,
    turn_number        integer     NOT NULL,
    province_state_id  uuid        NOT NULL,
    attacker_nation_id uuid        NOT NULL,
    defender_nation_id uuid        NOT NULL,
    winner             varchar(16) NOT NULL,
    ruleset_version    varchar(16) NOT NULL,
    -- Inputs, per-phase numbers and outcomes: enough to re-run and explain the battle.
    report             jsonb       NOT NULL,
    created_at         timestamptz NOT NULL,
    CONSTRAINT battle_report_winner_ck CHECK (winner IN ('ATTACKER', 'DEFENDER')),
    FOREIGN KEY (game_id, province_state_id) REFERENCES province_state (game_id, id),
    FOREIGN KEY (game_id, attacker_nation_id) REFERENCES nation (game_id, id),
    FOREIGN KEY (game_id, defender_nation_id) REFERENCES nation (game_id, id)
);

CREATE INDEX battle_report_game_idx ON battle_report (game_id, created_at DESC);

ALTER TABLE army ENABLE ROW LEVEL SECURITY;
ALTER TABLE battalion ENABLE ROW LEVEL SECURITY;
ALTER TABLE battle_report ENABLE ROW LEVEL SECURITY;
