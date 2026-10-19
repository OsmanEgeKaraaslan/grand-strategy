-- Phase 4: treasuries, workshops (incl. construction projects) and per-round economy reports.
-- Balance values are not stored here; they come from the game's ruleset version (rulesets/v1).

-- ---------------------------------------------------------------------------------------------
-- Every game keeps the ruleset version it was created with. All existing games are on v1.
ALTER TABLE game ADD COLUMN ruleset_version varchar(16);
UPDATE game SET ruleset_version = 'v1';
ALTER TABLE game ALTER COLUMN ruleset_version SET NOT NULL;

-- Commands may reference what they produced (e.g. the new workshop) so retries can be replayed.
ALTER TABLE game_command ADD COLUMN result_ref uuid;
ALTER TABLE game_command DROP CONSTRAINT game_command_type_ck;
ALTER TABLE game_command ADD CONSTRAINT game_command_type_ck CHECK (command_type IN ('END_TURN', 'BUILD_WORKSHOP'));

-- ---------------------------------------------------------------------------------------------
-- One treasury per nation per game, in milligold (1 gold = 1000 mg). It can never be negative:
-- actions are rejected when unaffordable and round-end deficits floor it at 0 (Q-E1b).
CREATE TABLE nation_economy (
    nation_id   uuid    PRIMARY KEY,
    game_id     uuid    NOT NULL REFERENCES game (id),
    treasury_mg bigint  NOT NULL,
    in_arrears  boolean NOT NULL DEFAULT false,
    CONSTRAINT nation_economy_game_nation_uq UNIQUE (game_id, nation_id),
    CONSTRAINT nation_economy_treasury_ck CHECK (treasury_mg >= 0),
    FOREIGN KEY (game_id, nation_id) REFERENCES nation (game_id, id)
);

-- Buildings of a city. Phase 4 has workshops only (fortresses arrive with sieges). A construction
-- project is a building row in status UNDER_CONSTRUCTION; it becomes ACTIVE at the round end of
-- completes_after_round, after that round's income (Q-E8).
CREATE TABLE building (
    id                    uuid        PRIMARY KEY,
    game_id               uuid        NOT NULL REFERENCES game (id),
    city_id               uuid        NOT NULL,
    type                  varchar(16) NOT NULL,
    level                 integer     NOT NULL,
    status                varchar(24) NOT NULL,
    ordered_round         integer     NOT NULL,
    completes_after_round integer,
    cost_paid_mg          bigint      NOT NULL,
    created_at            timestamptz NOT NULL,
    CONSTRAINT building_game_id_uq UNIQUE (game_id, id),
    CONSTRAINT building_type_ck CHECK (type IN ('WORKSHOP')),
    -- Workshop tiers 1 and 2 (Tier 2 stays unbuildable until civilian technology exists).
    CONSTRAINT building_level_ck CHECK (level BETWEEN 1 AND 2),
    CONSTRAINT building_status_ck CHECK (status IN ('UNDER_CONSTRUCTION', 'ACTIVE', 'DAMAGED')),
    CONSTRAINT building_ordered_round_ck CHECK (ordered_round >= 0),
    CONSTRAINT building_project_ck CHECK (status <> 'UNDER_CONSTRUCTION' OR completes_after_round IS NOT NULL),
    CONSTRAINT building_completion_ck CHECK (completes_after_round IS NULL OR completes_after_round >= ordered_round),
    CONSTRAINT building_cost_ck CHECK (cost_paid_mg >= 0),
    FOREIGN KEY (game_id, city_id) REFERENCES city (game_id, id)
);

CREATE INDEX building_city_idx ON building (game_id, city_id);
CREATE INDEX building_projects_idx ON building (game_id, completes_after_round) WHERE status = 'UNDER_CONSTRUCTION';

-- What each nation received at each round end. The primary key is a second guard (next to
-- round_resolution) against paying a round twice.
CREATE TABLE economy_round_report (
    game_id           uuid        NOT NULL,
    round_number      integer     NOT NULL,
    nation_id         uuid        NOT NULL,
    gross_income_mg   bigint      NOT NULL,
    upkeep_mg         bigint      NOT NULL,
    deficit_mg        bigint      NOT NULL,
    treasury_after_mg bigint      NOT NULL,
    in_arrears        boolean     NOT NULL,
    created_at        timestamptz NOT NULL,
    PRIMARY KEY (game_id, round_number, nation_id),
    CONSTRAINT economy_round_report_amounts_ck CHECK (
        gross_income_mg >= 0 AND upkeep_mg >= 0 AND deficit_mg >= 0 AND treasury_after_mg >= 0),
    FOREIGN KEY (game_id, nation_id) REFERENCES nation (game_id, id),
    FOREIGN KEY (game_id, round_number) REFERENCES round_resolution (game_id, round_number)
);

-- ---------------------------------------------------------------------------------------------
-- Backfill for games started before Phase 4, using the ruleset v1 starting state that new games
-- receive (Q-W3): 50 gold per nation and one active Tier 1 workshop in each nation's capital.
-- Rounds already completed are not paid retroactively.
INSERT INTO nation_economy (nation_id, game_id, treasury_mg, in_arrears)
SELECT n.id, n.game_id, 50000, false
FROM nation n;

INSERT INTO building (id, game_id, city_id, type, level, status, ordered_round, completes_after_round, cost_paid_mg, created_at)
SELECT gen_random_uuid(), n.game_id, c.id, 'WORKSHOP', 1, 'ACTIVE', 0, NULL, 0, now()
FROM nation n
JOIN nation_template nt ON nt.id = n.nation_template_id
JOIN province_state ps ON ps.game_id = n.game_id AND ps.province_template_id = nt.capital_province_id
JOIN city c ON c.game_id = ps.game_id AND c.province_state_id = ps.id;

ALTER TABLE nation_economy ENABLE ROW LEVEL SECURITY;
ALTER TABLE building ENABLE ROW LEVEL SECURITY;
ALTER TABLE economy_round_report ENABLE ROW LEVEL SECURITY;
