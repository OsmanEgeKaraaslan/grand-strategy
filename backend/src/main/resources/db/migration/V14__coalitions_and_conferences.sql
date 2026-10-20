-- Phase 10B: coalition warfare, attached battalions, calls to arms, peace conferences with
-- revisioned drafts and approvals, separate peace, elimination. Running games keep their rulesets
-- (v1–v5): none of these rows exist for them and their behaviour is unchanged.

-- Commands -----------------------------------------------------------------------------------------

ALTER TABLE game_command DROP CONSTRAINT game_command_type_ck;
ALTER TABLE game_command ADD CONSTRAINT game_command_type_ck
    CHECK (command_type IN ('END_TURN', 'BUILD_WORKSHOP', 'RECRUIT', 'MOVE', 'REGROUP', 'REINFORCE', 'DISBAND',
                            'BUILD_FORTRESS', 'REPAIR_FORTRESS', 'REPAIR_WORKSHOP', 'UPGRADE_WORKSHOP',
                            'BUILD_INSTITUTION', 'UNLOCK_TECHNOLOGY', 'UNLOCK_DOCTRINE',
                            'DECLARE_WAR', 'PROPOSE_ALLIANCE', 'ACCEPT_ALLIANCE', 'REJECT_ALLIANCE', 'END_ALLIANCE',
                            'PROPOSE_PEACE', 'ACCEPT_PEACE', 'REJECT_PEACE', 'COUNTER_PEACE', 'CANCEL_PEACE',
                            'SEND_GOLD', 'ATTACH_BATTALIONS', 'DETACH_BATTALIONS', 'CALL_TO_ARMS', 'ANSWER_CALL',
                            'REQUEST_CONFERENCE', 'ANSWER_CONFERENCE', 'CANCEL_CONFERENCE', 'SUBMIT_DRAFT',
                            'APPROVE_DRAFT', 'REJECT_DRAFT'));

-- Game: paused while a peace conference is active. The turn state is untouched meanwhile.
ALTER TABLE game ADD COLUMN paused_conference_id uuid;
ALTER TABLE game
    ADD CONSTRAINT game_pause_ck CHECK (paused_conference_id IS NULL OR status = 'IN_PROGRESS');

-- Battalions: command by an ally and once-per-round movement (ruleset v6) -------------------------

ALTER TABLE battalion
    ADD COLUMN commander_nation_id uuid,
    ADD COLUMN last_moved_round integer,
    ADD COLUMN last_reinforced_round integer;
ALTER TABLE battalion
    ADD CONSTRAINT battalion_commander_ck CHECK (commander_nation_id IS NULL OR commander_nation_id <> nation_id),
    ADD CONSTRAINT battalion_commander_fk FOREIGN KEY (game_id, commander_nation_id) REFERENCES nation (game_id, id);
CREATE INDEX battalion_commander_idx ON battalion (game_id, commander_nation_id) WHERE commander_nation_id IS NOT NULL;

-- Wars: leaving a war (separate peace, elimination) and new reasons --------------------------------

ALTER TABLE war DROP CONSTRAINT war_end_reason_ck;
ALTER TABLE war ADD CONSTRAINT war_end_reason_ck CHECK (end_reason IS NULL OR end_reason IN ('PEACE_TREATY', 'ELIMINATION'));

ALTER TABLE war_participant DROP CONSTRAINT war_participant_reason_ck;
ALTER TABLE war_participant
    ADD CONSTRAINT war_participant_reason_ck
        CHECK (join_reason IN ('DECLARED', 'ATTACKED', 'DEFENSIVE_RESPONSE', 'CALL_TO_ARMS')),
    ADD COLUMN left_round integer,
    ADD COLUMN left_reason varchar(24);
ALTER TABLE war_participant
    ADD CONSTRAINT war_participant_left_ck CHECK ((left_round IS NULL) = (left_reason IS NULL)),
    ADD CONSTRAINT war_participant_left_reason_ck CHECK (left_reason IS NULL OR left_reason IN ('SEPARATE_PEACE', 'ELIMINATED'));

-- Calls to arms (ruleset v6): the defender asks an ally; the ally honours or declines ---------------

CREATE TABLE call_to_arms (
    id                uuid        PRIMARY KEY,
    game_id           uuid        NOT NULL,
    war_id            uuid        NOT NULL,
    caller_nation_id  uuid        NOT NULL,
    ally_nation_id    uuid        NOT NULL,
    alliance_id       uuid        NOT NULL,
    status            varchar(16) NOT NULL,
    created_round     integer     NOT NULL,
    answered_round    integer,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    CONSTRAINT call_to_arms_game_id_uq UNIQUE (game_id, id),
    CONSTRAINT call_to_arms_parties_ck CHECK (caller_nation_id <> ally_nation_id),
    CONSTRAINT call_to_arms_status_ck CHECK (status IN ('PENDING', 'HONORED', 'DECLINED', 'CANCELLED')),
    CONSTRAINT call_to_arms_answered_ck CHECK ((status = 'PENDING') = (answered_round IS NULL)),
    FOREIGN KEY (game_id, war_id) REFERENCES war (game_id, id),
    FOREIGN KEY (game_id, caller_nation_id) REFERENCES nation (game_id, id),
    FOREIGN KEY (game_id, ally_nation_id) REFERENCES nation (game_id, id),
    FOREIGN KEY (game_id, alliance_id) REFERENCES alliance (game_id, id)
);

CREATE UNIQUE INDEX call_to_arms_pending_uq ON call_to_arms (war_id, ally_nation_id) WHERE status = 'PENDING';

-- Peace conferences (ruleset v6) ----------------------------------------------------------------------
-- A conference negotiates either the general peace (between the two war leaders) or a separate
-- peace (one non-leader participant and the opposing war leader). While one is ACTIVE the game is
-- paused (game.paused_conference_id); at most one is active per game.

CREATE TABLE peace_conference (
    id                     uuid        PRIMARY KEY,
    game_id                uuid        NOT NULL,
    war_id                 uuid        NOT NULL,
    kind                   varchar(16) NOT NULL,
    requester_nation_id    uuid        NOT NULL,
    counterpart_nation_id  uuid        NOT NULL,
    status                 varchar(16) NOT NULL,
    current_revision       integer     NOT NULL DEFAULT 0,
    requested_round        integer     NOT NULL,
    opened_at              timestamptz,
    closed_at              timestamptz,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    CONSTRAINT peace_conference_game_id_uq UNIQUE (game_id, id),
    CONSTRAINT peace_conference_kind_ck CHECK (kind IN ('GENERAL', 'SEPARATE')),
    CONSTRAINT peace_conference_status_ck CHECK (status IN ('REQUESTED', 'ACTIVE', 'REJECTED', 'CANCELLED', 'CONCLUDED')),
    CONSTRAINT peace_conference_parties_ck CHECK (requester_nation_id <> counterpart_nation_id),
    CONSTRAINT peace_conference_revision_ck CHECK (current_revision >= 0),
    FOREIGN KEY (game_id, war_id) REFERENCES war (game_id, id),
    FOREIGN KEY (game_id, requester_nation_id) REFERENCES nation (game_id, id),
    FOREIGN KEY (game_id, counterpart_nation_id) REFERENCES nation (game_id, id)
);

CREATE UNIQUE INDEX peace_conference_active_uq ON peace_conference (game_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX peace_conference_open_uq ON peace_conference (war_id, requester_nation_id, kind)
    WHERE status IN ('REQUESTED', 'ACTIVE');

-- Immutable drafts: every substantive change is a new revision. Approvals name the revision they
-- approve, so an approval can never carry over to changed terms.
CREATE TABLE conference_revision (
    game_id          uuid        NOT NULL,
    conference_id    uuid        NOT NULL,
    revision         integer     NOT NULL,
    author_nation_id uuid        NOT NULL,
    terms            jsonb       NOT NULL,
    required         jsonb       NOT NULL,
    created_at       timestamptz NOT NULL,
    PRIMARY KEY (conference_id, revision),
    CONSTRAINT conference_revision_ck CHECK (revision >= 1),
    FOREIGN KEY (game_id, conference_id) REFERENCES peace_conference (game_id, id),
    FOREIGN KEY (game_id, author_nation_id) REFERENCES nation (game_id, id)
);

CREATE TABLE conference_decision (
    game_id       uuid        NOT NULL,
    conference_id uuid        NOT NULL,
    revision      integer     NOT NULL,
    nation_id     uuid        NOT NULL,
    decision      varchar(16) NOT NULL,
    decided_at    timestamptz NOT NULL,
    PRIMARY KEY (conference_id, revision, nation_id),
    CONSTRAINT conference_decision_ck CHECK (decision IN ('APPROVED', 'REJECTED')),
    FOREIGN KEY (conference_id, revision) REFERENCES conference_revision (conference_id, revision),
    FOREIGN KEY (game_id, nation_id) REFERENCES nation (game_id, id)
);

-- Treaties from conferences: several per war (separate peace), no Phase 9 proposal behind them.
ALTER TABLE peace_treaty DROP CONSTRAINT peace_treaty_war_id_key;
ALTER TABLE peace_treaty ALTER COLUMN proposal_id DROP NOT NULL;
ALTER TABLE peace_treaty
    ADD COLUMN conference_id uuid,
    ADD COLUMN revision integer,
    ADD COLUMN kind varchar(16) NOT NULL DEFAULT 'GENERAL',
    ADD COLUMN exiting_nation_id uuid;
ALTER TABLE peace_treaty
    ADD CONSTRAINT peace_treaty_kind_ck CHECK (kind IN ('GENERAL', 'SEPARATE')),
    ADD CONSTRAINT peace_treaty_source_ck CHECK ((proposal_id IS NULL) <> (conference_id IS NULL)),
    ADD CONSTRAINT peace_treaty_separate_ck CHECK ((kind = 'SEPARATE') = (exiting_nation_id IS NOT NULL)),
    ADD CONSTRAINT peace_treaty_conference_uq UNIQUE (conference_id),
    ADD CONSTRAINT peace_treaty_conference_fk FOREIGN KEY (game_id, conference_id) REFERENCES peace_conference (game_id, id),
    ADD CONSTRAINT peace_treaty_exiting_fk FOREIGN KEY (game_id, exiting_nation_id) REFERENCES nation (game_id, id);
-- Exactly one general treaty per war (the Phase 9 uniqueness, kept for general peace).
CREATE UNIQUE INDEX peace_treaty_general_uq ON peace_treaty (war_id) WHERE kind = 'GENERAL';

-- Diplomatic history kinds --------------------------------------------------------------------------

ALTER TABLE diplomacy_event DROP CONSTRAINT diplomacy_event_kind_ck;
ALTER TABLE diplomacy_event ADD CONSTRAINT diplomacy_event_kind_ck CHECK (kind IN ('WAR_DECLARED', 'JOINED_WAR',
    'ALLIANCE_FORMED', 'ALLIANCE_ENDED', 'PEACE_SIGNED', 'PROVINCE_CEDED', 'REPARATIONS_PAID',
    'POPULATION_TRANSFERRED', 'TRIBUTE_PAID', 'TRIBUTE_COMPLETED', 'TRIBUTE_CANCELLED', 'FORCES_WITHDRAWN',
    'CONTROL_RETURNED', 'GOLD_GIFT', 'CALL_TO_ARMS', 'CALL_HONORED', 'CALL_DECLINED', 'CONFERENCE_REQUESTED',
    'CONFERENCE_OPENED', 'CONFERENCE_REJECTED', 'CONFERENCE_CANCELLED', 'SEPARATE_PEACE', 'NATION_ELIMINATED',
    'WAR_LEADER_CHANGED', 'ATTACHMENTS_ENDED'));
