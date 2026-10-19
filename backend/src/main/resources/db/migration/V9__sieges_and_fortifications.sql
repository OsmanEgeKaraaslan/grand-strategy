-- Phase 7: fortifications, sieges and repairs. Running games are not changed: they keep their
-- ruleset (v1/v2, which has no fortifications) and get no fortress rows.

ALTER TABLE game_command DROP CONSTRAINT game_command_type_ck;
ALTER TABLE game_command ADD CONSTRAINT game_command_type_ck
    CHECK (command_type IN ('END_TURN', 'BUILD_WORKSHOP', 'RECRUIT', 'MOVE', 'REGROUP', 'REINFORCE', 'DISBAND',
                            'BUILD_FORTRESS', 'REPAIR_FORTRESS', 'REPAIR_WORKSHOP'));

-- Workshop repairs (COMBAT_AND_SIEGE §5.7): DAMAGED → UNDER_REPAIR → ACTIVE at the round end of
-- repair_completes_after_round, after that round's income (Q-E8). An UNDER_REPAIR workshop employs nobody.
ALTER TABLE building DROP CONSTRAINT building_status_ck;
ALTER TABLE building ADD CONSTRAINT building_status_ck
    CHECK (status IN ('UNDER_CONSTRUCTION', 'ACTIVE', 'DAMAGED', 'UNDER_REPAIR'));
ALTER TABLE building ADD COLUMN repair_ordered_round integer;
ALTER TABLE building ADD COLUMN repair_completes_after_round integer;
ALTER TABLE building ADD COLUMN repair_cost_paid_mg bigint NOT NULL DEFAULT 0;
ALTER TABLE building ADD CONSTRAINT building_repair_ck
    CHECK (status <> 'UNDER_REPAIR' OR (repair_completes_after_round IS NOT NULL AND repair_ordered_round IS NOT NULL));
ALTER TABLE building ADD CONSTRAINT building_repair_cost_ck CHECK (repair_cost_paid_mg >= 0);
CREATE INDEX building_repairs_idx ON building (game_id, repair_completes_after_round) WHERE status = 'UNDER_REPAIR';

-- One fortification per city. level 0 only exists while the first level is under construction.
-- Effective level for sieges: level while ACTIVE, 0 while DAMAGED (Q-S4).
CREATE TABLE fortification (
    id                            uuid        PRIMARY KEY,
    game_id                       uuid        NOT NULL REFERENCES game (id),
    city_id                       uuid        NOT NULL,
    level                         integer     NOT NULL,
    status                        varchar(16) NOT NULL,
    integrity                     integer     NOT NULL,
    max_integrity                 integer     NOT NULL,
    project                       varchar(16),
    project_target_level          integer,
    project_ordered_round         integer,
    project_completes_after_round integer,
    project_cost_mg               bigint      NOT NULL DEFAULT 0,
    ruleset_version               varchar(16) NOT NULL,
    created_at                    timestamptz NOT NULL,
    CONSTRAINT fortification_game_id_uq UNIQUE (game_id, id),
    CONSTRAINT fortification_city_uq UNIQUE (game_id, city_id),
    CONSTRAINT fortification_level_ck CHECK (level BETWEEN 0 AND 10),
    CONSTRAINT fortification_status_ck CHECK (status IN ('ACTIVE', 'DAMAGED')),
    CONSTRAINT fortification_integrity_ck CHECK (integrity >= 0 AND integrity <= max_integrity),
    CONSTRAINT fortification_project_ck CHECK (project IS NULL OR project IN ('UPGRADE', 'REPAIR')),
    CONSTRAINT fortification_project_fields_ck CHECK (
        (project IS NULL AND project_completes_after_round IS NULL AND project_ordered_round IS NULL)
        OR (project IS NOT NULL AND project_completes_after_round IS NOT NULL AND project_ordered_round IS NOT NULL)),
    CONSTRAINT fortification_upgrade_ck CHECK (project <> 'UPGRADE' OR project_target_level = level + 1),
    CONSTRAINT fortification_level0_ck CHECK (level > 0 OR coalesce(project, '') = 'UPGRADE'),
    CONSTRAINT fortification_cost_ck CHECK (project_cost_mg >= 0),
    FOREIGN KEY (game_id, city_id) REFERENCES city (game_id, id)
);

CREATE TABLE siege (
    id                   uuid        PRIMARY KEY,
    game_id              uuid        NOT NULL REFERENCES game (id),
    province_state_id    uuid        NOT NULL,
    city_id              uuid        NOT NULL,
    besieger_nation_id   uuid        NOT NULL,
    -- Controller when the siege began; it keeps control until the siege succeeds.
    defender_nation_id   uuid        NOT NULL,
    fortress_level       integer     NOT NULL,
    required_progress    integer     NOT NULL,
    progress             integer     NOT NULL DEFAULT 0,
    status               varchar(16) NOT NULL,
    started_round        integer     NOT NULL,
    started_turn_number  integer     NOT NULL,
    -- Exactly-once guard next to round_resolution: a siege advances at most once per round.
    last_processed_round integer,
    ended_round          integer,
    end_reason           varchar(24),
    artillery_siege      boolean,
    damaged_building_id  uuid,
    population_lost      integer     NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL,
    CONSTRAINT siege_game_id_uq UNIQUE (game_id, id),
    CONSTRAINT siege_status_ck CHECK (status IN ('ACTIVE', 'SUCCEEDED', 'LIFTED')),
    CONSTRAINT siege_end_ck CHECK ((status = 'ACTIVE') = (ended_round IS NULL)),
    CONSTRAINT siege_reason_ck CHECK (end_reason IS NULL OR end_reason IN ('CAPTURED', 'BESIEGERS_GONE')),
    CONSTRAINT siege_progress_ck CHECK (progress >= 0 AND required_progress > 0 AND fortress_level >= 1),
    CONSTRAINT siege_population_ck CHECK (population_lost >= 0),
    CONSTRAINT siege_sides_ck CHECK (besieger_nation_id <> defender_nation_id),
    FOREIGN KEY (game_id, province_state_id) REFERENCES province_state (game_id, id),
    FOREIGN KEY (game_id, city_id) REFERENCES city (game_id, id),
    FOREIGN KEY (game_id, besieger_nation_id) REFERENCES nation (game_id, id),
    FOREIGN KEY (game_id, defender_nation_id) REFERENCES nation (game_id, id),
    FOREIGN KEY (game_id, damaged_building_id) REFERENCES building (game_id, id)
);

-- At most one active siege per province: entering twice can never open a second one.
CREATE UNIQUE INDEX siege_active_uq ON siege (game_id, province_state_id) WHERE status = 'ACTIVE';
CREATE INDEX siege_game_idx ON siege (game_id, started_round DESC);

-- One row per siege per processed round: the history behind the progress bar.
CREATE TABLE siege_round (
    siege_id           uuid    NOT NULL,
    game_id            uuid    NOT NULL,
    round_number       integer NOT NULL,
    artillery_manpower integer NOT NULL,
    progress_added     integer NOT NULL,
    progress_after     integer NOT NULL,
    population_before  integer NOT NULL,
    population_lost    integer NOT NULL,
    PRIMARY KEY (siege_id, round_number),
    CONSTRAINT siege_round_values_ck CHECK (artillery_manpower >= 0 AND progress_added > 0
        AND population_lost >= 0 AND population_lost <= population_before),
    FOREIGN KEY (game_id, siege_id) REFERENCES siege (game_id, id)
);

ALTER TABLE fortification ENABLE ROW LEVEL SECURITY;
ALTER TABLE siege ENABLE ROW LEVEL SECURITY;
ALTER TABLE siege_round ENABLE ROW LEVEL SECURITY;
