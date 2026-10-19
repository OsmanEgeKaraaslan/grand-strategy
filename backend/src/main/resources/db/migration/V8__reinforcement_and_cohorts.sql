-- Phase 6: reinforcement, disbanding and origin-aware manpower.
-- A battalion's soldiers are tracked as cohorts by origin city (no per-soldier rows). The cohort
-- sizes always add up to battalion.manpower; the application keeps that invariant inside every
-- command transaction and verifies it before commit.

ALTER TABLE game_command DROP CONSTRAINT game_command_type_ck;
ALTER TABLE game_command ADD CONSTRAINT game_command_type_ck
    CHECK (command_type IN ('END_TURN', 'BUILD_WORKSHOP', 'RECRUIT', 'MOVE', 'REGROUP', 'REINFORCE', 'DISBAND'));

-- Per-turn reinforcement allowance (500 men per battalion per player turn) and the provisional
-- rule that a reinforced battalion may not move or attack in that turn.
ALTER TABLE battalion ADD COLUMN last_reinforced_turn_number integer;
ALTER TABLE battalion ADD COLUMN reinforced_this_turn integer NOT NULL DEFAULT 0;
ALTER TABLE battalion ADD CONSTRAINT battalion_reinforced_ck
    CHECK (reinforced_this_turn >= 0 AND reinforced_this_turn <= max_manpower);

CREATE TABLE battalion_cohort (
    id             uuid    PRIMARY KEY,
    game_id        uuid    NOT NULL REFERENCES game (id),
    battalion_id   uuid    NOT NULL,
    origin_city_id uuid    NOT NULL,
    soldiers       integer NOT NULL,
    CONSTRAINT battalion_cohort_soldiers_ck CHECK (soldiers > 0),
    CONSTRAINT battalion_cohort_origin_uq UNIQUE (battalion_id, origin_city_id),
    -- Deleting a battalion (destroyed or disbanded) removes its cohorts in the same statement.
    FOREIGN KEY (game_id, battalion_id) REFERENCES battalion (game_id, id) ON DELETE CASCADE,
    FOREIGN KEY (game_id, origin_city_id) REFERENCES city (game_id, id)
);

CREATE INDEX battalion_cohort_battalion_idx ON battalion_cohort (game_id, battalion_id);

-- Existing battalions: every soldier came from the battalion's origin city.
INSERT INTO battalion_cohort (id, game_id, battalion_id, origin_city_id, soldiers)
SELECT gen_random_uuid(), b.game_id, b.id, b.origin_city_id, b.manpower
FROM battalion b;

-- Append-only record of civilians moving into or out of battalions by command (reinforcement,
-- disbanding). Battalion ids are kept without a foreign key: the history outlives the battalion.
-- It also answers retried commands and identifies an already disbanded battalion.
CREATE TABLE manpower_transfer (
    id            uuid        PRIMARY KEY,
    game_id       uuid        NOT NULL REFERENCES game (id),
    kind          varchar(16) NOT NULL,
    battalion_id  uuid        NOT NULL,
    nation_id     uuid        NOT NULL,
    round_number  integer     NOT NULL,
    turn_number   integer     NOT NULL,
    soldiers      integer     NOT NULL,
    cost_mg       bigint      NOT NULL,
    details       jsonb       NOT NULL,
    created_at    timestamptz NOT NULL,
    CONSTRAINT manpower_transfer_kind_ck CHECK (kind IN ('REINFORCE', 'DISBAND')),
    CONSTRAINT manpower_transfer_soldiers_ck CHECK (soldiers > 0),
    CONSTRAINT manpower_transfer_cost_ck CHECK (cost_mg >= 0),
    FOREIGN KEY (game_id, nation_id) REFERENCES nation (game_id, id)
);

CREATE INDEX manpower_transfer_battalion_idx ON manpower_transfer (game_id, battalion_id);
-- A battalion is disbanded at most once.
CREATE UNIQUE INDEX manpower_transfer_disband_uq ON manpower_transfer (game_id, battalion_id) WHERE kind = 'DISBAND';

ALTER TABLE battalion_cohort ENABLE ROW LEVEL SECURITY;
ALTER TABLE manpower_transfer ENABLE ROW LEVEL SECURITY;
