-- Phase 10A: larger maps with N nations, nations without a human player, elimination markers and
-- direct gold gifts. Running games are unchanged: every existing nation is HUMAN-controlled and
-- nobody is eliminated.

-- Map templates ---------------------------------------------------------------------------------

-- Minimum shared border (map units) that makes two provinces adjacent. Maps that declare it have
-- their adjacency graph verified against the province geometry; NULL keeps older hand-drawn maps
-- on structural validation only.
ALTER TABLE map_template ADD COLUMN min_border_length integer;
ALTER TABLE map_template
    ADD CONSTRAINT map_template_min_border_ck CHECK (min_border_length IS NULL OR min_border_length >= 1);

-- The city is a point inside its province, separate from the boundary geometry and the label.
-- NULL on older maps: their city sits at the label anchor.
ALTER TABLE province_template ADD COLUMN city_x integer, ADD COLUMN city_y integer;
ALTER TABLE province_template
    ADD CONSTRAINT province_template_city_ck CHECK ((city_x IS NULL) = (city_y IS NULL));

-- Starting profile of a faction (balance values per tier live in the ruleset) and whether a human
-- may choose it. Existing two-nation maps: both nations are balanced and playable.
ALTER TABLE nation_template
    ADD COLUMN tier varchar(16) NOT NULL DEFAULT 'POWER',
    ADD COLUMN playable boolean NOT NULL DEFAULT true;
ALTER TABLE nation_template
    ADD CONSTRAINT nation_template_tier_ck CHECK (tier IN ('POWER', 'MAJOR', 'REGIONAL', 'MINOR'));

-- Per-game nations --------------------------------------------------------------------------------

-- A nation without a human player is NPC-controlled (passive until AI arrives in Phase 11).
ALTER TABLE nation ALTER COLUMN game_player_id DROP NOT NULL;
ALTER TABLE nation
    ADD COLUMN control varchar(8) NOT NULL DEFAULT 'HUMAN',
    ADD COLUMN eliminated_round integer;
ALTER TABLE nation
    ADD CONSTRAINT nation_control_ck CHECK (control IN ('HUMAN', 'NPC')),
    ADD CONSTRAINT nation_control_player_ck CHECK ((control = 'HUMAN') = (game_player_id IS NOT NULL)),
    ADD CONSTRAINT nation_eliminated_round_ck CHECK (eliminated_round IS NULL OR eliminated_round >= 1);

-- A human whose nation is eliminated keeps the seat for history but takes no more turns.
ALTER TABLE game_player ADD COLUMN eliminated_round integer;
ALTER TABLE game_player
    ADD CONSTRAINT game_player_eliminated_round_ck CHECK (eliminated_round IS NULL OR eliminated_round >= 1);

-- Commands -----------------------------------------------------------------------------------------

ALTER TABLE game_command DROP CONSTRAINT game_command_type_ck;
ALTER TABLE game_command ADD CONSTRAINT game_command_type_ck
    CHECK (command_type IN ('END_TURN', 'BUILD_WORKSHOP', 'RECRUIT', 'MOVE', 'REGROUP', 'REINFORCE', 'DISBAND',
                            'BUILD_FORTRESS', 'REPAIR_FORTRESS', 'REPAIR_WORKSHOP', 'UPGRADE_WORKSHOP',
                            'BUILD_INSTITUTION', 'UNLOCK_TECHNOLOGY', 'UNLOCK_DOCTRINE',
                            'DECLARE_WAR', 'PROPOSE_ALLIANCE', 'ACCEPT_ALLIANCE', 'REJECT_ALLIANCE', 'END_ALLIANCE',
                            'PROPOSE_PEACE', 'ACCEPT_PEACE', 'REJECT_PEACE', 'COUNTER_PEACE', 'CANCEL_PEACE',
                            'SEND_GOLD'));

-- Gold gifts -----------------------------------------------------------------------------------------

-- A voluntary, final transfer between two treasuries of one game. Recorded once per command.
CREATE TABLE gold_gift (
    id                  uuid        PRIMARY KEY,
    game_id             uuid        NOT NULL REFERENCES diplomacy_game (game_id),
    sender_nation_id    uuid        NOT NULL,
    recipient_nation_id uuid        NOT NULL,
    amount_mg           bigint      NOT NULL,
    round_number        integer     NOT NULL,
    turn_number         integer     NOT NULL,
    created_at          timestamptz NOT NULL,
    CONSTRAINT gold_gift_game_id_uq UNIQUE (game_id, id),
    CONSTRAINT gold_gift_parties_ck CHECK (sender_nation_id <> recipient_nation_id),
    CONSTRAINT gold_gift_amount_ck CHECK (amount_mg > 0),
    FOREIGN KEY (game_id, sender_nation_id) REFERENCES nation (game_id, id),
    FOREIGN KEY (game_id, recipient_nation_id) REFERENCES nation (game_id, id)
);

CREATE INDEX gold_gift_game_idx ON gold_gift (game_id, round_number);

ALTER TABLE diplomacy_event DROP CONSTRAINT diplomacy_event_kind_ck;
ALTER TABLE diplomacy_event ADD CONSTRAINT diplomacy_event_kind_ck CHECK (kind IN ('WAR_DECLARED', 'JOINED_WAR',
    'ALLIANCE_FORMED', 'ALLIANCE_ENDED', 'PEACE_SIGNED', 'PROVINCE_CEDED', 'REPARATIONS_PAID',
    'POPULATION_TRANSFERRED', 'TRIBUTE_PAID', 'TRIBUTE_COMPLETED', 'TRIBUTE_CANCELLED', 'FORCES_WITHDRAWN',
    'CONTROL_RETURNED', 'GOLD_GIFT'));

-- Results: NPC-controlled nations have a standing but no player.
ALTER TABLE game_result_standing ALTER COLUMN player_id DROP NOT NULL;
