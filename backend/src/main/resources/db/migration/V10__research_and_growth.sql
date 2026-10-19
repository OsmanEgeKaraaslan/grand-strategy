-- Phase 8: research institutions, national research and doctrines, Tier 1 → 2 workshop upgrades,
-- and population growth records. Running games are not changed: they keep their ruleset (v1–v3,
-- which has no research and no growth) and get no research rows.

ALTER TABLE game_command DROP CONSTRAINT game_command_type_ck;
ALTER TABLE game_command ADD CONSTRAINT game_command_type_ck
    CHECK (command_type IN ('END_TURN', 'BUILD_WORKSHOP', 'RECRUIT', 'MOVE', 'REGROUP', 'REINFORCE', 'DISBAND',
                            'BUILD_FORTRESS', 'REPAIR_FORTRESS', 'REPAIR_WORKSHOP', 'UPGRADE_WORKSHOP',
                            'BUILD_INSTITUTION', 'UNLOCK_TECHNOLOGY', 'UNLOCK_DOCTRINE'));

-- Workshop upgrade (Q-E3: it keeps producing at its current tier until the upgrade completes at
-- the round end of upgrade_completes_after_round). The workshop keeps its id and its slot.
ALTER TABLE building ADD COLUMN upgrade_target_tier integer;
ALTER TABLE building ADD COLUMN upgrade_ordered_round integer;
ALTER TABLE building ADD COLUMN upgrade_completes_after_round integer;
ALTER TABLE building ADD COLUMN upgrade_cost_paid_mg bigint NOT NULL DEFAULT 0;
ALTER TABLE building ADD CONSTRAINT building_upgrade_ck CHECK (
    (upgrade_target_tier IS NULL AND upgrade_completes_after_round IS NULL AND upgrade_ordered_round IS NULL)
    OR (upgrade_target_tier = level + 1 AND upgrade_completes_after_round IS NOT NULL
        AND upgrade_ordered_round IS NOT NULL AND status = 'ACTIVE'));
ALTER TABLE building ADD CONSTRAINT building_upgrade_cost_ck CHECK (upgrade_cost_paid_mg >= 0);
CREATE INDEX building_upgrades_idx ON building (game_id, upgrade_completes_after_round)
    WHERE upgrade_target_tier IS NOT NULL;

-- Research institutions (one of each type per city in the MVP).
CREATE TABLE institution (
    id                    uuid        PRIMARY KEY,
    game_id               uuid        NOT NULL REFERENCES game (id),
    city_id               uuid        NOT NULL,
    type                  varchar(24) NOT NULL,
    status                varchar(24) NOT NULL,
    ordered_round         integer     NOT NULL,
    completes_after_round integer     NOT NULL,
    cost_paid_mg          bigint      NOT NULL,
    ruleset_version       varchar(16) NOT NULL,
    created_at            timestamptz NOT NULL,
    CONSTRAINT institution_game_id_uq UNIQUE (game_id, id),
    CONSTRAINT institution_one_per_type_uq UNIQUE (game_id, city_id, type),
    CONSTRAINT institution_type_ck CHECK (type IN ('MILITARY_ACADEMY', 'UNIVERSITY', 'BARRACKS')),
    CONSTRAINT institution_status_ck CHECK (status IN ('UNDER_CONSTRUCTION', 'ACTIVE')),
    CONSTRAINT institution_timing_ck CHECK (ordered_round >= 1 AND completes_after_round >= ordered_round),
    CONSTRAINT institution_cost_ck CHECK (cost_paid_mg >= 0),
    FOREIGN KEY (game_id, city_id) REFERENCES city (game_id, id)
);

CREATE INDEX institution_completion_idx ON institution (game_id, completes_after_round)
    WHERE status = 'UNDER_CONSTRUCTION';

-- A nation's research: three point pools and two technology levels (doctrines below).
CREATE TABLE nation_research (
    nation_id       uuid        PRIMARY KEY,
    game_id         uuid        NOT NULL REFERENCES game (id),
    military_points integer     NOT NULL DEFAULT 0,
    civilian_points integer     NOT NULL DEFAULT 0,
    doctrine_points integer     NOT NULL DEFAULT 0,
    military_level  integer     NOT NULL DEFAULT 1,
    civilian_level  integer     NOT NULL DEFAULT 1,
    ruleset_version varchar(16) NOT NULL,
    CONSTRAINT nation_research_points_ck CHECK (military_points >= 0 AND civilian_points >= 0 AND doctrine_points >= 0),
    CONSTRAINT nation_research_levels_ck CHECK (military_level >= 1 AND civilian_level >= 1),
    FOREIGN KEY (game_id, nation_id) REFERENCES nation (game_id, id)
);

CREATE TABLE nation_doctrine (
    id             uuid        PRIMARY KEY,
    game_id        uuid        NOT NULL REFERENCES game (id),
    nation_id      uuid        NOT NULL,
    doctrine_key   varchar(32) NOT NULL,
    tier           integer     NOT NULL,
    cost_points    integer     NOT NULL,
    unlocked_round integer     NOT NULL,
    created_at     timestamptz NOT NULL,
    CONSTRAINT nation_doctrine_key_uq UNIQUE (game_id, nation_id, doctrine_key),
    CONSTRAINT nation_doctrine_values_ck CHECK (tier >= 1 AND cost_points > 0 AND unlocked_round >= 1),
    FOREIGN KEY (game_id, nation_id) REFERENCES nation (game_id, id)
);

-- Points each nation gained at each round end: the primary key forbids paying research twice.
CREATE TABLE research_round_report (
    game_id         uuid    NOT NULL,
    round_number    integer NOT NULL,
    nation_id       uuid    NOT NULL,
    military_gained integer NOT NULL,
    civilian_gained integer NOT NULL,
    doctrine_gained integer NOT NULL,
    PRIMARY KEY (game_id, round_number, nation_id),
    CONSTRAINT research_round_report_values_ck CHECK (military_gained >= 0 AND civilian_gained >= 0 AND doctrine_gained >= 0),
    FOREIGN KEY (game_id, round_number) REFERENCES round_resolution (game_id, round_number),
    FOREIGN KEY (game_id, nation_id) REFERENCES nation (game_id, id)
);

-- Technology and doctrine purchases: history, and the recorded answer to a retried command.
CREATE TABLE research_unlock (
    id           uuid        PRIMARY KEY,
    game_id      uuid        NOT NULL REFERENCES game (id),
    nation_id    uuid        NOT NULL,
    kind         varchar(16) NOT NULL,
    subject      varchar(32) NOT NULL,
    level        integer,
    cost_points  integer     NOT NULL,
    round_number integer     NOT NULL,
    created_at   timestamptz NOT NULL,
    CONSTRAINT research_unlock_kind_ck CHECK (kind IN ('TECHNOLOGY', 'DOCTRINE')),
    CONSTRAINT research_unlock_cost_ck CHECK (cost_points > 0),
    FOREIGN KEY (game_id, nation_id) REFERENCES nation (game_id, id)
);

-- Civilians added to each city at each round end (audit; the primary key forbids growing twice).
CREATE TABLE population_growth (
    game_id           uuid    NOT NULL,
    round_number      integer NOT NULL,
    city_id           uuid    NOT NULL,
    population_before integer NOT NULL,
    growth            integer NOT NULL,
    PRIMARY KEY (game_id, round_number, city_id),
    CONSTRAINT population_growth_values_ck CHECK (population_before >= 0 AND growth >= 0),
    FOREIGN KEY (game_id, round_number) REFERENCES round_resolution (game_id, round_number),
    FOREIGN KEY (game_id, city_id) REFERENCES city (game_id, id)
);

ALTER TABLE institution ENABLE ROW LEVEL SECURITY;
ALTER TABLE nation_research ENABLE ROW LEVEL SECURITY;
ALTER TABLE nation_doctrine ENABLE ROW LEVEL SECURITY;
ALTER TABLE research_round_report ENABLE ROW LEVEL SECURITY;
ALTER TABLE research_unlock ENABLE ROW LEVEL SECURITY;
ALTER TABLE population_growth ENABLE ROW LEVEL SECURITY;
