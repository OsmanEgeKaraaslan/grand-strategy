-- Phase 9: diplomacy (wars, alliances, peace treaties, tribute), war score and match completion.
-- Running games are not changed: games of rulesets v1–v4 get no diplomacy_game row, so for them
-- every pair of nations stays permanently at war and no game ends (the pre-diplomacy rule).
-- Nation pairs are stored in either orientation; uniqueness uses LEAST/GREATEST so the database and
-- the application never have to agree on a uuid ordering.

ALTER TABLE game_command DROP CONSTRAINT game_command_type_ck;
ALTER TABLE game_command ADD CONSTRAINT game_command_type_ck
    CHECK (command_type IN ('END_TURN', 'BUILD_WORKSHOP', 'RECRUIT', 'MOVE', 'REGROUP', 'REINFORCE', 'DISBAND',
                            'BUILD_FORTRESS', 'REPAIR_FORTRESS', 'REPAIR_WORKSHOP', 'UPGRADE_WORKSHOP',
                            'BUILD_INSTITUTION', 'UNLOCK_TECHNOLOGY', 'UNLOCK_DOCTRINE',
                            'DECLARE_WAR', 'PROPOSE_ALLIANCE', 'ACCEPT_ALLIANCE', 'REJECT_ALLIANCE', 'END_ALLIANCE',
                            'PROPOSE_PEACE', 'ACCEPT_PEACE', 'REJECT_PEACE', 'COUNTER_PEACE', 'CANCEL_PEACE'));

-- A finished game keeps its round and seat so its final state stays readable.
ALTER TABLE game DROP CONSTRAINT game_status_ck;
ALTER TABLE game DROP CONSTRAINT game_turn_state_ck;
ALTER TABLE game
    ADD CONSTRAINT game_status_ck CHECK (status IN ('WAITING_FOR_PLAYERS', 'READY', 'IN_PROGRESS', 'FINISHED')),
    ADD CONSTRAINT game_turn_state_ck CHECK (
        (status IN ('IN_PROGRESS', 'FINISHED')) = (current_round IS NOT NULL AND active_seat IS NOT NULL AND started_at IS NOT NULL));

-- ---------------------------------------------------------------------------------------------
-- Match results (PROVISIONAL Q-T2). One row per finished game, never updated; standings name the
-- player behind each nation for later player statistics (Phase 12).

CREATE TABLE game_result (
    game_id          uuid        PRIMARY KEY REFERENCES game (id),
    finished_round   integer     NOT NULL,
    reason           varchar(32) NOT NULL,
    outcome          varchar(8)  NOT NULL,
    winner_nation_id uuid,
    ruleset_version  varchar(16) NOT NULL,
    statistics       jsonb       NOT NULL,
    finished_at      timestamptz NOT NULL,
    CONSTRAINT game_result_round_ck CHECK (finished_round >= 1),
    CONSTRAINT game_result_outcome_ck CHECK (outcome IN ('WINNER', 'DRAW')),
    CONSTRAINT game_result_winner_ck CHECK ((outcome = 'WINNER') = (winner_nation_id IS NOT NULL)),
    FOREIGN KEY (game_id, winner_nation_id) REFERENCES nation (game_id, id)
);

CREATE TABLE game_result_standing (
    game_id          uuid    NOT NULL REFERENCES game_result (game_id),
    nation_id        uuid    NOT NULL,
    player_id        uuid    NOT NULL REFERENCES player (id),
    rank             integer NOT NULL,
    score            bigint  NOT NULL,
    owned_provinces  integer NOT NULL,
    owned_population bigint  NOT NULL,
    treasury_mg      bigint  NOT NULL,
    PRIMARY KEY (game_id, nation_id),
    CONSTRAINT game_result_standing_values_ck CHECK (rank >= 1 AND score >= 0 AND owned_provinces >= 0
        AND owned_population >= 0 AND treasury_mg >= 0),
    FOREIGN KEY (game_id, nation_id) REFERENCES nation (game_id, id)
);

CREATE INDEX game_result_standing_player_idx ON game_result_standing (player_id);

-- ---------------------------------------------------------------------------------------------
-- Diplomacy. Present only for games whose ruleset has diplomacy (v5+).

CREATE TABLE diplomacy_game (
    game_id         uuid        PRIMARY KEY REFERENCES game (id),
    ruleset_version varchar(16) NOT NULL,
    created_at      timestamptz NOT NULL
);

-- Proposed by one member, ACTIVE once the other accepts. At most one open (PROPOSED or ACTIVE)
-- alliance per pair of nations.
CREATE TABLE alliance (
    id                 uuid        PRIMARY KEY,
    game_id            uuid        NOT NULL REFERENCES diplomacy_game (game_id),
    kind               varchar(16) NOT NULL,
    nation_a_id        uuid        NOT NULL,
    nation_b_id        uuid        NOT NULL,
    proposer_nation_id uuid        NOT NULL,
    status             varchar(16) NOT NULL,
    proposed_round     integer     NOT NULL,
    accepted_round     integer,
    ended_round        integer,
    end_reason         varchar(32),
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    CONSTRAINT alliance_game_id_uq UNIQUE (game_id, id),
    CONSTRAINT alliance_kind_ck CHECK (kind IN ('NORMAL', 'DEFENSIVE')),
    CONSTRAINT alliance_status_ck CHECK (status IN ('PROPOSED', 'ACTIVE', 'REJECTED', 'CANCELLED', 'EXPIRED', 'ENDED')),
    CONSTRAINT alliance_pair_ck CHECK (nation_a_id <> nation_b_id),
    CONSTRAINT alliance_proposer_ck CHECK (proposer_nation_id IN (nation_a_id, nation_b_id)),
    CONSTRAINT alliance_accepted_ck CHECK (status NOT IN ('ACTIVE', 'ENDED') OR accepted_round IS NOT NULL),
    CONSTRAINT alliance_ended_ck CHECK ((status IN ('PROPOSED', 'ACTIVE')) = (ended_round IS NULL)),
    FOREIGN KEY (game_id, nation_a_id) REFERENCES nation (game_id, id),
    FOREIGN KEY (game_id, nation_b_id) REFERENCES nation (game_id, id)
);

CREATE UNIQUE INDEX alliance_open_pair_uq ON alliance (game_id, LEAST(nation_a_id, nation_b_id),
    GREATEST(nation_a_id, nation_b_id)) WHERE status IN ('PROPOSED', 'ACTIVE');

CREATE TABLE war (
    id                 uuid        PRIMARY KEY,
    game_id            uuid        NOT NULL REFERENCES diplomacy_game (game_id),
    attacker_leader_id uuid        NOT NULL,
    defender_leader_id uuid        NOT NULL,
    status             varchar(16) NOT NULL,
    declared_round     integer     NOT NULL,
    declared_turn      integer     NOT NULL,
    ended_round        integer,
    ended_turn         integer,
    end_reason         varchar(32),
    created_at         timestamptz NOT NULL,
    CONSTRAINT war_game_id_uq UNIQUE (game_id, id),
    CONSTRAINT war_sides_ck CHECK (attacker_leader_id <> defender_leader_id),
    CONSTRAINT war_status_ck CHECK (status IN ('ACTIVE', 'ENDED')),
    CONSTRAINT war_ended_ck CHECK ((status = 'ENDED') = (ended_round IS NOT NULL AND ended_turn IS NOT NULL
        AND end_reason IS NOT NULL)),
    CONSTRAINT war_end_reason_ck CHECK (end_reason IS NULL OR end_reason IN ('PEACE_TREATY')),
    FOREIGN KEY (game_id, attacker_leader_id) REFERENCES nation (game_id, id),
    FOREIGN KEY (game_id, defender_leader_id) REFERENCES nation (game_id, id)
);

CREATE TABLE war_participant (
    game_id      uuid        NOT NULL,
    war_id       uuid        NOT NULL,
    nation_id    uuid        NOT NULL,
    side         varchar(8)  NOT NULL,
    joined_round integer     NOT NULL,
    join_reason  varchar(24) NOT NULL,
    PRIMARY KEY (war_id, nation_id),
    CONSTRAINT war_participant_side_ck CHECK (side IN ('ATTACKER', 'DEFENDER')),
    CONSTRAINT war_participant_reason_ck CHECK (join_reason IN ('DECLARED', 'ATTACKED', 'DEFENSIVE_RESPONSE')),
    FOREIGN KEY (game_id, war_id) REFERENCES war (game_id, id),
    FOREIGN KEY (game_id, nation_id) REFERENCES nation (game_id, id)
);

-- Every pair of nations fighting each other in a war. The partial unique index is the database
-- backstop against two simultaneous wars between the same nations.
CREATE TABLE war_front (
    game_id     uuid    NOT NULL,
    war_id      uuid    NOT NULL,
    nation_a_id uuid    NOT NULL,
    nation_b_id uuid    NOT NULL,
    active      boolean NOT NULL,
    PRIMARY KEY (war_id, nation_a_id, nation_b_id),
    CONSTRAINT war_front_pair_ck CHECK (nation_a_id <> nation_b_id),
    FOREIGN KEY (game_id, war_id) REFERENCES war (game_id, id),
    FOREIGN KEY (game_id, nation_a_id) REFERENCES nation (game_id, id),
    FOREIGN KEY (game_id, nation_b_id) REFERENCES nation (game_id, id)
);

CREATE UNIQUE INDEX war_front_active_pair_uq ON war_front (game_id, LEAST(nation_a_id, nation_b_id),
    GREATEST(nation_a_id, nation_b_id)) WHERE active;

-- Battle and siege points, fixed when the event happened. One row per source (battle report or
-- siege) and war: nothing is counted twice.
CREATE TABLE war_score_event (
    id           uuid        PRIMARY KEY,
    game_id      uuid        NOT NULL,
    war_id       uuid        NOT NULL,
    side         varchar(8)  NOT NULL,
    kind         varchar(8)  NOT NULL,
    source_id    uuid        NOT NULL,
    round_number integer     NOT NULL,
    enemy_losses integer     NOT NULL,
    points       integer     NOT NULL,
    created_at   timestamptz NOT NULL,
    CONSTRAINT war_score_event_source_uq UNIQUE (war_id, kind, source_id),
    CONSTRAINT war_score_event_side_ck CHECK (side IN ('ATTACKER', 'DEFENDER')),
    CONSTRAINT war_score_event_kind_ck CHECK (kind IN ('BATTLE', 'SIEGE')),
    CONSTRAINT war_score_event_values_ck CHECK (points >= 0 AND enemy_losses >= 0),
    FOREIGN KEY (game_id, war_id) REFERENCES war (game_id, id)
);

-- Visible only to proposer and recipient. At most one pending proposal per proposer and war.
CREATE TABLE peace_proposal (
    id                    uuid        PRIMARY KEY,
    game_id               uuid        NOT NULL,
    war_id                uuid        NOT NULL,
    proposer_nation_id    uuid        NOT NULL,
    recipient_nation_id   uuid        NOT NULL,
    status                varchar(16) NOT NULL,
    terms                 jsonb       NOT NULL,
    created_round         integer     NOT NULL,
    created_turn          integer     NOT NULL,
    created_state_version bigint      NOT NULL,
    responded_round       integer,
    counter_of_id         uuid,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    CONSTRAINT peace_proposal_game_id_uq UNIQUE (game_id, id),
    CONSTRAINT peace_proposal_parties_ck CHECK (proposer_nation_id <> recipient_nation_id),
    CONSTRAINT peace_proposal_status_ck CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED', 'EXPIRED',
                                                          'SUPERSEDED')),
    CONSTRAINT peace_proposal_responded_ck CHECK ((status = 'PENDING') = (responded_round IS NULL)),
    FOREIGN KEY (game_id, war_id) REFERENCES war (game_id, id),
    FOREIGN KEY (game_id, proposer_nation_id) REFERENCES nation (game_id, id),
    FOREIGN KEY (game_id, recipient_nation_id) REFERENCES nation (game_id, id),
    FOREIGN KEY (game_id, counter_of_id) REFERENCES peace_proposal (game_id, id)
);

CREATE UNIQUE INDEX peace_proposal_pending_uq ON peace_proposal (war_id, proposer_nation_id) WHERE status = 'PENDING';
CREATE INDEX peace_proposal_game_idx ON peace_proposal (game_id, status);

-- A war ends with exactly one treaty; a proposal becomes at most one treaty.
CREATE TABLE peace_treaty (
    id                uuid        PRIMARY KEY,
    game_id           uuid        NOT NULL,
    war_id            uuid        NOT NULL UNIQUE,
    proposal_id       uuid        NOT NULL UNIQUE,
    signed_round      integer     NOT NULL,
    signed_turn       integer     NOT NULL,
    terms             jsonb       NOT NULL,
    truce_until_round integer     NOT NULL,
    created_at        timestamptz NOT NULL,
    CONSTRAINT peace_treaty_game_id_uq UNIQUE (game_id, id),
    FOREIGN KEY (game_id, war_id) REFERENCES war (game_id, id),
    FOREIGN KEY (game_id, proposal_id) REFERENCES peace_proposal (game_id, id)
);

CREATE TABLE truce (
    game_id     uuid    NOT NULL,
    treaty_id   uuid    NOT NULL,
    nation_a_id uuid    NOT NULL,
    nation_b_id uuid    NOT NULL,
    until_round integer NOT NULL,
    PRIMARY KEY (treaty_id, nation_a_id, nation_b_id),
    CONSTRAINT truce_pair_ck CHECK (nation_a_id <> nation_b_id),
    FOREIGN KEY (game_id, treaty_id) REFERENCES peace_treaty (game_id, id),
    FOREIGN KEY (game_id, nation_a_id) REFERENCES nation (game_id, id),
    FOREIGN KEY (game_id, nation_b_id) REFERENCES nation (game_id, id)
);

CREATE TABLE tribute (
    id                 uuid        PRIMARY KEY,
    game_id            uuid        NOT NULL,
    treaty_id          uuid        NOT NULL,
    payer_nation_id    uuid        NOT NULL,
    receiver_nation_id uuid        NOT NULL,
    rate_bps           integer     NOT NULL,
    rounds_total       integer     NOT NULL,
    rounds_charged     integer     NOT NULL DEFAULT 0,
    first_round        integer     NOT NULL,
    total_paid_mg      bigint      NOT NULL DEFAULT 0,
    status             varchar(16) NOT NULL,
    end_reason         varchar(32),
    ended_round        integer,
    created_at         timestamptz NOT NULL,
    CONSTRAINT tribute_game_id_uq UNIQUE (game_id, id),
    CONSTRAINT tribute_parties_ck CHECK (payer_nation_id <> receiver_nation_id),
    CONSTRAINT tribute_values_ck CHECK (rate_bps BETWEEN 1 AND 10000 AND rounds_total >= 1
        AND rounds_charged BETWEEN 0 AND rounds_total AND total_paid_mg >= 0),
    CONSTRAINT tribute_status_ck CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT tribute_completed_ck CHECK ((status = 'COMPLETED') = (rounds_charged = rounds_total)),
    CONSTRAINT tribute_ended_ck CHECK ((status = 'ACTIVE') = (ended_round IS NULL)),
    FOREIGN KEY (game_id, treaty_id) REFERENCES peace_treaty (game_id, id),
    FOREIGN KEY (game_id, payer_nation_id) REFERENCES nation (game_id, id),
    FOREIGN KEY (game_id, receiver_nation_id) REFERENCES nation (game_id, id)
);

-- The primary key makes charging a tribute twice for one round impossible.
CREATE TABLE tribute_payment (
    game_id         uuid        NOT NULL,
    tribute_id      uuid        NOT NULL,
    round_number    integer     NOT NULL,
    gross_income_mg bigint      NOT NULL,
    due_mg          bigint      NOT NULL,
    paid_mg         bigint      NOT NULL,
    created_at      timestamptz NOT NULL,
    PRIMARY KEY (tribute_id, round_number),
    CONSTRAINT tribute_payment_values_ck CHECK (gross_income_mg >= 0 AND due_mg >= 0 AND paid_mg BETWEEN 0 AND due_mg),
    FOREIGN KEY (game_id, tribute_id) REFERENCES tribute (game_id, id)
);

-- Public diplomatic history (wars, alliances formed or ended, treaties and their settlement,
-- tribute, withdrawals). Proposals are private and never appear here.
CREATE TABLE diplomacy_event (
    id                uuid        PRIMARY KEY,
    game_id           uuid        NOT NULL REFERENCES diplomacy_game (game_id),
    round_number      integer     NOT NULL,
    turn_number       integer     NOT NULL,
    kind              varchar(32) NOT NULL,
    actor_nation_id   uuid,
    target_nation_id  uuid,
    war_id            uuid,
    details           jsonb       NOT NULL,
    created_at        timestamptz NOT NULL,
    CONSTRAINT diplomacy_event_kind_ck CHECK (kind IN ('WAR_DECLARED', 'JOINED_WAR', 'ALLIANCE_FORMED',
        'ALLIANCE_ENDED', 'PEACE_SIGNED', 'PROVINCE_CEDED', 'REPARATIONS_PAID', 'POPULATION_TRANSFERRED',
        'TRIBUTE_PAID', 'TRIBUTE_COMPLETED', 'TRIBUTE_CANCELLED', 'FORCES_WITHDRAWN', 'CONTROL_RETURNED')),
    FOREIGN KEY (game_id, actor_nation_id) REFERENCES nation (game_id, id),
    FOREIGN KEY (game_id, target_nation_id) REFERENCES nation (game_id, id),
    FOREIGN KEY (game_id, war_id) REFERENCES war (game_id, id)
);

CREATE INDEX diplomacy_event_game_idx ON diplomacy_event (game_id, round_number DESC, created_at DESC);
