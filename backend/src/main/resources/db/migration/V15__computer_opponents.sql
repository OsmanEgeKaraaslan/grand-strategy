-- Phase 11: computer-controlled nations (ruleset v7, docs/AI.md). Every nation no human chose gets a
-- seat in the turn order, held by a computer player that has no Supabase identity and can therefore
-- never be reached by an authenticated request. Its commands go through the same command pipeline.
-- Running games are unchanged: their game-controlled nations stay passive NPC placeholders.

-- Computer players: no Supabase user, a reserved display name ("AI#..." is not a valid human name).
ALTER TABLE player
    ADD COLUMN kind varchar(8) NOT NULL DEFAULT 'HUMAN',
    ALTER COLUMN supabase_user_id DROP NOT NULL,
    ADD CONSTRAINT player_kind_ck CHECK (kind IN ('HUMAN', 'COMPUTER')),
    ADD CONSTRAINT player_kind_identity_ck CHECK ((kind = 'HUMAN') = (supabase_user_id IS NOT NULL));

ALTER TABLE game_player ADD COLUMN computer boolean NOT NULL DEFAULT false;

-- Computer seats come after the human seats, so the active seat may exceed max_players (humans).
ALTER TABLE game
    DROP CONSTRAINT game_active_seat_ck,
    ADD CONSTRAINT game_active_seat_ck CHECK (active_seat IS NULL OR active_seat >= 0);

-- A nation is HUMAN (a player's seat), AI (a computer seat, v7) or NPC (passive, v6).
ALTER TABLE nation
    DROP CONSTRAINT nation_control_ck,
    DROP CONSTRAINT nation_control_player_ck,
    ADD CONSTRAINT nation_control_ck CHECK (control IN ('HUMAN', 'AI', 'NPC')),
    ADD CONSTRAINT nation_control_player_ck CHECK ((control IN ('HUMAN', 'AI')) = (game_player_id IS NOT NULL));

-- Progress of one computer turn: written in the same transaction as each command it issues, so a
-- restarted backend continues exactly where it stopped and never repeats a committed action.
CREATE TABLE ai_turn (
    game_id         uuid         NOT NULL REFERENCES game (id),
    turn_number     integer      NOT NULL,
    round_number    integer      NOT NULL,
    game_player_id  uuid         NOT NULL,
    nation_id       uuid         NOT NULL,
    status          varchar(8)   NOT NULL,
    goal            varchar(16),
    actions         integer      NOT NULL DEFAULT 0,
    failures        integer      NOT NULL DEFAULT 0,
    observations    integer      NOT NULL DEFAULT 0,
    failed          jsonb        NOT NULL DEFAULT '[]'::jsonb,
    last_error      varchar(300),
    started_at      timestamptz  NOT NULL,
    finished_at     timestamptz,
    duration_ms     bigint,
    CONSTRAINT ai_turn_pk PRIMARY KEY (game_id, turn_number),
    CONSTRAINT ai_turn_player_fk FOREIGN KEY (game_id, game_player_id) REFERENCES game_player (game_id, id),
    CONSTRAINT ai_turn_nation_fk FOREIGN KEY (game_id, nation_id) REFERENCES nation (game_id, id),
    CONSTRAINT ai_turn_status_ck CHECK (status IN ('RUNNING', 'DONE')),
    CONSTRAINT ai_turn_counts_ck CHECK (actions >= 0 AND failures >= 0 AND observations >= 0)
);

-- Why a computer nation took its major decisions (development diagnostics; not public history).
CREATE TABLE ai_decision (
    id            uuid         PRIMARY KEY,
    game_id       uuid         NOT NULL REFERENCES game (id),
    round_number  integer      NOT NULL,
    turn_number   integer      NOT NULL,
    nation_id     uuid         NOT NULL,
    kind          varchar(24)  NOT NULL,
    summary       varchar(300) NOT NULL,
    details       jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at    timestamptz  NOT NULL,
    CONSTRAINT ai_decision_nation_fk FOREIGN KEY (game_id, nation_id) REFERENCES nation (game_id, id)
);

CREATE INDEX ai_decision_game_idx ON ai_decision (game_id, round_number, nation_id);

ALTER TABLE ai_turn ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_decision ENABLE ROW LEVEL SECURITY;
